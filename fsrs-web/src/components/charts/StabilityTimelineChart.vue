<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { CHART_COLORS, baseTooltip, type EChartsOption } from './useEcharts'
import { RATING_META, type TimelineReview } from '@/types/api'
import { formatDateTime } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    reviews: TimelineReview[]
    height?: number
    showDifficulty?: boolean
  }>(),
  { height: 300, showDifficulty: true },
)

const option = computed<EChartsOption>(() => {
  const labels = props.reviews.map((r, i) => `#${i + 1} · ${formatDateTime(r.reviewedAt).slice(0, 10)}`)
  const series: Record<string, unknown>[] = [
    {
      name: '稳定性 S',
      type: 'line',
      smooth: 0.35,
      symbolSize: 9,
      lineStyle: { width: 2.4, color: CHART_COLORS.accent },
      itemStyle: { color: CHART_COLORS.accent, borderColor: '#0b1120', borderWidth: 1.5 },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(76,201,240,0.28)' },
            { offset: 1, color: 'rgba(76,201,240,0.02)' },
          ],
        },
      },
      data: props.reviews.map((r) => ({
        value: r.stabilityAfter,
        symbolSize: r.rating === 1 ? 11 : 8,
        itemStyle: { color: RATING_META[r.rating].color, borderColor: '#0b1120', borderWidth: 1.5 },
      })),
    },
  ]

  if (props.showDifficulty) {
    series.push({
      name: '难度 D',
      type: 'line',
      smooth: 0.35,
      yAxisIndex: 1,
      symbolSize: 6,
      lineStyle: { width: 1.6, color: CHART_COLORS.violet, type: 'dashed' },
      itemStyle: { color: CHART_COLORS.violet },
      data: props.reviews.map((r) => r.difficultyAfter),
    })
  }

  return {
    backgroundColor: 'transparent',
    grid: { left: 52, right: props.showDifficulty ? 54 : 22, top: 38, bottom: 46 },
    legend: {
      top: 4,
      right: 8,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { color: '#b9c6db', fontSize: 11.5 },
    },
    tooltip: {
      ...baseTooltip,
      trigger: 'axis',
      formatter: (params: unknown) => {
        const list = params as { dataIndex: number }[]
        const idx = list?.[0]?.dataIndex ?? 0
        const r = props.reviews[idx]
        if (!r) return ''
        const meta = RATING_META[r.rating]
        return [
          `<b>${formatDateTime(r.reviewedAt)}</b>`,
          `评分 <span style="color:${meta.color}"><b>${meta.label}</b></span>`,
          `S：${r.stabilityBefore?.toFixed(2) ?? '—'} → <b>${r.stabilityAfter?.toFixed(2) ?? '—'}</b> 天`,
          `D：${r.difficultyBefore?.toFixed(2) ?? '—'} → <b>${r.difficultyAfter?.toFixed(2) ?? '—'}</b>`,
          `复习时 R = <b>${r.retrievability == null ? '—' : (r.retrievability * 100).toFixed(1)}%</b>`,
          `本次排期间隔 ${r.intervalDays} 天`,
        ].join('<br/>')
      },
    },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: CHART_COLORS.axis } },
      axisLabel: { color: CHART_COLORS.label, fontSize: 10.5, rotate: props.reviews.length > 6 ? 24 : 0 },
      axisTick: { show: false },
    },
    yAxis: [
      {
        type: 'log',
        name: 'S (天)',
        nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
        min: 0.3,
        axisLine: { show: false },
        axisLabel: { color: CHART_COLORS.label, fontSize: 10.5 },
        splitLine: { lineStyle: { color: 'rgba(36,51,77,0.6)' } },
      },
      {
        type: 'value',
        name: 'D',
        min: 1,
        max: 10,
        show: props.showDifficulty,
        nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
        axisLine: { show: false },
        axisLabel: { color: CHART_COLORS.label, fontSize: 10.5 },
        splitLine: { show: false },
      },
    ],
    series,
  }
})
</script>
