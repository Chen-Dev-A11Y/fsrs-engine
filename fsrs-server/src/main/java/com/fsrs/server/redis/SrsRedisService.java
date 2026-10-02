package com.fsrs.server.redis;

import com.fsrs.server.config.FsrsProperties;
import com.fsrs.server.domain.Card;
import com.fsrs.server.dto.card.CardIdDue;
import com.fsrs.server.mapper.CardMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Redis side of the system: a due <em>index</em>, a new-card <em>quota</em> and a
 * short-lived state <em>cache</em>.
 *
 * <h2>Consistency strategy</h2>
 * MySQL is the single source of truth; every structure in Redis can be rebuilt from it.
 * That single rule resolves every ordering question in this class:
 * <ul>
 *   <li>a review writes MySQL first, and only after the transaction commits are the Redis
 *       structures updated (see {@code ReviewService});</li>
 *   <li>if that Redis update fails, MySQL is <b>not</b> rolled back. The card is already
 *       scheduled correctly; Redis is merely stale, and {@link #rebuildDueQueue(long)}
 *       converges it on the next run;</li>
 *   <li>nothing is ever read <em>only</em> from Redis when a decision has to be correct.
 *       The queue uses Redis to pick cards cheaply, then re-reads them from MySQL before
 *       showing them, so a stale index can at worst hand out a card slightly early, never
 *       lose a review.</li>
 * </ul>
 *
 * <p>What is deliberately <b>not</b> here: distributed locks, a change-data-capture pipeline
 * or a Redis cluster. With one application instance and one Redis, an atomic Lua script plus
 * a periodic rebuild covers the same failure modes with a fraction of the machinery, and the
 * cost of the stronger design (operational complexity, a new failure domain) would not be
 * paid back by this workload.
 */
@Service
public class SrsRedisService {

    private static final Logger log = LoggerFactory.getLogger(SrsRedisService.class);

    private static final int REBUILD_BATCH_SIZE = 1000;

    private final StringRedisTemplate redis;
    private final SrsKeys keys;
    private final CardMapper cardMapper;
    private final FsrsProperties properties;
    private final RedisScript<List> acquireCardsScript;
    private final RedisScript<List> releaseCardsScript;
    private final RedisScript<List> spendNewQuotaScript;
    private final RedisScript<List> reserveIdempotencyScript;
    private final RedisScript<List> rateLimitScript;

    public SrsRedisService(StringRedisTemplate redis,
                           SrsKeys keys,
                           CardMapper cardMapper,
                           FsrsProperties properties,
                           @Qualifier("acquireCardsScript") RedisScript<List> acquireCardsScript,
                           @Qualifier("releaseCardsScript") RedisScript<List> releaseCardsScript,
                           @Qualifier("spendNewQuotaScript") RedisScript<List> spendNewQuotaScript,
                           @Qualifier("reserveIdempotencyScript") RedisScript<List> reserveIdempotencyScript,
                           @Qualifier("rateLimitScript") RedisScript<List> rateLimitScript) {
        this.redis = redis;
        this.keys = keys;
        this.cardMapper = cardMapper;
        this.properties = properties;
        this.acquireCardsScript = acquireCardsScript;
        this.releaseCardsScript = releaseCardsScript;
        this.spendNewQuotaScript = spendNewQuotaScript;
        this.reserveIdempotencyScript = reserveIdempotencyScript;
        this.rateLimitScript = rateLimitScript;
    }

    // ------------------------------------------------------------------
    // Due queue
    // ------------------------------------------------------------------

    /**
     * Atomically hands out up to {@code limit} cards, filling the round with review cards
     * first and new cards second.
     *
     * <p>Cards already in the served set are skipped, so two concurrent requests for the same
     * user never receive the same card, and a page reload returns the cards that are still
     * outstanding instead of the same batch again.
     *
     * @param unlockAtMillis absolute instant the served/introduced sets expire, i.e. the user's
     *                       next local midnight - not a TTL, because the studying day is defined
     *                       by the user's calendar
     * @return card ids in hand-out order, split into due cards and new cards
     */
    public AcquiredCards acquireCards(long userId, long nowMillis, int limit, long unlockAtMillis) {
        if (limit <= 0) {
            return AcquiredCards.empty();
        }
        List<String> args = List.of(
                Long.toString(nowMillis),
                Integer.toString(limit),
                Long.toString(unlockAtMillis));
        List<String> raw = execute(acquireCardsScript,
                List.of(keys.dueQueue(userId), keys.session(userId), keys.newServed(userId)), args);

        List<Long> due = new ArrayList<>();
        List<Long> fresh = new ArrayList<>();
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            Long cardId = parseLong(raw.get(i));
            if (cardId == null) {
                continue;
            }
            if ("new".equals(raw.get(i + 1))) {
                fresh.add(cardId);
            } else {
                due.add(cardId);
            }
        }
        return new AcquiredCards(due, fresh);
    }

    /**
     * Puts a reviewed card back into the index with its new due time, and clears it from the
     * session so it can be handed out again once it falls due.
     */
    public void releaseCard(long userId, long cardId, long newDueMillis, long unlockAtMillis) {
        execute(releaseCardsScript,
                List.of(keys.dueQueue(userId), keys.session(userId), keys.newServed(userId)),
                List.of(Long.toString(cardId), Long.toString(newDueMillis), Long.toString(unlockAtMillis)));
    }

    /** Removes a card from the index entirely, used when it is suspended or deleted. */
    public void removeFromDueQueue(long userId, long cardId) {
        unindexCard(userId, cardId);
    }

    /**
     * Erases every trace of one card from Redis.
     *
     * <p>Used by suspension and deletion, which is why it covers the due index, the
     * served-this-round session, the served-new set and the cached state: leaving the id in
     * the session set would keep a deleted card occupying a slot in the current round, and a
     * stale {@code srs:state:{cardId}} hash would keep answering for a card that no longer
     * exists. All four are safe to remove for any card, and none of them is authoritative, so
     * a failure here is repaired by {@link #rebuildDueQueue(long)} rather than by failing the
     * caller's already-committed write.
     */
    public void unindexCard(long userId, long cardId) {
        String id = Long.toString(cardId);
        redis.opsForZSet().remove(keys.dueQueue(userId), id);
        redis.opsForSet().remove(keys.session(userId), id);
        redis.opsForSet().remove(keys.newServed(userId), id);
        redis.delete(keys.cardState(cardId));
    }

    /**
     * Adds a freshly created card to the due index at its initial due instant.
     *
     * <p>Without this, a new card would be invisible until the next rebuild - up to one
     * interval of "I just added a card and it never appears".
     */
    public void indexCard(long userId, long cardId, long dueMillis) {
        redis.opsForZSet().add(keys.dueQueue(userId), Long.toString(cardId), (double) dueMillis);
    }

    /**
     * Re-indexes a card only while the database still says it belongs in the queue.
     *
     * <p>Restoring a card or a deck has to put cards back, but both the value captured earlier
     * in the request and the card's own flags can be stale by the time this runs: the card may
     * have been deleted, or its deck may have been deleted or archived - and a restore is
     * explicitly not allowed to override an archive marker. Asking the source of truth here
     * means a card that must not be studied can never be inserted into the index, so nothing
     * the next rebuild would disagree with can survive longer than one interval.
     */
    public void indexOwnedCard(long userId, long cardId, long dueMillis) {
        if (cardMapper.findIndexableDeckId(cardId, userId) == null) {
            unindexCard(userId, cardId);
            return;
        }
        indexCard(userId, cardId, dueMillis);
    }

    /**
     * Rebuilds {@code srs:due:{userId}} from MySQL.
     *
     * <p>This is the convergence mechanism the whole design leans on: it runs at startup and
     * on a timer, so any review whose Redis update was lost is repaired. It is written as a
     * full overwrite in a temporary key followed by {@code RENAME}, because building the
     * ZSet in place would leave a window where the queue is half-populated and a request
     * would see no cards at all.
     *
     * <p>The source query excludes suspended and soft-deleted cards
     * ({@code CardMapper.findAllDueRows}), which is what makes this safe to point at a
     * database with deleted rows: a full overwrite that included them would put a deleted card
     * straight back into the queue on the next tick.
     *
     * @return number of cards indexed
     */
    public long rebuildDueQueue(long userId) {
        List<CardIdDue> rows = cardMapper.findAllDueRows(userId);
        String target = keys.dueQueue(userId);
        // A temporary key cannot use the hash-tag-free naming of the target, so it is derived
        // from it to stay inside the same prefix namespace.
        String staging = target + ":rebuilding:" + ThreadLocalRandom.current().nextInt(1_000_000);
        try {
            for (int offset = 0; offset < rows.size(); offset += REBUILD_BATCH_SIZE) {
                int end = Math.min(offset + REBUILD_BATCH_SIZE, rows.size());
                // Bulk ZADD per batch: one round trip per thousand cards instead of one per card.
                Set<ZSetOperations.TypedTuple<String>> batch = new HashSet<>();
                for (int i = offset; i < end; i++) {
                    CardIdDue row = rows.get(i);
                    if (row.getId() != null && row.getDueTs() != null) {
                        batch.add(new DefaultTypedTuple<>(Long.toString(row.getId()), (double) row.getDueTs()));
                    }
                }
                if (!batch.isEmpty()) {
                    redis.opsForZSet().add(staging, batch);
                }
            }
            if (rows.isEmpty()) {
                // An empty user still needs the key to exist so counts read as 0 rather than null.
                redis.opsForZSet().add(staging, "-1", Double.MAX_VALUE);
                redis.opsForZSet().remove(staging, "-1");
            }
            redis.rename(staging, target);
            redis.delete(keys.session(userId));
            return rows.size();
        } catch (RuntimeException ex) {
            // Never let a convergence failure take traffic down; the previous index survives.
            log.warn("rebuildDueQueue failed for user {}: {}", userId, ex.getMessage());
            redis.delete(staging);
            throw ex;
        }
    }

    /** Every user that has cards; drives the periodic rebuild job. */
    public List<Long> usersWithCards() {
        return cardMapper.selectDistinctUserIds();
    }

    public long dueQueueSize(long userId, long upToMillis) {
        Long size = redis.opsForZSet().count(keys.dueQueue(userId), Double.NEGATIVE_INFINITY, (double) upToMillis);
        return size == null ? 0L : size;
    }

    public long sessionSize(long userId) {
        Long size = redis.opsForSet().size(keys.session(userId));
        return size == null ? 0L : size;
    }

    // ------------------------------------------------------------------
    // New-card quota
    // ------------------------------------------------------------------

    /**
     * Spends {@code requested} units of today's new-card allowance, capped at {@code limit}.
     *
     * @param expireAtMillis the user's next local midnight
     * @return how many units were actually granted
     */
    public int spendNewQuota(long userId, int requested, int limit, long expireAtMillis) {
        if (requested <= 0 || limit <= 0) {
            return 0;
        }
        List<String> raw = execute(spendNewQuotaScript, List.of(keys.newQuota(userId)),
                List.of(Integer.toString(requested), Integer.toString(limit),
                        Long.toString(expireAtMillis / 1000L)));
        if (raw.size() < 2) {
            return 0;
        }
        Integer granted = parseInt(raw.get(1));
        return granted == null ? 0 : granted;
    }

    public int newCardsServedToday(long userId) {
        String value = redis.opsForValue().get(keys.newQuota(userId));
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    /**
     * Claims a {@code clientReviewId} before the work starts.
     *
     * @return {@code claimed} when this caller owns the id, {@code pending} when another
     *         request is still working on it, or {@code done} together with the stored
     *         response when it has already been completed
     */
    public IdempotencyOutcome reserveIdempotency(String clientReviewId) {
        List<String> raw = execute(reserveIdempotencyScript, List.of(keys.idempotency(clientReviewId)),
                List.of("reserve", "PENDING", Long.toString(properties.redis().idempotencyTtlSeconds())));
        if (raw.size() < 2) {
            return new IdempotencyOutcome(IdempotencyState.PENDING, null);
        }
        String status = raw.get(0);
        IdempotencyState state;
        if ("claimed".equals(status)) {
            state = IdempotencyState.CLAIMED;
        } else if ("done".equals(status)) {
            state = IdempotencyState.DONE;
        } else {
            state = IdempotencyState.PENDING;
        }
        return new IdempotencyOutcome(state, state == IdempotencyState.DONE ? raw.get(1) : null);
    }

    /** Stores the finished response so a replay returns byte-identical numbers. */
    public void completeIdempotency(String clientReviewId, String responseJson) {
        execute(reserveIdempotencyScript, List.of(keys.idempotency(clientReviewId)),
                List.of("complete", responseJson, Long.toString(properties.redis().idempotencyTtlSeconds())));
    }

    /** Releases a reservation that produced no result, so the client may retry with the same id. */
    public void clearIdempotency(String clientReviewId) {
        redis.delete(keys.idempotency(clientReviewId));
    }

    // ------------------------------------------------------------------
    // State cache
    // ------------------------------------------------------------------

    /**
     * Caches the scheduling state of a hot card as JSON.
     *
     * <p>The TTL carries random jitter: without it, a batch of cards cached during one study
     * session would expire in the same second and the following requests would all miss at
     * once, which is the cache-avalanche pattern. The jitter costs nothing and removes the
     * synchronised miss.
     */
    public void cacheCardState(Card card) {
        long jitter = properties.redis().stateTtlJitterSeconds();
        long ttl = properties.redis().stateTtlSeconds() + (jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter));
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("id", card.getId());
            state.put("userId", card.getUserId());
            state.put("deckId", card.getDeckId());
            state.put("state", card.getState());
            state.put("dueTs", card.getDueTs());
            state.put("stability", card.getStability());
            state.put("difficulty", card.getDifficulty());
            state.put("reps", card.getReps());
            state.put("lapses", card.getLapses());
            state.put("lastReviewTs", card.getLastReviewTs());
            redis.opsForHash().putAll(keys.cardState(card.getId()), stringify(state));
            redis.expire(keys.cardState(card.getId()), Duration.ofSeconds(ttl));
        } catch (RuntimeException ex) {
            // The cache is an optimisation; failing to write it must never fail a request.
            log.debug("cacheCardState skipped for card {}: {}", card.getId(), ex.getMessage());
        }
    }

    public void invalidateCardState(long cardId) {
        redis.delete(keys.cardState(cardId));
    }

    // ------------------------------------------------------------------
    // Rate limiting
    // ------------------------------------------------------------------

    /**
     * Fixed-window throttle, applied to review submissions.
     *
     * @return {@code true} when the caller is still inside the window
     */
    public boolean tryAcquireRateLimit(long userId) {
        FsrsProperties.RateLimit config = properties.redis().ratelimit();
        if (config == null || config.maxRequests() <= 0) {
            return true;
        }
        List<String> raw = execute(rateLimitScript, List.of(keys.rateLimit(userId)),
                List.of(Integer.toString(config.maxRequests()), Integer.toString(config.windowSeconds())));
        if (raw.isEmpty()) {
            // Fail open: a throttling outage must not block studying.
            return true;
        }
        return "1".equals(raw.get(0));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<String> execute(RedisScript<List> script, List<String> scriptKeys, List<String> args) {
        List<Object> raw = redis.execute(script, scriptKeys, args.toArray());
        if (raw == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(raw.size());
        for (Object element : raw) {
            values.add(element == null ? null : element.toString());
        }
        return values;
    }

    private Map<String, String> stringify(Map<String, Object> source) {
        Map<String, String> result = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
        return result;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Outcome of {@link #acquireCards}. */
    public record AcquiredCards(List<Long> dueCardIds, List<Long> newCardIds) {

        static AcquiredCards empty() {
            return new AcquiredCards(List.of(), List.of());
        }

        public boolean isEmpty() {
            return dueCardIds.isEmpty() && newCardIds.isEmpty();
        }

        public List<Long> allIds() {
            List<Long> all = new ArrayList<>(dueCardIds.size() + newCardIds.size());
            all.addAll(dueCardIds);
            all.addAll(newCardIds);
            return all;
        }
    }

    /** State of an idempotency record. */
    public enum IdempotencyState {
        /** This caller owns the id and must do the work. */
        CLAIMED,
        /** Another request holds the id and has not finished yet. */
        PENDING,
        /** A finished response exists and must be replayed. */
        DONE
    }

    /** @param state see {@link IdempotencyState} @param payload stored response JSON when {@code DONE} */
    public record IdempotencyOutcome(IdempotencyState state, String payload) {
    }
}
