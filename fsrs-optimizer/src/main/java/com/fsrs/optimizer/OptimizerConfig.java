package com.fsrs.optimizer;

/**
 * Knobs of the parameter fit. Every field has a default in {@link #defaults()}, and
 * the defaults are the ones the benchmark in {@link BenchmarkRunner} reports.
 *
 * @param maxIterations     hard cap on descent iterations; the loop also stops early
 *                          when a step stops paying off
 * @param convergenceTol    stop as soon as one iteration improves the full-data loss
 *                          by less than this. The loss is a mean binary cross-entropy,
 *                          so 1e-6 is "no longer worth another 42 replays"
 * @param finiteDiffStep    central-difference step {@code h} used to approximate
 *                          {@code dLoss/dw_i}. 1e-4 balances truncation error
 *                          ({@code O(h^2)}) against cancellation error in the replay
 * @param minReviewsPerCard cards with fewer reviews than this are dropped. A card
 *                          reviewed once yields no predictable sample at all, and a
 *                          card reviewed twice yields one - fitting 21 parameters on
 *                          that is pure noise
 * @param randomSeed        seed for the deterministic review subsample used when the
 *                          history is larger than the gradient budget. It is the only
 *                          source of randomness in the module, so two runs with the
 *                          same seed produce bit-identical weights
 */
public record OptimizerConfig(
        int maxIterations,
        double convergenceTol,
        double finiteDiffStep,
        int minReviewsPerCard,
        long randomSeed) {

    public OptimizerConfig {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be >= 1 but was " + maxIterations);
        }
        if (!Double.isFinite(convergenceTol) || convergenceTol <= 0.0) {
            throw new IllegalArgumentException("convergenceTol must be finite and > 0 but was " + convergenceTol);
        }
        if (!Double.isFinite(finiteDiffStep) || finiteDiffStep <= 0.0) {
            throw new IllegalArgumentException("finiteDiffStep must be finite and > 0 but was " + finiteDiffStep);
        }
        if (minReviewsPerCard < 1) {
            throw new IllegalArgumentException("minReviewsPerCard must be >= 1 but was " + minReviewsPerCard);
        }
    }

    /** The tuned defaults: 200 iterations, 1e-6 tolerance, 1e-4 step, seed 42. */
    public static OptimizerConfig defaults() {
        return new OptimizerConfig(200, 1e-6, 1e-4, 2, 42L);
    }

    public OptimizerConfig withMaxIterations(int iterations) {
        return new OptimizerConfig(iterations, convergenceTol, finiteDiffStep, minReviewsPerCard, randomSeed);
    }

    public OptimizerConfig withConvergenceTol(double tolerance) {
        return new OptimizerConfig(maxIterations, tolerance, finiteDiffStep, minReviewsPerCard, randomSeed);
    }

    public OptimizerConfig withFiniteDiffStep(double step) {
        return new OptimizerConfig(maxIterations, convergenceTol, step, minReviewsPerCard, randomSeed);
    }

    public OptimizerConfig withMinReviewsPerCard(int minimum) {
        return new OptimizerConfig(maxIterations, convergenceTol, finiteDiffStep, minimum, randomSeed);
    }

    public OptimizerConfig withRandomSeed(long seed) {
        return new OptimizerConfig(maxIterations, convergenceTol, finiteDiffStep, minReviewsPerCard, seed);
    }
}
