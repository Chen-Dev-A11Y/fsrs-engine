package com.fsrs.optimizer;

import com.fsrs.core.FsrsConfig;
import com.fsrs.core.FsrsParams;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.core.MemoryState;
import com.fsrs.core.Rating;

import java.util.ArrayList;
import java.util.List;

/**
 * The controlled comparison the project needs for its headline numbers: the stock defaults,
 * the personalized fit, and a fixed 1/3/7/15/30 ladder, scored on exactly the same reviews.
 *
 * <h2>What can and cannot be measured from a recorded history</h2>
 * The histories are <em>given</em>: every grade was produced at the elapsed time that
 * actually happened, under whatever schedule the learner was on. That fixes two things no
 * re-scoring can change - which reviews exist, and how many of them were recalls. So in
 * {@link #run} all three strategies are scored on the identical sample set, and
 * {@code totalReviews} and {@code averageRetention} come out the same for each: they describe
 * the evaluation set, not the strategy. The number that <em>does</em> differ is
 * {@code logLoss}, because the three strategies disagree about how likely each of those
 * reviews was to be recalled.
 *
 * <p>Claiming a review-count reduction from this experiment would be dishonest - the counts
 * are an input, not a result. That claim needs a closed loop, and {@link #workload} is where
 * it lives: it re-schedules the same learner under each rule and counts the reviews each
 * rule needs to cover a fixed horizon. It is still not a full counterfactual study (the
 * grades are replayed rather than re-generated, so retention is an estimate), and it says so.
 *
 * <h2>What the fixed ladder can and cannot predict</h2>
 * A fixed interval rule has no memory model, so it has nothing to forecast recall with.
 * Scoring it as a constant 0.9 forecast is the honest reading: 90% is precisely the
 * retention such a ladder is designed around, and a constant is exactly the amount of
 * information the rule contains. Any real model that cannot beat it is not earning its
 * parameters.
 */
public final class BenchmarkRunner {

    /** Default horizon for the workload comparison: one year of study per card. */
    public static final double DEFAULT_HORIZON_DAYS = 365.0;

    /** The fixed ladder, cycling on its last step once the list runs out. */
    private static final int[] FIXED_LADDER_DAYS = {1, 3, 7, 15, 30};

    /** The retention a 1/3/7/15/30 ladder is conventionally designed around. */
    private static final double FIXED_LADDER_RETENTION = 0.9;

    /** Name of the model-free baseline; part of the reported contract. */
    public static final String FIXED_LADDER_NAME = "fixed-1-3-7-15-30";

    /**
     * @param name              strategy name
     * @param logLoss           mean binary cross-entropy on the shared evaluation set
     * @param totalReviews      scored reviews; identical for every strategy in one
     *                          {@link BenchmarkReport} by construction
     * @param averageRetention  observed recall rate over those reviews; likewise identical
     */
    public record StrategyResult(String name, double logLoss, int totalReviews, double averageRetention) {
    }

    /**
     * @param strategies       one entry per strategy, in report order
     * @param optimizedWeights the fitted weights that the {@code optimized} entry used
     */
    public record BenchmarkReport(List<StrategyResult> strategies, double[] optimizedWeights) {

        public BenchmarkReport {
            if (strategies == null) {
                throw new IllegalArgumentException("strategies must not be null");
            }
            strategies = List.copyOf(strategies);
            if (optimizedWeights == null || optimizedWeights.length != FsrsParams.SIZE) {
                throw new IllegalArgumentException("optimizedWeights must contain exactly " + FsrsParams.SIZE
                        + " values but got " + (optimizedWeights == null ? "null" : optimizedWeights.length));
            }
            optimizedWeights = optimizedWeights.clone();
        }

        @Override
        public double[] optimizedWeights() {
            return optimizedWeights.clone();
        }

        /** Look up one strategy by name. */
        public StrategyResult strategy(String name) {
            return strategies.stream()
                    .filter(result -> result.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown strategy: " + name));
        }
    }

    /**
     * @param name              strategy name
     * @param totalReviews      reviews the rule schedules to cover the horizon, over all cards
     * @param averageRetention  observed recall rate over the replayed grades
     */
    public record WorkloadResult(String name, int totalReviews, double averageRetention) {
    }

    /** Fits with {@link OptimizerConfig#defaults()} and scores on every usable review. */
    public BenchmarkReport run(List<CardHistory> histories, double[] defaultWeights) {
        return run(histories, defaultWeights, OptimizerConfig.defaults());
    }

