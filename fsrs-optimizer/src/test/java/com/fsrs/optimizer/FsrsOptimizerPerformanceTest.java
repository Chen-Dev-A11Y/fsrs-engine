package com.fsrs.optimizer;

import com.fsrs.core.FsrsParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * The fit has to stay usable on a real user's data, not just on a toy history. 100 000
 * reviews is the scale the project targets for a heavy account, and an optimizer job that
 * takes minutes is one nobody runs.
 *
 * <p>The bound is 60 seconds. The budget comes from {@link LogLoss#DEFAULT_GRADIENT_REVIEW_BUDGET}:
 * central differences need 42 replays per iteration, so without a cap on the gradient sample
 * the cost would be 42 x 100 000 reviews per iteration rather than 42 x 12 000.
 */
class FsrsOptimizerPerformanceTest {

    private static final int TARGET_REVIEWS = 100_000;

    @Test
    @DisplayName("optimize returns within 60 seconds on 100 000 reviews")
    void optimizesOneHundredThousandReviewsWithinAMinute() {
        double[] truth = FsrsParams.DEFAULT_FSRS_6.clone();
        truth[20] = 0.2;
        truth[8] = FsrsParams.DEFAULT_FSRS_6[8] * 1.2;

        // maxReviewsPerCard = 20 gives an average card length of 11, so 10 000 cards clear
        // 100 000 reviews with margin. The exact count is asserted rather than assumed.
        List<CardHistory> histories = new SyntheticDataGenerator().generate(truth, 10_000, 20, 5L);
        int reviews = histories.stream().mapToInt(CardHistory::size).sum();
        assertThat(reviews).isGreaterThanOrEqualTo(TARGET_REVIEWS);
        System.out.printf("performance: generated cards=%d reviews=%d%n", histories.size(), reviews);

        // Warm the JIT on a small history first: measuring interpreted bytecode would report
        // the startup cost of the JVM rather than the throughput of the algorithm.
        new FsrsOptimizer().optimize(histories.subList(0, 40), FsrsParams.DEFAULT_FSRS_6.clone());

        FsrsOptimizer optimizer = new FsrsOptimizer();
        assertTimeout(java.time.Duration.ofSeconds(60), () -> {
            long startedAt = System.nanoTime();
            OptimizationResult result =
                    optimizer.optimize(histories, FsrsParams.DEFAULT_FSRS_6.clone(), OptimizerConfig.defaults());
            double seconds = (System.nanoTime() - startedAt) / 1e9;
            System.out.printf(
                    "performance: reviews=%d iterations=%d converged=%b initial=%.6f final=%.6f elapsed=%.1fs%n",
                    result.reviewsUsed(), result.iterations(), result.converged(),
                    result.initialLogLoss(), result.finalLogLoss(), seconds);
            assertThat(result.finalLogLoss()).isLessThan(result.initialLogLoss());
        });
    }
}
