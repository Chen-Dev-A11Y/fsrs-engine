<template>
  <div class="fs-page">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          调度参数
          <span v-if="dirty" class="fs-chip fs-chip--learning">有未保存的改动</span>
        </h1>
        <p class="fs-page__sub">
          这张表决定你什么时候会再见到同一张卡。参数越「记牢优先」，复习越勤；越「省时间」，间隔越长。
          <b class="muted">不熟悉的话，只调下面的目标保持率就够了。</b>
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="$router.push('/compare')">参数对比</el-button>
      </div>
    </header>

    <!-- ---------------------------------------------------------------
         1. 主控制：目标保持率（白话 + 一个滑块）
         --------------------------------------------------------------- -->
    <section class="fs-card">
      <div class="fs-card__head">
        <span class="fs-card__title">
          唯一的日常控制：目标保持率
          <span class="fs-card__hint">越高 → 越常复习、记得越牢；越低 → 间隔越长、复习越省</span>
        </span>
        <span class="fs-chip fs-chip--muted">{{ retentionBadge }}</span>
      </div>
      <div class="fs-card__body">
        <div class="presets">
          <span class="presets__label">一键套用：</span>
          <el-button
            v-for="preset in presets"
            :key="preset.key"
            size="small"
            :type="activePreset === preset.key ? 'primary' : 'default'"
            :plain="activePreset !== preset.key"
            :title="preset.hint"
            @click="applyPreset(preset.key)"
          >
            {{ preset.label }}
          </el-button>
          <span class="presets__note">
            处方只调「目标保持率」，21 个权重保持官方默认（想改权重见页面底部的高级区）。
            {{ presetDelta }}
          </span>
        </div>

        <div class="control">
          <div class="control__head">
            <span>目标保持率</span>
            <span class="fs-mono control__val">{{ retentionPercent }}%</span>
          </div>
          <el-slider v-model="params.desiredRetention" :min="0.7" :max="0.99" :step="0.01" :marks="retentionMarks" />
          <div class="control__hint">
            {{ retentionExplain }}
          </div>
        </div>

        <div class="control__hint control__hint--minor">
          例：按当前参数，一张「已经熟到 S=10 天」的卡，这次点 <b class="good">Good</b> 后会在
          <b class="good fs-mono">{{ goodIntervalText }}</b> 再出现。
        </div>
      </div>
    </section>

    <!-- ---------------------------------------------------------------
         2+3. 间隔预览表（白话列名）与遗忘曲线
         --------------------------------------------------------------- -->
    <div class="layout">
      <section class="fs-card">
        <div class="fs-card__head">
          <span class="fs-card__title">
            复习间隔预览
            <span class="fs-card__hint">当前参数下，这张卡下次会在多少天后再出现</span>
          </span>
        </div>
        <div class="fs-card__body">
          <table class="preview">
            <thead>
              <tr>
                <th class="preview__th-s">这张卡现在有多熟</th>
                <th v-for="meta in ratingMeta" :key="meta.rating" :style="{ color: meta.color }">
                  {{ meta.label }}
                  <span class="preview__zh">{{ meta.zh }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in previewRows" :key="row.stability">
                <td class="preview__s">
                  <span class="fs-mono preview__s-val">S = {{ row.stability }} 天</span>
                  <span class="preview__s-note">{{ familiarity(row.stability) }}</span>
                </td>
                <td v-for="cell in row.cells" :key="cell.key">
                  <span class="preview__value">{{ cell.text }}</span>
                  <span class="preview__delta" :class="cell.deltaClass">{{ cell.deltaText }}</span>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="read-guide">
            <div class="read-guide__title">怎么读这张表</div>
            <p>
              横向找你这张卡现在有多熟，纵向找你这回点哪个分，交叉格就是<strong>下次什么时候再见到它</strong>。
              例如：这张卡现在的 S = 10 天，这次点了 <strong>Good</strong>，那么它下次会在
              <strong class="fs-mono">{{ exampleInterval }}</strong> 后再出现。
            </p>
            <p class="read-guide__sub">
              每格下面那行灰字是<strong>和 FSRS-6 官方默认参数的差别</strong>：
              <span class="legend legend--up">绿色 = 比默认间隔更长（复习更少）</span>，
              <span class="legend legend--down">红色 = 更短（复习更勤）</span>，±0 = 和默认一样。
            </p>
            <p class="read-guide__sub">
              预览固定按「难度中等（D=5）、距上次复习 {{ elapsedDays }} 天」估算；同一张卡的实际间隔还会受它自己的 D 和当前记得概率影响。
            </p>
          </div>
        </div>
      </section>

      <section class="fs-card">
        <div class="fs-card__head">
          <span class="fs-card__title">
            记忆能保持多久
            <span class="fs-card__hint">曲线的形状由下面的参数决定</span>
          </span>
          <el-tag size="small" :type="curveSource === 'api' ? 'success' : 'warning'" effect="dark">
            {{ curveSource === 'api' ? '接口数据' : '本地公式' }}
          </el-tag>
        </div>
        <div class="fs-card__body">
          <div class="curve-tools">
            <span class="curve-tools__label">假设这张卡现在的 S =</span>
            <el-radio-group v-model="curveStability" size="small">
              <el-radio-button :value="1">1 天</el-radio-button>
              <el-radio-button :value="10">10 天</el-radio-button>
              <el-radio-button :value="100">100 天</el-radio-button>
            </el-radio-group>
          </div>

          <ForgettingCurveChart
            :points="curve"
            :stability="curveStability"
            :target-retention="params.desiredRetention"
            :crossing-day="crossingDay"
            :height="268"
          />

          <ChartHint :lines="curveHintLines" />
        </div>
      </section>
    </div>

    <!-- ---------------------------------------------------------------
         4. 高级：21 个权重（默认收起）
         --------------------------------------------------------------- -->
    <section class="fs-card advanced">
      <el-collapse v-model="advancedOpen" class="advanced__collapse">
        <el-collapse-item name="weights">
          <template #title>
            <span class="advanced__title">
              高级：21 个模型权重
              <span class="advanced__warn">改这里会影响所有卡片，不了解请勿调整</span>
            </span>
          </template>

          <div class="advanced__bar">
            <span class="advanced__bar-note">
              当前 source：<span class="fs-mono">{{ params.source || '—' }}</span>
              ·
              {{ backend.isOffline ? '演示数据（保存后本地生效）' : '已连后端（保存会写入后端）' }}
              · 数值会被限制在合法区间内
            </span>
            <div class="advanced__actions">
              <el-button size="small" :disabled="!dirty" @click="restoreDefaults">恢复默认</el-button>
              <el-button size="small" :loading="loading.save" :disabled="!dirty" type="primary" @click="save">保存</el-button>
            </div>
          </div>

          <p class="advanced__intro">
            这 21 个数字是算法内部的可调旋钮（w0 – w20），分成几组各管一件事：新卡第一次记得多牢、难度怎么更新、
            复习一次后牢度涨多少、忘了以后怎么算、以及遗忘曲线的形状。下面每格的灰色小字是官方默认值，
            左边的进度条变橙就表示你改过它。改动会立刻重算上面的预览表和曲线。
          </p>

          <div class="weights__body">
            <div v-for="group in groups" :key="group.name" class="wgroup">
              <div class="wgroup__title">
                {{ group.name }}
                <span class="wgroup__hint">{{ group.hint }}</span>
              </div>
              <div class="wgroup__grid">
                <label v-for="item in group.items" :key="item.index" class="wfield">
                  <span class="wfield__name fs-mono" :title="item.hint">{{ item.name }}</span>
                  <el-input-number
                    v-model="params.weights[item.index]"
                    :min="item.min"
                    :max="item.max"
                    :step="item.step"
                    :precision="4"
                    size="small"
                    controls-position="right"
                  />
                  <span class="wfield__bar">
                    <span
                      class="wfield__bar-in"
                      :class="{ 'wfield__bar-in--diff': differsFromDefault(item.index) }"
                      :style="{ width: `${barWidth(params.weights[item.index] ?? 0, item.max)}%` }"
                    />
                  </span>
                  <span class="wfield__default fs-mono" :title="`官方默认值 ${DEFAULT_WEIGHTS[item.index]}`">
                    {{ DEFAULT_WEIGHTS[item.index] }}
                  </span>
                </label>
              </div>
            </div>
          </div>

          <div class="advanced__foot">
            <span class="advanced__foot-note">
              不确定就先别动：上面的「默认（推荐）」会把权重和保持率一起恢复成官方默认值。
            </span>
            <div class="advanced__actions">
              <el-button size="small" @click="load" :loading="loading.load">重新读取</el-button>
              <el-button size="small" :disabled="!dirty" @click="restoreDefaults">恢复默认</el-button>
              <el-button size="small" type="primary" :loading="loading.save" :disabled="!dirty" @click="save">保存</el-button>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ChartHint from '@/components/ChartHint.vue'
import ForgettingCurveChart from '@/components/charts/ForgettingCurveChart.vue'
import { errorMessage, schedulerApi } from '@/api'
import { useBackendStore } from '@/stores/backend'
import { useIntervalPreview } from '@/hooks/useIntervalPreview'
import {
  DEFAULT_MAX_INTERVAL,
  DEFAULT_RETENTION,
  DEFAULT_WEIGHTS,
  WEIGHT_META,
  formatDays,
  sampleCurve,
} from '@/utils/fsrs'
import { saveParams } from '@/utils/paramsStore'
import { CURRENT_PARAMS_KEY, isWeightArray, parseWeights } from '@/mock/optimized'
import { RATING_META, type CurvePoint, type Rating } from '@/types/api'

const backend = useBackendStore()
const route = useRoute()

const params = reactive({
  weights: [...DEFAULT_WEIGHTS],
  desiredRetention: DEFAULT_RETENTION,
  maximumIntervalDays: DEFAULT_MAX_INTERVAL,
  source: '',
})

const savedSnapshot = ref('')
const curve = ref<CurvePoint[]>([])
const curveStability = ref(10)
const curveSource = ref<'api' | 'local'>('api')
const elapsedDays = ref(1)
const loading = reactive({ load: false, save: false })
const advancedOpen = ref<string[]>([])

const maxDay = 365
const retentionMarks = { 0.7: '70%', 0.8: '80%', 0.9: '90%（默认）', 0.95: '95%', 0.99: '99%' }

type PresetKey = 'default' | 'retain' | 'save'

/**
 * The three "处方". They deliberately differ only in `desiredRetention`:
 * inventing a second set of weight arrays would mean shipping numbers nobody
 * has fitted, so a preset is the retention knob plus the official weights.
 */
const PRESETS: Record<PresetKey, { label: string; retention: number; hint: string }> = {
  default: {
    label: '默认（推荐）',
    retention: DEFAULT_RETENTION,
    hint: '90%：Anki 的默认值，官方 21 个权重，适合大多数人',
  },
  retain: {
    label: '记牢优先（复习更勤）',
    retention: 0.95,
    hint: '95%：每次复习要求九成半把握，间隔更短、复习次数更多',
  },
  save: {
    label: '省时间（间隔更长）',
    retention: 0.85,
    hint: '85%：允许记得稍模糊一点，间隔拉长、复习次数更少',
  },
}

const presets = (Object.keys(PRESETS) as PresetKey[]).map((key) => ({ key, ...PRESETS[key] }))

// Per-weight bounds: comfortably wide, but they stop NaN/absurd input from breaking the preview.
function boundsFor(index: number): { min: number; max: number; step: number } {
  if (index === 20) return { min: 0.01, max: 1, step: 0.001 }
  if (index === 7) return { min: 0, max: 1, step: 0.001 }
  if (index === 4 || index === 11) return { min: 0, max: 20, step: 0.01 }
  if (index === 5 || index === 6 || index === 8 || index === 10 || index === 14) return { min: 0, max: 10, step: 0.01 }
  if (index >= 15 && index <= 19) return { min: 0, max: 10, step: 0.001 }
  return { min: 0, max: 20, step: 0.0001 }
}

const groups = computed(() => {
  const order: string[] = []
  WEIGHT_META.forEach((m) => {
    if (!order.includes(m.group)) order.push(m.group)
  })
  return order.map((name) => ({
    name,
    hint: WEIGHT_META.find((m) => m.group === name)?.hint ?? '',
    items: WEIGHT_META.filter((m) => m.group === name).map((m) => ({ ...m, ...boundsFor(m.index) })),
  }))
})

const ratingMeta = ([1, 2, 3, 4] as Rating[]).map((rating) => ({
  rating,
  key: ({ 1: 'again', 2: 'hard', 3: 'good', 4: 'easy' } as const)[rating],
  label: RATING_META[rating].label,
  zh: RATING_META[rating].zh,
  color: RATING_META[rating].color,
}))

const preview = useIntervalPreview(
  () => params.weights,
  () => params.desiredRetention,
  () => elapsedDays.value,
)
const dirty = computed(() => snapshot() !== savedSnapshot.value)

const retentionPercent = computed(() => (params.desiredRetention * 100).toFixed(0))

const retentionBadge = computed(() => {
  const diff = Math.round((params.desiredRetention - DEFAULT_RETENTION) * 100)
  if (diff === 0) return '和官方默认一致（90%）'
  return diff > 0 ? `比默认高 ${diff} 个百分点` : `比默认低 ${Math.abs(diff)} 个百分点`
})

const retentionExplain = computed(() => {
  const pct = Number(retentionPercent.value)
  const posture =
    pct >= 93
      ? '复习会明显变勤：更不容易忘，但要花更多时间。'
      : pct >= 88
        ? '复习量和记忆效果的平衡点，适合大多数人。'
        : '复习次数会更少：省时间，但偶尔会忘得更多。'
  return `${pct}% = 每次复习时，你大约有 ${pct}% 的把握还记得它（官方默认 90%，Anki 也是这个值）。${posture}`
})

/** Interval for an imagined card whose stability equals the current curve preview S. */
const goodIntervalText = computed(() => {
  const row = preview.rows.value.find((r) => r.stability === curveStability.value) ?? preview.rows.value[0]
  return formatDays(row?.good ?? 0)
})

/** The S = 10 row is the worked example in「怎么读这张表」. */
const exampleInterval = computed(() => {
  const row = preview.rows.value.find((r) => r.stability === 10) ?? preview.rows.value[0]
  return formatDays(row?.good ?? 0)
})

function familiarity(stability: number): string {
  if (stability <= 2) return '刚学的卡'
  if (stability <= 30) return '有点印象'
  return '已经很熟'
}

const previewRows = computed(() =>
  preview.rows.value.map((row) => ({
    stability: row.stability,
    cells: ratingMeta.map((meta) => {
      const value = row[meta.key]
      const delta = preview.delta(row.stability, meta.rating)
      return {
        key: meta.key,
        text: formatDays(value),
        deltaText: delta === 0 ? '和默认一样' : `比官方默认${delta > 0 ? '长' : '短'} ${formatDays(Math.abs(delta))}`,
        deltaClass: delta > 0 ? 'preview__delta--up' : delta < 0 ? 'preview__delta--down' : '',
      }
    }),
  })),
)

/** Which prescription the current numbers correspond to, if any. */
const activePreset = computed<PresetKey | null>(() => {
  const sameWeights = params.weights.every((w, i) => Math.abs(w - (DEFAULT_WEIGHTS[i] ?? 0)) < 1e-9)
  if (!sameWeights) return null
  const match = (Object.keys(PRESETS) as PresetKey[]).find(
    (key) => Math.abs(params.desiredRetention - PRESETS[key].retention) < 1e-9,
  )
  return match ?? null
})

/**
 * Plain-language readout of where the current numbers sit relative to the
 * shipped defaults - the "和默认差多少" the reader actually cares about, in
 * words rather than a bare ± number.
 */
const presetDelta = computed(() => {
  const preset = activePreset.value
  if (preset === 'default') return '现在是官方默认组合，没有改动。'
  if (preset) {
    const pct = (PRESETS[preset].retention * 100).toFixed(0)
    const direction = PRESETS[preset].retention > DEFAULT_RETENTION ? '高' : '低'
    return `当前是「${PRESETS[preset].label}」：目标保持率 ${pct}%，比官方默认的 90% ${direction} ${Math.abs(PRESETS[preset].retention - DEFAULT_RETENTION) * 100} 个百分点。`
  }
  const weightChanges = params.weights.filter((w, i) => Math.abs(w - (DEFAULT_WEIGHTS[i] ?? 0)) > 1e-9).length
  if (weightChanges) {
    return `当前有 ${weightChanges} 个权重和官方默认不同，目标保持率 ${retentionPercent.value}% —— 保存前建议先确认。`
  }
  return `当前目标保持率 ${retentionPercent.value}%，权重仍是官方默认。`
})

const crossingDay = computed(() => {
  const target = params.desiredRetention
  for (let i = 1; i < curve.value.length; i += 1) {
    const a = curve.value[i - 1]!
    const b = curve.value[i]!
    if (a.retrievability >= target && b.retrievability <= target) {
      const span = a.retrievability - b.retrievability
      const ratio = span === 0 ? 0 : (a.retrievability - target) / span
      return a.day + (b.day - a.day) * ratio
    }
  }
  return curve.value.at(-1)?.day ?? 0
})

const curveHintLines = computed(() => [
  '横轴 = 距上次复习过了多少天（对数刻度，越靠右隔得越久）；纵轴 = 那时你还记得它的概率，100% 是刚复习完，往下走就是慢慢忘掉。',
  `橙黄虚线 = 你设的目标保持率 ${retentionPercent.value}%：曲线穿过它的那一天（约 ${formatDays(crossingDay.value)}）就是算法认为「该复习了」的时间点，也就是按钮上那个间隔。`,
  '把参数调得更「记牢优先」，这条曲线会整体更平、穿过目标线的位置更靠左，于是复习更勤；反过来则更靠右、复习更少。',
])

function snapshot(): string {
  return `${params.weights.map((w) => Number(w).toFixed(6)).join(',')}|${Number(params.desiredRetention).toFixed(4)}`
}

function differsFromDefault(index: number): boolean {
  return Math.abs((params.weights[index] ?? 0) - (DEFAULT_WEIGHTS[index] ?? 0)) > 1e-9
}

function barWidth(value: number, max: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(100, (value / max) * 100))
}

