/**
 * The single HTTP gateway of the app.
 *
 * Every method follows the same shape:
 *   1. try the real backend (`/api/...`, proxied by Vite to :8080);
 *   2. on any network / 5xx / timeout failure, fall back to the built-in demo
 *      dataset in `src/mock/data.ts` and flip the app into "offline demo" mode.
 *
 * The API contract (paths + field names) is never adapted on the way out, so
 * wiring the real backend up later requires zero page-level changes.
 */

import axios, { AxiosError } from 'axios'
import type {
  Card,
  CardDeleteResult,
  CardPatch,
  CardTimeline,
  CurveResponse,
  Deck,
  DeckDeleteResult,
  HeatmapDay,
  ReviewRequest,
  ReviewResponse,
  SchedulerParams,
  StatsSummary,
  StudyQueueResponse,
} from '@/types/api'
import type { DeletedCardRow, DeletedDeckRow, DeletedInventory } from '@/types/deleted'
import {
  DEFAULT_MAX_INTERVAL,
  DEFAULT_RETENTION,
  DEFAULT_WEIGHTS,
  sampleCurve,
} from '@/utils/fsrs'
import * as demo from '@/mock/data'

export type BackendState = 'unknown' | 'online' | 'offline'

type Listener = (state: BackendState, detail: string) => void

let backendState: BackendState = 'unknown'
let backendDetail = ''
const listeners = new Set<Listener>()

export function getBackendState(): BackendState {
  return backendState
}

export function getBackendDetail(): string {
  return backendDetail
}

export function subscribeBackend(listener: Listener): () => void {
  listeners.add(listener)
  listener(backendState, backendDetail)
  return () => listeners.delete(listener)
}

function setState(state: BackendState, detail = ''): void {
  backendDetail = detail
  if (state === 'offline') {
    backendState = state
    listeners.forEach((l) => l(state, detail))
    return
  }
  backendState = state
  listeners.forEach((l) => l(state, detail))
}

/** Marks the backend as unreachable (used by the pages when a request falls back). */
export function markOffline(detail = ''): void {
  if (backendState !== 'offline') setState('offline', detail)
}

export function markOnline(): void {
  if (backendState !== 'online') setState('online', '')
}

// ---------------------------------------------------------------------------
// axios instance
// ---------------------------------------------------------------------------

export const TOKEN_KEY = 'fsrs.token'
export const USER_KEY = 'fsrs.user'

export const http = axios.create({
  baseURL: '/api',
  timeout: 6000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    markOnline()
    return response
  },
  (error: AxiosError) => {
    const status = error.response?.status
    if (status === 401) {
      // Token expired or invalid: drop the session and bounce to the login page.
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
      const path = window.location.pathname + window.location.search
      if (!path.startsWith('/login')) {
        window.location.replace(`/login?redirect=${encodeURIComponent(path)}`)
      }
      return Promise.reject(error)
    }
    if (!status || status >= 500) {
      markOffline(error.message)
    }
    return Promise.reject(error)
  },
)

/** True when the failure means "backend not usable", as opposed to a 4xx validation error. */
function isTransportFailure(error: unknown): boolean {
  const err = error as AxiosError
  const status = err?.response?.status
  return !status || status >= 500
}

/** Runs the real request; on a transport failure runs `fallback` instead. */
async function withFallback<T>(task: () => Promise<T>, fallback: () => T | Promise<T>, label: string): Promise<T> {
  try {
    const result = await task()
    markOnline()
    return result
  } catch (error) {
    if (!isTransportFailure(error)) throw error
    markOffline(label)
    return await fallback()
  }
}

const OFFLINE_NOTE = '后端未连接'

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

export interface LoginResult {
  token: string
  username: string
  userId: number
}

