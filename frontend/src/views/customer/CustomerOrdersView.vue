<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { useOrderStore } from '@/stores/orderStore'
import type { OrderStatus } from '@/types'
import { formatDate, formatMoney, orderLabel, pluralize } from '@/utils/format'
import { orderStatus } from '@/utils/status'

/**
 * My orders.
 *
 * Cards rather than a table: this screen is used on a phone, where four columns of type
 * and three actions per row is unreadable. The filter and the paging live in the shared
 * order store, so a link or a back-navigation asks the backend for the same slice the
 * operations console would.
 */

const STATUS_OPTIONS: OrderStatus[] = [
  'CREATED',
  'CONFIRMED',
  'PROCESSING',
  'READY_FOR_DELIVERY',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'CANCELLED',
]

const orderStore = useOrderStore()
const router = useRouter()

const statusFilter = ref<OrderStatus | ''>(orderStore.query.status)

const statusChoices = computed(() =>
  STATUS_OPTIONS.map((status) => ({ value: status, label: orderStatus(status).label })),
)

/** Distinguishes "you have never ordered" from "this filter matched nothing". */
const filteredEmpty = computed(() => statusFilter.value !== '')

const filteredLabel = computed(() =>
  statusFilter.value === '' ? '' : orderStatus(statusFilter.value as OrderStatus).label.toLowerCase(),
)

const ACTION_LINK =
  'inline-flex h-9 items-center justify-center gap-1.5 rounded-control px-3 text-small font-medium transition-colors duration-150'

function onStatusChange(value: string | number | null): void {
  statusFilter.value = value === null || value === '' ? '' : (String(value) as OrderStatus)
  orderStore.query.status = statusFilter.value
  // Any filter change returns to page 0, otherwise a narrow result set pages past its end.
  orderStore.query.page = 0
  void orderStore.fetchOrders()
}

function clearFilter(): void {
  statusFilter.value = ''
  orderStore.query.status = ''
  orderStore.query.page = 0
  void orderStore.fetchOrders()
}

onMounted(() => {
  void orderStore.fetchOrders()
})
</script>

<template>
  <div class="space-y-4">
    <header class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h2 class="page-title">My orders</h2>
        <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
          <template v-if="orderStore.totalElements > 0 && !orderStore.loading">
            {{ pluralize(orderStore.totalElements, 'order') }}
            <template v-if="filteredLabel"> currently shown</template>
          </template>
          <template v-else>Every order you have placed with FleetFlow.</template>
        </p>
      </div>

      <RouterLink
        :to="{ name: 'customer-checkout' }"
        class="inline-flex h-10 w-full shrink-0 items-center justify-center gap-2 rounded-control bg-primary px-4 text-body font-medium text-white shadow-card transition-colors duration-150 hover:bg-primary-dark sm:w-auto"
      >
        New order
      </RouterLink>
    </header>

    <BaseCard as="section" :padded="false">
      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <BaseSelect
          :modelValue="statusFilter"
          label="Filter by status"
          placeholder="All statuses"
          :options="statusChoices"
          @update:model-value="onStatusChange"
        />
      </div>

      <BaseSkeleton
        v-if="orderStore.loading"
        variant="cards"
        :rows="3"
        label="Loading your orders"
      />

      <ErrorState
        v-else-if="orderStore.error"
        :message="orderStore.error"
        retryLabel="Try again"
        @retry="orderStore.fetchOrders()"
      />

      <template v-else>
        <EmptyState
          v-if="orderStore.orders.length === 0 && filteredEmpty"
          title="Nothing in this status"
          :description="`You have no ${filteredLabel} orders right now. Clear the filter to see all of your orders.`"
          actionLabel="Clear the filter"
          icon="search"
          @action="clearFilter"
        />

        <EmptyState
          v-else-if="orderStore.orders.length === 0"
          title="Nothing here yet"
          description="No orders yet — your orders will appear here once you place your first order."
          actionLabel="Create an order"
          icon="orders"
          @action="router.push({ name: 'customer-checkout' })"
        />

        <ul v-else class="grid gap-3 p-4 sm:p-5">
          <li v-for="order in orderStore.orders" :key="order.id" class="card overflow-hidden">
            <RouterLink
              :to="{ name: 'customer-order-detail', params: { id: order.id } }"
              class="block px-5 py-4 transition-colors duration-150 hover:bg-surface-muted dark:hover:bg-[#1A2436]"
            >
              <div class="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
                <div class="min-w-0">
                  <p class="font-mono text-body font-semibold text-content dark:text-[#F8FAFC]">
                    {{ orderLabel(order.id) }}
                  </p>
                  <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                    Placed {{ formatDate(order.createdAt) }} ·
                    {{ pluralize(order.itemCount, 'item') }}
                  </p>
                </div>
                <StatusBadge :meta="orderStatus(order.status)" />
              </div>

              <p class="mt-2 truncate text-small text-content-muted dark:text-[#94A3B8]">
                {{ order.deliveryAddress }}, {{ order.city }}
              </p>
            </RouterLink>

            <div
              class="flex flex-wrap items-center justify-between gap-2 border-t border-edge px-5 py-3 dark:border-[#334155]"
            >
              <p class="text-body font-semibold text-content dark:text-[#F8FAFC]">
                {{ formatMoney(order.totalAmount, order.currency) }}
              </p>

              <div class="flex flex-wrap items-center gap-2">
                <RouterLink
                  v-if="order.deliveryId !== undefined"
                  :to="{ name: 'customer-tracking', params: { deliveryId: order.deliveryId } }"
                  :class="[
                    ACTION_LINK,
                    'bg-blue-50 text-primary hover:bg-blue-100 dark:bg-blue-950/60 dark:text-blue-300 dark:hover:bg-blue-900/50',
                  ]"
                >
                  <svg
                    class="h-4 w-4"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    stroke-width="1.8"
                    aria-hidden="true"
                  >
                    <path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round" />
                    <circle cx="7" cy="18" r="1.4" />
                    <circle cx="17" cy="18" r="1.4" />
                  </svg>
                  Track
                </RouterLink>

                <RouterLink
                  :to="{ name: 'customer-order-detail', params: { id: order.id } }"
                  :class="[
                    ACTION_LINK,
                    'bg-surface text-content ring-1 ring-inset ring-edge hover:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:hover:bg-[#1E293B]',
                  ]"
                >
                  View details
                </RouterLink>
              </div>
            </div>
          </li>
        </ul>

        <TablePagination
          :page="orderStore.query.page"
          :totalPages="orderStore.totalPages"
          :totalElements="orderStore.totalElements"
          :size="orderStore.query.size"
          label="orders"
          @change="orderStore.goToPage($event)"
        />
      </template>
    </BaseCard>
  </div>
</template>
