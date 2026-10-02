<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { CHART_COLORS, baseTooltip, type EChartsOption } from './useEcharts'
import type { HeatmapDay } from '@/types/api'
import { addDays, prettyDate, toDateKey } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    data: HeatmapDay[]
    height?: number
  }>(),
  { height: 196 },
)

const range = computed<[string, string]>(() => {
  const today = new Date()
  const start = addDays(today, -364)
  return [toDateKey(start), toDateKey(today)]
})

const maxCount = computed(() => {
  const max = props.data.reduce((m, d) => Math.max(m, d.count), 0)
  return Math.max(4, Math.ceil(max / 4) * 4)
})

const byDate = computed(() => {
  const map = new Map<string, HeatmapDay>()
  props.data.forEach((d) => map.set(d.date, d))
  return map
})

const totalCount = computed(() => props.data.reduce((sum, d) => sum + d.count, 0))
const activeDays = computed(() => props.data.filter((d) => d.count > 0).length)

const option = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: {
    ...baseTooltip,
    formatter: (params: unknown) => {
      const p = params as { data?: [string, number] }
      const key = Array.isArray(p.data) ? p.data[0] : ''
      const day = byDate.value.get(key)
      if (!day) return `<b>${prettyDate(key)}</b><br/>没有复习记录`
      const rows = [
        `<b>${prettyDate(key)}</b>`,
        `复习总数 <b>${day.count}</b> 次`,
        `<span style="color:${CHART_COLORS.again}">Again ${day.again}</span> · ` +
          `<span style="color:${CHART_COLORS.hard}">Hard ${day.hard}</span> · ` +
          `<span style="color:${CHART_COLORS.good}">Good ${day.good}</span> · ` +
          `<span style="color:${CHART_COLORS.easy}">Easy ${day.easy}</span>`,
      ]
      return rows.join('<br/>')
    },
  },
  visualMap: {
    type: 'piecewise',
    orient: 'horizontal',
    right: 8,
    bottom: 0,
    itemWidth: 11,
    itemHeight: 11,
    itemGap: 4,
    textStyle: { color: CHART_COLORS.label, fontSize: 10 },
    pieces: [
      { min: 1, max: 4, label: '1-4', color: '#12384a' },
      { min: 5, max: 12, label: '5-12', color: '#175d78' },
      { min: 13, max: 29, label: '13-29', color: '#1f92b8' },
      { min: 30, max: 59, label: '30-59', color: '#2fb6dd' },
      { min: 60, label: '60+', color: '#7fe0ff' },
    ],
    outOfRange: { color: 'rgba(139,155,180,0.10)' },
  },
  calendar: {
    top: 34,
    left: 40,
    right: 16,
    cellSize: ['auto', 15],
    range: range.value,
    splitLine: { show: false, lineStyle: { color: 'rgba(139,155,180,0.08)' } },
    itemStyle: {
      color: 'rgba(139,155,180,0.10)',
      borderColor: 'rgba(7,12,23,0.85)',
      borderWidth: 2,
      borderRadius: 3,
    },
    yearLabel: { show: false },
    dayLabel: {
      firstDay: 1,
      nameMap: ['日', '一', '二', '三', '四', '五', '六'],
      color: CHART_COLORS.label,
      fontSize: 10,
    },
    monthLabel: {
      nameMap: 'ZH',
      color: CHART_COLORS.label,
      fontSize: 10,
    },
  },
  series: [
    {
      type: 'heatmap',
      coordinateSystem: 'calendar',
      data: props.data.map((d) => [d.date, d.count] as [string, number]),
      emphasis: { itemStyle: { borderColor: '#7fe0ff', borderWidth: 1.4 } },
    },
  ],
  // A tiny caption drawn inside the canvas keeps the card header clean.
  graphic: [
    {
      type: 'text',
      left: 40,
      top: 6,
      style: {
        text: `近一年共 ${totalCount.value} 次复习 · ${activeDays.value} 天有记录 · 峰值 ${maxCount.value} 次/天`,
        fill: CHART_COLORS.label,
        fontSize: 11.5,
      },
    },
  ],
}))
</script>
