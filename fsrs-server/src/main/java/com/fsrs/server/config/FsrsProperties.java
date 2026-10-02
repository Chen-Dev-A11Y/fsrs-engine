package com.fsrs.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the JWT and Redis layers, bound from the {@code fsrs.*} block.
 *
 * @param jwt       token signing and lifetime settings
 * @param redis     key layout and TTLs
 * @param scheduler background convergence settings
 * @param demo      demo-data seeding settings
 */
@ConfigurationProperties(prefix = "fsrs")
public record FsrsProperties(Jwt jwt, Redis redis, Scheduler scheduler, Demo demo) {

    /**
     * @param secret     HMAC-SHA256 key material
     * @param ttlSeconds token lifetime
     */
    public record Jwt(String secret, long ttlSeconds) {
    }

    /**
     * @param keyPrefix             prefix applied to every key, so one Redis can host several services
     * @param sessionTtlSeconds     lifetime of the "cards handed out in this round" set
     * @param idempotencyTtlSeconds lifetime of a review idempotency record
     * @param stateTtlSeconds       base TTL of the per-card state cache (jitter is added on top)
     * @param stateTtlJitterSeconds maximum random jitter, which prevents a cache avalanche
     * @param ratelimit             review-endpoint throttle
     */
    public record Redis(
            String keyPrefix,
            long sessionTtlSeconds,
            long idempotencyTtlSeconds,
            long stateTtlSeconds,
            long stateTtlJitterSeconds,
            RateLimit ratelimit) {
    }

    /**
     * @param maxRequests   requests allowed per window per user
     * @param windowSeconds window length
     */
    public record RateLimit(int maxRequests, int windowSeconds) {
    }

    /**
     * @param rebuildOnStartup  rebuild the Redis due index from MySQL on boot
     * @param rebuildIntervalMs how often the periodic convergence job runs
     * @param dailyNewCardLimit default daily allowance of new cards per user
     */
    public record Scheduler(boolean rebuildOnStartup, long rebuildIntervalMs, int dailyNewCardLimit) {
    }

    /**
     * @param seedCardsOnStartup generate the demonstration deck on first boot
     */
    public record Demo(boolean seedCardsOnStartup) {
    }
}
