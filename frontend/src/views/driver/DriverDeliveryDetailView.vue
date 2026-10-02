<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import TrackingMap from '@/components/map/TrackingMap.vue'
import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'

import { ApiError } from '@/services/api'
import { deliveryApi } from '@/services/deliveryApi'
import { trackingApi } from '@/services/trackingApi'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { useToastStore } from '@/stores/toastStore'
import { useTrackingStore } from '@/stores/trackingStore'
import { deliveryLabel, formatDateTime, formatRelative, orderLabel } from '@/utils/format'
import { deliveryStatus, nextDriverActions } from '@/utils/status'

import type { DeliveryResponse, DeliveryStatus, LocationResponse, VehicleType } from '@/types'

/** Only these two states accept a position fix; they are also the states that can fail. */
const LIVE_STATUSES: DeliveryStatus[] = ['PICKED_UP', 'IN_TRANSIT']

const TUNIS = { latitude: 36.8065, longitude: 10.1815 }
const METRES_PER_DEGREE_LAT = 111_320

const SIM_MIN_INTERVAL = 2
const SIM_MAX_INTERVAL = 10
const SIM_SPEED_KPH = 30
const SIM_BEARING_JITTER_DEGREES = 12

const VEHICLE_TYPE_LABEL: Record<VehicleType, string> = {
  VAN: 'Van',
  MOTORCYCLE: 'Motorcycle',
  TRUCK: 'Truck',
  CAR: 'Car',
}

const route = useRoute()
const delivery = useDeliveryStore()
const tracking = useTrackingStore()
const toasts = useToastStore()

const item = ref<DeliveryResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const actionError = ref<string | null>(null)
const busy = ref(false)
const pendingAction = ref<{ status: DeliveryStatus; label: string } | null>(null)
const reportOpen = ref(false)
const reportReason = ref('')
const reportTouched = ref(false)
const mapOpen = ref(false)

const simulating = ref(false)
const simInterval = ref<number | string>(3)
const simError = ref<string | null>(null)
const simSent = ref(0)
/** Positions posted by this screen, so the map moves even without a live stream. */
const postedPositions = ref<LocationResponse[]>([])

const mapLocations = computed(() => [...tracking.history, ...postedPositions.value])

let timer: number | null = null
let position: { latitude: number; longitude: number } | null = null
let bearing = 0

const deliveryId = computed(() => Number(route.params.id))

const statusMeta = computed(() => deliveryStatus(item.value?.status ?? 'CREATED'))

/** The single forward transition the backend allows from the current state, if any. */
const primaryAction = computed(() =>
  item.value ? nextDriverActions(item.value.status)[0] ?? null : null,
)

const isLive = computed(() => (item.value ? LIVE_STATUSES.includes(item.value.status) : false))

/** Human label for the state the confirm dialog is about to move the delivery into. */
const pendingTargetLabel = computed(() =>
  pendingAction.value ? deliveryStatus(pendingAction.value.status).label : '',
)

const closedNote = computed(() => {
  if (!item.value || primaryAction.value || LIVE_STATUSES.includes(item.value.status)) {
    return ''
  }
  return item.value.status === 'CREATED'
    ? 'Operations has not assigned a driver to this delivery yet.'
    : 'This delivery is closed — there is nothing left to do.'
})

const phone = computed(() => item.value?.customerPhone ?? '')
const telHref = computed(() => (phone.value ? `tel:${phone.value.replace(/\s+/g, '')}` : ''))
const vehicleTypeLabel = computed(() =>
  item.value?.vehicleType ? VEHICLE_TYPE_LABEL[item.value.vehicleType] : '',
)

const intervalOptions = Array.from(
  { length: SIM_MAX_INTERVAL - SIM_MIN_INTERVAL + 1 },
  (_, index) => {
    const seconds = SIM_MIN_INTERVAL + index
    return { value: seconds, label: `Every ${seconds} seconds` }
  },
)

