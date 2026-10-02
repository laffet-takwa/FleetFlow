<script setup lang="ts">
import { computed, useId } from 'vue'

/**
 * Text input with a label, hint and error slot.
 *
 * The label is always rendered and always associated through `for`, and errors are
 * wired through `aria-describedby`, so a screen reader announces the problem with the
 * field rather than leaving it as orphaned red text.
 */
const props = withDefaults(
  defineProps<{
    modelValue: string | number | null
    label: string
    type?: 'text' | 'email' | 'password' | 'tel' | 'number' | 'date' | 'search'
    placeholder?: string
    hint?: string
    error?: string
    required?: boolean
    disabled?: boolean
    autocomplete?: string
    inputmode?: 'text' | 'numeric' | 'tel' | 'email' | 'search' | 'decimal'
    min?: number | string
    max?: number | string
    step?: number | string
  }>(),
  {
    type: 'text',
    placeholder: '',
    hint: '',
    error: '',
    required: false,
    disabled: false,
    autocomplete: undefined,
    inputmode: undefined,
    min: undefined,
    max: undefined,
    step: undefined,
  },
)

const emit = defineEmits<{ 'update:modelValue': [string | number | null]; blur: [FocusEvent] }>()

const id = useId()
const describedBy = computed(() => {
  const ids: string[] = []
  if (props.hint) {
    ids.push(`${id}-hint`)
  }
  if (props.error) {
    ids.push(`${id}-error`)
  }
  return ids.length > 0 ? ids.join(' ') : undefined
})
</script>

<template>
  <div class="space-y-1.5">
    <label :for="id" class="block text-small font-medium text-content dark:text-[#F8FAFC]">
      {{ label }}
      <span v-if="required" class="text-danger" aria-hidden="true">*</span>
    </label>

    <input
      :id="id"
      :type="type"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :disabled="disabled"
      :required="required"
      :autocomplete="autocomplete"
      :inputmode="inputmode"
      :min="min"
      :max="max"
      :step="step"
      :aria-invalid="Boolean(error)"
      :aria-describedby="describedBy"
      class="h-10 w-full rounded-control border bg-surface px-3 text-body text-content transition-colors duration-150 placeholder:text-content-subtle focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:cursor-not-allowed disabled:bg-surface-muted dark:bg-[#0F172A] dark:text-[#F8FAFC] dark:placeholder:text-[#64748B] dark:disabled:bg-[#1E293B]"
      :class="error ? 'border-danger focus:border-danger focus:ring-danger/30' : 'border-edge dark:border-[#334155]'"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      @blur="emit('blur', $event)"
    />

    <p v-if="error" :id="`${id}-error`" class="text-small text-danger" role="alert">
      {{ error }}
    </p>
    <p v-else-if="hint" :id="`${id}-hint`" class="text-small text-content-muted dark:text-[#94A3B8]">
      {{ hint }}
    </p>
  </div>
</template>