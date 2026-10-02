package com.fsrs.server.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Timezone arithmetic for "what day is it for this user?".
 *
 * <p>The whole service stores instants as epoch milliseconds in UTC. A natural day -
 * the unit of "due today", the daily new-card quota and the heatmap - only exists
 * relative to a user's zone, so every day boundary is computed here and nowhere else.
 * If this logic were spread across the services, the heatmap and the quota would
 * eventually disagree about which day a 23:50 review belonged to.
 */
public final class TimeUtils {

    /** Fixed fallback so an unparsable zone can never take the service down. */
    private static final ZoneId FALLBACK_ZONE = ZoneOffset.UTC;

    private TimeUtils() {
    }

    /** Resolves an IANA zone id, falling back to UTC instead of throwing. */
    public static ZoneId zone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (RuntimeException ex) {
            return FALLBACK_ZONE;
        }
    }

    /** The user-local calendar day that contains the given instant. */
    public static LocalDate localDate(long epochMillis, ZoneId zone) {
        return Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate();
    }

    /** Start of the user-local day (00:00) as epoch millis. */
    public static long startOfDay(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /**
     * The instant the user's current studying day ends, i.e. their next local midnight.
     * This is what the daily quota and the issued-cards set expire at.
     */
    public static long nextLocalMidnight(long epochMillis, ZoneId zone) {
        LocalDate today = localDate(epochMillis, zone);
        return today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** The same instant expressed in epoch seconds, for Redis {@code EXPIREAT}. */
    public static long toEpochSeconds(long epochMillis) {
        return epochMillis / 1000L;
    }

    /** {@code yyyy-MM-dd} of the user-local day containing the instant. */
    public static String localDayString(long epochMillis, ZoneId zone) {
        return localDate(epochMillis, zone).toString();
    }
}
