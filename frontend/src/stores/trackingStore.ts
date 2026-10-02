import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'

import { trackingApi } from '@/services/trackingApi'
import { ApiError } from '@/services/api'
import { openSseStream, type SseStatus, type SseStream } from '@/services/sse'
import type { LocationResponse, TrackedDeliveryResponse } from '@/types'

/**
 * Live tracking state.
 *
 * The store owns exactly one SSE subscription at a time because a browser page can only
 * usefully follow one delivery map at a time; opening a second closes the first.
 * `latest` is mirrored from Redis by the backend, so the first paint of the map does not
 * wait for the stream to deliver something.
 */
export const useTrackingStore = defineStore('tracking', () => {
  const activeDeliveries = ref<TrackedDeliveryResponse[]>([])
  const latest = shallowRef<LocationResponse | null>(null)
  const history = ref<LocationResponse[]>([])
  const connection = ref<SseStatus>('closed')
  const connectionDetail = ref<string | null>(null)
  const lastUpdated = ref<string | null>(null)

  const loading = ref(false)
  const error = ref<string | null>(null)

  let stream: SseStream | null = null

  const isConnected = computed(() => connection.value === 'open')
  const isStale = computed(() => {
    if (!lastUpdated.value) {
      return true
    }
    return Date.now() - new Date(lastUpdated.value).getTime() > 120_000
  })

  async function fetchActive(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      activeDeliveries.value = await trackingApi.active()
    } catch (caught) {
      error.value =
        caught instanceof ApiError
          ? caught.message
          : 'Unable to load active deliveries right now.'
    } finally {
      loading.value = false
    }
  }

  async function fetchLatest(deliveryId: number): Promise<void> {
    try {
      latest.value = await trackingApi.latest(deliveryId)
      lastUpdated.value = latest.value?.receivedAt ?? null
    } catch (caught) {
      if (caught instanceof ApiError && caught.isNotFound) {
        latest.value = null
      } else {
        error.value = 'The driver has not reported a position yet.'
      }
    }
  }

  async function fetchHistory(deliveryId: number, limit = 200): Promise<void> {
    try {
      const result = await trackingApi.history(deliveryId, limit)
      history.value = result.locations
    } catch {
      history.value = []
    }
  }

  function applyLocation(location: LocationResponse): void {
    latest.value = location
    lastUpdated.value = location.receivedAt
    history.value = [...history.value, location].slice(-500)

    // Keep the operations list marker in step with the stream.
    const tracked = activeDeliveries.value.find((entry) => entry.deliveryId === location.deliveryId)
    if (tracked) {
      tracked.latestLocation = location
      tracked.lastLocationAt = location.receivedAt
      tracked.online = true
    }
  }

  /** Subscribes to the live stream for one delivery, replacing any previous subscription. */
  function subscribe(deliveryId: number): void {
    unsubscribe()
    stream = openSseStream<LocationResponse>(trackingApi.streamPath(deliveryId), {
      onStatus: (status, detail) => {
        connection.value = status
        connectionDetail.value = detail ?? null
      },
      onReconnecting: () => {
        connection.value = 'connecting'
        connectionDetail.value = 'Reconnecting to the live stream…'
      },
      onMessage: (message) => {
        if (message.event === 'location' && message.data) {
          applyLocation(message.data)
        }
      },
    })
  }

  function unsubscribe(): void {
    stream?.close()
    stream = null
    connection.value = 'closed'
    connectionDetail.value = null
  }

  function reset(): void {
    unsubscribe()
    latest.value = null
    history.value = []
    lastUpdated.value = null
    error.value = null
  }

  return {
    activeDeliveries,
    latest,
    history,
    connection,
    connectionDetail,
    lastUpdated,
    loading,
    error,
    isConnected,
    isStale,
    fetchActive,
    fetchLatest,
    fetchHistory,
    subscribe,
    unsubscribe,
    reset,
  }
})