export const authApi = {
  login(username: string, password: string): Promise<LoginResult> {
    return withFallback(
      async () => (await http.post<LoginResult>('/auth/login', { username, password })).data,
      async () => {
        await demo.delay(400)
        if (username === demo.DEMO_USER.username && password === demo.DEMO_USER.password) {
          return { token: demo.DEMO_TOKEN, username, userId: demo.DEMO_USER.userId }
        }
        throw new Error('用户名或密码错误（离线演示模式仅支持 demo / demo1234）')
      },
      OFFLINE_NOTE,
    )
  },
}

// ---------------------------------------------------------------------------
// Decks & cards
// ---------------------------------------------------------------------------

export const deckApi = {
  list(): Promise<Deck[]> {
    return withFallback(
      async () => (await http.get<Deck[]>('/decks')).data,
      async () => {
        await demo.delay(200)
        return demo.demoDecks.map((d) => ({ ...d }))
      },
      '卡组列表',
    )
  },
  create(name: string): Promise<Deck> {
    return withFallback(
      async () => (await http.post<Deck>('/decks', { name })).data,
      async () => {
        await demo.delay(220)
        return demo.addDemoDeck(name)
      },
      '新建卡组',
    )
  },
  rename(id: number, name: string): Promise<Deck> {
    return withFallback(
      async () => (await http.put<Deck>(`/decks/${id}`, { name })).data,
      async () => {
        await demo.delay(220)
        return demo.renameDemoDeck(id, name)
      },
      '重命名卡组',
    )
  },
  /**
   * Soft-deletes a deck (`DELETE /api/decks/{id}`): the deck and its cards leave every list, the
   * review logs stay behind as the optimizer's training data. Idempotent - a deck that was already
   * deleted resolves with `alreadyGone: true` instead of failing.
   */
  remove(id: number): Promise<DeckDeleteResult> {
    return withFallback(
      async () => (await http.delete<DeckDeleteResult>(`/decks/${id}`)).data,
      async () => {
        await demo.delay(260)
        return demo.removeDemoDeck(id)
      },
      '删除卡组',
    )
  },
  /** Brings a soft-deleted deck back, with every card that was soft-deleted below it. */
  restore(id: number): Promise<Deck> {
    return withFallback(
      async () => (await http.post<Deck>(`/decks/${id}/restore`)).data,
      async () => {
        await demo.delay(240)
        return demo.restoreDemoDeck(id)
      },
      '恢复卡组',
    )
  },
  /**
   * Archives a deck: "not studying this for now" rather than "throw it away". Nothing is removed -
   * the cards and their whole review history stay - it just leaves the study queue and the
   * statistics. Undone by `unarchive`, never by `restore`.
   */
  archive(id: number): Promise<Deck> {
    return withFallback(
      async () => (await http.post<Deck>(`/decks/${id}/archive`)).data,
      async () => {
        await demo.delay(220)
        return demo.archiveDemoDeck(id)
      },
      '归档卡组',
    )
  },
  unarchive(id: number): Promise<Deck> {
    return withFallback(
      async () => (await http.post<Deck>(`/decks/${id}/unarchive`)).data,
      async () => {
        await demo.delay(220)
        return demo.unarchiveDemoDeck(id)
      },
      '取消归档',
    )
  },
}

