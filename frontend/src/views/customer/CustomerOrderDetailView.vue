<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { ApiError } from '@/services/api'
import { orderApi } from '@/services/orderApi'
import { useOrderStore } from '@/stores/orderStore'
import type { OrderResponse, OrderStatus } from '@/types'
import { formatDateTime, formatMoney, orderLabel, pluralize } from '@/utils/format'
import { isCancellable, orderStatus } from '@/utils/status'

/**
 * A single order, customer view.
 *
 * The same record is read on the operations screen; the difference is what a customer may
 * do with it, which is cancel — and only while the server would still accept it, so the
 * button is gated on `isCancellable(status)` rather than on a client-side guess.
 */

const LIFECYCLE: OrderStatus[] = [
  'CREATED',
  'CONFIRMED',
  'PROCESSING',
  'READY_FOR_DELIVERY',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
]

type TimelineState = 'done' | 'current' | 'pending'

interface TimelineStep {
  key: string
  status: OrderStatus
  state: TimelineState
  source?: 'CUSTOMER' | 'OPERATIONS' | 'SYSTEM'
  changedAt?: string
  note?: string
}

const SOURCE_LABEL: Record<'CUSTOMER' | 'OPERATIONS' | 'SYSTEM', string> = {
  CUSTOMER: 'You',
  OPERATIONS: 'Operations',
  SYSTEM: 'Automatic',
}

const DEFAULT_CANCEL_REASON = 'No longer needed'

const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()

const orderId = computed(() => Number(route.params.id))
const order = ref<OrderResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const inaccessible = ref(false)

const cancelOpen = ref(false)
const cancelReason = ref(DEFAULT_CANCEL_REASON)
const cancelError = ref('')
const cancelling = ref(false)

const itemCountLabel = computed(() => pluralize(order.value?.items.length ?? 0, 'line'))

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
      source: entry.source,
      changedAt: entry.changedAt,
      note: entry.note,
    }))
  }

  const currentIndex = LIFECYCLE.indexOf(current.status)
  return LIFECYCLE.map((status, index) => {
    const entry = byStatus.get(status)
    const state: TimelineState =
      index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'pending'
    return {
      key: status,
      status,
      state,
      source: entry?.source,
      changedAt: entry?.changedAt,
      note: entry?.note,
    }
  })
})

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  inaccessible.value = false
  try {
    order.value = await orderApi.get(orderId.value)
  } catch (caught) {
    order.value = null
    // Another customer's order is a 403 and a deleted one a 404: both are "not yours to
    // see", never a server fault worth showing as an error.
    if (caught instanceof ApiError && (caught.isNotFound || caught.isForbidden)) {
      inaccessible.value = true
    } else {
      error.value =
        caught instanceof ApiError ? caught.message : 'This order could not be loaded.'
    }
  } finally {
    loading.value = false
  }
}

function openCancel(): void {
  cancelReason.value = DEFAULT_CANCEL_REASON
  cancelError.value = ''
  cancelOpen.value = true
}

async function confirmCancel(): Promise<void> {
  const current = order.value
  if (!current) {
    return
  }
  const reason = cancelReason.value.trim()
  if (!reason) {
    cancelError.value = 'A short reason is required so the timeline records why the order stopped.'
    return
  }
  if (reason.length > 255) {
    cancelError.value = 'Keep the reason under 255 characters.'
    return
  }

  cancelling.value = true
  const ok = await orderStore.cancelOrder(current.id, reason)
  cancelling.value = false

  if (ok) {
    cancelOpen.value = false
    router.back()
  } else {
    // The store already raised a toast; the reason belongs next to the control too.
    cancelError.value = 'The order could not be cancelled. It may already have left the warehouse.'
  }
}

