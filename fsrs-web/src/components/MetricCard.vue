<template>
  <div class="metric" :class="`metric--${tone}`">
    <div class="metric__top">
      <span class="metric__label">
        <!-- Callers may pass a richer label (e.g. a term plus a「?」explanation). -->
        <slot name="label">{{ label }}</slot>
      </span>
      <span v-if="tag" class="metric__tag">{{ tag }}</span>
    </div>
    <div class="metric__value fs-mono">
      {{ value }}<span v-if="unit" class="metric__unit">{{ unit }}</span>
    </div>
    <div class="metric__foot">
      <span v-if="hint" class="metric__hint">{{ hint }}</span>
      <span class="metric__spark" :style="{ width: `${barPercent}%` }" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    unit?: string
    hint?: string
    tag?: string
    tone?: 'accent' | 'good' | 'hard' | 'again' | 'violet'
    barPercent?: number
  }>(),
  { tone: 'accent', barPercent: 0 },
)

const barPercent = computed(() => Math.max(0, Math.min(100, props.barPercent ?? 0)))
</script>

<style scoped>
.metric {
  position: relative;
  overflow: hidden;
  padding: 14px 16px 12px;
  border-radius: var(--fs-radius);
  border: 1px solid var(--fs-line);
  background: linear-gradient(180deg, rgba(20, 31, 51, 0.92), rgba(12, 19, 33, 0.92));
  box-shadow: var(--fs-shadow);
}

.metric::after {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 2px;
  background: var(--tone-color, var(--fs-accent));
  opacity: 0.85;
}

.metric--accent {
  --tone-color: var(--fs-accent);
}
.metric--good {
  --tone-color: var(--fs-good);
}
.metric--hard {
  --tone-color: var(--fs-hard);
}
.metric--again {
  --tone-color: var(--fs-again);
}
.metric--violet {
  --tone-color: var(--fs-accent-2);
}

.metric__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.metric__label {
  font-size: 12px;
  color: var(--fs-text-2);
  letter-spacing: 0.3px;
}

.metric__tag {
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  color: var(--tone-color, var(--fs-accent));
  background: color-mix(in srgb, var(--tone-color, var(--fs-accent)) 16%, transparent);
  border: 1px solid color-mix(in srgb, var(--tone-color, var(--fs-accent)) 38%, transparent);
}

.metric__value {
  margin-top: 6px;
  font-size: 27px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--fs-text-0);
  letter-spacing: 0.4px;
}

.metric__unit {
  font-size: 13px;
  margin-left: 4px;
  color: var(--fs-text-2);
  font-family: inherit;
}

.metric__foot {
  margin-top: 9px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.metric__hint {
  font-size: 11.5px;
  color: var(--fs-text-2);
}

.metric__spark {
  display: block;
  height: 3px;
  border-radius: 3px;
  background: var(--tone-color, var(--fs-accent));
  opacity: 0.65;
  transition: width 0.45s ease;
}
</style>
