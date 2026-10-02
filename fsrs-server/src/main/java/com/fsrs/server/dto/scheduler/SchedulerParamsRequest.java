package com.fsrs.server.dto.scheduler;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/scheduler/params}.
 *
 * <p>{@code weights} is required and must have exactly 21 entries. The bound is expressed
 * as {@code @Size} rather than a custom validator so the failure is reported before any
 * FSRS code runs, and {@code FsrsParams.of} remains the single authority on what a legal
 * parameter set is.
 */
public record SchedulerParamsRequest(
        @NotNull @Size(min = 21, max = 21) double[] weights,
        @DecimalMin("0.70") @DecimalMax("0.99") double desiredRetention,
        @JsonProperty("maximumIntervalDays") Double maximumIntervalDays,
        @JsonProperty("source") String source) {

    public SchedulerParamsRequest {
        if (source == null || source.isBlank()) {
            source = "user";
        }
    }
}
