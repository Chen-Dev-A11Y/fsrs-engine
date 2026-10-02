/**
 * Deterministic offline demo dataset.
 *
 * Used when the Spring Boot backend is unreachable, so that every page still
 * renders and stays interactive. All shapes match the documented API contract,
 * which means the pages never need to know where the data came from.
 */

import type {
  Card,
  CardDeleteResult,
  CardPatch,
  CardTimeline,
  CurveResponse,
  Deck,
  DeckDeleteResult,
  HeatmapDay,
  Rating,
  ReviewQueueCard,
  ReviewResponse,
  SchedulerParams,
  StatsSummary,
  StudyQueueResponse,
  TimelineReview,
} from '@/types/api'
import type { DeletedInventory } from '@/types/deleted'
import { DEFAULT_MAX_INTERVAL, DEFAULT_RETENTION, DEFAULT_WEIGHTS, previewIntervals, retrievability, sampleCurve } from '@/utils/fsrs'
import { addDays, toDateKey } from '@/utils/format'

export const DEMO_USER = { username: 'demo', password: 'demo1234', userId: 1 }

export const DEMO_TOKEN = 'demo-offline-token'

let seed = 20260930

/** Tiny deterministic PRNG so the demo looks identical on every reload. */
function rnd(): number {
  seed = (seed * 1103515245 + 12345) & 0x7fffffff
  return seed / 0x7fffffff
}

function pick<T>(list: T[]): T {
  return list[Math.floor(rnd() * list.length)] as T
}

function round(value: number, digits = 2): number {
  const f = 10 ** digits
  return Math.round(value * f) / f
}

// ---------------------------------------------------------------------------
// Decks
// ---------------------------------------------------------------------------

export const demoDecks: Deck[] = [
  { id: 1, name: '分布式系统 · 面试八股', cardCount: 42, dueCount: 8, newCount: 6, archived: false },
  { id: 2, name: 'FSRS-6 算法论文精读', cardCount: 28, dueCount: 5, newCount: 4, archived: false },
  { id: 3, name: 'Java 并发与 JVM', cardCount: 36, dueCount: 11, newCount: 3, archived: false },
  { id: 4, name: '英语学术词汇 · 高频', cardCount: 120, dueCount: 19, newCount: 10, archived: false },
  { id: 5, name: 'MySQL 索引与执行计划', cardCount: 24, dueCount: 2, newCount: 0, archived: false },
]

interface SeedCard {
  front: string
  back: string
  state: Card['state']
  stability: number
  difficulty: number
  reps: number
  lapses: number
  dueInDays: number
}

