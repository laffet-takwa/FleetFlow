<script setup lang="ts">
import { useToastStore } from '@/stores/toastStore'

/**
 * Toast stack.
 *
 * `aria-live="polite"` so a message is announced without interrupting whatever the user
 * is doing, and every toast carries its own dismiss control rather than relying on the
 * timeout alone.
 */
const toasts = useToastStore()
</script>

<template>
  <Teleport to="body">
    <div
      class="pointer-events-none fixed inset-x-0 bottom-0 z-50 flex flex-col items-center gap-2 p-4 sm:inset-x-auto sm:right-0 sm:top-0 sm:items-end"
      role="region"
      aria-label="Notifications"
    >
      <TransitionGroup
        enterActiveClass="transition duration-150 ease-out"
        enterFromClass="translate-y-2 opacity-0"
        leaveActiveClass="transition duration-150 ease-in"
        leaveToClass="translate-y-1 opacity-0"
      >
        <div
          v-for="toast in toasts.toasts"
          :key="toast.id"
          class="pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-card border bg-surface p-3.5 shadow-raised dark:bg-[#111827]"
          :class="{
            'border-emerald-200 dark:border-emerald-800': toast.tone === 'success',
            'border-red-200 dark:border-red-800': toast.tone === 'danger',
            'border-amber-200 dark:border-amber-800': toast.tone === 'warning',
            'border-sky-200 dark:border-sky-800': toast.tone === 'info',
          }"
          role="status"
          aria-live="polite"
        >
          <span
            class="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center text-small font-semibold"
            :class="{
              'text-success': toast.tone === 'success',
              'text-danger': toast.tone === 'danger',
              'text-warning': toast.tone === 'warning',
              'text-info': toast.tone === 'info',
            }"
            aria-hidden="true"
          >
            {{ toast.tone === 'success' ? '✓' : toast.tone === 'danger' ? '✕' : toast.tone === 'warning' ? '▲' : 'ⓘ' }}
          </span>

          <div class="min-w-0 flex-1">
            <p class="text-small font-semibold text-content dark:text-[#F8FAFC]">{{ toast.title }}</p>
            <p
              v-if="toast.description"
              class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]"
            >
              {{ toast.description }}
            </p>
          </div>

          <button
            type="button"
            class="-m-1 shrink-0 rounded p-1 text-content-subtle transition-colors hover:text-content dark:hover:text-[#F8FAFC]"
            :aria-label="`Dismiss: ${toast.title}`"
            @click="toasts.dismiss(toast.id)"
          >
            <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="m6 6 12 12M18 6 6 18" stroke-linecap="round" />
            </svg>
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>