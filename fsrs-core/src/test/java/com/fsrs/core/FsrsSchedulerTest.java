package com.fsrs.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Conformance and behaviour tests for the FSRS-6 model.
 *
 * <p>The first group pins the <em>exact algebraic identities</em> of the published
 * formulas (for example {@code R(S,S) = 0.9} and {@code I(0.9,S) = S}). Those
 * identities hold for any parameter set, so they prove the implementation matches
 * the specification rather than merely producing plausible numbers.
 */
class FsrsSchedulerTest {

    private static final double EPS = 1e-9;

    private final FsrsScheduler scheduler =
            new FsrsScheduler(FsrsParams.defaults(), FsrsConfig.deterministic());

    // ------------------------------------------------------------------
    // Algebraic identities of the forgetting curve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("stability S is exactly the interval that leaves 90% recall")
    void stabilityIsTheNinetyPercentInterval() {
        for (double s : new double[]{1.0, 2.3065, 11.9, 100.0, 3650.0}) {
            assertThat(scheduler.retrievability(s, s))
                    .as("R(S=%s, t=S)", s)
                    .isCloseTo(0.9, within(EPS));
        }
    }

    @Test
    @DisplayName("recall is certain with zero elapsed time")
    void recallIsCertainAtZeroElapsedDays() {
        assertThat(scheduler.retrievability(0.0, 30.0)).isCloseTo(1.0, within(EPS));
    }

    @Test
    @DisplayName("intervalForRetention is the inverse of retrievability")
    void intervalIsTheInverseOfRetrievability() {
        for (double s : new double[]{1.0, 5.0, 60.0, 400.0}) {
            assertThat(scheduler.intervalForRetention(s, 0.9)).isCloseTo(s, within(1e-6));
        }
    }

    @Test
    @DisplayName("recall probability decays monotonically with time")
    void recallDecaysMonotonically() {
        double previous = 1.0;
        for (double t = 0; t <= 60; t += 0.5) {
            double r = scheduler.retrievability(t, 20.0);
            assertThat(r).isLessThanOrEqualTo(previous);
            previous = r;
        }
    }

    @Test
    @DisplayName("a higher retention target means shorter intervals")
    void higherRetentionMeansShorterIntervals() {
        assertThat(scheduler.intervalForRetention(50.0, 0.95))
                .isLessThan(scheduler.intervalForRetention(50.0, 0.90));
        assertThat(scheduler.intervalForRetention(50.0, 0.90))
                .isLessThan(scheduler.intervalForRetention(50.0, 0.80));
    }

