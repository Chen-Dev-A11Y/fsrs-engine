/**
 * Browser-side mirror of the FSRS-6 formulas implemented in `fsrs-core`
 * (`FsrsScheduler.java` / `FsrsParams.java`).
 *
 * The primary forgetting curve always comes from `GET /api/scheduler/curve`.
 * This module exists so that the parameter lab keeps working - live, on every
 * keystroke - when the backend is unreachable (offline demo mode), and so that
 * the "interval preview" table can be recomputed without a round trip.
 */

import type { Rating } from '@/types/api'

/** Official FSRS-6 default weights, copied from `FsrsParams.DEFAULT_FSRS_6`. */
export const DEFAULT_WEIGHTS: number[] = [
  0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629,
  1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
]

export const DEFAULT_RETENTION = 0.9
export const DEFAULT_MAX_INTERVAL = 36500

export const STABILITY_MIN = 0.001

/** Human-readable meaning of every weight, matching the layout documented in `FsrsParams`. */
export const WEIGHT_META: { index: number; name: string; group: string; hint: string }[] = [
  { index: 0, name: 'w0', group: '初始稳定性', hint: 'S0(Again)：首次评 Again 的初始稳定性' },
  { index: 1, name: 'w1', group: '初始稳定性', hint: 'S0(Hard)' },
  { index: 2, name: 'w2', group: '初始稳定性', hint: 'S0(Good)' },
  { index: 3, name: 'w3', group: '初始稳定性', hint: 'S0(Easy)' },
  { index: 4, name: 'w4', group: '初始难度', hint: 'D0 基数：D0(G) = w4 - e^(w5·(G-1)) + 1' },
  { index: 5, name: 'w5', group: '初始难度', hint: 'D0 随评分衰减速度' },
  { index: 6, name: 'w6', group: '难度更新', hint: 'ΔD 步长：ΔD(G) = -w6·(G-3)' },
  { index: 7, name: 'w7', group: '难度更新', hint: '均值回归强度（防 ease hell），∈[0,1]' },
  { index: 8, name: 'w8', group: '成功复习', hint: 'S 增长的整体尺度 e^w8' },
  { index: 9, name: 'w9', group: '成功复习', hint: 'S 的幂次衰减：S 越大增量越小' },
  { index: 10, name: 'w10', group: '成功复习', hint: 'R 的敏感度：R 越低增量越大（间隔效应）' },
  { index: 11, name: 'w11', group: '遗忘后', hint: 'post-lapse 稳定性尺度' },
  { index: 12, name: 'w12', group: '遗忘后', hint: '难度惩罚指数 D^-w12' },
  { index: 13, name: 'w13', group: '遗忘后', hint: '(S+1)^w13 - 1 的形状参数' },
  { index: 14, name: 'w14', group: '遗忘后', hint: '逾期程度 e^(w14·(1-R))' },
  { index: 15, name: 'w15', group: '评分系数', hint: 'Hard 惩罚乘子' },
  { index: 16, name: 'w16', group: '评分系数', hint: 'Easy 奖励乘子' },
  { index: 17, name: 'w17', group: '同日复习', hint: 'FSRS-6 同日复习增长率' },
  { index: 18, name: 'w18', group: '同日复习', hint: '同日复习偏移量 w18' },
  { index: 19, name: 'w19', group: '同日复习', hint: '同日复习 S 的幂次衰减' },
  { index: 20, name: 'w20', group: '遗忘曲线', hint: '可训练衰减指数 DECAY（曲线形状）' },
]

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function w(weights: number[], index: number): number {
  const value = weights[index]
  return Number.isFinite(value) ? value : DEFAULT_WEIGHTS[index]
}

/** w20: trainable decay exponent of the forgetting curve. */
export function decayExponent(weights: number[] = DEFAULT_WEIGHTS): number {
  return Math.max(1e-6, w(weights, 20))
}

/** factor = 0.9^(-1/w20) - 1, so that R(S, S) = 90% by construction. */
export function curveFactor(weights: number[] = DEFAULT_WEIGHTS): number {
  return Math.pow(0.9, -1 / decayExponent(weights)) - 1
}

/** R(t, S) = (1 + factor · t / S) ^ (-w20) */
export function retrievability(day: number, stability: number, weights: number[] = DEFAULT_WEIGHTS): number {
  const s = Math.max(STABILITY_MIN, stability)
  return Math.pow(1 + curveFactor(weights) * Math.max(0, day) / s, -decayExponent(weights))
}

/** I(r, S) = S / factor · (r^(-1/w20) - 1) - the interval that leaves recall at r. */
export function intervalForRetention(
  stability: number,
  retention = DEFAULT_RETENTION,
  weights: number[] = DEFAULT_WEIGHTS,
): number {
  const r = clamp(retention, 0.01, 0.9999)
  const s = Math.max(STABILITY_MIN, stability)
  return (s / curveFactor(weights)) * (Math.pow(r, -1 / decayExponent(weights)) - 1)
}

