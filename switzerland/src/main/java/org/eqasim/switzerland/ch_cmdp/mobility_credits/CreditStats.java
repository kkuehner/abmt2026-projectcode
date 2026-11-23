package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.concurrent.atomic.AtomicLong;

/** Counters of the look-ahead during one iteration (mode choice runs in parallel, hence atomic). */
public class CreditStats {
	private final AtomicLong rejected = new AtomicLong();
	private final AtomicLong safetyNetAccepted = new AtomicLong();

	void countRejected() {
		rejected.incrementAndGet();
	}

	void countSafetyNetAccept() {
		safetyNetAccepted.incrementAndGet();
	}

	/** Returns {rejected, safety-net accepts} since the last call and resets both. */
	long[] getAndReset() {
		return new long[] { rejected.getAndSet(0), safetyNetAccepted.getAndSet(0) };
	}
}
