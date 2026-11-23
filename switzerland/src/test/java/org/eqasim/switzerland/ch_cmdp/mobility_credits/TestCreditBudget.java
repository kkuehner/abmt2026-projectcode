package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.DefaultTourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourCandidate;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.DefaultRoutedTripCandidate;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.candidates.TripCandidate;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

/** Budget, day profile, look-ahead constraint, scarcity penalty and DMC config switch (Phase 1b). */
public class TestCreditBudget {
	private static final double DELTA = 1e-9;
	private static final double H = 3600.0;

	private MobilityCreditsParameters parameters;

	@Before
	public void setUp() {
		parameters = MobilityCreditsParameters.buildDefault();
		parameters.car.constant_credits = 1.0;
		parameters.car.distanceFactor_credits = 2.0;
		parameters.pt.constant_credits = 0.5;
		parameters.pt.peakFactor = 1.5;
		parameters.bike.constant_credits = 0.1;
		parameters.budget.ptTrips = 6.0;
		parameters.budget.carRoundTripOneWay_km = 10.0;
		parameters.budget.walkMax_km = 2.0;
		parameters.budget.bikeMax_km = 8.0;
		parameters.budget.beelineFactor = 1.3;
	}

	private CreditCalculator calculator() {
		return new CreditCalculator(parameters, LinkCongestionTable.empty(), NetworkUtils.createNetwork(),
				new FreeSpeedTravelTime());
	}

	private DayProfiles profiles() {
		var calculator = calculator();
		return new DayProfiles(calculator, CreditBudget.equal(calculator, parameters),
				AcceptableDistances.fixed(parameters), parameters.budget.beelineFactor);
	}

	/** Person whose plan visits the given x coordinates (m) in order, leaving each activity at the given hour. */
	private static Person person(String id, double[] x_m, double[] endTime_h) {
		Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId(id));
		Plan plan = PopulationUtils.createPlan(person);

		for (int i = 0; i < x_m.length; i++) {
			var activity = PopulationUtils.createAndAddActivityFromCoord(plan, i == 0 ? "home" : "other",
					new Coord(x_m[i], 0.0));

			if (i < x_m.length - 1) {
				activity.setEndTime(endTime_h[i] * H);
				PopulationUtils.createAndAddLeg(plan, "walk");
			}
		}

