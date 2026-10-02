package com.fsrs.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * Redis wiring.
 *
 * <p>All keys and members are written as plain UTF-8 strings. That is a deliberate
 * choice over a binary value serializer: the due-queue members in {@code redis-cli}
 * are readable card ids, and no Java class names end up inside the keyspace. The few
 * JSON payloads (cached card state, replayed review response) are produced by the
 * application's own {@code ObjectMapper}, so the format is explicit at the call site.
 *
 * <p>Each Lua script is registered as a named bean; {@code DefaultRedisScript}
 * computes the SHA1 on load and uses {@code EVALSHA} after the first call.
 */
@Configuration
public class RedisConfig {

    /** @see ClassPathResource lua/acquire_cards.lua */
    @Bean
    public RedisScript<List> acquireCardsScript() {
        return script("lua/acquire_cards.lua", List.class);
    }

    /** @see ClassPathResource lua/release_cards.lua */
    @Bean
    public RedisScript<List> releaseCardsScript() {
        return script("lua/release_cards.lua", List.class);
    }

    /** @see ClassPathResource lua/spend_new_quota.lua */
    @Bean
    public RedisScript<List> spendNewQuotaScript() {
        return script("lua/spend_new_quota.lua", List.class);
    }

    /** @see ClassPathResource lua/reserve_idempotency.lua */
    @Bean
    public RedisScript<List> reserveIdempotencyScript() {
        return script("lua/reserve_idempotency.lua", List.class);
    }

    /** @see ClassPathResource lua/rate_limit.lua */
    @Bean
    public RedisScript<List> rateLimitScript() {
        return script("lua/rate_limit.lua", List.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> RedisScript<T> script(String classpathLocation, Class<T> resultType) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        redisScript.setResultType((Class) resultType);
        return redisScript;
    }
}
