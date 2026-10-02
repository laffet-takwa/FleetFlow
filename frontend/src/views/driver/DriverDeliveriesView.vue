<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'

import { ApiError } from '@/services/api'
import { deliveryApi } from '@/services/deliveryApi'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { deliveryLabel, formatDateTime, formatRelative, orderLabel } from '@/utils/format'
import { deliveryStatus } from '@/utils/status'

import type { DeliveryResponse, DeliveryStatus } from '@/types'

/** Assignments the driver still has to act on, versus assignments that are finished. */
const ACTIVE_STATUSES: DeliveryStatus[] = ['ASSIGNED', 'PICKED_UP', 'IN_TRANSIT']
const CLOSED_STATUSES: DeliveryStatus[] = ['DELIVERED', 'FAILED', 'CANCELLED']

const router = useRouter()
const delivery = useDeliveryStore()

const loading = ref(true)
const error = ref<string | null>(null)

const activeDeliveries = computed(() =>
  delivery.myDeliveries
    .filter((item) => ACTIVE_STATUSES.includes(item.status))
    .slice()
    .sort(byTimeDesc((item) => item.createdAt)),
)

const completedDeliveries = computed(() =>
  delivery.myDeliveries
    .filter((item) => CLOSED_STATUSES.includes(item.status))
    .slice()
    .sort(byTimeDesc((item) => item.completedAt)),
)

/** Newest first on an ISO-8601 timestamp, falling back to the highest id for ties. */
function byTimeDesc(key: (item: DeliveryResponse) => string | undefined) {
  return (a: DeliveryResponse, b: DeliveryResponse): number => {
    const left = key(a) ?? ''
    const right = key(b) ?? ''
    if (left === right) {
      return b.id - a.id
    }
    if (left === '') {
      return 1
    }
    if (right === '') {
      return -1
    }
    return right.localeCompare(left)
  }
}

/**
 * Mirrors `deliveryStore.fetchMyDeliveries` and writes the result back into the store so
 * the shell keeps its "Active delivery" shortcut. The request is made here rather than
 * through the store action because this screen has to tell a failed request apart from
 * an empty one, and the store action deliberately swallows the error.
 */
async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    delivery.myDeliveries = await deliveryApi.mine()
  } catch (caught) {
    error.value =
      caught instanceof ApiError ? caught.message : 'Unable to load your deliveries right now.'
  } finally {
    loading.value = false
  }
}

function open(item: DeliveryResponse): void {
  void router.push({ name: 'driver-delivery-detail', params: { id: item.id } })
}

function subtitleFor(item: DeliveryResponse): string {
  if (item.status === 'IN_TRANSIT') {
    return `Started ${formatRelative(item.startedAt)}`
  }
  if (item.status === 'PICKED_UP') {
    return `Picked up ${formatRelative(item.startedAt)}`
  }
  if (item.status === 'ASSIGNED') {
    return item.scheduledAt
      ? `Scheduled ${formatDateTime(item.scheduledAt)}`
      : `Assigned ${formatRelative(item.createdAt)}`
  }
  if (item.completedAt) {
    return `Closed ${formatDateTime(item.completedAt)}`
  }
  return `Updated ${formatRelative(item.updatedAt)}`
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="space-y-5">
    <header>
      <h1 class="page-title">Deliveries</h1>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        Everything currently assigned to you.
      </p>
    </header>

    <BaseSkeleton v-if="loading" variant="cards" :rows="3" />

    <ErrorState v-else-if="error" :message="error" retryLabel="Try again" @retry="load" />

    <template v-else>
      <section aria-labelledby="active-deliveries-heading">
        <div class="flex items-baseline justify-between gap-3">
          <h2 id="active-deliveries-heading" class="section-title">Active</h2>
          <p class="text-small text-content-muted dark:text-[#94A3B8]">
            {{ activeDeliveries.length }} in progress
          </p>
        </div>

        <div v-if="activeDeliveries.length" class="mt-2.5 space-y-3">
          <BaseCard v-for="item in activeDeliveries" :key="item.id">
            <template #actions>
              <StatusBadge :meta="deliveryStatus(item.status)" size="md" />
            </template>

            <p class="text-small font-medium text-content-muted dark:text-[#94A3B8]">
              Delivery {{ deliveryLabel(item.id) }} · Order {{ orderLabel(item.orderId) }}
            </p>
            <p class="mt-1.5 break-words text-section-title font-semibold leading-tight text-content dark:text-[#F8FAFC]">
              {{ item.deliveryAddress }}
            </p>
            <p class="text-body text-content-muted dark:text-[#94A3B8]">{{ item.city }}</p>
            <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">{{ subtitleFor(item) }}</p>

            <BaseButton class="mt-4" size="touch" variant="primary" fullWidth @click="open(item)">
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
        </div>

        <BaseCard v-else class="mt-2.5" :padded="false">
          <EmptyState
            icon="deliveries"
            title="Nothing in progress"
            description="You have no active delivery. Stay available on your dashboard and operations will assign the next one."
            actionLabel="Back to dashboard"
            @action="router.push({ name: 'driver-dashboard' })"
          />
        </BaseCard>
      </section>

      <section aria-labelledby="completed-deliveries-heading">
        <div class="flex items-baseline justify-between gap-3">
          <h2 id="completed-deliveries-heading" class="section-title">Completed</h2>
          <p class="text-small text-content-muted dark:text-[#94A3B8]">
            {{ completedDeliveries.length }} closed
          </p>
        </div>

        <div v-if="completedDeliveries.length" class="mt-2.5 space-y-3">
          <BaseCard v-for="item in completedDeliveries" :key="item.id">
            <template #actions>
              <StatusBadge :meta="deliveryStatus(item.status)" size="md" />
            </template>

            <p class="text-small font-medium text-content-muted dark:text-[#94A3B8]">
              Delivery {{ deliveryLabel(item.id) }} · Order {{ orderLabel(item.orderId) }}
            </p>
            <p class="mt-1.5 break-words text-card-title font-semibold text-content dark:text-[#F8FAFC]">
              {{ item.deliveryAddress }}
            </p>
            <p class="text-body text-content-muted dark:text-[#94A3B8]">{{ item.city }}</p>
            <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">{{ subtitleFor(item) }}</p>

            <BaseButton class="mt-4" size="touch" fullWidth @click="open(item)">
              <template #icon>
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                  <path d="M6 3h9l4 4v14H6z" stroke-linejoin="round" />
                  <path d="M15 3v4h4M9 12h6" stroke-linecap="round" />
                </svg>
              </template>
              Open details
            </BaseButton>
          </BaseCard>
        </div>

        <BaseCard v-else class="mt-2.5" :padded="false">
          <EmptyState
            icon="deliveries"
            title="No completed deliveries yet"
            description="Deliveries you deliver, fail or that are cancelled will be listed here for your records."
          />
        </BaseCard>
      </section>
    </template>
  </div>
</template>