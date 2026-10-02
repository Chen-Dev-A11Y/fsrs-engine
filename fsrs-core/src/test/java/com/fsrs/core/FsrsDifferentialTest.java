package com.fsrs.core;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Differential ("golden reference") tests.
 *
 * <p>This implementation is checked against the official
 * {@code io.github.open-spaced-repetition:fsrs} library. Both sides are given the
 * <em>same</em> weight array - it is read back from the reference library rather
 * than hard-coded - so a difference in shipped defaults can never mask a
 * difference in the formulas.
 *
 * <p>The reference library exposes {@code reviewCard(card, rating, instant)}, which
 * makes the elapsed time fully controllable and therefore makes these comparisons
 * exact rather than approximate. Three quantities are compared on every review:
 * stability, difficulty and the scheduled interval.
 */
class FsrsDifferentialTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final double MILLIS_PER_DAY = 86_400_000.0;
    private static final double TOL = 1e-9;
    private static final int MAX_INTERVAL_DAYS = 36500;

    /** Reference implementation, configured to be deterministic and free of learning steps. */
    private final Scheduler reference = Scheduler.builder()
            .learningSteps(new Duration[0])
            .relearningSteps(new Duration[0])
            .maximumInterval(MAX_INTERVAL_DAYS)
            .enableFuzzing(false)
            .build();

    /** Weights read back from the reference, so both sides use an identical array. */
    private final double[] sharedParams = reference.getParameters();

    private final FsrsScheduler mine = new FsrsScheduler(
            FsrsParams.of(sharedParams),
            new FsrsConfig(reference.getDesiredRetention(), MAX_INTERVAL_DAYS, false));

    private static io.github.openspacedrepetition.Rating ref(Rating rating) {
        return io.github.openspacedrepetition.Rating.valueOf(rating.name());
    }

    private static Card reviewedCard(double stability, double difficulty, double elapsedDays) {
        return Card.builder()
                .state(State.REVIEW)
                .stability(stability)
                .difficulty(difficulty)
                .lastReview(T0)
                .due(T0.plusSeconds((long) (elapsedDays * 86_400.0)))
                .build();
    }

    private static Instant at(double elapsedDays) {
        return T0.plusSeconds((long) (elapsedDays * 86_400.0));
    }

    /** Interval in days the reference scheduled, derived from the resulting due date. */
    private static double scheduledDays(Card card, Instant reviewDatetime) {
        return (card.getDue().toEpochMilli() - reviewDatetime.toEpochMilli()) / MILLIS_PER_DAY;
    }

    @Test
    @DisplayName("both sides are driven by the same 21-weight parameter array")
    void weightsAreShared() {
        assertThat(sharedParams).hasSize(FsrsParams.SIZE);
    }

    @Test
    @DisplayName("the forgetting-curve factor matches the reference library")
    void factorMatchesReference() {
        assertThat(reference.getFACTOR()).isCloseTo(mine.factor(), within(1e-12));
    }

    @Test
    @DisplayName("the forgetting curve matches the reference library on whole days")
    void retrievabilityCurveMatchesReference() {
        for (double stability : new double[]{1.0, 2.3065, 10.0, 100.0, 1000.0}) {
            for (double elapsed : new double[]{0.0, 1.0, 3.0, 10.0, 30.0, 365.0}) {
                Card card = reviewedCard(stability, 5.0, elapsed);
                assertThat(reference.getCardRetrievability(card, at(elapsed)))
                        .as("R(t=%s, S=%s)", elapsed, stability)
                        .isCloseTo(mine.retrievability(elapsed, stability), within(TOL));
            }
        }
    }

    @Test
    @DisplayName("scheduling is day-granular on both sides")
    void schedulingTruncatesToWholeDays() {
        MemoryState state = new MemoryState(10.0, 5.0);

        // The reference truncates elapsed time to integers, so a sub-day review still
        // reports R = 1. Our curve is intentionally continuous for charting, but the
        // scheduler applies the same whole-day truncation.
        assertThat(reference.getCardRetrievability(reviewedCard(10.0, 5.0, 0.5), at(0.5))).isEqualTo(1.0);
        assertThat(mine.retrievability(0.5, 10.0)).isLessThan(1.0);

        assertThat(mine.review(state, Rating.GOOD, 0.5))
                .isEqualTo(mine.review(state, Rating.GOOD, 0.0));
        assertThat(mine.review(state, Rating.GOOD, 1.5))
                .isEqualTo(mine.review(state, Rating.GOOD, 1.0));
    }

    @Test
    @DisplayName("stability, difficulty and interval all match the reference library")
    void reviewTransitionsMatchReference() {
        double[] stabilities = {1.0, 5.0, 20.0, 100.0};
        double[] difficulties = {2.0, 5.0, 8.0};
        double[] elapsedDays = {1.0, 3.0, 10.0, 40.0, 200.0};

        int checked = 0;
        for (double s : stabilities) {
            for (double d : difficulties) {
                for (double elapsed : elapsedDays) {
                    for (Rating rating : Rating.values()) {
                        Instant reviewDatetime = at(elapsed);
                        CardAndReviewLog result = reference.reviewCard(
                                reviewedCard(s, d, elapsed), ref(rating), reviewDatetime);

                        MemoryState actual = mine.review(new MemoryState(s, d), rating, elapsed);

                        assertThat(result.card().getStability())
                                .as("stability S=%s D=%s t=%s G=%s", s, d, elapsed, rating)
                                .isCloseTo(actual.stability(), within(TOL * Math.max(1.0, actual.stability())));
                        assertThat(result.card().getDifficulty())
                                .as("difficulty S=%s D=%s t=%s G=%s", s, d, elapsed, rating)
                                .isCloseTo(actual.difficulty(), within(TOL * 10));
                        assertThat(scheduledDays(result.card(), reviewDatetime))
                                .as("interval S=%s D=%s t=%s G=%s", s, d, elapsed, rating)
                                .isCloseTo(mine.nextIntervalDays(actual), within(TOL));
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(stabilities.length * difficulties.length
                * elapsedDays.length * Rating.values().length);
    }

    @Test
    @DisplayName("the first review matches the reference library")
    void firstReviewMatchesReference() {
        for (Rating rating : Rating.values()) {
            CardAndReviewLog result =
                    reference.reviewCard(Card.builder().build(), ref(rating), T0);
            MemoryState expected = mine.initialState(rating);

            assertThat(result.card().getStability())
                    .as("initial stability for %s", rating)
                    .isCloseTo(expected.stability(), within(TOL));
            assertThat(result.card().getDifficulty())
                    .as("initial difficulty for %s", rating)
                    .isCloseTo(expected.difficulty(), within(TOL));
            assertThat(scheduledDays(result.card(), T0))
                    .as("initial interval for %s", rating)
                    .isCloseTo(mine.nextIntervalDays(expected), within(TOL));
        }
    }

    @Test
    @DisplayName("a long review sequence stays in lock-step with the reference library")
    void fullSequenceMatchesReference() {
        Rating[] sequence = {
                Rating.GOOD, Rating.GOOD, Rating.AGAIN, Rating.HARD,
                Rating.GOOD, Rating.EASY, Rating.GOOD, Rating.AGAIN, Rating.GOOD,
        };

        Card refCard = Card.builder().build();
        MemoryState mineState = null;
        Instant clock = T0;

        for (int i = 0; i < sequence.length; i++) {
            Rating rating = sequence[i];

            // Elapsed time is measured BEFORE the review, from the reference card's own
            // lastReview, applying the same whole-day truncation.
            double elapsed = refCard.getLastReview() == null
                    ? 0.0
                    : ChronoUnit.DAYS.between(refCard.getLastReview(), clock);

            CardAndReviewLog result = reference.reviewCard(refCard, ref(rating), clock);
            refCard = result.card();

            MemoryState actual = mine.review(mineState, rating, elapsed);

            assertThat(refCard.getStability())
                    .as("step %s (%s) stability after %s elapsed days", i, rating, elapsed)
                    .isCloseTo(actual.stability(), within(1e-6 * Math.max(1.0, actual.stability())));
            assertThat(refCard.getDifficulty())
                    .as("step %s (%s) difficulty", i, rating)
                    .isCloseTo(actual.difficulty(), within(1e-6));

            mineState = actual;
            clock = refCard.getDue();
        }

        assertThat(mineState.stability()).isGreaterThan(1.0);
    }
}
