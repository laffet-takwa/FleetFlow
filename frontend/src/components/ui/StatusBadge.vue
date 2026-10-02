<script setup lang="ts">
import { computed } from 'vue'

import { toneClasses, type StatusMeta } from '@/utils/status'

/**
 * Status pill.
 *
 * Every badge renders a glyph, a label and a tone. The glyph and the label are what
 * carry the meaning; the colour only reinforces it, so the component stays readable in
 * monochrome and for a colour-blind user.
 */
const props = withDefaults(
  defineProps<{
    meta: StatusMeta
    size?: 'sm' | 'md'
    /** Shown after the label, e.g. a delivery id. */
    suffix?: string
  }>(),
  { size: 'sm', suffix: '' },
)

const classes = computed(() => [
  'inline-flex items-center gap-1.5 rounded-full font-medium ring-1 ring-inset whitespace-nowrap',
  props.size === 'sm' ? 'px-2 py-0.5 text-small' : 'px-2.5 py-1 text-body',
  toneClasses(props.meta.tone),
])
</script>

<template>
  <span :class="classes" :data-status="meta.tone">
    <span aria-hidden="true" class="leading-none">{{ meta.glyph }}</span>
    <span>{{ meta.label }}</span>
    <span v-if="suffix" class="opacity-70">{{ suffix }}</span>
  </span>
</template>