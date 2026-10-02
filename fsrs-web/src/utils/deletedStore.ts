import { USER_KEY } from '@/api'
import type { DeletedCardRow, DeletedDeckRow, DeletedInventory } from '@/types/deleted'

/**
 * The browser's own record of the rows it has soft-deleted.
 *
 * The server is the source of truth for the「已删除」panel (`GET /api/cards/deleted` +
 * `GET /api/decks/deleted`); this is the fallback for when it cannot be reached and the gap-filler
 * for a delete that happened moments ago. Every delete on the decks page is recorded here with
 * everything the panel needs (the id, the text, the deck it belonged to, the instant it went away
 * and how many review logs were kept), and a successful restore drops the entry again.
 *
 * Kept in localStorage, tagged with the user id, so a reload neither loses the panel nor shows one
 * account's rows to another. `mergeInventory` unions it with the server's answer - server rows are
 * never dropped just because this browser has no record of them.
 */

const STORAGE_KEY = 'fsrs.deleted'

/** A stored row is a panel row plus the account it belongs to. */
type StoredCardRow = DeletedCardRow & { userId: number | null }
type StoredDeckRow = DeletedDeckRow & { userId: number | null }

/**
 * The signed-in user id, or null when this browser has no stored session.
 *
 * Read straight from storage rather than from the auth store: this module is imported by pages that
 * already talk to `@/api`, and a Pinia store here would buy nothing for the two fields needed.
 */
function currentUserId(): number | null {
  try {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { userId?: unknown }
    return typeof parsed.userId === 'number' ? parsed.userId : null
  } catch {
    return null
  }
}

function isCardRow(value: unknown): value is StoredCardRow {
  const row = value as StoredCardRow
  return (
    Boolean(row) &&
    typeof row === 'object' &&
    typeof row.id === 'number' &&
    typeof row.deckId === 'number' &&
    typeof row.deletedAt === 'number'
  )
}

function isDeckRow(value: unknown): value is StoredDeckRow {
  const row = value as StoredDeckRow
  return Boolean(row) && typeof row === 'object' && typeof row.id === 'number' && typeof row.deletedAt === 'number'
}

function readAll(): { cards: StoredCardRow[]; decks: StoredDeckRow[] } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { cards: [], decks: [] }
    const parsed = JSON.parse(raw) as { cards?: unknown; decks?: unknown }
    return {
      cards: Array.isArray(parsed.cards) ? parsed.cards.filter(isCardRow) : [],
      decks: Array.isArray(parsed.decks) ? parsed.decks.filter(isDeckRow) : [],
    }
  } catch {
    return { cards: [], decks: [] }
  }
}

function write(all: { cards: StoredCardRow[]; decks: StoredDeckRow[] }): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all))
  } catch {
    /* storage disabled - the panel still shows whatever earlier writes managed to store */
  }
}

/** Newest deletion first, which is the order the panel reads in. */
function byRecency<T extends { deletedAt: number }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => b.deletedAt - a.deletedAt)
}

function toCardRow(row: StoredCardRow): DeletedCardRow {
  return {
    id: row.id,
    deckId: row.deckId,
    front: row.front,
    deckName: row.deckName,
    deletedAt: row.deletedAt,
    reviewsPreserved: row.reviewsPreserved,
  }
}

function toDeckRow(row: StoredDeckRow): DeletedDeckRow {
  return {
    id: row.id,
    name: row.name,
    cardCount: row.cardCount,
    deletedAt: row.deletedAt,
    reviewsPreserved: row.reviewsPreserved,
  }
}

export function listDeletedRows(): DeletedInventory {
  const all = readAll()
  const userId = currentUserId()
  return {
    cards: byRecency(all.cards.filter((row) => row.userId === userId).map(toCardRow)),
    decks: byRecency(all.decks.filter((row) => row.userId === userId).map(toDeckRow)),
  }
}

