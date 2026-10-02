package com.fsrs.core;

import java.util.random.RandomGenerator;

/**
 * Pure implementation of the {@code FSRS-6} memory model.
 *
 * <p>This class contains no IO, no framework types and no mutable state, which
 * makes it usable from a web request, a batch optimizer and a unit test alike.
 *
 * <p>Symbols used below follow the published specification exactly:
 * <ul>
 *   <li>{@code R} retrievability - probability the item is still remembered</li>
 *   <li>{@code S} stability - interval in days at which {@code R} falls to 90%</li>
 *   <li>{@code D} difficulty - in [1, 10]</li>
 *   <li>{@code G} grade/rating - 1 Again, 2 Hard, 3 Good, 4 Easy</li>
 * </ul>
 *
 * <h2>Two views of retrievability</h2>
 * {@link #retrievability(double, double)} is the <em>continuous</em> published
 * curve, which is what the forgetting-curve chart draws and what the parameter
 * optimizer fits. <em>Scheduling</em>, however, is day-granular: a review uses
 * whole elapsed days, so anything within the first day counts as day zero. That
 * distinction is deliberate and matches the reference library, which truncates
 * elapsed time with {@code (int) ChronoUnit.DAYS.between(...)}.
 *
 * <h2>What the differential test changed</h2>
 * Comparing against {@code io.github.open-spaced-repetition:fsrs} surfaced three
 * behaviours that are easy to miss when implementing from the published formulas:
 * <ol>
 *   <li>Post-lapse stability is capped by {@code S / e^(w17 * w18)}, not only by
 *       the long-term term.</li>
 *   <li>The same-day "stability must not decrease" floor applies to <b>Good and
 *       Easy only</b>; a same-day Hard review is allowed to shrink stability.</li>
 *   <li>Stability has a floor of {@link #STABILITY_MIN}.</li>
 * </ol>
 */
public final class FsrsScheduler {

    /** Stability is never allowed below this value (same floor as the reference library). */
    public static final double STABILITY_MIN = 0.001;

    /** Intervals below this many days are not fuzzed (a day of jitter is meaningless). */
    private static final double MIN_FUZZ_INTERVAL_DAYS = 2.5;

    private final FsrsParams params;
    private final FsrsConfig config;
    private final RandomGenerator random;

    public FsrsScheduler(FsrsParams params, FsrsConfig config) {
        this(params, config, null);
    }

    /**
     * @param random source of jitter when fuzzing is enabled; {@code null} disables it
     */
    public FsrsScheduler(FsrsParams params, FsrsConfig config, RandomGenerator random) {
        this.params = params;
        this.config = config;
        this.random = random;
    }

    public static FsrsScheduler withDefaults() {
        return new FsrsScheduler(FsrsParams.defaults(), FsrsConfig.defaults());
    }

    public FsrsParams params() {
        return params;
    }

    public FsrsConfig config() {
        return config;
    }

    /** Truncates elapsed time to whole days, matching the day granularity of scheduling. */
    static int wholeDays(double elapsedDays) {
        return (int) Math.max(0.0, elapsedDays);
    }

    // ------------------------------------------------------------------
    // Forgetting curve
    // ------------------------------------------------------------------

    /**
     * {@code w20}: the trainable decay exponent of the forgetting curve. The
     * reference library stores its negation as {@code DECAY}.
     */
    public double decayExponent() {
        return params.w(20);
    }

    /**
     * {@code factor = 0.9 ^ (-1 / w20) - 1}, chosen so that {@code R(S, S) = 90%}
     * by construction. This is what lets {@code S} be read as "the interval that
     * leaves you a 90% chance of recall".
     */
    public double factor() {
        return Math.pow(0.9, -1.0 / decayExponent()) - 1.0;
    }

    /**
     * The continuous forgetting curve: {@code R(t, S) = (1 + factor * t / S) ^ (-w20)}.
     *
     * @param elapsedDays days since the last review; fractional values are allowed here
     * @param stability   current stability in days
     */
    public double retrievability(double elapsedDays, double stability) {
        if (stability <= 0.0 || !Double.isFinite(stability)) {
            throw new IllegalArgumentException("stability must be > 0 but was " + stability);
        }
        if (elapsedDays < 0.0) {
            throw new IllegalArgumentException("elapsedDays must be >= 0 but was " + elapsedDays);
        }
        return Math.pow(1.0 + factor() * elapsedDays / stability, -decayExponent());
    }

