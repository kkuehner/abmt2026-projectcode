package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.List;

import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.RoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/**
 * A tour option that remembers its credits, so the look-ahead doesn't have to price earlier tours again. Created by
 * {@link CreditTourEstimator}.
 */
public class CreditTourCandidate implements TourCandidate {
	private final double utility;
	private final List<TripCandidate> tripCandidates;
	private final double credits;

	public CreditTourCandidate(double utility, List<TripCandidate> tripCandidates, double credits) {
		this.utility = utility;
		this.tripCandidates = tripCandidates;
		this.credits = credits;
	}

	@Override
	public double getUtility() {
		return utility;
	}

	@Override
	public List<TripCandidate> getTripCandidates() {
		return tripCandidates;
	}

	public double getCredits() {
		return credits;
	}

	/** Credits of a tour option, read from the candidate if possible, otherwise computed from its routes. */
	static double creditsOf(TourCandidate candidate, List<DiscreteModeChoiceTrip> tourTrips,
			CreditCalculator calculator) {
		if (candidate instanceof CreditTourCandidate creditCandidate) {
			return creditCandidate.getCredits();
		}

		double credits = 0.0;
		List<TripCandidate> trips = candidate.getTripCandidates();

		for (int i = 0; i < trips.size(); i++) {
			TripCandidate trip = trips.get(i);
			List<? extends PlanElement> elements = trip instanceof RoutedTripCandidate routed
					? routed.getRoutedPlanElements()
					: List.of();
			double departureTime = tourTrips != null && i < tourTrips.size() ? tourTrips.get(i).getDepartureTime()
					: 0.0;
			credits += calculator.calculateCredits(trip.getMode(), departureTime, elements);
		}

		return credits;
	}

	/** Credits used by all earlier tours of the day. */
	static double creditsOf(List<TourCandidate> previous, CreditCalculator calculator) {
		double credits = 0.0;

		for (TourCandidate candidate : previous) {
			credits += creditsOf(candidate, null, calculator);
		}

		return credits;
	}

	/** Number of trips covered by the earlier tours = position of the current tour's first trip in the day. */
	static int tripsOf(List<TourCandidate> previous) {
		int trips = 0;

		for (TourCandidate candidate : previous) {
			trips += candidate.getTripCandidates().size();
		}

		return trips;
	}

	static boolean usesCar(TourCandidate candidate) {
		for (TripCandidate trip : candidate.getTripCandidates()) {
			if (CreditCalculator.isCarMode(trip.getMode())) {
				return true;
			}
		}

		return false;
	}
}
