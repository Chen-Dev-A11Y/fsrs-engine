package com.fsrs.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fsrs.core.CardState;
import com.fsrs.core.FsrsScheduler;
import com.fsrs.core.MemoryState;
import com.fsrs.core.Rating;
import com.fsrs.server.common.ApiException;
import com.fsrs.server.common.TimeUtils;
import com.fsrs.server.common.Transactions;
import com.fsrs.server.domain.Card;
import com.fsrs.server.domain.ReviewLog;
import com.fsrs.server.domain.User;
import com.fsrs.server.dto.study.ReviewRequest;
import com.fsrs.server.dto.study.ReviewResponse;
import com.fsrs.server.mapper.CardMapper;
import com.fsrs.server.mapper.DailyStatMapper;
import com.fsrs.server.mapper.ReviewLogMapper;
import com.fsrs.server.mapper.UserMapper;
import com.fsrs.server.redis.SrsRedisService;
import com.fsrs.server.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;

/**
 * Applies one graded review - the only write path in the service.
 *
 * <h2>Idempotency</h2>
 * A retry must not count twice. Three layers make that true, cheapest first:
 * <ol>
 *   <li>{@code srs:idem:{clientReviewId}} in Redis answers a replay without touching MySQL;</li>
 *   <li>before applying anything, {@code review_log.client_review_id} is checked in MySQL, so
 *       replays still work after the Redis record has expired or the cache was flushed;</li>
 *   <li>{@code UNIQUE KEY uk_client_review} is the final authority: if two requests slip past
 *       both checks, the second insert fails and is answered with the first one's result.</li>
 * </ol>
 *
 * <h2>Write ordering</h2>
 * MySQL first, Redis second. The transaction commits the card's new state and the log row;
 * only then - via an after-commit callback - is the due index moved and the state cache
 * invalidated. If that Redis work fails, MySQL is <b>not</b> rolled back: the review really
 * happened and must not be lost. Redis is a rebuildable index, so the periodic
 * {@code rebuildDueQueue} converges it. That is the whole consistency story, and it is
 * deliberately one-directional.
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** A review that has not appeared in Redis within this window is treated as in-flight. */
    private static final long PENDING_RETRY_MS = 400;

    /** Minimum delay before a just-reviewed card may be handed out again, whatever the model says. */
    private static final long FRESH_CARD_GRACE_MS = 60_000L;

    private final CardMapper cardMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final DailyStatMapper dailyStatMapper;
    private final UserMapper userMapper;
    private final SrsRedisService redisService;
    private final SchedulerFactory schedulerFactory;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ReviewService(CardMapper cardMapper,
                         ReviewLogMapper reviewLogMapper,
                         DailyStatMapper dailyStatMapper,
                         UserMapper userMapper,
                         SrsRedisService redisService,
                         SchedulerFactory schedulerFactory,
                         ObjectMapper objectMapper,
                         TransactionTemplate transactionTemplate) {
        this.cardMapper = cardMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.dailyStatMapper = dailyStatMapper;
        this.userMapper = userMapper;
        this.redisService = redisService;
        this.schedulerFactory = schedulerFactory;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ReviewResponse submit(long userId, ReviewRequest request) {
        if (!redisService.tryAcquireRateLimit(userId)) {
            throw ApiException.tooManyRequests("too many reviews in a short window; slow down");
        }

        // Layer 1: the Redis idempotency record, which also serialises concurrent retries.
        SrsRedisService.IdempotencyOutcome reservation = redisService.reserveIdempotency(request.clientReviewId());
        if (reservation.state() == SrsRedisService.IdempotencyState.DONE) {
            log.debug("replaying stored result for clientReviewId={}", request.clientReviewId());
            return readStoredResponse(reservation.payload());
        }
        if (reservation.state() == SrsRedisService.IdempotencyState.PENDING) {
            // Another request owns this id and is still working. Wait briefly for it to
            // publish its result; if it does not, the MySQL layer below settles it.
            ReviewResponse stored = awaitStoredResponse(request);
            if (stored != null) {
                return stored;
            }
        }
        // CLAIMED falls through: this request does the work.

        // Layer 2: the durable record. Catches replays whose Redis record expired, and races.
        ReviewLog existing = reviewLogMapper.findByClientReviewId(request.clientReviewId());
        if (existing != null) {
            return replayFromLog(existing);
        }

        try {
            ReviewResponse response = transactionTemplate.execute(status -> doApplyReview(userId, request));
            redisService.completeIdempotency(request.clientReviewId(), serialize(response));
            return response;
        } catch (DuplicateKeyException ex) {
            // Layer 3: two requests raced through layers 1 and 2 and the unique index won.
            log.info("duplicate review rejected by uk_client_review for clientReviewId={}", request.clientReviewId());
            ReviewLog winner = reviewLogMapper.findByClientReviewId(request.clientReviewId());
            if (winner == null) {
                throw ex;
            }
            return replayFromLog(winner);
        } catch (RuntimeException ex) {
            // The work produced nothing, so the id must be free for the client to retry.
            redisService.clearIdempotency(request.clientReviewId());
            throw ex;
        }
    }

    /**
     * The transactional core: read the card, run the model, persist the new state, the log row
     * and the daily counters as one unit.
     *
     * <p>Named {@code doApplyReview} and called through {@link TransactionTemplate} because a
     * {@code @Transactional} annotation on a self-invoked method is not proxied and would do
     * nothing - which would silently break the after-commit hand-off to Redis.
     */
    ReviewResponse doApplyReview(long userId, ReviewRequest request) {
        Card card = cardMapper.findByIdIncludingDeleted(request.cardId(), userId);
        if (card == null) {
            throw ApiException.notFound("card " + request.cardId() + " not found");
        }
        if (card.getUserId() == null || card.getUserId() != userId) {
            // Same message as "not found": another user's card id must not be probeable.
            throw ApiException.notFound("card " + request.cardId() + " not found");
        }
        if (card.deletedFlag()) {
            // A soft-deleted card is gone as far as the API is concerned. Grading one would
            // write to a row the user removed, and the review could never be shown again.
            throw ApiException.notFound("card " + request.cardId() + " not found");
        }
        if (card.suspendedFlag()) {
            throw ApiException.conflict("card " + card.getId() + " is suspended");
        }
        if (card.archivedFlag()) {
            // Archived cards never reach the queue, so a submission for one is either a stale
            // client or a hand-made request. It is not a 404: the card exists and is visible in
            // the deck list, and answering "gone" would contradict what the list shows. 409 is
            // the honest answer - the row is there, the state forbids the operation.
            throw ApiException.conflict("card " + card.getId() + " belongs to an archived deck");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw ApiException.unauthorized("account no longer exists");
        }
        ZoneId zone = TimeUtils.zone(user.getTimezone());
        long now = System.currentTimeMillis();
        Rating rating = Rating.of(request.rating());

        boolean firstReview = card.isNew();
        MemoryState previous = firstReview || card.getStability() == null || card.getDifficulty() == null
                ? null
                : new MemoryState(card.getStability(), card.getDifficulty());

        double elapsedDays = StudyQueueService.elapsedDays(card, now);
        double scheduledDays = scheduledIntervalDays(card);

        FsrsScheduler scheduler = schedulerFactory.forUser(userId);
        FsrsScheduler.ScheduleOutcome outcome = scheduler.apply(previous, rating, elapsedDays);
        MemoryState next = outcome.next();

        // ------------------------------------------------------------------
        // Card
        // ------------------------------------------------------------------
        card.setState(CardState.REVIEW.code());
        card.setStability(next.stability());
        card.setDifficulty(next.difficulty());
        card.setReps((card.getReps() == null ? 0 : card.getReps()) + 1);
        if (rating == Rating.AGAIN) {
            card.setLapses((card.getLapses() == null ? 0 : card.getLapses()) + 1);
        }
        long nextDue = now + Math.round(outcome.intervalDays() * 86_400_000.0);
        // A lapsed card gets a one-day interval, so without this floor a card answered with
        // "Again" at 09:00 would be due at 09:00 *today* as far as the calendar is concerned
        // and would come straight back in the same round. FRESH_CARD_GRACE_MS keeps it out of
        // the queue for a minute, which is the deliberate trade-off: this service schedules in
        // whole days and has no minute-level learning steps, so a lapsed card waits a day, and
        // the grace only stops it from reappearing inside the round that just answered it.
        nextDue = Math.max(nextDue, now + FRESH_CARD_GRACE_MS);
        card.setDueTs(nextDue);
        card.setLastReviewTs(now);
        cardMapper.updateById(card);

        // ------------------------------------------------------------------
        // Review log - the optimizer's future input, so every model input is stored
        // ------------------------------------------------------------------
        ReviewLog logRow = new ReviewLog();
        logRow.setCardId(card.getId());
        logRow.setUserId(userId);
        logRow.setRating(rating.value());
        logRow.setReviewedAt(now);
        logRow.setElapsedDays(elapsedDays);
        logRow.setScheduledDays(scheduledDays);
        logRow.setStateBefore(stateBefore(card, firstReview));
        logRow.setSBefore(previous == null ? null : previous.stability());
        logRow.setDBefore(previous == null ? null : previous.difficulty());
        logRow.setRBefore(outcome.retrievabilityAtReview());
        logRow.setDurationMs(request.durationMs());
        logRow.setClientReviewId(request.clientReviewId());
        logRow.setCreatedAt(now);
        reviewLogMapper.insert(logRow);

        // ------------------------------------------------------------------
        // Daily rollup
        // ------------------------------------------------------------------
        dailyStatMapper.accumulate(userId, TimeUtils.localDate(now, zone),
                rating == Rating.AGAIN ? 1 : 0,
                rating == Rating.HARD ? 1 : 0,
                rating == Rating.GOOD ? 1 : 0,
                rating == Rating.EASY ? 1 : 0,
                firstReview ? 1 : 0);

        ReviewResponse response = new ReviewResponse(
                card.getId(),
                nextDue,
                outcome.intervalDays(),
                next.stability(),
                next.difficulty(),
                card.getState(),
                outcome.retrievabilityAtReview(),
                false);

        // Redis is updated only once the transaction is durable. Registering the work here -
        // rather than calling it after the method returns - keeps the ordering guarantee
        // inside one place, so a future caller cannot accidentally invert it.
        long unlockAt = TimeUtils.nextLocalMidnight(now, zone);
        registerAfterCommit(userId, card, nextDue, unlockAt, request.clientReviewId());
        return response;
    }

    /**
     * Moves the due index and refreshes the state cache after the commit.
     *
     * <p>Failures are logged and swallowed on purpose: the review is already durable, and
     * the next rebuild repairs the index. Rolling back instead would lose a real review to
     * protect a cache.
     */
    private void registerAfterCommit(long userId, Card card, long nextDue, long unlockAt, String clientReviewId) {
        Transactions.afterCommit(() -> {
            try {
                redisService.releaseCard(userId, card.getId(), nextDue, unlockAt);
                redisService.cacheCardState(card);
            } catch (RuntimeException ex) {
                log.warn("Redis index update after commit failed for card {} (user {}): {}. "
                                + "MySQL is authoritative; the due-queue rebuild will converge.",
                        card.getId(), userId, ex.getMessage());
            }
        });
    }

    /**
     * State the card had before this review, as written to the log.
     *
     * <p>A first-ever grade is always logged as NEW regardless of what the column held,
     * because that is what the model actually saw ({@code previous == null}).
     *
     * <p>Mapping after a review is intentionally small: a card is NEW until its first grade
     * and REVIEW afterwards. LEARNING and RELEARNING belong to a future minute-level
     * learning-step mode; with day-granular scheduling they would be unreachable, and
     * marking a lapsed card RELEARNING would leave it there forever.
     */
    private int stateBefore(Card card, boolean firstReview) {
        if (firstReview || card.getState() == null) {
            return CardState.NEW.code();
        }
        return card.getState();
    }

    /** Interval the card had been scheduled for, derived from its stored due instant. */
    private double scheduledIntervalDays(Card card) {
        if (card.getLastReviewTs() == null || card.getDueTs() == null) {
            return 0.0;
        }
        return Math.max(0.0, (card.getDueTs() - card.getLastReviewTs()) / 86_400_000.0);
    }

    // ------------------------------------------------------------------
    // Replay helpers
    // ------------------------------------------------------------------

    /**
     * Rebuilds a response from the durable log row.
     *
     * <p>The interval is not a column of its own; it is derived from the card's current due
     * instant. For a replay that arrives before the card is graded again that value is exactly
     * what the first request produced. Redis normally answers first with the literal stored
     * response, so this path is the fallback for a replay whose Redis record is gone.
     */
    private ReviewResponse replayFromLog(ReviewLog logRow) {
        // Including deleted: a replay of a review that was accepted before the card was deleted
        // must still return the same numbers, and the log row is proof it happened.
        Card card = cardMapper.findByIdIncludingDeleted(logRow.getCardId(), logRow.getUserId());
        if (card == null) {
            throw ApiException.notFound("card " + logRow.getCardId() + " not found");
        }
        double intervalDays = scheduledIntervalDays(card);
        return new ReviewResponse(
                card.getId(),
                card.getDueTs() == null ? logRow.getReviewedAt() : card.getDueTs(),
                intervalDays,
                card.getStability() == null ? 0.0 : card.getStability(),
                card.getDifficulty() == null ? 0.0 : card.getDifficulty(),
                card.getState() == null ? CardState.REVIEW.code() : card.getState(),
                logRow.getRBefore() == null ? 0.0 : logRow.getRBefore(),
                true);
    }

    private ReviewResponse readStoredResponse(String payload) {
        try {
            return objectMapper.readValue(payload, ReviewResponse.class).asDuplicate();
        } catch (Exception ex) {
            log.warn("stored idempotency payload was unreadable: {}", ex.getMessage());
            throw ApiException.conflict("a previous submission is being processed; retry shortly");
        }
    }

    /** Short wait for an in-flight request to publish its result, then fall back to MySQL. */
    private ReviewResponse awaitStoredResponse(ReviewRequest request) {
        try {
            Thread.sleep(PENDING_RETRY_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        SrsRedisService.IdempotencyOutcome second = redisService.reserveIdempotency(request.clientReviewId());
        if (second.state() == SrsRedisService.IdempotencyState.DONE && second.payload() != null) {
            return readStoredResponse(second.payload());
        }
        ReviewLog stored = reviewLogMapper.findByClientReviewId(request.clientReviewId());
        return stored == null ? null : replayFromLog(stored);
    }

    private String serialize(ReviewResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception ex) {
            throw new IllegalStateException("unable to serialize review response", ex);
        }
    }
}
