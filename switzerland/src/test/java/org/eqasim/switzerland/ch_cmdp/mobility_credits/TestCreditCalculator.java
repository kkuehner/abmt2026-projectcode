package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.eqasim.core.simulation.mode_choice.ParameterDefinition;
import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/**
 * Network: start (100 m) -> middle (1 km) -> end (2 km), all at 10 m/s. A car departing on "start" enters
 * "middle" at the departure time and "end" 100 s later; the start link is not charged.
 */
public class TestCreditCalculator {
	private static final double DELTA = 1e-9;
	private static final double H = 3600.0;

	private static final Id<Link> START = Id.createLinkId("start");
	private static final Id<Link> MIDDLE = Id.createLinkId("middle");
	private static final Id<Link> END = Id.createLinkId("end");

	private Network network;
	private MobilityCreditsParameters parameters;
	private final TravelTime freeSpeed = new FreeSpeedTravelTime();

	@Before
	public void setUp() {
		network = NetworkUtils.createNetwork();
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(1100, 0));
		Node n4 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n4"), new Coord(3100, 0));
		NetworkUtils.createAndAddLink(network, START, n1, n2, 100.0, 10.0, 1000.0, 1.0);
		NetworkUtils.createAndAddLink(network, MIDDLE, n2, n3, 1000.0, 10.0, 1000.0, 1.0);
		NetworkUtils.createAndAddLink(network, END, n3, n4, 2000.0, 10.0, 1000.0, 1.0);

