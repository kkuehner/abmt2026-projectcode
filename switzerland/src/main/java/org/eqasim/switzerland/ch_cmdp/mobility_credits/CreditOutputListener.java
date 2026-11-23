package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * After each iteration: prices the executed day of every resident and logs a summary. Every
 * {@code mobilityCredits.outputInterval} iterations (and in the last one) it writes
 * {@code mobility_credits_persons.csv.gz} (one row per resident), and in the last iteration also
 * {@code mobility_credits_trips.csv.gz} (one row per trip).
 * <p>
 * Also useful without a budget: the persons file of a pricing-only run shows how many credits residents use
 * today, which is the input for checking the budget.
 */
@Singleton
public class CreditOutputListener implements IterationEndsListener {
	private static final Logger logger = LogManager.getLogger(CreditOutputListener.class);

	static final String PERSONS_FILE = "mobility_credits_persons.csv.gz";
	static final String TRIPS_FILE = "mobility_credits_trips.csv.gz";

	private final Population population;
	private final ResidentFilter residentFilter;
	private final DayProfiles profiles;
	private final CreditCalculator calculator;
	private final OutputDirectoryHierarchy outputHierarchy;
	private final CreditStats stats;
	private final int outputInterval;

	@Inject
	public CreditOutputListener(Population population, ResidentFilter residentFilter, DayProfiles profiles,
			CreditCalculator calculator, OutputDirectoryHierarchy outputHierarchy, CreditStats stats, Config config) {
		this.population = population;
		this.residentFilter = residentFilter;
		this.profiles = profiles;
		this.calculator = calculator;
		this.outputHierarchy = outputHierarchy;
		this.stats = stats;
		this.outputInterval = MobilityCreditsConfigGroup.get(config).getOutputInterval();
	}

	/** Credits of one executed trip. */
	record TripCredits(String mode, double departureTime, double credits) {
	}

	/** Prices the executed trips of a person's selected plan. */
	List<TripCredits> priceDay(Person person) {
		List<TripCredits> trips = new ArrayList<>();

		for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
			List<Leg> legs = trip.getLegsOnly();

			if (legs.isEmpty()) {
				continue;
			}

			String mode = TripStructureUtils.getRoutingMode(legs.get(0));

			if (mode == null) {
				mode = legs.get(0).getMode();
			}

			double departureTime = legs.get(0).getDepartureTime()
					.orElse(trip.getOriginActivity().getEndTime().orElse(0.0));
			double credits = calculator.calculateCredits(mode, departureTime, trip.getTripElements());
			trips.add(new TripCredits(mode, departureTime, credits));
		}

		return trips;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		boolean write = event.isLastIteration()
				|| (outputInterval > 0 && event.getIteration() % outputInterval == 0);

		int residents = 0;
		int overBudget = 0;
		int raised = 0;
		int tight = 0;
		double totalUsed = 0.0;

		try (BufferedWriter persons = write
				? IOUtils.getBufferedWriter(outputHierarchy.getIterationFilename(event.getIteration(), PERSONS_FILE))
				: null;
				BufferedWriter trips = event.isLastIteration()
						? IOUtils.getBufferedWriter(
								outputHierarchy.getIterationFilename(event.getIteration(), TRIPS_FILE))
						: null) {
			if (persons != null) {
				persons.write("person_id;budget;budget_raised;credits_used;balance;over_budget;"
						+ "car_credits;car_passenger_credits;pt_credits;bike_credits\n");
			}

			if (trips != null) {
				trips.write("person_id;trip_index;mode;departure_time;credits\n");
			}

			for (Person person : population.getPersons().values()) {
				if (!residentFilter.isResident(person)) {
					continue;
				}

				DayProfiles.DayProfile day = profiles.get(person);
				List<TripCredits> dayTrips = priceDay(person);

				double used = 0.0;
				double[] byMode = new double[4]; // car, car_passenger, pt, bike

				for (int i = 0; i < dayTrips.size(); i++) {
					TripCredits trip = dayTrips.get(i);
					used += trip.credits();

					switch (CreditCalculator.baseMode(trip.mode())) {
					case "car" -> byMode[0] += trip.credits();
					case CreditCalculator.CAR_PASSENGER -> byMode[1] += trip.credits();
					case "pt" -> byMode[2] += trip.credits();
					case "bike" -> byMode[3] += trip.credits();
					default -> {
					}
					}

					if (trips != null) {
						trips.write(String.format(Locale.US, "%s;%d;%s;%.0f;%.4f\n", person.getId(), i, trip.mode(),
								trip.departureTime(), trip.credits()));
					}
				}

				double balance = day.getBudget() - used;
				boolean over = balance < -1e-9;

				residents++;
				totalUsed += used;
				overBudget += over ? 1 : 0;
				raised += day.isRaised() ? 1 : 0;
				tight += balance < 0.1 * day.getBudget() ? 1 : 0;

				if (persons != null) {
					persons.write(String.format(Locale.US, "%s;%.4f;%b;%.4f;%.4f;%b;%.4f;%.4f;%.4f;%.4f\n",
							person.getId(), day.getBudget(), day.isRaised(), used, balance, over, byMode[0],
							byMode[1], byMode[2], byMode[3]));
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		long[] counters = stats.getAndReset();
		logger.info(String.format(Locale.US,
				"Mobility credits it.%d: %d residents, mean %.2f credits/day, %.1f%% with < 10%% budget left, "
						+ "%.1f%% over budget, %.1f%% budget raised to minimum need; look-ahead: %d options rejected, "
						+ "%d accepted by safety net",
				event.getIteration(), residents, residents == 0 ? 0.0 : totalUsed / residents, percent(tight, residents),
				percent(overBudget, residents), percent(raised, residents), counters[0], counters[1]));
	}

	private static double percent(int count, int total) {
		return total == 0 ? 0.0 : 100.0 * count / total;
	}
}
