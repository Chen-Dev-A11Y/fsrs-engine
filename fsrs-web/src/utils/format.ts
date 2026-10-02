/** Small date helpers. Everything the UI shows is in the browser's local day. */

export function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** `YYYY-MM-DD` in local time. */
export function toDateKey(input: Date | string | number): string {
  const d = input instanceof Date ? input : new Date(input)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

export function addDays(date: Date, days: number): Date {
  const next = new Date(date.getTime())
  next.setDate(next.getDate() + days)
  return next
}

export function todayKey(): string {
  return toDateKey(new Date())
}

/** Inclusive list of date keys from `from` to `to`. */
export function dateRange(from: Date, to: Date): string[] {
  const out: string[] = []
  let cursor = new Date(from.getFullYear(), from.getMonth(), from.getDate())
  const end = new Date(to.getFullYear(), to.getMonth(), to.getDate())
  let guard = 0
  while (cursor.getTime() <= end.getTime() && guard < 4000) {
    out.push(toDateKey(cursor))
    cursor = addDays(cursor, 1)
    guard += 1
  }
  return out
}

/**
 * "2026-09-30" -> "2026年09月30日"
 *
 * Accepts everything its siblings accept (`Date | string | number`). It used to take only
 * a date-key string, which is exactly how `prettyDate(card.due)` slipped past type checking:
 * the API sends `due` as epoch milliseconds, the call compiled against a wrong `due: string`
 * declaration, and then threw "key.split is not a function" during render.
 */
export function prettyDate(input: Date | string | number | null | undefined): string {
  if (input === null || input === undefined || input === '') return '—'
  // A date-only string is already the local day we want to print. Parsing it would go
  // through `new Date('2026-09-30')`, which is UTC midnight and therefore shifts the day
  // backwards in any negative-offset timezone.
  const key = typeof input === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(input) ? input : toDateKey(input)
  if (!key) return '—'
  const [y, m, d] = key.split('-')
  if (!y || !m || !d) return '—'
  return `${y}年${m}月${d}日`
}

/** Relative day distance narrative, used for due dates and the study feedback. */
export function relativeDays(key: string | number | Date): string {
  if (key === null || key === undefined || key === '') return '—'
  const target = toDateKey(key)
  const today = todayKey()
  if (!target) return '—'
  const diff = Math.round(
    (new Date(`${target}T00:00:00`).getTime() - new Date(`${today}T00:00:00`).getTime()) / 86400000,
  )
  if (diff === 0) return '今天'
  if (diff === 1) return '明天'
  if (diff === -1) return '昨天'
  if (diff > 1) return `${diff} 天后`
  // Overdue cards are the ones the reader has to act on, so say so outright
  // instead of leaving them to work out that "5 天前" means "you are late".
  return `已逾期 ${Math.abs(diff)} 天`
}

/** Day distance between a date key and today, signed. */
export function daysFromToday(key: string | number | Date): number {
  const target = toDateKey(key)
  if (!target) return 0
  return Math.round(
    (new Date(`${target}T00:00:00`).getTime() - new Date(`${todayKey()}T00:00:00`).getTime()) / 86400000,
  )
}

export function formatDateTime(input: string | number): string {
  const d = new Date(input)
  if (Number.isNaN(d.getTime())) return '—'
  return `${toDateKey(d)} ${pad2(d.getHours())}:${pad2(d.getMinutes())}`
}

export function formatShortDate(key: string): string {
  const parts = key.split('-')
  if (parts.length < 3) return key
  return `${Number(parts[1])}/${Number(parts[2])}`
}

export function formatMs(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '0.0s'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

export function formatPercent(value: number, digits = 1): string {
  if (!Number.isFinite(value)) return '—'
  return `${(value * 100).toFixed(digits)}%`
}
