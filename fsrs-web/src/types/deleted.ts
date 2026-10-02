/**
 * Read model of the「已删除」panel.
 *
 * It mirrors what `GET /api/cards/deleted` and `GET /api/decks/deleted` return - the fields the panel
 * actually renders, that is. The card endpoint also sends `back` / `state` / `due` / `stability` /
 * `difficulty` / `reps` / `lapses` / `suspended`, which this panel has no use for and therefore does
 * not declare.
 *
 * One shape rule comes from those endpoints: a card that went away with its deck is listed as that
 * deck, never on its own. `pruneDeckCards` in `utils/deletedStore.ts` enforces the same rule on the
 * merged result, so a stale local record cannot reintroduce the 16 rows the server deliberately
 * leaves out.
 */

export interface DeletedCardRow {
  id: number
  /** Deck the card belonged to; kept so the name can still be resolved after the deck moved on. */
  deckId: number
  front: string
  /** Deck name as it was when the card was deleted. */
  deckName: string
  /** Epoch milliseconds, the same unit every other timestamp in this app uses. */
  deletedAt: number
  /** Review logs that were kept for this card. */
  reviewsPreserved: number
}

export interface DeletedDeckRow {
  id: number
  name: string
  /** How many cards the delete moved out of the live lists. */
  cardCount: number
  /** Epoch milliseconds. */
  deletedAt: number
  /** Review logs that were kept for the deck's cards. */
  reviewsPreserved: number
}

export interface DeletedInventory {
  cards: DeletedCardRow[]
  decks: DeletedDeckRow[]
}
