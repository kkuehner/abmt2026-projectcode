package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;

/**
 * Per participant: the budget (after the minimum-need guarantee) and the minimum credits the
 * remaining trips of the day need. Activity chains don't change in this setup, so a profile is computed once
 * per person and reused in every iteration. 
 */
public class DayProfiles {
	private final CreditCalculator calculator;
	private final CreditBudget budget;
	private final AcceptableDistances distances;
	private final double beelineFactor;
	private final Map<Id<Person>, DayProfile> profiles = new ConcurrentHashMap<>();

	public DayProfiles(CreditCalculator calculator, CreditBudget budget, AcceptableDistances distances,
			double beelineFactor) {
		this.calculator = calculator;
		this.budget = budget;
		this.distances = distances;
		this.beelineFactor = beelineFactor;
	}

	/** Profile from the trips DMC is about to choose modes for. */
	public DayProfile get(Person person, List<DiscreteModeChoiceTrip> trips) {
		return profiles.computeIfAbsent(person.getId(), id -> {
			List<Activity[]> odPairs = new ArrayList<>();

			for (DiscreteModeChoiceTrip trip : trips) {
				odPairs.add(new Activity[] { trip.getOriginActivity(), trip.getDestinationActivity() });
			}

			return build(person, odPairs);
		});
	}

	/** Profile from the person's selected plan (used outside of mode choice, e.g. for output). */
	public DayProfile get(Person person) {
		return profiles.computeIfAbsent(person.getId(), id -> {
			List<Activity[]> odPairs = new ArrayList<>();

			for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
				odPairs.add(new Activity[] { trip.getOriginActivity(), trip.getDestinationActivity() });
			}

			return build(person, odPairs);
		});
	}

	private DayProfile build(Person person, List<Activity[]> odPairs) {
		double walkMax_km = distances.getWalkMax_km(person);
		double bikeMax_km = distances.getBikeMax_km(person);

		double[] needFrom = new double[odPairs.size() + 1];

		for (int i = odPairs.size() - 1; i >= 0; i--) {
			Activity origin = odPairs.get(i)[0];
			Activity destination = odPairs.get(i)[1];
			double distance_km = estimateDistance_km(origin.getCoord(), destination.getCoord());
			double departureTime = origin.getEndTime().orElse(0.0);

			double need;

			if (distance_km <= walkMax_km) {
				need = 0.0;
			} else if (distance_km <= bikeMax_km) {
				need = calculator.getBikeTripCredits();
			} else {
				need = calculator.getPtTripCredits(departureTime);
			}

			needFrom[i] = needFrom[i + 1] + need;
		}

		double baseBudget = budget.getBudget(person);
		return new DayProfile(baseBudget, Math.max(baseBudget, needFrom[0]), needFrom);
	}

	private double estimateDistance_km(Coord origin, Coord destination) {
		if (origin == null || destination == null) {
			return Double.POSITIVE_INFINITY; // unknown: assume PT is needed
		}

		return CoordUtils.calcEuclideanDistance(origin, destination) * beelineFactor / 1000.0;
	}

	public static final class DayProfile {
		private final double baseBudget;
		private final double budget;
		private final double[] needFrom;

		DayProfile(double baseBudget, double budget, double[] needFrom) {
			this.baseBudget = baseBudget;
			this.budget = budget;
			this.needFrom = needFrom;
		}

		/** Budget after the minimum-need guarantee. */
		public double getBudget() {
			return budget;
		}

		/** True if the budget was raised to the day's minimum need. */
		public boolean isRaised() {
			return budget > baseBudget;
		}

		/** Minimum credits needed by the trips from position {@code tripIndex} to the end of the day. */
		public double getNeedFrom(int tripIndex) {
			if (tripIndex >= needFrom.length) {
				return 0.0;
			}

			return needFrom[Math.max(0, tripIndex)];
		}

		public int getNumberOfTrips() {
			return needFrom.length - 1;
		}
	}
}