onMounted(() => {
  if (!Number.isFinite(orderId.value)) {
    error.value = 'That order id is not valid.'
    loading.value = false
    return
  }
  void load()
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
      Back to my orders
    </RouterLink>

    <BaseCard v-if="loading" :padded="false" as="section" aria-busy="true">
      <BaseSkeleton class="px-5 py-5" variant="lines" :rows="6" label="Loading the order" />
    </BaseCard>

    <BaseCard v-else-if="error" :padded="false" as="section">
      <ErrorState :message="error" retryLabel="Try again" @retry="load" />
    </BaseCard>

    <BaseCard v-else-if="inaccessible" :padded="false" as="section">
      <div class="px-6 py-12 text-center">
        <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
          This order is not available
        </p>
        <p class="mx-auto mt-1.5 max-w-md text-body text-content-muted dark:text-[#94A3B8]">
          It may have been removed, or it belongs to another account. Orders you placed are
          always listed under your own account.
        </p>
        <div class="mt-5 flex flex-wrap justify-center gap-2">
          <RouterLink
            :to="{ name: 'customer-orders' }"
            class="inline-flex h-10 items-center justify-center rounded-control bg-primary px-4 text-body font-medium text-white transition-colors duration-150 hover:bg-primary-dark"
          >
            Back to my orders
          </RouterLink>
        </div>
      </div>
    </BaseCard>

    <template v-else-if="order">
      <BaseCard :padded="false" as="section">
        <div
          class="flex flex-wrap items-start justify-between gap-3 border-b border-edge px-5 py-4 dark:border-[#334155]"
        >
          <div class="min-w-0">
            <div class="flex flex-wrap items-center gap-2.5">
              <h2 class="page-title">Order {{ orderLabel(order.id) }}</h2>
              <StatusBadge :meta="orderStatus(order.status)" size="md" />
            </div>
            <p class="mt-1 text-small text-content-muted dark:text-[#94A3B8]">
              Placed {{ formatDateTime(order.createdAt) }} · Last update
              {{ formatDateTime(order.updatedAt) }}
            </p>
          </div>

          <div class="flex shrink-0 items-center gap-2">
            <RouterLink
              v-if="order.deliveryId !== undefined"
              :to="{ name: 'customer-tracking', params: { deliveryId: order.deliveryId } }"
              class="inline-flex h-9 items-center justify-center gap-1.5 rounded-control bg-blue-50 px-3 text-small font-medium text-primary transition-colors duration-150 hover:bg-blue-100 dark:bg-blue-950/60 dark:text-blue-300 dark:hover:bg-blue-900/50"
            >
              Track delivery
            </RouterLink>
            <BaseButton
              v-if="isCancellable(order.status)"
              size="sm"
              variant="danger"
              :aria-label="`Cancel order ${order.id}`"
              @click="openCancel"
            >
              Cancel order
            </BaseButton>
          </div>
        </div>

        <div class="grid grid-cols-1 gap-5 p-5 lg:grid-cols-3">
          <div class="space-y-5 lg:col-span-2">
            <section aria-labelledby="customer-order-items">
              <h3 id="customer-order-items" class="card-title">
                Items ({{ itemCountLabel }})
              </h3>

              <div class="mt-2 overflow-x-auto rounded-control border border-edge dark:border-[#334155]">
                <table class="w-full border-collapse text-left">
                  <caption class="sr-only">Line items on order {{ orderLabel(order.id) }}</caption>
                  <thead>
                    <tr class="border-b border-edge bg-surface-muted dark:border-[#334155] dark:bg-[#0F172A]">
                      <th
                        scope="col"
                        class="px-4 py-2.5 text-small font-semibold text-content-muted dark:text-[#94A3B8]"
                      >
                        Product
                      </th>
                      <th
                        scope="col"
                        class="hidden px-4 py-2.5 text-right text-small font-semibold text-content-muted sm:table-cell dark:text-[#94A3B8]"
                      >
                        Qty
                      </th>
                      <th
                        scope="col"
                        class="hidden px-4 py-2.5 text-right text-small font-semibold text-content-muted sm:table-cell dark:text-[#94A3B8]"
                      >
                        Unit price
                      </th>
                      <th
                        scope="col"
                        class="px-4 py-2.5 text-right text-small font-semibold text-content-muted dark:text-[#94A3B8]"
                      >
                        Subtotal
                      </th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-edge dark:divide-[#1E293B]">
                    <tr v-for="item in order.items" :key="item.id">
                      <td class="px-4 py-2.5 text-body text-content dark:text-[#F8FAFC]">
                        {{ item.productName }}
                        <span
                          class="mt-0.5 block text-small text-content-muted sm:hidden dark:text-[#94A3B8]"
                        >
                          {{ item.quantity }} × {{ formatMoney(item.unitPrice, order.currency) }}
                        </span>
                      </td>
                      <td
                        class="hidden px-4 py-2.5 text-right text-body text-content-muted sm:table-cell dark:text-[#94A3B8]"
                      >
                        {{ item.quantity }}
                      </td>
                      <td
                        class="hidden px-4 py-2.5 text-right text-body text-content-muted sm:table-cell dark:text-[#94A3B8]"
                      >
                        {{ formatMoney(item.unitPrice, order.currency) }}
                      </td>
                      <td class="px-4 py-2.5 text-right text-body font-medium text-content dark:text-[#F8FAFC]">
                        {{ formatMoney(item.lineSubtotal, order.currency) }}
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              <dl class="mt-4 ml-auto max-w-xs space-y-1.5">
                <div class="flex items-baseline justify-between gap-4">
                  <dt class="text-body text-content-muted dark:text-[#94A3B8]">Subtotal</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    {{ formatMoney(order.subtotal, order.currency) }}
                  </dd>
                </div>
                <div class="flex items-baseline justify-between gap-4">
                  <dt class="text-body text-content-muted dark:text-[#94A3B8]">Delivery fee</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    {{ formatMoney(order.deliveryFee, order.currency) }}
                  </dd>
                </div>
                <div
                  class="flex items-baseline justify-between gap-4 border-t border-edge pt-1.5 dark:border-[#334155]"
                >
                  <dt class="text-body font-semibold text-content dark:text-[#F8FAFC]">Total</dt>
                  <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">
                    {{ formatMoney(order.totalAmount, order.currency) }}
                  </dd>
                </div>
              </dl>
            </section>

            <section aria-labelledby="customer-order-timeline">
              <h3 id="customer-order-timeline" class="card-title">Progress</h3>

              <p
                v-if="order.status === 'CANCELLED'"
                class="mt-2 rounded-control border border-red-200 bg-red-50 px-3 py-2 text-small text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
                role="alert"
              >
                This order was cancelled
                <template v-if="order.cancelledReason"> — {{ order.cancelledReason }}</template>.
              </p>

              <ol v-if="steps.length > 0" class="mt-3">
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
                      <span
                        v-if="step.state === 'pending'"
                        class="text-small text-content-subtle dark:text-[#64748B]"
                      >
                        pending
                      </span>
                    </p>
                    <p
                      v-if="step.source && step.changedAt"
                      class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]"
                    >
                      {{ SOURCE_LABEL[step.source] }} · {{ formatDateTime(step.changedAt) }}
                    </p>
                    <p v-if="step.note" class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                      “{{ step.note }}”
                    </p>
                  </div>
                </li>
              </ol>
            </section>
          </div>

          <div class="space-y-4">
            <section
              class="rounded-card border border-edge p-5 dark:border-[#334155]"
              aria-labelledby="customer-order-address"
            >
              <h3 id="customer-order-address" class="card-title">Delivering to</h3>
              <address class="mt-2 text-body not-italic text-content dark:text-[#F8FAFC]">
                {{ order.deliveryAddress }}<br />
                {{ order.city }} {{ order.postalCode }}
              </address>
            </section>

            <section
              v-if="order.deliveryId !== undefined"
              class="rounded-card border border-edge p-5 dark:border-[#334155]"
              aria-labelledby="customer-order-delivery"
            >
              <h3 id="customer-order-delivery" class="card-title">Delivery</h3>
              <p class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
                A driver has been assigned to this order. Follow the van live.
              </p>
              <RouterLink
                :to="{ name: 'customer-tracking', params: { deliveryId: order.deliveryId } }"
                class="mt-3 inline-flex h-10 w-full items-center justify-center gap-2 rounded-control bg-surface text-body font-medium text-content ring-1 ring-inset ring-edge transition-colors duration-150 hover:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:hover:bg-[#1E293B]"
              >
                Track this delivery
              </RouterLink>
            </section>
          </div>
        </div>
      </BaseCard>
    </template>

    <ConfirmModal
      :open="cancelOpen"
      :title="order ? `Cancel order ${orderLabel(order.id)}?` : 'Cancel order'"
      description="Cancelling releases the stock reserved for this order and cannot be undone."
      confirmLabel="Cancel the order"
      cancelLabel="Keep the order"
      :busy="cancelling"
      @cancel="cancelOpen = false"
      @confirm="confirmCancel"
    >
      <div class="mt-3">
        <label
          for="customer-cancel-reason"
          class="block text-small font-medium text-content dark:text-[#F8FAFC]"
        >
          Reason
          <span class="text-danger" aria-hidden="true">*</span>
        </label>
        <input
          id="customer-cancel-reason"
          v-model="cancelReason"
          type="text"
          :aria-invalid="Boolean(cancelError)"
          aria-describedby="customer-cancel-reason-hint"
          class="mt-1.5 h-10 w-full rounded-control border bg-surface px-3 text-body text-content transition-colors duration-150 focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 dark:bg-[#0F172A] dark:text-[#F8FAFC]"
          :class="cancelError ? 'border-danger' : 'border-edge dark:border-[#334155]'"
        />
        <p id="customer-cancel-reason-hint" class="mt-1.5 text-small text-content-muted dark:text-[#94A3B8]">
          Up to 255 characters, recorded on the order timeline.
        </p>
        <p v-if="cancelError" class="mt-1.5 text-small text-danger" role="alert">
          {{ cancelError }}
        </p>
      </div>
    </ConfirmModal>
  </div>
</template>
