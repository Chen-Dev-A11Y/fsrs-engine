package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the three-way comparison that produces the project's headline numbers.
 *
 * <p>The ordering is what matters, not the individual values: a personalized fit that cannot
 * beat the population defaults it started from is a bug, not a result, and any model at all
 * must beat a rule that owns no parameters.
 */
class BenchmarkRunnerTest {

    private static final String DEFAULT = "default";
    private static final String OPTIMIZED = "optimized";

    private static double[] personalizedWeights() {
        double[] truth = FsrsParams.DEFAULT_FSRS_6.clone();
        truth[2] = 3.4;
        truth[4] = 7.2;
        truth[5] = 1.15;
        truth[8] = FsrsParams.DEFAULT_FSRS_6[8] * 1.2;
        truth[9] = 0.25;
        truth[11] = 2.4;
        truth[20] = 0.2;
        return truth;
    }

    @Test
    @DisplayName("three strategies are reported and the fit beats the defaults")
    void reportsThreeStrategiesWithTheFitAhead() {
        List<CardHistory> histories = new SyntheticDataGenerator().generate(personalizedWeights(), 300, 24, 4242L);
        BenchmarkRunner.BenchmarkReport report =
                new BenchmarkRunner().run(histories, FsrsParams.DEFAULT_FSRS_6.clone());

        assertThat(report.strategies()).hasSize(3);
        assertThat(report.strategies()).extracting(BenchmarkRunner.StrategyResult::name)
                .containsExactlyInAnyOrder(DEFAULT, OPTIMIZED, BenchmarkRunner.FIXED_LADDER_NAME);

        BenchmarkRunner.StrategyResult defaults = report.strategy(DEFAULT);
        BenchmarkRunner.StrategyResult optimized = report.strategy(OPTIMIZED);
        BenchmarkRunner.StrategyResult fixed = report.strategy(BenchmarkRunner.FIXED_LADDER_NAME);

        System.out.printf(
                "benchmark: %-20s logLoss=%.6f reviews=%d retention=%.4f%n"
                        + "           %-20s logLoss=%.6f reviews=%d retention=%.4f%n"
                        + "           %-20s logLoss=%.6f reviews=%d retention=%.4f%n",
                defaults.name(), defaults.logLoss(), defaults.totalReviews(), defaults.averageRetention(),
                optimized.name(), optimized.logLoss(), optimized.totalReviews(), optimized.averageRetention(),
                fixed.name(), fixed.logLoss(), fixed.totalReviews(), fixed.averageRetention());

        assertThat(optimized.logLoss())
                .as("personalized parameters must predict this learner better than the defaults")
                .isLessThan(defaults.logLoss());
        assertThat(defaults.logLoss())
                .as("a real memory model must beat a parameterless constant forecast")
                .isLessThan(fixed.logLoss());
        assertThat(optimized.totalReviews()).isEqualTo(defaults.totalReviews());
        assertThat(report.optimizedWeights()).hasSize(FsrsParams.SIZE);
    }

    @Test
    @DisplayName("the workload replay pins the fixed ladder and spaces the fitted learner out further")
    void workloadComparesReviewCounts() {
        List<CardHistory> histories = new SyntheticDataGenerator().generate(personalizedWeights(), 200, 24, 31337L);
        List<BenchmarkRunner.WorkloadResult> workload =
                new BenchmarkRunner().workload(histories, FsrsParams.DEFAULT_FSRS_6.clone());

        assertThat(workload).hasSize(3);
        for (BenchmarkRunner.WorkloadResult result : workload) {
            System.out.printf("workload: %-20s reviews=%d retention=%.4f%n",
                    result.name(), result.totalReviews(), result.averageRetention());
        }
        assertThat(workload).allSatisfy(result -> assertThat(result.totalReviews()).isPositive());

        // The ladder is pure arithmetic and cannot depend on the fitted weights:
        // 1+3+7+15+30 reaches day 56, then 11 more 30-day steps pass day 365 -> 16 reviews per card.
        assertThat(totalReviewsOf(workload, BenchmarkRunner.FIXED_LADDER_NAME))
                .as("the fixed ladder must be exactly the ladder, 16 reviews per card per year")
                .isEqualTo(histories.size() * 16);

        // The fitted weights describe this learner, so they space the same grades out further
        // than the population defaults do. That is the only review-count claim this experiment
        // supports - see BenchmarkRunner for why the ladder's count is not comparable.
        assertThat(totalReviewsOf(workload, OPTIMIZED))
                .as("personalized parameters must not need more reviews than the defaults")
                .isLessThan(totalReviewsOf(workload, DEFAULT));
    }

    private static int totalReviewsOf(List<BenchmarkRunner.WorkloadResult> workload, String name) {
        return workload.stream()
                .filter(result -> result.name().equals(name))
                .findFirst()
                .orElseThrow()
                .totalReviews();
    }
}
