package com.fsrs.core;

/**
 * Scheduler settings that are <em>not</em> part of the memory model itself.
 *
 * @param desiredRetention    target probability of recall when a card comes due.
 *                            0.9 means "show it again when the model predicts a
 *                            90% chance you still remember it". Higher values
 *                            mean more reviews and a lighter load per review.
 * @param maximumIntervalDays hard cap on any computed interval.
 * @param enableFuzzing       adds a small random spread to intervals, which avoids
 *                            "review avalanches" where many cards clump together.
 */
public record FsrsConfig(double desiredRetention, double maximumIntervalDays, boolean enableFuzzing) {

    public FsrsConfig {
        if (desiredRetention < 0.70 || desiredRetention > 0.99) {
            throw new IllegalArgumentException("desiredRetention must be within [0.70, 0.99] but was "
                    + desiredRetention);
        }
        if (maximumIntervalDays < 1.0) {
            throw new IllegalArgumentException("maximumIntervalDays must be >= 1 but was " + maximumIntervalDays);
        }
    }

    public static FsrsConfig defaults() {
        return new FsrsConfig(0.9, 36500.0, true);
    }

    /** Deterministic settings, used by tests and by the optimizer. */
    public static FsrsConfig deterministic() {
        return new FsrsConfig(0.9, 36500.0, false);
    }

    public FsrsConfig withRetention(double retention) {
        return new FsrsConfig(retention, maximumIntervalDays, enableFuzzing);
    }

    public FsrsConfig withFuzzing(boolean fuzzing) {
        return new FsrsConfig(desiredRetention, maximumIntervalDays, fuzzing);
    }

    public FsrsConfig withMaximumIntervalDays(double days) {
        return new FsrsConfig(desiredRetention, days, enableFuzzing);
    }
}