const seedCards: Record<number, SeedCard[]> = {
  1: [
    {
      front: 'Raft 中 term 的作用是什么？',
      back: 'term 是逻辑时钟：每个选举周期单调递增。收到更小的 term 直接拒绝；更大的 term 立即转为 Follower 并更新本地 term。它把「谁是最新 Leader」变成一个可比较的全序，从而避免脑裂。',
      state: 'REVIEW',
      stability: 18.4,
      difficulty: 6.1,
      reps: 7,
      lapses: 1,
      dueInDays: 0,
    },
    {
      front: '为什么 Redis ZSet 用跳表而不是红黑树？',
      back: '范围查询 O(log N + M) 且实现简单；跳表的区间操作、按 rank 查找都更直观，并发/内存局部性也更好，还便于做 ZRANGEBYSCORE 这类区间扫描。',
      state: 'REVIEW',
      stability: 11.2,
      difficulty: 5.4,
      reps: 5,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: 'CAP 定理中 P 为什么不能真正「放弃」？',
      back: '分区是网络现实而非设计选项。所以真正的选择是在分区期间牺牲 C 或 A，这也是 PACELC 把「无分区时的延迟 vs 一致性」补充进来的原因。',
      state: 'REVIEW',
      stability: 34.9,
      difficulty: 4.2,
      reps: 9,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: '幂等键（clientReviewId）为什么必须由客户端生成？',
      back: '只有客户端知道「这两次请求是同一次提交的重试」。服务端生成就失去了去重依据。配合唯一索引 + SETNX 即可做到重复提交只计分一次。',
      state: 'NEW',
      stability: 0,
      difficulty: 0,
      reps: 0,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: '虚拟线程在 IO 密集场景解决了什么问题？',
      back: '把「一线程一请求」的阻塞式写法从平台线程的稀缺资源里解放出来：挂起时释放载体线程，吞吐接近异步但保持同步代码可读性。CPU 密集场景无收益。',
      state: 'REVIEW',
      stability: 6.8,
      difficulty: 6.9,
      reps: 3,
      lapses: 1,
      dueInDays: 1,
    },
    {
      front: '两阶段提交最大的工程缺陷是什么？',
      back: '协调者单点 + 阻塞式协议：协调者崩溃后参与者长期持锁，无法自主决议。生产中更常用 Saga / TCC / 本地消息表。',
      state: 'LEARNING',
      stability: 2.1,
      difficulty: 7.4,
      reps: 2,
      lapses: 2,
      dueInDays: 0,
    },
  ],
  2: [
    {
      front: 'FSRS-6 中 factor 为什么取 0.9^(-1/w20) - 1？',
      back: '为了让 R(S, S) = 90% 恒成立：把 t = S 代入 R(t,S) 后需要结果为 0.9，反解即得该 factor。于是 S 有了直观含义——「掉到 90% 的那一天」。',
      state: 'REVIEW',
      stability: 22.6,
      difficulty: 5.1,
      reps: 6,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: '难度 D 为什么要做均值回归？',
      back: '否则 D 会单向漂移，Good 也会让难度缓慢上升（ease hell）。回归目标是 D0(Easy)，用 w7 控制强度，使 D 有界且可恢复。',
      state: 'REVIEW',
      stability: 15.3,
      difficulty: 4.8,
      reps: 5,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: 'FSRS-6 为什么单独给同日复习一条公式？',
      back: '同日内 S 的增长必须收敛：若复用长期公式，反复「刷」同一张卡可把 S 无限推高。S\' = S·e^(w17·(G-3+w18))·S^(-w19) 中的 S^-w19 正是抑制项。',
      state: 'REVIEW',
      stability: 9.1,
      difficulty: 5.9,
      reps: 4,
      lapses: 1,
      dueInDays: 0,
    },
    {
      front: '等同保持率下，为什么 FSRS 的复习次数更少？',
      back: '因为它按每张卡的 D/S 逐卡反解间隔，而不是用全局固定阶梯。记忆强的卡拉长、弱的卡缩短，把复习预算分配给真正需要的位置。',
      state: 'NEW',
      stability: 0,
      difficulty: 0,
      reps: 0,
      lapses: 0,
      dueInDays: 0,
    },
  ],
  3: [
    {
      front: 'JMM 中 happens-before 与 volatile 的关系？',
      back: 'volatile 写 happens-before 后续对该变量的读；它保证可见性与有序性，但不保证复合操作的原子性（i++ 仍需锁或 Atomic）。',
      state: 'REVIEW',
      stability: 13.7,
      difficulty: 6.3,
      reps: 5,
      lapses: 1,
      dueInDays: 0,
    },
    {
      front: '为什么 G1 的 Region 要保留 Humongous 区域？',
      back: '大于 Region 一半的对象会跨 Region，复制成本高。Humongous 区把它们单独安置，避免在 Young GC 时反复搬运大对象。',
      state: 'REVIEW',
      stability: 7.4,
      difficulty: 7.1,
      reps: 3,
      lapses: 2,
      dueInDays: 0,
    },
    {
      front: 'AQS 的 state 为什么用 volatile int？',
      back: 'state 表示锁/许可状态，需要在无锁 CAS 下可见且原子更新：volatile 保证可见性，CAS 保证「读-改-写」原子，两者组合才构成同步基础。',
      state: 'RELEARNING',
      stability: 1.6,
      difficulty: 8.2,
      reps: 6,
      lapses: 3,
      dueInDays: 0,
    },
    {
      front: 'CMS 的 concurrent mode failure 是什么？',
      back: '并发标记期间老年代被填满，来不及完成回收，退化到一次 Serial Old 全停顿。这正是 G1/ZGC 想要消除的场景。',
      state: 'REVIEW',
      stability: 26.5,
      difficulty: 5.5,
      reps: 7,
      lapses: 0,
      dueInDays: 0,
    },
  ],
  4: [
    {
      front: 'ubiquitous',
      back: 'adj. 无处不在的；普遍存在的。例：Smartphones are ubiquitous in modern life.',
      state: 'REVIEW',
      stability: 41.2,
      difficulty: 3.6,
      reps: 8,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: 'mitigate',
      back: 'v. 减轻、缓和（不良影响）。例：Caching mitigates the load on the database.',
      state: 'REVIEW',
      stability: 19.8,
      difficulty: 4.4,
      reps: 6,
      lapses: 0,
      dueInDays: 0,
    },
    {
      front: 'pernicious',
      back: 'adj. 有害的、潜移默化的（常指难以察觉的长期危害）。',
      state: 'LEARNING',
      stability: 2.9,
      difficulty: 7.8,
      reps: 2,
      lapses: 1,
      dueInDays: 0,
    },
    {
      front: 'conflate',
      back: 'v. 把（两个不同的事物）混为一谈。例：Do not conflate latency with throughput.',
      state: 'NEW',
      stability: 0,
      difficulty: 0,
      reps: 0,
      lapses: 0,
      dueInDays: 0,
    },
  ],
  5: [
    {
      front: '为什么最左前缀原则会失效于范围查询之后的列？',
      back: 'B+ 树按 (a,b,c) 排序，一旦 a 上出现范围条件，后续列在同一 a 内虽有序，但整体扫描无法再用等值定位，因此只能用索引下推过滤而非定位。',
      state: 'REVIEW',
      stability: 12.9,
      difficulty: 6.6,
      reps: 4,
      lapses: 1,
      dueInDays: 0,
    },
    {
      front: '覆盖索引如何避免回表？',
      back: '查询所需列全部包含在二级索引中，直接从索引叶子拿到数据，无需按主键回聚簇索引再查一次，减少随机 IO。',
      state: 'REVIEW',
      stability: 21.4,
      difficulty: 5.2,
      reps: 6,
      lapses: 0,
      dueInDays: 0,
    },
  ],
}

