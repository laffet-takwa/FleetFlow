<script setup lang="ts">
import BaseButton from './BaseButton.vue'

/**
 * Shown when a request failed.
 *
 * The message comes from the API error contract when there is one, so the user sees the
 * server's own wording instead of a generic apology, and the correlation id is offered
 * for support. No stack trace ever reaches this component.
 */
defineProps<{
  title?: string
  message: string
  correlationId?: string
  retryLabel?: string
}>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="flex flex-col items-center justify-center px-6 py-12 text-center">
    <div
      class="mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-red-50 text-danger dark:bg-red-950"
      aria-hidden="true"
    >
      <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M12 8v5" stroke-linecap="round" />
        <circle cx="12" cy="16.5" r="0.6" fill="currentColor" />
        <path d="M10.3 3.8 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" stroke-linejoin="round" />
      </svg>
    </div>

    <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
      {{ title ?? 'Something went wrong' }}
    </p>
    <p class="mt-1.5 max-w-md text-body text-content-muted dark:text-[#94A3B8]">{{ message }}</p>

    <p
      v-if="correlationId"
      class="mt-3 font-mono text-small text-content-subtle dark:text-[#64748B]"
    >
      Reference: {{ correlationId }}
    </p>

    <BaseButton v-if="retryLabel !== ''" variant="primary" class="mt-5" @click="emit('retry')">
      {{ retryLabel ?? 'Try again' }}
    </BaseButton>
  </div>
</template>