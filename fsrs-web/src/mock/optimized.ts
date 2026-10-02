/**
 * Demo "optimized" weights, used only to make the parameter-comparison page
 * immediately meaningful while the optimizer module (`fsrs-optimizer`) is still
 * under development.
 *
 * IMPORTANT: these numbers are hand-tuned, NOT the output of a real fitting run.
 * The UI always labels them as「演示优化参数（非真实拟合结果）」. When the
 * optimizer produces real parameters, only `OPTIMIZED_DEMO` needs replacing.
 *
 * What the tweak is meant to illustrate:
 *   - an easier start (larger w2/w3) so first reviews are not scheduled too soon;
 *   - a slightly larger growth scale (w8) plus stronger `S^-w9` damping (w9),
 *     i.e. fast early growth that flattens out for well-known cards;
 *   - a longer effective memory decay (w20 up from 0.1542 to 0.2), which makes
 *     R fall away more slowly for the same S;
 *   - a stronger mean reversion of difficulty (w7).
 */
export const OPTIMIZED_DEMO_WEIGHTS: number[] = [
  0.28, 1.452, 2.61, 8.94, 6.08, 0.912, 3.34, 0.058, 2.045, 0.184, 0.832, 1.276, 0.048, 0.301, 1.702, 0.573, 2.086,
  0.498, 0.106, 0.061, 0.2,
]

export const OPTIMIZED_DEMO_LABEL = '演示优化参数（非真实拟合结果）'

export const OPTIMIZED_DEMO_RETENTION = 0.9

/** localStorage keys used by the parameter lab. */
export const SAVED_PARAMS_KEY = 'fsrs.savedParams'
export const CURRENT_PARAMS_KEY = 'fsrs.currentParams'

export interface SavedParamsEntry {
  savedAt: string
  source: string
  weights: number[]
  desiredRetention: number
}

export function isWeightArray(value: unknown): value is number[] {
  return Array.isArray(value) && value.length === 21 && value.every((v) => typeof v === 'number' && Number.isFinite(v))
}

/** Accepts a raw JSON array, a `{weights: [...]}` object, or a comma separated string. */
export function parseWeights(input: string): number[] | null {
  const text = input.trim()
  if (!text) return null
  try {
    const parsed = JSON.parse(text) as unknown
    if (isWeightArray(parsed)) return parsed
    if (parsed && typeof parsed === 'object' && isWeightArray((parsed as { weights?: unknown }).weights)) {
      return (parsed as { weights: number[] }).weights
    }
  } catch {
    /* fall through to the comma separated form */
  }
  const parts = text
    .replace(/[[\]]/g, '')
    .split(/[,\s;]+/)
    .filter(Boolean)
    .map(Number)
  if (parts.length === 21 && parts.every((v) => Number.isFinite(v))) return parts
  return null
}