let cardIdCursor = 1000

function buildCards(): Card[] {
  const out: Card[] = []
  for (const deckId of Object.keys(seedCards).map(Number)) {
    for (const seed of seedCards[deckId] as SeedCard[]) {
      out.push({
        id: (cardIdCursor += 1),
        deckId,
        front: seed.front,
        back: seed.back,
        state: seed.state,
        due: new Date(addDays(new Date(), seed.dueInDays).setHours(9, 0, 0, 0)).getTime(),
        stability: seed.stability,
        difficulty: seed.difficulty,
        reps: seed.reps,
        lapses: seed.lapses,
        suspended: false,
      })
    }
  }
  return out
}

export let demoCards: Card[] = buildCards()

/** Cards created while running in offline mode are kept in memory only. */
export function addDemoCard(deckId: number, front: string, back: string): Card {
  const card: Card = {
    id: (cardIdCursor += 1),
    deckId,
    front,
    back,
    state: 'NEW',
    due: Date.now(),
    stability: 0,
    difficulty: 0,
    reps: 0,
    lapses: 0,
    suspended: false,
  }
  demoCards = [...demoCards, card]
  const deck = demoDecks.find((d) => d.id === deckId)
  if (deck) {
    deck.cardCount += 1
    deck.newCount += 1
  }
  return card
}

