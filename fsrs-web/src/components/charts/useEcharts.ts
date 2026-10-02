import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import {
  CalendarComponent,
  DataZoomComponent,
  GraphicComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsOption } from 'echarts'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  HeatmapChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  VisualMapComponent,
  CalendarComponent,
  MarkLineComponent,
  MarkPointComponent,
  DataZoomComponent,
  GraphicComponent,
  CanvasRenderer,
])

/** Shared dark palette so every chart matches the shell. */
export const CHART_COLORS = {
  accent: '#4cc9f0',
  violet: '#7b8cff',
  good: '#3ddc97',
  hard: '#ffab4d',
  again: '#ff5d6c',
  easy: '#4cc9f0',
  axis: '#24334d',
  label: '#8494ad',
  text: '#eaf1fb',
  tooltipBg: 'rgba(11, 17, 32, 0.94)',
}

export const baseTooltip = {
  backgroundColor: CHART_COLORS.tooltipBg,
  borderColor: 'rgba(76, 201, 240, 0.35)',
  borderWidth: 1,
  textStyle: { color: CHART_COLORS.text, fontSize: 12 },
  extraCssText: 'border-radius:10px;box-shadow:0 12px 28px rgba(2,6,16,.5);',
} as const

export function useEcharts(option: () => EChartsOption) {
  const el = ref<HTMLDivElement | null>(null)
  const chart = shallowRef<echarts.ECharts | null>(null)
  let observer: ResizeObserver | null = null

  function render(): void {
    if (!chart.value) return
    chart.value.setOption(option(), true)
  }

  onMounted(() => {
    if (!el.value) return
    chart.value = echarts.init(el.value, undefined, { renderer: 'canvas' })
    render()
    observer = new ResizeObserver(() => chart.value?.resize())
    observer.observe(el.value)
  })

  watch(option, render, { deep: true })

  onBeforeUnmount(() => {
    observer?.disconnect()
    chart.value?.dispose()
    chart.value = null
  })

  return { el, chart, render }
}

export { echarts }
export type { EChartsOption }