function applyPreset(key: PresetKey): void {
  const preset = PRESETS[key]
  params.weights = [...DEFAULT_WEIGHTS]
  params.desiredRetention = preset.retention
  ElMessage.info(`已套用「${preset.label}」：权重为官方默认，目标保持率 ${(preset.retention * 100).toFixed(0)}%`)
}

async function load(): Promise<void> {
  loading.load = true
  try {
    const data = await schedulerApi.params()
    const weights = Array.isArray(data.weights) && data.weights.length === 21 ? data.weights : DEFAULT_WEIGHTS
    params.weights = weights.map((w, i) => (Number.isFinite(w) ? w : (DEFAULT_WEIGHTS[i] as number)))
    params.desiredRetention = Number.isFinite(data.desiredRetention) ? data.desiredRetention : DEFAULT_RETENTION
    params.maximumIntervalDays = data.maximumIntervalDays ?? DEFAULT_MAX_INTERVAL
    params.source = data.source ?? ''
    savedSnapshot.value = snapshot()
  } catch (e) {
    params.weights = [...DEFAULT_WEIGHTS]
    params.desiredRetention = DEFAULT_RETENTION
    savedSnapshot.value = snapshot()
    ElMessage.warning(errorMessage(e, '读取参数失败，已回退到 FSRS-6 默认值'))
  } finally {
    loading.load = false
    applyRouteQuery()
    void refreshCurve()
  }
}