const intervalSeconds = computed(() => {
  const parsed = Number(simInterval.value)
  if (!Number.isFinite(parsed)) {
    return 3
  }
  return Math.min(SIM_MAX_INTERVAL, Math.max(SIM_MIN_INTERVAL, Math.round(parsed)))
})

async function load(): Promise<void> {
  const id = deliveryId.value
  if (!Number.isFinite(id) || id <= 0) {
    error.value = 'That delivery link is not valid.'
    loading.value = false
    return
  }
  loading.value = true
  error.value = null
  try {
    item.value = await deliveryApi.get(id)
  } catch (caught) {
    error.value =
      caught instanceof ApiError ? caught.message : 'Unable to load this delivery right now.'
  } finally {
    loading.value = false
  }
}

async function loadTracking(): Promise<void> {
  tracking.reset()
  await Promise.all([tracking.fetchLatest(deliveryId.value), tracking.fetchHistory(deliveryId.value)])
}

// ------------------------------------------------------------ status transitions

function requestPrimaryAction(): void {
  const action = primaryAction.value
  if (action) {
    actionError.value = null
    pendingAction.value = action
  }
}

function closeConfirm(): void {
  pendingAction.value = null
}

async function confirmPrimaryAction(): Promise<void> {
  const action = pendingAction.value
  const current = item.value
  if (!action || !current) {
    return
  }

  busy.value = true
  const changed = await delivery.changeStatus(current.id, action.status)
  busy.value = false
  pendingAction.value = null
  if (!changed) {
    // The store already toasted the server's own message; the phone is usually being
    // held at eye level looking at the primary action, so it needs a copy here too.
    await load()
    actionError.value =
      item.value && item.value.status !== current.status
        ? 'This delivery was already moved on by operations. The details below are up to date.'
        : 'The server refused this change — the delivery may have been reassigned or cancelled.'
    return
  }

  stopSimulation()
  await Promise.all([load(), delivery.fetchMyDeliveries()])
}

function requestReport(): void {
  reportReason.value = ''
  reportTouched.value = false
  actionError.value = null
  reportOpen.value = true
}

function onReportReason(value: string | number | null): void {
  reportReason.value = value === null ? '' : String(value)
}

function cancelReport(): void {
  reportOpen.value = false
}

async function confirmReport(): Promise<void> {
  const current = item.value
  if (!current) {
    return
  }
  const reason = reportReason.value.trim()
  if (!reason) {
    reportTouched.value = true
    return
  }

  busy.value = true
  const changed = await delivery.changeStatus(current.id, 'FAILED', reason)
  busy.value = false
  reportOpen.value = false
  if (!changed) {
    actionError.value =
      'The problem could not be recorded. The delivery may have been reassigned or cancelled.'
    return
  }

  stopSimulation()
  await Promise.all([load(), delivery.fetchMyDeliveries()])
}

// ------------------------------------------------------------------- simulation

function round6(value: number): number {
  return Math.round(value * 1e6) / 1e6
}

/**
 * Seeds the route from the driver's last reported position when one exists, otherwise
 * from a point near Tunis. Addresses are not geocoded in this MVP, so there is no real
 * destination to steer towards and the route is a generated heading instead.
 */
function seedPosition(): void {
  const latest = tracking.latest
  if (latest) {
    position = { latitude: latest.latitude, longitude: latest.longitude }
    bearing =
      latest.heading !== undefined && Number.isFinite(latest.heading)
        ? latest.heading
        : Math.random() * 360
    return
  }
  position = {
    latitude: TUNIS.latitude + (Math.random() - 0.5) * 0.04,
    longitude: TUNIS.longitude + (Math.random() - 0.5) * 0.04,
  }
  bearing = Math.random() * 360
}