export function addDemoDeck(name: string): Deck {
  const deck: Deck = {
    id: demoDecks.reduce((max, d) => Math.max(max, d.id), 0) + 1,
    name,
    cardCount: 0,
    dueCount: 0,
    newCount: 0,
    archived: false,
  }
  demoDecks.push(deck)
  return deck
}

// ---------------------------------------------------------------------------
// Edits and deletes - the offline mirror of PUT
// ---------------------------------------------------------------------------

/**
 * Keeps a demo deck's counters in step as cards come and go.
 *
 * The counters are adjusted by the delta rather than recomputed from `demoCards`: the seeded decks
 * describe a real account (42 cards in the first deck) while only a handful of sample cards exist
 * offline, so recomputing would visibly shrink every number on the page. `sign` is -1 when cards
 * are taken away and +1 when they come back through a restore.
 */
function adjustDeckCounts(deckId: number, cards: Card[], sign: 1 | -1): void {
  const deck = demoDecks.find((d) => d.id === deckId)
  if (!deck) return
  const now = Date.now()
  const fresh = cards.filter((c) => c.reps === 0).length
  const due = cards.filter((c) => c.reps > 0 && !c.suspended && c.due <= now).length
  deck.cardCount = Math.max(0, deck.cardCount + sign * cards.length)
  deck.newCount = Math.max(0, deck.newCount + sign * fresh)
  deck.dueCount = Math.max(0, deck.dueCount + sign * due)
}

/**
 * Partial update of one card, exactly like `PUT /api/cards/{id}`: only the fields present in the
 * patch are written, and the memory state (state / due / S / D / reps / lapses) is left untouched
 * on purpose - correcting a typo must not make the card look unlearned.
 *
 * A soft-deleted card is no longer in `demoCards`, so this throws - the live endpoint answers 404
 * for one too, which is why the UI offers no「编辑」on a deleted row.
 */
export function updateDemoCard(id: number, patch: CardPatch): Card {
  const card = demoCards.find((c) => c.id === id)
  if (!card) throw new Error(`卡片 ${id} 不存在`)
  if (patch.front !== undefined) card.front = patch.front
  if (patch.back !== undefined) card.back = patch.back
  if (patch.suspended !== undefined) card.suspended = patch.suspended
  return { ...card }
}

export function renameDemoDeck(id: number, name: string): Deck {
  const deck = demoDecks.find((d) => d.id === id)
  if (!deck) throw new Error(`卡组 ${id} 不存在`)
  deck.name = name
  return { ...deck }
}

/** A deck's due / new counts while it is archived; the live API recomputes them from the database. */
const parkedWorkload = new Map<number, { dueCount: number; newCount: number }>()

/**
 * Archives a deck: nothing is removed, but it stops being pushed for review.
 *
 * The cards stay in `demoCards` - they are still the user's, and they still carry S / D and the
 * whole review history - so the only two places that have to know about `archived` are the study
 * queue and the statistics. The workload counters are parked and read as 0 while archived, which is
 * what the live deck list answers (its due / new counts are computed with `archived_at IS NULL`).
 *
 * Archiving an already-archived deck simply answers with it: idempotent, and it does not refresh
 * the archived instant, exactly like the endpoint.
 */
export function archiveDemoDeck(id: number): Deck {
  const deck = demoDecks.find((d) => d.id === id)
  if (!deck) throw new Error(`卡组 ${id} 不存在`)
  if (deck.archived !== true) {
    parkedWorkload.set(id, { dueCount: deck.dueCount, newCount: deck.newCount })
    deck.archived = true
  }
  deck.dueCount = 0
  deck.newCount = 0
  return { ...deck }
}

export function unarchiveDemoDeck(id: number): Deck {
  const deck = demoDecks.find((d) => d.id === id)
  if (!deck) throw new Error(`卡组 ${id} 不存在`)
  deck.archived = false
  const parked = parkedWorkload.get(id)
  if (parked) {
    deck.dueCount = parked.dueCount
    deck.newCount = parked.newCount
    parkedWorkload.delete(id)
  }
  return { ...deck }
}

