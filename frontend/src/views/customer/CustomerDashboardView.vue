<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { useAuthStore } from '@/stores/authStore'
import { useOrderStore } from '@/stores/orderStore'
import type { OrderResponse, OrderStatus } from '@/types'
import { deliveryLabel, formatDate, formatMoney, orderLabel, pluralize } from '@/utils/format'
import { orderStatus } from '@/utils/status'

/**
 * Customer home.
 *
 * The whole screen is the customer's own order list, so it reads the shared order store
 * rather than fetching again: the dashboard and the orders screen then always agree on
 * what "my orders" means. Filters are reset on entry because a status filter left behind
 * by the orders screen would hide the one order worth surfacing here — the active delivery.
 */

const RECENT_LIMIT = 5

/** Statuses that mean "a driver is, or is about to be, on this order". */
const ACTIVE_STATUSES: OrderStatus[] = ['READY_FOR_DELIVERY', 'OUT_FOR_DELIVERY']

const auth = useAuthStore()
const orderStore = useOrderStore()
const router = useRouter()

const firstName = computed(() => auth.user?.firstName ?? 'there')

/** The server sorts by createdAt desc, so the first match on the page is the newest active one. */
const activeOrder = computed<OrderResponse | null>(
  () =>
    orderStore.orders.find(
      (order) => ACTIVE_STATUSES.includes(order.status) && order.deliveryId !== undefined,
    ) ?? null,
)

const recentOrders = computed(() => orderStore.orders.slice(0, RECENT_LIMIT))

onMounted(async () => {
  orderStore.resetFilters()
  await orderStore.fetchOrders()
})
</script>

<template>
  <div class="space-y-4">
    <header>
      <h2 class="page-title">Welcome back, {{ firstName }}</h2>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        Follow a delivery in progress or start a new order.
      </p>
    </header>

    <BaseSkeleton v-if="orderStore.loading" variant="cards" :rows="3" label="Loading your orders" />

    <BaseCard v-else-if="orderStore.error" :padded="false" as="section">
      <ErrorState
        :message="orderStore.error"
        retryLabel="Try again"
        @retry="orderStore.fetchOrders()"
      />
    </BaseCard>

    <template v-else>
      <!-- Prominent: this is the one thing the customer opened the app to see. -->
      <BaseCard
        v-if="activeOrder"
        as="section"
        class="border-primary/40 dark:border-blue-800"
        :title="`Delivery ${deliveryLabel(activeOrder.deliveryId)}`"
        subtitle="On the way to you"
      >
        <template #actions>
          <StatusBadge :meta="orderStatus(activeOrder.status)" size="md" />
        </template>

        <div class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <dl class="grid min-w-0 flex-1 grid-cols-2 gap-x-4 gap-y-3">
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Order</dt>
              <dd>
                <RouterLink
                  :to="{ name: 'customer-order-detail', params: { id: activeOrder.id } }"
                  class="font-mono text-body font-medium text-primary hover:underline dark:text-blue-300"
                >
                  {{ orderLabel(activeOrder.id) }}
                </RouterLink>
              </dd>
            </div>
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Total</dt>
              <dd class="text-body font-medium text-content dark:text-[#F8FAFC]">
                {{ formatMoney(activeOrder.totalAmount, activeOrder.currency) }}
              </dd>
            </div>
            <div class="col-span-2 min-w-0">
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Delivering to</dt>
              <dd class="truncate text-body text-content dark:text-[#F8FAFC]">
                {{ activeOrder.deliveryAddress }}, {{ activeOrder.city }}
              </dd>
            </div>
          </dl>

          <RouterLink
            :to="{ name: 'customer-tracking', params: { deliveryId: activeOrder.deliveryId } }"
            class="inline-flex h-11 w-full shrink-0 items-center justify-center gap-2 rounded-control bg-primary px-5 text-body font-medium text-white shadow-card transition-colors duration-150 hover:bg-primary-dark sm:w-auto"
          >
            Track delivery
            <svg
              class="h-4 w-4"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              aria-hidden="true"
            >
              <path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </RouterLink>
        </div>
      </BaseCard>

      <BaseCard as="section" :padded="false">
        <template #title>
          <h2 class="card-title">Recent orders</h2>
          <p v-if="orderStore.totalElements > 0" class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
            {{ pluralize(orderStore.totalElements, 'order') }} in total
          </p>
        </template>
        <template #actions>
          <RouterLink
            :to="{ name: 'customer-orders' }"
            class="text-small font-medium text-primary hover:underline dark:text-blue-300"
          >
            View all
          </RouterLink>
        </template>

        <EmptyState
          v-if="recentOrders.length === 0"
          title="Nothing here yet"
          description="No orders yet — your orders will appear here once you place your first order."
          actionLabel="Create an order"
          icon="orders"
          @action="router.push({ name: 'customer-checkout' })"
        />

        <ul v-else class="divide-y divide-edge dark:divide-[#1E293B]">
          <li v-for="order in recentOrders" :key="order.id">
            <RouterLink
              :to="{ name: 'customer-order-detail', params: { id: order.id } }"
              class="flex items-center gap-3 px-5 py-3.5 transition-colors duration-150 hover:bg-surface-muted dark:hover:bg-[#1A2436]"
            >
              <span class="min-w-0 flex-1">
                <span class="flex flex-wrap items-center gap-2">
                  <span class="font-mono text-body font-medium text-content dark:text-[#F8FAFC]">
                    {{ orderLabel(order.id) }}
                  </span>
                  <StatusBadge :meta="orderStatus(order.status)" />
                </span>
                <span class="mt-1 block text-small text-content-muted dark:text-[#94A3B8]">
                  {{ formatDate(order.createdAt) }} ·
                  {{ pluralize(order.itemCount, 'item') }} ·
                  {{ formatMoney(order.totalAmount, order.currency) }}
                </span>
              </span>

              <svg
                class="h-4 w-4 shrink-0 text-content-subtle dark:text-[#64748B]"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                aria-hidden="true"
              >
                <path d="m9 6 6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </RouterLink>
          </li>
        </ul>
      </BaseCard>
    </template>
  </div>
</template>