/** One step of the generated route: advance along the heading, then turn a little. */
function stepPosition(speedKph: number): { latitude: number; longitude: number } | null {
  if (!position) {
    return null
  }
  bearing =
    (bearing + (Math.random() - 0.5) * 2 * SIM_BEARING_JITTER_DEGREES + 360) % 360

  const metres = (speedKph / 3.6) * intervalSeconds.value
  const radians = (bearing * Math.PI) / 180
  const dLat = (metres * Math.cos(radians)) / METRES_PER_DEGREE_LAT
  const dLon =
    (metres * Math.sin(radians)) /
    (METRES_PER_DEGREE_LAT * Math.cos((position.latitude * Math.PI) / 180))

  position = { latitude: position.latitude + dLat, longitude: position.longitude + dLon }
  return position
}

async function publishNextPosition(): Promise<void> {
  const speedKph = Math.round(SIM_SPEED_KPH * (0.8 + Math.random() * 0.4))
  const next = stepPosition(speedKph)
  if (!next) {
    return
  }
  try {
    const location = await trackingApi.publishLocation({
      deliveryId: deliveryId.value,
      latitude: round6(next.latitude),
      longitude: round6(next.longitude),
      speedKph,
      heading: Math.round(bearing),
      recordedAt: new Date().toISOString(),
    })
    simSent.value += 1
    simError.value = null
    postedPositions.value = [...postedPositions.value, location].slice(-500)
  } catch (caught) {
    // A rejected position means the backend no longer considers this delivery active;
    // continuing to fire the timer would only produce a stream of failures.
    stopSimulation()
    simError.value =
      caught instanceof ApiError ? caught.message : 'The position could not be sent.'
    toasts.fromException('Tracking update failed', caught)
  }
}

function startSimulation(): void {
  stopSimulation()
  if (!position) {
    seedPosition()
  }
  simError.value = null
  simulating.value = true
  timer = window.setInterval(() => {
    void publishNextPosition()
  }, intervalSeconds.value * 1000)
}

function stopSimulation(): void {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
  simulating.value = false
}

function onIntervalChange(value: string | number | null): void {
  simInterval.value = value ?? 3
}

watch(intervalSeconds, () => {
  if (simulating.value) {
    startSimulation()
  }
})

watch(
  () => item.value?.status,
  (status) => {
    if (status !== 'PICKED_UP' && status !== 'IN_TRANSIT') {
      stopSimulation()
    }
  },
)

onMounted(async () => {
  await load()
  if (item.value) {
    await loadTracking()
  }
})

onBeforeUnmount(() => {
  stopSimulation()
  tracking.reset()
})
</script>

