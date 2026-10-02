<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import DataTable from '@/components/ui/DataTable.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { ApiError } from '@/services/api'
import { deliveryApi } from '@/services/deliveryApi'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { useToastStore } from '@/stores/toastStore'
import type { DeliveryResponse, DeliveryStatus } from '@/types'
import { formatDateTime, pluralize } from '@/utils/format'
import { deliveryStatus } from '@/utils/status'

/**
 * Delivery board.
 *
 * Assignment and cancellation call the API directly rather than the store actions: the
 * store discards the exception, and a 409 ("that driver was just taken") has to reach the
 * operator next to the control they used, not only in a toast. Every read still goes
 * through the store.
 */

const STATUS_OPTIONS: { value: DeliveryStatus; label: string }[] = [
  { value: 'CREATED', label: 'Awaiting dispatch' },
  { value: 'ASSIGNED', label: 'Assigned' },
  { value: 'PICKED_UP', label: 'Picked up' },
  { value: 'IN_TRANSIT', label: 'In transit' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'FAILED', label: 'Failed' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

/**
 * Visibility rules copied from the delivery service's own transition table:
 * `POST /{id}/assign` accepts CREATED only, and CANCELLED is reachable from CREATED and
 * ASSIGNED alone (PICKED_UP and IN_TRANSIT may only go to IN_TRANSIT / DELIVERED / FAILED).
 * The server still owns the decision — its 409 wording is what the operator sees.
 */
const ASSIGNABLE: DeliveryStatus[] = ['CREATED']
const CANCELLABLE: DeliveryStatus[] = ['CREATED', 'ASSIGNED']

const deliveryStore = useDeliveryStore()
const toasts = useToastStore()
const route = useRoute()

const columns = [
  { key: 'delivery', label: 'Delivery' },
  { key: 'order', label: 'Order' },
  { key: 'driver', label: 'Driver' },
  { key: 'vehicle', label: 'Vehicle' },
  { key: 'status', label: 'Status' },
  { key: 'started', label: 'Started' },
  { key: 'destination', label: 'Destination' },
  { key: 'actions', label: 'Actions', align: 'right' as const },
]

const filtersOpen = ref(false)
const detail = ref<DeliveryResponse | null>(null)
const drawer = ref<HTMLElement | null>(null)

const assignTarget = ref<DeliveryResponse | null>(null)
const assignDriverId = ref<number | null>(null)
const assignVehicleId = ref<number | null>(null)
const assignError = ref('')
const assigning = ref(false)

const cancelTarget = ref<DeliveryResponse | null>(null)
const cancelReason = ref('')
const cancelError = ref('')
const cancelling = ref(false)

const driverOptions = computed(() =>
  deliveryStore.assignableDrivers.map((driver) => ({
    value: driver.id,
    label: `${driver.fullName} · ${driver.phone}`,
  })),
)

const vehicleOptions = computed(() =>
  deliveryStore.assignableVehicles.map((vehicle) => ({
    value: vehicle.id,
    label: vehicle.registrationNumber,
  })),
)

const driverFilterOptions = computed(() =>
  deliveryStore.drivers.map((driver) => ({ value: driver.id, label: driver.fullName })),
)

const vehicleFilterOptions = computed(() =>
  deliveryStore.vehicles.map((vehicle) => ({ value: vehicle.id, label: vehicle.registrationNumber })),
)

const activeFilterCount = computed(
  () =>
    Number(Boolean(deliveryStore.query.status)) +
    Number(deliveryStore.query.driverId !== undefined) +
    Number(deliveryStore.query.vehicleId !== undefined) +
    Number(Boolean(deliveryStore.query.from)) +
    Number(Boolean(deliveryStore.query.to)),
)

async function refreshAll(): Promise<void> {
  await Promise.all([
    deliveryStore.fetchDeliveries(),
    deliveryStore.fetchActive(),
    deliveryStore.fetchDrivers({ size: 100 }),
    deliveryStore.fetchVehicles(),
  ])
}

function onStatusChange(value: string | number | null): void {
  deliveryStore.query.status = (value ?? '') as DeliveryStatus | ''
  deliveryStore.query.page = 0
  void deliveryStore.fetchDeliveries()
}

function onDriverChange(value: string | number | null): void {
  deliveryStore.query.driverId = value === null || value === '' ? undefined : Number(value)
  deliveryStore.query.page = 0
  void deliveryStore.fetchDeliveries()
}

function onVehicleChange(value: string | number | null): void {
  deliveryStore.query.vehicleId = value === null || value === '' ? undefined : Number(value)
  deliveryStore.query.page = 0
  void deliveryStore.fetchDeliveries()
}

function onFromDate(value: string | number | null): void {
  deliveryStore.query.from = String(value ?? '')
  deliveryStore.query.page = 0
  void deliveryStore.fetchDeliveries()
}

function onToDate(value: string | number | null): void {
  deliveryStore.query.to = String(value ?? '')
  deliveryStore.query.page = 0
  void deliveryStore.fetchDeliveries()
}

function goToPage(next: number): void {
  deliveryStore.query.page = next
  void deliveryStore.fetchDeliveries()
}

function clearFilters(): void {
  deliveryStore.resetFilters()
  void deliveryStore.fetchDeliveries()
}

function openAssign(delivery: DeliveryResponse): void {
  assignTarget.value = delivery
  assignDriverId.value = delivery.driverId ?? null
  assignVehicleId.value = delivery.vehicleId ?? null
  assignError.value = ''
}

async function confirmAssign(): Promise<void> {
  const target = assignTarget.value
  if (!target) {
    return
  }
  if (assignDriverId.value === null || assignVehicleId.value === null) {
    assignError.value = 'Pick both a driver and a vehicle.'
    return
  }
  assigning.value = true
  assignError.value = ''
  try {
    await deliveryApi.assign(target.id, assignDriverId.value, assignVehicleId.value)
    toasts.success('Driver and vehicle assigned', 'The driver has been notified.')
    assignTarget.value = null
    await refreshAll()
  } catch (caught) {
    // A 409 here means somebody else claimed the driver a moment ago; their wording wins.
    assignError.value =
      caught instanceof ApiError ? caught.message : 'The assignment was refused by the server.'
    toasts.fromException('Unable to assign the delivery', caught)
    await refreshAll()
  } finally {
    assigning.value = false
  }
}

function openCancel(delivery: DeliveryResponse): void {
  cancelTarget.value = delivery
  cancelReason.value = ''
  cancelError.value = ''
}

async function confirmCancel(): Promise<void> {
  const target = cancelTarget.value
  if (!target) {
    return
  }
  if (!cancelReason.value.trim()) {
    cancelError.value = 'A reason is required so the delivery record explains itself.'
    return
  }
  cancelling.value = true
  cancelError.value = ''
  try {
    await deliveryApi.cancel(target.id, cancelReason.value.trim())
    toasts.success(`Delivery #${target.id} cancelled`)
    cancelTarget.value = null
    if (detail.value?.id === target.id) {
      detail.value = null
    }
    await refreshAll()
  } catch (caught) {
    cancelError.value =
      caught instanceof ApiError ? caught.message : 'The delivery could not be cancelled.'
    toasts.fromException(`Unable to cancel delivery #${target.id}`, caught)
  } finally {
    cancelling.value = false
  }
}

function openDetail(delivery: DeliveryResponse): void {
  detail.value = delivery
  void nextTick(() => drawer.value?.focus())
}

function onEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape' && detail.value) {
    detail.value = null
  }
}

