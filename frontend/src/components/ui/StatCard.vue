<script setup lang="ts">
import BaseCard from './BaseCard.vue'

/**
 * KPI card for the operations dashboard.
 *
 * A metric without context is noise, so every card carries a supporting line in
 * addition to the value. There is deliberately no sparkline: the dashboard's job is to
 * answer "how is it going right now", and the analytics screen is where trends belong.
 */
withDefaults(
  defineProps<{
    label: string
    value: string | number
    hint?: string
    icon: 'orders' | 'truck' | 'check' | 'clock' | 'alert' | 'users'
    tone?: 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'
    loading?: boolean
  }>(),
  { hint: '', tone: 'neutral', loading: false },
)

const TONE_CLASS = {
  neutral: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  primary: 'bg-blue-50 text-primary dark:bg-blue-950 dark:text-blue-300',
  success: 'bg-emerald-50 text-success dark:bg-emerald-950 dark:text-emerald-300',
  warning: 'bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300',
  danger: 'bg-red-50 text-danger dark:bg-red-950 dark:text-red-300',
  info: 'bg-sky-50 text-info dark:bg-sky-950 dark:text-sky-300',
}
</script>

<template>
  <BaseCard class="flex items-start gap-4">
    <div
      class="flex h-10 w-10 shrink-0 items-center justify-center rounded-control"
      :class="TONE_CLASS[tone]"
      aria-hidden="true"
    >
      <svg v-if="icon === 'orders'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M6 3h9l4 4v14H6z" stroke-linejoin="round" />
        <path d="M15 3v4h4M9 12h6M9 16h4" stroke-linecap="round" />
      </svg>
      <svg v-else-if="icon === 'truck'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M3 7h10v9H3zM13 10h4l3 3v3h-7z" stroke-linejoin="round" />
        <circle cx="7" cy="18" r="1.6" />
        <circle cx="17" cy="18" r="1.6" />
      </svg>
      <svg v-else-if="icon === 'check'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <circle cx="12" cy="12" r="8.5" />
        <path d="m8.5 12 2.5 2.5 4.5-5" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <svg v-else-if="icon === 'clock'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <circle cx="12" cy="12" r="8.5" />
        <path d="M12 7.5V12l3 2" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <svg v-else-if="icon === 'alert'" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M12 8v5" stroke-linecap="round" />
        <circle cx="12" cy="16.5" r="0.7" fill="currentColor" />
        <path d="M10.3 3.8 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" stroke-linejoin="round" />
      </svg>
      <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <circle cx="9" cy="8" r="3.2" />
        <path d="M3.5 19a5.5 5.5 0 0 1 11 0M16 11.5a2.7 2.7 0 0 1 0 5.2M18 19h3" stroke-linecap="round" />
      </svg>
    </div>

    <div class="min-w-0 flex-1">
      <p class="text-small font-medium text-content-muted dark:text-[#94A3B8]">{{ label }}</p>
      <div v-if="loading" class="skeleton mt-1.5 h-7 w-16" />
      <p
        v-else
        class="mt-0.5 text-page-title font-semibold leading-7 tracking-[-0.01em] text-content dark:text-[#F8FAFC]"
      >
        {{ value }}
      </p>
      <p v-if="hint && !loading" class="mt-0.5 truncate text-small text-content-subtle dark:text-[#64748B]">
        {{ hint }}
      </p>
    </div>
  </BaseCard>
</template>