    /**
     * Inverse of {@link #retrievability}: the interval that brings recall
     * probability down to {@code retention}.
     *
     * <p>{@code I(r, S) = S / factor * (r ^ (-1 / w20) - 1)}, and {@code I(0.9, S) = S}.
     * The result is a real number; {@link #nextIntervalDays(MemoryState)} rounds it
     * to the whole days that are actually scheduled.
     */
    public double intervalForRetention(double stability, double retention) {
        if (retention <= 0.0 || retention > 1.0) {
            throw new IllegalArgumentException("retention must be within (0, 1] but was " + retention);
        }
        double interval = stability / factor() * (Math.pow(retention, -1.0 / decayExponent()) - 1.0);
        return clampInterval(interval);
    }

    /** The interval the card is scheduled for, honouring the configured retention target. */
    public double intervalForRetention(double stability) {
        return intervalForRetention(stability, config.desiredRetention());
    }

    // ------------------------------------------------------------------
    // Initial state (first ever review)
    // ------------------------------------------------------------------

    /** {@code S0(G) = w[G-1]} */
    public double initialStability(Rating rating) {
        return clampStability(params.w(rating.value() - 1));
    }

    /** {@code D0(G) = w4 - e ^ (w5 * (G - 1)) + 1}, clamped to [1, 10]. */
    public double initialDifficulty(Rating rating) {
        double d = params.w(4) - Math.exp(params.w(5) * (rating.value() - 1)) + 1.0;
        return MemoryState.clampDifficulty(d);
    }

