package com.fsrs.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fsrs.server.common.ApiException;
import com.fsrs.server.domain.Deck;
import com.fsrs.server.dto.deck.CreateDeckRequest;
import com.fsrs.server.dto.deck.DeckResponse;
import com.fsrs.server.dto.deck.DeleteDeckResponse;
import com.fsrs.server.dto.deck.DeletedDeckResponse;
import com.fsrs.server.dto.deck.UpdateDeckRequest;
import com.fsrs.server.dto.stats.DeckCountRow;
import com.fsrs.server.mapper.CardMapper;
import com.fsrs.server.mapper.DeckMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deck listing, creation, rename, delete and restore.
 *
 * <p>The counters are aggregated in the database with one grouped query rather than per deck,
 * and are computed from MySQL - not from the Redis index - because these numbers are shown as
 * facts. The due queue is an index for handing cards out cheaply; it is never the answer to
 * "how many cards are due", which must stay correct even while the index is converging.
 *
 * <p>Only live decks and live cards are counted: both are soft-deleted, so the aggregate query
 * filters {@code deleted_at IS NULL} (see {@code CardMapper.countsByDeck}) and this class
 * filters the deck rows itself.
 *
 * <p>The delete/restore cascade is delegated to {@link CardService} because the card ids have
 * to be collected for the Redis cleanup anyway; this class owns the deck row and the counters.
 */
@Service
public class DeckService {

    private final DeckMapper deckMapper;
    private final CardMapper cardMapper;
    private final CardService cardService;

    public DeckService(DeckMapper deckMapper, CardMapper cardMapper, CardService cardService) {
        this.deckMapper = deckMapper;
        this.cardMapper = cardMapper;
        this.cardService = cardService;
    }

    public List<DeckResponse> list(long userId) {
        // Archived decks are included: archiving is "not studying this now", not removal. A
        // deleted deck is not, which is the whole difference between the two markers.
        List<Deck> decks = deckMapper.findLiveByUser(userId);
        if (decks.isEmpty()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        Map<Long, DeckCountRow> counts = new HashMap<>();
        for (DeckCountRow row : cardMapper.countsByDeck(userId, now)) {
            counts.put(row.deckId(), row);
        }

        List<DeckResponse> result = new ArrayList<>(decks.size());
        for (Deck deck : decks) {
            DeckCountRow row = counts.get(deck.getId());
            result.add(new DeckResponse(
                    deck.getId(),
                    deck.getName(),
                    row == null ? 0L : row.cardCount(),
                    // dueCount / newCount are workload numbers and already exclude archived
                    // cards in SQL, so an archived deck reports zero work without a branch here.
                    row == null ? 0L : row.dueCount(),
                    row == null ? 0L : row.newCount(),
                    deck.archivedFlag()));
        }
        return result;
    }

    @Transactional
    public DeckResponse create(long userId, CreateDeckRequest request) {
        Deck deck = new Deck();
        deck.setUserId(userId);
        deck.setName(request.name().trim());
        deck.setCreatedAt(System.currentTimeMillis());
        deckMapper.insert(deck);
        return new DeckResponse(deck.getId(), deck.getName(), 0L, 0L, 0L, false);
    }

    /**
     * Renames a live deck.
     *
     * <p>The new name is trimmed before it is stored for the same reason as on create: the
     * unique role of the name is to be read by a human, and a leading space is invisible in the
     * database and obvious in the UI. The response carries the live counters rather than
     * zeroes, so the caller can replace its cached row with the answer without a second
     * request - a rename does not change any count, and saying so explicitly is cheaper than
     * making the caller assume it.
     */
    @Transactional
    public DeckResponse rename(long userId, long deckId, UpdateDeckRequest request) {
        Deck deck = requireOwnedDeck(userId, deckId);
        String name = request.name().trim();
        deckMapper.rename(deckId, userId, name);
        deck.setName(name);
        return withCounts(userId, deck);
    }

    /**
     * Soft-deletes a deck with every live card in it; all review history is kept.
     *
     * <p>Idempotent: a deck that is already gone - or that belongs to another account, which is
     * indistinguishable here on purpose - is reported as {@code alreadyGone} rather than as a
     * 404, so a stale list or a double-clicked button is not an error.
     */
    public DeleteDeckResponse delete(long userId, long deckId) {
        return cardService.deleteDeck(userId, deckId);
    }

    /**
     * The user's deleted decks, newest first, with how many cards and reviews a restore returns.
     *
     * <p>The counterpart of {@link CardService#listDeleted(long)}: a card that went down with one
     * of these decks is represented by the deck row here, not by a card row there.
     */
    public List<DeletedDeckResponse> listDeleted(long userId) {
        return deckMapper.findDeletedDecks(userId);
    }

    /**
     * Restores a soft-deleted deck together with the cards that were deleted with it.
     *
     * <p>A deck that is not deleted answers 404, for the same reason as the card restore: a
     * caller reacting to a delete must be able to tell "restored" from "was never deleted".
     *
     * <p>An archived deck stays archived through this: undoing a delete undoes one of the two
     * independent operations, and the response says which state the deck came back in.
     */
    public DeckResponse restore(long userId, long deckId) {
        CardService.DeckRestoreResult restored = cardService.restoreDeck(userId, deckId);
        return withCounts(userId, restored.deck());
    }

    /**
     * Archives a deck: it stays in the list with {@code archived:true} but leaves the study queue
     * and the workload counters. History is untouched.
     *
     * <p>Idempotent without refreshing the timestamp; see {@code CardService.archiveDeck}.
     * A soft-deleted deck answers 404.
     */
    public DeckResponse archive(long userId, long deckId) {
        return withCounts(userId, cardService.archiveDeck(userId, deckId));
    }

    /** Undoes an archive, putting the deck's cards back into the workload. Idempotent. */
    public DeckResponse unarchive(long userId, long deckId) {
        return withCounts(userId, cardService.unarchiveDeck(userId, deckId));
    }

    /**
     * Shared ownership check for a deck that may still be acted on: missing, another account's,
     * or soft-deleted all answer the same way, on purpose.
     *
     * <p>An archived deck passes: it is still in the user's collection, so it can be renamed,
     * unarchived or deleted.
     */
    public Deck requireOwnedDeck(long userId, Long deckId) {
        if (deckId == null) {
            throw ApiException.badRequest("deckId is required");
        }
        Deck deck = deckMapper.findLive(deckId, userId);
        if (deck == null) {
            throw ApiException.notFound("deck " + deckId + " not found");
        }
        return deck;
    }

    /** One deck with its counters, read from MySQL - the same numbers {@link #list} reports. */
    private DeckResponse withCounts(long userId, Deck deck) {
        long now = System.currentTimeMillis();
        for (DeckCountRow row : cardMapper.countsByDeck(userId, now)) {
            if (row.deckId() != null && row.deckId() == deck.getId()) {
                return new DeckResponse(deck.getId(), deck.getName(),
                        row.cardCount(), row.dueCount(), row.newCount(), deck.archivedFlag());
            }
        }
        return new DeckResponse(deck.getId(), deck.getName(), 0L, 0L, 0L, deck.archivedFlag());
    }
}
