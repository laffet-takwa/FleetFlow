import { get, post } from './api'
import type {
  LocationResponse,
  LocationUpdateRequest,
  TrackingHistoryResponse,
  TrackedDeliveryResponse,
} from '@/types'

export const trackingApi = {
  /** Accepts one position fix. The driver simulation posts here on a timer. */
  publishLocation: (body: LocationUpdateRequest) =>
    post<LocationResponse>('/tracking/locations', body),

  latest: (deliveryId: number, signal?: AbortSignal) =>
    get<LocationResponse>(`/tracking/${deliveryId}/latest`, undefined, { signal }),

  history: (deliveryId: number, limit = 200, signal?: AbortSignal) =>
    get<TrackingHistoryResponse>(`/tracking/${deliveryId}/history`, { limit }, { signal }),

  status: (deliveryId: number, signal?: AbortSignal) =>
    get<TrackedDeliveryResponse>(`/tracking/${deliveryId}/status`, undefined, { signal }),

  active: (signal?: AbortSignal) => get<TrackedDeliveryResponse[]>('/tracking/active', undefined, { signal }),

  /** Server-Sent Events endpoint consumed through the fetch-based SSE client. */
  streamPath: (deliveryId: number) => `/tracking/${deliveryId}/stream`,
}