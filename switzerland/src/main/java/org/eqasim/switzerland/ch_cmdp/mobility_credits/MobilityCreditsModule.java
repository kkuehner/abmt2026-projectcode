package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.core.simulation.mode_choice.ParameterDefinition;
import org.eqasim.core.simulation.mode_choice.parameters.ModeParameters;
import org.eqasim.core.simulation.policies.Policy;
import org.eqasim.core.simulation.policies.utility.SumPenalty;
import org.eqasim.core.simulation.policies.utility.UtilityPenalty;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Wires the mobility-credit scheme into the simulation. Does nothing unless {@code mobilityCredits.enabled} is
 * true, so the baseline is unchanged.
 * <p>
 * When enabled, the {@link UtilityPenalty} used by {@code EqasimUtilityEstimator} becomes the sum of all active
 * eqasim policies (as before) plus the {@link CreditUtilityPenalty}. Must be installed after the core
 * {@code PolicyModule} (guaranteed when registered in {@code SwitzerlandConfigurator}).
 */
public class MobilityCreditsModule extends AbstractModule {
	private static final Logger logger = LogManager.getLogger(MobilityCreditsModule.class);

	public static final String PARAMETER_PREFIX = "credit-parameter";

	private final CommandLine commandLine;

	public MobilityCreditsModule(CommandLine commandLine) {
		this.commandLine = commandLine;
	}

	@Override
	public void install() {
		MobilityCreditsConfigGroup creditConfig = MobilityCreditsConfigGroup.get(getConfig());

		if (creditConfig == null || !creditConfig.isEnabled()) {
			return;
		}

		logger.info("Mobility credits enabled");
		bind(UtilityPenalty.class).toProvider(UtilityPenaltyProvider.class);
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
