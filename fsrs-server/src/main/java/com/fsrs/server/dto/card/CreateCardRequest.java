package com.fsrs.server.dto.card;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/cards}. */
public record CreateCardRequest(
        @NotNull Long deckId,
        @NotBlank @Size(max = 512) String front,
        @NotBlank @Size(max = 1024) String back) {
}
