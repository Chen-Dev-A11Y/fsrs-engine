import { DEFAULT_RETENTION, DEFAULT_WEIGHTS, WEIGHT_META, previewIntervals } from '@/utils/fsrs'
import { RATING_META, type Rating } from '@/types/api'

export type IntervalKey = 'again' | 'hard' | 'good' | 'easy'

export const INTERVAL_STABILITIES = [1, 10, 100, 1000]

export interface GroupIntervals {
  stability: number
  intervals: Record<IntervalKey, number>
}

export interface CompareCell {
  key: IntervalKey
  label: string
  color: string
  a: number
  b: number
  ratio: number
  percent: number
  delta: number
  trend: 'up' | 'down' | 'same'
  significant: boolean
}

export interface CompareRow {
  stability: number
  cells: CompareCell[]
}

export interface CompareGroup {
  weights: number[]
  retention: number
  label: string
  color: string
}

function intervalsFor(stability: number, weights: number[], retention: number, elapsedDays: number): GroupIntervals {
  return {
    stability,
    intervals: previewIntervals({
      stability,
      difficulty: 5,
      daysSinceReview: elapsedDays,
      retention,
      weights,
    }),
  }
}

/** Threshold above which a difference is called out in bold. */
export const SIGNIFICANT_PERCENT = 10

export function compareGroups(a: CompareGroup, b: CompareGroup, elapsedDays = 1, stabilities = INTERVAL_STABILITIES) {
  const rows: CompareRow[] = stabilities.map((stability) => {
    const left = intervalsFor(stability, a.weights, a.retention, elapsedDays).intervals
    const right = intervalsFor(stability, b.weights, b.retention, elapsedDays).intervals
    const cells: CompareCell[] = (Object.keys(left) as IntervalKey[]).map((key) => {
      const av = left[key]
      const bv = right[key]
      const ratio = av === 0 ? 0 : bv / av
      const percent = (ratio - 1) * 100
      return {
        key,
        label: RATING_META[({ again: 1, hard: 2, good: 3, easy: 4 } as Record<IntervalKey, Rating>)[key]].label,
        color: RATING_META[({ again: 1, hard: 2, good: 3, easy: 4 } as Record<IntervalKey, Rating>)[key]].color,
        a: av,
        b: bv,
        ratio,
        percent,
        delta: bv - av,
        trend: percent > 0.5 ? 'up' : percent < -0.5 ? 'down' : 'same',
        significant: Math.abs(percent) >= SIGNIFICANT_PERCENT,
      }
    })
    return { stability, cells }
  })

  const flat = rows.flatMap((r) => r.cells)
  const averagePercent = flat.length ? flat.reduce((sum, c) => sum + c.percent, 0) / flat.length : 0
  const reviewReduction = 1 - 1 / (1 + averagePercent / 100)

  return {
    rows,
    /** Mean interval change across every (S, rating) cell, in percent. */
    averagePercent,
    /** Approximate change in review count for the same retention target. */
    reviewReduction,
  }
}

/** Reference preview at the FSRS-6 defaults - handy as a static baseline. */
export function defaultGroup(retention = DEFAULT_RETENTION): CompareGroup {
  return { weights: [...DEFAULT_WEIGHTS], retention, label: 'FSRS-6 官方默认', color: '#8b9bb4' }
}

/** Weights that differ from the defaults, with their human-readable names. */
export function weightDiff(a: number[], b: number[]): { index: number; name: string; from: number; to: number }[] {
  return WEIGHT_META.filter((m) => Math.abs((a[m.index] ?? 0) - (b[m.index] ?? 0)) > 1e-9).map((m) => ({
    index: m.index,
    name: m.name,
    from: a[m.index] ?? 0,
    to: b[m.index] ?? 0,
  }))
}
