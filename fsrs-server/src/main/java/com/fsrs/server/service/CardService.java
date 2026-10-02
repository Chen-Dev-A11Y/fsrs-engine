package com.fsrs.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fsrs.core.CardState;
import com.fsrs.server.common.ApiException;
import com.fsrs.server.common.Transactions;
import com.fsrs.server.domain.Card;
import com.fsrs.server.domain.Deck;
import com.fsrs.server.domain.ReviewLog;
import com.fsrs.server.dto.card.CardTimelineResponse;
import com.fsrs.server.dto.card.CardUpdateRequest;
import com.fsrs.server.dto.card.CardView;
import com.fsrs.server.dto.card.CreateCardRequest;
import com.fsrs.server.dto.card.DeleteCardResponse;
import com.fsrs.server.dto.card.DeletedCardResponse;
import com.fsrs.server.dto.deck.DeleteDeckResponse;
import com.fsrs.server.mapper.CardMapper;
import com.fsrs.server.mapper.DeckMapper;
import com.fsrs.server.mapper.ReviewLogMapper;
import com.fsrs.server.redis.SrsRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Card CRUD, delete/restore, and the review timeline.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>every read and write is scoped by the authenticated {@code userId}, so one account can
 *       never address another's rows even by guessing an id - and a row belonging to somebody
 *       else is reported as missing rather than forbidden, because the difference between the
 *       two is itself information;</li>
 *   <li>deletion is a <b>soft</b> delete: the card stops being visible, its {@code review_log}
 *       rows are kept forever, and {@code POST /api/cards/{id}/restore} brings it back with its
 *       memory state intact. History is the parameter optimizer's training data, so throwing it
 *       away would destroy samples that cannot be recovered;</li>
 *   <li>a soft-deleted card is treated as non-existent by every other endpoint: no edit, no
 *       suspend, no review, not in the queue;</li>
 *   <li>creating, updating, suspending, deleting or restoring a card keeps the Redis due index
 *       in step, and every one of those writes happens in an after-commit callback: MySQL is
 *       committed first, Redis second, and a Redis failure is logged and swallowed because the
 *       index can be rebuilt from the database. The reverse order would let a rollback leave
 *       the queue pointing at a card that does not exist.
 * </ul>
 */
@Service
public class CardService {

    private static final Logger log = LoggerFactory.getLogger(CardService.class);

    private final CardMapper cardMapper;
    private final ReviewLogMapper reviewLogMapper;
    private final DeckMapper deckMapper;
    private final SrsRedisService redisService;
    private final TransactionTemplate transactionTemplate;

    public CardService(CardMapper cardMapper,
                       ReviewLogMapper reviewLogMapper,
                       DeckMapper deckMapper,
                       SrsRedisService redisService,
                       TransactionTemplate transactionTemplate) {
        this.cardMapper = cardMapper;
        this.reviewLogMapper = reviewLogMapper;
        this.deckMapper = deckMapper;
        this.redisService = redisService;
        this.transactionTemplate = transactionTemplate;
    }

    public List<CardView> list(long userId, Long deckId) {
        QueryWrapper<Card> query = new QueryWrapper<Card>()
                .eq("user_id", userId)
                .isNull("deleted_at");
        if (deckId != null) {
            requireOwnedDeck(userId, deckId);
            query.eq("deck_id", deckId);
        }
        query.orderByAsc("id");
        return cardMapper.selectList(query).stream().map(CardView::of).toList();
    }

    /**
     * Creates a card and makes it immediately available to the study queue.
     *
     * <p>{@code due_ts} is set to "now" rather than to a far-future sentinel: a new card is
     * eligible as soon as the learner has new-card allowance, and the quota - not the due
     * instant - is what stops every card from being introduced at once.
     */
    public CardView create(long userId, CreateCardRequest request) {
        return transactionTemplate.execute(status -> doCreate(userId, request));
    }

