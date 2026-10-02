import { computed } from 'vue'
import type { ComputedRef } from 'vue'
import { DEFAULT_WEIGHTS, previewIntervals } from '@/utils/fsrs'
import type { Rating } from '@/types/api'

export type Getter<T> = () => T

export interface IntervalRow {
  stability: number
  again: number
  hard: number
  good: number
  easy: number
}

export interface IntervalPreviewTable {
  /** Reference stabilities shown as rows, in the order they are rendered. */
  stabilities: number[]
  /** Rows computed with the currently edited weights. */
  rows: ComputedRef<IntervalRow[]>
  /** Same rows computed with the FSRS-6 default weights (the baseline). */
  baseline: ComputedRef<IntervalRow[]>
  /** Signed change vs the default weights, in days. */
  delta: (stability: number, rating: Rating) => number
  /** Interval for one cell, in days. */
  valueAt: (stability: number, key: keyof Omit<IntervalRow, 'stability'>) => number
}

const RATING_KEY: Record<Rating, keyof Omit<IntervalRow, 'stability'>> = {
  1: 'again',
  2: 'hard',
  3: 'good',
  4: 'easy',
}

/**
 * The "间隔预览" table: for a few reference stabilities, what each rating would
 * schedule next, computed with the *currently edited* weights.
 *
 * Getters (rather than refs) are taken as input so that the table can be driven
 * from a plain reactive object and stays fully type-checked inside the template.
 */
export function useIntervalPreview(
  weights: Getter<number[]>,
  retention: Getter<number>,
  elapsedDays: Getter<number> = () => 1,
  stabilities: number[] = [1, 10, 100],
): IntervalPreviewTable {
  const build = (w: number[]): IntervalRow[] =>
    stabilities.map((stability) => ({
      stability,
      ...previewIntervals({
        stability,
        difficulty: 5,
        daysSinceReview: elapsedDays(),
        retention: retention(),
        weights: w,
      }),
    }))

  const rows = computed(() => build(weights()))
  const baseline = computed(() => build(DEFAULT_WEIGHTS))

  const rowFor = (list: IntervalRow[], stability: number): IntervalRow | undefined =>
    list.find((r) => r.stability === stability)

  return {
    stabilities: [...stabilities],
    rows,
    baseline,
    delta: (stability: number, rating: Rating): number => {
      const current = rowFor(rows.value, stability)
      const base = rowFor(baseline.value, stability)
      if (!current || !base) return 0
      return current[RATING_KEY[rating]] - base[RATING_KEY[rating]]
    },
    valueAt: (stability: number, key: keyof Omit<IntervalRow, 'stability'>): number =>
      rowFor(rows.value, stability)?.[key] ?? 0,
  }
}