/** The day at which the curve crosses the given retention target. */
export function dayForRetention(
  stability: number,
  retention = DEFAULT_RETENTION,
  weights: number[] = DEFAULT_WEIGHTS,
): number {
  return intervalForRetention(stability, retention, weights)
}

/** D0(G) = w4 - e^(w5·(G-1)) + 1, clamped to [1, 10]. */
function initialDifficulty(rating: Rating, weights: number[]): number {
  const d = w(weights, 4) - Math.exp(w(weights, 5) * (rating - 1)) + 1
  return clamp(d, 1, 10)
}

/** S0(G) = w[G-1] */
function initialStability(rating: Rating, weights: number[]): number {
  return Math.max(STABILITY_MIN, w(weights, rating - 1))
}

/** One review applied to (S, D), mirroring `FsrsScheduler.review`. */
export function applyReview(
  stability: number,
  difficulty: number,
  rating: Rating,
  elapsedDays: number,
  weights: number[] = DEFAULT_WEIGHTS,
): { stability: number; difficulty: number } {
  if (stability <= 0 || difficulty <= 0) {
    return { stability: initialStability(rating, weights), difficulty: initialDifficulty(rating, weights) }
  }
  const delta = -w(weights, 6) * (rating - 3)
  const damped = difficulty + (delta * (10 - difficulty)) / 9
  const nextD = clamp(w(weights, 7) * initialDifficulty(4, weights) + (1 - w(weights, 7)) * damped, 1, 10)

  const elapsed = Math.max(0, Math.floor(elapsedDays))
  let nextS: number
  if (elapsed < 1) {
    let increase = Math.exp(w(weights, 17) * (rating - 3 + w(weights, 18))) * Math.pow(stability, -w(weights, 19))
    if (rating === 3 || rating === 4) increase = Math.max(increase, 1)
    nextS = stability * increase
  } else if (rating > 1) {
    const r = retrievability(elapsed, stability, weights)
    const hardPenalty = rating === 2 ? w(weights, 15) : 1
    const easyBonus = rating === 4 ? w(weights, 16) : 1
    const increase =
      Math.exp(w(weights, 8)) *
      (11 - difficulty) *
      Math.pow(stability, -w(weights, 9)) *
      (Math.exp(w(weights, 10) * (1 - r)) - 1) *
      hardPenalty *
      easyBonus
    nextS = stability * (increase + 1)
  } else {
    const r = retrievability(elapsed, stability, weights)
    const longTerm =
      w(weights, 11) *
      Math.pow(difficulty, -w(weights, 12)) *
      (Math.pow(stability + 1, w(weights, 13)) - 1) *
      Math.exp((1 - r) * w(weights, 14))
    const shortTerm = stability / Math.exp(w(weights, 17) * w(weights, 18))
    nextS = Math.min(longTerm, shortTerm)
  }
  return { stability: Math.max(STABILITY_MIN, nextS), difficulty: nextD }
}

export interface IntervalPreviewInput {
  stability: number
  difficulty: number
  daysSinceReview: number
  retention: number
  weights: number[]
  maxIntervalDays?: number
}

/** Predicted next interval (days) for the four ratings. */
export function previewIntervals(input: IntervalPreviewInput): Record<'again' | 'hard' | 'good' | 'easy', number> {
  const { stability, difficulty, daysSinceReview, retention, weights } = input
  const maxInterval = input.maxIntervalDays ?? DEFAULT_MAX_INTERVAL
  const out: Record<'again' | 'hard' | 'good' | 'easy', number> = { again: 1, hard: 1, good: 1, easy: 1 }
  const keys: Record<Rating, 'again' | 'hard' | 'good' | 'easy'> = {
    1: 'again',
    2: 'hard',
    3: 'good',
    4: 'easy',
  }
  for (const rating of [1, 2, 3, 4] as Rating[]) {
    const next = applyReview(stability, difficulty, rating, daysSinceReview, weights)
    const raw = intervalForRetention(next.stability, retention, weights)
    out[keys[rating]] = clamp(Math.max(1, Math.round(raw)), 1, maxInterval)
  }
  return out
}

/** Sample R(t) for `maxDay` days; used as the offline stand-in for `/scheduler/curve`. */
export function sampleCurve(
  stability: number,
  maxDay: number,
  weights: number[] = DEFAULT_WEIGHTS,
  step = 1,
): { day: number; retrievability: number }[] {
  const points: { day: number; retrievability: number }[] = []
  for (let day = 0; day <= maxDay; day += step) {
    points.push({ day, retrievability: retrievability(day, stability, weights) })
  }
  return points
}

/** Formats a day count the way the study page does: 1 天 / 11 天 / 1.2 年. */
export function formatDays(days: number): string {
  if (!Number.isFinite(days)) return '—'
  if (days < 1) return '<1 天'
  if (days < 31) return `${Math.round(days)} 天`
  if (days < 365) {
    const months = days / 30.44
    return `${months < 10 ? months.toFixed(1) : Math.round(months)} 个月`
  }
  const years = days / 365.25
  return `${years < 10 ? years.toFixed(1) : Math.round(years)} 年`
}