    /**
     * Creation body, executed inside an explicit transaction.
     *
     * <p>The transaction is driven by {@link TransactionTemplate} rather than by the
     * {@code @Transactional} annotation alone. A self-invoked {@code @Transactional} method is
     * not proxied, so the annotation on this method would silently do nothing - and then
     * {@code Transactions.afterCommit} would fire immediately instead of after the commit,
     * which is exactly the "card not in the queue yet" race it is meant to prevent.
     */
    CardView doCreate(long userId, CreateCardRequest request) {
        Deck deck = requireOwnedDeck(userId, request.deckId());
        long now = System.currentTimeMillis();

        Card card = new Card();
        card.setUserId(userId);
        card.setDeckId(deck.getId());
        card.setFront(request.front().trim());
        card.setBack(request.back().trim());
        card.setState(CardState.NEW.code());
        card.setDueTs(now);
        card.setReps(0);
        card.setLapses(0);
        card.setSuspended(0);
        card.setCreatedAt(now);
        cardMapper.insert(card);

        // The Redis index is an optimisation; a failure here is repaired by the rebuild job and
        // must not fail the card creation the user just asked for. It is registered for after
        // commit on purpose: indexing the card before the insert is durable would let a rollback
        // leave a card id in the queue that does not exist.
        Transactions.afterCommit(() -> {
            try {
                redisService.indexCard(userId, card.getId(), now);
            } catch (RuntimeException ignored) {
                // Deliberately swallowed; see comment above.
            }
        });
        return CardView.of(card);
    }

    /** Suspending keeps the history but takes the card out of the study queue. */
    public void suspend(long userId, long cardId) {
        transactionTemplate.executeWithoutResult(status -> {
            Card card = requireLiveCard(userId, cardId);
            cardMapper.setSuspended(card.getId(), userId, 1);
            Transactions.afterCommit(() -> {
                try {
                    redisService.unindexCard(userId, cardId);
                } catch (RuntimeException ex) {
                    log.warn("Redis cleanup after suspend failed for card {} (user {}): {}",
                            cardId, userId, ex.getMessage());
                }
            });
        });
    }

    public CardView get(long userId, long cardId) {
        return CardView.of(requireLiveCard(userId, cardId));
    }

    /**
     * Partially updates a card: text, suspension, or both.
     *
     * <p><b>Editing the text never touches the memory state.</b> {@code state}, {@code due_ts},
     * {@code stability}, {@code difficulty}, {@code reps}, {@code lapses} and
     * {@code last_review_ts} are written here only when the caller explicitly suspends or
     * resumes the card; a rename leaves every one of them exactly as it was. The user is
     * correcting a typo, not re-learning the card, and resetting the schedule would throw away
     * work they actually did - while keeping it costs nothing, because the answer is unchanged
     * and the review history that produced S and D is still valid.
     *
     * <p>Absent fields are left alone: {@code null} in the body means "not mentioned", which is
     * why a body carrying only {@code front} cannot resume a suspended card.
     *
     * <p>A soft-deleted card cannot be edited: it is reported as missing.
     */
    public CardView update(long userId, long cardId, CardUpdateRequest request) {
        return transactionTemplate.execute(status -> doUpdate(userId, cardId, request));
    }

    /** Transactional body of {@link #update}; see there for the invariants. */
    CardView doUpdate(long userId, long cardId, CardUpdateRequest request) {
        Card card = requireLiveCard(userId, cardId);

        boolean textChanged = false;
        if (request.getFront() != null) {
            card.setFront(request.getFront().trim());
            textChanged = true;
        }
        if (request.getBack() != null) {
            card.setBack(request.getBack().trim());
            textChanged = true;
        }

        Boolean suspended = request.getSuspended();
        boolean suspensionChanged = suspended != null && suspended != card.suspendedFlag();
        if (suspensionChanged) {
            card.setSuspended(suspended ? 1 : 0);
        }

        // Nothing in the body: answer with the current card instead of issuing a write whose
        // only effect would be a row version bump.
        if (!textChanged && !suspensionChanged) {
            return CardView.of(card);
        }

        // updateById only writes the non-null fields of the entity, so the seven memory-state
        // columns are simply not part of the statement - which is exactly the guarantee wanted
        // here. The explicit `suspended` above is the only state this method may change.
        cardMapper.updateById(card);

        // Only suspension moves the due index; a text edit must not, or an edit would change
        // when the card is next shown.
        if (suspensionChanged) {
            long dueTs = card.getDueTs() == null ? System.currentTimeMillis() : card.getDueTs();
            boolean nowSuspended = card.suspendedFlag();
            Transactions.afterCommit(() -> {
                try {
                    if (nowSuspended) {
                        redisService.unindexCard(userId, cardId);
                    } else {
                        redisService.indexOwnedCard(userId, cardId, dueTs);
                    }
                    // The cached state hash mirrors the row; it is dropped in both directions
                    // so a resumed card cannot be served from a suspended-era cache entry.
                    redisService.invalidateCardState(cardId);
                } catch (RuntimeException ex) {
                    log.warn("Redis index update after commit failed for card {} (user {}): {}. "
                                    + "MySQL is authoritative; the due-queue rebuild will converge.",
                            cardId, userId, ex.getMessage());
                }
            });
        }
        return CardView.of(card);
    }

