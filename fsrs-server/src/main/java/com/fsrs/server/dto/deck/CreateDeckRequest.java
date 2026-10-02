package com.fsrs.server.dto.deck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/decks}. */
public record CreateDeckRequest(@NotBlank @Size(max = 128) String name) {
}
