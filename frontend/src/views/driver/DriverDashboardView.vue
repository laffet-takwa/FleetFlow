<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'

import { ApiError } from '@/services/api'
import { deliveryApi, driverApi } from '@/services/deliveryApi'
import { useAuthStore } from '@/stores/authStore'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { deliveryLabel, formatRelative, orderLabel } from '@/utils/format'
import { deliveryStatus, driverStatus } from '@/utils/status'

import type { DeliveryResponse, DeliveryStatus, DriverResponse, DriverStatus } from '@/types'

const ACTIVE_STATUSES: DeliveryStatus[] = ['ASSIGNED', 'PICKED_UP', 'IN_TRANSIT']

const router = useRouter()
const auth = useAuthStore()
const delivery = useDeliveryStore()

const driver = ref<DriverResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const statusError = ref<string | null>(null)
const statusBusy = ref<DriverStatus | null>(null)

const firstName = computed(() => auth.user?.firstName ?? 'driver')

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 12) {
    return 'Good morning'
  }
  return hour < 18 ? 'Good afternoon' : 'Good evening'
})

const today = new Date().toLocaleDateString('en-TN', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
})

const activeDelivery = computed(
  () => delivery.myDeliveries.find((item) => ACTIVE_STATUSES.includes(item.status)) ?? null,
)
const activeCount = computed(
  () => delivery.myDeliveries.filter((item) => ACTIVE_STATUSES.includes(item.status)).length,
)
const completedCount = computed(
  () => delivery.myDeliveries.filter((item) => item.status === 'DELIVERED').length,
)

const statusMeta = computed(() => driverStatus(driver.value?.status ?? 'OFFLINE'))

const statusHint = computed(() => {
  switch (driver.value?.status) {
    case 'AVAILABLE':
      return 'Operations can assign you a delivery.'
    case 'ON_DELIVERY':
      return 'Close the delivery in progress to become available again.'
    default:
      return 'No new deliveries will be assigned to you.'
  }
})

