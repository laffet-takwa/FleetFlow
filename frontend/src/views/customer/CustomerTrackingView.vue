<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TrackingMap from '@/components/map/TrackingMap.vue'
import { ApiError } from '@/services/api'
import { deliveryApi } from '@/services/deliveryApi'
import { orderApi } from '@/services/orderApi'
import { useTrackingStore } from '@/stores/trackingStore'
import type { SseStatus } from '@/services/sse'
import type { DeliveryResponse, LocationResponse, OrderResponse, OrderStatus } from '@/types'
import {
  deliveryLabel,
  formatDateTime,
  formatMoney,
  formatRelative,
  orderLabel,
  pluralize,
} from '@/utils/format'
import { deliveryStatus, orderStatus } from '@/utils/status'

/**
 * Live tracking for one of the customer's own deliveries.
 *
 * The route carries a delivery id, so delivery-service is read first: it is the only
 * service that knows the driver, the vehicle and which order this delivery belongs to.
 * The order then supplies the timeline and the address, and the tracking store supplies
 * the positions — a REST snapshot first so the map is never blank, then SSE for the rest.
 */

const LIFECYCLE: OrderStatus[] = [
  'CREATED',
  'CONFIRMED',
  'PROCESSING',
  'READY_FOR_DELIVERY',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
]

/** Terminal states: the platform will never stream another position for these. */
const TERMINAL_DELIVERY = ['DELIVERED', 'FAILED', 'CANCELLED']

const CONNECTION_META: Record<SseStatus, { label: string; dot: string; glyph: string }> = {
  connecting: { label: 'Connecting…', dot: 'bg-warning', glyph: '◔' },
  open: { label: 'Live', dot: 'bg-success', glyph: '●' },
  closed: { label: 'Offline', dot: 'bg-slate-400', glyph: '○' },
  error: { label: 'Connection problem', dot: 'bg-danger', glyph: '✕' },
}

type TimelineState = 'done' | 'current' | 'pending'

interface TimelineStep {
  key: string
  status: OrderStatus
  state: TimelineState
  changedAt?: string
}

const route = useRoute()
const tracking = useTrackingStore()

const deliveryId = computed(() => Number(route.params.deliveryId))
const map = ref<InstanceType<typeof TrackingMap> | null>(null)
const follow = ref(false)

const order = ref<OrderResponse | null>(null)
const delivery = ref<DeliveryResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const inaccessible = ref(false)

const mapLocations = computed<LocationResponse[]>(() => {
  const points = [...tracking.history]
  const latest = tracking.latest
  // The Redis snapshot can be newer than the Mongo trail that was read first.
  if (latest && points[points.length - 1]?.receivedAt !== latest.receivedAt) {
    points.push(latest)
  }
  return points
})

const lastUpdate = computed(() => ({
  text: formatRelative(tracking.lastUpdated),
  stale: tracking.isStale,
}))

const hasPositions = computed(() => mapLocations.value.length > 0)

/** The platform answers 409 for a delivery it will not stream; that is a state, not a fault. */
const streamRefused = computed(
  () => tracking.connection === 'error' && /\b409\b/.test(tracking.connectionDetail ?? ''),
)

const trackingUnavailable = computed(
  () =>
    streamRefused.value ||
    (delivery.value !== null && TERMINAL_DELIVERY.includes(delivery.value.status)),
)

const connection = computed(() => {
  if (trackingUnavailable.value) {
    // There is no stream to be offline from: the delivery will never report again.
    return { label: 'Tracking finished', dot: 'bg-slate-400', glyph: '✓' }
  }
  const reconnecting =
    tracking.connection === 'connecting' && /reconnect/i.test(tracking.connectionDetail ?? '')
  return reconnecting
    ? { label: 'Reconnecting…', dot: 'bg-warning', glyph: '◔' }
    : CONNECTION_META[tracking.connection]
})

