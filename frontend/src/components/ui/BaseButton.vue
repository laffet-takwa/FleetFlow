<script setup lang="ts">
import { computed } from 'vue'

/**
 * The single button in the application.
 *
 * Variants exist for the four things that actually happen in this product; there is no
 * `size="xl"` ladder, because a design system that offers every combination is a design
 * system nobody follows. Touch targets on the driver screens come from `touch` rather
 * than from oversized type.
 */
type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'sm' | 'md' | 'lg' | 'touch'

const props = withDefaults(
  defineProps<{
    variant?: Variant
    size?: Size
    type?: 'button' | 'submit'
    loading?: boolean
    disabled?: boolean
    fullWidth?: boolean
    loadingLabel?: string
  }>(),
  {
    variant: 'secondary',
    size: 'md',
    type: 'button',
    loading: false,
    disabled: false,
    fullWidth: false,
    loadingLabel: 'Working…',
  },
)

const emit = defineEmits<{ click: [MouseEvent] }>()

const VARIANT: Record<Variant, string> = {
  primary:
    'bg-primary text-white hover:bg-primary-dark active:bg-primary-dark disabled:bg-primary/50 shadow-card',
  secondary:
    'bg-surface text-content ring-1 ring-inset ring-edge hover:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:hover:bg-[#1E293B]',
  ghost:
    'bg-transparent text-content-muted hover:bg-surface-muted hover:text-content dark:text-[#94A3B8] dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]',
  danger: 'bg-danger text-white hover:bg-red-700 active:bg-red-800 disabled:bg-danger/50 shadow-card',
}

const SIZE: Record<Size, string> = {
  sm: 'h-8 px-3 text-small gap-1.5 rounded-control',
  md: 'h-10 px-4 text-body gap-2 rounded-control',
  lg: 'h-11 px-5 text-body gap-2 rounded-control',
  touch: 'h-14 px-5 text-body font-semibold gap-2.5 rounded-control',
}

const classes = computed(() => [
  'inline-flex items-center justify-center font-medium transition-colors duration-150 select-none',
  'disabled:cursor-not-allowed disabled:opacity-60',
  VARIANT[props.variant],
  SIZE[props.size],
  props.fullWidth ? 'w-full' : '',
])

const iconSize = computed(() => (props.size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4'))
</script>

<template>
  <button
    :type="type"
    :class="classes"
    :disabled="disabled || loading"
    :aria-busy="loading"
    @click="emit('click', $event)"
  >
    <svg
      v-if="loading"
      :class="iconSize"
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      class="animate-spin"
    >
      <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" opacity="0.25" />
      <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
    </svg>
    <slot v-else name="icon" :iconClass="iconSize" />
    <span>{{ loading ? loadingLabel : '' }}<slot v-if="!loading" /></span>
  </button>
</template>