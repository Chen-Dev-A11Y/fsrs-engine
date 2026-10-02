package com.fsrs.server.service;

import com.fsrs.core.FsrsScheduler;
import com.fsrs.core.Rating;
import com.fsrs.server.common.ApiException;
import com.fsrs.server.common.TimeUtils;
import com.fsrs.server.config.FsrsProperties;
import com.fsrs.server.domain.Card;
import com.fsrs.server.domain.Deck;
import com.fsrs.server.domain.User;
import com.fsrs.server.dto.study.CardQueueItem;
import com.fsrs.server.dto.study.StudyQueueResponse;
import com.fsrs.server.mapper.CardMapper;
import com.fsrs.server.mapper.DeckMapper;
import com.fsrs.server.mapper.UserMapper;
import com.fsrs.server.redis.SrsRedisService;
import com.fsrs.server.scheduler.SchedulerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the study queue for one round.
 *
 * <p>Order of operations is the design:
 * <ol>
 *   <li>read the user's timezone, because "today" is a user-local concept;</li>
 *   <li>hand out cards atomically (Lua) from the Redis index, which also claims them so two
 *       concurrent requests for the same user never see the same card;</li>
 *   <li>re-read the claimed cards from MySQL - the source of truth - before showing them,
 *       so a stale index can hand a card out a little early but never show stale numbers;</li>
 *   <li>compute the four interval predictions with the deterministic scheduler.</li>
 * </ol>
 */
@Service
public class StudyQueueService {

    /** Hard ceiling on one round, so a malicious `limit` cannot ask for 10 million rows. */
    private static final int MAX_LIMIT = 200;

    private final CardMapper cardMapper;
    private final DeckMapper deckMapper;
    private final UserMapper userMapper;
    private final SrsRedisService redisService;
    private final SchedulerFactory schedulerFactory;
    private final FsrsProperties properties;

    public StudyQueueService(CardMapper cardMapper,
                             DeckMapper deckMapper,
                             UserMapper userMapper,
                             SrsRedisService redisService,
                             SchedulerFactory schedulerFactory,
                             FsrsProperties properties) {
        this.cardMapper = cardMapper;
        this.deckMapper = deckMapper;
        this.userMapper = userMapper;
        this.redisService = redisService;
        this.schedulerFactory = schedulerFactory;
        this.properties = properties;
    }

    public StudyQueueResponse queue(long userId, Long deckId, int limit) {
        User user = requireUser(userId);
        ZoneId zone = TimeUtils.zone(user.getTimezone());
        long now = System.currentTimeMillis();
        long unlockAt = TimeUtils.nextLocalMidnight(now, zone);

        int effectiveLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int dailyNewLimit = Math.max(0, properties.scheduler().dailyNewCardLimit());

        // One round worth of cards, review cards first. The script itself skips anything already
        // handed out and refuses to introduce the same new card twice in a day.
        SrsRedisService.AcquiredCards acquired =
                redisService.acquireCards(userId, now, effectiveLimit, unlockAt);
        if (!acquired.newCardIds().isEmpty()) {
            // The script is the only writer of the introduced-today set, so billing the
            // difference keeps the counter equal to the number of distinct new cards served.
            redisService.spendNewQuota(userId, acquired.newCardIds().size(), dailyNewLimit, unlockAt);
        }

        List<CardQueueItem> items = loadItems(userId, deckId, acquired, now);

        // totalDue / totalNew are the *workload in scope*, not "what this round has left".
        // They are deliberately independent of how many cards were already handed out:
        //   * a total that shrank on every reload would make a progress bar jump backwards, and
        //   * the frontend can derive "remaining" itself as total - cards already submitted.
        // They are also independent of the Redis served set, so a stale index cannot make the
        // dashboard disagree with MySQL.
        long totalDue = cardMapper.countDue(userId, deckId, now);
        long totalNew = Math.min(cardMapper.countNew(userId, deckId), dailyNewLimit);

        return new StudyQueueResponse(totalDue, totalNew, items);
    }

    /**
     * Loads the claimed cards in hand-out order and filters out anything that must not be
     * shown: suspended, soft-deleted, archived, or belonging to another deck than the requested
     * one.
     *
     * <p>The deleted and archived checks are not redundant with the Redis cleanup. The due index
     * is a rebuildable cache, so a card whose Redis entry could not be removed - because Redis
     * was unreachable at the time - is still claimable here, and
     * {@code cardMapper.selectBatchIds} would happily return it. Refusing it at this point is
     * what guarantees that a deleted or archived card is never handed to a learner, whatever the
     * index believes.
     */
    private List<CardQueueItem> loadItems(long userId, Long deckId,
                                         SrsRedisService.AcquiredCards acquired, long now) {
        List<Long> ids = acquired.allIds();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Card> cards = cardMapper.selectBatchIds(ids);
        Map<Long, Card> byId = new HashMap<>(cards.size());
        for (Card card : cards) {
            // studyable() is "not deleted and not archived", the same predicate every workload
            // query is built from - so the queue cannot drift from the counters.
            if (card.getUserId() != null && card.getUserId() == userId
                    && card.studyable() && !card.suspendedFlag()) {
                byId.put(card.getId(), card);
            }
        }

        Map<Long, String> deckNames = deckNames(userId);
        FsrsScheduler scheduler = schedulerFactory.deterministicForUser(userId);

        List<CardQueueItem> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Card card = byId.get(id);
            if (card == null) {
                continue;
            }
            if (deckId != null && !deckId.equals(card.getDeckId())) {
                continue;
            }
            items.add(toItem(card, scheduler, now, deckNames));
        }
        return items;
    }

    /** Maps the four grades to the interval each would produce. */
    public CardQueueItem toItem(Card card, FsrsScheduler scheduler, long now, Map<Long, String> deckNames) {
        boolean isNew = card.isNew();
        Double stability = card.getStability();
        Double difficulty = card.getDifficulty();
        Double retrievability = null;
        List<Double> predictions = new ArrayList<>(4);

        if (isNew) {
            // No memory state yet: R is defined as 1 and the model starts from S0/D0.
            retrievability = 1.0;
            for (Rating rating : Rating.values()) {
                predictions.add(scheduler.apply(null, rating, 0.0).intervalDays());
            }
        } else {
            double elapsedDays = elapsedDays(card, now);
            retrievability = scheduler.retrievability((int) elapsedDays, stability);
            for (Rating rating : Rating.values()) {
                predictions.add(scheduler.apply(
                        new com.fsrs.core.MemoryState(stability, difficulty), rating, elapsedDays).intervalDays());
            }
        }

        return new CardQueueItem(
                card.getId(),
                card.getDeckId(),
                card.getFront(),
                card.getBack(),
                card.getState(),
                stability,
                difficulty,
                retrievability,
                isNew,
                CardQueueItem.PredictedIntervals.of(predictions));
    }

    /** Whole days since the last review; the model itself truncates, so this returns a whole number. */
    public static double elapsedDays(Card card, long now) {
        if (card.getLastReviewTs() == null) {
            return 0.0;
        }
        long delta = now - card.getLastReviewTs();
        return delta <= 0 ? 0.0 : delta / 86_400_000.0;
    }

    private Map<Long, String> deckNames(long userId) {
        // Includes archived decks: a card that is somehow still handed out should show its
        // deck name rather than an empty label.
        List<Deck> decks = deckMapper.findLiveByUser(userId);
        Map<Long, String> names = new HashMap<>(decks.size());
        for (Deck deck : decks) {
            names.put(deck.getId(), deck.getName());
        }
        return names;
    }

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw ApiException.unauthorized("account no longer exists");
        }
        return user;
    }
}
