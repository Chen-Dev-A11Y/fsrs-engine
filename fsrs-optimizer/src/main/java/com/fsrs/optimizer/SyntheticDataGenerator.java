package com.fsrs.optimizer;

import com.fsrs.core.FsrsConfig;
import com.fsrs.core.FsrsParams;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.core.MemoryState;
import com.fsrs.core.Rating;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates plausible review histories from a known set of weights.
 *
 * <p>This is the ground truth the optimizer is measured against. It is the only way to
 * answer the question that actually matters about a fit - <em>did it find the parameters
 * that were really there?</em> - because real review logs come with no answer key.
 *
 * <h2>The label must depend on the model</h2>
 * A learner's grade is drawn from the model's own prediction: with probability
 * {@code R = retrievability(elapsed, S)} the card is recalled, and a recalled card is then
 * graded Hard/Good/Easy in a fixed 12/68/20 split. Drawing grades from a <em>fixed</em>
 * distribution regardless of {@code R} would be simpler and would generate histories that
 * look right, but the labels would then carry no information about the parameters, the loss
 * surface would be flat, and the recovery test would be measuring nothing. The
 * around-8/12/60/20 overall mix that shows up in real logs falls out of the model here
 * rather than being imposed.
 *
 * <h2>Why reviews are late, not on time</h2>
 * Reviewing at exactly the scheduled interval would pin {@code R} to the 90% retention
 * target on every single sample, leaving the decay weight {@code w20} essentially
 * unidentifiable - the curve would never be observed anywhere except at {@code R = 0.9}.
 * So the actual gap is the scheduled interval scaled by {@code [1, 6)}, i.e. cards are
 * reviewed on time or overdue, never early. That is both what real learners do and what
 * gives the fit something to see: {@code R} spreads over roughly {@code [0.72, 0.90]}.
 * Overdue reviews are also where most of the identifiability of the decay weight lives - a
 * history that only ever contains on-time reviews observes the forgetting curve at a single
 * point.
 *
 * <p>A card's first review has no {@code R} to draw from, so its grade comes from a fixed
 * prior; that review seeds the state and is not scored anyway.
 *
 * <p>The generator is deterministic: the same {@code seed} always yields the same histories.
 */
public final class SyntheticDataGenerator {

    /** Upper bound (exclusive) on how late a review happens, as a multiple of its scheduled gap. */
    private static final double MAX_STUDY_DELAY = 6.0;

    /** Grade split inside the "recalled" branch: Hard 12%, Good 68%, Easy 20%. */
    private static final double HARD_SHARE = 0.12;
    private static final double GOOD_SHARE = 0.68;

    /** Grade split for the very first review, where the model has nothing to predict from. */
    private static final double FIRST_AGAIN = 0.08;
    private static final double FIRST_HARD = 0.20;
    private static final double FIRST_GOOD = 0.80;

    /**
     * @param trueWeights        the weights the simulated learner is assumed to obey
     * @param cardCount          how many cards to generate
     * @param maxReviewsPerCard  upper bound on a card's length; the actual length is drawn
     *                           uniformly from {@code 2..maxReviewsPerCard}, so every generated
     *                           card is usable and a batch still contains both barely-studied
     *                           and heavily-studied cards
     * @param seed               seed for every random draw
     * @throws IllegalArgumentException when the weights are not a valid FSRS-6 vector or the
     *                                  counts are out of range
     */
    public List<CardHistory> generate(double[] trueWeights, int cardCount, int maxReviewsPerCard, long seed) {
        ParameterBounds.requireValid(trueWeights);
        if (cardCount < 0) {
            throw new IllegalArgumentException("cardCount must be >= 0 but was " + cardCount);
        }
        if (maxReviewsPerCard < 2) {
            throw new IllegalArgumentException("maxReviewsPerCard must be >= 2 but was " + maxReviewsPerCard);
        }
        FsrsScheduler scheduler =
                new FsrsScheduler(FsrsParams.of(trueWeights), FsrsConfig.deterministic());
        Random random = new Random(seed);
        List<CardHistory> histories = new ArrayList<>(cardCount);

        for (int card = 0; card < cardCount; card++) {
            int reviews = 2 + random.nextInt(maxReviewsPerCard - 1);
            List<Double> elapsedDays = new ArrayList<>(reviews);
            List<Integer> ratings = new ArrayList<>(reviews);
            MemoryState state = null;

            for (int i = 0; i < reviews; i++) {
                double elapsed;
                Rating rating;
                if (state == null) {
                    elapsed = 0.0;
                    rating = firstRating(random);
                } else {
                    double scheduled = Math.max(1.0, scheduler.nextIntervalDays(state));
                    double delay = 1.0 + random.nextDouble() * (MAX_STUDY_DELAY - 1.0);
                    elapsed = Math.max(1.0, Math.round(scheduled * delay));
                    double r = scheduler.retrievability(elapsed, state.stability());
                    rating = gradedRating(random, r);
                }
                elapsedDays.add(elapsed);
                ratings.add(rating.value());
                state = scheduler.review(state, rating, elapsed);
            }
            histories.add(new CardHistory(elapsedDays, ratings));
        }
        return histories;
    }

    /** The outcome the learner actually had, given the model's predicted recall probability. */
    private static Rating gradedRating(Random random, double retrievability) {
        if (random.nextDouble() >= retrievability) {
            return Rating.AGAIN;
        }
        return recallRating(random);
    }

    /** A first review, drawn from a fixed prior because no {@code R} exists yet. */
    private static Rating firstRating(Random random) {
        double draw = random.nextDouble();
        if (draw < FIRST_AGAIN) {
            return Rating.AGAIN;
        }
        if (draw < FIRST_HARD) {
            return Rating.HARD;
        }
        return draw < FIRST_GOOD ? Rating.GOOD : Rating.EASY;
    }

    private static Rating recallRating(Random random) {
        double draw = random.nextDouble();
        if (draw < HARD_SHARE) {
            return Rating.HARD;
        }
        return draw < HARD_SHARE + GOOD_SHARE ? Rating.GOOD : Rating.EASY;
    }
}