/** `/scheduler?weights=w0,w1,…,w20&retention=0.92` — used by the comparison page. */
function applyRouteQuery(): void {
  const rawWeights = route.query.weights
  if (typeof rawWeights !== 'string' || !rawWeights) return
  const parsed = parseWeights(rawWeights)
  if (!isWeightArray(parsed)) return
  params.weights = [...parsed]
  const rawRetention = route.query.retention
  if (typeof rawRetention === 'string' && Number.isFinite(Number(rawRetention))) {
    params.desiredRetention = Math.min(0.99, Math.max(0.7, Number(rawRetention)))
  }
  advancedOpen.value = ['weights']
  ElMessage.info('已从参数对比页载入一组参数，确认后可保存')
}

async function refreshCurve(): Promise<void> {
  const weights = params.weights.map((w) => (Number.isFinite(w) ? w : 0))
  try {
    const res = await schedulerApi.curve({
      stability: curveStability.value,
      difficulty: 5,
      maxDay,
      weights,
      desiredRetention: params.desiredRetention,
    })
    if (res.points?.length) {
      curve.value = res.points
      curveSource.value = 'api'
      return
    }
    throw new Error('empty')
  } catch {
    curve.value = sampleCurve(curveStability.value, maxDay, weights, 2)
    curveSource.value = 'local'
  }
}

