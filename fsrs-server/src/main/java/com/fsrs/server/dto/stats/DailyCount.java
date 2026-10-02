package com.fsrs.server.dto.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Reviews on one local calendar day; feeds both the streak and the heatmap. */
public record DailyCount(@JsonProperty("day") String day, @JsonProperty("total") int total) {
}