const steps = computed<TimelineStep[]>(() => {
  const current = order.value
  if (!current) {
    return []
  }

  const byStatus = new Map(current.timeline.map((entry) => [entry.status, entry]))

  if (current.status === 'CANCELLED') {
    // A cancelled order never walks the forward path, so its recorded history is the stepper.
    return current.timeline.map((entry, index) => ({
      key: `${entry.status}-${index}`,
      status: entry.status,
      state: index === current.timeline.length - 1 ? 'current' : 'done',
      changedAt: entry.changedAt,
    }))
  }

  const currentIndex = LIFECYCLE.indexOf(current.status)
  return LIFECYCLE.map((status, index) => ({
    key: status,
    status,
    state: index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'pending',
    changedAt: byStatus.get(status)?.changedAt,
  }))
})

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  inaccessible.value = false

  try {
    const loadedDelivery = await deliveryApi.get(deliveryId.value)
    delivery.value = loadedDelivery
    order.value = await orderApi.get(loadedDelivery.orderId)
  } catch (caught) {
    order.value = null
    delivery.value = null
    // Another account's delivery is a 403 and a removed one a 404: both mean "not yours to
    // see", never a server fault worth showing as an error.
    if (caught instanceof ApiError && (caught.isNotFound || caught.isForbidden)) {
      inaccessible.value = true
    } else {
      error.value =
        caught instanceof ApiError ? caught.message : 'This delivery could not be loaded.'
    }
  } finally {
    loading.value = false
  }

  if (order.value === null) {
    return
  }

  tracking.reset()
  await Promise.all([
    tracking.fetchLatest(deliveryId.value),
    tracking.fetchHistory(deliveryId.value),
  ])
  // The trail only exists after the REST calls land, so the first fit has to wait for it.
  await nextTick()
  map.value?.fitAll()

  // A delivery the platform will not stream is answered 409; opening the stream anyway
  // would only spin the client's reconnect backoff against a refusal.
  if (!trackingUnavailable.value) {
    tracking.subscribe(deliveryId.value)
  }
}

function toggleFollow(): void {
  follow.value = !follow.value
  if (!follow.value) {
    map.value?.fitAll()
  }
}

function onMapReady(): void {
  if (hasPositions.value) {
    map.value?.fitAll()
  }
}

onMounted(async () => {
  if (!Number.isFinite(deliveryId.value)) {
    error.value = 'That delivery id is not valid.'
    loading.value = false
    return
  }
  await load()
})

onBeforeUnmount(() => {
  tracking.unsubscribe()
})
</script>