export function recordDeletedCard(row: DeletedCardRow): void {
  const all = readAll()
  const userId = currentUserId()
  write({
    cards: [...all.cards.filter((r) => !(r.userId === userId && r.id === row.id)), { ...row, userId }],
    decks: all.decks,
  })
}

export function recordDeletedDeck(row: DeletedDeckRow): void {
  const all = readAll()
  const userId = currentUserId()
  write({
    cards: all.cards,
    decks: [...all.decks.filter((r) => !(r.userId === userId && r.id === row.id)), { ...row, userId }],
  })
}

export function forgetDeletedCard(id: number): void {
  const all = readAll()
  const userId = currentUserId()
  write({ cards: all.cards.filter((r) => !(r.userId === userId && r.id === id)), decks: all.decks })
}

/** Dropping a deck also drops its cards: restoring the deck brings them back with it. */
export function forgetDeletedDeck(id: number): void {
  const all = readAll()
  const userId = currentUserId()
  write({
    cards: all.cards.filter((r) => !(r.userId === userId && r.deckId === id)),
    decks: all.decks.filter((r) => !(r.userId === userId && r.id === id)),
  })
}

/**
 * Drops the recorded cards of one deck, keeping the deck's own row.
 *
 * A deleted deck stands for its cards: `GET /api/cards/deleted` does not list them separately, so a
 * card that was deleted on its own and whose deck is deleted later must stop being a row of its own -
 * otherwise the panel would show a deck and the individual cards inside it at the same time.
 */
export function forgetDeletedCardsOfDeck(deckId: number): void {
  const all = readAll()
  const userId = currentUserId()
  write({
    cards: all.cards.filter((r) => !(r.userId === userId && r.deckId === deckId)),
    decks: all.decks,
  })
}

/** Union of two inventories, keyed by id; `first` wins when both know the same row. */
export function mergeInventory(first: DeletedInventory, second: DeletedInventory): DeletedInventory {
  const cardIds = new Set(first.cards.map((row) => row.id))
  const deckIds = new Set(first.decks.map((row) => row.id))
  return {
    cards: byRecency([...first.cards, ...second.cards.filter((row) => !cardIds.has(row.id))]),
    decks: byRecency([...first.decks, ...second.decks.filter((row) => !deckIds.has(row.id))]),
  }
}

/**
 * Applies the read endpoints' rule that a deleted deck represents its cards: any card row whose deck
 * is also in the list is dropped. Enforced after merging so a stale local record cannot put the
 * deck's cards back on screen next to the deck's own row.
 */
export function pruneDeckCards(inventory: DeletedInventory): DeletedInventory {
  const deckIds = new Set(inventory.decks.map((row) => row.id))
  return { cards: inventory.cards.filter((row) => !deckIds.has(row.deckId)), decks: inventory.decks }
}

/**
 * Drops local rows the server no longer lists, and keeps everything recorded in the last minute.
 *
 * The server is the source of truth now, so a local row it does not list is stale - it was restored
 * in another browser, or on another device - and without this it would sit in the panel for ever.
 * The grace window covers the one case where the server answer can legitimately lag: a delete that
 * was just confirmed, whose row has not reached the read path yet.
 *
 * Only call this when the server actually answered. Offline the local rows are all there is, and
 * pruning them against the demo dataset would empty the panel.
 */
export function retainServerRows(inventory: DeletedInventory, graceMs = 60_000): void {
  const all = readAll()
  const userId = currentUserId()
  const cardIds = new Set(inventory.cards.map((row) => row.id))
  const deckIds = new Set(inventory.decks.map((row) => row.id))
  const justRecorded = (row: { deletedAt: number }) => Date.now() - row.deletedAt < graceMs
  write({
    cards: all.cards.filter((r) => r.userId !== userId || cardIds.has(r.id) || justRecorded(r)),
    decks: all.decks.filter((r) => r.userId !== userId || deckIds.has(r.id) || justRecorded(r)),
  })
}
