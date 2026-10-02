package com.fsrs.server.dto.card;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code DELETE /api/cards/{id}}.
 *
 * <p>{@code alreadyGone} is the whole reason this endpoint answers 200 for a card that is not
 * there: deleting is the state the caller asked for, and a list that is a few seconds stale
 * plus a double-clicked button must not produce an error dialog.
 *
 * <p>{@code reviewsPreserved} is the reassurance half of the response. The delete is a soft
 * delete, so the review history is deliberately kept - it is the input the parameter optimizer
 * fits on - and reporting the count is how the client can say "your history was not lost"
 * instead of leaving the user to hope.
 *
 * @param alreadyGone true when nothing was deleted because the card was already absent or
 *                    belongs to another account (both are indistinguishable on purpose)
 */
public record DeleteCardResponse(
        @JsonProperty("ok") boolean ok,
        @JsonProperty("alreadyGone") boolean alreadyGone,
        @JsonProperty("reviewsPreserved") long reviewsPreserved) {

    public static DeleteCardResponse ofDeleted(long reviewsPreserved) {
        return new DeleteCardResponse(true, false, reviewsPreserved);
    }

    public static DeleteCardResponse ofAlreadyGone() {
        return new DeleteCardResponse(true, true, 0L);
    }
}
