<template>
  <div class="fs-page">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          卡片详情
          <span v-if="card" class="fs-chip" :class="stateClass(card.state)">{{ stateLabel(card.state) }}</span>
          <span class="fs-chip fs-chip--muted fs-mono">#{{ cardId }}</span>
        </h1>
        <p class="fs-page__sub">
          这张卡现在的记忆状态，以及每次复习后「记得有多牢」的变化。卡片上的
          <span class="fs-mono">?</span>
          可以悬停查看白话解释。
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="$router.back()">返回</el-button>
        <el-button @click="$router.push('/study')">去复习</el-button>
        <el-button type="warning" plain @click="suspend">暂停卡片</el-button>
      </div>
    </header>

    <div v-if="loading" class="fs-card placeholder">正在加载卡片状态…</div>

    <template v-else-if="card">
      <section class="fs-card qa">
        <div class="qa__grid">
          <div class="qa__block">
            <div class="qa__label">正面 · 问题</div>
            <p class="qa__text">{{ card.front }}</p>
          </div>
          <div class="qa__block qa__block--back">
            <div class="qa__label">背面 · 答案</div>
            <p class="qa__text qa__text--back">{{ card.back }}</p>
          </div>
        </div>
      </section>

      <section class="fs-stat-row">
        <MetricCard
          label="稳定性 S"
          :value="stabilityText"
          unit="天"
          tone="accent"
          :hint="stabilityHint"
        >
          <template #label>
            <TermHint term="稳定性 S" :text="S_HINT" />
          </template>
        </MetricCard>
        <MetricCard
          label="难度 D"
          :value="difficultyText"
          unit="/ 10"
          tone="violet"
          :hint="`${DIFFICULTY_HINT}${card.reps === 0 ? '还没复习过，这个数是按首次评分估的。' : ''}`"
        >
          <template #label>
            <TermHint term="难度 D" :text="D_HINT" />
          </template>
        </MetricCard>
        <MetricCard
          label="下次到期"
          :value="relativeDays(card.due)"
          tone="good"
          :hint="`具体时间：${prettyDate(card.due)}`"
        />
        <MetricCard
          label="复习 / 遗忘"
          :value="`${card.reps} / ${card.lapses}`"
          tone="hard"
          :hint="`复习 ${card.reps} 次，其中忘了 ${card.lapses} 次（遗忘率 ${lapseRate}%）`"
        />
      </section>

      <section class="fs-card">
        <div class="fs-card__head">
          <span class="fs-card__title">
            记忆牢度演进
            <span class="fs-card__hint">每次复习后 S 的变化 · 点的颜色 = 当时评分</span>
          </span>
          <div class="head-tools">
            <el-radio-group v-model="ratingFilter" size="small">
              <el-radio-button value="ALL">全部</el-radio-button>
              <el-radio-button value="1">仅 Again</el-radio-button>
            </el-radio-group>
            <el-checkbox v-model="showDifficulty" size="small">显示难度 D</el-checkbox>
          </div>
        </div>
        <div class="fs-card__body">
          <div v-if="!filteredReviews.length" class="fs-empty">这张卡还没有复习记录，去复习页打分后这里就会出现曲线。</div>
          <StabilityTimelineChart v-else :reviews="filteredReviews" :show-difficulty="showDifficulty" :height="320" />

          <div class="rating-legend">
            <span v-for="meta in ratingLegend" :key="meta.rating" class="rating-legend__item">
              <span class="rating-legend__dot" :style="{ background: meta.color }" />
              {{ meta.label }}
            </span>
            <span class="rating-legend__note">
              平均间隔
              <b class="fs-mono">{{ avgInterval }} 天</b>
              · 复习时平均 R
              <b class="fs-mono">{{ avgRetrievability }}%</b>
            </span>
          </div>

          <ChartHint :lines="timelineHintLines" />
        </div>
      </section>

      <section class="fs-card">
        <div class="fs-card__head">
          <span class="fs-card__title">
            复习日志
            <span class="fs-card__hint">{{ reviews.length }} 条 · 表头带「?」可悬停看白话解释</span>
          </span>
        </div>
        <el-table :data="reviewsAsc" height="360" class="log-table">
          <el-table-column label="#" width="52">
            <template #default="{ $index }">
              <span class="fs-mono cell-dim">{{ $index + 1 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="复习时间" width="150">
            <template #default="{ row }">
              <span class="fs-mono">{{ formatDateTime(row.reviewedAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="评分" width="98">
            <template #default="{ row }">
              <span class="fs-chip" :style="ratingChipStyle(row.rating)">{{ RATING_META[row.rating as Rating].label }}</span>
            </template>
          </el-table-column>
          <el-table-column label="S 变化" width="150">
            <template #default="{ row }">
              <span class="fs-mono">
                {{ row.stabilityBefore?.toFixed(2) ?? '—' }}
                <span class="arrow" :class="(row.stabilityAfter ?? 0) >= (row.stabilityBefore ?? 0) ? 'arrow--up' : 'arrow--down'">→</span>
                <b>{{ row.stabilityAfter?.toFixed(2) ?? '—' }}</b>
              </span>
            </template>
          </el-table-column>
          <el-table-column label="D 变化" width="140">
            <template #default="{ row }">
              <span class="fs-mono cell-dim">
                {{ row.difficultyBefore?.toFixed(2) ?? '—' }} → {{ row.difficultyAfter?.toFixed(2) ?? '—' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="复习时 R" width="110">
            <template #header>
              <TermHint term="复习时 R" text="你复习它的那一刻，算法估计你还能记起来的概率；越低说明忘得越多。" />
            </template>
            <template #default="{ row }">
              <span class="fs-mono">{{ row.retrievability == null ? '—' : (row.retrievability * 100).toFixed(1) }}%</span>
            </template>
          </el-table-column>
          <el-table-column label="排期间隔" width="110">
            <template #default="{ row }">
              <span class="fs-mono">{{ row.intervalDays }} 天</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="fs-empty">暂无复习日志。</div>
          </template>
        </el-table>
      </section>
    </template>

    <div v-else class="fs-card placeholder placeholder--error">
      找不到该卡片（id={{ cardId }}）。可能它已被删除，或后端暂未提供该记录。
      <div class="placeholder__actions">
        <el-button type="primary" @click="$router.push('/decks')">回到卡组</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ChartHint from '@/components/ChartHint.vue'
import MetricCard from '@/components/MetricCard.vue'
import TermHint from '@/components/TermHint.vue'
import StabilityTimelineChart from '@/components/charts/StabilityTimelineChart.vue'
import { cardApi, errorMessage } from '@/api'
import { RATING_META, type Card, type TimelineReview, type Rating } from '@/types/api'
import { formatDateTime, prettyDate, relativeDays } from '@/utils/format'

/** The same plain-language glossary the 卡组与卡片 page uses for its columns. */
const S_HINT = 'S（稳定性）：还有 90% 把握记得住它的天数。S=46 表示「46 天后你大约还有九成记得」。'
const D_HINT = 'D（难度）：这张卡对你个人有多难，1~10，越大越难；它会随你的评分上下浮动。'
const DIFFICULTY_HINT = '这张卡对你个人的难度：1 最容易，10 最难。'

const route = useRoute()
const router = useRouter()

const cardId = Number(route.params.id)
const card = ref<Card | null>(null)
const reviews = ref<TimelineReview[]>([])
const loading = ref(true)
const showDifficulty = ref(true)
const ratingFilter = ref<'ALL' | '1'>('ALL')

const reviewsAsc = computed(() => [...reviews.value].sort((a, b) => +new Date(a.reviewedAt) - +new Date(b.reviewedAt)))
const filteredReviews = computed(() =>
  ratingFilter.value === 'ALL' ? reviewsAsc.value : reviewsAsc.value.filter((r) => r.rating === 1),
)

const ratingLegend = ([1, 2, 3, 4] as Rating[]).map((rating) => ({
  rating,
  label: RATING_META[rating].label,
  color: RATING_META[rating].color,
}))

/**
 * `stability` / `difficulty` are null until the first review, so never coerce
 * them to 0 - a brand-new card would then read "0 天" and look broken.
 */
const stabilityText = computed(() => card.value?.stability?.toFixed(2) ?? '—')
const difficultyText = computed(() => card.value?.difficulty?.toFixed(2) ?? '—')
const stabilityHint = computed(() => {
  const s = card.value?.stability
  if (s === null || s === undefined) return '还没复习过，所以还没有 S：第一次评分后算法才会算出它。'
  return `现在这张卡的 S = ${s.toFixed(2)} 天，意思是大致 ${Math.round(s)} 天后你还有九成把握记得。`
})
const timelineHintLines = computed(() => [
  '横轴是第几次复习（按时间排序），纵轴是「记得有多牢」——S 越大越牢（纵轴是对数刻度，所以每一格的距离代表翻倍）。',
  '蓝线往上走 = 这张卡越记越牢；某个点突然掉下来，通常是那次忘了（Again），可以看到点的颜色和它对应。',
  showDifficulty.value ? '紫色虚线是难度 D（右侧 1~10 刻度），越大越难；它一般变化很小，只是随评分缓慢浮动。' : '勾选「显示难度 D」可以把这张卡对你有多难也画在同一张图上。',
])
const lapseRate = computed(() => {
  if (!card.value || !card.value.reps) return '0.0'
  return ((card.value.lapses / card.value.reps) * 100).toFixed(1)
})
const avgInterval = computed(() => {
  if (!reviews.value.length) return '0.0'
  return (reviews.value.reduce((s, r) => s + r.intervalDays, 0) / reviews.value.length).toFixed(1)
})
const avgRetrievability = computed(() => {
  if (!reviews.value.length) return '0.0'
  return ((reviews.value.reduce((s, r) => s + r.retrievability, 0) / reviews.value.length) * 100).toFixed(1)
})

/**
 * `state` arrives as the numeric code from the live API and as a name from the offline
 * mock, so normalise both before mapping to a label or a colour. Without this the chip
 * shows a bare "2" and gets no styling at all.
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

function ratingChipStyle(rating: Rating): Record<string, string> {
  const color = RATING_META[rating].color
  return { color, borderColor: `${color}55`, background: `${color}1f` }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await cardApi.timeline(cardId)
    card.value = data.card
    reviews.value = data.reviews ?? []
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载卡片详情失败'))
    card.value = null
  } finally {
    loading.value = false
  }
}

async function suspend(): Promise<void> {
  try {
    await ElMessageBox.confirm('暂停后该卡片不会再出现在复习队列中。', '暂停卡片', {
      confirmButtonText: '暂停',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await cardApi.suspend(cardId)
    ElMessage.success('已暂停这张卡片')
    await router.push('/decks')
  } catch (e) {
    ElMessage.error(errorMessage(e, '暂停失败'))
  }
}

onMounted(load)
</script>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.placeholder {
  padding: 40px 24px;
  text-align: center;
  color: var(--fs-text-2);
}

.placeholder--error {
  color: #ffc3c9;
}

.placeholder__actions {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}

.qa__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.25fr);
}

.qa__block {
  padding: 18px 20px;
}

.qa__block--back {
  border-left: 1px solid var(--fs-line-soft);
  background: rgba(61, 220, 151, 0.04);
}

.qa__label {
  font-size: 11.5px;
  letter-spacing: 0.6px;
  color: var(--fs-text-2);
  margin-bottom: 8px;
  text-transform: uppercase;
}

.qa__text {
  margin: 0;
  font-size: 18px;
  line-height: 1.65;
  font-weight: 550;
}

.qa__text--back {
  font-size: 14.5px;
  font-weight: 400;
  color: #d5e6f5;
  line-height: 1.8;
}

.head-tools {
  display: flex;
  align-items: center;
  gap: 14px;
}

.rating-legend {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-top: 12px;
  font-size: 12px;
  color: var(--fs-text-2);
}

.rating-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--fs-text-1);
}

.rating-legend__dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}

.rating-legend__note {
  margin-left: auto;
}

.rating-legend__note b {
  color: var(--fs-text-0);
}

.log-table {
  width: 100%;
}

.cell-dim {
  color: var(--fs-text-2);
}

.arrow {
  margin: 0 5px;
  color: var(--fs-text-2);
}

.arrow--up {
  color: var(--fs-good);
}

.arrow--down {
  color: var(--fs-again);
}

@media (max-width: 980px) {
  .qa__grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .qa__block--back {
    border-left: none;
    border-top: 1px solid var(--fs-line-soft);
  }
}
</style>
