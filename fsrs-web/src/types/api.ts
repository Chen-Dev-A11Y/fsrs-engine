/** Rating values exactly as defined by the FSRS specification (1..4). */
export type Rating = 1 | 2 | 3 | 4

export const RATING_ORDER: Rating[] = [1, 2, 3, 4]

export const RATING_META: Record<
  Rating,
  { key: string; label: string; zh: string; color: string; shortcut: string }
> = {
  1: { key: 'again', label: 'Again', zh: '重来', color: '#ff5d6c', shortcut: '1' },
  2: { key: 'hard', label: 'Hard', zh: '困难', color: '#ffab4d', shortcut: '2' },
  3: { key: 'good', label: 'Good', zh: '良好', color: '#3ddc97', shortcut: '3' },
  4: { key: 'easy', label: 'Easy', zh: '简单', color: '#4cc9f0', shortcut: '4' },
}

export const CARD_STATES = ['NEW', 'LEARNING', 'REVIEW', 'RELEARNING'] as const
/**
 * The live API sends the numeric code (0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING) because
 * that is what the database stores and what avoids a second mapping layer on the server,
 * while the offline mock uses the string names. Accept both, and normalise before display -
 * otherwise a numeric state renders as a bare "2" and loses its colour.
 */
export type CardState = (typeof CARD_STATES)[number] | string | number

export interface LoginResponse {
  token: string
  username: string
  userId: number
}

export interface Deck {
  id: number
  name: string
  cardCount: number
  dueCount: number
  newCount: number
  /**
   * true while the deck is archived: "done with this for now" rather than "throw it away".
   *
   * An archived deck keeps every card and all of its review history and stays in the deck list
   * (under 「已归档」), but it is out of the study queue and out of the statistics - and undo is
   * `POST /api/decks/{id}/unarchive`, not a restore.
   */
  archived: boolean
}

export interface Card {
  id: number
  deckId: number
  front: string
  back: string
  state: CardState
  /**
   * Epoch milliseconds in UTC, exactly as the API sends it - NOT a date string.
   * Declaring this as a string is what let `prettyDate(card.due)` past the type checker.
   */
  due: number
  /** null until the first review: a NEW card has no memory state yet. */
  stability: number | null
  /** null until the first review, same reason as stability. */
  difficulty: number | null
  reps: number
  lapses: number
  suspended?: boolean
}

/**
 * Body of `PUT /api/cards/{id}`. A partial update: only the fields that are present
 * change, and none of them touch the memory state (stability / difficulty / due / reps
 * stay exactly as they were), so fixing a typo never sends the card back to learning.
 */
export interface CardPatch {
  front?: string
  back?: string
  suspended?: boolean
}

/**
 * Result of `DELETE /api/cards/{id}`.
 *
 * A *soft* delete: the card leaves every list, queue and statistic, while its review history is
 * kept - that history is the parameter optimizer's training data. The call is idempotent, so a
 * card that was already gone is still `ok`.
 */
export interface CardDeleteResult {
  ok: boolean
  /** true when the card was already deleted - the call is a successful no-op. */
  alreadyGone: boolean
  /** How many review logs were kept. Surfaced in the UI, because "kept" is the whole point. */
  reviewsPreserved: number
}

/** Result of `DELETE /api/decks/{id}`: the same soft delete for the deck and every card below it. */
export interface DeckDeleteResult {
  ok: boolean
  /** true when the deck was already deleted - the call is a successful no-op. */
  alreadyGone: boolean
  cardsDeleted: number
  reviewsPreserved: number
}

export interface ReviewQueueCard {
  cardId: number
  deckId: number
  front: string
  back: string
  state: CardState
  /** null for a card that has never been reviewed (isNew = true). */
  stability: number | null
  /** null for a card that has never been reviewed (isNew = true). */
  difficulty: number | null
  retrievability: number
  isNew: boolean
  predictedIntervals: Record<'again' | 'hard' | 'good' | 'easy', number>
}

export interface StudyQueueResponse {
  totalDue: number
  totalNew: number
  cards: ReviewQueueCard[]
}

export interface ReviewRequest {
  cardId: number
  rating: Rating
  durationMs: number
  clientReviewId: string
}

export interface ReviewResponse {
  cardId: number
  /** Epoch milliseconds in UTC, as the API sends it. */
  nextDue: number
  intervalDays: number
  stability: number
  difficulty: number
  state: CardState
  retrievabilityAtReview: number
  duplicate: boolean
}

export interface HeatmapDay {
  date: string
  count: number
  again: number
  hard: number
  good: number
  easy: number
}

export interface ForecastPoint {
  date: string
  count: number
}

export interface StatsSummary {
  totalCards: number
  totalReviews: number
  dueToday: number
  newToday: number
  /** Decks the user archived: kept with their cards and history, but out of the queue. */
  archivedDecks: number
  /** Cards sitting in those archived decks. */
  archivedCards: number
  retention7d: number
  retention30d: number
  streakDays: number
  forecast: ForecastPoint[]
}

export interface TimelineReview {
  /** Epoch milliseconds in UTC, as the API sends it. */
  reviewedAt: number
  rating: Rating
  /** null on a card's first review: there was no memory state before it. */
  stabilityBefore: number | null
  stabilityAfter: number
  /** null on a card's first review, same reason as stabilityBefore. */
  difficultyBefore: number | null
  difficultyAfter: number
  retrievability: number
  intervalDays: number
}

export interface CardTimeline {
  card: Card
  reviews: TimelineReview[]
}

export interface SchedulerParams {
  weights: number[]
  desiredRetention: number
  maximumIntervalDays: number
  source: string
}

export interface CurvePoint {
  day: number
  retrievability: number
}

export interface CurveResponse {
  points: CurvePoint[]
}
