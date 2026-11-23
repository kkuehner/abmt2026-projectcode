package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import org.matsim.api.core.v01.population.Person;

// Daily credit budget of a participant before the minimum-need guarantee 
public interface CreditBudget {
	double getBudget(Person person);

	/**
	 * Equal budget for everyone: {@code max(ptTrips PT trips, car round trip of 2 x carRoundTripOneWay_km)}, both at
	 * off-peak and uncongested prices.
	 */
	static CreditBudget equal(CreditCalculator calculator, MobilityCreditsParameters parameters) {
		double ptAllowance = parameters.budget.ptTrips * calculator.getOffPeakPtTripCredits();
		double carAllowance = 2.0
				* calculator.getUncongestedCarTripCredits(parameters.budget.carRoundTripOneWay_km);
		double budget = Math.max(ptAllowance, carAllowance);
		return person -> budget;
	}
}
