package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import org.matsim.api.core.v01.population.Person;

/**
 * Longest distance a person is assumed to walk or cycle, used by the look-ahead to estimate the minimum credits
 * of the remaining trips. Phase 3 may draw these per agent.
 */
public interface AcceptableDistances {
	double getWalkMax_km(Person person);

	double getBikeMax_km(Person person);

	static AcceptableDistances fixed(MobilityCreditsParameters parameters) {
		double walk = parameters.budget.walkMax_km;
		double bike = parameters.budget.bikeMax_km;

		return new AcceptableDistances() {
			@Override
			public double getWalkMax_km(Person person) {
				return walk;
			}

			@Override
			public double getBikeMax_km(Person person) {
				return bike;
			}
		};
	}
}
