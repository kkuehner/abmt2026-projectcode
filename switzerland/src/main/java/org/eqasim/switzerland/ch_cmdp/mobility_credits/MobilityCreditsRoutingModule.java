package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eqasim.switzerland.ch_cmdp.tolls.EqasimTollsTravelDisutilityFactory;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Adds link credits to car routing for residents. Does nothing unless {@code mobilityCredits.enabled} is true.
 * <p>
 * The car travel disutility becomes credits + tolls + calibrated network penalties + travel time. This module must
 * be added <b>after</b> {@code TollsModule}, because both bind the car travel disutility and the later one wins.
 * Only car is affected: car passengers ride along (they pay car credits / occupancy in mode choice), trucks and
 * non-residents are exempt.
 */
public class MobilityCreditsRoutingModule extends AbstractModule {
	private static final Logger logger = LogManager.getLogger(MobilityCreditsRoutingModule.class);

	@Override
	public void install() {
		MobilityCreditsConfigGroup creditConfig = MobilityCreditsConfigGroup.get(getConfig());

		if (creditConfig == null || !creditConfig.isEnabled()) {
			return;
		}

		addTravelDisutilityFactoryBinding(TransportMode.car).to(CreditTravelDisutilityFactory.class);
	}

	@Provides
	@Singleton
	CreditTravelDisutilityFactory provideCreditTravelDisutilityFactory(EqasimTollsTravelDisutilityFactory tolls,
			CreditCalculator calculator, ResidentFilter residentFilter, MobilityCreditsParameters parameters) {
		double secondsPerCredit = CreditTravelDisutilityFactory.secondsPerCredit(parameters);
		logger.info("Credit routing: {} s per credit (creditValue_CHF = {}, routingValueOfTime_CHF_h = {})",
				secondsPerCredit, parameters.creditValue_CHF, parameters.routingValueOfTime_CHF_h);
		return new CreditTravelDisutilityFactory(tolls, calculator, residentFilter, secondsPerCredit);
	}
}
