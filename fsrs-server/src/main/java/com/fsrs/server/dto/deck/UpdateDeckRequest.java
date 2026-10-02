package com.fsrs.server.dto.deck;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/decks/{id}}.
 *
 * <p>The name is required - a deck always has one (the column is {@code NOT NULL}) - and the
 * limit matches {@code POST /api/decks} and the DDL's {@code VARCHAR(128)}.
 */
public record UpdateDeckRequest(@NotBlank @Size(max = 128) String name) {
}