    /**
     * Soft-deletes a card: it disappears from every list and queue, and its review history is
     * kept.
     *
     * <p>Idempotent by design: a card that is already gone - or that belongs to another
     * account, which is indistinguishable here on purpose - is reported as {@code alreadyGone}
     * with HTTP 200. A stale list or a double-clicked button is not an error condition, and a
     * 404 would tell the caller that the id exists but is somebody else's.
     *
     * <p>The order inside the transaction matters for the reported counts: the card row is
     * marked <em>first</em> and the history count is only read when that update affected a row.
     * Counting first would let two concurrent deletes of the same card both report the reviews,
     * and the second one would claim preserved history it never touched.
     */
    public DeleteCardResponse delete(long userId, long cardId) {
        return transactionTemplate.execute(status -> doDelete(userId, cardId));
    }

    /** Transactional body of {@link #delete}; see there for the ordering rule. */
    DeleteCardResponse doDelete(long userId, long cardId) {
        long now = System.currentTimeMillis();
        if (cardMapper.softDelete(cardId, userId, now) == 0) {
            return DeleteCardResponse.ofAlreadyGone();
        }
        long reviewsPreserved = reviewLogMapper.countByCardId(cardId, userId);
        Transactions.afterCommit(() -> {
            try {
                redisService.unindexCard(userId, cardId);
            } catch (RuntimeException ex) {
                // Already committed; the rebuild job converges the index.
                log.warn("Redis cleanup after card delete failed for card {} (user {}): {}",
                        cardId, userId, ex.getMessage());
            }
        });
        return DeleteCardResponse.ofDeleted(reviewsPreserved);
    }

    /**
     * Undoes a card delete.
     *
     * <p>Restoring a card that is not deleted answers <b>404</b> rather than succeeding as a
     * no-op. The contract has two possible readings, and "missing" is the safer one: the client
     * that calls restore is reacting to a delete, so a 200 for a live card would hide a real
     * disagreement between the client's list and the server's - and it would still leave the
     * caller unable to tell "restored" from "never deleted".
     */
    public CardView restore(long userId, long cardId) {
        return transactionTemplate.execute(status -> doRestore(userId, cardId));
    }

    /** Transactional body of {@link #restore}; see there for the 404 choice. */
    CardView doRestore(long userId, long cardId) {
        // Visibility first: a card belonging to someone else, or one that was never deleted,
        // must not be restored - and both look the same from outside.
        Card card = cardMapper.findByIdIncludingDeleted(cardId, userId);
        if (card == null || !card.deletedFlag()) {
            throw ApiException.notFound("card " + cardId + " not found");
        }
        cardMapper.restore(cardId, userId);
        card.setDeletedAt(null);

        long dueTs = card.getDueTs() == null ? System.currentTimeMillis() : card.getDueTs();
        boolean suspended = card.suspendedFlag();
        boolean studyable = card.studyable();
        Transactions.afterCommit(() -> {
            try {
                if (!studyable || suspended) {
                    // A card that was suspended - or whose deck was archived - when it was
                    // deleted comes back in exactly that state, so it must stay out of the
                    // queue. Restoring a delete undoes the delete and nothing else.
                    redisService.unindexCard(userId, cardId);
                } else {
                    redisService.indexOwnedCard(userId, cardId, dueTs);
                }
            } catch (RuntimeException ex) {
                log.warn("Redis index update after card restore failed for card {} (user {}): {}. "
                                + "MySQL is authoritative; the due-queue rebuild will converge.",
                        cardId, userId, ex.getMessage());
            }
        });
        return CardView.of(card);
    }