let curveTimer: number | undefined
watch(
  () => [snapshot(), curveStability.value] as const,
  () => {
    // Mirror the working copy so /compare can diff "当前编辑中的参数" even before saving.
    try {
      localStorage.setItem(
        CURRENT_PARAMS_KEY,
        JSON.stringify({
          weights: params.weights.map((w) => Number(w)),
          desiredRetention: Number(params.desiredRetention),
          updatedAt: new Date().toISOString(),
        }),
      )
    } catch {
      /* storage unavailable - comparison falls back to the backend/defaults */
    }
    if (curveTimer) window.clearTimeout(curveTimer)
    curveTimer = window.setTimeout(() => void refreshCurve(), 160)
  },
)

async function restoreDefaults(): Promise<void> {
  try {
    await ElMessageBox.confirm('将 21 个权重与目标保持率恢复为 FSRS-6 官方默认值？', '恢复默认', {
      confirmButtonText: '恢复',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  params.weights = [...DEFAULT_WEIGHTS]
  params.desiredRetention = DEFAULT_RETENTION
  ElMessage.success('已恢复默认值，记得保存')
}

async function save(): Promise<void> {
  loading.save = true
  try {
    await schedulerApi.save(params.weights.map((w) => Number(w)), Number(params.desiredRetention))
    savedSnapshot.value = snapshot()
    // Keep a local history so /compare can diff this set against the defaults.
    saveParams(
      params.weights.map((w) => Number(w)),
      Number(params.desiredRetention),
      backend.isOffline ? '本地编辑（离线演示）' : '后端已保存',
    )
    ElMessage.success(backend.isOffline ? '已保存到本地演示数据' : '参数已保存到后端')
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'))
  } finally {
    loading.save = false
  }
}

onMounted(load)
</script>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.muted {
  color: var(--fs-text-1);
  font-weight: 550;
}

.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

/* ---------------------------- presets ---------------------------- */

.presets {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-bottom: 14px;
  margin-bottom: 4px;
  border-bottom: 1px dashed var(--fs-line);
}

.presets__label {
  font-size: 12.5px;
  color: var(--fs-text-2);
}

.presets__note {
  flex-basis: 100%;
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.control__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12.5px;
  margin-bottom: 2px;
}

.control__val {
  color: var(--fs-accent);
  font-weight: 600;
  font-size: 15px;
}

.control__hint {
  margin-top: 26px;
  font-size: 12.5px;
  color: var(--fs-text-1);
  line-height: 1.7;
}

.control__hint--minor {
  margin-top: 10px;
  font-size: 12px;
  color: var(--fs-text-2);
}

.control__hint b.good {
  color: var(--fs-good);
}

/* ---------------------------- preview table ---------------------------- */

.preview {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.preview th {
  text-align: center;
  padding: 8px 6px;
  font-size: 12px;
  font-weight: 600;
  border-bottom: 1px solid var(--fs-line);
}

.preview__th-s {
  text-align: left;
}

.preview__zh {
  display: block;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--fs-text-2);
}

.preview td {
  padding: 10px 6px;
  text-align: center;
  border-bottom: 1px solid var(--fs-line-soft);
}

.preview tbody tr:hover {
  background: rgba(76, 201, 240, 0.06);
}

.preview__s {
  text-align: left !important;
  color: var(--fs-text-1);
}

.preview__s-val {
  font-weight: 600;
  color: var(--fs-text-0);
}

.preview__s-note {
  display: block;
  font-size: 10.5px;
  color: var(--fs-text-2);
}

.preview__value {
  font-size: 15px;
  font-weight: 600;
  color: var(--fs-text-0);
}

.preview__delta {
  display: block;
  font-size: 10.5px;
  color: var(--fs-text-2);
}

.preview__delta--up {
  color: var(--fs-good);
}

.preview__delta--down {
  color: var(--fs-again);
}

.read-guide {
  margin-top: 14px;
  padding: 11px 13px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  border-left: 3px solid rgba(76, 201, 240, 0.5);
  background: rgba(16, 26, 44, 0.5);
}

.read-guide__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fs-accent);
  margin-bottom: 5px;
}

.read-guide p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--fs-text-1);
}

