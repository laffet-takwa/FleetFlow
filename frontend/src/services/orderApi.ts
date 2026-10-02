import { get, patch, post } from './api'
import type {
  CancelOrderRequest,
  CreateOrderRequest,
  DailyOrderCount,
  OrderKpiResponse,
  OrderQuery,
  OrderResponse,
  OrderStatus,
  PageResponse,
} from '@/types'

export const orderApi = {
  list: (query: OrderQuery, signal?: AbortSignal) =>
    get<PageResponse<OrderResponse>>('/orders', query as Record<string, unknown>, { signal }),

  get: (id: number, signal?: AbortSignal) =>
    get<OrderResponse>(`/orders/${id}`, undefined, { signal }),

  create: (body: CreateOrderRequest) => post<OrderResponse>('/orders', body),

  cancel: (id: number, body?: CancelOrderRequest) => post<OrderResponse>(`/orders/${id}/cancel`, body),

  updateStatus: (id: number, status: OrderStatus, note?: string) =>
    patch<OrderResponse>(`/orders/${id}/status`, { status, note }),

  kpi: (signal?: AbortSignal) => get<OrderKpiResponse>('/orders/kpi', undefined, { signal }),

  dailyAnalytics: (days = 14, signal?: AbortSignal) =>
    get<DailyOrderCount[]>('/orders/analytics/daily', { days }, { signal }),
}