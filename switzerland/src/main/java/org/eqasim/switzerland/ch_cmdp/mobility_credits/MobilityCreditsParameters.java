package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import org.eqasim.core.simulation.mode_choice.ParameterDefinition;

/**
 * Hyperparameters of the mobility-credit cost model (see Project_Plan.md, "Cost
 * per trip").
 * <p>
 * Like the existing cost parameters, values can be overridden from a flat yml
 * file
 * ({@code car.constant_credits: 1.5}) or the command line
 * ({@code --credit-parameter:car.constant_credits 1.5}) via
 * {@link ParameterDefinition}.
 * <p>
 * The defaults are placeholders that only fix the ordering car &gt; PT &gt;
 * bike &gt; walk; they are
 * meant to be tuned in Phase 2.
 */
public class MobilityCreditsParameters implements ParameterDefinition {

	public static class CarParameters {
		/** Credits charged once per car trip. */
		public double constant_credits = 1.0;
		/** Distance term: {@code distanceFactor_credits * ln(1 + distance_km)}. */
		public double distanceFactor_credits = 2.0;
		/**
		 * Congestion ratio (congested / free-flow travel time) from which a link counts
		 * as medium congested.
		 */
		public double congestionThresholdMedium = 1.2;
		/** Congestion ratio from which a link counts as highly congested. */
		public double congestionThresholdHigh = 1.0;
		/** Credits per km driven on a medium-congested link. */
		public double mediumCongestion_credits_km = 1.5;
		/** Credits per km driven on a highly congested link. */
		public double highCongestion_credits_km = 2.0;
	}

	public static class PtParameters {
		/** Credits charged once per PT trip (independent of distance and transfers). */
		public double constant_credits = 0.5;
		/** Multiplier on the constant for trips departing in a peak window. */
		public double peakFactor = 1.5;
		public double morningPeakStart_h = 7.0;
		public double morningPeakEnd_h = 9.0;
		public double eveningPeakStart_h = 16.0;
		public double eveningPeakEnd_h = 19.0;
	}

	public static class BikeParameters {
		/** Credits charged once per bike trip. */
		public double constant_credits = 0.1;
	}

	public static class CarPassengerParameters {
		/**
		 * Car passengers pay the car credits of the same trip divided by this
		 * occupancy.
		 */
		public double occupancy = 2.0;
	}

	/**
	 * Value of one credit in CHF. The credit term of the utility is betaCost_u_MU *
	 * creditValue_CHF * credits,
	 * without the income interaction of the base cost term.
	 */
	public double creditValue_CHF = 1.0;

	/**
	 * Value of time used to convert credits into router disutility (seconds):
	 * seconds per credit = creditValue_CHF * 3600 / routingValueOfTime_CHF_h. Same idea and default as
	 * networkCalibration.tollsValueOfTime. Needed because the car time utility is non-linear, so there is no
	 * single value of time implied by the mode choice model.
	 */
	public double routingValueOfTime_CHF_h = 15.0;

	public final CarParameters car = new CarParameters();
	public final PtParameters pt = new PtParameters();
	public final BikeParameters bike = new BikeParameters();
	public final CarPassengerParameters carPassenger = new CarPassengerParameters();

	public static MobilityCreditsParameters buildDefault() {
		return new MobilityCreditsParameters();
	}

	/** Throws if the parameters are inconsistent. */
	public void validate() {
		requireNonNegative("creditValue_CHF", creditValue_CHF);

		if (!(routingValueOfTime_CHF_h > 0.0) || !Double.isFinite(routingValueOfTime_CHF_h)) {
			throw new IllegalStateException(
					"routingValueOfTime_CHF_h must be finite and positive, got " + routingValueOfTime_CHF_h);
		}

		requireNonNegative("car.constant_credits", car.constant_credits);
		requireNonNegative("car.distanceFactor_credits", car.distanceFactor_credits);
		requireNonNegative("car.mediumCongestion_credits_km", car.mediumCongestion_credits_km);
		requireNonNegative("car.highCongestion_credits_km", car.highCongestion_credits_km);
		requireNonNegative("pt.constant_credits", pt.constant_credits);
		requireNonNegative("pt.peakFactor", pt.peakFactor);
		requireNonNegative("bike.constant_credits", bike.constant_credits);

		if (!(car.congestionThresholdMedium <= car.congestionThresholdHigh)) {
			throw new IllegalStateException(
					"car.congestionThresholdMedium must not exceed car.congestionThresholdHigh");
		}

		if (!(carPassenger.occupancy >= 1.0)) {
			throw new IllegalStateException("carPassenger.occupancy must be at least 1");
		}

		requireWindow("pt.morningPeak", pt.morningPeakStart_h, pt.morningPeakEnd_h);
		requireWindow("pt.eveningPeak", pt.eveningPeakStart_h, pt.eveningPeakEnd_h);
	}

	private static void requireNonNegative(String name, double value) {
		if (!(value >= 0.0) || !Double.isFinite(value)) {
			throw new IllegalStateException(name + " must be finite and non-negative, got " + value);
		}
	}

	private static void requireWindow(String name, double start_h, double end_h) {
		if (!(start_h >= 0.0 && start_h <= end_h && end_h <= 24.0)) {
			throw new IllegalStateException(
					name + " must satisfy 0 <= start <= end <= 24, got " + start_h + "-" + end_h);
		}
	}
}
