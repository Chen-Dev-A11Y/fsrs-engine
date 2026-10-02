<template>
  <div class="fs-page study">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          复习
          <span v-if="phase === 'running'" class="fs-chip fs-chip--review">进行中</span>
          <span v-else-if="phase === 'summary'" class="fs-chip fs-chip--new">本轮结束</span>
          <span v-else class="fs-chip fs-chip--muted">空闲</span>
        </h1>
        <p class="fs-page__sub">
          空格翻转答案，<span class="fs-kbd">1</span> <span class="fs-kbd">2</span> <span class="fs-kbd">3</span>
          <span class="fs-kbd">4</span> 直接评分 —— 每个按钮上显示的是该评分对应的下次间隔预测。
        </p>
      </div>
      <div class="head-actions">
        <el-select v-model="deckId" class="deck-select" placeholder="全部卡组" clearable size="default" @change="restart">
          <el-option v-for="d in decks" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-select v-model="limit" class="limit-select" size="default" @change="restart">
          <el-option v-for="n in [10, 20, 40, 60]" :key="n" :label="`每轮 ${n} 张`" :value="n" />
        </el-select>
        <el-button :loading="loading.queue" @click="restart">重新取队列</el-button>
      </div>
    </header>

    <!-- 进度 + 评分统计 -->
    <section class="fs-card progress-card">
      <div class="progress-card__row">
        <div class="progress-card__main">
          <div class="progress-card__label">
            <span>本轮进度</span>
            <span class="fs-mono progress-card__count">{{ reviewed.length }} / {{ queue.length }}</span>
          </div>
          <el-progress
            :percentage="progressPercent"
            :stroke-width="10"
            :show-text="false"
            :color="progressColor"
            class="progress-card__bar"
          />
          <div class="progress-card__meta">
            <span>到期 <b class="fs-mono">{{ queueTotalDue }}</b></span>
            <span>新卡 <b class="fs-mono">{{ queueTotalNew }}</b></span>
            <span>已计时 <b class="fs-mono">{{ elapsedText }}</b></span>
            <span v-if="queue.length">剩余 <b class="fs-mono">{{ queue.length - cursor }}</b> 张</span>
          </div>
        </div>

        <div class="progress-card__stats">
          <div v-for="meta in statCards" :key="meta.rating" class="stat" :style="{ '--c': meta.color }">
            <div class="stat__top">
              <span class="stat__key fs-mono">{{ meta.shortcut }}</span>
              <span class="stat__label">{{ meta.label }}</span>
            </div>
            <div class="stat__value fs-mono">{{ counts[meta.rating] }}</div>
            <div class="stat__hint">{{ meta.zh }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- 复习主体 -->
    <section v-if="phase === 'running' && current" class="stage">
      <div class="stage__card-wrap">
        <div class="flip" :class="{ 'flip--done': flipped }" @click="flip">
          <div class="flip__inner">
            <div class="flip__face flip__face--front">
              <div class="face__head">
                <span class="fs-chip" :class="stateClass(current.state)">{{ stateLabel(current.state) }}</span>
                <span v-if="current.isNew" class="fs-chip fs-chip--new">新卡</span>
                <span class="face__metrics fs-mono">
                  <span v-if="!current.isNew">R {{ (current.retrievability * 100).toFixed(0) }}%</span>
                  <span v-if="!current.isNew">S {{ current.stability?.toFixed(1) ?? '—' }}d</span>
                  <span v-if="!current.isNew">D {{ current.difficulty?.toFixed(1) ?? '—' }}</span>
                </span>
              </div>
              <div class="face__body">
                <p class="face__text">{{ current.front }}</p>
              </div>
              <div class="face__foot">
                <span><span class="fs-kbd">空格</span> 或点击卡片翻转</span>
                <span class="face__pos">{{ cursor + 1 }} / {{ queue.length }}</span>
              </div>
            </div>

            <div class="flip__face flip__face--back">
              <div class="face__head">
                <span class="fs-chip fs-chip--review">答案</span>
                <span class="face__metrics fs-mono">
                  <span v-if="current.stability">S {{ current.stability.toFixed(1) }}d</span>
                  <span v-if="current.difficulty">D {{ current.difficulty.toFixed(1) }}</span>
                </span>
              </div>
              <div class="face__body">
                <p class="face__text face__text--answer">{{ current.back }}</p>
              </div>
              <div class="face__foot">
                <span>选择评分，或在键盘上按 <span class="fs-kbd">1</span>–<span class="fs-kbd">4</span></span>
                <span class="face__pos">{{ cursor + 1 }} / {{ queue.length }}</span>
              </div>
            </div>
          </div>
        </div>

        <transition name="fade-slide">
          <div v-if="flipped" class="rating-bar">
            <button
              v-for="meta in ratingButtons"
              :key="meta.rating"
              class="rating"
              :class="{ 'rating--busy': submitting }"
              :style="{ '--c': meta.color }"
              :disabled="submitting"
              @click.stop="rate(meta.rating)"
            >
              <span class="rating__key fs-mono">{{ meta.shortcut }}</span>
              <span class="rating__label">{{ meta.label }}</span>
              <span class="rating__interval">
                <span class="rating__interval-value fs-mono">{{ intervalText(meta.rating, meta.raw) }}</span>
                <span class="rating__interval-cap">下次间隔</span>
              </span>
            </button>
            <div class="rating-bar__note">
              预测间隔由接口 <span class="fs-mono">predictedIntervals</span> 提供；离线演示模式下由前端按 FSRS-6
              公式本地计算。
            </div>
          </div>
        </transition>

        <transition name="fade-slide">
          <div v-if="feedback" class="feedback" :class="{ 'feedback--dup': feedback.duplicate }">
            <span class="feedback__rating" :style="{ color: feedback.color }">{{ feedback.ratingLabel }}</span>
            <span class="feedback__text">
              下次
              <b class="fs-mono">{{ formatDays(feedback.intervalDays) }}</b>
              后（{{ feedback.nextDueText }}）
            </span>
            <span class="feedback__meta fs-mono">
              S {{ feedback.stability.toFixed(1) }}d · D {{ feedback.difficulty.toFixed(1) }} · 用时
              {{ formatMs(feedback.durationMs) }}
              <template v-if="feedback.duplicate"> · 幂等命中，未重复计分</template>
            </span>
          </div>
        </transition>
      </div>
    </section>

    <!-- 空队列 -->
    <section v-else-if="phase === 'empty'" class="fs-card stage-empty">
      <div class="stage-empty__icon">✓</div>
      <h2>今日没有到期的卡片</h2>
      <p>当前队列为空。换个卡组、提高每轮上限，或去「卡组与卡片」新建一些卡片。</p>
      <div class="stage-empty__actions">
        <el-button type="primary" @click="restart">重新取队列</el-button>
        <el-button @click="$router.push('/decks')">去建卡</el-button>
        <el-button @click="$router.push('/stats')">看看统计</el-button>
      </div>
    </section>

    <!-- 本轮小结 -->
    <section v-else class="fs-card summary">
      <div class="summary__head">
        <div>
          <h2>本轮复习小结</h2>
          <p class="summary__sub">共 {{ reviewed.length }} 张 · 本轮用时 {{ elapsedText }}</p>
        </div>
        <div class="summary__actions">
          <el-button type="primary" @click="restart">再来一轮</el-button>
          <el-button @click="$router.push('/stats')">查看统计</el-button>
        </div>
      </div>

      <div class="summary__grid">
        <MetricCard label="复习张数" :value="reviewed.length" unit="张" tone="accent" tag="本轮" :bar-percent="100" />
        <MetricCard
          label="平均用时"
          :value="avgDurationSeconds"
          unit="秒/张"
          tone="violet"
          :hint="`累计 ${elapsedText}`"
          :bar-percent="Math.min(100, Number(avgDurationSeconds) * 4)"
        />
        <MetricCard
          label="Again 比例"
          :value="againRatioPercent"
          unit="%"
          :tone="againRatio >= 0.3 ? 'again' : 'good'"
          :hint="`${counts[1]} 张需要重学`"
          :bar-percent="againRatioPercentNumber"
        />
        <MetricCard
          label="保持率"
          :value="recallPercent"
          unit="%"
          tone="good"
          hint="Hard + Good + Easy 占比"
          :bar-percent="recallPercentNumber"
        />
      </div>

      <div class="summary__bars">
        <div v-for="meta in statCards" :key="meta.rating" class="sbar">
          <span class="sbar__label" :style="{ color: meta.color }">{{ meta.label }}</span>
          <span class="sbar__track">
            <span
              class="sbar__fill"
              :style="{ width: `${barPercent(counts[meta.rating])}%`, background: meta.color }"
            />
          </span>
          <span class="sbar__value fs-mono">{{ counts[meta.rating] }}</span>
          <span class="sbar__avg fs-mono">均 {{ avgPerRating(meta.rating) }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import MetricCard from '@/components/MetricCard.vue'
import { deckApi, errorMessage, studyApi } from '@/api'
import { RATING_META, type Deck, type Rating, type ReviewQueueCard } from '@/types/api'
import { formatDays } from '@/utils/fsrs'
import { formatMs, relativeDays } from '@/utils/format'

interface Feedback {
  rating: Rating
  ratingLabel: string
  color: string
  intervalDays: number
  nextDueText: string
  stability: number
  difficulty: number
  durationMs: number
  duplicate: boolean
}

const decks = ref<Deck[]>([])
const queue = ref<ReviewQueueCard[]>([])
const queueTotalDue = ref(0)
const queueTotalNew = ref(0)
const deckId = ref<number | undefined>(undefined)
const limit = ref(20)

const cursor = ref(0)
const flipped = ref(false)
const submitting = ref(false)
const phase = ref<'empty' | 'running' | 'summary'>('empty')
const feedback = ref<Feedback | null>(null)
const loading = reactive({ queue: false })

const reviewed = ref<{ cardId: number; rating: Rating; durationMs: number; intervalDays: number }[]>([])
const counts = reactive<Record<Rating, number>>({ 1: 0, 2: 0, 3: 0, 4: 0 })
const sessionStart = ref<number>(Date.now())
const cardShownAt = ref<number>(Date.now())
const now = ref(Date.now())
let timer: number | undefined

const current = computed(() => queue.value[cursor.value] ?? null)
const progressPercent = computed(() =>
  queue.value.length ? Math.round((reviewed.value.length / queue.value.length) * 100) : 0,
)
const progressColor = computed(() =>
  progressPercent.value >= 100 ? '#3ddc97' : progressPercent.value > 50 ? '#4cc9f0' : '#7b8cff',
)
const elapsedMs = computed(() => now.value - sessionStart.value)
const elapsedText = computed(() => formatMs(elapsedMs.value))
const avgDuration = computed(() =>
  reviewed.value.length
    ? reviewed.value.reduce((sum, r) => sum + r.durationMs, 0) / reviewed.value.length
    : 0,
)
const avgDurationSeconds = computed(() => (avgDuration.value / 1000).toFixed(1))
const againRatio = computed(() => (reviewed.value.length ? counts[1] / reviewed.value.length : 0))
const againRatioPercent = computed(() => (againRatio.value * 100).toFixed(1))
const againRatioPercentNumber = computed(() => Number(againRatioPercent.value))
const recallPercent = computed(() =>
  reviewed.value.length ? (((reviewed.value.length - counts[1]) / reviewed.value.length) * 100).toFixed(1) : '0.0',
)
const recallPercentNumber = computed(() => Number(recallPercent.value))

const statCards = [1, 2, 3, 4].map((rating) => ({
  rating: rating as Rating,
  label: RATING_META[rating as Rating].label,
  zh: RATING_META[rating as Rating].zh,
  color: RATING_META[rating as Rating].color,
  shortcut: RATING_META[rating as Rating].shortcut,
}))

const ratingButtons = computed(() =>
  ([1, 2, 3, 4] as Rating[]).map((rating) => ({
    rating,
    label: RATING_META[rating].label,
    color: RATING_META[rating].color,
    shortcut: RATING_META[rating].shortcut,
    raw: current.value?.predictedIntervals[ratingKey(rating)] ?? 1,
  })),
)

function ratingKey(rating: Rating): 'again' | 'hard' | 'good' | 'easy' {
  return ({ 1: 'again', 2: 'hard', 3: 'good', 4: 'easy' } as const)[rating]
}

function intervalText(rating: Rating, days: number): string {
  return `${RATING_META[rating].label} · ${formatDays(days)}`
}

/**
 * The live API sends `state` as the numeric code the database stores (0 NEW, 1 LEARNING,
 * 2 REVIEW, 3 RELEARNING) while the offline mock uses the string names - same duplication
 * lives in CardDetailPage. Both are normalised here; without it the chip renders a bare
 * "2" and gets no colour class.
 */
const STATE_BY_CODE: Record<number, string> = { 0: 'NEW', 1: 'LEARNING', 2: 'REVIEW', 3: 'RELEARNING' }
const STATE_LABELS: Record<string, string> = { NEW: '新卡', LEARNING: '学习中', REVIEW: '复习中', RELEARNING: '重学中' }
const STATE_CLASSES: Record<string, string> = {
  NEW: 'fs-chip--new',
  LEARNING: 'fs-chip--learning',
  REVIEW: 'fs-chip--review',
  RELEARNING: 'fs-chip--relearning',
}

function normalizeState(state: string | number | null | undefined): string | null {
  if (state === null || state === undefined) return null
  if (typeof state === 'number') return STATE_BY_CODE[state] ?? null
  return state in STATE_LABELS ? state : null
}

function stateLabel(state: string | number | null | undefined): string {
  const key = normalizeState(state)
  return key ? STATE_LABELS[key] : '—'
}

function stateClass(state: string | number | null | undefined): string {
  const key = normalizeState(state)
  return key ? STATE_CLASSES[key] : 'fs-chip--muted'
}

function barPercent(count: number): number {
  const max = Math.max(1, ...([1, 2, 3, 4] as Rating[]).map((r) => counts[r]))
  return Math.round((count / max) * 100)
}

function avgPerRating(rating: Rating): string {
  const rows = reviewed.value.filter((r) => r.rating === rating)
  if (!rows.length) return '—'
  return formatMs(rows.reduce((s, r) => s + r.durationMs, 0) / rows.length)
}

async function loadDecks(): Promise<void> {
  try {
    // Archived decks are out of the study queue by definition, so offering one here would produce
    // an empty session that looks like a bug.
    decks.value = (await deckApi.list()).filter((d) => d.archived !== true)
  } catch {
    /* the queue load below will surface the problem */
  }
}

async function restart(): Promise<void> {
  loading.queue = true
  try {
    const data = await studyApi.queue(deckId.value, limit.value)
    queue.value = data.cards
    queueTotalDue.value = data.totalDue
    queueTotalNew.value = data.totalNew
    cursor.value = 0
    flipped.value = false
    feedback.value = null
    reviewed.value = []
    counts[1] = counts[2] = counts[3] = counts[4] = 0
    sessionStart.value = Date.now()
    cardShownAt.value = Date.now()
    phase.value = data.cards.length ? 'running' : 'empty'
  } catch (e) {
    ElMessage.error(errorMessage(e, '获取复习队列失败'))
    phase.value = 'empty'
  } finally {
    loading.queue = false
  }
}

function flip(): void {
  if (phase.value !== 'running') return
  flipped.value = !flipped.value
}

async function rate(rating: Rating): Promise<void> {
  if (!flipped.value || submitting.value || !current.value) return
  const card = current.value
  const durationMs = Math.max(0, Date.now() - cardShownAt.value)
  submitting.value = true
  try {
    const result = await studyApi.review({
      cardId: card.cardId,
      rating,
      durationMs,
      clientReviewId: crypto.randomUUID(),
    })
    counts[rating] += 1
    reviewed.value = [...reviewed.value, { cardId: card.cardId, rating, durationMs, intervalDays: result.intervalDays }]
    feedback.value = {
      rating,
      ratingLabel: `${RATING_META[rating].label} · ${RATING_META[rating].zh}`,
      color: RATING_META[rating].color,
      intervalDays: result.intervalDays,
      nextDueText: relativeDays(result.nextDue),
      stability: result.stability,
      difficulty: result.difficulty,
      durationMs,
      duplicate: result.duplicate,
    }
    // Show the scheduling feedback, then advance.
    window.setTimeout(advance, 620)
  } catch (e) {
    ElMessage.error(errorMessage(e, '提交复习失败'))
  } finally {
    submitting.value = false
  }
}

function advance(): void {
  feedback.value = null
  flipped.value = false
  if (cursor.value + 1 >= queue.value.length) {
    phase.value = 'summary'
    return
  }
  cursor.value += 1
  cardShownAt.value = Date.now()
}

function onKeydown(event: KeyboardEvent): void {
  const target = event.target as HTMLElement | null
  if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (phase.value !== 'running') return
  if (event.code === 'Space') {
    event.preventDefault()
    flip()
    return
  }
  if (event.key >= '1' && event.key <= '4') {
    event.preventDefault()
    void rate(Number(event.key) as Rating)
  }
}

onMounted(async () => {
  await loadDecks()
  await restart()
  window.addEventListener('keydown', onKeydown)
  timer = window.setInterval(() => {
    now.value = Date.now()
  }, 500)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.deck-select {
  width: 220px;
}

.limit-select {
  width: 132px;
}

/* ---------------- progress ---------------- */

.progress-card__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 22px;
  padding: 16px 18px;
  align-items: center;
}

.progress-card__label {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--fs-text-2);
  margin-bottom: 7px;
}

.progress-card__count {
  color: var(--fs-text-0);
  font-weight: 600;
}

.progress-card__bar :deep(.el-progress-bar__outer) {
  border-radius: 6px;
}

.progress-card__meta {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 10px;
  font-size: 12px;
  color: var(--fs-text-2);
}

.progress-card__meta b {
  color: var(--fs-text-0);
}

.progress-card__stats {
  display: grid;
  grid-template-columns: repeat(4, 92px);
  gap: 10px;
}

.stat {
  padding: 9px 10px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid color-mix(in srgb, var(--c) 30%, transparent);
  background: color-mix(in srgb, var(--c) 9%, transparent);
}

.stat__top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stat__key {
  font-size: 10px;
  padding: 0 5px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--c) 22%, transparent);
  color: var(--c);
}

