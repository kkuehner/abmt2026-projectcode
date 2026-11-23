package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;

/**
 * Tour utility = utility of the wrapped estimator (sum of trip utilities incl. the credit term) minus the scarcity
 * penalty f:
 * f = weight * penaltyPerCredit * c_tour * s^exponent,   s = (c_tour + future need) / balance, clamped to [0, 1]
 *
 * Non-residents get the wrapped utility unchanged. Residents' options are returned as {@link CreditTourCandidate}
 * so the look-ahead can read their credits.
 */
public class CreditTourEstimator implements TourEstimator {
	private final TourEstimator delegate;
	private final CreditCalculator calculator;
	private final ResidentFilter residentFilter;
	private final DayProfiles profiles;
	private final double penaltyPerCredit_u;
	private final double weight;
	private final double exponent;

	public CreditTourEstimator(TourEstimator delegate, CreditCalculator calculator, ResidentFilter residentFilter,
			DayProfiles profiles, double penaltyPerCredit_u, double weight, double exponent) {
		this.delegate = delegate;
		this.calculator = calculator;
		this.residentFilter = residentFilter;
		this.profiles = profiles;
		this.penaltyPerCredit_u = penaltyPerCredit_u;
		this.weight = weight;
		this.exponent = exponent;
	}

	@Override
	public TourCandidate estimateTour(Person person, List<String> modes, List<DiscreteModeChoiceTrip> tourTrips,
			List<TourCandidate> previousTours) {
		TourCandidate base = delegate.estimateTour(person, modes, tourTrips, previousTours);

		if (!residentFilter.isResident(person)) {
			return base;
		}

		double credits = CreditTourCandidate.creditsOf(base, tourTrips, calculator);
		double used = CreditTourCandidate.creditsOf(previousTours, calculator);
		int nextTrip = CreditTourCandidate.tripsOf(previousTours) + tourTrips.size();

		DayProfiles.DayProfile day = profiles.get(person);
		double futureNeed = day.getNeedFrom(nextTrip);
		double balance = day.getBudget() - used;

		double penalty = calculatePenalty(credits, futureNeed, balance);
		return new CreditTourCandidate(base.getUtility() - penalty, base.getTripCandidates(), credits);
	}

	double calculatePenalty(double tourCredits, double futureNeed, double balance) {
		if (tourCredits <= 0.0 || weight == 0.0) {
			return 0.0;
		}

		double scarcity = balance <= 0.0 ? 1.0 : Math.min(1.0, Math.max(0.0, (tourCredits + futureNeed) / balance));
		return weight * penaltyPerCredit_u * tourCredits * Math.pow(scarcity, exponent);
	}
}
