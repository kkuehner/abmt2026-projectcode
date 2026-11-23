package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.policies.Policy;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Injector;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioByInstanceModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import org.matsim.contribs.discrete_mode_choice.components.estimators.CumulativeTourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourConstraintFactory;
import org.matsim.contribs.discrete_mode_choice.model.tour_based.TourEstimator;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripEstimator;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.utils.timing.TimeInterpretation;

import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.TypeLiteral;

public class TestCreditUtilityPenalty {
	private static final double DELTA = 1e-9;
	private static final double BETA_COST = -0.1;

	private static final Id<Link> START = Id.createLinkId("start");
	private static final Id<Link> MIDDLE = Id.createLinkId("middle");

	/** start (100 m) -> middle (1 km); a car trip on it drives 1 km. */
	private static Network network() {
		return fill(NetworkUtils.createNetwork());
	}

	private static Network fill(Network network) {
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(1100, 0));
		NetworkUtils.createAndAddLink(network, START, n1, n2, 100.0, 10.0, 1000.0, 1.0);
		NetworkUtils.createAndAddLink(network, MIDDLE, n2, n3, 1000.0, 10.0, 1000.0, 1.0);
		return network;
	}

	private static Person person(Population population, String id, String canton) {
		Person person = population.getFactory().createPerson(Id.createPersonId(id));

		if (canton != null) {
			person.getAttributes().putAttribute("cantonName", canton);
		}

		population.addPerson(person);
		return person;
	}

	private static Leg carLeg() {
		Leg leg = PopulationUtils.createLeg(TransportMode.car);
		leg.setRoute(RouteUtils.createLinkNetworkRouteImpl(START, List.of(), MIDDLE));
		leg.setDepartureTime(12 * 3600.0);
		return leg;
	}

	private static DiscreteModeChoiceTrip trip() {
		DiscreteModeChoiceTrip trip = mock(DiscreteModeChoiceTrip.class);
		when(trip.getDepartureTime()).thenReturn(12 * 3600.0);
		return trip;
	}

	/** Default parameters: car constant 1 + 2 * ln(1 + 1 km), no congestion. */
	private static final double CAR_CREDITS = 1.0 + 2.0 * Math.log1p(1.0);

	@Test
	public void residentsPayCreditsTimesValueTimesCostCoefficient() {
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		Person resident = person(population, "resident", "fribourg");
		Person visitor = person(population, "visitor", "bern");
		Person unknown = person(population, "unknown", null);

		ResidentFilter filter = ResidentFilter.create(population, "cantonName", "fribourg");
		assertEquals(1, filter.getNumberOfResidents());

		var parameters = MobilityCreditsParameters.buildDefault();
		var calculator = new CreditCalculator(parameters, LinkCongestionTable.empty(), network(),
				new FreeSpeedTravelTime());
		var penalty = new CreditUtilityPenalty(calculator, filter, BETA_COST, 2.0);

		List<Leg> elements = List.of(carLeg());
		assertEquals(0.1 * 2.0 * CAR_CREDITS, penalty.calculatePenalty("car", resident, trip(), elements), DELTA);
		assertEquals(0.0, penalty.calculatePenalty("car", visitor, trip(), elements), DELTA);
		assertEquals(0.0, penalty.calculatePenalty("car", unknown, trip(), elements), DELTA);
		assertEquals(0.0, penalty.calculatePenalty("car", null, trip(), elements), DELTA);
		assertEquals(0.0, penalty.calculatePenalty("walk", resident, trip(), List.of()), DELTA);
	}

	@Test
	public void rejectsPositiveCostCoefficient() {
		Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		var filter = ResidentFilter.create(population, "cantonName", "fribourg");
		var calculator = new CreditCalculator(MobilityCreditsParameters.buildDefault(), LinkCongestionTable.empty(),
				network(), new FreeSpeedTravelTime());
		assertThrows(IllegalArgumentException.class, () -> new CreditUtilityPenalty(calculator, filter, 0.1, 1.0));
	}

	/** Builds the module as MATSim would and checks what UtilityPenalty ends up bound. */
	private static com.google.inject.Injector inject(Config config, Scenario scenario, Policy existingPolicy) {
		ModeParameters modeParameters = new ModeParameters();
		modeParameters.betaCost_u_MU = BETA_COST;

		AbstractModule baseline = new AbstractModule() {
			@Override
			public void install() {
				bind(ModeParameters.class).toInstance(modeParameters);
				addTravelTimeBinding(TransportMode.car).toInstance(new FreeSpeedTravelTime());
				bind(new TypeLiteral<Map<String, Policy>>() {
				}).toInstance(Map.of("existing", existingPolicy));
				// stands in for the core PolicyModule binding that the credit module overrides
				bind(UtilityPenalty.class).toInstance(existingPolicy.getUtilityPenalty());
				// provided by MATSim / DMC in a real run
				bind(OutputDirectoryHierarchy.class).toInstance(mock(OutputDirectoryHierarchy.class));
				bind(CumulativeTourEstimator.class).toInstance(
						new CumulativeTourEstimator(mock(TripEstimator.class), TimeInterpretation.create(config)));
			}
		};

		var overridden = org.matsim.core.controler.AbstractModule.override(
				List.of(new ScenarioByInstanceModule(scenario), baseline), new MobilityCreditsModule(null));
		return Injector.createInjector(config, overridden);
	}

	private static Scenario scenario(Config config) {
		Scenario scenario = ScenarioUtils.createScenario(config);
		fill(scenario.getNetwork());
		person(scenario.getPopulation(), "resident", "fribourg");
		person(scenario.getPopulation(), "visitor", "vaud");
		return scenario;
	}

	private static Policy constantPolicy(double penalty) {
		Policy policy = mock(Policy.class);
		UtilityPenalty utilityPenalty = (mode, person, trip, elements) -> penalty;
		when(policy.getUtilityPenalty()).thenReturn(utilityPenalty);
		return policy;
	}

	@Test
	public void disabledModuleKeepsExistingPenalty() {
		Config config = ConfigUtils.createConfig(new MobilityCreditsConfigGroup());
		Policy existing = constantPolicy(0.5);
		var injector = inject(config, scenario(config), existing);

		assertSame(existing.getUtilityPenalty(), injector.getInstance(UtilityPenalty.class));
	}

	@Test
	public void enabledModuleAddsCreditTermForResidentsOnTopOfExistingPolicies() {
		MobilityCreditsConfigGroup creditConfig = new MobilityCreditsConfigGroup();
		creditConfig.setEnabled(true);
		Config config = ConfigUtils.createConfig(creditConfig);
		Scenario scenario = scenario(config);
		var injector = inject(config, scenario, constantPolicy(0.5));

		UtilityPenalty penalty = injector.getInstance(UtilityPenalty.class);
		Person resident = scenario.getPopulation().getPersons().get(Id.createPersonId("resident"));
		Person visitor = scenario.getPopulation().getPersons().get(Id.createPersonId("visitor"));
		List<Leg> elements = List.of(carLeg());

		// default creditValue_CHF = 1: 0.1 utility per credit
		assertEquals(0.5 + 0.1 * CAR_CREDITS, penalty.calculatePenalty("car", resident, trip(), elements), DELTA);
		assertEquals(0.5, penalty.calculatePenalty("car", visitor, trip(), elements), DELTA);
		assertTrue(injector.getInstance(ResidentFilter.class).isResident(resident));
	}

	@Test
	public void budgetRegistersTourEstimatorAndConstraintUnderDmcNames() {
		MobilityCreditsConfigGroup creditConfig = new MobilityCreditsConfigGroup();
		creditConfig.setEnabled(true);
		creditConfig.setBudgetEnabled(true);
		Config config = ConfigUtils.createConfig(creditConfig);
		var injector = inject(config, scenario(config), constantPolicy(0.0));

		var estimators = injector.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TourEstimator>>>() {
		}));
		var constraints = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, Provider<TourConstraintFactory>>>() {
				}));

		assertTrue(estimators.get(MobilityCreditsModule.TOUR_ESTIMATOR).get() instanceof CreditTourEstimator);
		assertTrue(constraints.get(MobilityCreditsModule.BUDGET_CONSTRAINT)
				.get() instanceof CreditBudgetConstraint.Factory);
	}
}