    public MemoryState initialState(Rating rating) {
        return new MemoryState(initialStability(rating), initialDifficulty(rating));
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    /**
     * Difficulty update with linear damping plus mean reversion.
     *
     * <p>Damping ({@code (10 - D) / 9}) stops difficulty from running away, and mean
     * reversion towards {@code D0(EASY)} prevents "ease hell" - the failure mode
     * where difficulty only ever climbs.
     */
    public double nextDifficulty(double difficulty, Rating rating) {
        double delta = -params.w(6) * (rating.value() - 3);
        double damped = difficulty + delta * (10.0 - difficulty) / 9.0;
        double reverted = params.w(7) * initialDifficulty(Rating.EASY) + (1.0 - params.w(7)) * damped;
        return MemoryState.clampDifficulty(reverted);
    }

    /**
     * Stability after a successful recall ({@code G >= 2}).
     *
     * <pre>
     * S' = S * ( e^w8 * (11 - D) * S^-w9 * (e^(w10 * (1 - R)) - 1)
     *            * w15[G=2] * w16[G=4]  +  1 )
     * </pre>
     *
     * <p>Three behaviours fall out of this formula and are worth remembering:
     * harder items (higher {@code D}) gain less stability, already-stable items
     * gain less, and a lower {@code R} at review time gains <em>more</em> - the
     * spacing effect.
     */
    public double stabilityAfterRecall(double difficulty, double stability, double retrievability, Rating rating) {
        if (!rating.isRecall()) {
            throw new IllegalArgumentException("stabilityAfterRecall requires a successful rating but got " + rating);
        }
        double hardPenalty = rating == Rating.HARD ? params.w(15) : 1.0;
        double easyBonus = rating == Rating.EASY ? params.w(16) : 1.0;
        double increase = Math.exp(params.w(8))
                * (11.0 - difficulty)
                * Math.pow(stability, -params.w(9))
                * (Math.exp(params.w(10) * (1.0 - retrievability)) - 1.0)
                * hardPenalty
                * easyBonus;
        return clampStability(stability * (increase + 1.0));
    }

    /**
     * Stability after forgetting (post-lapse).
     *
     * <pre>
     * longTerm  = w11 * D^-w12 * ((S + 1)^w13 - 1) * e^(w14 * (1 - R))
     * shortTerm = S / e^(w17 * w18)
     * S'        = min(longTerm, shortTerm)
     * </pre>
     *
     * <p>The short-term cap is easy to miss and matters a lot: it stops a lapse
     * from ever <em>raising</em> stability on a nearly-new card, no matter how
     * overdue the review was.
     */
    public double stabilityAfterForgetting(double difficulty, double stability, double retrievability) {
        double longTerm = params.w(11)
                * Math.pow(difficulty, -params.w(12))
                * (Math.pow(stability + 1.0, params.w(13)) - 1.0)
                * Math.exp((1.0 - retrievability) * params.w(14));
        double shortTerm = stability / Math.exp(params.w(17) * params.w(18));
        return clampStability(Math.min(longTerm, shortTerm));
    }

    /**
     * FSRS-6 stability update for a review that happens on the same day as the
     * previous one. Growth now decays with {@code S}, so same-day grinding cannot
     * inflate stability without bound.
     *
     * <pre>
     * S' = S * e^(w17 * (G - 3 + w18)) * S^-w19
     * </pre>
     *
     * <p>The "must not decrease" floor applies to <b>Good and Easy only</b>. A
     * same-day Hard review is permitted to shrink stability, which matches the
     * reference library exactly.
     */
    public double stabilityAfterSameDayReview(double stability, Rating rating) {
        double increase = Math.exp(params.w(17) * (rating.value() - 3 + params.w(18)))
                * Math.pow(stability, -params.w(19));
        if (rating == Rating.GOOD || rating == Rating.EASY) {
            increase = Math.max(increase, 1.0);
        }
        return clampStability(stability * increase);
    }

    /**
     * Applies one review to a memory state.
     *
     * <p>Elapsed time is truncated to whole days before it enters the model, so a
     * review 0.9 days later is handled as a same-day review and 1.9 days later as
     * a full day. This mirrors the reference library.
     *
     * @param previous    state before the review; {@code null} for the first ever review
     * @param rating      what the learner answered
     * @param elapsedDays days since the previous review (ignored when {@code previous} is null)
     * @return the new memory state
     */
    public MemoryState review(MemoryState previous, Rating rating, double elapsedDays) {
        if (previous == null) {
            return initialState(rating);
        }
        double d = previous.difficulty();
        double s = previous.stability();
        int elapsed = wholeDays(elapsedDays);

        double nextDifficulty = nextDifficulty(d, rating);
        double nextStability;
        if (elapsed < 1) {
            // Same-day review: the long-term formulas are not defined for t < 1 day.
            nextStability = stabilityAfterSameDayReview(s, rating);
        } else {
            double r = retrievability(elapsed, s);
            nextStability = rating.isRecall()
                    ? stabilityAfterRecall(d, s, r, rating)
                    : stabilityAfterForgetting(d, s, r);
        }
        return new MemoryState(nextStability, nextDifficulty);
    }

    /**
     * The interval (in days) a card is scheduled for after the given review.
     * Rounded to whole days, because that is what actually gets persisted.
     */
    public double nextIntervalDays(MemoryState state) {
        return applyFuzzing(Math.max(1.0, Math.round(intervalForRetention(state.stability()))));
    }

    /**
     * One review in, one scheduling decision out. This is the entry point used by
     * the service layer.
     */
    public ScheduleOutcome apply(MemoryState previous, Rating rating, double elapsedDays) {
        double retrievabilityAtReview = previous == null
                ? 1.0
                : retrievability(wholeDays(elapsedDays), previous.stability());
        MemoryState next = review(previous, rating, elapsedDays);
        double intervalDays = nextIntervalDays(next);
        boolean sameDay = previous != null && wholeDays(elapsedDays) < 1;
        return new ScheduleOutcome(next, intervalDays, retrievabilityAtReview, sameDay, rating);
    }

    /**
     * Randomly spreads intervals so that cards reviewed together do not stay
     * clumped forever.
     *
     * <p>Deliberate simplification: the reference library uses three interval bands
     * with different fuzz factors and a seeded {@link java.util.Random}. Fuzzing is
     * a scheduling nicety rather than part of the memory model, so this
     * implementation applies a flat +/-5% and is excluded from the differential
     * comparison.
     */
    public double applyFuzzing(double intervalDays) {
        if (!config.enableFuzzing() || random == null || intervalDays < MIN_FUZZ_INTERVAL_DAYS) {
            return clampInterval(intervalDays);
        }
        double jitter = (random.nextDouble() * 2.0 - 1.0) * intervalDays * 0.05;
        return clampInterval(intervalDays + jitter);
    }

    private double clampStability(double stability) {
        return Math.max(stability, STABILITY_MIN);
    }

    private double clampInterval(double intervalDays) {
        return Math.max(1.0, Math.min(intervalDays, config.maximumIntervalDays()));
    }

    /**
     * @param next                    memory state after the review
     * @param intervalDays            days until the card is due again
     * @param retrievabilityAtReview  probability of recall at the moment of review
     * @param sameDay                 whether this review happened on the same day as the previous one
     * @param rating                  the grade that produced this outcome
     */
    public record ScheduleOutcome(
            MemoryState next,
            double intervalDays,
            double retrievabilityAtReview,
            boolean sameDay,
            Rating rating) {
    }
}