export const cardApi = {
  list(deckId?: number): Promise<Card[]> {
    return withFallback(
      async () => (await http.get<Card[]>('/cards', { params: deckId ? { deckId } : {} })).data,
      async () => {
        await demo.delay(180)
        const all = demo.demoCards.map((c) => ({ ...c }))
        return deckId ? all.filter((c) => c.deckId === deckId) : all
      },
      '卡片列表',
    )
  },
  create(deckId: number, front: string, back: string): Promise<Card> {
    return withFallback(
      async () => (await http.post<Card>('/cards', { deckId, front, back })).data,
      async () => {
        await demo.delay(220)
        return demo.addDemoCard(deckId, front, back)
      },
      '新建卡片',
    )
  },
  /**
   * Partial update (`PUT /api/cards/{id}`). Only the fields present in `patch` are sent, and
   * the request never resets the memory state - see `CardPatch`.
   */
  update(id: number, patch: CardPatch): Promise<Card> {
    return withFallback(
      async () => (await http.put<Card>(`/cards/${id}`, patch)).data,
      async () => {
        await demo.delay(200)
        return demo.updateDemoCard(id, patch)
      },
      '更新卡片',
    )
  },
  /**
   * Soft-deletes a card (`DELETE /api/cards/{id}`): it leaves every list, queue and statistic while
   * its review history is kept. Idempotent, so a card that was already deleted is a successful
   * no-op carrying `alreadyGone: true`.
   */
  remove(id: number): Promise<CardDeleteResult> {
    return withFallback(
      async () => (await http.delete<CardDeleteResult>(`/cards/${id}`)).data,
      async () => {
        await demo.delay(220)
        return demo.removeDemoCard(id)
      },
      '删除卡片',
    )
  },
  /** Brings a soft-deleted card back with its schedule and review history intact. */
  restore(id: number): Promise<Card> {
    return withFallback(
      async () => (await http.post<Card>(`/cards/${id}/restore`)).data,
      async () => {
        await demo.delay(220)
        return demo.restoreDemoCard(id)
      },
      '恢复卡片',
    )
  },
  suspend(id: number): Promise<{ ok: boolean }> {
    return withFallback(
      async () => (await http.post<{ ok: boolean }>(`/cards/${id}/suspend`)).data,
      async () => {
        await demo.delay(160)
        const card = demo.demoCards.find((c) => c.id === id)
        if (card) card.suspended = true
        return { ok: true }
      },
      '暂停卡片',
    )
  },
  timeline(id: number): Promise<CardTimeline> {
    return withFallback(
      async () => (await http.get<CardTimeline>(`/cards/${id}/timeline`)).data,
      async () => {
        await demo.delay(220)
        return demo.demoTimeline(id)
      },
      '复习时间线',
    )
  },
}

// ---------------------------------------------------------------------------
// The「已删除」inventory
// ---------------------------------------------------------------------------

export const deletedApi = {
  /**
   * Soft-deleted rows, for the「已删除」panel.
   *
   * Live this is the server's own answer (`GET /api/cards/deleted` + `GET /api/decks/deleted`), so
   * the panel reads the same in every browser. Offline the demo dataset's in-memory markers stand
   * in, and the page unions the result with the deletes this browser recorded itself
   * (`@/utils/deletedStore`). That local list is a fallback and a gap-filler only - never a filter:
   * a row the server can list is shown even when this browser has no memory of deleting it.
   *
   * The two endpoints agree on a detail worth keeping: a card that went away with its deck is listed
   * as that deck, not on its own. `pruneDeckCards` in `@/utils/deletedStore` enforces the same shape
   * on the merged result.
   */
  list(): Promise<DeletedInventory> {
    return withFallback(
      async () => {
        const [cards, decks] = await Promise.all([
          http.get<DeletedCardRow[]>('/cards/deleted'),
          http.get<DeletedDeckRow[]>('/decks/deleted'),
        ])
        return { cards: cards.data, decks: decks.data }
      },
      async () => {
        await demo.delay(200)
        return demo.demoDeletedInventory()
      },
      '已删除列表',
    )
  },
}

// ---------------------------------------------------------------------------
// Study
// ---------------------------------------------------------------------------

