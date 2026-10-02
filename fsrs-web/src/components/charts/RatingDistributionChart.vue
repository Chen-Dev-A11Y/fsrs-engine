<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { baseTooltip, type EChartsOption } from './useEcharts'
import { RATING_META, RATING_ORDER, type HeatmapDay, type Rating } from '@/types/api'

const props = withDefaults(
  defineProps<{
    data: HeatmapDay[]
    days?: number
    height?: number
  }>(),
  { height: 258, days: 365 },
)

const totals = computed(() => {
  const rows = props.data.slice(-props.days)
  const sum: Record<Rating, number> = { 1: 0, 2: 0, 3: 0, 4: 0 }
  rows.forEach((r) => {
    sum[1] += r.again
    sum[2] += r.hard
    sum[3] += r.good
    sum[4] += r.easy
  })
  return sum
})

const total = computed(() => RATING_ORDER.reduce((s, r) => s + (totals.value[r] ?? 0), 0))

const option = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  tooltip: {
    ...baseTooltip,
    formatter: (params: unknown) => {
      const p = params as { name: string; value: number; percent: number }
      return `<b>${p.name}</b><br/>${p.value} 次 · ${p.percent}%`
    },
  },
  legend: {
    orient: 'vertical',
    right: 4,
    top: 'center',
    itemWidth: 9,
    itemHeight: 9,
    itemGap: 12,
    textStyle: { color: '#b9c6db', fontSize: 12 },
    formatter: (name: string) => {
      const rating = RATING_ORDER.find((r) => RATING_META[r].label === name)
      const count = rating ? (totals.value[rating] ?? 0) : 0
      const pct = total.value ? ((count / total.value) * 100).toFixed(1) : '0.0'
      return `${name}  ${count}  (${pct}%)`
    },
  },
  series: [
    {
      type: 'pie',
      radius: ['52%', '76%'],
      center: ['34%', '52%'],
      avoidLabelOverlap: true,
      padAngle: 2,
      itemStyle: { borderRadius: 5, borderColor: '#0b1120', borderWidth: 1.5 },
      label: {
        show: true,
        position: 'center',
        formatter: () => `{v|${total.value}}\n{t|总复习}`,
        rich: {
          v: { color: '#eaf1fb', fontSize: 26, fontWeight: 600, lineHeight: 32 },
          t: { color: '#8494ad', fontSize: 11 },
        },
      },
      emphasis: { scale: true, scaleSize: 6, label: { show: true } },
      labelLine: { show: false },
      data: RATING_ORDER.map((r) => ({
        name: RATING_META[r].label,
        value: totals.value[r] ?? 0,
        itemStyle: { color: RATING_META[r].color },
      })),
    },
  ],
}))
</script>
