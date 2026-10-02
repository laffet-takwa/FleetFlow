import { ref } from 'vue'
import { defineStore } from 'pinia'

import type { Toast, ToastTone } from './types'

let nextId = 1

/**
 * Transient confirmations and failures.
 *
 * Toasts are announcements, not decoration: they are announced to assistive
 * technology, they never carry the only copy of an error (inline messages do that),
 * and they disappear on their own so nothing important depends on a user noticing one.
 */
export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([])

  function dismiss(id: number): void {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }

  function push(toast: Omit<Toast, 'id'>): number {
    const id = nextId++
    toasts.value = [...toasts.value, { ...toast, id }]
    window.setTimeout(() => dismiss(id), toast.duration ?? 5_000)
    return id
  }

  const success = (title: string, description?: string) =>
    push({ tone: 'success', title, description })

  const error = (title: string, description?: string) =>
    push({ tone: 'danger', title, description, duration: 7_000 })

  const warning = (title: string, description?: string) =>
    push({ tone: 'warning', title, description })

  const info = (title: string, description?: string) => push({ tone: 'info', title, description })

  function fromException(title: string, error: unknown, fallback?: string): void {
    const description =
      (error as { message?: string })?.message ?? fallback ?? 'The request could not be completed.'
    push({ tone: 'danger', title, description, duration: 7_000 })
  }

  function byTone(tone: ToastTone): Toast[] {
    return toasts.value.filter((toast) => toast.tone === tone)
  }

  return { toasts, push, success, error, warning, info, fromException, dismiss, byTone }
})