<template>
  <div ref="el" class="chart" :style="{ height: `${height}px` }" />
</template>

<script setup lang="ts">
import { useEcharts, type EChartsOption } from './useEcharts'

const props = defineProps<{
  option: EChartsOption
  height?: number
}>()

const { el } = useEcharts(() => props.option)
</script>

<style scoped>
.chart {
  width: 100%;
}
</style>