    // ------------------------------------------------------------------
    // Initial state
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Rating.class)
    @DisplayName("initial stability is taken directly from w0..w3")
    void initialStabilityFollowsTheFirstFourWeights(Rating rating) {
        assertThat(scheduler.initialStability(rating))
                .isEqualTo(FsrsParams.DEFAULT_FSRS_6[rating.value() - 1]);
    }

    @ParameterizedTest
    @EnumSource(Rating.class)
    @DisplayName("initial difficulty always lands inside [1, 10]")
    void initialDifficultyIsAlwaysValid(Rating rating) {
        assertThat(scheduler.initialDifficulty(rating))
                .isBetween(MemoryState.MIN_DIFFICULTY, MemoryState.MAX_DIFFICULTY);
    }

    @Test
    @DisplayName("a better first answer yields a lower initial difficulty")
    void initialDifficultyDecreasesWithRating() {
        assertThat(scheduler.initialDifficulty(Rating.EASY))
                .isLessThan(scheduler.initialDifficulty(Rating.GOOD));
        assertThat(scheduler.initialDifficulty(Rating.GOOD))
                .isLessThan(scheduler.initialDifficulty(Rating.AGAIN));
    }

    // ------------------------------------------------------------------
    // Difficulty transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("difficulty moves in the direction implied by the rating")
    void difficultyMovesWithRating() {
        double d = 5.0;
        double again = scheduler.nextDifficulty(d, Rating.AGAIN);
        double hard = scheduler.nextDifficulty(d, Rating.HARD);
        double good = scheduler.nextDifficulty(d, Rating.GOOD);
        double easy = scheduler.nextDifficulty(d, Rating.EASY);

        assertThat(again).isGreaterThan(hard);
        assertThat(hard).isGreaterThan(good);
        assertThat(good).isGreaterThan(easy);
    }

    @Test
    @DisplayName("difficulty is clamped and never escapes [1, 10]")
    void difficultyStaysClampedUnderRepeatedRatings() {
        double d = 5.0;
        for (int i = 0; i < 200; i++) {
            d = scheduler.nextDifficulty(d, Rating.AGAIN);
            assertThat(d).isBetween(MemoryState.MIN_DIFFICULTY, MemoryState.MAX_DIFFICULTY);
        }
        for (int i = 0; i < 200; i++) {
            d = scheduler.nextDifficulty(d, Rating.EASY);
            assertThat(d).isBetween(MemoryState.MIN_DIFFICULTY, MemoryState.MAX_DIFFICULTY);
        }
    }

    @Test
    @DisplayName("mean reversion pulls difficulty back towards the Easy anchor")
    void meanReversionPreventsEaseHell() {
        // w7 is the mean-reversion weight. With a non-trivial value, difficulty
        // must converge instead of drifting forever.
        double[] weights = FsrsParams.DEFAULT_FSRS_6.clone();
        weights[7] = 0.5;
        FsrsScheduler reverting =
                new FsrsScheduler(FsrsParams.of(weights), FsrsConfig.deterministic());

        double d = 5.0;
        for (int i = 0; i < 50; i++) {
            d = reverting.nextDifficulty(d, Rating.AGAIN);
        }
        assertThat(d).isLessThan(MemoryState.MAX_DIFFICULTY);
    }

    // ------------------------------------------------------------------
    // Stability transitions
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = Rating.class, names = {"HARD", "GOOD", "EASY"})
    @DisplayName("a successful review never reduces stability")
    void successfulReviewNeverReducesStability(Rating rating) {
        double s = 10.0;
        double d = 5.0;
        double r = scheduler.retrievability(s, s);
        assertThat(scheduler.stabilityAfterRecall(d, s, r, rating)).isGreaterThanOrEqualTo(s);
    }

    @Test
    @DisplayName("harder items gain less stability")
    void harderItemsGainLessStability() {
        double s = 10.0;
        double r = 0.9;
        double easyItem = scheduler.stabilityAfterRecall(2.0, s, r, Rating.GOOD);
        double hardItem = scheduler.stabilityAfterRecall(8.0, s, r, Rating.GOOD);
        assertThat(hardItem).isLessThan(easyItem);
    }

    @Test
    @DisplayName("spacing effect: an overdue successful review gains more stability")
    void spacingEffectRewardsOverdueReviews() {
        double s = 10.0;
        double d = 5.0;
        double onTime = scheduler.stabilityAfterRecall(d, s, scheduler.retrievability(s, s), Rating.GOOD) - s;
        double overdue = scheduler.stabilityAfterRecall(d, s, scheduler.retrievability(3 * s, s), Rating.GOOD) - s;
        assertThat(overdue).isGreaterThan(onTime);
    }

    @Test
    @DisplayName("a lapse reduces stability")
    void forgettingReducesStability() {
        double s = 100.0;
        double d = 5.0;
        double r = scheduler.retrievability(s, s);
        assertThat(scheduler.stabilityAfterForgetting(d, s, r)).isLessThan(s);
    }

    @Test
    @DisplayName("stability after forgetting grows with prior stability")
    void postLapseStabilityGrowsWithPriorStability() {
        double d = 5.0;
        double r = 0.9;
        assertThat(scheduler.stabilityAfterForgetting(d, 100.0, r))
                .isGreaterThan(scheduler.stabilityAfterForgetting(d, 1.0, r));
    }

    @Test
    @DisplayName("same-day review grows stability but sub-linearly (FSRS-6 change)")
    void sameDayReviewGrowsSubLinearly() {
        // With a small stability the w17/w18 gain beats the S^-w19 penalty, so
        // stability does grow - but each repeat grows it by less than the last.
        double s = 1.0;
        double once = scheduler.stabilityAfterSameDayReview(s, Rating.GOOD);
        assertThat(once).isGreaterThan(s);

        double twice = scheduler.stabilityAfterSameDayReview(once, Rating.GOOD);
        assertThat(twice - once).isLessThan(once - s);
    }

    @Test
    @DisplayName("with default weights a same-day review cannot inflate a stable card")
    void sameDayReviewIsNeutralForStableCards() {
        // S^-w19 decays the gain: once S is large enough the multiplier falls below
        // 1 and the "SInc >= 1 on success" floor pins stability in place. This is
        // what stops same-day grinding from gaming the scheduler, and it is a
        // deliberate property of FSRS-6 rather than a defect.
        assertThat(scheduler.stabilityAfterSameDayReview(10.0, Rating.GOOD)).isEqualTo(10.0);
        assertThat(scheduler.stabilityAfterSameDayReview(100.0, Rating.GOOD)).isEqualTo(100.0);
        // The floor only pins Good/Hard: an Easy same-day rating clears it even for
        // a very stable card.
        assertThat(scheduler.stabilityAfterSameDayReview(100.0, Rating.EASY)).isGreaterThan(100.0);
    }

    @Test
    @DisplayName("a successful same-day review never shrinks stability for Good and Easy")
    void sameDayReviewNeverShrinksOnGoodOrEasy() {
        for (double s : new double[]{0.1, 1.0, 10.0, 1000.0}) {
            for (Rating rating : new Rating[]{Rating.GOOD, Rating.EASY}) {
                assertThat(scheduler.stabilityAfterSameDayReview(s, rating)).isGreaterThanOrEqualTo(s);
            }
        }
    }

    @Test
    @DisplayName("a same-day Hard review is allowed to shrink stability (matches reference)")
    void sameDayHardReviewMayShrinkStability() {
        // The "SInc >= 1 on success" floor is applied to Good and Easy only. A Hard
        // same-day rating can therefore reduce stability - verified against the
        // reference library rather than assumed.
        assertThat(scheduler.stabilityAfterSameDayReview(0.1, Rating.HARD)).isLessThan(0.1);
    }

    // ------------------------------------------------------------------
    // Scheduling behaviour
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the first Good review schedules two days out (intervals are rounded)")
    void firstReviewScheduleIsReasonable() {
        FsrsScheduler.ScheduleOutcome outcome = scheduler.apply(null, Rating.GOOD, 0.0);
        assertThat(outcome.next().stability()).isCloseTo(2.3065, within(1e-6));
        // Stability is fractional, but what gets persisted is whole days.
        assertThat(outcome.intervalDays()).isEqualTo(2.0);
        assertThat(outcome.retrievabilityAtReview()).isCloseTo(1.0, within(EPS));
        assertThat(outcome.sameDay()).isFalse();
    }

    @Test
    @DisplayName("scheduling truncates elapsed time to whole days")
    void dayGranularityIsAppliedAtTheReviewBoundary() {
        // The published forgetting curve is continuous, but FSRS schedules in days:
        // 0.9 days later is still "the same day", while 1.9 days later is one day.
        MemoryState state = new MemoryState(10.0, 5.0);
        assertThat(scheduler.review(state, Rating.GOOD, 0.9).stability())
                .isEqualTo(scheduler.stabilityAfterSameDayReview(10.0, Rating.GOOD));
        assertThat(scheduler.review(state, Rating.GOOD, 1.9))
                .isEqualTo(scheduler.review(state, Rating.GOOD, 1.0));
    }

    @Test
    @DisplayName("repeated Good reviews grow the interval monotonically")
    void repeatedGoodReviewsGrowTheInterval() {
        MemoryState state = null;
        double elapsed = 0.0;
        double previousInterval = 0.0;

        for (int i = 0; i < 6; i++) {
            FsrsScheduler.ScheduleOutcome outcome = scheduler.apply(state, Rating.GOOD, elapsed);
            assertThat(outcome.intervalDays())
                    .as("review #%s", i + 1)
                    .isGreaterThan(previousInterval);
            state = outcome.next();
            elapsed = outcome.intervalDays();
            previousInterval = outcome.intervalDays();
        }
        assertThat(previousInterval).isGreaterThan(30.0);
    }

    @Test
    @DisplayName("intervals never exceed the configured maximum")
    void intervalsRespectMaximum() {
        FsrsScheduler capped = new FsrsScheduler(
                FsrsParams.defaults(), FsrsConfig.deterministic().withMaximumIntervalDays(30.0));
        double s = 100_000.0;
        assertThat(capped.intervalForRetention(s)).isEqualTo(30.0);
    }

    @Test
    @DisplayName("fuzzing is a no-op in deterministic mode")
    void fuzzingDisabledIsDeterministic() {
        assertThat(scheduler.applyFuzzing(50.0)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("fuzzing stays within +/-5%")
    void fuzzingIsBounded() {
        FsrsScheduler fuzzed = new FsrsScheduler(
                FsrsParams.defaults(),
                FsrsConfig.deterministic().withFuzzing(true),
                java.util.random.RandomGenerator.of("L64X128MixRandom"));
        for (int i = 0; i < 1000; i++) {
            assertThat(fuzzed.applyFuzzing(100.0)).isBetween(95.0, 105.0);
        }
    }

    @Test
    @DisplayName("very short intervals are not fuzzed")
    void shortIntervalsAreNotFuzzed() {
        FsrsScheduler fuzzed = new FsrsScheduler(
                FsrsParams.defaults(),
                FsrsConfig.deterministic().withFuzzing(true),
                java.util.random.RandomGenerator.of("L64X128MixRandom"));
        assertThat(fuzzed.applyFuzzing(2.0)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("fuzzing never escapes the allowed interval range")
    void fuzzingRespectsMaximum() {
        FsrsScheduler fuzzed = new FsrsScheduler(
                FsrsParams.defaults(),
                FsrsConfig.deterministic().withFuzzing(true).withMaximumIntervalDays(50.0),
                java.util.random.RandomGenerator.of("L64X128MixRandom"));
        for (int i = 0; i < 1000; i++) {
            assertThat(fuzzed.applyFuzzing(100.0)).isEqualTo(50.0);
        }
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("parameter sets must contain exactly 21 finite weights")
    void paramsAreValidated() {
        assertThatThrownBy(() -> FsrsParams.of(1, 2, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("21");

        double[] withNaN = new double[21];
        withNaN[7] = Double.NaN;
        assertThatThrownBy(() -> FsrsParams.of(withNaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite");
    }

    @Test
    @DisplayName("invalid configuration is rejected up front")
    void configIsValidated() {
        assertThatThrownBy(() -> new FsrsConfig(0.5, 100, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FsrsConfig(0.9, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalid memory states are rejected")
    void memoryStateIsValidated() {
        assertThatThrownBy(() -> new MemoryState(0.0, 5.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryState(10.0, 11.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("asking for recall stability after a lapse is a programming error")
    void stabilityAfterRecallRejectsLapse() {
        assertThatThrownBy(() -> scheduler.stabilityAfterRecall(5, 10, 0.9, Rating.AGAIN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
