<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { ApiError } from '@/services/api'
import { customerApi } from '@/services/customerApi'
import { deliveryApi } from '@/services/deliveryApi'
import { orderApi } from '@/services/orderApi'
import { useToastStore } from '@/stores/toastStore'
import type { DeliveryResponse, OrderResponse, OrderStatus } from '@/types'
import { formatDateTime, formatMoney } from '@/utils/format'
import { deliveryStatus, isCancellable, orderStatus } from '@/utils/status'

/**
 * Single order, operations view.
 *
 * The delivery block is fetched from `deliveryApi.get(order.deliveryId)` because the order
 * payload carries only the id; when that fetch fails the panel links onward instead of
 * inventing a driver.
 */

const LIFECYCLE: OrderStatus[] = [
  'CREATED',
  'CONFIRMED',
  'PROCESSING',
  'READY_FOR_DELIVERY',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
]

const STATUS_OPTIONS: OrderStatus[] = [
  'CREATED',
  'CONFIRMED',
  'PROCESSING',
  'READY_FOR_DELIVERY',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'CANCELLED',
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
  CUSTOMER: 'Customer',
  OPERATIONS: 'Operations',
  SYSTEM: 'System',
}

const route = useRoute()
const toasts = useToastStore()

const orderId = computed(() => Number(route.params.id))
const order = ref<OrderResponse | null>(null)
const delivery = ref<DeliveryResponse | null>(null)

const loading = ref(true)
const error = ref<string | null>(null)
const deliveryError = ref<string | null>(null)

const customerName = ref<string | null>(null)

const cancelOpen = ref(false)
const cancelReason = ref('')
const cancelError = ref('')
const cancelling = ref(false)

const statusOpen = ref(false)
const nextStatus = ref<OrderStatus | null>(null)
const statusNote = ref('')
const statusError = ref('')
const savingStatus = ref(false)

const statusOptions = computed(() =>
  STATUS_OPTIONS.filter((status) => status !== order.value?.status).map((status) => ({
    value: status,
    label: orderStatus(status).label,
  })),
)

