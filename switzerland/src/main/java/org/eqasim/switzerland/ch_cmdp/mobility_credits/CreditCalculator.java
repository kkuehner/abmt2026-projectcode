package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.ArrayList;
import java.util.List;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.util.TravelTime;

/**
 * Computes the mobility credits a trip costs (see Project_Plan.md, "Cost per trip").
 *
 * <ul>
 * <li>car: constant + distanceFactor * ln(1 + km) + congestion price of every link the car enters. The price is
 * (credits/km of the link's congestion level) * link length, with the level taken from the baseline congestion
 * ratio at the time the car enters the link (from the given car travel times).</li>
 * <li>car_passenger: the car formula on the passenger's route, divided by the occupancy</li>
 * <li>pt: constant, times the peak factor if the trip departs in a peak window</li>
 * <li>bike: constant</li>
 * <li>walk and all other modes (truck, outside, remote_walk, ...): 0</li>
 * </ul>
 *
 * "_loop" variants (e.g. car_loop) are priced like their base mode. Only legs of the trip's own mode
 * count towards distance and congestion; access/egress walks are free.
 * <p>
 * Whether a person takes part in the scheme is not decided here; this class only prices trips.
 * It is stateless after construction and safe to use from several threads.
 */
public class CreditCalculator {
	public static final String CAR_PASSENGER = "car_passenger";
	static final String LOOP_SUFFIX = "_loop";

	private static final double SECONDS_PER_DAY = 24.0 * 3600.0;

	private final MobilityCreditsParameters parameters;
	private final LinkCongestionTable congestion;
	private final Network network;
	private final TravelTime carTravelTime;

	public CreditCalculator(MobilityCreditsParameters parameters, LinkCongestionTable congestion, Network network,
			TravelTime carTravelTime) {
		parameters.validate();
		this.parameters = parameters;
		this.congestion = congestion;
		this.network = network;
		this.carTravelTime = carTravelTime;
	}

	public double calculateCredits(String mode, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
		return calculateCredits(mode, trip.getDepartureTime(), elements);
	}

	/**
	 * @param mode          the trip's main mode
	 * @param departureTime departure time of the trip, used where legs carry no departure time
	 * @param elements      the routed legs and interaction activities of the trip
	 */
	public double calculateCredits(String mode, double departureTime, List<? extends PlanElement> elements) {
		switch (baseMode(mode)) {
		case TransportMode.car:
			return calculateCarCredits(mode, departureTime, elements);
		case CAR_PASSENGER:
			return calculateCarCredits(mode, departureTime, elements) / parameters.carPassenger.occupancy;
		case TransportMode.pt:
			return calculatePtCredits(firstDepartureTime(departureTime, elements));
		case TransportMode.bike:
			return parameters.bike.constant_credits;
		default:
			return 0.0;
		}
	}

	/**
	 * Congestion credits for entering the link at the given time. Also meant for car routing, so that route
	 * choice and mode choice use the same prices.
	 */
	public double getLinkCredits(Link link, double enterTime) {
		double ratio = congestion.getCongestionRatio(link.getId(), enterTime);
		return getCongestionPrice_credits_km(ratio) * link.getLength() / 1000.0;
	}

	double getCongestionPrice_credits_km(double congestionRatio) {
		if (congestionRatio >= parameters.car.congestionThresholdHigh) {
			return parameters.car.highCongestion_credits_km;
		} else if (congestionRatio >= parameters.car.congestionThresholdMedium) {
			return parameters.car.mediumCongestion_credits_km;
		} else {
			return 0.0;
		}
	}

	boolean isPtPeak(double time) {
		double hour = (((time % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY) / 3600.0;
		var pt = parameters.pt;
		return (hour >= pt.morningPeakStart_h && hour < pt.morningPeakEnd_h)
				|| (hour >= pt.eveningPeakStart_h && hour < pt.eveningPeakEnd_h);
	}

	private double calculatePtCredits(double departureTime) {
		double factor = isPtPeak(departureTime) ? parameters.pt.peakFactor : 1.0;
		return parameters.pt.constant_credits * factor;
	}

	private double calculateCarCredits(String mode, double departureTime, List<? extends PlanElement> elements) {
		String base = baseMode(mode);
		double distance_m = 0.0;
		double congestionCredits = 0.0;
		double time = departureTime;

		for (PlanElement element : elements) {
			if (!(element instanceof Leg leg)) {
				continue;
			}

			double legDeparture = leg.getDepartureTime().orElse(time);
			Route route = leg.getRoute();
			boolean isMainLeg = leg.getMode().equals(mode) || leg.getMode().equals(base);

			if (isMainLeg && route instanceof NetworkRoute networkRoute) {
				double enterTime = legDeparture;

				for (Link link : enteredLinks(networkRoute)) {
					distance_m += link.getLength();
					congestionCredits += getLinkCredits(link, enterTime);
					enterTime += carTravelTime.getLinkTravelTime(link, enterTime, null, null);
				}

				time = enterTime;
			} else {
				if (isMainLeg && route != null && Double.isFinite(route.getDistance())) {
					distance_m += route.getDistance(); // e.g. teleported car_loop legs
				}

				time = legDeparture + leg.getTravelTime().orElse(0.0);
			}
		}

		var car = parameters.car;
		return car.constant_credits + car.distanceFactor_credits * Math.log1p(distance_m / 1000.0)
				+ congestionCredits;
	}

	/**
	 * Links a vehicle enters on this route: all route links plus the end link. The start link is not entered,
	 * because a departing vehicle starts at its end.
	 */
	private List<Link> enteredLinks(NetworkRoute route) {
		List<Id<Link>> linkIds = new ArrayList<>(route.getLinkIds());
		boolean stationary = linkIds.isEmpty() && route.getStartLinkId().equals(route.getEndLinkId());

		if (!stationary) {
			linkIds.add(route.getEndLinkId());
		}

		return linkIds.stream().map(this::getLink).toList();
	}

	private Link getLink(Id<Link> linkId) {
		Link link = network.getLinks().get(linkId);

		if (link == null) {
			throw new IllegalStateException("Route contains link " + linkId + " which is not in the network");
		}

		return link;
	}

	private static double firstDepartureTime(double fallback, List<? extends PlanElement> elements) {
		for (PlanElement element : elements) {
			if (element instanceof Leg leg) {
				return leg.getDepartureTime().orElse(fallback);
			}
		}

		return fallback;
	}

	static String baseMode(String mode) {
		return mode.endsWith(LOOP_SUFFIX) ? mode.substring(0, mode.length() - LOOP_SUFFIX.length()) : mode;
	}
}
