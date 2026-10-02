<template>
  <div class="fs-page">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          卡组与卡片
          <span class="fs-chip fs-chip--muted">{{ decks.length }} 个卡组</span>
        </h1>
        <p class="fs-page__sub">
          左栏选卡组，右栏看卡片。表头带「?」的都可以悬停查看一句白话解释，点击任意一行进入该卡片的记忆详情。
        </p>
      </div>
      <div class="head-actions">
        <el-button :loading="loading.decks" @click="reloadAll">刷新</el-button>
        <el-button type="primary" @click="openCreateDeckDialog">新建卡组</el-button>
        <el-button
          type="primary"
          plain
          :disabled="!activeDeck || activeDeckArchived"
          :title="activeDeckArchived ? '已归档的卡组不进入复习队列，先取消归档再新建卡片' : undefined"
          @click="openCreateCardDialog"
        >
          新建卡片
        </el-button>
      </div>
    </header>

    <div class="layout">
      <aside class="fs-card deck-list">
        <div class="fs-card__head">
          <span class="fs-card__title">
            卡组
            <span class="fs-card__hint" title="待复习 = 今天该复习的旧卡张数；新卡 = 还没学过的张数">待复习 / 新卡</span>
          </span>
        </div>
        <div class="deck-list__body">
          <button
            v-for="deck in liveDecks"
            :key="deck.id"
            class="deck"
            :class="{ 'deck--active': deck.id === activeDeckId }"
            @click="selectDeck(deck.id)"
          >
            <div class="deck__main">
              <div class="deck__name">{{ deck.name }}</div>
              <div class="deck__meta">
                <span>{{ deck.cardCount }} 张</span>
                <span class="deck__sep">·</span>
                <span class="deck__due">待复习 {{ deck.dueCount }}</span>
                <span class="deck__sep">·</span>
                <span class="deck__new">新卡 {{ deck.newCount }}</span>
              </div>
            </div>
            <div class="deck__bar">
              <span class="deck__bar-in" :style="{ width: `${deckPercent(deck)}%` }" />
            </div>
          </button>
          <div v-if="!loading.decks && liveDecks.length === 0" class="fs-empty">
            {{ archivedDecks.length ? '在用的卡组都归档了，展开下面的「已归档」可以取消归档。' : '还没有卡组，先新建一个。' }}
          </div>

          <!--
            Archived decks stay in the list but out of the way: a collapsed group at the bottom,
            visually muted, one「取消归档」per row. The open/closed state is remembered, and it
            defaults to closed - that is what "stop taking up room" means.
          -->
          <div v-if="archivedDecks.length" class="archived-group">
            <button class="archived-group__toggle" @click="toggleArchivedGroup">
              <span class="archived-group__caret">{{ archivedOpen ? '▾' : '▸' }}</span>
              已归档 ({{ archivedDecks.length }})
            </button>
            <div v-show="archivedOpen" class="archived-group__body">
              <div
                v-for="deck in archivedDecks"
                :key="deck.id"
                class="deck deck--archived"
                :class="{ 'deck--active': deck.id === activeDeckId }"
              >
                <button class="deck__select" @click="selectDeck(deck.id)">
                  <span class="deck__name">{{ deck.name }}</span>
                  <span class="deck__meta">{{ deck.cardCount }} 张</span>
                </button>
                <el-button
                  link
                  type="primary"
                  size="small"
                  :disabled="busyDecks[deck.id] === true"
                  @click="unarchiveDeck(deck)"
                >
                  取消归档
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <section class="fs-card cards-panel">
        <div class="fs-card__head">
          <span class="fs-card__title">
            {{ activeDeck?.name ?? (decks.length ? '请选择卡组' : '还没有卡组') }}
            <span class="fs-card__hint">{{ cards.length }} 张卡片</span>
          </span>
          <div class="panel-actions">
            <!--
              The deck's own actions live here rather than as icons inside each list row: the row
              itself is a button (it selects the deck), and nesting buttons inside a button is
              invalid HTML that browsers re-parent unpredictably.
            -->
            <div v-if="activeDeck" class="deck-actions">
              <el-button
                v-if="!activeDeckArchived"
                size="small"
                :disabled="activeDeckBusy"
                @click="openRenameDeckDialog"
              >
                重命名
              </el-button>
              <el-button v-if="!activeDeckArchived" size="small" :disabled="activeDeckBusy" @click="requestArchiveDeck">
                归档
              </el-button>
              <el-button v-else size="small" :disabled="activeDeckBusy" @click="requestUnarchiveDeck">
                取消归档
              </el-button>
              <el-button size="small" type="danger" plain :disabled="activeDeckBusy" @click="requestRemoveDeck">
                删除卡组
              </el-button>
            </div>
            <el-input
              v-model="keyword"
              class="search"
              placeholder="搜索正面 / 背面"
              clearable
              size="small"
            />
            <el-radio-group v-model="stateFilter" size="small" class="filter-group">
              <el-radio-button value="ALL" title="显示这个卡组里的全部卡片">全部</el-radio-button>
              <el-radio-button value="NEW" :title="STATE_HINT">新卡</el-radio-button>
              <el-radio-button value="DUE" :title="DUE_HINT">到期</el-radio-button>
            </el-radio-group>
            <el-button
              size="small"
              class="deleted-toggle"
              :type="showDeleted ? 'primary' : ''"
              plain
              @click="showDeleted = !showDeleted"
            >
              已删除{{ deletedTotal ? ` (${deletedTotal})` : '' }}
            </el-button>
          </div>
        </div>

        <div v-if="activeDeckArchived" class="archived-banner">
          这个卡组已归档，其中的卡片不会进入复习队列。
        </div>

        <el-collapse-transition>
          <div v-show="showDeleted" class="deleted-panel">
            <div class="deleted-panel__head">
              <span class="deleted-panel__title">已删除</span>
              <span class="deleted-panel__hint">
                删除只是标记：这些内容已从卡组列表、复习队列和统计里移除，但复习历史一条没丢，随时可以恢复。
              </span>
            </div>
            <p class="deleted-panel__source">
              {{
                deletedFromCache
                  ? '离线演示：以下是本机记录的删除内容。'
                  : '数据来自服务端，换浏览器或换设备也能看到这里的内容。'
              }}
            </p>

            <div v-if="deletedDecks.length" class="deleted-panel__group">
              <div class="deleted-panel__label">已删除的卡组</div>
              <el-table :data="deletedDecks" size="small" class="deleted-table">
                <el-table-column prop="name" label="卡组名称" min-width="180" show-overflow-tooltip />
                <el-table-column label="卡片数" width="96" align="right">
                  <template #default="{ row }">
                    <span class="fs-mono cell-num">{{ row.cardCount }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="保留历史" width="96" align="right">
                  <template #default="{ row }">
                    <span class="fs-mono cell-num">{{ row.reviewsPreserved }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="删除时间" width="150">
                  <template #default="{ row }">
                    <span class="fs-mono">{{ formatDateTime(row.deletedAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="88" align="right">
                  <template #default="{ row }">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="busyDecks[row.id] === true"
                      @click="restoreDeck(row)"
                    >
                      恢复
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div v-if="deletedCards.length" class="deleted-panel__group">
              <div class="deleted-panel__label">已删除的卡片</div>
              <el-table :data="deletedCards" size="small" class="deleted-table">
                <el-table-column label="所在卡组" min-width="150" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="cell-deck">{{ decks.find((d) => d.id === row.deckId)?.name ?? row.deckName }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="正面" min-width="200" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="cell-front">{{ row.front }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="保留历史" width="96" align="right">
                  <template #default="{ row }">
                    <span class="fs-mono cell-num">{{ row.reviewsPreserved }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="删除时间" width="150">
                  <template #default="{ row }">
                    <span class="fs-mono">{{ formatDateTime(row.deletedAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="88" align="right">
                  <template #default="{ row }">
                    <!-- A card cannot come back into a deck that is itself deleted, so restore the deck first. -->
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="busyCards[row.id] === true || deletedDeckIds.has(row.deckId)"
                      :title="deletedDeckIds.has(row.deckId) ? '所在卡组也在「已删除」里，先恢复卡组' : undefined"
                      @click="restoreCard(row)"
                    >
                      恢复
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div v-if="!deletedTotal" class="fs-empty">没有已删除的内容。</div>
          </div>
        </el-collapse-transition>

        <el-table
          v-loading="loading.cards"
          :data="filteredCards"
          class="cards-table"
          height="calc(100vh - 330px)"
          row-key="id"
          @row-click="(row: Card) => goDetail(row)"
        >
          <el-table-column label="正面" min-width="220">
            <template #default="{ row }">
              <div class="cell-front">{{ row.front }}</div>
            </template>
          </el-table-column>
          <el-table-column label="背面" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="cell-back">{{ row.back }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="118">
            <template #default="{ row }">
              <span class="fs-chip" :class="stateClass(row.state)">{{ stateLabel(row.state) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="S" width="86" align="right">
            <template #header>
              <TermHint term="S" :text="STABILITY_HINT" />
            </template>
            <template #default="{ row }">
              <span class="fs-mono cell-num">{{ row.stability?.toFixed(1) ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="D" width="86" align="right">
            <template #header>
              <TermHint term="D" :text="DIFFICULTY_HINT" />
            </template>
            <template #default="{ row }">
              <span class="fs-mono cell-num">{{ row.difficulty?.toFixed(1) ?? '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="到期" width="130">
            <template #default="{ row }">
              <span class="fs-mono" :class="{ 'cell-due': isDue(row) }">{{ relativeDays(row.due) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="复习 / 遗忘" width="128" align="right">
            <template #default="{ row }">
              <span class="fs-mono cell-num">{{ row.reps }} / {{ row.lapses }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="236" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" :disabled="isCardBusy(row)" @click.stop="goDetail(row)">
                详情
              </el-button>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="isCardBusy(row)"
                @click.stop="openEditCardDialog(row)"
              >
                编辑
              </el-button>
              <el-button
                link
                type="warning"
                size="small"
                :disabled="isCardBusy(row)"
                @click.stop="toggleSuspended(row)"
              >
                {{ row.suspended ? '恢复' : '暂停' }}
              </el-button>
              <el-button link type="danger" size="small" :disabled="isCardBusy(row)" @click.stop="removeCard(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="fs-empty">{{ emptyCardsHint }}</div>
          </template>
        </el-table>
      </section>
    </div>

    <!-- One dialog for both "new deck" and "rename deck": only the title and the action differ. -->
    <el-dialog v-model="deckDialog" :title="deckDialogMode === 'create' ? '新建卡组' : '重命名卡组'" width="420">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="卡组名称">
          <el-input v-model="deckForm.name" placeholder="例如：分布式系统 · 面试八股" @keyup.enter="submitDeck" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deckDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving.deck" @click="submitDeck">
          {{ deckDialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Same dialog for "new card" and "edit card": `cardDialogMode` drives title, body and submit. -->
    <el-dialog v-model="cardDialog" :title="cardDialogMode === 'create' ? '新建卡片' : '编辑卡片'" width="620">
      <el-form label-position="top" @submit.prevent>
        <el-form-item v-if="cardDialogMode === 'create'" label="所属卡组">
          <el-select v-model="cardForm.deckId" style="width: 100%">
            <el-option v-for="d in decks" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="正面（问题）">
          <el-input v-model="cardForm.front" type="textarea" :rows="3" placeholder="要回忆的问题" />
        </el-form-item>
        <el-form-item label="背面（答案）">
          <el-input v-model="cardForm.back" type="textarea" :rows="4" placeholder="答案 / 解释" />
        </el-form-item>
      </el-form>
      <p v-if="cardDialogMode === 'edit'" class="dialog-note">
        编辑只改文字：S / D、复习次数与到期时间都保持原样，卡片不会重新变成新卡。
      </p>
      <template #footer>
        <el-button @click="cardDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving.card" @click="submitCard">
          {{ cardDialogMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import TermHint from '@/components/TermHint.vue'
import { cardApi, deckApi, deletedApi, errorMessage, getBackendState } from '@/api'
import type { Card, CardState, Deck } from '@/types/api'
import type { DeletedCardRow, DeletedDeckRow } from '@/types/deleted'
import {
  forgetDeletedCard,
  forgetDeletedCardsOfDeck,
  forgetDeletedDeck,
  listDeletedRows,
  mergeInventory,
  pruneDeckCards,
  recordDeletedCard,
  recordDeletedDeck,
  retainServerRows,
} from '@/utils/deletedStore'
import { archivedGroupOpen, rememberArchivedGroupOpen } from '@/utils/uiPrefs'
import { daysFromToday, formatDateTime, relativeDays } from '@/utils/format'

/**
 * One-line glosses for the column vocabulary. They live here (rather than inline
 * in the template) so the "explain the term to a first-time reader" wording is
 * in one place per page.
 */
const STATE_HINT = '新卡 = 从未复习；学习中 / 重学中 = 刚学完还在短期内巩固；复习中 = 已经交给算法排期。'
const STABILITY_HINT = 'S（稳定性）：还有 90% 把握记得住它的天数。S=46 表示「46 天后你大约还有九成记得」。'
const DIFFICULTY_HINT = 'D（难度）：这张卡对你个人有多难，1~10，越大越难；它会随你的评分上下浮动。'
const DUE_HINT = '下次应该复习它的时间：3 天后 / 已逾期 2 天。'

const router = useRouter()

const decks = ref<Deck[]>([])
const cards = ref<Card[]>([])
const activeDeckId = ref<number | null>(null)
const keyword = ref('')
const stateFilter = ref<'ALL' | 'NEW' | 'DUE'>('ALL')

const loading = reactive({ decks: false, cards: false })
const saving = reactive({ deck: false, card: false })
const deckDialog = ref(false)
const cardDialog = ref(false)

/** Both dialogs serve two flows (create / rename, create / edit); the mode picks the title and the submit. */
const deckDialogMode = ref<'create' | 'rename'>('create')
const cardDialogMode = ref<'create' | 'edit'>('create')
const renamingDeck = ref<Deck | null>(null)
const editingCard = ref<Card | null>(null)

/** Rows with a write in flight, so their buttons stay disabled against a double submit. */
const busyCards = reactive<Record<number, boolean>>({})
const busyDecks = reactive<Record<number, boolean>>({})

/** The「已删除」panel: soft-deleted rows from this browser plus, offline, the demo dataset's markers. */
const deletedCards = ref<DeletedCardRow[]>([])
const deletedDecks = ref<DeletedDeckRow[]>([])
const showDeleted = ref(false)
/** true when the last load had to fall back to the offline demo data instead of the server list. */
const deletedFromCache = ref(false)
const deletedTotal = computed(() => deletedCards.value.length + deletedDecks.value.length)
const deletedDeckIds = computed(() => new Set(deletedDecks.value.map((d) => d.id)))

/**
 * Archived decks live in the same list as the rest (they are kept, not thrown away) but render in
 * their own collapsed group; the open state survives a reload.
 */
const archivedOpen = ref(archivedGroupOpen())

const deckForm = reactive({ name: '' })
const cardForm = reactive({ deckId: 0, front: '', back: '' })

const activeDeck = computed(() => decks.value.find((d) => d.id === activeDeckId.value) ?? null)
const liveDecks = computed(() => decks.value.filter((d) => d.archived !== true))
const archivedDecks = computed(() => decks.value.filter((d) => d.archived === true))
const activeDeckArchived = computed(() => activeDeck.value?.archived === true)
const activeDeckBusy = computed(() => (activeDeck.value ? busyDecks[activeDeck.value.id] === true : false))
const emptyCardsHint = computed(() => {
  if (activeDeck.value?.archived === true) return '这个卡组已归档，暂无卡片。'
  if (activeDeck.value) return '该卡组暂无卡片，点击右上角「新建卡片」。'
  return decks.value.length ? '先在左栏选择一个卡组。' : '还没有卡组，先新建一个卡组。'
})

const filteredCards = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return cards.value.filter((c) => {
    if (kw && !`${c.front} ${c.back}`.toLowerCase().includes(kw)) return false
    if (stateFilter.value === 'NEW') return c.state === 'NEW' || c.reps === 0
    if (stateFilter.value === 'DUE') return daysFromToday(c.due) <= 0
    return true
  })
})

function deckPercent(deck: Deck): number {
  if (!deck.cardCount) return 0
  return Math.min(100, ((deck.dueCount + deck.newCount) / deck.cardCount) * 100)
}

/**
 * `state` arrives as the numeric code from the live API (0 NEW, 1 LEARNING,
 * 2 REVIEW, 3 RELEARNING) and as the name from the offline mock, so normalise
 * both before mapping to a label or a colour - otherwise a numeric state renders
 * as a bare "2" and loses its colour.
 */
const STATE_BY_CODE: Record<number, string> = { 0: 'NEW', 1: 'LEARNING', 2: 'REVIEW', 3: 'RELEARNING' }
const STATE_LABELS: Record<string, string> = { NEW: '新卡', LEARNING: '学习中', REVIEW: '复习中', RELEARNING: '重学中' }
const STATE_CLASSES: Record<string, string> = {
  NEW: 'fs-chip--new',
  LEARNING: 'fs-chip--learning',
  REVIEW: 'fs-chip--review',
  RELEARNING: 'fs-chip--relearning',
}

function normalizeState(state: CardState | null | undefined): string | null {
  if (state === null || state === undefined) return null
  if (typeof state === 'number') return STATE_BY_CODE[state] ?? null
  return state in STATE_LABELS ? state : null
}

function stateLabel(state: CardState): string {
  const key = normalizeState(state)
  return key ? STATE_LABELS[key]! : '—'
}

function stateClass(state: CardState): string {
  const key = normalizeState(state)
  return key ? STATE_CLASSES[key]! : 'fs-chip--muted'
}

/** Due today or already overdue - matches the「到期」filter above. */
function isDue(card: Card): boolean {
  return daysFromToday(card.due) <= 0
}

async function loadDecks(): Promise<void> {
  loading.decks = true
  try {
    decks.value = await deckApi.list()
    // Keep the current selection while the deck still exists; otherwise fall back to the first live
    // deck, and only to an archived one when nothing is live. Cards are loaded by the caller, once
    // the selection has settled.
    if (!decks.value.some((d) => d.id === activeDeckId.value)) {
      const next = liveDecks.value[0] ?? decks.value[0] ?? null
      activeDeckId.value = next ? next.id : null
      cardForm.deckId = next ? next.id : 0
    }
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载卡组失败'))
  } finally {
    loading.decks = false
  }
}

async function loadCards(): Promise<void> {
  if (activeDeckId.value === null) {
    cards.value = []
    return
  }
  loading.cards = true
  try {
    cards.value = await cardApi.list(activeDeckId.value)
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载卡片失败'))
  } finally {
    loading.cards = false
  }
}

async function selectDeck(id: number): Promise<void> {
  activeDeckId.value = id
  cardForm.deckId = id
  await loadCards()
}

async function reloadAll(): Promise<void> {
  await loadDecks()
  await loadCards()
  await loadDeleted()
}

/**
 * The「已删除」panel reads two sources: the server's own list (the source of truth, so the same rows
 * show up in every browser) and this browser's record of the deletes it performed, which stands in
 * when the backend cannot be reached and covers a delete made moments ago. Unioning them means a row
 * the server knows about is always shown, whether or not this browser remembers deleting it.
 *
 * `pruneDeckCards` then applies the endpoints' own rule: a card that went away with its deck is
 * represented by the deck's row and must not appear next to it, and `retainServerRows` forgets local
 * records the server no longer lists (a card restored in another browser, for instance).
 */
async function loadDeleted(): Promise<void> {
  try {
    deletedCards.value = []
    deletedDecks.value = []
    const server = await deletedApi.list()
    // `withFallback` marks the gateway offline when it had to answer from the demo dataset, so this
    // is a snapshot of where the rows came from, not a live guess.
    deletedFromCache.value = getBackendState() === 'offline'
    // Only the server's own answer is authoritative enough to forget a local row by.
    if (!deletedFromCache.value) retainServerRows(server)
    const inventory = pruneDeckCards(mergeInventory(listDeletedRows(), server))
    deletedCards.value = inventory.cards
    deletedDecks.value = inventory.decks
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载「已删除」失败'))
  }
}

/**
 * After any write: re-read everything. Cards and decks can both change shape, the deck counters
 * (张数 / 待复习 / 新卡) are computed server-side, and a delete or restore moves rows in and out of
 * the「已删除」panel - so only a reload keeps the page true.
 */
async function refreshAfterWrite(): Promise<void> {
  await loadDecks()
  await loadCards()
  await loadDeleted()
}

/** One-line, whitespace-flattened preview of a card front for confirm texts. */
function preview(text: string, max = 24): string {
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > max ? `${flat.slice(0, max)}…` : flat
}

/** `row` reaches the operation column untyped, so only the id is trusted here. */
function isCardBusy(row: Card): boolean {
  return busyCards[row.id] === true
}

function deckNameOf(deckId: number): string {
  return decks.value.find((d) => d.id === deckId)?.name ?? '未知卡组'
}

/**
 * Success line for a delete. A soft delete only makes sense if the user knows nothing was lost, so
 * the kept review count and the way back are both stated - `ElMessage` has no room for a button.
 */
function deletedMessage(what: string, reviewsPreserved: number): string {
  const kept = reviewsPreserved > 0 ? `${reviewsPreserved} 条复习历史已保留` : '复习历史已保留'
  return `${what} · ${kept}，可在「已删除」中恢复`
}

// ---------------------------------------------------------------------------
// Deck writes
// ---------------------------------------------------------------------------

function openCreateDeckDialog(): void {
  deckDialogMode.value = 'create'
  renamingDeck.value = null
  deckForm.name = ''
  deckDialog.value = true
}

function openRenameDeckDialog(): void {
  const deck = activeDeck.value
  if (!deck) return
  deckDialogMode.value = 'rename'
  renamingDeck.value = deck
  deckForm.name = deck.name
  deckDialog.value = true
}

async function submitDeck(): Promise<void> {
  if (deckDialogMode.value === 'create') {
    await createDeck()
    return
  }
  await renameDeck()
}

async function createDeck(): Promise<void> {
  const name = deckForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入卡组名称')
    return
  }
  saving.deck = true
  try {
    const deck = await deckApi.create(name)
    ElMessage.success(`卡组「${deck.name}」已创建`)
    deckDialog.value = false
    await loadDecks()
    await selectDeck(deck.id)
  } catch (e) {
    ElMessage.error(errorMessage(e, '创建卡组失败'))
  } finally {
    saving.deck = false
  }
}

async function renameDeck(): Promise<void> {
  const deck = renamingDeck.value
  const name = deckForm.name.trim()
  if (!deck) return
  if (!name) {
    ElMessage.warning('请输入卡组名称')
    return
  }
  if (name === deck.name) {
    deckDialog.value = false
    return
  }
  saving.deck = true
  busyDecks[deck.id] = true
  try {
    const updated = await deckApi.rename(deck.id, name)
    ElMessage.success(`卡组已重命名为「${updated.name}」`)
    deckDialog.value = false
    await loadDecks()
  } catch (e) {
    ElMessage.error(errorMessage(e, '重命名卡组失败'))
  } finally {
    saving.deck = false
    delete busyDecks[deck.id]
  }
}

function requestRemoveDeck(): void {
  const deck = activeDeck.value
  if (deck) void removeDeck(deck)
}

function toggleArchivedGroup(): void {
  archivedOpen.value = !archivedOpen.value
  rememberArchivedGroupOpen(archivedOpen.value)
}

function requestArchiveDeck(): void {
  const deck = activeDeck.value
  if (deck && !deck.archived) void archiveDeck(deck)
}

function requestUnarchiveDeck(): void {
  const deck = activeDeck.value
  if (deck?.archived) void unarchiveDeck(deck)
}

/**
 * Archiving says "stop pushing this at me", so the confirmation has to make the two things clear
 * that are easy to get wrong: the cards leave the queue, and nothing at all is thrown away.
 */
async function archiveDeck(deck: Deck): Promise<void> {
  const cards = deck.cardCount
    ? `它的 ${deck.cardCount} 张卡片会从复习队列和统计中移除，`
    : '它会从复习队列和统计中移除，'
  try {
    await ElMessageBox.confirm(
      `归档「${deck.name}」？${cards}卡片与全部复习历史都会保留，随时可以取消归档。`,
      '归档卡组',
      { confirmButtonText: '归档', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  busyDecks[deck.id] = true
  try {
    const result = await deckApi.archive(deck.id)
    ElMessage.success(`已归档「${result.name}」 · 卡片与复习历史都保留，可在「已归档」里取消`)
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '归档卡组失败'))
  } finally {
    delete busyDecks[deck.id]
  }
}

async function unarchiveDeck(deck: Deck): Promise<void> {
  busyDecks[deck.id] = true
  try {
    const result = await deckApi.unarchive(deck.id)
    ElMessage.success(`「${result.name}」已取消归档，卡片重新进入复习队列`)
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '取消归档失败'))
  } finally {
    delete busyDecks[deck.id]
  }
}

async function removeDeck(deck: Deck): Promise<void> {
  const consequence = deck.cardCount
    ? `它下面的 ${deck.cardCount} 张卡片会从列表和复习队列中移除，但复习历史会保留（优化器仍可用）。之后可以从「已删除」里恢复。`
    : '它会从卡组列表里移除，但复习历史会保留（优化器仍可用）。之后可以从「已删除」里恢复。'
  try {
    await ElMessageBox.confirm(`删除「${deck.name}」？${consequence}`, '删除卡组', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  busyDecks[deck.id] = true
  try {
    const result = await deckApi.remove(deck.id)
    if (result.alreadyGone) {
      ElMessage.success(`卡组「${deck.name}」此前已经删除，可在「已删除」中恢复`)
    } else {
      ElMessage.success(
        `已删除卡组「${deck.name}」 · ${
          result.cardsDeleted ? `${result.cardsDeleted} 张卡片移出列表，` : ''
        }复习历史已保留，可在「已删除」中恢复`,
      )
    }
    recordDeletedDeck({
      id: deck.id,
      name: deck.name,
      cardCount: result.cardsDeleted,
      deletedAt: Date.now(),
      reviewsPreserved: result.reviewsPreserved,
    })
    // The deck's row now stands for any card of it that had been deleted individually earlier: the
    // read endpoints do not list those separately, so neither does the panel.
    forgetDeletedCardsOfDeck(deck.id)
    // Reloads every counter and re-selects another deck when this one was the selected deck; with
    // no decks left the right panel falls back to its empty state.
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '删除卡组失败'))
  } finally {
    delete busyDecks[deck.id]
  }
}

async function restoreDeck(row: DeletedDeckRow): Promise<void> {
  busyDecks[row.id] = true
  try {
    // A deck that was archived before it was deleted comes back archived - the backend keeps that
    // flag - so it may well land in the「已归档」group instead of the live list.
    const deck = await deckApi.restore(row.id)
    forgetDeletedDeck(row.id)
    ElMessage.success(deck.archived ? `卡组「${deck.name}」已恢复，仍处于归档状态` : `卡组「${deck.name}」已恢复`)
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '恢复卡组失败'))
  } finally {
    delete busyDecks[row.id]
  }
}

// ---------------------------------------------------------------------------
// Card writes
// ---------------------------------------------------------------------------

function openCreateCardDialog(): void {
  if (activeDeckArchived.value) {
    ElMessage.warning('这个卡组已归档，先取消归档再新建卡片')
    return
  }
  cardDialogMode.value = 'create'
  editingCard.value = null
  cardForm.deckId = activeDeckId.value ?? decks.value[0]?.id ?? 0
  cardForm.front = ''
  cardForm.back = ''
  cardDialog.value = true
}

function openEditCardDialog(card: Card): void {
  cardDialogMode.value = 'edit'
  editingCard.value = card
  cardForm.deckId = card.deckId
  cardForm.front = card.front
  cardForm.back = card.back
  cardDialog.value = true
}

async function submitCard(): Promise<void> {
  if (cardDialogMode.value === 'create') {
    await createCard()
    return
  }
  await updateCard()
}

async function createCard(): Promise<void> {
  if (!cardForm.front.trim() || !cardForm.back.trim()) {
    ElMessage.warning('正面与背面都需要填写')
    return
  }
  saving.card = true
  try {
    await cardApi.create(cardForm.deckId, cardForm.front.trim(), cardForm.back.trim())
    ElMessage.success('卡片已创建')
    cardDialog.value = false
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '创建卡片失败'))
  } finally {
    saving.card = false
  }
}

async function updateCard(): Promise<void> {
  const card = editingCard.value
  if (!card) return
  const front = cardForm.front.trim()
  const back = cardForm.back.trim()
  if (!front || !back) {
    ElMessage.warning('正面与背面都需要填写')
    return
  }
  if (front === card.front && back === card.back) {
    cardDialog.value = false
    return
  }
  saving.card = true
  busyCards[card.id] = true
  try {
    await cardApi.update(card.id, { front, back })
    ElMessage.success('卡片已更新')
    cardDialog.value = false
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '更新卡片失败'))
  } finally {
    saving.card = false
    delete busyCards[card.id]
  }
}

async function removeCard(card: Card): Promise<void> {
  const history = card.reps
    ? `它会从列表和复习队列中移除，但 ${card.reps} 条复习历史会保留（参数优化器还要用它）。之后可以在「已删除」里恢复。`
    : '它会从列表和复习队列中移除（它还没有复习历史）。之后可以在「已删除」里恢复。'
  try {
    await ElMessageBox.confirm(`确定删除这张卡片吗？${history}`, '删除卡片', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  busyCards[card.id] = true
  try {
    const result = await cardApi.remove(card.id)
    ElMessage.success(
      result.alreadyGone
        ? '这张卡片此前已经删除，可在「已删除」中恢复'
        : deletedMessage('已删除卡片', result.reviewsPreserved),
    )
    recordDeletedCard({
      id: card.id,
      deckId: card.deckId,
      front: card.front,
      deckName: deckNameOf(card.deckId),
      deletedAt: Date.now(),
      reviewsPreserved: result.reviewsPreserved,
    })
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '删除卡片失败'))
  } finally {
    delete busyCards[card.id]
  }
}

async function restoreCard(row: DeletedCardRow): Promise<void> {
  busyCards[row.id] = true
  try {
    await cardApi.restore(row.id)
    forgetDeletedCard(row.id)
    ElMessage.success(`已恢复「${preview(row.front)}」`)
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, '恢复卡片失败'))
  } finally {
    delete busyCards[row.id]
  }
}

/**
 * Pause / resume one card. Pausing goes through the dedicated `POST /cards/{id}/suspend`;
 * resuming has no endpoint of its own, so it is the partial update `PUT /cards/{id}` with
 * `suspended: false` - the only field that request changes.
 */
async function toggleSuspended(card: Card): Promise<void> {
  const suspended = card.suspended === true
  if (!suspended) {
    try {
      await ElMessageBox.confirm(
        `暂停后该卡片不再出现在复习队列中，确认暂停「${preview(card.front)}」？`,
        '暂停卡片',
        { confirmButtonText: '暂停', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  busyCards[card.id] = true
  try {
    if (suspended) await cardApi.update(card.id, { suspended: false })
    else await cardApi.suspend(card.id)
    ElMessage.success(suspended ? '已恢复' : '已暂停')
    await refreshAfterWrite()
  } catch (e) {
    ElMessage.error(errorMessage(e, suspended ? '恢复卡片失败' : '暂停卡片失败'))
  } finally {
    delete busyCards[card.id]
  }
}

function goDetail(card: Card): void {
  router.push(`/cards/${card.id}`)
}

onMounted(reloadAll)
</script>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.deck-list {
  overflow: hidden;
}

.deck-list__body {
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: calc(100vh - 260px);
  overflow-y: auto;
}

.deck {
  display: block;
  width: 100%;
  text-align: left;
  padding: 11px 12px 9px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.5);
  color: var(--fs-text-1);
  cursor: pointer;
  transition: all 0.16s ease;
}

.deck:hover {
  border-color: rgba(76, 201, 240, 0.35);
  background: rgba(76, 201, 240, 0.08);
}

.deck--active {
  border-color: rgba(76, 201, 240, 0.55);
  background: linear-gradient(135deg, rgba(76, 201, 240, 0.18), rgba(123, 140, 255, 0.08));
  color: var(--fs-text-0);
}

.deck__name {
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.45;
}

.deck__meta {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--fs-text-2);
  display: flex;
  gap: 5px;
  flex-wrap: wrap;
}

.deck__due {
  color: #ffc98f;
}

.deck__new {
  color: #9fd8ef;
}

.deck__bar {
  margin-top: 8px;
  height: 3px;
  border-radius: 3px;
  background: rgba(139, 155, 180, 0.16);
  overflow: hidden;
}

.deck__bar-in {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #4cc9f0, #7b8cff);
  transition: width 0.4s ease;
}

/* ---------------------------------------------------------------------------
   「已归档」group.

   Archived decks are kept, not thrown away, so they stay in the list - but dimmed and folded away
   at the bottom, because "stop pushing this at me" also means "stop taking up room".
   --------------------------------------------------------------------------- */
.archived-group {
  margin-top: 6px;
  padding-top: 8px;
  border-top: 1px dashed var(--fs-line-soft);
}

.archived-group__toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 2px;
  border: 0;
  background: none;
  color: var(--fs-text-2);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.archived-group__toggle:hover {
  color: var(--fs-text-1);
}

.archived-group__caret {
  font-size: 10px;
}

.archived-group__body {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.deck--archived {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  opacity: 0.68;
  /* Only the name inside the row selects the deck, so the row itself is not a click target. */
  cursor: default;
}

.deck--archived:hover,
.deck--archived.deck--active {
  opacity: 1;
}

/* The row is a container, so selecting the deck is this inner button and 「取消归档」sits beside it
   instead of inside it - a button inside a button is invalid HTML. */
.deck__select {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.archived-banner {
  margin: 12px 18px 0;
  padding: 9px 12px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid rgba(255, 171, 77, 0.32);
  background: rgba(255, 171, 77, 0.1);
  color: #ffcf8a;
  font-size: 12.5px;
  line-height: 1.6;
}

.panel-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

/* The selected deck's own actions, kept visually apart from the table filters next to them. */
.deck-actions {
  display: flex;
  gap: 8px;
  padding-right: 10px;
  border-right: 1px solid var(--fs-line-soft);
}

.dialog-note {
  margin: 2px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--fs-text-2);
}

.search {
  width: 200px;
}

/* ---------------------------------------------------------------------------
   The「已删除」panel.

   It sits between the panel head and the table so it can use the full width for the
   columns a deleted row needs (where it came from, what it said, when, how much
   history was kept) - the 300px deck column would have to wrap all of that.
   --------------------------------------------------------------------------- */
.deleted-panel {
  padding: 14px 18px 16px;
  border-bottom: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.55);
  max-height: 320px;
  overflow-y: auto;
}

.deleted-panel__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.deleted-panel__title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fs-text-0);
}

.deleted-panel__hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--fs-text-2);
}

.deleted-panel__source {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--fs-text-2);
  opacity: 0.85;
}

.deleted-panel__group + .deleted-panel__group {
  margin-top: 14px;
}

.deleted-panel__label {
  font-size: 12px;
  color: var(--fs-text-2);
  margin-bottom: 4px;
}

.deleted-table {
  width: 100%;
}

.cell-deck {
  color: var(--fs-text-1);
  font-size: 12.5px;
}

.cards-table {
  width: 100%;
}

.cards-table :deep(.el-table__row) {
  cursor: pointer;
}

/* ---------------------------------------------------------------------------
   Pinned "操作" column must actually occlude what scrolls under it.

   Element Plus pins a `fixed="right"` column with `position: sticky` and
   `background: inherit`. The theme sets `--el-table-tr-bg-color: transparent`
   (see styles/theme.css), so the <tr> it inherits from is see-through and the
   pinned column ends up transparent too - the columns underneath show through and
   their text collides with the buttons. The eight columns are wider than the panel,
   so this happens as soon as the window is not maximised.

   `!important` is required because Element Plus's own rules are both more specific
   and translucent: its row-hover background is an rgba() fill
   (--el-table-row-hover-bg-color), which would re-introduce the see-through effect
   on hover. --fs-bg-2 matches the panel gradient behind the table.
   --------------------------------------------------------------------------- */
.cards-table :deep(.el-table-fixed-column--right) {
  background: var(--fs-bg-2) !important;
}

.cards-table :deep(.el-table__body tr:hover > td.el-table-fixed-column--right),
.cards-table :deep(.el-table__body tr.hover-row > td.el-table-fixed-column--right) {
  /* The theme's hover tint composited onto --fs-bg-2 by hand. */
  background: #14263a !important;
}

.cell-front {
  font-weight: 550;
  color: var(--fs-text-0);
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.cell-back {
  color: var(--fs-text-2);
  font-size: 12.5px;
}

.cell-num {
  color: var(--fs-text-1);
}

.cell-due {
  color: #ffab4d;
}

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
