<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
import { customerApi } from '@/services/customerApi'
import { orderApi } from '@/services/orderApi'
import { useDebouncedRef } from '@/stores/asyncStore'
import { useOrderStore } from '@/stores/orderStore'
import type { OrderStatus } from '@/types'
import { deliveryLabel, formatDateTime, formatMoney, pluralize } from '@/utils/format'
import { isCancellable, orderStatus } from '@/utils/status'

/**
 * Order register.
 *
 * `OrderResponse` carries a customerId and no name, so names are resolved from a single
 * `customerApi.list({ size: 100 })` call held in a Map and reused for every page — one
 * request, not one per row, and not refetched when the search term changes.
 *
 * Sorting is done by the order service, which whitelists `createdAt`, `totalAmount` and
 * `status`. `orderStore.fetchOrders` cannot express it (its query has no `sort`), so the
 * page is requested here and written into the same store collection.
 */

const route = useRoute()
const orderStore = useOrderStore()

const STATUS_OPTIONS: { value: OrderStatus; label: string }[] = [
  { value: 'CREATED', label: 'Created' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'PROCESSING', label: 'Processing' },
  { value: 'READY_FOR_DELIVERY', label: 'Ready for delivery' },
  { value: 'OUT_FOR_DELIVERY', label: 'Out for delivery' },
  { value: 'DELIVERED', label: 'Delivered' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

type SortKey = 'createdAt' | 'totalAmount'
type SortDirection = 'asc' | 'desc'

const customerNames = ref(new Map<number, string>())
const customerNamesError = ref<string | null>(null)

/** The header search box hands its term over in the query string. */
const search = ref(typeof route.query.search === 'string' ? route.query.search : '')

const { debounced: debouncedSearch, watchSource: queueSearch } = useDebouncedRef(() => search.value, 300)

const filtersOpen = ref(false)
const sortKey = ref<SortKey>('createdAt')
const sortDirection = ref<SortDirection>('desc')

const cancelTarget = ref<number | null>(null)
const cancelReason = ref('')
const cancelReasonError = ref('')
const cancelling = ref(false)

const loading = ref(true)
const error = ref<string | null>(null)

let inFlight: AbortController | null = null

const columns = [
  { key: 'order', label: 'Order' },
  { key: 'customer', label: 'Customer' },
  { key: 'items', label: 'Items', align: 'right' as const },
  { key: 'amount', label: 'Amount', align: 'right' as const, sortable: true },
  { key: 'status', label: 'Status' },
  { key: 'created', label: 'Created', sortable: true },
  { key: 'delivery', label: 'Delivery' },
  { key: 'actions', label: 'Actions', align: 'right' as const },
]

const sortParam = computed(() => `${sortKey.value},${sortDirection.value}`)

const rows = computed(() => orderStore.orders)

const sortLabel = computed(() =>
  sortKey.value === 'totalAmount'
    ? `amount, ${sortDirection.value === 'asc' ? 'lowest first' : 'highest first'}`
    : `creation date, ${sortDirection.value === 'asc' ? 'oldest first' : 'newest first'}`,
)

const hasActiveFilters = computed(
  () =>
    Boolean(search.value.trim()) ||
    Boolean(orderStore.query.status) ||
    Boolean(orderStore.query.from) ||
    Boolean(orderStore.query.to),
)

function customerName(customerId: number): string {
  return customerNames.value.get(customerId) ?? `Customer ${customerId}`
}

async function loadCustomerNames(): Promise<void> {
  customerNamesError.value = null
  try {
    const page = await customerApi.list({ size: 100 })
    customerNames.value = new Map(
      page.content.map((customer) => [customer.id, `${customer.firstName} ${customer.lastName}`]),
    )
  } catch (caught) {
    customerNamesError.value =
      caught instanceof ApiError ? caught.message : 'Customer names could not be loaded.'
  }
}

async function loadOrders(): Promise<void> {
  inFlight?.abort()
  inFlight = new AbortController()
  loading.value = true
  error.value = null
  try {
    const page = await orderApi.list({ ...orderStore.query, sort: sortParam.value }, inFlight.signal)
    orderStore.orders = page.content
    orderStore.totalElements = page.totalElements
    orderStore.totalPages = page.totalPages
  } catch (caught) {
    if (caught instanceof DOMException && caught.name === 'AbortError') {
      return
    }
    error.value = caught instanceof ApiError ? caught.message : 'Unable to load orders right now.'
  } finally {
    loading.value = false
  }
}

/** Any filter change returns to page 0, otherwise a narrow result set can page past its end. */
function refresh(): void {
  orderStore.query.page = 0
  void loadOrders()
}

function onSearchInput(value: string | number | null): void {
  search.value = String(value ?? '')
  queueSearch(search.value)
}

function onStatusChange(value: string | number | null): void {
  orderStore.query.status = (value ?? '') as OrderStatus | ''
  refresh()
}

function onFromDate(value: string | number | null): void {
  orderStore.query.from = String(value ?? '')
  refresh()
}

function onToDate(value: string | number | null): void {
  orderStore.query.to = String(value ?? '')
  refresh()
}

function onSort(key: string): void {
  if (key !== 'createdAt' && key !== 'totalAmount') {
    return
  }
  if (sortKey.value === key) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDirection.value = 'desc'
  }
  refresh()
}

function goToPage(next: number): void {
  orderStore.query.page = next
  void loadOrders()
}

function clearFilters(): void {
  search.value = ''
  orderStore.resetFilters()
  void loadOrders()
}

function openCancel(orderId: number): void {
  cancelTarget.value = orderId
  cancelReason.value = ''
  cancelReasonError.value = ''
}

function closeCancel(): void {
  cancelTarget.value = null
  cancelReason.value = ''
  cancelReasonError.value = ''
}

async function confirmCancel(): Promise<void> {
  if (cancelTarget.value === null) {
    return
  }
  if (!cancelReason.value.trim()) {
    cancelReasonError.value = 'A reason is required so the customer timeline records why.'
    return
  }
  cancelling.value = true
  const done = await orderStore.cancelOrder(cancelTarget.value, cancelReason.value.trim())
  cancelling.value = false
  if (done) {
    closeCancel()
    return
  }
  cancelReasonError.value = 'The order could not be cancelled. It may no longer be in a cancellable state.'
}

watch(debouncedSearch, (value) => {
  const term = value.trim()
  if (term === orderStore.query.search) {
    return
  }
  orderStore.query.search = term
  refresh()
})

watch(
  () => route.query.search,
  (value) => {
    const term = typeof value === 'string' ? value : ''
    if (term !== search.value) {
      search.value = term
      queueSearch(term)
    }
  },
)

onMounted(() => {
  // The store is shared with the customer screens, so the filters start from a known
  // state and only the search term carried in the query string survives.
  orderStore.resetFilters()
  orderStore.query.search = search.value.trim()
  void loadCustomerNames()
  void loadOrders()
})
</script>

<template>
  <div class="space-y-4">
    <BaseCard title="Order register" subtitle="Filter the book, then act on a single order" :padded="false">
      <template #actions>
        <BaseButton size="sm" variant="ghost" class="lg:hidden" @click="filtersOpen = !filtersOpen">
          {{ filtersOpen ? 'Hide filters' : 'Filters' }}
        </BaseButton>
      </template>

      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div
          class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4"
          :class="filtersOpen ? 'grid' : 'hidden lg:grid'"
        >
          <BaseInput
            :modelValue="search"
            type="search"
            label="Search"
            placeholder="Order id, address or city"
            hint="The order service matches the order id, delivery address and city — not the customer name."
            @update:model-value="onSearchInput"
          />
          <BaseSelect
            :modelValue="orderStore.query.status"
            label="Status"
            :options="STATUS_OPTIONS"
            clearable
            @update:model-value="onStatusChange"
          />
          <BaseInput
            :modelValue="orderStore.query.from"
            type="date"
            label="Created from"
            @update:model-value="onFromDate"
          />
          <BaseInput
            :modelValue="orderStore.query.to"
            type="date"
            label="Created to"
            @update:model-value="onToDate"
          />
        </div>

        <div class="mt-3 flex flex-wrap items-center gap-3">
          <p class="text-small text-content-subtle dark:text-[#64748B]">
            Sorted by {{ sortLabel }} across all matching orders.
          </p>
          <BaseButton v-if="hasActiveFilters" size="sm" variant="ghost" @click="clearFilters">
            Clear filters
          </BaseButton>
          <p v-if="customerNamesError" class="text-small text-danger" role="alert">
            {{ customerNamesError }}
          </p>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="rows"
        rowKey="id"
        :loading="loading"
        :error="error"
        emptyTitle="No orders match these filters"
        emptyDescription="Widen the date range, pick another status, or clear the search."
        emptyIcon="orders"
        emptyActionLabel="Clear filters"
        caption="Orders matching the current filters"
        @retry="loadOrders()"
        @empty-action="clearFilters"
        @sort="onSort"
      >
        <template #cell-order="{ row }">
          <RouterLink
            :to="{ name: 'admin-order-detail', params: { id: row.id } }"
            class="font-mono text-small font-medium text-primary hover:underline"
          >
            #{{ row.id }}
          </RouterLink>
        </template>

        <template #cell-customer="{ row }">
          <span class="text-body text-content dark:text-[#F8FAFC]">{{ customerName(row.customerId) }}</span>
        </template>

        <template #cell-items="{ row }">
          <span class="text-body text-content-muted dark:text-[#94A3B8]">
            {{ pluralize(row.itemCount, 'item') }}
          </span>
        </template>

        <template #cell-amount="{ row }">
          <span class="whitespace-nowrap font-medium text-content dark:text-[#F8FAFC]">
            {{ formatMoney(row.totalAmount, row.currency) }}
          </span>
        </template>

        <template #cell-status="{ row }">
          <StatusBadge :meta="orderStatus(row.status)" />
        </template>

        <template #cell-created="{ row }">
          <span class="whitespace-nowrap text-small text-content-muted dark:text-[#94A3B8]">
            {{ formatDateTime(row.createdAt) }}
          </span>
        </template>

        <template #cell-delivery="{ row }">
          <span class="font-mono text-small text-content-muted dark:text-[#94A3B8]">
            {{ deliveryLabel(row.deliveryId) }}
          </span>
        </template>

        <template #cell-actions="{ row }">
          <div class="flex items-center justify-end gap-1.5">
            <RouterLink
              :to="{ name: 'admin-order-detail', params: { id: row.id } }"
              class="inline-flex h-8 items-center rounded-control px-3 text-small font-medium text-primary hover:bg-surface-muted dark:hover:bg-[#1E293B]"
            >
              View
            </RouterLink>
            <BaseButton
              v-if="isCancellable(row.status)"
              size="sm"
              variant="ghost"
              :aria-label="`Cancel order ${row.id}`"
              @click="openCancel(row.id)"
            >
              Cancel
            </BaseButton>
          </div>
        </template>
      </DataTable>

      <TablePagination
        :page="orderStore.query.page"
        :totalPages="orderStore.totalPages"
        :totalElements="orderStore.totalElements"
        :size="orderStore.query.size"
        label="orders"
        @change="goToPage"
      />
    </BaseCard>

    <ConfirmModal
      :open="cancelTarget !== null"
      title="Cancel this order?"
      :description="
        cancelTarget === null
          ? ''
          : `Order #${cancelTarget} will be cancelled and its reserved stock released.`
      "
      confirmLabel="Cancel order"
      cancelLabel="Keep the order"
      :busy="cancelling"
      @cancel="closeCancel"
      @confirm="confirmCancel"
    >
      <label for="cancel-reason" class="mt-3 block text-small font-medium text-content dark:text-[#F8FAFC]">
        Reason
        <span class="text-danger" aria-hidden="true">*</span>
      </label>
      <textarea
        id="cancel-reason"
        v-model="cancelReason"
        rows="2"
        :aria-invalid="Boolean(cancelReasonError)"
        :aria-describedby="cancelReasonError ? 'cancel-reason-error' : undefined"
        class="mt-1.5 w-full rounded-control border bg-surface px-3 py-2 text-body text-content focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 dark:bg-[#0F172A] dark:text-[#F8FAFC]"
        :class="cancelReasonError ? 'border-danger' : 'border-edge dark:border-[#334155]'"
      />
      <p v-if="cancelReasonError" id="cancel-reason-error" class="mt-1.5 text-small text-danger" role="alert">
        {{ cancelReasonError }}
      </p>
    </ConfirmModal>
  </div>
</template>