<template>
  <div class="space-y-4">
    <RouterLink
      :to="{ name: 'customer-orders' }"
      class="inline-flex items-center gap-1.5 text-body font-medium text-primary hover:underline dark:text-blue-300"
    >
      <svg
        class="h-4 w-4"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.8"
        aria-hidden="true"
      >
        <path d="M19 12H5M11 6l-6 6 6 6" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      Back to orders
    </RouterLink>

    <BaseCard v-if="loading" :padded="false" as="section">
      <BaseSkeleton class="px-5 py-5" variant="lines" :rows="7" label="Loading the delivery" />
    </BaseCard>

    <BaseCard v-else-if="error" :padded="false" as="section">
      <ErrorState :message="error" retryLabel="Try again" @retry="load" />
    </BaseCard>

    <BaseCard v-else-if="inaccessible" :padded="false" as="section">
      <div class="px-6 py-12 text-center">
        <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
          This delivery is not available
        </p>
        <p class="mx-auto mt-1.5 max-w-md text-body text-content-muted dark:text-[#94A3B8]">
          It may have been removed, or it belongs to another account. Deliveries for your own
          orders are always listed under your account.
        </p>
        <RouterLink
          :to="{ name: 'customer-orders' }"
          class="mt-5 inline-flex h-10 items-center justify-center rounded-control bg-primary px-4 text-body font-medium text-white transition-colors duration-150 hover:bg-primary-dark"
        >
          Back to my orders
        </RouterLink>
      </div>
    </BaseCard>

    <template v-else-if="order">
      <header class="flex flex-wrap items-start justify-between gap-3">
        <div class="min-w-0">
          <div class="flex flex-wrap items-center gap-2.5">
            <h2 class="page-title">Order {{ orderLabel(order.id) }}</h2>
            <StatusBadge :meta="orderStatus(order.status)" size="md" />
          </div>
          <p class="mt-1 text-body text-content-muted dark:text-[#94A3B8]">
            Delivery {{ deliveryLabel(deliveryId) }} ·
            {{ pluralize(order.itemCount, 'item') }} ·
            {{ formatMoney(order.totalAmount, order.currency) }}
          </p>
        </div>

        <!-- Colour is never the only cue: the dot always travels with a glyph and a label. -->
        <p
          class="flex items-center gap-2 rounded-full bg-surface px-3 py-1.5 text-small ring-1 ring-inset ring-edge dark:bg-[#111827] dark:ring-[#334155]"
          aria-live="polite"
        >
          <span class="h-2 w-2 shrink-0 rounded-full" :class="connection.dot" aria-hidden="true" />
          <span class="text-content-muted dark:text-[#94A3B8]" aria-hidden="true">
            {{ connection.glyph }}
          </span>
          <span class="text-content dark:text-[#F8FAFC]">{{ connection.label }}</span>
        </p>
      </header>

      <div class="grid grid-cols-1 gap-4 lg:grid-cols-[1fr_320px]">
        <div class="min-w-0 space-y-3">
          <BaseCard
            v-if="trackingUnavailable"
            as="section"
            class="border-amber-200 dark:border-amber-800"
          >
            <div class="flex flex-col items-center gap-2 py-4 text-center">
              <span
                class="flex h-11 w-11 items-center justify-center rounded-full bg-amber-50 text-warning dark:bg-amber-950"
                aria-hidden="true"
              >
                <svg
                  class="h-5 w-5"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                >
                  <circle cx="12" cy="12" r="9" stroke-linejoin="round" />
                  <path d="M12 7.5V12l3 2" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
                Tracking is not available for this delivery yet
              </p>
              <p class="max-w-md text-body text-content-muted dark:text-[#94A3B8]">
                <template v-if="delivery && TERMINAL_DELIVERY.includes(delivery.status)">
                  This delivery is {{ deliveryStatus(delivery.status).label.toLowerCase() }}, so
                  the driver is no longer reporting positions. Everything that happened stays on
                  the order.
                </template>
                <template v-else>
                  Live positions open once the delivery is under way and the driver starts
                  reporting. The order below still shows how far it has got.
                </template>
              </p>
              <RouterLink
                :to="{ name: 'customer-order-detail', params: { id: order.id } }"
                class="mt-1 inline-flex h-10 items-center justify-center rounded-control bg-surface px-4 text-body font-medium text-content ring-1 ring-inset ring-edge transition-colors duration-150 hover:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:hover:bg-[#1E293B]"
              >
                Open the order
              </RouterLink>
            </div>
          </BaseCard>

          <template v-else>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <p class="text-small text-content-muted dark:text-[#94A3B8]">
                <template v-if="hasPositions">
                  {{ pluralize(mapLocations.length, 'position') }} on record
                  <template v-if="tracking.latest?.speedKph">
                    · {{ Math.round(tracking.latest.speedKph) }} km/h
                  </template>
                </template>
                <template v-else>Waiting for the driver's first position.</template>
              </p>

              <BaseButton
                size="sm"
                variant="secondary"
                @click="toggleFollow"
              >
                {{ follow ? 'Show the whole route' : 'Follow the driver' }}
              </BaseButton>
            </div>

            <!--
              destination is deliberately null: FleetFlow has no geocoder, and inventing a
              coordinate for the drop-off would put a confident pin in the wrong place.
            -->
            <TrackingMap
              ref="map"
              :locations="mapLocations"
              :follow="follow"
              height="420px"
              :destination="null"
              @ready="onMapReady"
            />

            <p
              v-if="tracking.error"
              class="rounded-control border border-red-200 bg-red-50 px-3 py-2.5 text-small text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
              role="alert"
            >
              {{ tracking.error }}
              <BaseButton size="sm" variant="secondary" class="mt-2" @click="load">
                Try again
              </BaseButton>
            </p>
          </template>
        </div>

        <div class="min-w-0 space-y-4">
          <BaseCard as="section" title="Delivery">
            <dl class="space-y-3">
              <div class="flex items-center justify-between gap-3">
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Driver</dt>
                <dd class="text-body text-content dark:text-[#F8FAFC]">
                  {{ delivery?.driverName ?? 'Not assigned yet' }}
                </dd>
              </div>
              <div class="flex items-center justify-between gap-3">
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Vehicle</dt>
                <dd class="font-mono text-body text-content dark:text-[#F8FAFC]">
                  {{ delivery?.vehicleRegistration ?? 'Not assigned yet' }}
                </dd>
              </div>
              <div class="flex items-center justify-between gap-3">
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Delivery status</dt>
                <dd>
                  <StatusBadge v-if="delivery" :meta="deliveryStatus(delivery.status)" />
                  <span v-else class="text-small text-content-subtle dark:text-[#64748B]">Unknown</span>
                </dd>
              </div>
              <div class="flex items-center justify-between gap-3">
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Last updated</dt>
                <dd class="flex flex-wrap items-center justify-end gap-2 text-body text-content dark:text-[#F8FAFC]">
                  <span>{{ lastUpdate.text }}</span>
                  <span
                    v-if="lastUpdate.stale && hasPositions"
                    class="rounded-full bg-amber-50 px-1.5 py-0.5 text-small font-medium text-amber-800 dark:bg-amber-950 dark:text-amber-300"
                  >
                    may be out of date
                  </span>
                </dd>
              </div>
            </dl>

            <!-- A div, not a p: <address> is block-level and a parser auto-closes a
                 <p> before it, which would leave this block's closing tag orphaned. -->
            <div
              v-if="order.deliveryAddress"
              class="mt-4 border-t border-edge pt-3 dark:border-[#334155]"
            >
              <span class="block text-small text-content-muted dark:text-[#94A3B8]">
                Drop-off address
              </span>
              <address class="mt-0.5 text-body not-italic text-content dark:text-[#F8FAFC]">
                {{ order.deliveryAddress }}<br />
                {{ order.city }} {{ order.postalCode }}
              </address>
            </div>
          </BaseCard>

          <BaseCard as="section" title="Progress">
            <ol>
              <li
                v-for="(step, index) in steps"
                :key="step.key"
                class="relative flex gap-3 pb-4 last:pb-0"
              >
                <span
                  v-if="index < steps.length - 1"
                  class="absolute left-[11px] top-6 h-full w-px bg-edge dark:bg-[#334155]"
                  aria-hidden="true"
                />
                <span
                  class="relative z-10 flex h-6 w-6 shrink-0 items-center justify-center rounded-full border text-[11px] font-semibold"
                  :class="[
                    step.state === 'done'
                      ? 'border-success bg-emerald-50 text-success dark:border-emerald-800 dark:bg-emerald-950 dark:text-emerald-300'
                      : step.state === 'current'
                        ? 'border-primary bg-blue-50 text-primary dark:border-blue-800 dark:bg-blue-950 dark:text-blue-300'
                        : 'border-edge bg-surface text-content-subtle dark:border-[#334155] dark:bg-[#0F172A] dark:text-[#64748B]',
                  ]"
                  aria-hidden="true"
                >
                  {{ step.state === 'done' ? '✓' : index + 1 }}
                </span>

                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-2">
                    <span
                      class="text-body font-medium"
                      :class="
                        step.state === 'pending'
                          ? 'text-content-subtle dark:text-[#64748B]'
                          : 'text-content dark:text-[#F8FAFC]'
                      "
                    >
                      {{ orderStatus(step.status).label }}
                    </span>
                    <span
                      v-if="step.state === 'current'"
                      class="text-small text-content-muted dark:text-[#94A3B8]"
                    >
                      current
                    </span>
                  </p>
                  <p
                    v-if="step.changedAt"
                    class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]"
                  >
                    {{ formatDateTime(step.changedAt) }}
                  </p>
                </div>
              </li>
            </ol>

            <RouterLink
              :to="{ name: 'customer-order-detail', params: { id: order.id } }"
              class="mt-4 inline-block text-small font-medium text-primary hover:underline dark:text-blue-300"
            >
              See the full order
            </RouterLink>
          </BaseCard>
        </div>
      </div>
    </template>
  </div>
</template>