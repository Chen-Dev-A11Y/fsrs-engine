<template>
  <div class="fs-page">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          统计可视化
          <span class="fs-chip fs-chip--muted">ECharts</span>
        </h1>
        <p class="fs-page__sub">
          热力图看习惯，遗忘曲线看模型，预测柱状图看未来的复习负荷 —— 全部数据来自统计接口。
        </p>
      </div>
      <el-button :loading="loading.summary" @click="reloadAll">刷新数据</el-button>
    </header>

    <section class="fs-stat-row">
      <MetricCard
        label="总卡片数"
        :value="summary?.totalCards ?? 0"
        unit="张"
        tone="accent"
        :tag="`+${summary?.newToday ?? 0} 今日新增`"
        :bar-percent="68"
      />
      <MetricCard
        label="总复习次数"
        :value="totalReviewsDisplay"
        unit="次"
        tone="violet"
        :hint="`累计 ${heatmap.length} 天记录`"
        :bar-percent="82"
      />
      <MetricCard
        label="7 日保持率"
        :value="retention7"
        unit="%"
        :tone="(summary?.retention7d ?? 0) >= 0.85 ? 'good' : 'hard'"
        :hint="`30 日 ${retention30}%`"
        :bar-percent="Number(retention7)"
      />
      <MetricCard
        label="连续复习天数"
        :value="summary?.streakDays ?? 0"
        unit="天"
        tone="good"
        :hint="`今日到期 ${summary?.dueToday ?? 0} 张`"
        :bar-percent="Math.min(100, (summary?.streakDays ?? 0) * 3.4)"
      />
    </section>

    <!--
      Archiving is a real action on the decks page, so it has to be visible here too: these cards are
      neither deleted nor part of the workload, and a summary that stayed silent about them would make
      "I archived 30 cards" look like nothing happened.
    -->
    <p v-if="archivedNote" class="archived-note">
      <span class="fs-chip fs-chip--muted">已归档</span>
      <span>{{ archivedNote }}</span>
      <span class="archived-note__hint">归档的卡组不进入复习队列，在「卡组与卡片」里取消归档即可恢复。</span>
    </p>

    <section class="fs-card">
      <div class="fs-card__head">
        <span class="fs-card__title">
          复习热力图
          <span class="fs-card__hint">近一年 · 按本地自然日聚合 · 颜色深浅 = 当天复习量</span>
        </span>
        <span class="legend-inline">
          <span class="legend-inline__dot" style="background: #12384a" />
          <span class="legend-inline__dot" style="background: #175d78" />
          <span class="legend-inline__dot" style="background: #1f92b8" />
          <span class="legend-inline__dot" style="background: #2fb6dd" />
          <span class="legend-inline__dot" style="background: #7fe0ff" />
          <span class="legend-inline__text">少 → 多</span>
        </span>
      </div>
      <div class="fs-card__body heatmap-body">
        <div v-if="loading.heatmap" class="chart-skeleton" style="height: 196px" />
        <HeatmapChart v-else :data="heatmap" :height="196" />
      </div>
    </section>

    <div class="grid-2">
      <section class="fs-card">
        <div class="fs-card__head">
          <span class="fs-card__title">
            遗忘曲线
            <span class="fs-card__hint">R(t, S) = (1 + factor·t/S)^(-w20)</span>
          </span>
          <div class="head-tools">
            <el-radio-group v-model="axisMode" size="small">
              <el-radio-button value="log">对数轴</el-radio-button>
              <el-radio-button value="linear">线性轴</el-radio-button>
            </el-radio-group>
            <el-tag v-if="curveSource === 'local'" size="small" type="warning" effect="dark">本地公式计算</el-tag>
            <el-tag v-else size="small" type="success" effect="dark">接口 /scheduler/curve</el-tag>
          </div>
        </div>
        <div class="fs-card__body">
          <div class="controls">
            <div class="control">
              <div class="control__head">
                <span>稳定性 S</span>
                <span class="fs-mono control__val">{{ stability.toFixed(1) }} 天</span>
              </div>
              <el-slider v-model="stability" :min="1" :max="200" :step="0.5" :marks="stabilityMarks" />
              <div class="control__hint">S 是「R 掉到 90% 所需的天数」，拖动即可比较不同记忆强度下的衰减速度。</div>
            </div>
            <div class="control">
              <div class="control__head">
                <span>目标保持率</span>
                <span class="fs-mono control__val">{{ (targetRetention * 100).toFixed(0) }}%</span>
              </div>
              <el-slider v-model="targetRetention" :min="0.7" :max="0.98" :step="0.01" :marks="retentionMarks" />
              <div class="control__hint">
                曲线上的标记点即该目标对应的复习时点：<b class="fs-mono">{{ crossingDay.toFixed(1) }} 天</b>。
              </div>
            </div>
          </div>

          <div class="curve-readout">
            <span>
              曲线数据点 <b class="fs-mono">{{ curve.length }}</b>
            </span>
            <span>
              R=90% 出现在 <b class="fs-mono">{{ ninetyDay.toFixed(1) }} 天</b>
            </span>
            <span>
              目标 <b class="fs-mono">{{ (targetRetention * 100).toFixed(0) }}%</b> → 间隔
              <b class="fs-mono">{{ crossingDay.toFixed(1) }} 天</b>
            </span>
            <span>
              第 {{ maxDay }} 天 R = <b class="fs-mono">{{ lastR }}%</b>
            </span>
          </div>

          <div v-if="loading.curve && !curve.length" class="chart-skeleton" style="height: 320px" />
          <ForgettingCurveChart
            v-else
            :points="curve"
            :stability="stability"
            :target-retention="targetRetention"
            :crossing-day="crossingDay"
            :log-axis="axisMode === 'log'"
            :height="320"
          />
        </div>
      </section>

      <div class="stack">
        <section class="fs-card">
          <div class="fs-card__head">
            <span class="fs-card__title">
              未来 30 天到期预测
              <span class="fs-card__hint">柱状图 · 调度负荷</span>
            </span>
          </div>
          <div class="fs-card__body">
            <div v-if="loading.summary && !forecast.length" class="chart-skeleton" style="height: 240px" />
            <ForecastChart v-else :forecast="forecast" :height="240" />
          </div>
        </section>

        <section class="fs-card">
          <div class="fs-card__head">
            <span class="fs-card__title">
              评分分布
              <span class="fs-card__hint">Again / Hard / Good / Easy</span>
            </span>
            <el-radio-group v-model="ratingWindow" size="small">
              <el-radio-button :value="7">7 天</el-radio-button>
              <el-radio-button :value="30">30 天</el-radio-button>
              <el-radio-button :value="365">一年</el-radio-button>
            </el-radio-group>
          </div>
          <div class="fs-card__body">
            <div v-if="loading.heatmap && !heatmap.length" class="chart-skeleton" style="height: 258px" />
            <RatingDistributionChart v-else :data="heatmap" :days="ratingWindow" :height="258" />
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import MetricCard from '@/components/MetricCard.vue'
import ForgettingCurveChart from '@/components/charts/ForgettingCurveChart.vue'
import ForecastChart from '@/components/charts/ForecastChart.vue'
import HeatmapChart from '@/components/charts/HeatmapChart.vue'
import RatingDistributionChart from '@/components/charts/RatingDistributionChart.vue'
import { errorMessage, schedulerApi, statsApi } from '@/api'
import type { CurvePoint, ForecastPoint, HeatmapDay, StatsSummary } from '@/types/api'
import { DEFAULT_WEIGHTS, sampleCurve } from '@/utils/fsrs'

