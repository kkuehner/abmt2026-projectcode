package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Car routing cost = existing cost (travel time, calibrated link penalties, tolls) + link credits of residents,
 * converted to seconds. Uses the same {@link CreditCalculator#getLinkCredits} as mode choice, so the route that
 * mode choice prices is the route that considered the credits.
 * <p>
 * Non-residents and routing calls without a person are routed as before. The minimum travel disutility is not
 * changed: credits are never negative, so the delegate's lower bound stays valid for A*-type routers.
 */
public class CreditTravelDisutilityFactory implements TravelDisutilityFactory {
	private static final Logger logger = LogManager.getLogger(CreditTravelDisutilityFactory.class);

	private final TravelDisutilityFactory delegate;
	private final CreditCalculator calculator;
	private final ResidentFilter residentFilter;
	private final double secondsPerCredit;
	private final AtomicBoolean warnedAboutMissingPerson = new AtomicBoolean(false);

	public CreditTravelDisutilityFactory(TravelDisutilityFactory delegate, CreditCalculator calculator,
			ResidentFilter residentFilter, double secondsPerCredit) {
		if (!(secondsPerCredit >= 0.0) || !Double.isFinite(secondsPerCredit)) {
			throw new IllegalArgumentException("secondsPerCredit must be finite and >= 0, got " + secondsPerCredit);
		}

		this.delegate = delegate;
		this.calculator = calculator;
		this.residentFilter = residentFilter;
		this.secondsPerCredit = secondsPerCredit;
	}

	public static double secondsPerCredit(MobilityCreditsParameters parameters) {
		return parameters.creditValue_CHF * 3600.0 / parameters.routingValueOfTime_CHF_h;
	}

	@Override
	public TravelDisutility createTravelDisutility(TravelTime travelTime) {
		return new CreditTravelDisutility(delegate.createTravelDisutility(travelTime));
	}

	public TravelDisutilityFactory getDelegate() {
		return delegate;
	}

	private class CreditTravelDisutility implements TravelDisutility {
		private final TravelDisutility base;

		CreditTravelDisutility(TravelDisutility base) {
			this.base = base;
		}

		@Override
		public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
			double disutility = base.getLinkTravelDisutility(link, time, person, vehicle);

			if (person == null) {
				if (warnedAboutMissingPerson.compareAndSet(false, true)) {
					logger.warn("Car routing called without a person: routed without credits (logged once)");
				}

				return disutility;
			}

			if (!residentFilter.isResident(person)) {
				return disutility;
			}

			return disutility + secondsPerCredit * calculator.getLinkCredits(link, time);
		}

		@Override
		public double getLinkMinimumTravelDisutility(Link link) {
			return base.getLinkMinimumTravelDisutility(link);
		}
	}
}
