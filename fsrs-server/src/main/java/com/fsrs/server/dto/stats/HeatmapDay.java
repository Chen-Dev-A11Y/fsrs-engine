package com.fsrs.server.dto.stats;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One cell of the review heatmap, already aggregated on the user's local calendar day. */
public record HeatmapDay(
        @JsonProperty("day") String day,
        @JsonProperty("total") int total,
        int again,
        int hard,
        int good,
        int easy) {
}