/**
 * The screen needs the real error, which `fetchMyDeliveries` cannot give: it swallows
 * failures on purpose so the shell's "Active delivery" shortcut keeps working. So the
 * request is made through the same API the store uses and the result is written back
 * into the store, keeping one copy of the list for the layout and this screen.
 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const [mine, profile] = await Promise.all([deliveryApi.mine(), driverApi.me()])
    delivery.myDeliveries = mine
    driver.value = profile
  } catch (caught) {
    error.value =
      caught instanceof ApiError ? caught.message : 'Unable to load your deliveries right now.'
  } finally {
    loading.value = false
  }
}

async function setStatus(next: DriverStatus): Promise<void> {
  if (statusBusy.value) {
    return
  }
  statusBusy.value = next
  statusError.value = null
  const changed = await delivery.setDriverStatus(next)
  statusBusy.value = null

  if (changed) {
    // The store has already re-read the delivery list; the profile has to be re-read
    // too because it is not part of that list.
    driver.value = await driverApi.me().catch(() => driver.value)
    return
  }
  statusError.value =
    'Your status was not changed. Operations may need to close the delivery in progress first.'
}

function openDelivery(item: DeliveryResponse): void {
  void router.push({ name: 'driver-delivery-detail', params: { id: item.id } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="space-y-5">
    <header>
      <h1 class="page-title">{{ greeting }}, {{ firstName }}</h1>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">{{ today }}</p>
    </header>

    <div v-if="loading" class="space-y-5">
      <div class="card p-5">
        <div class="skeleton h-3 w-20" />
        <div class="skeleton mt-3 h-8 w-40" />
        <div class="mt-5 grid grid-cols-2 gap-3">
          <div class="skeleton h-14 w-full rounded-control" />
          <div class="skeleton h-14 w-full rounded-control" />
        </div>
      </div>
      <BaseSkeleton variant="cards" :rows="2" />
    </div>

    <ErrorState v-else-if="error" :message="error" retryLabel="Try again" @retry="load" />

    <template v-else>
      <section class="card p-5" aria-labelledby="driver-status-heading">
        <h2 id="driver-status-heading" class="text-small font-medium uppercase tracking-wide text-content-muted dark:text-[#94A3B8]">
          Your status
        </h2>

        <div class="mt-2 flex items-center gap-3">
          <span
            class="flex h-12 w-12 shrink-0 items-center justify-center rounded-full text-2xl leading-none"
            :class="statusMeta.tone === 'success' ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-200' : statusMeta.tone === 'info' ? 'bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-200' : 'bg-slate-200 text-slate-800 dark:bg-slate-700 dark:text-slate-100'"
            aria-hidden="true"
          >
            {{ statusMeta.glyph }}
          </span>
          <div class="min-w-0">
            <p class="text-page-title font-bold uppercase leading-tight tracking-tight text-content dark:text-[#F8FAFC]">
              {{ statusMeta.label }}
            </p>
            <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">{{ statusHint }}</p>
          </div>
        </div>

        <div
          v-if="statusError"
          role="alert"
          class="mt-4 rounded-control bg-red-50 px-3 py-2.5 text-small text-danger dark:bg-red-950"
        >
          {{ statusError }}
        </div>

        <div class="mt-5 grid grid-cols-2 gap-3" role="group" aria-label="Change your availability">
          <BaseButton
            size="touch"
            :variant="driver?.status === 'AVAILABLE' ? 'primary' : 'secondary'"
            :disabled="driver?.status === 'AVAILABLE'"
            :loading="statusBusy === 'AVAILABLE'"
            loadingLabel="Saving…"
            @click="setStatus('AVAILABLE')"
          >
            Available
          </BaseButton>
          <BaseButton
            size="touch"
            :variant="driver?.status === 'OFFLINE' ? 'primary' : 'secondary'"
            :disabled="driver?.status === 'OFFLINE'"
            :loading="statusBusy === 'OFFLINE'"
            loadingLabel="Saving…"
            @click="setStatus('OFFLINE')"
          >
            Go offline
          </BaseButton>
        </div>

        <p class="mt-3 text-small text-content-muted dark:text-[#94A3B8]">
          On delivery is set by operations when a delivery is assigned to you.
        </p>
      </section>

      <section aria-labelledby="active-delivery-heading">
        <h2 id="active-delivery-heading" class="section-title">Current delivery</h2>

        <div class="mt-2.5">
          <BaseCard v-if="activeDelivery">
            <template #actions>
              <StatusBadge :meta="deliveryStatus(activeDelivery.status)" size="md" />
            </template>

            <p class="text-small font-medium text-content-muted dark:text-[#94A3B8]">
              Delivery {{ deliveryLabel(activeDelivery.id) }} · Order {{ orderLabel(activeDelivery.orderId) }}
            </p>
            <p class="mt-1.5 break-words text-section-title font-semibold leading-tight text-content dark:text-[#F8FAFC]">
              {{ activeDelivery.deliveryAddress }}
            </p>
            <p class="text-body text-content-muted dark:text-[#94A3B8]">{{ activeDelivery.city }}</p>
            <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">
              Started {{ formatRelative(activeDelivery.startedAt) }}
            </p>

            <BaseButton
              class="mt-4"
              size="touch"
              variant="primary"
              fullWidth
              @click="openDelivery(activeDelivery)"
            >
              <template #icon>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                  <path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round" />
                  <circle cx="7" cy="18" r="1.4" />
                  <circle cx="17" cy="18" r="1.4" />
                </svg>
              </template>
              Open delivery
            </BaseButton>
          </BaseCard>

          <BaseCard v-else :padded="false">
            <EmptyState
              icon="deliveries"
              title="No delivery in progress"
              description="You have nothing in the van right now. Stay available and operations will assign your next delivery."
              actionLabel="View all deliveries"
              @action="router.push({ name: 'driver-deliveries' })"
            />
          </BaseCard>
        </div>
      </section>
    </template>

    <section v-if="!error" aria-labelledby="today-summary-heading">
      <h2 id="today-summary-heading" class="section-title">Today's summary</h2>
      <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
        Counted from the deliveries currently assigned to you.
      </p>
      <div class="mt-2.5 grid gap-3 sm:grid-cols-3">
        <StatCard
          label="Deliveries"
          :value="delivery.myDeliveries.length"
          hint="On your list"
          icon="truck"
          :loading="loading"
        />
        <StatCard
          label="Completed"
          :value="completedCount"
          hint="Delivered"
          icon="check"
          tone="success"
          :loading="loading"
        />
        <StatCard
          label="Active"
          :value="activeCount"
          hint="In progress now"
          icon="clock"
          tone="primary"
          :loading="loading"
        />
      </div>
    </section>
  </div>
</template>