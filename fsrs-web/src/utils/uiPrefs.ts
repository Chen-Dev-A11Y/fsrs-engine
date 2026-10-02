/**
 * Small localStorage-backed UI preferences.
 *
 * Only state that must survive a reload and is not a request parameter belongs here - the「已归档」
 * group on the decks page is the reason this file exists: the user collapses it because the
 * archived decks should stop taking up room, and it must stay collapsed on the next visit.
 */

const ARCHIVED_GROUP_KEY = 'fsrs.decks.archivedGroupOpen'

function readFlag(key: string, fallback: boolean): boolean {
  try {
    const raw = localStorage.getItem(key)
    return raw === null ? fallback : raw === '1'
  } catch {
    return fallback
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, value ? '1' : '0')
  } catch {
    /* storage disabled - the default is used again on the next visit */
  }
}

/** Default is collapsed: that is the point of archiving a deck. */
export function archivedGroupOpen(): boolean {
  return readFlag(ARCHIVED_GROUP_KEY, false)
}

export function rememberArchivedGroupOpen(open: boolean): void {
  writeFlag(ARCHIVED_GROUP_KEY, open)
}
