<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import BaseButton from './BaseButton.vue'

/**
 * Confirmation dialog for destructive actions.
 *
 * Cancelling is always the default focus and the default button, and Escape closes it,
 * so a dialog can never be dismissed by accident. `role="dialog"` plus a focus trap on
 * Tab keeps keyboard focus inside while it is open.
 */
const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    confirmLabel?: string
    cancelLabel?: string
    tone?: 'danger' | 'primary'
    busy?: boolean
  }>(),
  {
    description: '',
    confirmLabel: 'Confirm',
    cancelLabel: 'Keep',
    tone: 'danger',
    busy: false,
  },
)

const emit = defineEmits<{ confirm: []; cancel: [] }>()

const panel = ref<HTMLElement | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (!props.open) {
    return
  }
  if (event.key === 'Escape') {
    emit('cancel')
    return
  }
  if (event.key !== 'Tab' || !panel.value) {
    return
  }

  const focusable = panel.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
  )
  if (focusable.length === 0) {
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]

  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) {
      return
    }
    // Focus the safe choice, so a stray Enter cannot confirm a destructive action.
    await new Promise((resolve) => requestAnimationFrame(resolve))
    panel.value?.querySelector<HTMLElement>('[data-autofocus]')?.focus()
  },
)
</script>

<template>
  <Teleport to="body">
    <Transition
      enterActiveClass="transition-opacity duration-150"
      enterFromClass="opacity-0"
      leaveActiveClass="transition-opacity duration-150"
      leaveToClass="opacity-0"
    >
      <div
        v-if="open"
        class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
        @click.self="emit('cancel')"
      >
        <div
          ref="panel"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="`${$attrs.id ?? 'confirm-dialog'}-title`"
          class="w-full max-w-md rounded-card bg-surface p-5 shadow-overlay dark:bg-[#111827]"
        >
          <div class="flex gap-3.5">
            <div
              v-if="tone === 'danger'"
              class="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-50 text-danger dark:bg-red-950"
              aria-hidden="true"
            >
              <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 8v5" stroke-linecap="round" />
                <circle cx="12" cy="16.5" r="0.6" fill="currentColor" />
                <path d="M10.3 3.8 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" stroke-linejoin="round" />
              </svg>
            </div>
            <div class="min-w-0">
              <h2 :id="`${$attrs.id ?? 'confirm-dialog'}-title`" class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
                {{ title }}
              </h2>
              <p v-if="description" class="mt-1.5 text-body text-content-muted dark:text-[#94A3B8]">
                {{ description }}
              </p>
              <slot />
            </div>
          </div>

          <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <BaseButton data-autofocus variant="secondary" @click="emit('cancel')">
              {{ cancelLabel }}
            </BaseButton>
            <BaseButton
              :variant="tone === 'danger' ? 'danger' : 'primary'"
              :loading="busy"
              @click="emit('confirm')"
            >
              {{ confirmLabel }}
            </BaseButton>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>