		person.addPlan(plan);
		person.setSelectedPlan(plan);
		return person;
	}

	@Test
	public void equalBudgetIsMaxOfPtTripsAndCarRoundTrip() {
		var calculator = calculator();
		double carRoundTrip = 2.0 * (1.0 + 2.0 * Math.log1p(10.0)); // ~11.59
		assertEquals(carRoundTrip, CreditBudget.equal(calculator, parameters).getBudget(null), DELTA);

		parameters.budget.ptTrips = 30.0; // 30 * 0.5 = 15 > 11.59
		assertEquals(15.0, CreditBudget.equal(calculator, parameters).getBudget(null), DELTA);
	}

	@Test
	public void dayProfileUsesCheapestAcceptableModePerRemainingTrip() {
		// home -> 1 km (walk, 0) -> 6 km away (bike, 0.1) -> 26 km away at 8h (PT peak, 0.75) -> home (PT, 0.5)
		// straight-line distances x 1.3: 1.3 km, 6.5 km, 26 km, 33.8 km
		Person person = person("p", new double[] { 0, 1000, 6000, 26000, 0 }, new double[] { 6, 7, 8, 12 });
		var day = profiles().get(person);

		assertEquals(4, day.getNumberOfTrips());
		assertEquals(0.0 + 0.1 + 0.75 + 0.5, day.getNeedFrom(0), DELTA);
		assertEquals(0.75 + 0.5, day.getNeedFrom(2), DELTA);
		assertEquals(0.0, day.getNeedFrom(4), DELTA);
		assertEquals(0.0, day.getNeedFrom(10), DELTA);
		assertFalse(day.isRaised());
		assertEquals(2.0 * (1.0 + 2.0 * Math.log1p(10.0)), day.getBudget(), DELTA);
	}

	@Test
	public void budgetIsRaisedToMinimumNeedOfTheDay() {
		parameters.pt.constant_credits = 5.0; // 3 long PT trips need 15 credits
		parameters.budget.ptTrips = 0.0;
		parameters.budget.carRoundTripOneWay_km = 0.0; // base budget = 2 * car constant = 2
		Person person = person("p", new double[] { 0, 20000, 40000, 0 }, new double[] { 12, 13, 14 });
		var day = profiles().get(person);

		assertTrue(day.isRaised());
		assertEquals(15.0, day.getBudget(), DELTA);
	}

	// ----- look-ahead constraint -----

	private static TripCandidate trip(String mode) {
		return new DefaultRoutedTripCandidate(0.0, mode, List.of(), 0.0);
	}

	private static CreditTourCandidate tour(double credits, String... modes) {
		return new CreditTourCandidate(0.0, java.util.Arrays.stream(modes).map(TestCreditBudget::trip).toList(),
				credits);
	}

	private static List<DiscreteModeChoiceTrip> trips(int n) {
		return java.util.stream.IntStream.range(0, n).mapToObj(i -> mock(DiscreteModeChoiceTrip.class)).toList();
	}

	/** Two tours of two trips; every trip needs at least 0.5 credits (PT); budget 6. */
	private static DayProfiles.DayProfile twoTourDay() {
		return new DayProfiles.DayProfile(6.0, 6.0, new double[] { 2.0, 1.5, 1.0, 0.5, 0.0 });
	}

	@Test
	public void morningCarMustLeaveEnoughForTheEvening() {
		var stats = new CreditStats();
		var constraint = new CreditBudgetConstraint(twoTourDay(), calculator(), stats);

		// morning: 5.0 + evening minimum 1.0 = 6.0 <= 6 -> ok
		assertTrue(constraint.validateAfterEstimation(trips(2), tour(5.0, "car", "car"), List.of()));
		// morning: 5.5 + 1.0 > 6 -> the evening could not be covered -> rejected
		assertFalse(constraint.validateAfterEstimation(trips(2), tour(5.5, "car", "car"), List.of()));

		// evening after a 5.0 morning: 1.0 left
		List<TourCandidate> morning = List.of(tour(5.0, "car", "car"));
		assertTrue(constraint.validateAfterEstimation(trips(2), tour(1.0, "car", "car"), morning));
		assertFalse(constraint.validateAfterEstimation(trips(2), tour(1.5, "car_passenger", "walk"), morning));

		assertEquals(2L, stats.getAndReset()[0]);
	}

	@Test
	public void optionsWithoutCarAreNeverRejected() {
		var stats = new CreditStats();
		var constraint = new CreditBudgetConstraint(twoTourDay(), calculator(), stats);
		List<TourCandidate> morning = List.of(tour(6.0, "car", "car"));

		assertTrue(constraint.validateAfterEstimation(trips(2), tour(1.0, "pt", "bike"), morning));
		long[] counters = stats.getAndReset();
		assertEquals(0L, counters[0]);
		assertEquals(1L, counters[1]);
	}

	@Test
	public void exemptAgentsAreNeverRestricted() {
		var constraint = new CreditBudgetConstraint(null, calculator(), new CreditStats());
		assertTrue(constraint.validateAfterEstimation(trips(2), tour(100.0, "car", "car"), List.of()));
	}

	// ----- scarcity penalty f -----

	private CreditTourEstimator estimator(TourEstimator delegate, ResidentFilter filter) {
		return new CreditTourEstimator(delegate, calculator(), filter, profiles(), 0.1, 1.0, 2.0);
	}

	@Test
	public void scarcityPenaltyGrowsAsCreditsGetTight() {
		var estimator = estimator(null, null);
		// f = 1 * 0.1 * credits * s^2, s = (credits + future) / balance
		assertEquals(0.1 * 2.0 * 0.25, estimator.calculatePenalty(2.0, 1.0, 6.0), DELTA); // s = 0.5
		assertEquals(0.1 * 2.0, estimator.calculatePenalty(2.0, 1.0, 3.0), DELTA); // s = 1
		assertEquals(0.1 * 2.0, estimator.calculatePenalty(2.0, 1.0, -1.0), DELTA); // nothing left: s = 1
		assertEquals(0.0, estimator.calculatePenalty(0.0, 1.0, 6.0), DELTA); // free tour
	}

	@Test
	public void estimatorSubtractsPenaltyForResidentsOnly() {
		Person resident = person("resident", new double[] { 0, 20000, 0 }, new double[] { 12, 13 });
		Person visitor = person("visitor", new double[] { 0, 20000, 0 }, new double[] { 12, 13 });
		resident.getAttributes().putAttribute("cantonName", "fribourg");

		var population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		population.addPerson(resident);
		population.addPerson(visitor);
		var filter = ResidentFilter.create(population, "cantonName", "fribourg");

		TourCandidate base = new DefaultTourCandidate(-1.0, List.of(trip("pt"), trip("pt")));
		TourEstimator delegate = (person, modes, tourTrips, previous) -> base;
		var estimator = estimator(delegate, filter);

		List<DiscreteModeChoiceTrip> tourTrips = trips(2);
		tourTrips.forEach(t -> when(t.getDepartureTime()).thenReturn(12 * H));

		assertSame(base, estimator.estimateTour(visitor, List.of("pt", "pt"), tourTrips, List.of()));

		var result = (CreditTourCandidate) estimator.estimateTour(resident, List.of("pt", "pt"), tourTrips,
				List.of());
		double budget = 2.0 * (1.0 + 2.0 * Math.log1p(10.0));
		double s = 1.0 / budget; // 2 off-peak PT trips = 1 credit, nothing needed afterwards
		assertEquals(1.0, result.getCredits(), DELTA);
		assertEquals(-1.0 - 0.1 * 1.0 * s * s, result.getUtility(), DELTA);
	}

	// ----- DMC config switch -----

	private static Config config(boolean enabled, boolean budget, String tourEstimator) {
		MobilityCreditsConfigGroup creditConfig = new MobilityCreditsConfigGroup();
		creditConfig.setEnabled(enabled);
		creditConfig.setBudgetEnabled(budget);
		Config config = ConfigUtils.createConfig(creditConfig);

		DiscreteModeChoiceConfigGroup dmc = DiscreteModeChoiceConfigGroup.getOrCreate(config);
		dmc.setModelType(ModelModule.ModelType.Tour);
		dmc.setTourEstimator(tourEstimator);
		dmc.setTourConstraintsAsString("FromTripBased, EqasimVehicleTourConstraint");
		return config;
	}

	@Test
	public void adaptConfigAddsConstraintAndEstimatorOnlyWithBudget() {
		Config pricingOnly = config(true, false, "Cumulative");
		MobilityCreditsModule.adaptConfig(pricingOnly);
		assertEquals("Cumulative", DiscreteModeChoiceConfigGroup.getOrCreate(pricingOnly).getTourEstimator());

		Config withBudget = config(true, true, "Cumulative");
		MobilityCreditsModule.adaptConfig(withBudget);
		MobilityCreditsModule.adaptConfig(withBudget); // idempotent
		var dmc = DiscreteModeChoiceConfigGroup.getOrCreate(withBudget);
		assertEquals(MobilityCreditsModule.TOUR_ESTIMATOR, dmc.getTourEstimator());
		assertEquals(List.of("FromTripBased", "EqasimVehicleTourConstraint", MobilityCreditsModule.BUDGET_CONSTRAINT),
				List.copyOf(dmc.getTourConstraints()));

		assertThrows(IllegalStateException.class,
				() -> MobilityCreditsModule.adaptConfig(config(true, true, "Uniform")));
	}
}