.stat__label {
  font-size: 11.5px;
  color: var(--fs-text-1);
}

.stat__value {
  font-size: 21px;
  font-weight: 600;
  color: var(--c);
  line-height: 1.25;
}

.stat__hint {
  font-size: 10.5px;
  color: var(--fs-text-2);
}

/* ---------------- stage / flip card ---------------- */

.stage {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.stage__card-wrap {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.flip {
  perspective: 1600px;
  height: 360px;
  cursor: pointer;
}

.flip__inner {
  position: relative;
  width: 100%;
  height: 100%;
  transition: transform 0.52s cubic-bezier(0.4, 0.15, 0.2, 1);
  transform-style: preserve-3d;
}

.flip--done .flip__inner {
  transform: rotateY(180deg);
}

.flip__face {
  position: absolute;
  inset: 0;
  backface-visibility: hidden;
  display: flex;
  flex-direction: column;
  border-radius: var(--fs-radius);
  border: 1px solid var(--fs-line);
  background: linear-gradient(180deg, rgba(22, 34, 58, 0.95), rgba(12, 19, 33, 0.96));
  box-shadow: var(--fs-shadow);
  overflow: hidden;
}

.flip__face--front {
  border-left: 3px solid var(--fs-accent);
}

.flip__face--back {
  transform: rotateY(180deg);
  border-left: 3px solid var(--fs-good);
}

.face__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--fs-line-soft);
}

