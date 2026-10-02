import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { notificationApi } from '@/services/notificationApi'
import { ApiError } from '@/services/api'
import { openSseStream, type SseStream } from '@/services/sse'
import { useToastStore } from './toastStore'
import type { NotificationResponse } from '@/types'

const PAGE_SIZE = 20

/**
 * Notification centre state.
 *
 * The unread count is pushed over SSE rather than polled, so the header badge reacts the
 * moment an order moves forward, even when the user is looking at another screen.
 */
export const useNotificationStore = defineStore('notifications', () => {
  const toasts = useToastStore()

  const items = ref<NotificationResponse[]>([])
  const unreadCount = ref(0)
  const totalElements = ref(0)
  const totalPages = ref(0)
  const page = ref(0)
  const unreadOnly = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  let stream: SseStream | null = null

  const hasUnread = computed(() => unreadCount.value > 0)

  async function fetchNotifications(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await notificationApi.list({
        page: page.value,
        size: PAGE_SIZE,
        unreadOnly: unreadOnly.value,
      })
      items.value = result.content
      totalElements.value = result.totalElements
      totalPages.value = result.totalPages
      unreadCount.value = result.content.filter((item) => !item.read).length
      await refreshUnreadCount()
    } catch (caught) {
      error.value =
        caught instanceof ApiError ? caught.message : 'Unable to load notifications right now.'
    } finally {
      loading.value = false
    }
  }

  async function refreshUnreadCount(): Promise<void> {
    try {
      const result = await notificationApi.unreadCount()
      unreadCount.value = result.unreadCount
    } catch {
      // The badge is a nicety; a failed refresh must not disturb the page.
    }
  }

  async function markRead(id: number): Promise<void> {
    const target = items.value.find((item) => item.id === id)
    if (!target || target.read) {
      return
    }
    // Optimistic: the badge must not lag behind a click.
    target.read = true
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    try {
      await notificationApi.markRead(id)
    } catch {
      target.read = false
      unreadCount.value += 1
      toasts.error('Unable to mark the notification as read')
    }
  }

  async function markAllRead(): Promise<void> {
    items.value.forEach((item) => {
      item.read = true
    })
    unreadCount.value = 0
    try {
      await notificationApi.markAllRead()
      toasts.success('All notifications marked as read')
    } catch {
      await refreshUnreadCount()
      toasts.error('Unable to mark all notifications as read')
    }
  }

  async function remove(id: number): Promise<void> {
    const previous = [...items.value]
    items.value = items.value.filter((item) => item.id !== id)
    try {
      await notificationApi.remove(id)
    } catch {
      items.value = previous
      toasts.error('Unable to remove the notification')
    }
  }

  function subscribe(): void {
    if (stream) {
      return
    }
    stream = openSseStream(notificationApi.streamPath(), {
      onMessage: (message) => {
        if (message.event === 'notification') {
          const notification = message.data as NotificationResponse
          items.value = [notification, ...items.value].slice(0, 100)
          unreadCount.value += 1
        } else if (message.event === 'unread-count') {
          const payload = message.data as { unreadCount: number }
          unreadCount.value = payload?.unreadCount ?? 0
        }
      },
    })
  }

  function unsubscribe(): void {
    stream?.close()
    stream = null
  }

  function reset(): void {
    unsubscribe()
    items.value = []
    unreadCount.value = 0
    page.value = 0
    error.value = null
  }

  return {
    items,
    unreadCount,
    totalElements,
    totalPages,
    page,
    unreadOnly,
    loading,
    error,
    hasUnread,
    fetchNotifications,
    refreshUnreadCount,
    markRead,
    markAllRead,
    remove,
    subscribe,
    unsubscribe,
    reset,
  }
})