.read-guide strong {
  color: var(--fs-text-0);
}

.read-guide__sub {
  margin-top: 6px !important;
  font-size: 11.5px !important;
  color: var(--fs-text-2) !important;
}

.legend--up {
  color: var(--fs-good);
}

.legend--down {
  color: var(--fs-again);
}

/* ---------------------------- curve panel ---------------------------- */

.curve-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 6px;
  flex-wrap: wrap;
}

.curve-tools__label {
  font-size: 12px;
  color: var(--fs-text-2);
}

/* ---------------------------- advanced ---------------------------- */

.advanced {
  padding: 0 4px;
}

.advanced__collapse {
  --el-collapse-border-color: transparent;
  --el-collapse-header-bg-color: transparent;
  --el-collapse-content-bg-color: transparent;
  border: none;
}

/* Element Plus only leaves a small gutter here; the editor needs breathing room. */
.advanced__collapse :deep(.el-collapse-item__header) {
  padding: 14px 4px;
  font-size: 14.5px;
}

.advanced__collapse :deep(.el-collapse-item__content) {
  padding: 4px 18px 20px;
}

.advanced__collapse :deep(.el-collapse-item__wrap) {
  border-bottom: none;
  background: transparent;
}

.advanced__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 14.5px;
  font-weight: 600;
}

