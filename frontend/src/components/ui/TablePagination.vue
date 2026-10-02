<script setup lang="ts">
import { computed } from 'vue'

import { pluralize } from '@/utils/format'

/**
 * Pagination.
 *
 * The current page is always rendered, with a window either side, and `aria-current`
 * marks the active page for screen readers rather than relying on the highlight alone.
 */
const props = withDefaults(
  defineProps<{
    page: number
    totalPages: number
    totalElements?: number
    size?: number
    label?: string
  }>(),
  { totalElements: 0, size: 0, label: 'results' },
)

const emit = defineEmits<{ change: [number] }>()

const pages = computed<(number | 'gap')[]>(() => {
  const total = props.totalPages
  const current = props.page
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index)
  }
  const window: (number | 'gap')[] = [0]
  const start = Math.max(1, current - 1)
  const end = Math.min(total - 2, current + 1)
  if (start > 1) {
    window.push('gap')
  }
  for (let index = start; index <= end; index += 1) {
    window.push(index)
  }
  if (end < total - 2) {
    window.push('gap')
  }
  window.push(total - 1)
  return window
})

const from = computed(() => (props.totalElements === 0 ? 0 : props.page * props.size + 1))
const to = computed(() => Math.min((props.page + 1) * props.size, props.totalElements))

const buttonClass =
  'flex h-8 min-w-8 items-center justify-center rounded-control px-2 text-small font-medium transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-40'
</script>

<template>
  <nav
    v-if="totalPages > 0"
    class="flex flex-col items-center justify-between gap-3 border-t border-edge px-5 py-3.5 sm:flex-row dark:border-[#334155]"
    aria-label="Pagination"
  >
    <p class="text-small text-content-muted dark:text-[#94A3B8]" aria-live="polite">
      <template v-if="totalElements > 0">
        Showing {{ from }}–{{ to }} of {{ pluralize(totalElements, label) }}
      </template>
      <template v-else>No {{ label }}</template>
    </p>

    <div v-if="totalPages > 1" class="flex items-center gap-1">
      <button
        type="button"
        :class="buttonClass"
        :disabled="page === 0"
        aria-label="Previous page"
        @click="emit('change', page - 1)"
      >
        <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="m14 6-6 6 6 6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <template v-for="(item, index) in pages" :key="`${item}-${index}`">
        <span v-if="item === 'gap'" class="px-1 text-content-subtle" aria-hidden="true">…</span>
        <button
          v-else
          type="button"
          :class="[
            buttonClass,
            item === page
              ? 'bg-primary text-white'
              : 'text-content-muted hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]',
          ]"
          :aria-current="item === page ? 'page' : undefined"
          @click="emit('change', item)"
        >
          {{ item + 1 }}
        </button>
      </template>

      <button
        type="button"
        :class="buttonClass"
        :disabled="page >= totalPages - 1"
        aria-label="Next page"
        @click="emit('change', page + 1)"
      >
        <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="m10 6 6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
    </div>
  </nav>
</template>