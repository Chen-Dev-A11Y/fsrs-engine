package com.fsrs.optimizer;

import com.fsrs.core.FsrsConfig;
import com.fsrs.core.FsrsParams;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.core.MemoryState;
import com.fsrs.core.Rating;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The objective being minimized: mean binary cross-entropy (log loss) of the
 * "did the learner recall this?" prediction, plus a tiny L2 penalty.
 *
 * <pre>
 * loss(w) = -mean( y * ln R + (1 - y) * ln(1 - R) ) + lambda * sum(w_i^2)
 * y       = rating &gt;= 2   (Again is the only "forgot" grade)
 * R       = retrievability(elapsedDays, S_prev)   at the moment of the review
 * </pre>
 *
 * <h2>Why log loss and not accuracy or RMSE</h2>
 * The model outputs a probability, and log loss is the proper scoring rule for a
 * probability forecast: it is minimized exactly when the predicted probability equals the
 * true one, and it punishes confident mistakes far harder than mild ones. Accuracy would
 * reward a model that is confidently wrong, and RMSE on a 0/1 label is a strictly worse
 * behaved version of the same thing.
 *
 * <h2>Replay, not per-row fitting</h2>
 * A row of {@code review_log} is not an independent sample: {@code S} at review {@code i}
 * is a function of reviews {@code 0..i-1}. So every candidate weight vector is scored by
 * replaying each card from scratch through {@link FsrsScheduler}, predicting at each step
 * from the <em>pre-review</em> state, and only then applying the review. That is why the
 * gradient costs a multiple of the raw row count, and why this class pre-parses the input
 * once into primitive arrays instead of touching boxed lists in the inner loop.
 *
 * <h2>Two numerical guards, both deliberate</h2>
 * <ul>
 *   <li>{@code R} is clamped into {@code [1e-6, 1 - 1e-6]}. A single unclamped
 *       {@code ln(0)} would turn the whole mean into {@code NaN} and make every subsequent
 *       finite difference meaningless.</li>
 *   <li>Stability is capped at {@link #MAX_STABILITY}. The model itself only floors
 *       stability, and at the edge of the parameter box (a tiny {@code w9} plus a large
 *       {@code w8}) the recurrence can grow without bound until it overflows. Capping keeps
 *       the objective finite so the line search can simply reject those candidates.</li>
 * </ul>
 *
 * <p>A card's first review contributes no term: there is no previous state, hence no
 * {@code R} to score. It still runs through {@code initialState(rating)} so that the rest of
 * the card is scored against the right trajectory.
 */
public final class LogLoss {

    /** Probability clamp; {@code ln(1e-6)} is about -13.8, the worst a single sample can cost. */
    public static final double PROBABILITY_EPSILON = 1e-6;

    /** L2 strength. Small on purpose: it only exists to keep runaway weights finite. */
    public static final double L2_LAMBDA = 1e-6;

    /** Stability ceiling for the replay; beyond this the model is numerically indistinguishable from "forever". */
    public static final double MAX_STABILITY = 1.0e7;

    /**
     * Reviews used per gradient when the history is huge. Central differences need
     * {@code 2 * 21} full replays per iteration, so the cost of one iteration is linear in
     * this number: at the measured ~120 ns per review, 12 000 reviews keeps an iteration
     * near 60 ms and a full 200-iteration fit near 15 s for a 100 000-review history.
     * Datasets at or below this size are never subsampled, so small fits stay exact.
     */
    public static final int DEFAULT_GRADIENT_REVIEW_BUDGET = 12_000;

    private static final Rating[] RATINGS = Rating.values();

    private final List<Card> cards;
    private final int[] selection;
    private final int reviewsUsed;

    /** One card, pre-parsed so the inner loop never touches a boxed collection. */
    private record Card(Rating[] ratings, double[] elapsedDays) {
    }

    /** A single number produced by one replay of the selected cards. */
    public record Evaluation(double loss, int reviewsUsed) {

        /** {@code false} when the replay produced nothing finite, i.e. the candidate is unusable. */
        public boolean isValid() {
            return Double.isFinite(loss);
        }
    }

    /**
     * @param histories         card histories; {@code null} entries and entries shorter than
     *                          {@code minReviewsPerCard} are skipped
     * @param minReviewsPerCard cards with fewer reviews than this are ignored
     */
    public LogLoss(List<CardHistory> histories, int minReviewsPerCard) {
        if (histories == null) {
            throw new IllegalArgumentException("histories must not be null");
        }
        if (minReviewsPerCard < 1) {
            throw new IllegalArgumentException("minReviewsPerCard must be >= 1 but was " + minReviewsPerCard);
        }
        List<Card> kept = new ArrayList<>(histories.size());
        for (CardHistory history : histories) {
            if (history == null || history.size() < minReviewsPerCard) {
                continue;
            }
            int size = history.size();
            Rating[] ratings = new Rating[size];
            double[] elapsedDays = new double[size];
            for (int i = 0; i < size; i++) {
                ratings[i] = RATINGS[history.ratings().get(i) - 1];
                elapsedDays[i] = history.elapsedDaysBeforeReview().get(i);
            }
            kept.add(new Card(ratings, elapsedDays));
        }
        this.cards = List.copyOf(kept);
        this.selection = sequential(this.cards.size());
        this.reviewsUsed = countReviews(this.cards, this.selection);
    }

    /** Internal constructor for a subsampled view that shares the parsed cards. */
    private LogLoss(List<Card> cards, int[] selection) {
        this.cards = cards;
        this.selection = selection;
        this.reviewsUsed = countReviews(cards, selection);
    }

    private static int[] sequential(int size) {
        int[] indices = new int[size];
        for (int i = 0; i < size; i++) {
            indices[i] = i;
        }
        return indices;
    }

    private static int countReviews(List<Card> cards, int[] selection) {
        int total = 0;
        for (int index : selection) {
            total += Math.max(0, cards.get(index).ratings().length - 1);
        }
        return total;
    }

    /** Cards kept after filtering. */
    public int cardCount() {
        return selection.length;
    }

    /** Review events in this objective that carry a prediction. */
    public int reviewsUsed() {
        return reviewsUsed;
    }

    /** True when there is nothing to fit; the caller should return a no-op rather than a fit. */
    public boolean isEmpty() {
        return reviewsUsed == 0;
    }

    /**
     * A deterministic subsample of <em>whole cards</em>, capped at {@code reviewBudget}
     * predicted reviews.
     *
     * <p>Whole cards rather than individual reviews, because the replay is sequential: a
     * truncated card would be scored against a memory state it never had. The mean over a
     * representative subset of cards is an unbiased estimate of the mean over all of them,
     * so the gradient is still a gradient of the same objective, just noisier.
     *
     * <p>{@link Random} is used on purpose: its linear congruential generator is specified
     * by the JDK, so the same seed picks the same cards on any JVM version. The loss that is
     * <em>reported</em> is always computed on the full data; only the gradient and the step
     * acceptance use this view.
     *
     * @return {@code this} when the objective already fits inside the budget
     */
    public LogLoss sample(int reviewBudget, long seed) {
        if (reviewBudget < 1) {
            throw new IllegalArgumentException("reviewBudget must be >= 1 but was " + reviewBudget);
        }
        if (reviewsUsed <= reviewBudget) {
            return this;
        }
        int[] order = sequential(cards.size());
        Random random = new Random(seed);
        for (int i = order.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        int[] chosen = new int[order.length];
        int chosenCount = 0;
        int reviews = 0;
        for (int index : order) {
            int cardReviews = Math.max(0, cards.get(index).ratings().length - 1);
            if (chosenCount > 0 && reviews + cardReviews > reviewBudget) {
                continue;
            }
            chosen[chosenCount++] = index;
            reviews += cardReviews;
            if (reviews >= reviewBudget) {
                break;
            }
        }
        return new LogLoss(cards, Arrays.copyOf(chosen, chosenCount));
    }

    /** The regularized objective: mean cross-entropy plus {@code lambda * sum(w^2)}. */
    public double loss(double[] weights) {
        double crossEntropy = meanCrossEntropy(weights);
        if (!Double.isFinite(crossEntropy)) {
            return crossEntropy;
        }
        return crossEntropy + L2_LAMBDA * sumOfSquares(weights);
    }

    /** The predictive part alone, which is what makes two strategies comparable. */
    public double meanCrossEntropy(double[] weights) {
        ParameterBounds.requireValid(weights);
        FsrsScheduler scheduler = new FsrsScheduler(FsrsParams.of(weights), FsrsConfig.deterministic());
        double total = 0.0;
        for (int index : selection) {
            Card card = cards.get(index);
            Rating[] ratings = card.ratings();
            double[] elapsedDays = card.elapsedDays();
            MemoryState state = null;
            for (int i = 0; i < ratings.length; i++) {
                if (state != null) {
                    double r = scheduler.retrievability(elapsedDays[i], state.stability());
                    double p = clampProbability(r);
                    double y = ratings[i].isRecall() ? 1.0 : 0.0;
                    total += -(y * Math.log(p) + (1.0 - y) * Math.log1p(-p));
                }
                MemoryState next = scheduler.review(state, ratings[i], elapsedDays[i]);
                double stability = next.stability();
                if (!(stability > 0.0)) {
                    // NaN or a collapsed state: no candidate may poison the whole mean.
                    return Double.POSITIVE_INFINITY;
                }
                state = stability > MAX_STABILITY
                        ? new MemoryState(MAX_STABILITY, next.difficulty())
                        : next;
            }
        }
        return reviewsUsed == 0 ? 0.0 : total / reviewsUsed;
    }

    /** The regularized objective together with the sample count it was computed over. */
    public Evaluation evaluate(double[] weights) {
        return new Evaluation(loss(weights), reviewsUsed);
    }

    /**
     * Mean cross-entropy of a <em>constant</em> forecast {@code p} over the same samples.
     *
     * <p>Depends only on the observed recall rate, which is what makes it a legitimate
     * zero-parameter baseline: it is the score of a strategy that has no memory model at all
     * and just assumes a fixed retention.
     */
    public double constantForecastCrossEntropy(double p) {
        double q = clampProbability(p);
        double y = observedRetention();
        return -(y * Math.log(q) + (1.0 - y) * Math.log1p(-q));
    }

    /**
     * Share of scored reviews graded {@code >= 2}, i.e. the observed recall rate. The
     * strategies in {@link BenchmarkRunner} all see the same outcomes, so this number is
     * about the data, not about the strategy.
     */
    public double observedRetention() {
        if (reviewsUsed == 0) {
            return 0.0;
        }
        int recalls = 0;
        int total = 0;
        for (int index : selection) {
            Rating[] ratings = cards.get(index).ratings();
            for (int i = 1; i < ratings.length; i++) {
                if (ratings[i].isRecall()) {
                    recalls++;
                }
                total++;
            }
        }
        return total == 0 ? 0.0 : (double) recalls / total;
    }

    /** {@code 1e-6} is already indistinguishable from "never" at any realistic horizon. */
    private static double clampProbability(double r) {
        if (!(r > PROBABILITY_EPSILON)) {
            return PROBABILITY_EPSILON;
        }
        return Math.min(r, 1.0 - PROBABILITY_EPSILON);
    }

    private static double sumOfSquares(double[] weights) {
        double sum = 0.0;
        for (double weight : weights) {
            sum += weight * weight;
        }
        return sum;
    }
}