const steps = computed<TimelineStep[]>(() => {
  const current = order.value
  if (!current) {
    return []
  }

  const byStatus = new Map(current.timeline.map((entry) => [entry.status, entry]))

  if (current.status === 'CANCELLED') {
    // A cancelled order never walks the forward path, so its history is the stepper.
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
    const state: TimelineState = index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'pending'
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

const itemCountLabel = computed(() =>
  `${order.value?.items.length ?? 0} ${order.value?.items.length === 1 ? 'line' : 'lines'}`,
)

async function load(): Promise<void> {
  loading.value = true
  error.value = null

  try {
    order.value = await orderApi.get(orderId.value)
  } catch (caught) {
    order.value = null
    error.value = caught instanceof ApiError ? caught.message : 'This order could not be loaded.'
    loading.value = false
    return
  }

  loading.value = false

  // The customer name and the linked delivery are secondary: neither may delay the page.
  void loadCustomerName()
  void loadDelivery()
}

async function loadCustomerName(): Promise<void> {
  const current = order.value
  if (!current) {
    return
  }
  try {
    const page = await customerApi.list({ size: 100 })
    const customer = page.content.find((entry) => entry.id === current.customerId)
    customerName.value = customer ? `${customer.firstName} ${customer.lastName}` : null
  } catch {
    customerName.value = null
  }
}

async function loadDelivery(): Promise<void> {
  const deliveryId = order.value?.deliveryId
  if (deliveryId === undefined) {
    delivery.value = null
    deliveryError.value = null
    return
  }
  deliveryError.value = null
  try {
    delivery.value = await deliveryApi.get(deliveryId)
  } catch (caught) {
    delivery.value = null
    deliveryError.value =
      caught instanceof ApiError ? caught.message : 'The linked delivery could not be loaded.'
  }
}

function openCancel(): void {
  cancelReason.value = ''
  cancelError.value = ''
  cancelOpen.value = true
}

async function confirmCancel(): Promise<void> {
  const current = order.value
  if (!current) {
    return
  }
  if (!cancelReason.value.trim()) {
    cancelError.value = 'A reason is required so the timeline records why the order stopped.'
    return
  }
  cancelling.value = true
  try {
    order.value = await orderApi.cancel(current.id, { reason: cancelReason.value.trim() })
    toasts.success(`Order #${current.id} cancelled`)
    cancelOpen.value = false
    await loadDelivery()
  } catch (caught) {
    cancelError.value = caught instanceof ApiError ? caught.message : 'The order could not be cancelled.'
    toasts.fromException(`Unable to cancel order #${current.id}`, caught)
  } finally {
    cancelling.value = false
  }
}

function openStatus(): void {
  nextStatus.value = statusOptions.value[0]?.value ?? null
  statusNote.value = ''
  statusError.value = ''
  statusOpen.value = true
}

function onNextStatusChange(value: string | number | null): void {
  nextStatus.value = value === null || value === '' ? null : (String(value) as OrderStatus)
}

function onStatusNoteChange(value: string | number | null): void {
  statusNote.value = String(value ?? '')
}

async function confirmStatus(): Promise<void> {
  const current = order.value
  const target = nextStatus.value
  if (!current || !target) {
    return
  }
  savingStatus.value = true
  statusError.value = ''
  try {
    order.value = await orderApi.updateStatus(current.id, target, statusNote.value.trim() || undefined)
    toasts.success(`Order #${current.id} moved to ${orderStatus(target).label.toLowerCase()}`)
    statusOpen.value = false
    await loadDelivery()
  } catch (caught) {
    // The server owns the transition rules; its 409 wording is shown rather than hidden.
    statusError.value =
      caught instanceof ApiError ? caught.message : 'The status change was refused by the server.'
    toasts.fromException(`Unable to update order #${current.id}`, caught)
  } finally {
    savingStatus.value = false
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
    <BaseCard :padded="false">
      <div class="flex flex-wrap items-start justify-between gap-3 border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div v-if="order" class="min-w-0">
          <div class="flex flex-wrap items-center gap-2.5">
            <h2 class="page-title">Order #{{ order.id }}</h2>
            <StatusBadge :meta="orderStatus(order.status)" size="md" />
          </div>
          <p class="mt-1 text-small text-content-muted dark:text-[#94A3B8]">
            Placed {{ formatDateTime(order.createdAt) }} · Last update {{ formatDateTime(order.updatedAt) }}
          </p>
        </div>
        <div v-else-if="!loading && !error" class="text-small text-content-muted">Order unavailable</div>

        <div v-if="order" class="flex shrink-0 items-center gap-2">
          <BaseButton size="sm" variant="secondary" @click="openStatus">Change status</BaseButton>
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

      <BaseSkeleton v-if="loading" class="px-5 py-4" variant="lines" :rows="6" label="Loading the order" />

      <ErrorState
        v-else-if="error"
        class="px-5 py-4"
        :message="error"
        retryLabel="Try again"
        @retry="load"
      />

      <div v-else-if="order" class="grid grid-cols-1 gap-5 p-5 xl:grid-cols-3">
        <div class="space-y-4 xl:col-span-2">
          <section aria-labelledby="order-items">
            <h3 id="order-items" class="card-title">Items ({{ itemCountLabel }})</h3>
            <div class="mt-2 overflow-x-auto rounded-control border border-edge dark:border-[#334155]">
              <table class="w-full border-collapse text-left">
                <caption class="sr-only">Line items on order #{{ order.id }}</caption>
                <thead>
                  <tr class="border-b border-edge bg-surface-muted dark:border-[#334155] dark:bg-[#0F172A]">
                    <th scope="col" class="px-4 py-2.5 text-small font-semibold text-content-muted dark:text-[#94A3B8]">Product</th>
                    <th scope="col" class="px-4 py-2.5 text-right text-small font-semibold text-content-muted dark:text-[#94A3B8]">Qty</th>
                    <th scope="col" class="px-4 py-2.5 text-right text-small font-semibold text-content-muted dark:text-[#94A3B8]">Unit price</th>
                    <th scope="col" class="px-4 py-2.5 text-right text-small font-semibold text-content-muted dark:text-[#94A3B8]">Subtotal</th>
                  </tr>
                </thead>
                <tbody class="divide-y divide-edge dark:divide-[#1E293B]">
                  <tr v-for="item in order.items" :key="item.id">
                    <td class="px-4 py-2.5 text-body text-content dark:text-[#F8FAFC]">{{ item.productName }}</td>
                    <td class="px-4 py-2.5 text-right text-body text-content-muted dark:text-[#94A3B8]">{{ item.quantity }}</td>
                    <td class="px-4 py-2.5 text-right text-body text-content-muted dark:text-[#94A3B8]">
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
                <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatMoney(order.subtotal, order.currency) }}</dd>
              </div>
              <div class="flex items-baseline justify-between gap-4">
                <dt class="text-body text-content-muted dark:text-[#94A3B8]">Delivery fee</dt>
                <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatMoney(order.deliveryFee, order.currency) }}</dd>
              </div>
              <div class="flex items-baseline justify-between gap-4 border-t border-edge pt-1.5 dark:border-[#334155]">
                <dt class="text-body font-semibold text-content dark:text-[#F8FAFC]">Total</dt>
                <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">{{ formatMoney(order.totalAmount, order.currency) }}</dd>
              </div>
            </dl>
          </section>

          <section aria-labelledby="order-timeline">
            <h3 id="order-timeline" class="card-title">Timeline</h3>

            <p
              v-if="order.status === 'CANCELLED'"
              class="mt-2 rounded-control border border-red-200 bg-red-50 px-3 py-2 text-small text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
              role="alert"
            >
              This order was cancelled
              <template v-if="order.cancelledReason"> — {{ order.cancelledReason }}</template>.
            </p>

            <ol v-if="steps.length > 0" class="mt-3 space-y-0">
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
                    <span v-if="step.state === 'current'" class="text-small text-content-muted dark:text-[#94A3B8]">
                      current
                    </span>
                    <span v-if="step.state === 'pending'" class="text-small text-content-subtle dark:text-[#64748B]">
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
          <section class="rounded-card border border-edge p-5 dark:border-[#334155]" aria-labelledby="order-customer">
            <h3 id="order-customer" class="card-title">Customer</h3>
            <dl class="mt-2.5 space-y-2 text-body">
              <div>
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Name</dt>
                <dd class="text-content dark:text-[#F8FAFC]">
                  {{ customerName ?? `Customer ${order.customerId}` }}
                </dd>
              </div>
              <div>
                <dt class="text-small text-content-muted dark:text-[#94A3B8]">Delivery address</dt>
                <dd class="text-content dark:text-[#F8FAFC]">{{ order.deliveryAddress }}</dd>
              </div>
              <div class="flex gap-6">
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">City</dt>
                  <dd class="text-content dark:text-[#F8FAFC]">{{ order.city }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Postal code</dt>
                  <dd class="font-mono text-content dark:text-[#F8FAFC]">{{ order.postalCode }}</dd>
                </div>
              </div>
            </dl>
          </section>

          <section class="rounded-card border border-edge p-5 dark:border-[#334155]" aria-labelledby="order-delivery">
            <h3 id="order-delivery" class="card-title">Delivery</h3>

            <p v-if="order.deliveryId === undefined" class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
              No delivery has been created for this order yet.
            </p>

            <template v-else>
              <p class="mt-1 font-mono text-small text-content-muted dark:text-[#94A3B8]">
                #{{ order.deliveryId }}
              </p>

              <template v-if="delivery">
                <dl class="mt-2.5 space-y-2 text-body">
                  <div class="flex items-center justify-between gap-3">
                    <dt class="text-small text-content-muted dark:text-[#94A3B8]">Status</dt>
                    <dd><StatusBadge :meta="deliveryStatus(delivery.status)" /></dd>
                  </div>
                  <div>
                    <dt class="text-small text-content-muted dark:text-[#94A3B8]">Driver</dt>
                    <dd class="text-content dark:text-[#F8FAFC]">{{ delivery.driverName ?? 'Not assigned' }}</dd>
                  </div>
                  <div>
                    <dt class="text-small text-content-muted dark:text-[#94A3B8]">Vehicle</dt>
                    <dd class="font-mono text-content dark:text-[#F8FAFC]">
                      {{ delivery.vehicleRegistration ?? 'Not assigned' }}
                    </dd>
                  </div>
                  <div>
                    <dt class="text-small text-content-muted dark:text-[#94A3B8]">Started</dt>
                    <dd class="text-content dark:text-[#F8FAFC]">{{ formatDateTime(delivery.startedAt) }}</dd>
                  </div>
                  <div>
                    <dt class="text-small text-content-muted dark:text-[#94A3B8]">Completed</dt>
                    <dd class="text-content dark:text-[#F8FAFC]">{{ formatDateTime(delivery.completedAt) }}</dd>
                  </div>
                </dl>
              </template>

              <div v-else class="mt-2">
                <p v-if="deliveryError" class="text-small text-danger" role="alert">{{ deliveryError }}</p>
                <p v-else class="text-body text-content-muted dark:text-[#94A3B8]">
                  See the delivery for driver and vehicle detail.
                </p>
                <RouterLink
                  :to="{ name: 'admin-deliveries' }"
                  class="mt-1.5 inline-block text-small font-medium text-primary hover:underline"
                >
                  See the delivery
                </RouterLink>
              </div>
            </template>
          </section>
        </div>
      </div>
    </BaseCard>

    <ConfirmModal
      :open="cancelOpen"
      :title="order ? `Cancel order #${order.id}?` : 'Cancel order'"
      description="Cancelling releases the reserved stock and cannot be undone from this console."
      confirmLabel="Cancel the order"
      cancelLabel="Keep the order"
      :busy="cancelling"
      @cancel="cancelOpen = false"
      @confirm="confirmCancel"
    >
      <label for="detail-cancel-reason" class="mt-3 block text-small font-medium text-content dark:text-[#F8FAFC]">
        Reason
        <span class="text-danger" aria-hidden="true">*</span>
      </label>
      <textarea
        id="detail-cancel-reason"
        v-model="cancelReason"
        rows="2"
        :aria-invalid="Boolean(cancelError)"
        class="mt-1.5 w-full rounded-control border bg-surface px-3 py-2 text-body text-content focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 dark:bg-[#0F172A] dark:text-[#F8FAFC]"
        :class="cancelError ? 'border-danger' : 'border-edge dark:border-[#334155]'"
      />
      <p v-if="cancelError" class="mt-1.5 text-small text-danger" role="alert">{{ cancelError }}</p>
    </ConfirmModal>

    <ConfirmModal
      :open="statusOpen"
      tone="primary"
      title="Change the order status"
      description="The order service owns the transition rules and may refuse an illegal move."
      confirmLabel="Apply status"
      cancelLabel="Leave it"
      :busy="savingStatus"
      @cancel="statusOpen = false"
      @confirm="confirmStatus"
    >
      <div class="mt-3 space-y-3">
        <BaseSelect
          :modelValue="nextStatus"
          label="New status"
          :options="statusOptions"
          required
          @update:model-value="onNextStatusChange"
        />
        <BaseInput
          :modelValue="statusNote"
          label="Note"
          placeholder="Optional note added to the timeline"
          @update:model-value="onStatusNoteChange"
        />
        <p v-if="statusError" class="text-small text-danger" role="alert">{{ statusError }}</p>
      </div>
    </ConfirmModal>
  </div>
</template>