		parameters = MobilityCreditsParameters.buildDefault();
		parameters.car.constant_credits = 1.0;
		parameters.car.distanceFactor_credits = 2.0;
		parameters.car.congestionThresholdMedium = 1.2;
		parameters.car.congestionThresholdHigh = 1.5;
		parameters.car.mediumCongestion_credits_km = 0.5;
		parameters.car.highCongestion_credits_km = 1.0;
		parameters.pt.constant_credits = 0.5;
		parameters.pt.peakFactor = 1.5;
		parameters.bike.constant_credits = 0.1;
		parameters.carPassenger.occupancy = 2.0;
	}

	/**
	 * 7-8h: middle is highly congested (1 km * 1.0 = 1.0 credits), end is medium congested (2 km * 0.5 = 1.0
	 * credits).
	 */
	private LinkCongestionTable morningPeak() {
		return new LinkCongestionTable.Builder() //
				.add(MIDDLE, 7 * H, 8 * H, 1.6) //
				.add(END, 7 * H, 8 * H, 1.3) //
				.build();
	}

	private CreditCalculator calculator(LinkCongestionTable table) {
		return new CreditCalculator(parameters, table, network, freeSpeed);
	}

	private static Leg networkLeg(String mode, double departureTime) {
		Leg leg = PopulationUtils.createLeg(mode);
		NetworkRoute route = RouteUtils.createLinkNetworkRouteImpl(START, List.of(MIDDLE), END);
		leg.setRoute(route);

		if (Double.isFinite(departureTime)) {
			leg.setDepartureTime(departureTime);
		}

		return leg;
	}

	private static Leg walkLeg(double travelTime) {
		Leg leg = PopulationUtils.createLeg("walk");
		leg.setTravelTime(travelTime);
		return leg;
	}

	/** constant + 2 * ln(1 + 3 km): middle and end are driven, start is not. */
	private static final double UNCONGESTED_CAR = 1.0 + 2.0 * Math.log1p(3.0);

	@Test
	public void carOffPeakPaysConstantAndLogDistance() {
		var calculator = calculator(morningPeak());
		double credits = calculator.calculateCredits("car", 12 * H, List.of(networkLeg("car", 12 * H)));
		assertEquals(UNCONGESTED_CAR, credits, DELTA);
	}

	@Test
	public void carInPeakPaysPerKmOnCongestedLinks() {
		var calculator = calculator(morningPeak());
		double credits = calculator.calculateCredits("car", 7.5 * H, List.of(networkLeg("car", 7.5 * H)));
		// middle: 1 km * 1.0 (high), end: 2 km * 0.5 (medium)
		assertEquals(UNCONGESTED_CAR + 1.0 + 1.0, credits, DELTA);
	}

	@Test
	public void linksArePricedAtTheirOwnEntryTime() {
		var calculator = calculator(morningPeak());
		// enters middle at 7:59:00 (in bin), end 100 s later at 8:00:40 (bin is over)
		double departure = 8 * H - 60.0;
		double credits = calculator.calculateCredits("car", departure, List.of(networkLeg("car", departure)));
		assertEquals(UNCONGESTED_CAR + 1.0, credits, DELTA);
	}

	@Test
	public void legWithoutDepartureTimeStartsAfterAccessWalk() {
		var calculator = calculator(morningPeak());
		// trip departs 6:59:30, 60 s access walk -> car enters middle at 7:00:30
		double departure = 7 * H - 30.0;
		List<PlanElement> elements = List.of(walkLeg(60.0), networkLeg("car", Double.NaN));
		assertEquals(UNCONGESTED_CAR + 2.0, calculator.calculateCredits("car", departure, elements), DELTA);
	}

	@Test
	public void accessAndEgressWalksAreFree() {
		var calculator = calculator(LinkCongestionTable.empty());
		List<PlanElement> elements = List.of(walkLeg(300.0), networkLeg("car", 12 * H), walkLeg(300.0));
		assertEquals(UNCONGESTED_CAR, calculator.calculateCredits("car", 12 * H, elements), DELTA);
	}

	@Test
	public void carPassengerPaysCarCreditsDividedByOccupancy() {
		var calculator = calculator(morningPeak());
		double car = calculator.calculateCredits("car", 7.5 * H, List.of(networkLeg("car", 7.5 * H)));
		double passenger = calculator.calculateCredits("car_passenger", 7.5 * H,
				List.of(networkLeg("car_passenger", 7.5 * H)));
		assertEquals(car / 2.0, passenger, DELTA);
	}

	@Test
	public void loopModesAreFree() {
		var calculator = calculator(morningPeak());
		Leg leg = PopulationUtils.createLeg("car_loop");
		var route = RouteUtils.createGenericRouteImpl(START, START);
		route.setDistance(3000.0);
		leg.setRoute(route);
		assertEquals(0.0, calculator.calculateCredits("car_loop", 7.5 * H, List.of(leg)), DELTA);
		assertEquals(0.0, calculator.calculateCredits("car_passenger_loop", 7.5 * H, List.of(leg)), DELTA);
		assertEquals(0.0, calculator.calculateCredits("pt_loop", 8 * H, List.of(leg)), DELTA);
		assertEquals(0.0, calculator.calculateCredits("bike_loop", 8 * H, List.of(leg)), DELTA);
		assertFalse(CreditCalculator.isCarMode("car_loop"));
		assertFalse(CreditCalculator.isCarMode("car_passenger_loop"));
		assertTrue(CreditCalculator.isCarMode("car_passenger"));
	}

	@Test
	public void stationaryCarRoutePaysOnlyConstant() {
		var calculator = calculator(morningPeak());
		Leg leg = PopulationUtils.createLeg("car");
		leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(MIDDLE, List.of(), MIDDLE));
		assertEquals(1.0, calculator.calculateCredits("car", 7.5 * H, List.of(leg)), DELTA);
	}

	@Test
	public void ptPaysPeakFactorInPeakWindowsIncludingAfterMidnight() {
		var calculator = calculator(LinkCongestionTable.empty());
		Leg leg = PopulationUtils.createLeg("pt");
		assertEquals(0.5, calculator.calculateCredits("pt", 12 * H, List.of(leg)), DELTA);
		assertEquals(0.75, calculator.calculateCredits("pt", 8 * H, List.of(leg)), DELTA);
		assertEquals(0.75, calculator.calculateCredits("pt", 17 * H, List.of(leg)), DELTA);
		assertEquals(0.5, calculator.calculateCredits("pt", 9 * H, List.of(leg)), DELTA); // end is exclusive
		assertEquals(0.75, calculator.calculateCredits("pt", 24 * H + 8 * H, List.of(leg)), DELTA);
	}

	@Test
	public void ptUsesDepartureOfFirstLeg() {
		var calculator = calculator(LinkCongestionTable.empty());
		Leg access = walkLeg(60.0);
		access.setDepartureTime(8 * H);
		// trip departure (fallback) is off-peak, but the first leg departs at 8:00
		assertEquals(0.75, calculator.calculateCredits("pt", 12 * H, List.of(access)), DELTA);
	}

	@Test
	public void bikeWalkAndOtherModes() {
		var calculator = calculator(LinkCongestionTable.empty());
		assertEquals(0.1, calculator.calculateCredits("bike", 8 * H, List.of()), DELTA);
		assertEquals(0.0, calculator.calculateCredits("walk", 8 * H, List.of()), DELTA);
		assertEquals(0.0, calculator.calculateCredits("truck", 8 * H, List.of()), DELTA);
		assertEquals(0.0, calculator.calculateCredits("outside", 8 * H, List.of()), DELTA);
	}

	@Test
	public void linkCreditsMatchCongestionLevels() {
		var calculator = calculator(morningPeak());
		Link middle = network.getLinks().get(MIDDLE);
		assertEquals(1.0, calculator.getLinkCredits(middle, 7.5 * H), DELTA);
		assertEquals(0.0, calculator.getLinkCredits(middle, 6.5 * H), DELTA);
		assertEquals(0.0, calculator.getCongestionPrice_credits_km(1.19), DELTA);
		assertEquals(0.5, calculator.getCongestionPrice_credits_km(1.2), DELTA);
		assertEquals(1.0, calculator.getCongestionPrice_credits_km(1.5), DELTA);
	}

	@Test
	public void unknownLinkInRouteFails() {
		var calculator = calculator(LinkCongestionTable.empty());
		Leg leg = PopulationUtils.createLeg("car");
		leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(START, List.of(Id.createLinkId("ghost")), END));
		assertThrows(IllegalStateException.class, () -> calculator.calculateCredits("car", 0.0, List.of(leg)));
	}

	@Test
	public void parametersCanBeSetByDottedNamesAndAreValidated() {
		ParameterDefinition.applyMap(parameters,
				Map.<String, Object>of("car.constant_credits", "3.5", "carPassenger.occupancy", "1.5"));
		assertEquals(3.5, parameters.car.constant_credits, DELTA);
		assertEquals(1.5, parameters.carPassenger.occupancy, DELTA);

		parameters.carPassenger.occupancy = 0.5;
		assertThrows(IllegalStateException.class, () -> calculator(LinkCongestionTable.empty()));
	}
}