.advanced__warn {
  font-size: 11.5px;
  font-weight: 400;
  color: #ffd7a3;
}

.advanced__bar,
.advanced__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.5);
}

.advanced__bar {
  margin-bottom: 12px;
}

.advanced__foot {
  margin-top: 16px;
}

.advanced__bar-note,
.advanced__foot-note {
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.advanced__actions {
  display: flex;
  gap: 8px;
}

.advanced__intro {
  margin: 0 0 14px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--fs-text-2);
}

.weights__body {
  display: flex;
  flex-direction: column;
  gap: 18px;
  max-height: none;
}

.wgroup__title {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--fs-text-0);
  padding-bottom: 7px;
  margin-bottom: 10px;
  border-bottom: 1px dashed var(--fs-line);
}

.wgroup__hint {
  font-size: 11.5px;
  color: var(--fs-text-2);
  font-weight: 400;
}

.wgroup__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(216px, 1fr));
  gap: 10px 16px;
}

.wfield {
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) 46px;
  grid-template-rows: auto auto;
  align-items: center;
  gap: 3px 8px;
}

.wfield__name {
  font-size: 12px;
  color: var(--fs-accent);
  cursor: help;
}

.wfield__bar {
  grid-column: 2;
  height: 2.5px;
  border-radius: 3px;
  background: rgba(139, 155, 180, 0.16);
  overflow: hidden;
}

.wfield__bar-in {
  display: block;
  height: 100%;
  background: rgba(76, 201, 240, 0.55);
  transition: width 0.25s ease;
}

.wfield__bar-in--diff {
  background: linear-gradient(90deg, #ffab4d, #ff5d6c);
}

.wfield__default {
  grid-row: 1;
  grid-column: 3;
  font-size: 10.5px;
  color: var(--fs-text-2);
  text-align: right;
}

.wfield :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 1240px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