    /**
     * Soft-deletes a deck together with every live card in it.
     *
     * <p>Written as a cascade in one transaction rather than as a foreign-key
     * {@code ON DELETE CASCADE}: the card ids have to be read anyway, because Redis is cleaned
     * per card, and doing both writes together keeps "the deck is gone" and "its cards are
     * gone" from being observable separately.
     *
     * <p>The order is fixed by the data: collect the live card ids, mark the cards, mark the
     * deck - a deck that vanished first would leave its cards unreachable and their ids unknown.
     * The single timestamp shared by the deck and its cards is what makes
     * {@code POST /api/decks/{id}/restore} able to bring back exactly this batch.
     */
    public DeleteDeckResponse deleteDeck(long userId, long deckId) {
        return transactionTemplate.execute(status -> doDeleteDeck(userId, deckId));
    }

    /** Transactional body of {@link #deleteDeck}; see there for the ordering rule. */
    DeleteDeckResponse doDeleteDeck(long userId, long deckId) {
        long now = System.currentTimeMillis();

        // The soft delete comes first and is the ownership check. Doing it the other way round -
        // reading the deck, then marking it - would make a repeated delete answer 404, because
        // the second read no longer finds a live deck while the user's intent is still "make it
        // gone". Here the UPDATE's row count is the only thing that decides, and a zero row count
        // is answered as alreadyGone for all three of its causes: already deleted, never existed,
        // or another account's. They are deliberately the same answer - "there is nothing of
        // yours to remove" - so the endpoint neither errors on a double-click nor reports whether
        // some other account owns that id.
        if (deckMapper.softDelete(deckId, userId, now) == 0) {
            return DeleteDeckResponse.ofAlreadyGone();
        }

        List<Long> cardIds = cardMapper.findIdsByDeck(userId, deckId);
        // The cards stay in the table; only the marker changes. The history of every one of
        // them is left alone - that is the point of the soft delete.
        cardMapper.softDeleteByDeck(userId, deckId, now);
        long reviewsPreserved = reviewLogMapper.countByDeckId(userId, deckId);
        long cardsDeleted = cardIds.size();

        Transactions.afterCommit(() -> {
            try {
                for (Long cardId : cardIds) {
                    redisService.unindexCard(userId, cardId);
                }
            } catch (RuntimeException ex) {
                // Already committed; the rebuild job converges the index.
                log.warn("Redis cleanup after deck {} delete failed (user {}): {}",
                        deckId, userId, ex.getMessage());
            }
        });
        return DeleteDeckResponse.ofDeleted(cardsDeleted, reviewsPreserved);
    }

    /**
     * Archives a deck: its cards leave the study queue and the workload counters, the deck stays
     * in the list, and every historical trace is kept.
     *
     * <p>Archiving versus deleting is the distinction that matters here. A delete says "I do not
     * want this any more" and takes the deck out of the list; an archive says "I am done with
     * this for now" and leaves it visible with an {@code archived} flag while removing it from
     * the work. They are orthogonal: a deck can be archived and then deleted, and restoring the
     * delete keeps it archived.
     *
     * <p>Idempotent without refreshing the timestamp: archiving an already-archived deck succeeds
     * and leaves the original instant alone, so the answer to "when did I archive this?" does not
     * depend on how many times the button was pressed.
     *
     * <p>The denormalised {@code card.archived_at} is written in the same transaction as
     * {@code deck.archived_at}, with the same value - the invariant that makes the copy
     * trustworthy and lets the queue and the counters filter with one indexed predicate.
     */
    public Deck archiveDeck(long userId, long deckId) {
        return transactionTemplate.execute(status -> doArchiveDeck(userId, deckId));
    }

    /** Transactional body of {@link #archiveDeck}; see there for the invariant. */
    Deck doArchiveDeck(long userId, long deckId) {
        Deck deck = requireOwnedDeck(userId, deckId);
        long now = System.currentTimeMillis();
        if (deckMapper.archive(deckId, userId, now) == 0) {
            // Already archived: nothing to cascade, and the existing timestamp stays.
            return deck;
        }
        cardMapper.setArchivedByDeck(userId, deckId, now);
        deck.setArchivedAt(now);

        List<Long> cardIds = cardMapper.findIdsByDeck(userId, deckId);
        Transactions.afterCommit(() -> {
            try {
                for (Long cardId : cardIds) {
                    redisService.unindexCard(userId, cardId);
                }
            } catch (RuntimeException ex) {
                // Already committed; the rebuild job converges the index.
                log.warn("Redis cleanup after archiving deck {} failed (user {}): {}",
                        deckId, userId, ex.getMessage());
            }
        });
        return deck;
    }

