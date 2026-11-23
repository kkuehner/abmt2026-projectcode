package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.mode_choice.AbstractEqasimExtension;
import org.eqasim.core.simulation.mode_choice.ParameterDefinition;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.policies.Policy;
import org.eqasim.core.simulation.policies.utility.SumPenalty;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contribs.discrete_mode_choice.components.estimators.CumulativeTourEstimator;
import org.matsim.contribs.discrete_mode_choice.modules.EstimatorModule;
import org.matsim.contribs.discrete_mode_choice.modules.ModelModule;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Wires the mobility-credit scheme into the simulation. Does nothing unless {@code mobilityCredits.enabled} is
 * true, so the baseline is unchanged.
 * <p>
 * When enabled:
 * <ul>
 * <li>the {@link UtilityPenalty} used by {@code EqasimUtilityEstimator} becomes the sum of all active eqasim policies
 * (as before) plus the {@link CreditUtilityPenalty}. Must be installed after the core {@code PolicyModule}
 * (guaranteed when registered in {@code SwitzerlandConfigurator});</li>
 * <li>{@link CreditOutputListener} writes the credits of every resident.</li>
 * </ul>
 * With {@code mobilityCredits.budgetEnabled} also the look-ahead constraint and the scarcity penalty (Phase 1b). Their
 * DMC names must be in the DMC config, see {@link #adaptConfig(Config)}.
 */
public class MobilityCreditsModule extends AbstractEqasimExtension {
	private static final Logger logger = LogManager.getLogger(MobilityCreditsModule.class);

	public static final String PARAMETER_PREFIX = "credit-parameter";
	public static final String BUDGET_CONSTRAINT = "MobilityCreditsBudget";
	public static final String TOUR_ESTIMATOR = "MobilityCreditsCumulative";

	private final CommandLine commandLine;

	public MobilityCreditsModule(CommandLine commandLine) {
		this.commandLine = commandLine;
	}

	@Override
	protected void installEqasimExtension() {
		MobilityCreditsConfigGroup creditConfig = MobilityCreditsConfigGroup.get(getConfig());

		if (creditConfig == null || !creditConfig.isEnabled()) {
			return;
		}

		logger.info("Mobility credits enabled (budget: {})", creditConfig.isBudgetEnabled());
		bind(UtilityPenalty.class).toProvider(UtilityPenaltyProvider.class);
		bind(CreditStats.class).in(Singleton.class);
		addControllerListenerBinding().to(CreditOutputListener.class);

		if (creditConfig.isBudgetEnabled()) {
			bindTourConstraintFactory(BUDGET_CONSTRAINT).to(CreditBudgetConstraint.Factory.class);
			bindTourEstimator(TOUR_ESTIMATOR).toProvider(CreditTourEstimatorProvider.class);
		}
	}

	/**
	 * Puts the budget constraint and tour estimator into the DMC config. Call after the command line has been
	 * applied (it may set {@code mobilityCredits.*}) and before the controller is created. Does nothing unless
	 * credits and budget are enabled.
	 */
	public static void adaptConfig(Config config) {
		MobilityCreditsConfigGroup creditConfig = MobilityCreditsConfigGroup.get(config);

		if (creditConfig == null || !creditConfig.isEnabled() || !creditConfig.isBudgetEnabled()) {
			return;
		}

		DiscreteModeChoiceConfigGroup dmcConfig = DiscreteModeChoiceConfigGroup.getOrCreate(config);

		if (dmcConfig.getModelType() != ModelModule.ModelType.Tour) {
			throw new IllegalStateException(
					"The credit budget needs the DiscreteModeChoice modelType 'Tour', found " + dmcConfig.getModelType());
		}

		if (!dmcConfig.getTourEstimator().equals(TOUR_ESTIMATOR)) {
			if (!dmcConfig.getTourEstimator().equals(EstimatorModule.CUMULATIVE)) {
				throw new IllegalStateException("The credit budget wraps the 'Cumulative' tour estimator, found "
						+ dmcConfig.getTourEstimator());
			}

			dmcConfig.setTourEstimator(TOUR_ESTIMATOR);
		}

		List<String> constraints = new ArrayList<>(dmcConfig.getTourConstraints());

		if (!constraints.contains(BUDGET_CONSTRAINT)) {
			constraints.add(BUDGET_CONSTRAINT);
			dmcConfig.setTourConstraints(constraints);
		}

		logger.info("Credit budget: DMC tourEstimator = {}, tourConstraints = {}", dmcConfig.getTourEstimator(),
				dmcConfig.getTourConstraints());
	}

	@Provides
	@Singleton
	MobilityCreditsParameters provideParameters(Config config) {
		MobilityCreditsParameters parameters = MobilityCreditsParameters.buildDefault();
		String path = MobilityCreditsConfigGroup.get(config).getParametersPath();

		if (path != null) {
			URL url = ConfigGroup.getInputFileURL(config.getContext(), path);
			ParameterDefinition.applyFile(new File(url.getPath()), parameters);
		}

		if (commandLine != null) {
			ParameterDefinition.applyCommandLine(PARAMETER_PREFIX, commandLine, parameters);
		}

		parameters.validate();
		return parameters;
	}

	@Provides
	@Singleton
	LinkCongestionTable provideLinkCongestionTable(Config config) {
		String path = MobilityCreditsConfigGroup.get(config).getCongestionPath();

		if (path == null) {
			logger.warn("mobilityCredits.congestionPath is not set: car trips get no congestion credits");
			return LinkCongestionTable.empty();
		}

		URL url = ConfigGroup.getInputFileURL(config.getContext(), path);
		LinkCongestionTable table = LinkCongestionTable.read(url.getPath());
		logger.info("Loaded congestion ratios for {} links from {}", table.getNumberOfLinks(), path);
		return table;
	}

	@Provides
	@Singleton
	ResidentFilter provideResidentFilter(Config config, Population population) {
		MobilityCreditsConfigGroup creditConfig = MobilityCreditsConfigGroup.get(config);
		ResidentFilter filter = ResidentFilter.create(population, creditConfig.getResidentAttribute(),
				creditConfig.getResidentValue());

		logger.info("Mobility credits apply to {} of {} persons ({} = {})", filter.getNumberOfResidents(),
				population.getPersons().size(), creditConfig.getResidentAttribute(), creditConfig.getResidentValue());

		if (filter.getNumberOfResidents() == 0) {
			logger.warn("No person matches the resident filter: the credit scheme has no effect");
		}

		return filter;
	}

	@Provides
	@Singleton
	CreditCalculator provideCreditCalculator(MobilityCreditsParameters parameters, LinkCongestionTable congestion,
			Network network, Map<String, TravelTime> travelTimes) {
		TravelTime carTravelTime = travelTimes.get(TransportMode.car);

		if (carTravelTime == null) {
			throw new IllegalStateException("No car travel time available for the credit calculator");
		}

		return new CreditCalculator(parameters, congestion, network, carTravelTime);
	}

	@Provides
	@Singleton
	CreditUtilityPenalty provideCreditUtilityPenalty(CreditCalculator calculator, ResidentFilter filter,
			ModeParameters modeParameters, MobilityCreditsParameters parameters) {
		CreditUtilityPenalty penalty = new CreditUtilityPenalty(calculator, filter, modeParameters.betaCost_u_MU,
				parameters.creditValue_CHF);
		logger.info("Credit utility term: {} utility per credit (creditValue_CHF = {}, betaCost_u_MU = {})",
				penalty.getPenaltyPerCredit_u(), parameters.creditValue_CHF, modeParameters.betaCost_u_MU);
		return penalty;
	}

	@Provides
	@Singleton
	CreditBudget provideCreditBudget(CreditCalculator calculator, MobilityCreditsParameters parameters) {
		double pt = parameters.budget.ptTrips * calculator.getOffPeakPtTripCredits();
		double car = 2.0 * calculator.getUncongestedCarTripCredits(parameters.budget.carRoundTripOneWay_km);
		logger.info("Equal credit budget: max({} PT trips = {}, car round trip 2 x {} km = {}) = {} credits/day "
				+ "(before the minimum-need guarantee)", parameters.budget.ptTrips, pt,
				parameters.budget.carRoundTripOneWay_km, car, Math.max(pt, car));
		return CreditBudget.equal(calculator, parameters);
	}

	@Provides
	@Singleton
	DayProfiles provideDayProfiles(CreditCalculator calculator, CreditBudget budget,
			MobilityCreditsParameters parameters) {
		return new DayProfiles(calculator, budget, AcceptableDistances.fixed(parameters),
				parameters.budget.beelineFactor);
	}

	/** Bound only with the budget, because it needs DMC's Cumulative tour estimator. */
	static class CreditTourEstimatorProvider implements Provider<CreditTourEstimator> {
		private final CreditTourEstimator estimator;

		@Inject
		CreditTourEstimatorProvider(CumulativeTourEstimator cumulative, CreditCalculator calculator,
				ResidentFilter filter, DayProfiles profiles, CreditUtilityPenalty creditPenalty,
				MobilityCreditsParameters parameters) {
			this.estimator = new CreditTourEstimator(cumulative, calculator, filter, profiles,
					creditPenalty.getPenaltyPerCredit_u(), parameters.scarcity.weight, parameters.scarcity.exponent);
		}

		@Override
		public CreditTourEstimator get() {
			return estimator;
		}
	}

	/** Existing policy penalties plus the credit penalty. */
	static class UtilityPenaltyProvider implements Provider<UtilityPenalty> {
		private final Map<String, Policy> policies;
		private final CreditUtilityPenalty creditPenalty;

		@Inject
		UtilityPenaltyProvider(Map<String, Policy> policies, CreditUtilityPenalty creditPenalty) {
			this.policies = policies;
			this.creditPenalty = creditPenalty;
		}

		@Override
		public UtilityPenalty get() {
			List<UtilityPenalty> penalties = new ArrayList<>();

			for (Policy policy : policies.values()) {
				if (policy.getUtilityPenalty() != null) {
					penalties.add(policy.getUtilityPenalty());
				}
			}

			penalties.add(creditPenalty);
			return new SumPenalty(penalties);
		}
	}
}
