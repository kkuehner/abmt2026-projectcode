package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.List;

import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/**
 * The credit term of the utility: {@code β_credit · credits} per trip, for residents only.
 * <p>
 * Credits are valued in CHF ({@code creditValue_CHF}) and converted with the plain cost coefficient
 * {@code betaCost_u_MU}, <b>without</b> the income/distance interaction of the base cost term, so a credit
 * hurts every agent equally. {@code EqasimUtilityEstimator} subtracts the returned penalty from the utility.
 */
public class CreditUtilityPenalty implements UtilityPenalty {
	private final CreditCalculator calculator;
	private final ResidentFilter residentFilter;
	/** Utility lost per credit (positive). */
	private final double penaltyPerCredit_u;

	public CreditUtilityPenalty(CreditCalculator calculator, ResidentFilter residentFilter, double betaCost_u_MU,
			double creditValue_CHF) {
		if (!(betaCost_u_MU <= 0.0)) {
			throw new IllegalArgumentException("betaCost_u_MU must be <= 0, got " + betaCost_u_MU);
		}

		if (!(creditValue_CHF >= 0.0) || !Double.isFinite(creditValue_CHF)) {
			throw new IllegalArgumentException("creditValue_CHF must be finite and >= 0, got " + creditValue_CHF);
		}

		this.calculator = calculator;
		this.residentFilter = residentFilter;
		this.penaltyPerCredit_u = -betaCost_u_MU * creditValue_CHF;
	}

	@Override
	public double calculatePenalty(String mode, Person person, DiscreteModeChoiceTrip trip,
			List<? extends PlanElement> elements) {
		if (!residentFilter.isResident(person)) {
			return 0.0;
		}

		return penaltyPerCredit_u * calculator.calculateCredits(mode, trip, elements);
	}

	public double getPenaltyPerCredit_u() {
		return penaltyPerCredit_u;
	}
}
