package com.fsrs.server.dto.study;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of one graded review. The same object is returned for the original request and
 * for every replay of the same {@code clientReviewId}, with only {@code duplicate} flipped,
 * so a client can always apply the response unconditionally.
 *
 * @param cardId                 card that was graded
 * @param nextDue                new due instant, epoch millis UTC
 * @param intervalDays           whole days the card was scheduled for
 * @param stability              S after the review
 * @param difficulty             D after the review
 * @param state                  new state code (0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING)
 * @param retrievabilityAtReview R predicted at the moment of the review; 1.0 for a first review
 * @param duplicate              true when this response replays an earlier submission
 */
public record ReviewResponse(
        @JsonProperty("cardId") long cardId,
        @JsonProperty("nextDue") long nextDue,
        @JsonProperty("intervalDays") double intervalDays,
        @JsonProperty("stability") double stability,
        @JsonProperty("difficulty") double difficulty,
        @JsonProperty("state") int state,
        @JsonProperty("retrievabilityAtReview") double retrievabilityAtReview,
        @JsonProperty("duplicate") boolean duplicate) {

    /** Replay of a stored result: identical numbers, flagged as a duplicate. */
    public ReviewResponse asDuplicate() {
        return new ReviewResponse(cardId, nextDue, intervalDays, stability, difficulty,
                state, retrievabilityAtReview, true);
    }
}
