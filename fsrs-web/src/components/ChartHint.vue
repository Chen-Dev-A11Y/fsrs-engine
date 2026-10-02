<template>
  <div class="chart-hint">
    <div class="chart-hint__title">{{ title }}</div>
    <ul class="chart-hint__list">
      <li v-for="(line, i) in lines" :key="i">{{ line }}</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
/**
 * The "怎么看这张图" block that sits under every chart.
 *
 * A chart is only useful to a first-time reader if the axes and the reference
 * lines are spelled out in plain language right next to it, so each page passes
 * its own 1-3 sentences. Kept as a shared component so the wording is never the
 * only thing that differs between pages.
 */
withDefaults(
  defineProps<{
    /** Row heading, e.g. 「怎么看这张图」. */
    title?: string
    /** One sentence per bullet. */
    lines: string[]
  }>(),
  { title: '怎么看这张图' },
)
</script>

<style scoped>
.chart-hint {
  margin-top: 12px;
  padding: 11px 13px;
  border-radius: var(--fs-radius-sm);
  border: 1px solid var(--fs-line-soft);
  border-left: 3px solid rgba(76, 201, 240, 0.5);
  background: rgba(16, 26, 44, 0.5);
}

.chart-hint__title {
  font-size: 12px;
  font-weight: 600;
  color: var(--fs-accent);
  margin-bottom: 5px;
}

.chart-hint__list {
  margin: 0;
  padding-left: 17px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--fs-text-1);
}
</style>