/** Deck ids of every archived deck, i.e. the decks whose cards are out of the queue. */
function archivedDeckIds(): Set<number> {
  return new Set(demoDecks.filter((d) => d.archived === true).map((d) => d.id))
}

// ---------------------------------------------------------------------------
// Soft delete and restore - the offline mirror of DELETE + POST /restore
// ---------------------------------------------------------------------------

/**
 * Everything soft-deleted offline, parked here exactly as it was at delete time.
 *
 * A demo deletion is a marker, not a removal: the row leaves the live arrays (so it disappears
 * from the deck list, the study queue, the stats and `updateDemoCard`, which is the offline
 * equivalent of the live `PUT /api/cards/{id}` answering 404 for a deleted card) and waits here
 * until it is restored. `card.reps` is what the review history survives as - see `demoTimeline`,
 * which synthesises one log row per `reps`.
 */
interface DeletedCardEntry {
  card: Card
  deckName: string
  deletedAt: number
}

interface DeletedDeckEntry {
  deck: Deck
  /** The deck's live cards at delete time: they leave the lists with it and come back with it. */
  cards: Card[]
  deletedAt: number
  cardsDeleted: number
  reviewsPreserved: number
}

let deletedCardEntries: DeletedCardEntry[] = []
let deletedDeckEntries: DeletedDeckEntry[] = []

/**
 * Deletes one card, softly and idempotently.
 *
 * @return the number of review logs that were kept, which the UI says out loud
 */
export function removeDemoCard(id: number): CardDeleteResult {
  const already = deletedCardEntries.find((e) => e.card.id === id)
  if (already) return { ok: true, alreadyGone: true, reviewsPreserved: already.card.reps }
  const card = demoCards.find((c) => c.id === id)
  if (!card) return { ok: true, alreadyGone: true, reviewsPreserved: 0 }

  deletedCardEntries = [
    ...deletedCardEntries,
    {
      card: { ...card },
      deckName: demoDecks.find((d) => d.id === card.deckId)?.name ?? '',
      deletedAt: Date.now(),
    },
  ]
  demoCards = demoCards.filter((c) => c.id !== id)
  adjustDeckCounts(card.deckId, [card], -1)
  return { ok: true, alreadyGone: false, reviewsPreserved: card.reps }
}

/** Restores a soft-deleted card with its schedule and review history intact. Idempotent. */
export function restoreDemoCard(id: number): Card {
  const index = deletedCardEntries.findIndex((e) => e.card.id === id)
  if (index < 0) {
    const live = demoCards.find((c) => c.id === id)
    if (live) return { ...live }
    throw new Error(`卡片 ${id} 不存在`)
  }
  const entry = deletedCardEntries[index] as DeletedCardEntry
  deletedCardEntries.splice(index, 1)
  // Re-inserted in id order, which is the order `GET /api/cards` returns (the live endpoint does an
  // `orderByAsc("id")`), so a restored card lands where it was rather than at the bottom.
  demoCards = [...demoCards, entry.card].sort((a, b) => a.id - b.id)
  adjustDeckCounts(entry.card.deckId, [entry.card], 1)
  return { ...entry.card }
}

/**
 * Deletes a deck softly: it and its live cards leave the lists, every review log under it is kept
 * - including the logs of cards that had been deleted individually before. The live cards are
 * parked inside the deck's own entry (not in the card inventory), because they are hidden *by* the
 * deck and return with it; the「已删除」panel lists the deck, not its 16 cards.
 */
