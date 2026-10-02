<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { useAuthStore } from '@/stores/authStore'
import { useNotificationStore } from '@/stores/notificationStore'
import { formatDateTime, formatRelative, orderLabel, pluralize } from '@/utils/format'
import { notificationLevel, toneDotClass } from '@/utils/status'
import type { NotificationResponse } from '@/types'

/**
 * Notification centre, reachable from every role.
 *
 * All state lives in `notificationStore` — including the filter and the page — so the
 * header badge, this list and the bell dropdown always agree. A notification is not a
 * columnar record, so this is a list of rows rather than a `DataTable`.
 */
const auth = useAuthStore()
const notifications = useNotificationStore()

/** Must match the page size the store requests; `TablePagination` needs it for "1–20 of". */
const PAGE_SIZE = 20

const pendingDelete = ref<number | null>(null)
const deleting = ref(false)

/** Newest first, even if the page arrives in another order after a live SSE insert. */
const items = computed(() =>
  [...notifications.items].sort(
    (left, right) => new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime(),
  ),
)

/** Customers and operations have an order screen; a driver has none, so no dead link. */
const canOpenOrders = computed(() => auth.isStaff || auth.isCustomer)

async function load(): Promise<void> {
  await notifications.fetchNotifications()
}

async function goToPage(page: number): Promise<void> {
  notifications.page = page
  await notifications.fetchNotifications()
}

async function toggleUnreadOnly(): Promise<void> {
  notifications.unreadOnly = !notifications.unreadOnly
  notifications.page = 0
  await notifications.fetchNotifications()
}

async function showAll(): Promise<void> {
  notifications.unreadOnly = false
  notifications.page = 0
  await notifications.fetchNotifications()
}

function orderLabelFor(item: NotificationResponse): string | null {
  return item.orderId === undefined ? null : `Order ${orderLabel(item.orderId)}`
}

function orderHref(item: NotificationResponse): string {
  return auth.isStaff ? `/admin/orders/${item.orderId}` : `/customer/orders/${item.orderId}`
}

function askDelete(id: number): void {
  pendingDelete.value = id
}

async function confirmDelete(): Promise<void> {
  const id = pendingDelete.value
  if (id === null) {
    return
  }
  deleting.value = true
  try {
    await notifications.remove(id)
    pendingDelete.value = null
    // Removing the last row of a later page would otherwise leave a blank page.
    if (notifications.items.length === 0 && notifications.page > 0) {
      notifications.page -= 1
      await notifications.fetchNotifications()
    }
  } finally {
    deleting.value = false
  }
}

onMounted(() => {
  void notifications.fetchNotifications()
})
</script>

