<template>
  <el-tooltip :content="text" placement="top" effect="dark" :show-after="80" :offset="8">
    <span class="term-hint" tabindex="0">
      <span v-if="term" class="term-hint__term">{{ term }}</span>
      <span class="term-hint__mark" aria-hidden="true">?</span>
      <span class="term-hint__sr">{{ term || '说明' }}</span>
    </span>
  </el-tooltip>
</template>

<script setup lang="ts">
/**
 * One-line plain-language gloss for a term, rendered as a small "?" the reader
 * can hover (or focus with the keyboard) when they meet the term for the first
 * time. Used for table headers such as 状态 / 稳定性 S / 到期 so that a
 * newcomer is never left guessing what a column means.
 */
defineProps<{
  /** The term itself, e.g. 「S（稳定性）」. Omit when the label already sits next to the mark. */
  term?: string
  /** The white-space explanation shown in the tooltip. */
  text: string
}>()
</script>

<style scoped>
.term-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: help;
  outline: none;
}

.term-hint__term {
  font-weight: 600;
}

.term-hint__mark {
  /* flex centring + a pinned line box, because `place-items: center` on an inline-grid
     does not shrink the anonymous text box: it inherits the surrounding line-height
     (table headers set 23px), which makes it taller than the 15px circle and pushes the
     "?" outside it. */
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 15px;
  height: 15px;
  line-height: 1;
  padding: 0;
  flex: none;
  border-radius: 50%;
  font-family: inherit;
  font-size: 10px;
  font-weight: 700;
  color: var(--fs-text-2);
  background: rgba(139, 155, 180, 0.16);
  border: 1px solid rgba(139, 155, 180, 0.32);
}

.term-hint:hover .term-hint__mark,
.term-hint:focus-visible .term-hint__mark {
  color: var(--fs-accent);
  border-color: rgba(76, 201, 240, 0.5);
  background: rgba(76, 201, 240, 0.16);
}

/* The tooltip carries the explanation; this text is only for screen readers. */
.term-hint__sr {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
</style>