export function removeDemoDeck(id: number): DeckDeleteResult {
  const index = demoDecks.findIndex((d) => d.id === id)
  if (index < 0) return { ok: true, alreadyGone: true, cardsDeleted: 0, reviewsPreserved: 0 }

  const deck = demoDecks[index] as Deck
  const live = demoCards.filter((c) => c.deckId === id)
  const earlier = deletedCardEntries.filter((e) => e.card.deckId === id)
  const reviewsPreserved = [...live, ...earlier.map((e) => e.card)].reduce((sum, card) => sum + card.reps, 0)

  demoCards = demoCards.filter((c) => c.deckId !== id)
  demoDecks.splice(index, 1)
  deletedDeckEntries = [
    ...deletedDeckEntries,
    {
      deck: { ...deck },
      cards: live.map((card) => ({ ...card })),
      deletedAt: Date.now(),
      cardsDeleted: live.length,
      reviewsPreserved,
    },
  ]
  return { ok: true, alreadyGone: false, cardsDeleted: live.length, reviewsPreserved }
}

/** Restores a soft-deleted deck together with every card that was soft-deleted below it. */
export function restoreDemoDeck(id: number): Deck {
  const index = deletedDeckEntries.findIndex((e) => e.deck.id === id)
  if (index < 0) {
    const live = demoDecks.find((d) => d.id === id)
    if (live) return { ...live }
    throw new Error(`卡组 ${id} 不存在`)
  }
  const entry = deletedDeckEntries[index] as DeletedDeckEntry
  deletedDeckEntries.splice(index, 1)
  demoDecks.push(entry.deck)

  // Cards that went away with the deck, plus the ones that had been deleted individually before it.
  const returning = [...entry.cards, ...deletedCardEntries.filter((e) => e.card.deckId === id).map((e) => e.card)]
  deletedCardEntries = deletedCardEntries.filter((e) => e.card.deckId !== id)
  for (const card of returning) {
    if (!demoCards.some((live) => live.id === card.id)) demoCards = [...demoCards, card]
  }
  demoCards = [...demoCards].sort((a, b) => a.id - b.id)
  return { ...entry.deck }
}

/** The offline half of the「已删除」panel; the page unions it with what this browser remembers. */
export function demoDeletedInventory(): DeletedInventory {
  return {
    cards: deletedCardEntries.map((e) => ({
      id: e.card.id,
      deckId: e.card.deckId,
      front: e.card.front,
      deckName: e.deckName,
      deletedAt: e.deletedAt,
      reviewsPreserved: e.card.reps,
    })),
    decks: deletedDeckEntries.map((e) => ({
      id: e.deck.id,
      name: e.deck.name,
      cardCount: e.cardsDeleted,
      deletedAt: e.deletedAt,
      reviewsPreserved: e.reviewsPreserved,
    })),
  }
}

// ---------------------------------------------------------------------------
// Study queue + review
// ---------------------------------------------------------------------------

function toQueueCard(card: Card): ReviewQueueCard {
  const isNew = card.reps === 0
  // Card.stability/difficulty are null until the first review, so a non-new card is
  // guaranteed to have them - but fall back rather than assert, to keep this total.
  const stability = isNew ? 0 : (card.stability ?? 0)
  const difficulty = isNew ? 0 : (card.difficulty ?? 0)
  const elapsed = card.due ? Math.max(0, (Date.now() - new Date(card.due).getTime()) / 86400000) : 0
  const predicted = isNew
    ? previewIntervals({
        stability: DEFAULT_WEIGHTS[2] as number,
        difficulty: 5,
        daysSinceReview: 0,
        retention: DEFAULT_RETENTION,
        weights: DEFAULT_WEIGHTS,
      })
    : previewIntervals({
        stability,
        difficulty,
        daysSinceReview: elapsed,
        retention: DEFAULT_RETENTION,
        weights: DEFAULT_WEIGHTS,
      })
  const r = isNew ? 0.9 : retrievability(Math.max(1, Math.round(elapsed)), stability, DEFAULT_WEIGHTS)
  return {
    cardId: card.id,
    deckId: card.deckId,
    front: card.front,
    back: card.back,
    state: card.state,
    stability: round(stability),
    difficulty: round(difficulty),
    retrievability: round(isNew ? 0.9 : r, 4),
    isNew,
    predictedIntervals: predicted,
  }
}