<template>
  <div class="space-y-4">
    <header class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <div class="min-w-0">
        <h1 class="page-title">Notifications</h1>
        <p class="mt-1 text-small text-content-muted dark:text-[#94A3B8]">
          <template v-if="notifications.unreadCount > 0">
            {{ pluralize(notifications.unreadCount, 'unread notification') }} ·
          </template>
          {{ pluralize(notifications.totalElements, 'notification') }} in total
        </p>
      </div>

      <div class="flex flex-wrap items-center gap-2">
        <button
          type="button"
          class="inline-flex h-8 items-center gap-2 rounded-control px-3 text-small font-medium transition-colors duration-150"
          :class="
            notifications.unreadOnly
              ? 'bg-blue-50 text-primary ring-1 ring-inset ring-blue-200 dark:bg-blue-950 dark:text-blue-300 dark:ring-blue-800'
              : 'bg-surface text-content-muted ring-1 ring-inset ring-edge hover:bg-surface-muted hover:text-content dark:bg-[#111827] dark:text-[#94A3B8] dark:ring-[#334155] dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]'
          "
          :aria-pressed="notifications.unreadOnly"
          @click="toggleUnreadOnly"
        >
          <span
            class="h-2 w-2 rounded-full"
            :class="notifications.unreadOnly ? 'bg-primary dark:bg-blue-300' : 'bg-content-subtle dark:bg-[#64748B]'"
            aria-hidden="true"
          />
          Unread only
        </button>

        <BaseButton
          variant="secondary"
          size="sm"
          :disabled="!notifications.hasUnread"
          @click="notifications.markAllRead()"
        >
          Mark all as read
        </BaseButton>
      </div>
    </header>

    <BaseCard :padded="false">
      <ErrorState
        v-if="notifications.error"
        :message="notifications.error"
        retryLabel="Try again"
        @retry="load"
      />

      <BaseSkeleton v-else-if="notifications.loading" class="p-5" variant="cards" :rows="4" />

      <EmptyState
        v-else-if="items.length === 0"
        :title="notifications.unreadOnly ? 'Nothing unread' : 'No notifications yet'"
        :description="
          notifications.unreadOnly
            ? 'You have read everything. Turn the filter off to see the full history.'
            : 'Updates about your orders and deliveries appear here as soon as something changes. You can keep using the rest of the app in the meantime.'
        "
        :actionLabel="notifications.unreadOnly ? 'Show all notifications' : ''"
        icon="notifications"
        @action="showAll"
      />

      <template v-else>
        <ul class="divide-y divide-edge dark:divide-[#1E293B]">
          <li
            v-for="item in items"
            :key="item.id"
            class="flex items-start gap-3 px-4 py-4 sm:px-5"
            :class="item.read ? '' : 'bg-blue-50/40 dark:bg-blue-950/20'"
          >
            <span class="mt-1.5 flex h-2.5 w-2.5 shrink-0 items-center justify-center" aria-hidden="true">
              <span
                v-if="!item.read"
                class="h-2.5 w-2.5 rounded-full"
                :class="toneDotClass(notificationLevel(item.level).tone)"
              />
              <span v-else class="h-2.5 w-2.5 rounded-full ring-1 ring-inset ring-edge-strong dark:ring-[#334155]" />
            </span>

            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-x-2 gap-y-1.5">
                <p
                  class="min-w-0 text-body font-semibold"
                  :class="
                    item.read
                      ? 'text-content-muted dark:text-[#94A3B8]'
                      : 'text-content dark:text-[#F8FAFC]'
                  "
                >
                  {{ item.title }}
                </p>
                <StatusBadge :meta="notificationLevel(item.level)" />
                <span v-if="!item.read" class="text-small font-medium text-primary dark:text-blue-300">
                  Unread
                </span>
              </div>

              <p class="mt-1 text-body text-content-muted dark:text-[#94A3B8]">{{ item.message }}</p>

              <div class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5">
                <time
                  :datetime="item.createdAt"
                  :title="formatDateTime(item.createdAt)"
                  class="text-small text-content-subtle dark:text-[#64748B]"
                >
                  {{ formatRelative(item.createdAt) }}
                </time>

                <RouterLink
                  v-if="orderLabelFor(item) && canOpenOrders"
                  :to="orderHref(item)"
                  class="inline-flex items-center gap-1 text-small font-medium text-primary hover:underline dark:text-blue-300"
                >
                  {{ orderLabelFor(item) }}
                  <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                    <path d="M7 17 17 7M9 7h8v8" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </RouterLink>
                <span
                  v-else-if="orderLabelFor(item)"
                  class="text-small text-content-subtle dark:text-[#64748B]"
                >
                  {{ orderLabelFor(item) }}
                </span>
              </div>
            </div>

            <div class="flex shrink-0 items-center gap-1">
              <button
                v-if="!item.read"
                type="button"
                class="flex h-8 w-8 items-center justify-center rounded-control text-content-muted transition-colors duration-150 hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]"
                :aria-label="`Mark “${item.title}” as read`"
                @click="notifications.markRead(item.id)"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="m5 12.5 4.5 4.5L19 7.5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>

              <button
                type="button"
                class="flex h-8 w-8 items-center justify-center rounded-control text-content-muted transition-colors duration-150 hover:bg-red-50 hover:text-danger dark:hover:bg-red-950"
                :aria-label="`Delete notification “${item.title}”`"
                @click="askDelete(item.id)"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M4 7h16M10 4h4M9 7v11m6-11v11" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
            </div>
          </li>
        </ul>

        <TablePagination
          :page="notifications.page"
          :totalPages="notifications.totalPages"
          :totalElements="notifications.totalElements"
          :size="PAGE_SIZE"
          label="notifications"
          @change="goToPage"
        />
      </template>
    </BaseCard>

    <ConfirmModal
      :open="pendingDelete !== null"
      title="Delete this notification?"
      description="It disappears from your list for good. This cannot be undone."
      confirmLabel="Delete"
      cancelLabel="Keep it"
      tone="danger"
      :busy="deleting"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>