<template>
  <div class="space-y-4">
    <RouterLink
      :to="{ name: 'driver-deliveries' }"
      class="inline-flex min-h-11 items-center gap-1.5 text-body font-medium text-primary"
    >
      <span aria-hidden="true">←</span>
      All deliveries
    </RouterLink>

    <BaseSkeleton v-if="loading" variant="lines" :rows="6" />

    <ErrorState v-else-if="error" :message="error" retryLabel="Try again" @retry="load" />

    <template v-else-if="item">
      <section class="card p-5">
        <p class="text-small font-medium uppercase tracking-wide text-content-muted dark:text-[#94A3B8]">
          Delivery {{ deliveryLabel(item.id) }} · Order {{ orderLabel(item.orderId) }}
        </p>

        <div class="mt-2 flex flex-wrap items-center gap-3">
          <h1
            id="delivery-status-heading"
            class="text-page-title font-bold uppercase leading-tight tracking-tight text-content dark:text-[#F8FAFC]"
          >
            {{ statusMeta.label }}
          </h1>
          <StatusBadge :meta="statusMeta" size="md" />
        </div>

        <p v-if="closedNote" class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
          {{ closedNote }}
        </p>
        <p v-else-if="item.startedAt" class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
          Started {{ formatRelative(item.startedAt) }}
        </p>

        <p v-if="item.failureReason" class="mt-2 break-words text-small font-medium text-danger">
          Recorded problem: {{ item.failureReason }}
        </p>

        <p
          v-if="actionError"
          role="alert"
          class="mt-4 rounded-control bg-red-50 px-3 py-2.5 text-small font-medium text-danger dark:bg-red-950"
        >
          {{ actionError }}
        </p>

        <BaseButton
          v-if="primaryAction"
          class="mt-4"
          size="touch"
          variant="primary"
          fullWidth
          @click="requestPrimaryAction"
        >
          {{ primaryAction.label }}
        </BaseButton>
      </section>

      <BaseCard title="Destination">
        <p class="break-words text-section-title font-semibold leading-tight text-content dark:text-[#F8FAFC]">
          {{ item.deliveryAddress }}
        </p>
        <p class="text-body text-content-muted dark:text-[#94A3B8]">
          {{ item.city }}<template v-if="item.postalCode"> · {{ item.postalCode }}</template>
        </p>
        <p v-if="item.scheduledAt" class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">
          Scheduled {{ formatDateTime(item.scheduledAt) }}
        </p>
      </BaseCard>

      <BaseCard title="Customer">
        <p class="text-card-title font-semibold text-content dark:text-[#F8FAFC]">
          {{ item.customerName ?? 'Customer' }}
        </p>

        <a
          v-if="telHref"
          :href="telHref"
          class="mt-3 inline-flex min-h-14 items-center gap-2 rounded-control px-1 text-body font-semibold text-primary"
        >
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M5 4h4l2 5-2.5 1.5a12 12 0 0 0 5 5L15 13l5 2v4a1.5 1.5 0 0 1-1.6 1.5C10.6 20 4 13.4 3.5 5.6A1.5 1.5 0 0 1 5 4Z" stroke-linejoin="round" />
          </svg>
          <span class="break-all">{{ phone }}</span>
        </a>
        <p v-else class="mt-1 text-body text-content-muted dark:text-[#94A3B8]">
          No phone number was recorded for this delivery.
        </p>
      </BaseCard>

      <BaseCard title="Items">
        <p class="text-body text-content-muted dark:text-[#94A3B8]">
          The delivery record does not carry the order contents. Check the order before you
          leave the depot, or ask the customer at the door.
        </p>
      </BaseCard>

      <BaseCard title="Vehicle">
        <p class="text-card-title font-semibold text-content dark:text-[#F8FAFC]">
          {{ item.vehicleRegistration ?? 'No vehicle assigned' }}
        </p>
        <p v-if="vehicleTypeLabel" class="text-body text-content-muted dark:text-[#94A3B8]">
          {{ vehicleTypeLabel }}
        </p>
      </BaseCard>

      <section aria-labelledby="secondary-actions-heading">
        <h2 id="secondary-actions-heading" class="sr-only">Other actions</h2>

        <a
          v-if="telHref"
          :href="telHref"
          class="inline-flex h-14 w-full items-center justify-center gap-2.5 rounded-control bg-surface px-5 text-body font-semibold text-content ring-1 ring-inset ring-edge transition-colors duration-150 active:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:active:bg-[#1E293B]"
        >
          <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M5 4h4l2 5-2.5 1.5a12 12 0 0 0 5 5L15 13l5 2v4a1.5 1.5 0 0 1-1.6 1.5C10.6 20 4 13.4 3.5 5.6A1.5 1.5 0 0 1 5 4Z" stroke-linejoin="round" />
          </svg>
          Call customer
        </a>

        <BaseButton class="mt-3" size="touch" fullWidth @click="mapOpen = !mapOpen">
          <template #icon>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
              <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11Z" stroke-linejoin="round" />
              <circle cx="12" cy="10" r="2.4" />
            </svg>
          </template>
          {{ mapOpen ? 'Hide map' : 'View map' }}
        </BaseButton>

        <BaseButton
          v-if="isLive"
          class="mt-3"
          size="touch"
          variant="danger"
          fullWidth
          @click="requestReport"
        >
          Report problem
        </BaseButton>
      </section>

      <section v-if="mapOpen" aria-labelledby="map-heading">
        <h2 id="map-heading" class="section-title">Map</h2>

        <div
          v-if="tracking.error"
          role="alert"
          class="mt-2.5 rounded-control bg-red-50 px-3 py-2.5 text-small text-danger dark:bg-red-950"
        >
          {{ tracking.error }}
        </div>

        <TrackingMap class="mt-2.5" :locations="mapLocations" height="280px" />

        <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">
          The destination cannot be plotted: addresses are not geocoded in this build.
        </p>
      </section>

      <section
        v-if="isLive"
        aria-labelledby="simulation-heading"
        class="rounded-card border-2 border-warning bg-amber-50 p-5 dark:border-amber-500 dark:bg-amber-950"
      >
        <div class="flex items-start gap-2.5">
          <svg class="mt-0.5 h-5 w-5 shrink-0 text-amber-700 dark:text-amber-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M12 8v5" stroke-linecap="round" />
            <circle cx="12" cy="16.5" r="0.7" fill="currentColor" />
            <path d="M10.3 3.8 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z" stroke-linejoin="round" />
          </svg>
          <div class="min-w-0">
            <h2 id="simulation-heading" class="text-card-title font-bold uppercase tracking-wide text-amber-900 dark:text-amber-100">
              Demo simulation
            </h2>
            <p class="mt-1 text-small font-medium text-amber-900 dark:text-amber-100">
              There is no GPS hardware in this build. The positions sent from this screen are
              generated by the app, not read from a device.
            </p>
          </div>
        </div>

        <div
          v-if="simError"
          role="alert"
          class="mt-3 rounded-control bg-red-50 px-3 py-2.5 text-small font-medium text-danger dark:bg-red-950"
        >
          {{ simError }}
        </div>

        <div class="mt-4 space-y-3">
          <BaseSelect
            :modelValue="simInterval"
            label="Update every"
            :options="intervalOptions"
            @update:model-value="onIntervalChange"
          />

          <BaseButton
            v-if="simulating"
            size="touch"
            fullWidth
            @click="stopSimulation"
          >
            Stop sending positions
          </BaseButton>
          <BaseButton
            v-else
            size="touch"
            variant="primary"
            fullWidth
            @click="startSimulation"
          >
            Start sending positions
          </BaseButton>
        </div>

        <p class="mt-3 text-small text-amber-900 dark:text-amber-100">
          <template v-if="simSent > 0">{{ simSent }} sent. </template>
          <template v-if="simulating">Sending now.</template>
          <template v-else>Stopped.</template>
        </p>
        <p class="mt-1 text-small text-amber-800 dark:text-amber-200">
          Addresses are not geocoded, so the simulated van starts near Tunis when there is no
          previous position and then travels on a generated heading.
        </p>
      </section>
    </template>

    <ConfirmModal
      :open="pendingAction !== null"
      :title="pendingAction?.label ?? 'Update the delivery'"
      :description="`This changes the delivery to “${pendingTargetLabel}”.`"
      confirmLabel="Confirm"
      cancelLabel="Not yet"
      tone="primary"
      :busy="busy"
      @confirm="confirmPrimaryAction"
      @cancel="closeConfirm"
    />

    <ConfirmModal
      :open="reportOpen"
      title="Report a problem"
      description="Operations is notified and the delivery is closed as failed. Write what happened."
      confirmLabel="Report problem"
      cancelLabel="Go back"
      tone="danger"
      :busy="busy"
      @confirm="confirmReport"
      @cancel="cancelReport"
    >
      <div class="mt-3">
        <BaseInput
          :modelValue="reportReason"
          label="What went wrong?"
          placeholder="Customer absent, address not found…"
          required
          :error="reportTouched && !reportReason.trim() ? 'A reason is required so operations can follow up.' : ''"
          @update:model-value="onReportReason"
        />
      </div>
    </ConfirmModal>
  </div>
</template>