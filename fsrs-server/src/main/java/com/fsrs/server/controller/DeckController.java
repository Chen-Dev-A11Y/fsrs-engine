package com.fsrs.server.controller;

import com.fsrs.server.dto.deck.CreateDeckRequest;
import com.fsrs.server.dto.deck.DeckResponse;
import com.fsrs.server.dto.deck.DeleteDeckResponse;
import com.fsrs.server.dto.deck.DeletedDeckResponse;
import com.fsrs.server.dto.deck.UpdateDeckRequest;
import com.fsrs.server.security.CurrentUser;
import com.fsrs.server.service.DeckService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/decks")
public class DeckController {

    private final DeckService deckService;

    public DeckController(DeckService deckService) {
        this.deckService = deckService;
    }

    @GetMapping
    public List<DeckResponse> list() {
        return deckService.list(CurrentUser.id());
    }

    /**
     * The user's deleted decks, newest delete first, each with the number of cards and reviews a
     * restore returns.
     *
     * <p>Same routing note as {@code GET /api/cards/deleted}: {@code /deleted} is a literal
     * segment and wins over any future {@code /{id}}, so the two can coexist.
     */
    @GetMapping("/deleted")
    public List<DeletedDeckResponse> listDeleted() {
        return deckService.listDeleted(CurrentUser.id());
    }

    @PostMapping
    public DeckResponse create(@Valid @RequestBody CreateDeckRequest request) {
        return deckService.create(CurrentUser.id(), request);
    }

    /** Renames a live deck; answers with the deck as stored, counters included. */
    @PutMapping("/{id}")
    public DeckResponse update(@PathVariable long id, @Valid @RequestBody UpdateDeckRequest request) {
        return deckService.rename(CurrentUser.id(), id, request);
    }

    /**
     * Soft-deletes a deck with every live card in it.
     *
     * <p>Answers 200 for a deck that is already gone rather than 404, for the same reason as
     * the card delete: a repeated request must not surface as an error, and a 404 would leak
     * the existence of another account's deck.
     *
     * <p>No review row is removed by any of this. {@code review_log} is the input of the
     * parameter optimizer, and {@code daily_stat} is not touched either: it records that a
     * review really happened on a given day, which stays true after the card is gone. History
     * is a fact, not a projection of the current card list - deleting a deck must not
     * retroactively rewrite how much the user studied last month.
     */
    @DeleteMapping("/{id}")
    public DeleteDeckResponse delete(@PathVariable long id) {
        return deckService.delete(CurrentUser.id(), id);
    }

    /** Undoes a deck delete, bringing back the cards that went down with it. */
    @PostMapping("/{id}/restore")
    public DeckResponse restore(@PathVariable long id) {
        return deckService.restore(CurrentUser.id(), id);
    }

    /**
     * Archives a deck: it stays in this list with {@code archived:true}, but leaves the study
     * queue and the workload counters. Distinct from the delete above - archiving says "I am done
     * with this for now", deleting says "I do not want this any more" - and the two can be
     * combined in either order.
     *
     * <p>Idempotent, and it does not refresh the archive timestamp: archiving twice keeps the
     * original instant. A soft-deleted deck answers 404.
     */
    @PostMapping("/{id}/archive")
    public DeckResponse archive(@PathVariable long id) {
        return deckService.archive(CurrentUser.id(), id);
    }

    /** Cancels an archive, returning the deck's cards to the workload. Idempotent. */
    @PostMapping("/{id}/unarchive")
    public DeckResponse unarchive(@PathVariable long id) {
        return deckService.unarchive(CurrentUser.id(), id);
    }
}
