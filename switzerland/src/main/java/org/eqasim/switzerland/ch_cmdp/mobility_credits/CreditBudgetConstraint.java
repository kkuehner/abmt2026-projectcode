package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.Collection;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.FallbackTourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraint;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;

import com.google.inject.Inject;

/**
 * Look-ahead budget constraint. A tour option of a resident is rejected if
 * credits of earlier tours + credits of this option + minimum need of all later trips > budget
 *
 * unless the option uses neither car nor car passenger. If all otherwise feasible options are rejected,
 * the tour model can select the one with the smallest projected budget overrun.
 */
public class CreditBudgetConstraint implements FallbackTourConstraint {
	private static final Logger logger = LogManager.getLogger(CreditBudgetConstraint.class);
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

		if (fallbackCost(tour, candidate, previousCandidates) <= TOLERANCE) {
			return true;
		}

		if (!CreditTourCandidate.usesCar(candidate)) {
			stats.countSafetyNetAccept();
			return true;
		}

		stats.countRejected();
		return false;
	}

	@Override
	public double fallbackCost(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
			List<TourCandidate> previousCandidates) {
		double used = CreditTourCandidate.creditsOf(previousCandidates, calculator);
		double credits = CreditTourCandidate.creditsOf(candidate, tour, calculator);
		double futureNeed = day.getNeedFrom(CreditTourCandidate.tripsOf(previousCandidates) + tour.size());
		return used + credits + futureNeed - day.getBudget();
	}

	@Override
	public void reportFallback(Person person, int tripIndex, TourCandidate candidate, double cost) {
		stats.countForcedOverflow();
		logger.warn("Forced credit budget overflow for resident {}, tour starting at trip {}, modes {}, "
				+ "projected overrun {} credits (including estimated credits for later trips)",
				person.getId(), tripIndex, candidate.getTripCandidates().stream().map(t -> t.getMode()).toList(), cost);
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
