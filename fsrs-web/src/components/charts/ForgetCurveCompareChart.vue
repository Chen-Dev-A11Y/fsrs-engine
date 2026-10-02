<template>
  <EChart :option="option" :height="height" />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import EChart from './EChart.vue'
import { CHART_COLORS, baseTooltip, type EChartsOption } from './useEcharts'
import { sampleCurve } from '@/utils/fsrs'

export interface CompareSeries {
  label: string
  weights: number[]
  color: string
  retention: number
}

const props = withDefaults(
  defineProps<{
    series: CompareSeries[]
    stability: number
    maxDay?: number
    height?: number
  }>(),
  { height: 340, maxDay: 365 },
)

/**
 * Crossings of R = 0.9 for each parameter set.
 *
 * With the official formula `factor = 0.9^(-1/w20) - 1` every curve passes
 * through exactly `R = 0.9` at `t = S`, so the crossing is a fixed point - but
 * it is computed from the sampled points anyway (rather than assumed) so the
 * readout stays correct if a backend ever returns a differently normalised
 * curve.
 */
const crossings = computed(() =>
  props.series.map((s) => {
    const points = sampleCurve(props.stability, props.maxDay ?? 365, s.weights, 1)
    for (let i = 1; i < points.length; i += 1) {
      const a = points[i - 1]!
      const b = points[i]!
      if (a.retrievability >= 0.9 && b.retrievability <= 0.9) {
        const span = a.retrievability - b.retrievability
        const ratio = span === 0 ? 0 : (a.retrievability - 0.9) / span
        return a.day + (b.day - a.day) * ratio
      }
    }
    return points.at(-1)?.day ?? 0
  }),
)

const option = computed<EChartsOption>(() => {
  const curves = props.series.map((s) => ({
    series: s,
    points: sampleCurve(props.stability, props.maxDay ?? 365, s.weights, 1),
  }))

  return {
    backgroundColor: 'transparent',
    grid: { left: 54, right: 26, top: 46, bottom: 42 },
    legend: {
      top: 6,
      left: 8,
      itemWidth: 16,
      itemHeight: 9,
      textStyle: { color: '#b9c6db', fontSize: 12 },
      data: props.series.map((s) => s.label),
    },
    tooltip: {
      ...baseTooltip,
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: 'rgba(76,201,240,0.42)' } },
      formatter: (params: unknown) => {
        const list = params as { seriesName: string; value: [number, number]; color: string }[]
        if (!list?.length) return ''
        const day = list[0]?.value?.[0] ?? 0
        const rows = [`<b>第 ${Number(day).toFixed(0)} 天</b>`]
        list.forEach((item) => {
          rows.push(
            `<span style="color:${item.color}">●</span> ${item.seriesName} R = <b>${(
              (item.value?.[1] ?? 0) * 100
            ).toFixed(2)}%</b>`,
          )
        })
        return rows.join('<br/>')
      },
    },
    xAxis: {
      type: 'log',
      name: '距复习天数（对数轴）',
      nameLocation: 'middle',
      nameGap: 26,
      nameTextStyle: { color: CHART_COLORS.label, fontSize: 11 },
      min: 1,
      max: props.maxDay ?? 365,
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
    series: curves.map(({ series, points }, index) => ({
      name: series.label,
      type: 'line' as const,
      smooth: true,
      showSymbol: false,
      sampling: 'lttb' as const,
      lineStyle: { width: 2.4, color: series.color },
      itemStyle: { color: series.color },
      data: points.map((p) => [p.day, p.retrievability]),
      markPoint: {
        symbol: 'circle',
        symbolSize: 10,
        itemStyle: { color: series.color, borderColor: '#0b1120', borderWidth: 2 },
        label: {
          formatter: `R=90% @ ${crossings.value[index]?.toFixed(1)} 天`,
          color: '#eaf1fb',
          fontSize: 10.5,
          offset: [0, index === 0 ? -18 : 20],
          backgroundColor: 'rgba(11,17,32,0.92)',
          borderColor: `${series.color}88`,
          borderWidth: 1,
          borderRadius: 6,
          padding: [3, 6],
        },
        data: [{ name: `cross-${index}`, coord: [Math.min(crossings.value[index] ?? 1, props.maxDay ?? 365), 0.9] }],
      },
      markLine:
        index === 0
          ? {
              silent: true,
              symbol: ['none', 'none'],
              lineStyle: { color: 'rgba(255,171,77,0.75)', type: 'dashed', width: 1.3 },
              label: {
                formatter: 'R = 90% 参考线',
                color: CHART_COLORS.hard,
                fontSize: 11,
                position: 'insideStartTop',
              },
              data: [{ yAxis: 0.9 }],
            }
          : undefined,
    })),
  }
})
</script>
