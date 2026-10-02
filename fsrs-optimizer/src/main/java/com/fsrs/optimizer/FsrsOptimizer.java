package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;

import java.util.List;

/**
 * Fits the 21 FSRS-6 weights to a learner's own review history.
 *
 * <p>This is the capability the official Java library does not have: it ships the model but
 * tells you to go elsewhere for personalization. The fit here is self-contained - no maths
 * library, no native code, no framework - so it can run inside a background job of the
 * service without dragging anything else in.
 *
 * <h2>What the fit actually optimizes</h2>
 * Each candidate weight vector is scored by replaying every card end to end and taking the
 * mean binary cross-entropy of {@code P(recall)} against the observed grades, plus a small
 * L2 term. See {@link LogLoss} for why the replay is unavoidable.
 *
 * <h2>Determinism</h2>
 * Same histories + same weights + same {@link OptimizerConfig} gives bit-identical output.
 * There is no threading, no wall-clock dependence, and the only random draw in the module -
 * the review subsample used when a history is larger than
 * {@link LogLoss#DEFAULT_GRADIENT_REVIEW_BUDGET} - is driven by
 * {@link OptimizerConfig#randomSeed()}. That matters for the product, not just for tests: an
 * optimizer job that returns a different answer every time it is re-run is impossible to
 * audit or to A/B.
 *
 * <h2>Degenerate input</h2>
 * An empty list, or a list where every card is shorter than
 * {@link OptimizerConfig#minReviewsPerCard()}, is not an error: there is simply nothing to
 * learn. The call returns a zero-loss, zero-iteration result carrying the projected input
 * weights, so a batch job can run over every user without special-casing the new ones.
 *
 * <p>Instances are stateless and safe to share.
 */
public final class FsrsOptimizer {

    /**
     * @param histories      every card of one learner; {@code null} entries and cards shorter
     *                       than {@code minReviewsPerCard} are skipped
     * @param initialWeights where the descent starts, usually
     *                       {@link FsrsParams#DEFAULT_FSRS_6}
     */
    public OptimizationResult optimize(List<CardHistory> histories, double[] initialWeights) {
        return optimize(histories, initialWeights, OptimizerConfig.defaults());
    }

    /**
     * @param config stopping rules, the finite-difference step and the subsample seed
     * @throws IllegalArgumentException when {@code initialWeights} is null, is not 21 long or
     *                                  contains a non-finite value, or when {@code histories}
     *                                  is null. An empty or too-short history is not an error
     */
    public OptimizationResult optimize(List<CardHistory> histories, double[] initialWeights, OptimizerConfig config) {
        if (histories == null) {
            throw new IllegalArgumentException("histories must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        ParameterBounds.requireValid(initialWeights);

        LogLoss objective = new LogLoss(histories, config.minReviewsPerCard());
        double initialLoss = objective.loss(initialWeights);

        if (objective.isEmpty()) {
            return new OptimizationResult(ParameterBounds.project(initialWeights), 0.0, 0.0, 0, true, 0);
        }
        if (!Double.isFinite(initialLoss)) {
            throw new IllegalArgumentException(
                    "the initial weights produce a non-finite loss; use a viable starting point such as "
                            + "FsrsParams.DEFAULT_FSRS_6, got " + initialLoss);
        }

        LogLoss gradientObjective = objective.sample(LogLoss.DEFAULT_GRADIENT_REVIEW_BUDGET, config.randomSeed());
        CoordinateDescent.Outcome outcome =
                new CoordinateDescent(objective, gradientObjective, config).minimize(initialWeights);

        double[] weights = outcome.weights();
        double finalLoss = objective.loss(weights);
        return new OptimizationResult(
                weights, initialLoss, finalLoss, outcome.iterations(), outcome.converged(), objective.reviewsUsed());
    }
}
