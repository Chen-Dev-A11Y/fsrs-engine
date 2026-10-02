package com.fsrs.server.dto.study;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** One card of the study queue, carrying everything the review screen renders. */
public record CardQueueItem(
        @JsonProperty("cardId") long cardId,
        @JsonProperty("deckId") long deckId,
        @JsonProperty("front") String front,
        @JsonProperty("back") String back,
        @JsonProperty("state") int state,
        @JsonProperty("stability") Double stability,
        @JsonProperty("difficulty") Double difficulty,
        @JsonProperty("retrievability") Double retrievability,
        @JsonProperty("isNew") boolean isNew,
        @JsonProperty("predictedIntervals") PredictedIntervals predictedIntervals) {

    /**
     * What would happen to this card for each of the four grades.
     *
     * <p>This is the field that makes interactive grading possible: the learner sees
     * "Again 10m / Hard 3d / Good 8d / Easy 21d" before choosing. It is computed by the
     * same scheduler that commits the review, with fuzzing disabled, so the preview and the
     * committed interval agree.
     */
    public record PredictedIntervals(
            @JsonProperty("again") double again,
            @JsonProperty("hard") double hard,
            @JsonProperty("good") double good,
            @JsonProperty("easy") double easy) {

        public static PredictedIntervals of(List<Double> days) {
            return new PredictedIntervals(days.get(0), days.get(1), days.get(2), days.get(3));
        }
    }
}
