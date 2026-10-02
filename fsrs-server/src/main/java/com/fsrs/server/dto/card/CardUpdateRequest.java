package com.fsrs.server.dto.card;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/cards/{id}} - a <b>partial</b> update.
 *
 * <p>Every field is optional, and {@code null} means "not mentioned in this request" rather
 * than "clear it". That is why {@code suspended} is a boxed {@link Boolean}: a primitive
 * {@code boolean} would default to {@code false} for a body that only carries {@code front},
 * and renaming a typo would silently resume a suspended card.
 *
 * <p>There is deliberately no {@code deckId}: moving a card between decks is a different
 * operation with its own consistency questions (the Redis index is per user, but the counters
 * are per deck) and is not part of this contract.
 *
 * <p>{@code front} and {@code back} keep the limits of {@code POST /api/cards}, so a card can
 * never be edited into a shape it could not have been created in.
 */
public class CardUpdateRequest {

    @Size(max = 512)
    private String front;

    @Size(max = 1024)
    private String back;

    private Boolean suspended;

    public String getFront() {
        return front;
    }

    public void setFront(String front) {
        this.front = front;
    }

    public String getBack() {
        return back;
    }

    public void setBack(String back) {
        this.back = back;
    }

    public Boolean getSuspended() {
        return suspended;
    }

    public void setSuspended(Boolean suspended) {
        this.suspended = suspended;
    }

    /**
     * Rejects whitespace-only text.
     *
     * <p>Written as a cross-field rule instead of {@code @NotBlank} on each field because
     * {@code @NotBlank} would also reject {@code null} - and {@code null} is exactly how an
     * omitted field is expressed here. A blank string is an attempt to blank out the card,
     * which the column ({@code VARCHAR NOT NULL}) could not represent anyway.
     */
    @JsonIgnore
    @AssertTrue(message = "front and back must not be blank when provided")
    public boolean isProvidedTextNonBlank() {
        return isBlankOrAbsent(front) && isBlankOrAbsent(back);
    }

    private static boolean isBlankOrAbsent(String value) {
        return value == null || !value.trim().isEmpty();
    }
}
