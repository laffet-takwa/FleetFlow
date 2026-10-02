import { del, get, post } from './api'
import type { NotificationResponse, PageResponse } from '@/types'

export const notificationApi = {
  list: (query: { unreadOnly?: boolean; page?: number; size?: number }, signal?: AbortSignal) =>
    get<PageResponse<NotificationResponse>>('/notifications', query, { signal }),

  unreadCount: (signal?: AbortSignal) =>
    get<{ unreadCount: number }>('/notifications/unread-count', undefined, { signal }),

  markRead: (id: number) => post<void>(`/notifications/${id}/read`),

  markAllRead: () => post<void>('/notifications/read-all'),

  remove: (id: number) => del<void>(`/notifications/${id}`),

  /** Server-Sent Events endpoint consumed through the fetch-based SSE client. */
  streamPath: () => '/notifications/stream',
}