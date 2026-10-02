package com.fsrs.server.dto.study;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @param totalDue review cards waiting, independent of this request's limit
 * @param totalNew new cards still inside today's allowance
 * @param cards    the cards handed out for this round
 */
public record StudyQueueResponse(
        @JsonProperty("totalDue") long totalDue,
        @JsonProperty("totalNew") long totalNew,
        @JsonProperty("cards") List<CardQueueItem> cards) {
}
