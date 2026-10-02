<script setup lang="ts">
import BaseButton from './BaseButton.vue'

/**
 * Shown when a query succeeded and legitimately returned nothing.
 *
 * A blank panel reads as a bug; this states what is missing, why, and what the user can
 * do about it. It is only ever rendered after a successful load, never during one.
 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    actionLabel?: string
    icon?: 'orders' | 'deliveries' | 'inventory' | 'notifications' | 'search'
  }>(),
  { description: '', actionLabel: '', icon: 'search' },
)

const emit = defineEmits<{ action: [] }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center px-6 py-14 text-center">
    <div
      class="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-surface-muted text-content-subtle dark:bg-[#1E293B] dark:text-[#64748B]"
      aria-hidden="true"
    >
      <svg v-if="icon === 'search'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" stroke-linecap="round" />
      </svg>
      <svg v-else-if="icon === 'orders'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M6 3h9l4 4v14H6z" stroke-linejoin="round" />
        <path d="M15 3v4h4M9 12h6M9 16h4" stroke-linecap="round" />
      </svg>
      <svg v-else-if="icon === 'deliveries'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M3 7h10v9H3zM13 10h4l3 3v3h-7z" stroke-linejoin="round" />
        <circle cx="7" cy="18" r="1.6" />
        <circle cx="17" cy="18" r="1.6" />
      </svg>
      <svg v-else-if="icon === 'inventory'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="m12 3 8 4.5v9L12 21l-8-4.5v-9z" stroke-linejoin="round" />
        <path d="M4 7.5 12 12l8-4.5M12 12v9" stroke-linejoin="round" />
      </svg>
      <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" stroke-linejoin="round" />
        <path d="M10.5 20a2 2 0 0 0 3 0" stroke-linecap="round" />
      </svg>
    </div>

    <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">{{ title }}</p>
    <p v-if="description" class="mt-1.5 max-w-sm text-body text-content-muted dark:text-[#94A3B8]">
      {{ description }}
    </p>

    <div v-if="actionLabel || $slots.default" class="mt-5">
      <BaseButton v-if="actionLabel" variant="primary" @click="emit('action')">
        {{ actionLabel }}
      </BaseButton>
      <slot v-else />
    </div>
  </div>
</template>