<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TrackingMap from '@/components/map/TrackingMap.vue'
import { useTrackingStore } from '@/stores/trackingStore'
import type { SseStatus } from '@/services/sse'
import { deliveryLabel, formatRelative } from '@/utils/format'
import { deliveryStatus } from '@/utils/status'

/**
 * Live operations map.
 *
 * The active list is polled rather than streamed: the SSE channel is reserved for the one
 * delivery the operator has selected, which is the only one the map can meaningfully show.
 */

const POLL_INTERVAL_MS = 15_000

const CONNECTION_META: Record<SseStatus, { label: string; dot: string }> = {
  connecting: { label: 'Connecting', dot: 'bg-warning' },
  open: { label: 'Live', dot: 'bg-success' },
  closed: { label: 'Stream closed', dot: 'bg-slate-400' },
  error: { label: 'Stream error', dot: 'bg-danger' },
}

const route = useRoute()
const tracking = useTrackingStore()

const selectedId = ref<number | null>(null)
const map = ref<InstanceType<typeof TrackingMap> | null>(null)

let pollTimer: number | undefined

const selected = computed(
  () => tracking.activeDeliveries.find((entry) => entry.deliveryId === selectedId.value) ?? null,
)

const connection = computed(() => CONNECTION_META[tracking.connection])

const mapLocations = computed(() => tracking.history)

const lastUpdate = computed(() => {
  const value = tracking.lastUpdated ?? selected.value?.lastLocationAt ?? null
  return {
    text: formatRelative(value),
    stale: tracking.isStale,
  }
})

const onlineCount = computed(
  () => tracking.activeDeliveries.filter((entry) => entry.online).length,
)

async function selectDelivery(deliveryId: number): Promise<void> {
  selectedId.value = deliveryId
  await Promise.all([tracking.fetchLatest(deliveryId), tracking.fetchHistory(deliveryId)])
  tracking.subscribe(deliveryId)
}

function clearSelection(): void {
  selectedId.value = null
  tracking.reset()
}

function onMapReady(): void {
  void nextTick(() => map.value?.fitAll())
}

watch(selectedId, (deliveryId) => {
  if (deliveryId === null) {
    map.value?.fitAll()
  }
})

