import {
  OPTIMIZED_DEMO_RETENTION,
  OPTIMIZED_DEMO_WEIGHTS,
  SAVED_PARAMS_KEY,
  isWeightArray,
  type SavedParamsEntry,
} from '@/mock/optimized'
import { DEFAULT_RETENTION, DEFAULT_WEIGHTS } from '@/utils/fsrs'

/**
 * Small localStorage-backed history of parameter sets.
 *
 * Every successful save on the parameter page appends a snapshot here, which is
 * what the comparison page offers as「从 localStorage 读取历史保存」. The list
 * also contains one built-in "演示优化参数" entry so the comparison feature is
 * useful before the optimizer module ships real fitted weights.
 */

function read(): SavedParamsEntry[] {
  try {
    const raw = localStorage.getItem(SAVED_PARAMS_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed.filter(
      (e): e is SavedParamsEntry =>
        Boolean(e) && typeof e === 'object' && isWeightArray((e as SavedParamsEntry).weights),
    )
  } catch {
    return []
  }
}

function write(entries: SavedParamsEntry[]): void {
  try {
    localStorage.setItem(SAVED_PARAMS_KEY, JSON.stringify(entries.slice(0, 12)))
  } catch {
    /* storage disabled - the page still works with in-memory state */
  }
}

/** Adds the built-in demo entry once; real saves take precedence in the UI list. */
export function listSavedParams(): SavedParamsEntry[] {
  const entries = read()
  const hasDemo = entries.some(
    (e) => e.weights.map((w) => w.toFixed(6)).join(',') === OPTIMIZED_DEMO_WEIGHTS.map((w) => w.toFixed(6)).join(','),
  )
  if (!hasDemo) {
    entries.push({
      savedAt: '内置',
      source: '演示优化参数（非真实拟合结果）',
      weights: [...OPTIMIZED_DEMO_WEIGHTS],
      desiredRetention: OPTIMIZED_DEMO_RETENTION,
    })
    write(entries)
  }
  // Newest first, demo entry last.
  return entries.sort((a, b) => (a.savedAt === '内置' ? 1 : b.savedAt === '内置' ? -1 : b.savedAt.localeCompare(a.savedAt)))
}

export function saveParams(weights: number[], desiredRetention: number, source = 'manual'): void {
  const entries = read()
  const key = weights.map((w) => w.toFixed(6)).join(',')
  if (entries.some((e) => e.weights.map((w) => w.toFixed(6)).join(',') === key)) return
  entries.push({
    savedAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
    source,
    weights: [...weights],
    desiredRetention,
  })
  write(entries)
}

export function clearSavedParams(): void {
  try {
    localStorage.removeItem(SAVED_PARAMS_KEY)
  } catch {
    /* ignore */
  }
}

export function defaultEntry(): SavedParamsEntry {
  return {
    savedAt: 'FSRS-6 官方默认',
    source: 'fsrs-core FsrsParams.DEFAULT_FSRS_6',
    weights: [...DEFAULT_WEIGHTS],
    desiredRetention: DEFAULT_RETENTION,
  }
}
