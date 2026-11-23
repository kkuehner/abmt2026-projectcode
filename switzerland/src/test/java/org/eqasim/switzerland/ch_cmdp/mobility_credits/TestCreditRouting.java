package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.eqasim.core.components.travel_disutility.EqasimTravelDisutilityFactory;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.policies.Policy;
import org.eqasim.core.simulation.policies.routing.ZeroRoutingPenalty;
import org.eqasim.switzerland.ch_cmdp.tolls.EqasimTollsTravelDisutilityFactory;
import org.eqasim.switzerland.ch_cmdp.tolls.MarginalCostOfTolls;
import org.eqasim.switzerland.ch_cmdp.tolls.Tolls;
import org.junit.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Injector;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutilityFactory;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.scenario.ScenarioByInstanceModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;

import static org.mockito.Mockito.mock;

import org.matsim.core.controler.OutputDirectoryHierarchy;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;

public class TestCreditRouting {
	private static final double DELTA = 1e-9;
	private static final double H = 3600.0;
	private static final Id<Link> MIDDLE = Id.createLinkId("middle");

	/** A single 1 km link at 10 m/s (100 s), highly congested 7-8h. */
	private static Link fill(Network network) {
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(1000, 0));
		return NetworkUtils.createAndAddLink(network, MIDDLE, n1, n2, 1000.0, 10.0, 1000.0, 1.0);
	}

	private static LinkCongestionTable peak() {
		return new LinkCongestionTable.Builder().add(MIDDLE, 7 * H, 8 * H, 1.6).build();
	}

	private static Person addPerson(Scenario scenario, String id, String canton) {
		Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(id));
		person.getAttributes().putAttribute("cantonName", canton);
		scenario.getPopulation().addPerson(person);
		return person;
	}

	@Test
	public void residentsPayCreditsInSecondsNonResidentsDoNot() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Link link = fill(scenario.getNetwork());
		Person resident = addPerson(scenario, "resident", "fribourg");
		Person visitor = addPerson(scenario, "visitor", "bern");

		var parameters = MobilityCreditsParameters.buildDefault();
		parameters.car.congestionThresholdMedium = 1.2; // ratio 1.6 counts as high: 1 credit/km
		parameters.car.congestionThresholdHigh = 1.5;
		parameters.car.highCongestion_credits_km = 1.0;
		parameters.creditValue_CHF = 2.0;
		parameters.routingValueOfTime_CHF_h = 18.0; // 2 CHF at 18 CHF/h = 400 s per credit
		double secondsPerCredit = CreditTravelDisutilityFactory.secondsPerCredit(parameters);
		assertEquals(400.0, secondsPerCredit, DELTA);

		var calculator = new CreditCalculator(parameters, peak(), scenario.getNetwork(), new FreeSpeedTravelTime());
		var filter = ResidentFilter.create(scenario.getPopulation(), "cantonName", "fribourg");
		var factory = new CreditTravelDisutilityFactory(new OnlyTimeDependentTravelDisutilityFactory(), calculator,
				filter, secondsPerCredit);
		TravelDisutility disutility = factory.createTravelDisutility(new FreeSpeedTravelTime());

		assertEquals(100.0 + 400.0, disutility.getLinkTravelDisutility(link, 7.5 * H, resident, null), DELTA);
		assertEquals(100.0, disutility.getLinkTravelDisutility(link, 12 * H, resident, null), DELTA); // off-peak
		assertEquals(100.0, disutility.getLinkTravelDisutility(link, 7.5 * H, visitor, null), DELTA);
		assertEquals(100.0, disutility.getLinkTravelDisutility(link, 7.5 * H, null, null), DELTA);
		assertEquals(100.0, disutility.getLinkMinimumTravelDisutility(link), DELTA);
	}

	/**
	 * Mirrors RunSimulation: modules from the configurator (incl. MobilityCreditsModule), then TollsModule, then
	 * MobilityCreditsRoutingModule. Returns the car travel disutility factory that wins.
	 */
	private static TravelDisutilityFactory carFactory(boolean enabled) {
		MobilityCreditsConfigGroup creditConfig = new MobilityCreditsConfigGroup();
		creditConfig.setEnabled(enabled);
		Config config = ConfigUtils.createConfig(creditConfig);
		Scenario scenario = ScenarioUtils.createScenario(config);
		fill(scenario.getNetwork());
		addPerson(scenario, "resident", "fribourg");

		var tolls = new EqasimTollsTravelDisutilityFactory(
				new EqasimTravelDisutilityFactory(new ZeroRoutingPenalty(), 0.0), new Tolls(),
				new MarginalCostOfTolls(0.0, 15.0));
		ModeParameters modeParameters = new ModeParameters();
		modeParameters.betaCost_u_MU = -0.1;

		AbstractModule baseline = new AbstractModule() {
			@Override
			public void install() {
				bind(ModeParameters.class).toInstance(modeParameters);
				addTravelTimeBinding(TransportMode.car).toInstance(new FreeSpeedTravelTime());
				bind(new TypeLiteral<Map<String, Policy>>() {
				}).toInstance(Map.of());
				bind(OutputDirectoryHierarchy.class).toInstance(mock(OutputDirectoryHierarchy.class));
			}
		};

		AbstractModule tollsModule = new AbstractModule() {
			@Override
			public void install() {
				bind(EqasimTollsTravelDisutilityFactory.class).toInstance(tolls);
				addTravelDisutilityFactoryBinding(TransportMode.car).toInstance(tolls);
			}
		};

		var configured = AbstractModule.override(List.of(new ScenarioByInstanceModule(scenario), baseline),
				new MobilityCreditsModule(null));
		var withTolls = AbstractModule.override(List.of(configured), tollsModule);
		var withCredits = AbstractModule.override(List.of(withTolls), new MobilityCreditsRoutingModule());

		var injector = Injector.createInjector(config, withCredits);
		Map<String, TravelDisutilityFactory> factories = injector
				.getInstance(Key.get(new TypeLiteral<Map<String, TravelDisutilityFactory>>() {
				}));

		TravelDisutilityFactory car = factories.get(TransportMode.car);

		if (!enabled) {
			assertSame(tolls, car);
		}

		return car;
	}

	@Test
	public void disabledKeepsTollsRouting() {
		carFactory(false);
	}

	@Test
	public void enabledWrapsTollsRouting() {
		TravelDisutilityFactory car = carFactory(true);
		assertTrue(car instanceof CreditTravelDisutilityFactory);
		assertTrue(((CreditTravelDisutilityFactory) car).getDelegate() instanceof EqasimTollsTravelDisutilityFactory);
	}
}