.face__metrics {
  margin-left: auto;
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: var(--fs-text-2);
}

.face__body {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 26px 40px;
  overflow-y: auto;
}

.face__text {
  margin: 0;
  font-size: 25px;
  line-height: 1.6;
  font-weight: 550;
  text-align: center;
  letter-spacing: 0.3px;
  max-width: 900px;
  white-space: pre-wrap;
}

.face__text--answer {
  font-size: 19px;
  font-weight: 400;
  color: #d5e6f5;
  text-align: left;
  line-height: 1.8;
}

.face__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 11px 18px;
  border-top: 1px solid var(--fs-line-soft);
  font-size: 12px;
  color: var(--fs-text-2);
}

.face__pos {
  font-family: var(--fs-font-mono);
}

/* ---------------- rating bar ---------------- */

.rating-bar {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.rating {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 13px 15px;
  border-radius: var(--fs-radius);
  border: 1px solid color-mix(in srgb, var(--c) 40%, transparent);
  background: linear-gradient(180deg, color-mix(in srgb, var(--c) 15%, transparent), rgba(12, 19, 33, 0.85));
  color: var(--fs-text-0);
  cursor: pointer;
  text-align: left;
  transition: transform 0.14s ease, box-shadow 0.16s ease, border-color 0.16s ease;
}

.rating:hover:not(:disabled) {
  transform: translateY(-2px);
  border-color: var(--c);
  box-shadow: 0 12px 26px color-mix(in srgb, var(--c) 26%, transparent);
}

.rating:active:not(:disabled) {
  transform: translateY(0);
}

.rating--busy {
  opacity: 0.6;
  cursor: progress;
}

.rating__key {
  position: absolute;
  top: 9px;
  right: 11px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 6px;
  font-size: 11px;
  background: color-mix(in srgb, var(--c) 22%, transparent);
  color: var(--c);
  border: 1px solid color-mix(in srgb, var(--c) 40%, transparent);
}

.rating__label {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.4px;
  color: var(--c);
}

.rating__interval {
  display: flex;
  flex-direction: column;
}

.rating__interval-value {
  font-size: 17px;
  font-weight: 600;
}

.rating__interval-cap {
  font-size: 10.5px;
  color: var(--fs-text-2);
  letter-spacing: 0.4px;
}

.rating-bar__note {
  grid-column: 1 / -1;
  font-size: 11.5px;
  color: var(--fs-text-2);
}

/* ---------------- feedback ---------------- */

.feedback {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 11px 16px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid rgba(61, 220, 151, 0.35);
  background: rgba(61, 220, 151, 0.08);
  font-size: 13.5px;
}

.feedback--dup {
  border-color: rgba(139, 155, 180, 0.4);
  background: rgba(139, 155, 180, 0.08);
}

.feedback__rating {
  font-weight: 650;
  letter-spacing: 0.4px;
}

.feedback__text b {
  color: var(--fs-good);
  font-size: 15px;
}

.feedback__meta {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--fs-text-2);
}