const heatmap = ref<HeatmapDay[]>([])
const summary = ref<StatsSummary | null>(null)
const curve = ref<CurvePoint[]>([])
const curveSource = ref<'api' | 'local'>('api')

const stability = ref(10)
const targetRetention = ref(0.9)
const axisMode = ref<'log' | 'linear'>('log')
const ratingWindow = ref(30)
const maxDay = 365

const loading = reactive({ heatmap: false, summary: false, curve: false })

const stabilityMarks = { 1: '1', 50: '50', 100: '100', 150: '150', 200: '200' }
const retentionMarks = { 0.7: '70%', 0.8: '80%', 0.9: '90%', 0.98: '98%' }

const totalReviewsDisplay = computed(() => (summary.value?.totalReviews ?? 0).toLocaleString('en-US'))
const retention7 = computed(() => ((summary.value?.retention7d ?? 0) * 100).toFixed(1))
const retention30 = computed(() => ((summary.value?.retention30d ?? 0) * 100).toFixed(1))
const forecast = computed<ForecastPoint[]>(() => summary.value?.forecast ?? [])

/**
 * The archive line under the metric row: empty while nothing is archived, which keeps the page
 * unchanged for anyone who never uses the feature.
 */
const archivedNote = computed(() => {
  const decks = summary.value?.archivedDecks ?? 0
  const cards = summary.value?.archivedCards ?? 0
  if (!decks && !cards) return ''
  if (!decks) return `${cards} 张卡属于已归档的卡组`
  return `已归档 ${decks} 个卡组 · ${cards} 张卡`
})