    /**
     * Undoes an archive: the deck returns to the workload and its cards go back into the due
     * index at their own due instants.
     *
     * <p>Idempotent: unarchiving a deck that is not archived succeeds and changes nothing.
     */
    public Deck unarchiveDeck(long userId, long deckId) {
        return transactionTemplate.execute(status -> doUnarchiveDeck(userId, deckId));
    }

    /** Transactional body of {@link #unarchiveDeck}; mirrors {@link #doArchiveDeck}. */
    Deck doUnarchiveDeck(long userId, long deckId) {
        Deck deck = requireOwnedDeck(userId, deckId);
        if (deckMapper.unarchive(deckId, userId) == 0) {
            return deck;
        }
        cardMapper.setArchivedByDeck(userId, deckId, null);
        deck.setArchivedAt(null);

        // Re-indexed from each card's own due_ts, and only for cards that are live and
        // unsuspended - both re-read from MySQL, because a delete may have landed in between.
        long now = System.currentTimeMillis();
        List<Card> cards = cardMapper.findLiveByDeck(userId, deckId);
        Transactions.afterCommit(() -> {
            try {
                for (Card card : cards) {
                    if (!card.suspendedFlag()) {
                        redisService.indexOwnedCard(userId, card.getId(),
                                card.getDueTs() == null ? now : card.getDueTs());
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Redis index update after unarchiving deck {} failed (user {}): {}",
                        deckId, userId, ex.getMessage());
            }
        });
        return deck;
    }

    /**
     * Undoes a deck delete: the deck and the cards that went down with it come back.
     *
     * <p>The batch is identified by the timestamp the delete wrote to both the deck and those
     * cards, so a card the user had deleted individually beforehand stays deleted - restoring a
     * deck must not resurrect cards the user never asked to bring back.
     *
     * <p>The archive marker is deliberately untouched in both directions: a deck that was
     * archived before it was deleted comes back archived, and its cards keep their copies of the
     * marker. Undoing a delete undoes exactly one of the two operations.
     *
     * <p>Like the card restore, a deck that is not deleted answers 404.
     */
    public DeckRestoreResult restoreDeck(long userId, long deckId) {
        return transactionTemplate.execute(status -> doRestoreDeck(userId, deckId));
    }

    /** Transactional body of {@link #restoreDeck}; see there for the batch rule. */
    DeckRestoreResult doRestoreDeck(long userId, long deckId) {
        Deck deck = deckMapper.findByIdIncludingDeleted(deckId, userId);
        if (deck == null || !deck.deletedFlag()) {
            throw ApiException.notFound("deck " + deckId + " not found");
        }
        long deletedAt = deck.getDeletedAt();
        deckMapper.restore(deckId, userId);
        int restoredCards = cardMapper.restoreByDeck(userId, deckId, deletedAt);

        // The restored cards have to be reconsidered for the due index. Their archive marker is
        // whatever it was before the delete, which is why this asks for the live rows of the deck
        // instead of assuming they are studyable: a card of a deck that was archived before the
        // delete comes back archived and must stay out of the queue.
        long now = System.currentTimeMillis();
        List<Card> restored = cardMapper.findLiveByDeck(userId, deckId);
        Transactions.afterCommit(() -> {
            try {
                for (Card card : restored) {
                    if (card.studyable() && !card.suspendedFlag()) {
                        redisService.indexOwnedCard(userId, card.getId(),
                                card.getDueTs() == null ? now : card.getDueTs());
                    } else {
                        redisService.unindexCard(userId, card.getId());
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("Redis index update after deck {} restore failed (user {}): {}",
                        deckId, userId, ex.getMessage());
            }
        });

        deck.setDeletedAt(null);
        return new DeckRestoreResult(deck, restoredCards, restored.size());
    }

    /**
     * Ownership check for a deck, scoped by the authenticated user, live decks only.
     *
     * <p>Deliberately duplicated from {@code DeckService} instead of injected from it:
     * {@code DeckService} calls this class to run the deck-delete cascade, so depending on it
     * back would form a bean cycle. The check is three lines over a mapper this class already
     * holds, and a shared abstraction over it would only exist to save those three lines.
     */
    private Deck requireOwnedDeck(long userId, Long deckId) {
        if (deckId == null) {
            throw ApiException.badRequest("deckId is required");
        }
        Deck deck = deckMapper.findLive(deckId, userId);
        if (deck == null) {
            // Covers three cases with one answer, on purpose: never existed, belongs to another
            // account, and soft-deleted all look identical from outside.
            throw ApiException.notFound("deck " + deckId + " not found");
        }
        return deck;
    }

    /**
     * Loads a card the caller may still edit, suspend or delete.
     *
     * <p>"Live" here means theirs and not soft-deleted. A deleted card is reported as missing
     * from every endpoint except the timeline and restore, which is what stops a stale tab from
     * editing or reviewing something the user removed on another device.
     *
     * <p>An <b>archived</b> card is deliberately still editable: archiving says "I am not
     * studying this now", not "this is gone", and the deck stays in the list - so fixing a typo
     * in an archived deck has to keep working. It is the queue, the review endpoint and the
     * counters that exclude archived cards.
     */
    private Card requireLiveCard(long userId, long cardId) {
        Card card = cardMapper.findByIdIncludingDeleted(cardId, userId);
        if (card == null || card.deletedFlag()) {
            throw ApiException.notFound("card " + cardId + " not found");
        }
        return card;
    }

    /**
     * The user's deleted cards, newest first.
     *
     * <p><b>Cards belonging to a deleted deck are not listed here.</b> Deleting a deck takes its
     * cards with it, and listing both would turn one deck delete into seventeen rows in the
     * recycle bin - the user would reasonably conclude they have to restore each card by hand.
     * Restoring the deck brings all of them back at once. The card is never lost; it is just
     * represented by its deck until that deck is restored. A card deleted on its own, while its
     * deck is still live, does appear here.
     */
    public List<DeletedCardResponse> listDeleted(long userId) {
        return cardMapper.findDeletedCards(userId);
    }

    /**
     * Builds the interval-evolution view of one card.
     *
     * <p>Each log row stores only the state <em>before</em> the review, so the "after" value
     * of a row is the "before" value of the following row. The final row is closed with the
     * card's current state, which keeps the chart's last point consistent with the card
     * endpoint instead of ending one step early.
     *
     * <p>This deliberately answers for a soft-deleted card too. The history was kept on
     * purpose, so the chart is still true, and a bookmarked link to a card the user removed is
     * better served with data than with a 404. Nothing else in the API agrees to show a deleted
     * card.
     */
    public CardTimelineResponse timeline(long userId, long cardId) {
        Card card = cardMapper.findByIdIncludingDeleted(cardId, userId);
        if (card == null) {
            throw ApiException.notFound("card " + cardId + " not found");
        }

        List<ReviewLog> logs = reviewLogMapper.timeline(cardId, userId);
        List<CardTimelineResponse.TimelineEntry> entries = new ArrayList<>(logs.size());
        for (int i = 0; i < logs.size(); i++) {
            ReviewLog row = logs.get(i);
            ReviewLog next = i + 1 < logs.size() ? logs.get(i + 1) : null;
            Double stabilityAfter = next != null ? next.getSBefore() : card.getStability();
            Double difficultyAfter = next != null ? next.getDBefore() : card.getDifficulty();
            entries.add(new CardTimelineResponse.TimelineEntry(
                    row.getReviewedAt(),
                    row.getRating(),
                    row.getSBefore(),
                    stabilityAfter,
                    row.getDBefore(),
                    difficultyAfter,
                    row.getRBefore(),
                    row.getScheduledDays() == null ? 0.0 : row.getScheduledDays()));
        }
        return new CardTimelineResponse(CardView.of(card), entries);
    }

    /**
     * Outcome of a deck restore.
     *
     * @param deck           the restored deck row
     * @param restoredCards  live cards brought back with it
     * @param totalLiveCards cards of the deck that are live now, i.e. the deck's card count
     */
    public record DeckRestoreResult(Deck deck, int restoredCards, int totalLiveCards) {
    }
}
