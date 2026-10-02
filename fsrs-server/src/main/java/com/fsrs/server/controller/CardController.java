package com.fsrs.server.controller;

import com.fsrs.server.dto.card.CardTimelineResponse;
import com.fsrs.server.dto.card.CardUpdateRequest;
import com.fsrs.server.dto.card.CardView;
import com.fsrs.server.dto.card.CreateCardRequest;
import com.fsrs.server.dto.card.DeleteCardResponse;
import com.fsrs.server.dto.card.DeletedCardResponse;
import com.fsrs.server.security.CurrentUser;
import com.fsrs.server.service.CardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    public List<CardView> list(@RequestParam(required = false) Long deckId) {
        return cardService.list(CurrentUser.id(), deckId);
    }

    /**
     * The user's deleted cards, newest delete first.
     *
     * <p>The path is a literal segment, so it is never captured by {@code /{id}}. Spring's
     * {@code PathPattern} matching prefers a literal over a variable at the same position, but
     * that only holds while the routes have the same number of segments: were a
     * {@code GET /api/cards/{id}} ever added, {@code /deleted} would still win because it is more
     * specific - this note exists so that nobody "fixes" it by renaming the segment.
     *
     * <p>Cards whose deck is itself deleted are deliberately absent; see
     * {@code CardService.listDeleted}.
     */
    @GetMapping("/deleted")
    public List<DeletedCardResponse> listDeleted() {
        return cardService.listDeleted(CurrentUser.id());
    }

    @PostMapping
    public CardView create(@Valid @RequestBody CreateCardRequest request) {
        return cardService.create(CurrentUser.id(), request);
    }

    /**
     * Suspends a card.
     *
     * @return {@code {"ok": true}} rather than 204, because the frontend contract expects a
     *         body it can check without inspecting the status code
     */
    @PostMapping("/{id}/suspend")
    public Map<String, Object> suspend(@PathVariable long id) {
        cardService.suspend(CurrentUser.id(), id);
        return Map.of("ok", true);
    }

    /**
     * Partially updates a card: {@code front}, {@code back} and {@code suspended} are each
     * optional, and an omitted field is left untouched.
     *
     * <p>{@code suspended} is what completes the suspend/resume pair started by
     * {@code POST /api/cards/{id}/suspend} - there was previously no way back.
     *
     * @return the card as stored after the update
     */
    @PutMapping("/{id}")
    public CardView update(@PathVariable long id, @Valid @RequestBody CardUpdateRequest request) {
        return cardService.update(CurrentUser.id(), id, request);
    }

    /**
     * Soft-deletes a card: it leaves every list and queue, and its review history is kept.
     *
     * <p>Answers 200 for a card that is already gone rather than 404: deleting is exactly what
     * the caller asked for, and a 404 would both break a double-click and reveal that the id
     * exists under another account.
     */
    @DeleteMapping("/{id}")
    public DeleteCardResponse delete(@PathVariable long id) {
        return cardService.delete(CurrentUser.id(), id);
    }

    /** Undoes a card delete; 404 when the card is not deleted (or is not the caller's). */
    @PostMapping("/{id}/restore")
    public CardView restore(@PathVariable long id) {
        return cardService.restore(CurrentUser.id(), id);
    }

    @GetMapping("/{id}/timeline")
    public CardTimelineResponse timeline(@PathVariable long id) {
        return cardService.timeline(CurrentUser.id(), id);
    }
}