onMounted(async () => {
  await tracking.fetchActive()

  const fromQuery = typeof route.query.delivery === 'string' ? Number(route.query.delivery) : Number.NaN
  if (Number.isFinite(fromQuery) && tracking.activeDeliveries.some((e) => e.deliveryId === fromQuery)) {
    await selectDelivery(fromQuery)
  }

  // Keeps the list honest when a driver goes offline without the stream noticing.
  pollTimer = window.setInterval(() => {
    void tracking.fetchActive()
  }, POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (pollTimer !== undefined) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
  tracking.unsubscribe()
})
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <p class="flex items-center gap-2 text-small text-content-muted dark:text-[#94A3B8]">
        <span class="h-2 w-2 shrink-0 rounded-full" :class="connection.dot" aria-hidden="true" />
        <span>{{ connection.label }}</span>
        <span v-if="selectedId !== null" class="text-content-subtle dark:text-[#64748B]">
          · stream for delivery #{{ selectedId }}
        </span>
      </p>
      <BaseButton
        v-if="selectedId !== null"
        size="sm"
        variant="secondary"
        @click="map?.fitAll()"
      >
        Fit the map
      </BaseButton>
    </div>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-[320px,1fr]">
      <BaseCard
title="Active deliveries"
        :subtitle="`${tracking.activeDeliveries.length} tracked, ${onlineCount} reporting`"
        :padded="false"
      >
        <BaseSkeleton v-if="tracking.loading" variant="lines" :rows="5" label="Loading tracked deliveries" />

        <ErrorState
          v-else-if="tracking.error"
          :message="tracking.error"
          retryLabel="Try again"
          @retry="tracking.fetchActive()"
        />

        <ul v-else-if="tracking.activeDeliveries.length > 0" class="max-h-[420px] overflow-y-auto">
          <li v-for="entry in tracking.activeDeliveries" :key="entry.deliveryId">
            <button
              type="button"
              class="flex w-full flex-col items-start gap-1 border-b border-edge px-5 py-3 text-left transition-colors duration-150 last:border-b-0 hover:bg-surface-muted dark:border-[#1E293B] dark:hover:bg-[#1A2436]"
              :class="selectedId === entry.deliveryId ? 'bg-blue-50 dark:bg-blue-950/50' : ''"
              :aria-pressed="selectedId === entry.deliveryId"
              @click="selectDelivery(entry.deliveryId)"
            >
              <span class="flex w-full items-center justify-between gap-2">
                <span class="font-mono text-small font-medium text-content dark:text-[#F8FAFC]">
                  {{ deliveryLabel(entry.deliveryId) }}
                </span>
                <StatusBadge :meta="deliveryStatus(entry.status)" />
              </span>
              <span class="w-full truncate text-body text-content-muted dark:text-[#94A3B8]">
                {{ entry.driverName ?? 'No driver assigned' }}
              </span>
              <span class="w-full truncate text-small text-content-subtle dark:text-[#64748B]">
                {{ entry.destination ?? entry.city ?? 'No destination recorded' }}
              </span>
            </button>
          </li>
        </ul>

        <EmptyState
          v-else
          title="Nothing is being tracked"
          description="Active deliveries appear here as soon as a driver reports a position."
          icon="deliveries"
        />
      </BaseCard>

      <div class="space-y-4">
        <TrackingMap
          ref="map"
          :locations="mapLocations"
          :follow="selectedId !== null"
          :loading="tracking.loading && tracking.activeDeliveries.length === 0"
          :height="selectedId !== null ? '440px' : '340px'"
          @ready="onMapReady"
        />

        <BaseCard :padded="false">
          <div class="px-5 py-4">
            <template v-if="selected">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <h2 class="card-title">Delivery #{{ selected.deliveryId }}</h2>
                  <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                    Order #{{ selected.orderId }} · {{ selected.driverName ?? 'No driver assigned' }}
                  </p>
                </div>
                <StatusBadge :meta="deliveryStatus(selected.status)" size="md" />
              </div>

              <dl class="mt-3.5 grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-3">
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Driver</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    {{ selected.driverName ?? 'Not assigned' }}
                  </dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Destination</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    {{ selected.destination ?? selected.city ?? 'Not recorded' }}
                  </dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Last update</dt>
                  <dd class="flex items-center gap-1.5 text-body text-content dark:text-[#F8FAFC]">
                    {{ lastUpdate.text }}
                    <span
                      v-if="lastUpdate.stale"
                      class="rounded-full bg-amber-50 px-1.5 py-0.5 text-small font-medium text-amber-800 dark:bg-amber-950 dark:text-amber-300"
                    >
                      stale
                    </span>
                  </dd>
                </div>
              </dl>

              <p v-if="selected.latestLocation" class="mt-3 text-small text-content-muted dark:text-[#94A3B8]">
                {{ tracking.history.length }}
                {{ tracking.history.length === 1 ? 'position' : 'positions' }} on record
                <template v-if="selected.latestLocation.speedKph">
                  · {{ selected.latestLocation.speedKph }} km/h
                </template>
              </p>

              <div class="mt-3">
                <BaseButton size="sm" variant="ghost" @click="clearSelection">Stop following</BaseButton>
              </div>
            </template>

            <template v-else>
              <h2 class="card-title">No delivery selected</h2>
              <p class="mt-1 text-body text-content-muted dark:text-[#94A3B8]">
                Pick a delivery from the list to follow its driver on the map. The list refreshes
                every 15 seconds; the live stream only runs for the selected delivery.
              </p>
            </template>
          </div>
        </BaseCard>
      </div>
    </div>
  </div>
</template>