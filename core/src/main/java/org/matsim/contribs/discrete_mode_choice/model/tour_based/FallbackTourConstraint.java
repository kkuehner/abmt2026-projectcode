package org.matsim.contribs.discrete_mode_choice.model.tour_based;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/** A constraint that can be relaxed when it alone rules out every feasible tour. */
public interface FallbackTourConstraint extends TourConstraint {
	double fallbackCost(List<DiscreteModeChoiceTrip> tour, TourCandidate candidate,
			List<TourCandidate> previousCandidates);

	void reportFallback(Person person, int tripIndex, TourCandidate candidate, double cost);
}