watch(
  () => route.query.driver,
  (value) => {
    const driverId = typeof value === 'string' ? Number(value) : Number.NaN
    if (Number.isFinite(driverId)) {
      deliveryStore.query.driverId = driverId
      deliveryStore.query.page = 0
      void deliveryStore.fetchDeliveries()
    }
  },
)

onMounted(() => {
  // The drivers screen hands a driver over in the query string; otherwise the board opens
  // unfiltered rather than on whatever this shared store was left holding.
  const driverId = typeof route.query.driver === 'string' ? Number(route.query.driver) : Number.NaN
  if (Number.isFinite(driverId)) {
    deliveryStore.query.driverId = driverId
  } else {
    deliveryStore.resetFilters()
  }
  document.addEventListener('keydown', onEscape)
  void refreshAll()
})

onBeforeUnmount(() => document.removeEventListener('keydown', onEscape))
</script>

<template>
  <div class="space-y-4">
    <BaseCard title="Deliveries" subtitle="Dispatch board for every delivery the service knows about" :padded="false">
      <template #actions>
        <BaseButton size="sm" variant="ghost" class="lg:hidden" @click="filtersOpen = !filtersOpen">
          {{ filtersOpen ? 'Hide filters' : `Filters${activeFilterCount ? ` (${activeFilterCount})` : ''}` }}
        </BaseButton>
      </template>

      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div
          class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5"
          :class="filtersOpen ? 'grid' : 'hidden lg:grid'"
        >
          <BaseSelect
            :modelValue="deliveryStore.query.status"
            label="Status"
            :options="STATUS_OPTIONS"
            clearable
            @update:model-value="onStatusChange"
          />
          <BaseSelect
            :modelValue="deliveryStore.query.driverId ?? null"
            label="Driver"
            :options="driverFilterOptions"
            clearable
            @update:model-value="onDriverChange"
          />
          <BaseSelect
            :modelValue="deliveryStore.query.vehicleId ?? null"
            label="Vehicle"
            :options="vehicleFilterOptions"
            clearable
            @update:model-value="onVehicleChange"
          />
          <BaseInput
            :modelValue="deliveryStore.query.from"
            type="date"
            label="From"
            @update:model-value="onFromDate"
          />
          <BaseInput
            :modelValue="deliveryStore.query.to"
            type="date"
            label="To"
            @update:model-value="onToDate"
          />
        </div>

        <div class="mt-3 flex flex-wrap items-center gap-3">
          <BaseButton size="sm" variant="ghost" @click="clearFilters">Clear filters</BaseButton>
          <p class="text-small text-content-subtle dark:text-[#64748B]">
            Select a row for the full delivery record.
          </p>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="deliveryStore.deliveries"
        rowKey="id"
        clickable
        :loading="deliveryStore.loading"
        :error="deliveryStore.error"
        emptyTitle="No deliveries match these filters"
        emptyDescription="Clear the filters, or widen the date range to see more of the board."
        emptyIcon="deliveries"
        emptyActionLabel="Clear filters"
        caption="Delivery board"
        @retry="deliveryStore.fetchDeliveries()"
        @empty-action="clearFilters"
        @row-click="openDetail"
      >
        <template #cell-delivery="{ row }">
          <span class="font-mono text-small font-medium text-content dark:text-[#F8FAFC]">#{{ row.id }}</span>
        </template>

        <template #cell-order="{ row }">
          <RouterLink
            :to="{ name: 'admin-order-detail', params: { id: row.orderId } }"
            class="font-mono text-small text-primary hover:underline"
            @click.stop
          >
            #{{ row.orderId }}
          </RouterLink>
        </template>

        <template #cell-driver="{ row }">
          <span class="text-body text-content dark:text-[#F8FAFC]">{{ row.driverName ?? 'Unassigned' }}</span>
        </template>

        <template #cell-vehicle="{ row }">
          <span class="font-mono text-body text-content-muted dark:text-[#94A3B8]">
            {{ row.vehicleRegistration ?? '—' }}
          </span>
        </template>

        <template #cell-status="{ row }">
          <StatusBadge :meta="deliveryStatus(row.status)" />
        </template>

        <template #cell-started="{ row }">
          <span class="whitespace-nowrap text-small text-content-muted dark:text-[#94A3B8]">
            {{ formatDateTime(row.startedAt) }}
          </span>
        </template>

        <template #cell-destination="{ row }">
          <span class="block max-w-48 truncate text-body text-content-muted dark:text-[#94A3B8]">
            {{ row.deliveryAddress }}, {{ row.city }}
          </span>
        </template>

        <template #cell-actions="{ row }">
          <div class="flex items-center justify-end gap-1.5" @click.stop>
            <BaseButton
              v-if="ASSIGNABLE.includes(row.status)"
              size="sm"
              variant="secondary"
              :aria-label="`Assign driver and vehicle to delivery ${row.id}`"
              @click="openAssign(row)"
            >
              Assign
            </BaseButton>
            <BaseButton
              v-if="CANCELLABLE.includes(row.status)"
              size="sm"
              variant="ghost"
              :aria-label="`Cancel delivery ${row.id}`"
              @click="openCancel(row)"
            >
              Cancel
            </BaseButton>
            <span
              v-if="!ASSIGNABLE.includes(row.status) && !CANCELLABLE.includes(row.status)"
              class="text-small text-content-subtle dark:text-[#64748B]"
            >
              Closed
            </span>
          </div>
        </template>
      </DataTable>

      <TablePagination
        :page="deliveryStore.query.page"
        :totalPages="deliveryStore.totalPages"
        :totalElements="deliveryStore.totalElements"
        :size="deliveryStore.query.size"
        label="deliveries"
        @change="goToPage"
      />
    </BaseCard>

    <!-- Assign: only the store's AVAILABLE-filtered drivers and vehicles are offered. -->
    <ConfirmModal
      :open="assignTarget !== null"
      tone="primary"
      title="Assign driver and vehicle"
      :description="
        assignTarget === null
          ? ''
          : `Delivery #${assignTarget.id} for order #${assignTarget.orderId}. Only resources the service reports as available can be chosen.`
      "
      confirmLabel="Assign"
      cancelLabel="Discard"
      :busy="assigning"
      @cancel="assignTarget = null"
      @confirm="confirmAssign"
    >
      <div class="mt-3 space-y-3">
        <BaseSelect
          :modelValue="assignDriverId"
          label="Driver"
          :options="driverOptions"
          clearable
          required
          @update:model-value="
            assignDriverId = $event === null || $event === '' ? null : Number($event)
          "
        />
        <BaseSelect
          :modelValue="assignVehicleId"
          label="Vehicle"
          :options="vehicleOptions"
          clearable
          required
          @update:model-value="
            assignVehicleId = $event === null || $event === '' ? null : Number($event)
          "
        />
        <p v-if="assignError" class="text-small text-danger" role="alert">{{ assignError }}</p>
        <p v-else class="text-small text-content-muted dark:text-[#94A3B8]">
          <template v-if="driverOptions.length === 0 || vehicleOptions.length === 0">
            Nothing can be assigned: {{ pluralize(driverOptions.length, 'driver') }},
            {{ pluralize(vehicleOptions.length, 'vehicle') }} available.
          </template>
          <template v-else>
            {{ pluralize(driverOptions.length, 'driver') }} and
            {{ pluralize(vehicleOptions.length, 'vehicle') }} can be assigned right now.
          </template>
        </p>
      </div>
    </ConfirmModal>

    <ConfirmModal
      :open="cancelTarget !== null"
      title="Cancel this delivery?"
      :description="
        cancelTarget === null ? '' : `Delivery #${cancelTarget.id} will be cancelled and the order has to be re-dispatched.`
      "
      confirmLabel="Cancel the delivery"
      cancelLabel="Keep it running"
      :busy="cancelling"
      @cancel="cancelTarget = null"
      @confirm="confirmCancel"
    >
      <label for="delivery-cancel-reason" class="mt-3 block text-small font-medium text-content dark:text-[#F8FAFC]">
        Reason
        <span class="text-danger" aria-hidden="true">*</span>
      </label>
      <textarea
        id="delivery-cancel-reason"
        v-model="cancelReason"
        rows="2"
        :aria-invalid="Boolean(cancelError)"
        class="mt-1.5 w-full rounded-control border bg-surface px-3 py-2 text-body text-content focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 dark:bg-[#0F172A] dark:text-[#F8FAFC]"
        :class="cancelError ? 'border-danger' : 'border-edge dark:border-[#334155]'"
      />
      <p v-if="cancelError" class="mt-1.5 text-small text-danger" role="alert">{{ cancelError }}</p>
    </ConfirmModal>

    <!--
      The foundation ships ConfirmModal for decisions but no read-only dialog, so the
      record drill-down is the one overlay built here.
    -->
    <Teleport to="body">
      <Transition
        enterActiveClass="transition-opacity duration-150"
        enterFromClass="opacity-0"
        leaveActiveClass="transition-opacity duration-150"
        leaveToClass="opacity-0"
      >
        <div
          v-if="detail"
          class="fixed inset-0 z-50 flex justify-end bg-slate-900/40"
          @click.self="detail = null"
        >
          <div
            ref="drawer"
            tabindex="-1"
            role="dialog"
            aria-modal="true"
            aria-labelledby="delivery-detail-title"
            class="flex h-full w-full max-w-md flex-col overflow-y-auto border-l border-edge bg-surface shadow-overlay outline-none dark:border-[#334155] dark:bg-[#111827]"
          >
            <header class="flex items-start justify-between gap-3 border-b border-edge px-5 py-4 dark:border-[#334155]">
              <div class="min-w-0">
                <h2 id="delivery-detail-title" class="section-title">Delivery #{{ detail.id }}</h2>
                <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                  Order #{{ detail.orderId }} · created {{ formatDateTime(detail.createdAt) }}
                </p>
              </div>
              <button
                type="button"
                class="flex h-8 w-8 shrink-0 items-center justify-center rounded-control text-content-muted hover:bg-surface-muted dark:hover:bg-[#1E293B]"
                aria-label="Close the delivery record"
                @click="detail = null"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                  <path d="m6 6 12 12M18 6 6 18" stroke-linecap="round" />
                </svg>
              </button>
            </header>

            <div class="flex-1 space-y-5 px-5 py-4">
              <div class="flex flex-wrap items-center gap-2">
                <StatusBadge :meta="deliveryStatus(detail.status)" size="md" />
                <RouterLink
                  :to="{ name: 'admin-tracking', query: { delivery: String(detail.id) } }"
                  class="text-small font-medium text-primary hover:underline"
                >
                  Track live
                </RouterLink>
              </div>

              <dl class="grid grid-cols-2 gap-x-4 gap-y-3">
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Driver</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ detail.driverName ?? 'Unassigned' }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Vehicle</dt>
                  <dd class="font-mono text-body text-content dark:text-[#F8FAFC]">
                    {{ detail.vehicleRegistration ?? '—' }}
                  </dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Customer</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ detail.customerName ?? '—' }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Customer phone</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    <a v-if="detail.customerPhone" :href="`tel:${detail.customerPhone}`" class="hover:text-primary">
                      {{ detail.customerPhone }}
                    </a>
                    <template v-else>—</template>
                  </dd>
                </div>
                <div class="col-span-2">
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Pickup</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ detail.pickupAddress }}</dd>
                </div>
                <div class="col-span-2">
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Destination</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">
                    {{ detail.deliveryAddress }}, {{ detail.city }} {{ detail.postalCode }}
                  </dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Scheduled</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatDateTime(detail.scheduledAt) }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Started</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatDateTime(detail.startedAt) }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Completed</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatDateTime(detail.completedAt) }}</dd>
                </div>
                <div>
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Last update</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ formatDateTime(detail.updatedAt) }}</dd>
                </div>
                <div v-if="detail.failureReason" class="col-span-2">
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Failure reason</dt>
                  <dd class="text-body text-danger">{{ detail.failureReason }}</dd>
                </div>
                <div v-if="detail.proofOfDelivery" class="col-span-2">
                  <dt class="text-small text-content-muted dark:text-[#94A3B8]">Proof of delivery</dt>
                  <dd class="text-body text-content dark:text-[#F8FAFC]">{{ detail.proofOfDelivery }}</dd>
                </div>
              </dl>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>