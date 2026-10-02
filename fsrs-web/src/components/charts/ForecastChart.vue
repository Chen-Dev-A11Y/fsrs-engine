<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { CHART_COLORS, baseTooltip, type EChartsOption } from './useEcharts'
import type { ForecastPoint } from '@/types/api'
import { formatShortDate } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    forecast: ForecastPoint[]
    height?: number
  }>(),
  { height: 240 },
)

const total = computed(() => props.forecast.reduce((sum, f) => sum + f.count, 0))
const peak = computed(() => props.forecast.reduce((m, f) => Math.max(m, f.count), 0))

const option = computed<EChartsOption>(() => ({
  backgroundColor: 'transparent',
  grid: { left: 48, right: 18, top: 30, bottom: 36 },
  tooltip: {
    ...baseTooltip,
    trigger: 'axis',
    axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(76,201,240,0.08)' } },
    formatter: (params: unknown) => {
      const list = params as { dataIndex: number }[]
      const idx = list?.[0]?.dataIndex ?? 0
      const point = props.forecast[idx]
      if (!point) return ''
      return `<b>${point.date}</b><br/>到期卡片 <b>${point.count}</b> 张<br/><span style="color:${CHART_COLORS.label}">累计 ${(props.forecast
        .slice(0, idx + 1)
        .reduce((s, f) => s + f.count, 0))} 张</span>`
    },
  },
  xAxis: {
    type: 'category',
    data: props.forecast.map((f) => formatShortDate(f.date)),
    axisLine: { lineStyle: { color: CHART_COLORS.axis } },
    axisLabel: { color: CHART_COLORS.label, fontSize: 10, interval: 2 },
    axisTick: { show: false },
  },
  yAxis: {
    type: 'value',
    name: '到期卡片数',
    nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
    axisLine: { show: false },
    axisLabel: { color: CHART_COLORS.label, fontSize: 10.5 },
    splitLine: { lineStyle: { color: 'rgba(36,51,77,0.6)' } },
  },
  series: [
    {
      type: 'bar',
      barMaxWidth: 16,
      data: props.forecast.map((f, i) => ({
        value: f.count,
        itemStyle: {
          borderRadius: [4, 4, 0, 0],
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops:
              i === 0
                ? [
                    { offset: 0, color: '#ffd39b' },
                    { offset: 1, color: 'rgba(255,171,77,0.25)' },
                  ]
                : [
                    { offset: 0, color: '#5fd6f5' },
                    { offset: 1, color: 'rgba(76,201,240,0.18)' },
                  ],
          },
        },
      })),
      markLine: {
        silent: true,
        symbol: ['none', 'none'],
        lineStyle: { color: 'rgba(139,155,180,0.5)', type: 'dashed' },
        label: { formatter: `峰值 ${peak.value}`, color: CHART_COLORS.label, fontSize: 10, position: 'insideEndTop' },
        data: [{ yAxis: peak.value }],
      },
    },
  ],
  graphic: [
    {
      type: 'text',
      left: 48,
      top: 4,
      style: { text: `未来 30 天共 ${total.value} 张到期`, fill: CHART_COLORS.label, fontSize: 11.5 },
    },
  ],
}))
</script>
