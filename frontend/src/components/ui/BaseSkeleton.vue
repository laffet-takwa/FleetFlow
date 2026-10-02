<script setup lang="ts">
import { computed } from 'vue'

/** The five states every data-backed view needs before it shows a record. */
withDefaults(
  defineProps<{
    /** Number of skeleton rows to draw. */
    rows?: number
    variant?: 'table' | 'cards' | 'lines'
    label?: string
  }>(),
  { rows: 6, variant: 'table', label: 'Loading…' },
)

const heights = computed(() => ['h-4', 'h-4', 'h-4', 'h-3', 'h-4', 'h-4', 'h-3', 'h-4'])
</script>

<template>
  <div role="status" aria-live="polite" :aria-label="label">
    <span class="sr-only">{{ label }}</span>

    <div v-if="variant === 'table'" class="divide-y divide-edge dark:divide-[#1E293B]">
      <div v-for="index in rows" :key="index" class="flex items-center gap-4 px-5 py-3.5">
        <div class="skeleton h-4 w-16" />
        <div class="skeleton h-4 flex-1" />
        <div class="skeleton h-4 w-24" />
        <div class="skeleton h-6 w-24 rounded-full" />
        <div class="skeleton h-4 w-16" />
      </div>
    </div>

    <div v-else-if="variant === 'cards'" class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      <div v-for="index in rows" :key="index" class="card p-5">
        <div class="skeleton mb-3 h-4 w-24" />
        <div class="skeleton mb-2 h-3 w-full" />
        <div class="skeleton h-3 w-2/3" />
        <div class="skeleton mt-4 h-9 w-28 rounded-control" />
      </div>
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="index in rows"
        :key="index"
        class="skeleton w-full"
        :class="heights[index % heights.length]"
      />
    </div>
  </div>
</template>