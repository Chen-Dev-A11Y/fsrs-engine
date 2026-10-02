<template>
  <div class="fs-page">
    <header class="fs-page__head">
      <div>
        <h1 class="fs-page__title">
          参数对比
          <span class="fs-chip fs-chip--muted">A / B 两组参数</span>
        </h1>
        <p class="fs-page__sub">对比两组调度参数，看看换成另一组会有什么不同。所有数字都是拿你现在选的两组实时算出来的。</p>
      </div>
      <div class="head-actions">
        <el-button size="small" @click="reloadLocal">刷新本地历史</el-button>
        <el-button size="small" @click="$router.push('/scheduler')">去参数页</el-button>
      </div>
    </header>

    <!-- ---------------------------------------------------------------
         1. 选 A / B（A 固定官方默认，B 只有三个来源）
         --------------------------------------------------------------- -->
    <section class="fs-card">
      <div class="fs-card__head">
        <span class="fs-card__title">
          <span class="group__badge" style="--c: #8b9bb4">A</span>
          官方默认
          <span class="fs-card__hint">FSRS-6 出厂的 21 个权重，目标保持率 90%，不可改</span>
        </span>
        <span class="fs-card__title group--b">
          <span class="group__badge" style="--c: #4cc9f0">B</span>
          要比的另一组
          <span class="fs-card__hint">{{ sourceLabel }}</span>
        </span>
      </div>
      <div class="fs-card__body">
        <div class="picker">
          <el-radio-group v-model="source" size="small">
            <el-radio-button value="current">当前编辑中</el-radio-button>
            <el-radio-button value="demo">演示优化参数</el-radio-button>
            <el-radio-button value="paste">手动粘贴</el-radio-button>
          </el-radio-group>

          <div class="picker__retention">
            <span class="picker__retention-label">B 组目标保持率</span>
            <el-input-number v-model="retentionB" :min="0.7" :max="0.99" :step="0.01" :precision="2" size="small" />
            <span class="picker__retention-hint">
              越高 = 越要求记得牢（间隔更短、复习更勤）；A 组固定 {{ (retentionA * 100).toFixed(0) }}%。
            </span>
          </div>
        </div>

        <p v-if="source === 'current' && !currentLoaded" class="picker__note picker__note--warn">
          还没读到参数页的参数，现在 B 组临时用官方默认值在算。
          <el-button link type="primary" size="small" :loading="loading" @click="loadCurrent">点这里载入当前编辑中的参数</el-button>
        </p>

        <div v-else-if="source === 'paste'" class="picker__stack">
          <el-input
            v-model="pasted"
            type="textarea"
            :rows="3"
            placeholder="粘贴 21 个权重：JSON 数组、{&quot;weights&quot;:[...]} 或逗号分隔都可以"
          />
          <div class="picker__row">
            <el-button size="small" @click="pasted = JSON.stringify(OPTIMIZED_DEMO_WEIGHTS)">填入演示优化参数</el-button>
            <span v-if="pasteError" class="picker__error">{{ pasteError }}</span>
            <span v-else class="picker__ok">已解析 21 个权重</span>
          </div>
        </div>

        <p v-else-if="source === 'demo'" class="picker__note picker__note--warn">
          {{ OPTIMIZED_DEMO_LABEL }}：优化器模块还在开发中，这组数字是手工调的示意值，只用来演示对比能力。
        </p>

        <p v-else class="picker__note">
          正在对比参数页当前编辑中的参数（含还没保存的改动）。
        </p>

        <details class="meter">
          <summary>两组到底差在哪（{{ diffList.length }} 个权重不同）</summary>
          <div v-if="!diffList.length" class="fs-empty">两组完全相同，换一组参数或改动 B 组保持率再看看。</div>
          <ul v-else class="meter__list">
            <li v-for="d in diffList" :key="d.index" class="diff">
              <span class="diff__name fs-mono">{{ d.name }}</span>
              <span class="diff__vals fs-mono">
                <span class="diff__a">{{ d.from }}</span>
                <span class="diff__arrow">→</span>
                <span class="diff__b">{{ d.to }}</span>
              </span>
              <span class="diff__pct fs-mono" :class="d.to >= d.from ? 'cell__pct--up' : 'cell__pct--down'">
                {{ d.from === 0 ? '—' : `${d.to >= d.from ? '+' : ''}${(((d.to - d.from) / Math.abs(d.from)) * 100).toFixed(0)}%` }}
              </span>
            </li>
          </ul>
        </details>
      </div>
    </section>

    <!-- ---------------------------------------------------------------
         2. 结论卡（先说人话，再给数字）
         --------------------------------------------------------------- -->
    <section class="fs-card verdict">
      <div class="fs-card__head">
        <span class="fs-card__title">一句话结论</span>
        <span class="fs-card__hint">
          用现算出来的间隔换成「复习省了多少 / 间隔长了多少」，口径见页面底部
        </span>
      </div>
      <div class="fs-card__body">
        <p class="verdict__line">{{ verdictSentence }}</p>

        <div class="tiles">
          <div class="tile" :class="cmp.reviewReduction >= 0 ? 'tile--good' : 'tile--bad'">
            <div class="tile__label">复习次数（同等记忆效果）</div>
            <div class="tile__value fs-mono">{{ reviewChangeText }}%</div>
            <div class="tile__hint">负数 = B 组更省：同样的时间里少复习这么多</div>
          </div>
          <div class="tile" :class="cmp.averagePercent >= 0 ? 'tile--good' : 'tile--bad'">
            <div class="tile__label">间隔长度（16 个格子平均）</div>
            <div class="tile__value fs-mono">{{ signed(cmp.averagePercent) }}%</div>
            <div class="tile__hint">正数 = B 组把复查时间推得更远</div>
          </div>
          <div class="tile" :class="retentionDelta >= 0 ? 'tile--good' : 'tile--bad'">
            <div class="tile__label">目标保持率</div>
            <div class="tile__value fs-mono">{{ signed(retentionDelta, 0) }} pt</div>
            <div class="tile__hint">A 组 90% · B 组 {{ (retentionB * 100).toFixed(0) }}%；正数 = B 组要求更牢</div>
          </div>
        </div>

        <div class="verdict__actions">
          <el-button
            size="small"
            @click="$router.push({ path: '/scheduler', query: { weights: queryWeights, retention: groupB.retention } })"
          >
            把这组参数带到参数页
          </el-button>
        </div>
      </div>
    </section>

    <!-- ---------------------------------------------------------------
         3. 两组曲线叠加
         --------------------------------------------------------------- -->
    <section class="fs-card">
      <div class="fs-card__head">
        <span class="fs-card__title">
          两组参数下，记忆掉得多快
          <span class="fs-card__hint">同一张卡（同样的 S）在两套参数下的遗忘曲线</span>
        </span>
        <div class="head-tools">
          <span class="tool-label">假设这张卡的 S =</span>
          <el-radio-group v-model="curveStability" size="small">
            <el-radio-button :value="10">10 天</el-radio-button>
            <el-radio-button :value="100">100 天</el-radio-button>
            <el-radio-button :value="365">365 天</el-radio-button>
          </el-radio-group>
        </div>
      </div>
      <div class="fs-card__body">
        <ForgetCurveCompareChart :series="curveSeries" :stability="curveStability" :max-day="365" :height="340" />

        <ChartHint :lines="curveHintLines" />

        <p class="crossings">
          两条线都在「S 天数」处穿过 90%（当前基准 S = {{ curveStability }} 天，所以都在
          <b class="fs-mono">{{ curveStability }} 天</b> 左右穿过），这是算法定义决定的；
          真正会变的是<b>曲线的陡峭程度</b>——也就是它们离开 90% 之后掉得多快。
        </p>
      </div>
    </section>

    <!-- ---------------------------------------------------------------
         4. 间隔对比（默认只看 Good，完整表折叠）
         --------------------------------------------------------------- -->
    <section class="fs-card">
      <div class="fs-card__head">
        <span class="fs-card__title">
          同一张卡，两组参数分别排到第几天
          <span class="fs-card__hint">先看最常见的评分 Good：答对了，下次该隔多久</span>
        </span>
        <span class="fs-card__hint">差异 ≥ {{ SIGNIFICANT_PERCENT }}% 会加粗</span>
      </div>
      <div class="fs-card__body">
        <table class="compare">
          <thead>
            <tr>
              <th>这张卡现在有多熟</th>
              <th>A 组多久后复习</th>
              <th>B 组多久后复习</th>
              <th>差别</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in goodRows" :key="row.stability">
              <td class="compare__s">
                <span class="fs-mono">S = {{ row.stability }} 天</span>
                <span class="compare__s-note">{{ familiarity(row.stability) }}</span>
              </td>
              <td class="fs-mono">{{ row.a }} 天</td>
              <td class="fs-mono compare__b" :class="`compare__b--${row.trend}`">{{ row.b }} 天</td>
              <td class="fs-mono" :class="`cell__pct--${row.trend}`">
                {{ row.percent > 0 ? '+' : '' }}{{ row.percent.toFixed(1) }}%
                <span class="compare__word">（{{ row.trend === 'same' ? '基本一样' : row.trend === 'up' ? '更晚复习' : '更早复习' }}）</span>
              </td>
            </tr>
          </tbody>
        </table>

        <p class="table-note">
          「多熟」用 S（稳定性）表示：还有 90% 把握记得住它的天数，越往下越熟。
          如果你这次点了 <b>Good</b>（答对但不算太轻松），那么下一行就是新算出来的间隔。
          想看 Again / Hard / Easy 和更多 S 的完整表格，展开下面的折叠区。
        </p>

        <details class="meter">
          <summary>完整表格：4 种评分 × 4 个熟练度</summary>
          <table class="compare compare--full">
            <thead>
              <tr>
                <th>这张卡现在有多熟</th>
                <th v-for="meta in ratingMeta" :key="meta.key" :style="{ color: meta.color }">
                  {{ meta.label }}
                  <span class="compare__zh">{{ meta.zh }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in cmp.rows" :key="row.stability">
                <td class="compare__s fs-mono">S = {{ row.stability }} 天</td>
                <td v-for="cell in row.cells" :key="cell.key">
                  <span class="full-cell" :class="{ 'full-cell--sig': cell.significant }">
                    <span class="fs-mono full-cell__ab">
                      <span class="full-cell__a">{{ cell.a }}</span>
                      <span class="full-cell__arrow">→</span>
                      <span class="full-cell__b" :class="`cell__b--${cell.trend}`">{{ cell.b }}</span>
                    </span>
                    <span class="fs-mono cell__pct" :class="`cell__pct--${cell.trend}`">
                      {{ cell.percent > 0 ? '+' : '' }}{{ cell.percent.toFixed(1) }}%
                    </span>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          <p class="table-note">
            每格是「A 的天数 → B 的天数」以及变化百分比。单位都是天，天数为 0 表示当天就再见。
          </p>
        </details>

        <details class="meter">
          <summary>这些数字是怎么算出来的（想深究再点开）</summary>
          <ul class="meter__list meter__list--rich">
            <li>
              <b>间隔</b>：用的是和正式排期同一套 FSRS-6 公式。假设条件固定为「难度中等（D=5）、距上次复习 1 天」，
              两组各自按自己的权重和目标保持率算下一间隔。也就是说，这是同一张难度中等的卡在两套参数下各排到第几天。
            </li>
            <li>
              <b>复习次数</b>：把「间隔变长多少」换算成「复习次数少多少」（<span class="fs-mono">1 - A间隔 ÷ B间隔</span>），
              再对全部格子取平均。它只反映间隔带来的量级差别，没有模拟忘了以后要额外补的复习，也没有算每天新卡上限，
              所以当作「量级」看，不要当精确预测。
            </li>
            <li>
              <b>目标保持率差异</b>：如果 A、B 的保持率不同，间隔差别里既有权重的功劳也有目标的功劳。
              想单独看权重的影响，就把 B 组保持率也调成 90% 再比一次。
            </li>
            <li>
              <b>曲线</b>：按天采样画出来的，和参数页用的是同一套公式，所以两页看到的是同一件事的两种画法。
            </li>
          </ul>
        </details>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ChartHint from '@/components/ChartHint.vue'
import ForgetCurveCompareChart, { type CompareSeries } from '@/components/charts/ForgetCurveCompareChart.vue'
import { schedulerApi } from '@/api'
import {
  CURRENT_PARAMS_KEY,
  OPTIMIZED_DEMO_LABEL,
  OPTIMIZED_DEMO_RETENTION,
  OPTIMIZED_DEMO_WEIGHTS,
  isWeightArray,
  parseWeights,
  type SavedParamsEntry,
} from '@/mock/optimized'
import { DEFAULT_RETENTION, formatDays } from '@/utils/fsrs'
import { listSavedParams } from '@/utils/paramsStore'
import { SIGNIFICANT_PERCENT, compareGroups, defaultGroup, weightDiff, type CompareGroup } from '@/utils/compare'
import { RATING_META } from '@/types/api'

/** Only three sources are offered; the localStorage history list feeds「演示优化参数」. */
type Source = 'current' | 'paste' | 'demo'

const loading = ref(false)
const source = ref<Source>('demo')
const pasted = ref('')
const pasteError = ref('')
const retentionA = ref(DEFAULT_RETENTION)
const retentionB = ref(OPTIMIZED_DEMO_RETENTION)
const savedEntries = ref<SavedParamsEntry[]>([])
const curveStability = ref(100)
const currentLoaded = ref(false)

const currentWeights = ref<number[]>([...OPTIMIZED_DEMO_WEIGHTS])

const ratingMeta: { key: 'again' | 'hard' | 'good' | 'easy'; label: string; zh: string; color: string }[] = [
  { key: 'again', label: RATING_META[1].label, zh: RATING_META[1].zh, color: RATING_META[1].color },
  { key: 'hard', label: RATING_META[2].label, zh: RATING_META[2].zh, color: RATING_META[2].color },
  { key: 'good', label: RATING_META[3].label, zh: RATING_META[3].zh, color: RATING_META[3].color },
  { key: 'easy', label: RATING_META[4].label, zh: RATING_META[4].zh, color: RATING_META[4].color },
]

const groupA = computed<CompareGroup>(() => ({ ...defaultGroup(retentionA.value), color: '#8b9bb4' }))

const groupB = computed<CompareGroup>(() => {
  let weights = [...OPTIMIZED_DEMO_WEIGHTS]
  if (source.value === 'current') weights = [...currentWeights.value]
  else if (source.value === 'paste') weights = parseWeights(pasted.value) ?? [...OPTIMIZED_DEMO_WEIGHTS]
  return {
    weights,
    retention: retentionB.value,
    label: sourceLabel.value,
    color: '#4cc9f0',
  }
})

const sourceLabel = computed(() => {
  if (source.value === 'current') return currentLoaded.value ? '参数页当前编辑中的参数' : '当前编辑中的参数（还没载入）'
  if (source.value === 'paste') return parseWeights(pasted.value) ? '手动粘贴的参数' : '手动粘贴（还没解析成功）'
  return OPTIMIZED_DEMO_LABEL
})

const cmp = computed(() => compareGroups(groupA.value, groupB.value))

const diffList = computed(() => weightDiff(groupA.value.weights, groupB.value.weights))

const curveSeries = computed<CompareSeries[]>(() => [
  { label: `A · 官方默认`, weights: groupA.value.weights, color: '#8b9bb4', retention: retentionA.value },
  { label: `B · ${shortLabel.value}`, weights: groupB.value.weights, color: '#4cc9f0', retention: retentionB.value },
])

/** Short enough for a chart legend, where the full label would be clipped. */
const shortLabel = computed(() => {
  if (source.value === 'current') return '当前编辑中'
  if (source.value === 'paste') return '手动粘贴'
  return '演示优化参数'
})

const retentionDelta = computed(() => (retentionB.value - retentionA.value) * 100)

const reviewChangeText = computed(() => `${cmp.value.reviewReduction >= 0 ? '-' : '+'}${Math.abs(cmp.value.reviewReduction * 100).toFixed(1)}`)

function signed(value: number, digits = 1): string {
  return `${value > 0 ? '+' : ''}${value.toFixed(digits)}`
}

/** The S = 100 Good cell - the headline number most readers can relate to. */
const goodAt100 = computed(() => {
  const row = cmp.value.rows.find((r) => r.stability === 100)
  return row?.cells.find((c) => c.key === 'good') ?? null
})

/** The compact Good-only table: A / B / 差别, in everyday units. */
const goodRows = computed(() =>
  cmp.value.rows.map((row) => {
    const cell = row.cells.find((c) => c.key === 'good')
    return {
      stability: row.stability,
      a: cell?.a ?? 0,
      b: cell?.b ?? 0,
      percent: cell?.percent ?? 0,
      trend: cell?.trend ?? 'same',
    }
  }),
)

const verdictSentence = computed(() => {
  const g = goodAt100.value
  const aDays = g ? formatDays(g.a) : '—'
  const bDays = g ? formatDays(g.b) : '—'
  const direction = (g?.percent ?? 0) >= 0 ? '更长' : '更短'
  const effort = cmp.value.reviewReduction >= 0 ? '减少' : '增加'
  const effortPct = Math.abs(cmp.value.reviewReduction * 100).toFixed(0)
  const retentionWord =
    Math.abs(retentionDelta.value) < 0.5
      ? '两组要求的记忆标准一样（都是 90%）'
      : `B 组要求的记忆标准${retentionDelta.value > 0 ? '更高' : '更低'}（${(retentionB.value * 100).toFixed(0)}% vs 90%）`
  const cost =
    (g?.percent ?? 0) >= 0
      ? '代价是每次的间隔更长，单次复习时更容易忘，忘了还要重来。'
      : '好处是复习时更不容易忘，代价是复习次数变多。'
  return (
    `换成 B 组（${sourceLabel.value}）后，考同一张卡、要同样的记忆效果，复习次数大约${effort} ${effortPct}%；` +
    `以「已经熟到 S = 100 天」的卡为例，答对（Good）后下次复习从 ${aDays} 变成 ${bDays}（${direction}）。` +
    `${cost}${retentionWord}。`
  )
})

const curveHintLines = computed(() => [
  '横轴是距上次复习的天数，纵轴是「还记得的概率」：两条线都从 100% 开始往下掉。',
  `线越陡，说明记忆掉得越快、需要越勤复习；两条线形状不同来自遗忘曲线形状参数 w20（A 组 ${groupA.value.weights[20]}，B 组 ${groupB.value.weights[20]}）。`,
  'R = 0.9 那条参考线是「目标线」：曲线穿过它的位置，就是算法认为「该复习了」的时间。',
])

/** Query string handed to the parameter page when applying group B. */
const queryWeights = computed(() => groupB.value.weights.join(','))

function reloadLocal(): void {
  savedEntries.value = listSavedParams()
}

watch(pasted, (value) => {
  pasteError.value = value.trim() && !parseWeights(value) ? '没能解析出 21 个数字，B 组暂时仍用演示参数。' : ''
})

/** `current` needs the parameter page's working copy, so fetch it as soon as it is picked. */
watch(source, (value) => {
  if (value === 'current' && !currentLoaded.value) void loadCurrent()
})

/**
 * Reads the parameter page's working copy (unsaved edits included).
 *
 * `syncRetention` is false on the initial mount: the page opens on「演示优化参数」,
 * and letting the parameter page's stored retention silently overwrite the demo
 * group's 90% would make the headline numbers depend on unrelated browser state.
 */
async function loadCurrent(syncRetention = true): Promise<void> {
  loading.value = true
  try {
    // Preference order: what the parameter page currently holds (even unsaved),
    // then what the backend reports.
    const raw = localStorage.getItem(CURRENT_PARAMS_KEY)
    const local = raw ? (JSON.parse(raw) as { weights?: unknown; desiredRetention?: unknown }) : null
    if (local && isWeightArray(local.weights)) {
      currentWeights.value = local.weights
      if (syncRetention && typeof local.desiredRetention === 'number') retentionB.value = local.desiredRetention
      currentLoaded.value = true
      return
    }
    const remote = await schedulerApi.params()
    if (isWeightArray(remote.weights)) {
      currentWeights.value = remote.weights
      if (syncRetention && Number.isFinite(remote.desiredRetention)) retentionB.value = remote.desiredRetention
      currentLoaded.value = true
    }
  } catch {
    currentLoaded.value = false
  } finally {
    loading.value = false
  }
}

function familiarity(stability: number): string {
  if (stability <= 2) return '刚学的卡'
  if (stability <= 30) return '有点印象'
  if (stability <= 300) return '已经很熟'
  return '滚瓜烂熟'
}

onMounted(() => {
  reloadLocal()
  void loadCurrent(false)
})
</script>

<style scoped>
.head-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.group__badge {
  width: 20px;
  height: 20px;
  display: inline-grid;
  place-items: center;
  border-radius: 6px;
  font-size: 11.5px;
  font-weight: 700;
  color: var(--c);
  background: color-mix(in srgb, var(--c) 18%, transparent);
  border: 1px solid color-mix(in srgb, var(--c) 42%, transparent);
}

.group--b {
  padding-left: 14px;
  border-left: 1px dashed var(--fs-line);
}

/* ---------------------------- picker ---------------------------- */

.picker {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.picker__retention {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.picker__retention-label {
  font-size: 12.5px;
  color: var(--fs-text-1);
}

.picker__retention-hint {
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.picker__note {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--fs-text-2);
}

.picker__note--warn {
  padding: 9px 11px;
  border-radius: var(--fs-radius-sm);
  color: #ffd7a3;
  background: rgba(255, 171, 77, 0.1);
  border: 1px solid rgba(255, 171, 77, 0.32);
}

.picker__stack {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}

.picker__row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.picker__error {
  font-size: 11.5px;
  color: #ff9aa4;
}

.picker__ok {
  font-size: 11.5px;
  color: var(--fs-good);
}

/* ---------------------------- verdict ---------------------------- */

.verdict__line {
  margin: 0;
  padding: 14px 16px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid rgba(76, 201, 240, 0.28);
  background: linear-gradient(90deg, rgba(76, 201, 240, 0.12), rgba(123, 140, 255, 0.06));
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--fs-text-0);
}

.tiles {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  margin-top: 16px;
}

.tile {
  padding: 12px 14px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line);
  background: rgba(16, 26, 44, 0.55);
}

.tile--good {
  border-color: rgba(61, 220, 151, 0.3);
}

.tile--bad {
  border-color: rgba(255, 93, 108, 0.3);
}

.tile__label {
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.tile__value {
  margin-top: 4px;
  font-size: 24px;
  font-weight: 650;
  color: var(--fs-text-0);
}

.tile--good .tile__value {
  color: var(--fs-good);
}

.tile--bad .tile__value {
  color: var(--fs-again);
}

.tile__hint {
  margin-top: 5px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--fs-text-2);
}

.verdict__actions {
  margin-top: 14px;
}

/* ---------------------------- compare table ---------------------------- */

.compare {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.compare th {
  padding: 9px 6px;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
  border-bottom: 1px solid var(--fs-line);
}

.compare th:first-child {
  text-align: left;
}

.compare td {
  padding: 9px 6px;
  border-bottom: 1px solid var(--fs-line-soft);
  text-align: center;
  vertical-align: middle;
}

.compare tbody tr:hover {
  background: rgba(76, 201, 240, 0.06);
}

.compare__s {
  text-align: left !important;
  color: var(--fs-text-1);
  white-space: nowrap;
}

.compare__s-note {
  display: block;
  font-size: 10.5px;
  color: var(--fs-text-2);
}

.compare__b--up {
  color: var(--fs-good);
}

.compare__b--down {
  color: var(--fs-again);
}

.compare__word {
  font-size: 10.5px;
  color: var(--fs-text-2);
}

.compare__zh {
  display: block;
  font-size: 10.5px;
  font-weight: 400;
  color: var(--fs-text-2);
}

.full-cell {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 4px 7px;
  border-radius: 8px;
  border: 1px solid transparent;
}

.full-cell--sig {
  border-color: rgba(139, 155, 180, 0.22);
  background: rgba(139, 155, 180, 0.06);
  font-weight: 700;
}

.full-cell__ab {
  font-size: 13px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--fs-text-1);
}

.full-cell__a {
  color: var(--fs-text-2);
}

.full-cell__arrow {
  color: var(--fs-text-2);
  font-size: 11px;
}

.full-cell__b {
  color: var(--fs-text-0);
}

.full-cell__b--up {
  color: var(--fs-good);
}

.full-cell__b--down {
  color: var(--fs-again);
}

.cell__pct {
  font-size: 10.5px;
}

.cell__pct--up {
  color: var(--fs-good);
}

.cell__pct--down {
  color: var(--fs-again);
}

.cell__pct--same {
  color: var(--fs-text-2);
}

.table-note {
  margin: 12px 0 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--fs-text-2);
}

