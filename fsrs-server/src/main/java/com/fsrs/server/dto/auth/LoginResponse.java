package com.fsrs.server.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Successful login.
 *
 * @param token    bearer token to send as {@code Authorization: Bearer <token>}
 * @param username echoed for the UI header
 * @param userId   the id every other endpoint is scoped to
 */
public record LoginResponse(
        @JsonProperty("token") String token,
        @JsonProperty("username") String username,
        @JsonProperty("userId") long userId) {
}