/* ---------------- empty / summary ---------------- */

.stage-empty {
  padding: 54px 24px;
  text-align: center;
}

.stage-empty__icon {
  width: 54px;
  height: 54px;
  margin: 0 auto 16px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 24px;
  color: var(--fs-good);
  background: rgba(61, 220, 151, 0.12);
  border: 1px solid rgba(61, 220, 151, 0.4);
}

.stage-empty h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.stage-empty p {
  margin: 0 auto 22px;
  max-width: 520px;
  color: var(--fs-text-2);
  font-size: 13px;
}

.stage-empty__actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}

.summary__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 18px;
  border-bottom: 1px solid var(--fs-line-soft);
}

.summary__head h2 {
  margin: 0;
  font-size: 19px;
}

.summary__sub {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--fs-text-2);
}

.summary__actions {
  display: flex;
  gap: 8px;
}

.summary__grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  padding: 18px;
}

.summary__bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 4px 18px 20px;
}

.sbar {
  display: grid;
  grid-template-columns: 62px minmax(0, 1fr) 44px 84px;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}

.sbar__track {
  height: 8px;
  border-radius: 5px;
  background: rgba(139, 155, 180, 0.14);
  overflow: hidden;
}

.sbar__fill {
  display: block;
  height: 100%;
  border-radius: 5px;
  transition: width 0.4s ease;
}

.sbar__value {
  text-align: right;
  color: var(--fs-text-0);
  font-weight: 600;
}

.sbar__avg {
  color: var(--fs-text-2);
  font-size: 11.5px;
}

@media (max-width: 1200px) {
  .progress-card__row {
    grid-template-columns: minmax(0, 1fr);
  }
  .progress-card__stats {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
  .rating-bar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .progress-card__stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .face__text {
    font-size: 20px;
  }
  .flip {
    height: 300px;
  }
}
</style>
