package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

/**
 * Decides who takes part in the credit scheme: persons whose attribute equals the configured value (default
 * {@code cantonName = fribourg}). Everyone else, and calls without a person, are exempt.
 */
public class ResidentFilter {
	private final IdSet<Person> residents;

	private ResidentFilter(IdSet<Person> residents) {
		this.residents = residents;
	}

	public static ResidentFilter create(Population population, String attribute, String value) {
		IdSet<Person> residents = new IdSet<>(Person.class);

		for (Person person : population.getPersons().values()) {
			Object attributeValue = person.getAttributes().getAttribute(attribute);

			if (attributeValue != null && value.equals(String.valueOf(attributeValue))) {
				residents.add(person.getId());
			}
		}

		return new ResidentFilter(residents);
	}

	public boolean isResident(Person person) {
		return person != null && residents.contains(person.getId());
	}

	public int getNumberOfResidents() {
		return residents.size();
	}
}