export function demoQueue(limit = 20): StudyQueueResponse {
  const archived = archivedDeckIds()
  const schedulable = demoCards.filter((c) => !archived.has(c.deckId))
  const due = schedulable.filter((c) => c.reps > 0 && !c.suspended)
  const fresh = schedulable.filter((c) => c.reps === 0 && !c.suspended)
  const cards = [...due, ...fresh].slice(0, limit).map(toQueueCard)
  return { totalDue: due.length, totalNew: fresh.length, cards }
}

export function demoReview(cardId: number, rating: Rating, durationMs: number, clientReviewId: string): ReviewResponse {
  const card = demoCards.find((c) => c.id === cardId)
  // The live review endpoint answers 409 for a card whose deck is archived; the queue already
  // excludes them, so this only catches a stale queue held in a tab.
  if (card && archivedDeckIds().has(card.deckId)) {
    throw new Error(`卡片 ${cardId} 属于已归档的卡组，不能复习`)
  }
  const queueCard = card ? toQueueCard(card) : null
  const intervalKey = ({ 1: 'again', 2: 'hard', 3: 'good', 4: 'easy' } as const)[rating]
  const intervalDays = queueCard ? (queueCard.predictedIntervals[intervalKey] ?? 1) : 1
  void durationMs
  void clientReviewId
  if (card) {
    card.reps += 1
    if (rating === 1) card.lapses += 1
    card.state = rating === 1 && card.reps > 1 ? 'RELEARNING' : card.state === 'NEW' ? 'LEARNING' : 'REVIEW'
    card.stability = round(
      Math.max(0.05, (card.stability || (DEFAULT_WEIGHTS[rating - 1] as number)) * (rating === 1 ? 0.45 : 1.6)),
    )
    card.difficulty = round(Math.min(10, Math.max(1, (card.difficulty || 5) + (rating - 3) * 0.35)))
    card.due = new Date(addDays(new Date(), intervalDays).setHours(9, 0, 0, 0)).getTime()
  }
  return {
    cardId,
    nextDue: card?.due ?? Date.now(),
    intervalDays,
    stability: card?.stability ?? 0,
    difficulty: card?.difficulty ?? 0,
    state: card?.state ?? 'REVIEW',
    retrievabilityAtReview: queueCard?.retrievability ?? 0.9,
    duplicate: false,
  }
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

let heatmapCache: HeatmapDay[] | null = null

export function demoHeatmap(days = 365): HeatmapDay[] {
  if (!heatmapCache) {
    const out: HeatmapDay[] = []
    const today = new Date()
    for (let i = days - 1; i >= 0; i -= 1) {
      const date = addDays(today, -i)
      const weekday = date.getDay()
      const weekend = weekday === 0 || weekday === 6
      const recency = 1 - i / (days * 1.6)
      const base = weekend ? 6 : 22
      const wave = Math.sin(i / 9) * 8
      let count = Math.max(0, Math.round((base + wave) * recency + rnd() * 26 - 6))
      if (rnd() < 0.12) count = 0
      const again = Math.round(count * (0.08 + rnd() * 0.08))
      const hard = Math.round(count * (0.12 + rnd() * 0.08))
      const easy = Math.round(count * (0.18 + rnd() * 0.12))
      const good = Math.max(0, count - again - hard - easy)
      out.push({ date: toDateKey(date), count, again, hard, good, easy })
    }
    heatmapCache = out
  }
  return heatmapCache.slice(-days)
}

export function demoSummary(): StatsSummary {
  const heat = demoHeatmap(365)
  const last30 = heat.slice(-30)
  const last7 = heat.slice(-7)
  const retention = (rows: HeatmapDay[]) => {
    const total = rows.reduce((sum, r) => sum + r.count, 0)
    const recalled = rows.reduce((sum, r) => sum + r.count - r.again, 0)
    return total === 0 ? 0 : recalled / total
  }
  let streak = 0
  for (let i = heat.length - 1; i >= 0; i -= 1) {
    if ((heat[i] as HeatmapDay).count > 0) streak += 1
    else break
  }
  const totalReviews = heat.reduce((sum, r) => sum + r.count, 0)
  // Archived content stays in `totalCards` - the user still owns those cards - and is reported
  // separately instead. The workload numbers (due / new / forecast) are what archiving removes.
  const archivedIds = archivedDeckIds()
  const archivedCards = demoCards.filter((c) => archivedIds.has(c.deckId)).length
  const forecast = Array.from({ length: 30 }, (_, i) => {
    const date = addDays(new Date(), i)
    const weekday = date.getDay()
    const weekend = weekday === 0 || weekday === 6
    const ramp = i < 3 ? 1.35 - i * 0.1 : 1
    const count = Math.max(0, Math.round(((weekend ? 26 : 74) + Math.sin(i / 3) * 14) * ramp + rnd() * 12))
    return { date: toDateKey(date), count }
  })
  return {
    totalCards: demoCards.length * 7 + 12,
    totalReviews,
    dueToday: forecast[0]?.count ?? 0,
    newToday: 23,
    archivedDecks: archivedIds.size,
    archivedCards,
    retention7d: round(retention(last7), 4),
    retention30d: round(retention(last30), 4),
    streakDays: streak,
    forecast,
  }
}

// ---------------------------------------------------------------------------
// Card timeline
// ---------------------------------------------------------------------------

export function demoTimeline(cardId: number): CardTimeline {
  // A deleted card has no timeline. Falling back to `demoCards[0]` used to render one card's
  // history under another card's id (and threw once every deck had been deleted offline).
  const card = demoCards.find((c) => c.id === cardId)
  if (!card) throw new Error(`卡片 ${cardId} 不存在`)
  const reps = Math.max(1, card.reps)
  const reviews: TimelineReview[] = []
  let stability = 1.2
  let difficulty = 5.4
  const start = addDays(new Date(), -(reps * 9 + 4))
  for (let i = 0; i < reps; i += 1) {
    const rating = (i === 0 ? 3 : i % 5 === 3 ? 1 : (pick([2, 3, 3, 4]) as Rating)) as Rating
    const before = stability
    const growth = rating === 1 ? 0.42 : rating === 2 ? 1.35 : rating === 3 ? 1.95 : 2.6
    stability = round(Math.max(0.2, before * growth))
    const dBefore = difficulty
    difficulty = round(Math.min(10, Math.max(1, difficulty + (rating - 3) * 0.34)), 2)
    const reviewedAt = new Date(addDays(start, i * 9 + 2).setHours(20, 12 + i, 0, 0)).getTime()
    reviews.push({
      reviewedAt,
      rating,
      stabilityBefore: round(before),
      stabilityAfter: round(stability),
      difficultyBefore: round(dBefore),
      difficultyAfter: round(difficulty),
      retrievability: round(i === 0 ? 0.9 : 0.82 + rnd() * 0.14, 4),
      intervalDays: Math.max(1, Math.round(stability)),
    })
  }
  return { card, reviews }
}

// ---------------------------------------------------------------------------
// Scheduler parameters + curve
// ---------------------------------------------------------------------------

export const demoParams: SchedulerParams = {
  weights: [...DEFAULT_WEIGHTS],
  desiredRetention: DEFAULT_RETENTION,
  maximumIntervalDays: DEFAULT_MAX_INTERVAL,
  source: 'offline-demo',
}

export function demoCurve(stability = 10, maxDay = 365, weights?: number[]): CurveResponse {
  return { points: sampleCurve(stability, maxDay, weights ?? DEFAULT_WEIGHTS, Math.max(1, Math.round(maxDay / 180))) }
}

export function delay(ms = 260): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
