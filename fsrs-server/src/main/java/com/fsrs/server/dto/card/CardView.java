package com.fsrs.server.dto.card;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Card projection used by {@code GET /api/cards}.
 *
 * <p>{@code dueTs} is serialized as {@code due} because that is the name the frontend
 * contract uses; the internal name stays {@code dueTs} to keep the UTC-millis meaning
 * obvious everywhere else in the code.
 */
public record CardView(
        @JsonProperty("id") long id,
        @JsonProperty("deckId") long deckId,
        @JsonProperty("front") String front,
        @JsonProperty("back") String back,
        @JsonProperty("state") int state,
        @JsonProperty("due") long due,
        @JsonProperty("stability") Double stability,
        @JsonProperty("difficulty") Double difficulty,
        @JsonProperty("reps") int reps,
        @JsonProperty("lapses") int lapses,
        @JsonProperty("suspended") boolean suspended,
        @JsonProperty("lastReviewTs") Long lastReviewTs) {

    public static CardView of(com.fsrs.server.domain.Card card) {
        return new CardView(
                card.getId(),
                card.getDeckId(),
                card.getFront(),
                card.getBack(),
                card.getState() == null ? 0 : card.getState(),
                card.getDueTs() == null ? 0L : card.getDueTs(),
                card.getStability(),
                card.getDifficulty(),
                card.getReps() == null ? 0 : card.getReps(),
                card.getLapses() == null ? 0 : card.getLapses(),
                card.suspendedFlag(),
                card.getLastReviewTs());
    }
}