    /**
     * @param histories      the learner's cards; the {@code optimized} strategy is fitted on them
     * @param defaultWeights the baseline parameter set, normally
     *                       {@link FsrsParams#DEFAULT_FSRS_6}
     * @param config         passed straight to {@link FsrsOptimizer}
     */
    public BenchmarkReport run(List<CardHistory> histories, double[] defaultWeights, OptimizerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        ParameterBounds.requireValid(defaultWeights);
        double[] optimized = new FsrsOptimizer().optimize(histories, defaultWeights, config).weights();
        LogLoss objective = new LogLoss(histories, config.minReviewsPerCard());

        List<StrategyResult> strategies = new ArrayList<>(3);
        strategies.add(score("default", objective, defaultWeights));
        strategies.add(score("optimized", objective, optimized));
        strategies.add(new StrategyResult(
                FIXED_LADDER_NAME,
                objective.constantForecastCrossEntropy(FIXED_LADDER_RETENTION),
                objective.reviewsUsed(),
                objective.observedRetention()));
        return new BenchmarkReport(strategies, optimized);
    }

    private static StrategyResult score(String name, LogLoss objective, double[] weights) {
        return new StrategyResult(
                name,
                objective.meanCrossEntropy(weights),
                objective.reviewsUsed(),
                objective.observedRetention());
    }

    /**
     * Reviews each rule needs to cover {@link #DEFAULT_HORIZON_DAYS} per card.
     *
     * <p>This is the closed-loop half of the benchmark, and it is the only place a
     * review-count reduction can legitimately come from. For every card the observed grades
     * are replayed in order to drive the memory state - so a strategy that believes stability
     * grows faster will indeed space the same learner out further - while the <em>interval</em>
     * is chosen by the strategy under test rather than by the recorded history. The fixed
     * ladder ignores the state entirely.
     *
     * <p>Known limitation, stated rather than hidden: the grades were originally produced at
     * other elapsed times, so {@code averageRetention} here is <em>not</em> a fair measurement
     * and the review counts are not comparable at equal retention. A rule that happens to
     * review early gets credit for grades that were earned under a different schedule. What
     * the number does support is narrower and still useful: how many reviews each rule would
     * spend covering a fixed period for the same learner. A same-retention comparison needs
     * outcomes to be re-generated from known weights, which is the offline simulator's job,
     * not the optimizer's.
     */
    public List<WorkloadResult> workload(List<CardHistory> histories, double[] defaultWeights) {
        return workload(histories, defaultWeights, OptimizerConfig.defaults(), DEFAULT_HORIZON_DAYS);
    }

    /**
     * @param horizonDays how long the simulated study period lasts, in days
     */
    public List<WorkloadResult> workload(
            List<CardHistory> histories, double[] defaultWeights, OptimizerConfig config, double horizonDays) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (!Double.isFinite(horizonDays) || horizonDays <= 0.0) {
            throw new IllegalArgumentException("horizonDays must be finite and > 0 but was " + horizonDays);
        }
        ParameterBounds.requireValid(defaultWeights);
        double[] optimized = new FsrsOptimizer().optimize(histories, defaultWeights, config).weights();

        List<WorkloadResult> results = new ArrayList<>(3);
        results.add(replayWorkload("default", histories, defaultWeights, config, horizonDays));
        results.add(replayWorkload("optimized", histories, optimized, config, horizonDays));
        results.add(replayWorkload(FIXED_LADDER_NAME, histories, null, config, horizonDays));
        return results;
    }

    private static WorkloadResult replayWorkload(
            String name,
            List<CardHistory> histories,
            double[] weights,
            OptimizerConfig config,
            double horizonDays) {
        FsrsScheduler scheduler =
                weights == null ? null : new FsrsScheduler(FsrsParams.of(weights), FsrsConfig.deterministic());
        int reviews = 0;
        int recalls = 0;
        int observed = 0;

        for (CardHistory history : histories) {
            if (history == null || history.size() < config.minReviewsPerCard()) {
                continue;
            }
            MemoryState state = null;
            double day = 0.0;
            double lastGap = 0.0;
            int step = 0;
            while (day <= horizonDays) {
                Rating grade = Rating.of(history.ratings().get(Math.min(step, history.size() - 1)));
                if (step < history.size()) {
                    observed++;
                    if (grade.isRecall()) {
                        recalls++;
                    }
                }
                reviews++;
                double gap;
                if (scheduler == null) {
                    gap = FIXED_LADDER_DAYS[Math.min(step, FIXED_LADDER_DAYS.length - 1)];
                } else {
                    state = scheduler.review(state, grade, lastGap);
                    gap = scheduler.nextIntervalDays(state);
                }
                lastGap = gap;
                day += gap;
                step++;
            }
        }
        return new WorkloadResult(name, reviews, observed == 0 ? 0.0 : (double) recalls / observed);
    }
}