export const studyApi = {
  queue(deckId?: number, limit = 20): Promise<StudyQueueResponse> {
    return withFallback(
      async () =>
        (
          await http.get<StudyQueueResponse>('/study/queue', {
            params: { ...(deckId ? { deckId } : {}), limit },
          })
        ).data,
      async () => {
        await demo.delay(260)
        const all = demo.demoQueue(limit)
        if (!deckId) return all
        const cards = all.cards.filter((c) => c.deckId === deckId)
        return {
          totalDue: cards.filter((c) => !c.isNew).length,
          totalNew: cards.filter((c) => c.isNew).length,
          cards,
        }
      },
      '复习队列',
    )
  },
  review(payload: ReviewRequest): Promise<ReviewResponse> {
    return withFallback(
      async () => (await http.post<ReviewResponse>('/study/review', payload)).data,
      async () => {
        await demo.delay(120)
        return demo.demoReview(payload.cardId, payload.rating, payload.durationMs, payload.clientReviewId)
      },
      '提交复习',
    )
  },
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

export const statsApi = {
  heatmap(days = 365): Promise<HeatmapDay[]> {
    return withFallback(
      async () => (await http.get<HeatmapDay[]>('/stats/heatmap', { params: { days } })).data,
      async () => {
        await demo.delay(240)
        return demo.demoHeatmap(days)
      },
      '复习热力图',
    )
  },
  summary(): Promise<StatsSummary> {
    return withFallback(
      async () => (await http.get<StatsSummary>('/stats/summary')).data,
      async () => {
        await demo.delay(220)
        return demo.demoSummary()
      },
      '统计汇总',
    )
  },
}

// ---------------------------------------------------------------------------
// Scheduler parameters & forgetting curve
// ---------------------------------------------------------------------------

export const schedulerApi = {
  params(): Promise<SchedulerParams> {
    return withFallback(
      async () => (await http.get<SchedulerParams>('/scheduler/params')).data,
      async () => {
        await demo.delay(200)
        return { ...demo.demoParams, weights: [...demo.demoParams.weights] }
      },
      '调度参数',
    )
  },
  save(weights: number[], desiredRetention: number): Promise<{ ok: boolean }> {
    return withFallback(
      async () => (await http.put<{ ok: boolean }>('/scheduler/params', { weights, desiredRetention })).data,
      async () => {
        await demo.delay(300)
        demo.demoParams.weights = [...weights]
        demo.demoParams.desiredRetention = desiredRetention
        return { ok: true }
      },
      '保存参数',
    )
  },
  /**
   * Forgetting curve for a given stability.
   *
   * The documented contract is `?stability=&difficulty=&maxDay=`. The current
   * draft of the parameter panel is also passed along as an *optional* extra
   * (`weights`, `desiredRetention`) so that, once the backend supports it, the
   * curve reacts to unsaved edits without a round trip through the save
   * endpoint. Unknown query parameters are ignored by Spring, so this is safe
   * against the frozen contract; when the call fails we compute the same curve
   * locally with the FSRS-6 formula, which keeps the sliders and the interval
   * preview fully live offline.
   */
  curve(options: {
    stability: number
    difficulty?: number
    maxDay?: number
    weights?: number[]
    desiredRetention?: number
  }): Promise<CurveResponse> {
    const { stability, difficulty = 5, maxDay = 365, weights, desiredRetention } = options
    const params: Record<string, string | number> = { stability, difficulty, maxDay }
    if (weights?.length) {
      params.weights = weights.join(',')
      params.weightsKey = weights.map((x) => x.toFixed(6)).join(',')
    }
    if (desiredRetention !== undefined) params.desiredRetention = desiredRetention
    return withFallback(
      async () => (await http.get<CurveResponse>('/scheduler/curve', { params })).data,
      async () => ({ points: sampleCurve(stability, maxDay, weights ?? DEFAULT_WEIGHTS, Math.max(1, Math.round(maxDay / 180))) }),
      '遗忘曲线',
    )
  },
  /** Locally computed curve - no network, always available. */
  localCurve(stability: number, maxDay = 365, weights: number[] = DEFAULT_WEIGHTS, step?: number): CurveResponse {
    return { points: sampleCurve(stability, maxDay, weights, step ?? Math.max(1, Math.round(maxDay / 180))) }
  },
}

export { DEFAULT_MAX_INTERVAL, DEFAULT_RETENTION, DEFAULT_WEIGHTS }

export function errorMessage(error: unknown, fallback = '操作失败'): string {
  const err = error as AxiosError<{ message?: string }>
  return err?.response?.data?.message ?? (error as Error)?.message ?? fallback
}
