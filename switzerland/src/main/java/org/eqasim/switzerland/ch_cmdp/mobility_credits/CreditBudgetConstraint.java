package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.Collection;
import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;

import com.google.inject.Inject;

/**
 * Look-ahead budget constraint. A tour option of a resident is rejected if
 * credits of earlier tours + credits of this option + minimum need of all later trips > budget
 *
 * unless the option uses neither car nor car passenger: those are never rejected, so every tour keeps a feasible
 * option. Such overspending is counted in {@link CreditStats}.
 */
public class CreditBudgetConstraint implements TourConstraint {
	private static final double TOLERANCE = 1e-9;

	private final DayProfiles.DayProfile day;
	private final CreditCalculator calculator;
	private final CreditStats stats;

	CreditBudgetConstraint(DayProfiles.DayProfile day, CreditCalculator calculator, CreditStats stats) {
		this.day = day;
		this.calculator = calculator;
		this.stats = stats;
	}

	@Override
	public boolean validateBeforeEstimation(List<DiscreteModeChoiceTrip> tour, List<String> modes,
			List<List<String>> previousModes) {
		return true; // credits are only known after routing
	}

	@Override
	public boolean validateAfterEstimation(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
			List<TourCandidate> previousCandidates) {
		if (day == null) {
			return true; // exempt
		}

		double used = CreditTourCandidate.creditsOf(previousCandidates, calculator);
		double credits = CreditTourCandidate.creditsOf(candidate, tour, calculator);
		double futureNeed = day.getNeedFrom(CreditTourCandidate.tripsOf(previousCandidates) + tour.size());

		if (used + credits + futureNeed <= day.getBudget() + TOLERANCE) {
			return true;
		}

		if (!CreditTourCandidate.usesCar(candidate)) {
			stats.countSafetyNetAccept();
			return true;
		}

		stats.countRejected();
		return false;
	}

	public static class Factory implements TourConstraintFactory {
		private final ResidentFilter residentFilter;
		private final DayProfiles profiles;
		private final CreditCalculator calculator;
		private final CreditStats stats;

		@Inject
		public Factory(ResidentFilter residentFilter, DayProfiles profiles, CreditCalculator calculator,
				CreditStats stats) {
			this.residentFilter = residentFilter;
			this.profiles = profiles;
			this.calculator = calculator;
			this.stats = stats;
		}

		@Override
		public TourConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> planTrips,
				Collection<String> availableModes) {
			DayProfiles.DayProfile day = residentFilter.isResident(person) ? profiles.get(person, planTrips) : null;
			return new CreditBudgetConstraint(day, calculator, stats);
		}
	}
}