/** Day at which the curve crosses the target retention (linear interpolation). */
const crossingDay = computed(() => {
  const target = targetRetention.value
  const pts = curve.value
  if (!pts.length) return 0
  for (let i = 1; i < pts.length; i += 1) {
    const a = pts[i - 1]!
    const b = pts[i]!
    if (a.retrievability >= target && b.retrievability <= target) {
      const span = a.retrievability - b.retrievability
      const ratio = span === 0 ? 0 : (a.retrievability - target) / span
      return a.day + (b.day - a.day) * ratio
    }
  }
  return pts.at(-1)?.day ?? 0
})

/** Wherever R = 90% lands - the definition of stability. */
const ninetyDay = computed(() => {
  const pts = curve.value
  if (!pts.length) return 0
  for (let i = 1; i < pts.length; i += 1) {
    const a = pts[i - 1]!
    const b = pts[i]!
    if (a.retrievability >= 0.9 && b.retrievability <= 0.9) {
      const span = a.retrievability - b.retrievability
      const ratio = span === 0 ? 0 : (a.retrievability - 0.9) / span
      return a.day + (b.day - a.day) * ratio
    }
  }
  return pts.at(-1)?.day ?? 0
})

const lastR = computed(() => ((curve.value.at(-1)?.retrievability ?? 0) * 100).toFixed(1))

async function loadHeatmap(): Promise<void> {
  loading.heatmap = true
  try {
    heatmap.value = await statsApi.heatmap(365)
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载热力图失败'))
  } finally {
    loading.heatmap = false
  }
}

async function loadSummary(): Promise<void> {
  loading.summary = true
  try {
    summary.value = await statsApi.summary()
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载统计汇总失败'))
  } finally {
    loading.summary = false
  }
}

async function loadCurve(): Promise<void> {
  loading.curve = true
  const localStep = 2
  try {
    const params = await schedulerApi.params()
    const res = await schedulerApi.curve({
      stability: stability.value,
      difficulty: 5,
      maxDay,
      weights: params.weights,
      desiredRetention: targetRetention.value,
    })
    if (res.points?.length) {
      curve.value = res.points
      curveSource.value = 'api'
      return
    }
    throw new Error('empty curve')
  } catch {
    // Offline (or an empty payload): compute the same curve with the FSRS-6 formula.
    curve.value = sampleCurve(stability.value, maxDay, DEFAULT_WEIGHTS, localStep)
    curveSource.value = 'local'
  } finally {
    loading.curve = false
  }
}

function reloadAll(): void {
  void loadHeatmap()
  void loadSummary()
  void loadCurve()
}

let curveTimer: number | undefined
watch([stability, targetRetention], () => {
  if (curveTimer) window.clearTimeout(curveTimer)
  curveTimer = window.setTimeout(() => void loadCurve(), 180)
})

onMounted(() => {
  reloadAll()
  // Keep the readout honest even in the pure-offline case.
  if (!curve.value.length) curve.value = sampleCurve(stability.value, maxDay, DEFAULT_WEIGHTS, 2)
})
</script>

<style scoped>
/* The archive line sits between the metric row and the charts, so it reads as a footnote to the
   numbers above it rather than as another metric. */
.archived-note {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: -6px 0 0;
  font-size: 12.5px;
  color: var(--fs-text-1);
}

.archived-note__hint {
  color: var(--fs-text-2);
  font-size: 11.5px;
}

.legend-inline {
  display: flex;
  align-items: center;
  gap: 5px;
}

.legend-inline__dot {
  width: 11px;
  height: 11px;
  border-radius: 3px;
  display: inline-block;
}

.legend-inline__text {
  font-size: 11.5px;
  color: var(--fs-text-2);
  margin-left: 3px;
}

.heatmap-body {
  padding: 6px 14px 14px;
}

.grid-2 {
  display: grid;
  grid-template-columns: minmax(0, 1.28fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}

.controls {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 22px;
  padding: 4px 6px 8px;
}

.control__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12.5px;
  color: var(--fs-text-1);
  margin-bottom: 2px;
}

.control__val {
  color: var(--fs-accent);
  font-weight: 600;
}

.control__hint {
  margin-top: 22px;
  font-size: 11.5px;
  color: var(--fs-text-2);
  line-height: 1.6;
}

.control__hint b {
  color: var(--fs-hard);
}

.curve-readout {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  margin: 10px 0 4px;
  padding: 10px 12px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.5);
  font-size: 12px;
  color: var(--fs-text-2);
}

.curve-readout b {
  color: var(--fs-text-0);
}

.chart-skeleton {
  border-radius: var(--fs-radius-sm);
  background: linear-gradient(90deg, rgba(139, 155, 180, 0.08), rgba(139, 155, 180, 0.16), rgba(139, 155, 180, 0.08));
  background-size: 200% 100%;
  animation: shimmer 1.3s linear infinite;
}

@keyframes shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

@media (max-width: 1240px) {
  .grid-2 {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
