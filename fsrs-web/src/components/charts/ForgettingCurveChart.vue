<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { CHART_COLORS, baseTooltip, type EChartsOption } from './useEcharts'
import type { CurvePoint } from '@/types/api'

const props = withDefaults(
  defineProps<{
    points: CurvePoint[]
    stability: number
    targetRetention: number
    crossingDay: number
    height?: number
    logAxis?: boolean
  }>(),
  { height: 320, logAxis: true },
)

const DEGRADED = 0.9

const option = computed<EChartsOption>(() => {
  const marks: Record<string, unknown>[] = [
    {
      yAxis: props.targetRetention,
      lineStyle: { color: CHART_COLORS.hard, type: 'dashed', width: 1.3 },
      label: {
        formatter: `目标保持率 ${(props.targetRetention * 100).toFixed(0)}%`,
        color: CHART_COLORS.hard,
        fontSize: 11,
        position: 'insideEndTop',
      },
    },
  ]
  if (Math.abs(props.targetRetention - DEGRADED) > 0.005) {
    marks.push({
      yAxis: DEGRADED,
      lineStyle: { color: 'rgba(139,155,180,0.55)', type: 'dotted', width: 1.2 },
      label: { formatter: 'R = 90%', color: CHART_COLORS.label, fontSize: 11, position: 'insideStartTop' },
    })
  }

  const crossX = Math.min(props.crossingDay, props.points.at(-1)?.day ?? props.crossingDay)

  return {
    backgroundColor: 'transparent',
    grid: { left: 52, right: 24, top: 34, bottom: 40 },
    tooltip: {
      ...baseTooltip,
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(76,201,240,0.5)' } },
      formatter: (params: unknown) => {
        const list = params as { value: [number, number] }[]
        const first = list?.[0]
        if (!first) return ''
        const [day, r] = first.value
        return [
          `<b>第 ${Number(day).toFixed(0)} 天</b>`,
          `R = <b>${(r * 100).toFixed(2)}%</b>`,
          `<span style="color:${CHART_COLORS.label}">S = ${props.stability.toFixed(1)} 天</span>`,
        ].join('<br/>')
      },
    },
    xAxis: {
      type: props.logAxis ? 'log' : 'value',
      name: '距复习的天数',
      nameLocation: 'middle',
      nameGap: 26,
      nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
      min: props.logAxis ? 1 : 0,
      max: props.points.at(-1)?.day ?? 365,
      axisLine: { lineStyle: { color: CHART_COLORS.axis } },
      axisLabel: { color: CHART_COLORS.label, fontSize: 10.5 },
      splitLine: { lineStyle: { color: 'rgba(36,51,77,0.6)' } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 1,
      name: '可提取性 R',
      nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
      axisLine: { lineStyle: { color: CHART_COLORS.axis } },
      axisLabel: { color: CHART_COLORS.label, fontSize: 10.5, formatter: (v: number) => `${(v * 100).toFixed(0)}%` },
      splitLine: { lineStyle: { color: 'rgba(36,51,77,0.6)' } },
    },
    series: [
      {
        name: 'R(t)',
        type: 'line',
        smooth: true,
        showSymbol: false,
        sampling: 'lttb',
        lineStyle: { width: 2.4, color: CHART_COLORS.accent },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(76,201,240,0.34)' },
              { offset: 1, color: 'rgba(76,201,240,0.02)' },
            ],
          },
        },
        data: props.points.map((p) => [p.day, p.retrievability]),
        markLine: {
          silent: true,
          symbol: ['none', 'none'],
          data: marks,
        },
        markPoint: {
          symbol: 'circle',
          symbolSize: 11,
          itemStyle: { color: CHART_COLORS.hard, borderColor: '#0b1120', borderWidth: 2 },
          label: {
            formatter: `R=${(props.targetRetention * 100).toFixed(0)}% @ ${props.crossingDay.toFixed(1)}天`,
            color: CHART_COLORS.text,
            fontSize: 11,
            offset: [0, -16],
            backgroundColor: 'rgba(11,17,32,0.9)',
            borderColor: 'rgba(255,171,77,0.5)',
            borderWidth: 1,
            borderRadius: 6,
            padding: [3, 7],
          },
          data: [{ name: 'target', coord: [crossX, props.targetRetention], value: props.crossingDay }],
        },
      },
    ],
  }
})
</script>
