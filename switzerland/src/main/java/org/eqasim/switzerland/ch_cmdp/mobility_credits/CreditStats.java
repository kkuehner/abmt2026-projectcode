package org.eqasim.switzerland.ch_cmdp.mobility_credits;

import java.util.concurrent.atomic.AtomicLong;

/** Counters of the look-ahead during one iteration (mode choice runs in parallel, hence atomic). */
public class CreditStats {
	private final AtomicLong rejected = new AtomicLong();
	private final AtomicLong safetyNetAccepted = new AtomicLong();
	private final AtomicLong forcedOverflow = new AtomicLong();

	void countRejected() {
		rejected.incrementAndGet();
	}

	void countSafetyNetAccept() {
		safetyNetAccepted.incrementAndGet();
	}

	void countForcedOverflow() {
		forcedOverflow.incrementAndGet();
	}

	/** Returns {rejected, safety-net accepts, forced overflows} since the last call and resets the counters. */
	long[] getAndReset() {
		return new long[] { rejected.getAndSet(0), safetyNetAccepted.getAndSet(0), forcedOverflow.getAndSet(0) };
	}
}
