<script setup lang="ts">
import { computed } from 'vue'

/**
 * Native select styled to match the inputs.
 *
 * A native element is deliberate: it gives keyboard navigation, type-ahead and the
 * platform picker on mobile for free, none of which a custom listbox reproduces well.
 */
const props = withDefaults(
  defineProps<{
    modelValue: string | number | null
    label: string
    options: { value: string | number; label: string; disabled?: boolean }[]
    placeholder?: string
    error?: string
    disabled?: boolean
    required?: boolean
    clearable?: boolean
  }>(),
  { placeholder: '', error: '', disabled: false, required: false, clearable: false },
)

const emit = defineEmits<{ 'update:modelValue': [string | number | null] }>()

const isEmpty = computed(() => props.modelValue === null || props.modelValue === '')
</script>

<template>
  <div class="space-y-1.5">
    <label class="block text-small font-medium text-content dark:text-[#F8FAFC]">
      {{ label }}
      <span v-if="required" class="text-danger" aria-hidden="true">*</span>
    </label>

    <div class="relative">
      <select
        :value="modelValue ?? ''"
        :disabled="disabled"
        :required="required"
        :aria-invalid="Boolean(error)"
        class="h-10 w-full appearance-none rounded-control border bg-surface px-3 pr-9 text-body text-content transition-colors duration-150 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:cursor-not-allowed disabled:bg-surface-muted dark:bg-[#0F172A] dark:text-[#F8FAFC] dark:disabled:bg-[#1E293B]"
        :class="error ? 'border-danger' : 'border-edge dark:border-[#334155]'"
        @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
      >
        <option v-if="placeholder || clearable" value="">
          {{ placeholder || 'All' }}
        </option>
        <option
          v-for="option in options"
          :key="option.value"
          :value="option.value"
          :disabled="option.disabled"
        >
          {{ option.label }}
        </option>
      </select>
      <svg
        class="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-content-subtle"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        aria-hidden="true"
      >
        <path d="m6 9 6 6 6-6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>

    <p v-if="error" class="text-small text-danger" role="alert">{{ error }}</p>
    <p v-else-if="isEmpty && placeholder" class="text-small text-content-muted dark:text-[#94A3B8]">
      Currently showing all entries.
    </p>
  </div>
</template>