.table-note b {
  color: var(--fs-text-1);
}

/* ---------------------------- curve ---------------------------- */

.head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.tool-label {
  font-size: 12px;
  color: var(--fs-text-2);
}

.crossings {
  margin: 12px 0 0;
  padding: 11px 13px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.5);
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--fs-text-1);
}

.crossings b {
  color: var(--fs-accent);
}

/* ---------------------------- folded detail ---------------------------- */

.meter {
  margin-top: 14px;
  font-size: 12px;
  color: var(--fs-text-2);
}

.meter summary {
  cursor: pointer;
  color: var(--fs-text-1);
  font-size: 12.5px;
  padding: 4px 0;
}

.meter__list {
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 8px;
}

.meter__list--rich {
  display: flex;
  flex-direction: column;
  padding-left: 18px;
  list-style: disc;
  gap: 7px;
  line-height: 1.8;
  font-size: 12px;
}

.meter__list--rich b {
  color: var(--fs-text-1);
}

.diff {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 52px;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  background: rgba(16, 26, 44, 0.45);
}

.diff__name {
  color: var(--fs-accent);
}

.diff__vals {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--fs-text-1);
}

.diff__a {
  color: var(--fs-text-2);
}

.diff__b {
  color: var(--fs-text-0);
  font-weight: 600;
}

.diff__arrow {
  color: var(--fs-text-2);
}

.diff__pct {
  text-align: right;
  font-size: 11px;
}

@media (max-width: 900px) {
  .group--b {
    padding-left: 0;
    border-left: none;
  }
}
</style>
