<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'

import { useNotificationStore } from '@/stores/notificationStore'
import { formatRelative } from '@/utils/format'
import { notificationLevel } from '@/utils/status'

import type { NotificationResponse } from '@/types'

const PAGE_SIZE = 20

const router = useRouter()
const notifications = useNotificationStore()

const pendingRemoval = ref<NotificationResponse | null>(null)
const removing = ref(false)

async function load(): Promise<void> {
  await notifications.fetchNotifications()
}

async function toggleUnreadOnly(): Promise<void> {
  notifications.unreadOnly = !notifications.unreadOnly
  notifications.page = 0
  await load()
}

async function goToPage(page: number): Promise<void> {
  notifications.page = page
  await load()
}

function requestRemoval(item: NotificationResponse): void {
  pendingRemoval.value = item
}

function cancelRemoval(): void {
  pendingRemoval.value = null
}

async function confirmRemoval(): Promise<void> {
  const target = pendingRemoval.value
  if (!target) {
    return
  }
  removing.value = true
  await notifications.remove(target.id)
  removing.value = false
  pendingRemoval.value = null
  // The page may now be short; step back rather than showing an empty list.
  if (notifications.items.length === 0 && notifications.page > 0) {
    notifications.page -= 1
  }
  await load()
}

/** A notification may carry the delivery it is about, which the driver can open. */
function openTarget(item: NotificationResponse): void {
  if (item.deliveryId) {
    void router.push({ name: 'driver-delivery-detail', params: { id: item.deliveryId } })
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="space-y-4">
    <header>
      <h1 class="page-title">Alerts</h1>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        Updates about your assignments, in the order they arrive.
      </p>
    </header>

    <div class="flex flex-wrap items-center justify-between gap-2">
      <button
        type="button"
        role="switch"
        :aria-checked="notifications.unreadOnly"
        class="inline-flex min-h-11 items-center gap-2.5 rounded-control bg-surface px-3 text-body font-medium text-content ring-1 ring-inset ring-edge transition-colors duration-150 active:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:active:bg-[#1E293B]"
        @click="toggleUnreadOnly"
      >
        <span
          class="relative h-5 w-9 shrink-0 rounded-full transition-colors duration-150"
          :class="notifications.unreadOnly ? 'bg-primary' : 'bg-edge-strong dark:bg-[#334155]'"
          aria-hidden="true"
        >
          <span
            class="absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all duration-150"
            :class="notifications.unreadOnly ? 'left-[1.125rem]' : 'left-0.5'"
          />
        </span>
        Unread only
      </button>

      <BaseButton
        :disabled="notifications.unreadCount === 0"
        @click="notifications.markAllRead()"
      >
        Mark all as read
      </BaseButton>
    </div>

    <BaseSkeleton v-if="notifications.loading" variant="cards" :rows="3" />

    <ErrorState
      v-else-if="notifications.error"
      :message="notifications.error"
      retryLabel="Try again"
      @retry="load"
    />

    <template v-else>
      <BaseCard v-if="notifications.items.length" :padded="false">
        <ul class="divide-y divide-edge dark:divide-[#1E293B]">
          <li
            v-for="item in notifications.items"
            :key="item.id"
            class="flex items-start gap-3 px-5 py-4"
          >
            <span
              class="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full"
              :class="item.read ? 'bg-transparent ring-1 ring-inset ring-edge-strong dark:ring-[#334155]' : 'bg-primary'"
              aria-hidden="true"
            />

            <div class="min-w-0 flex-1">
              <p
                class="break-words text-body"
                :class="item.read ? 'text-content-muted dark:text-[#94A3B8]' : 'font-bold text-content dark:text-[#F8FAFC]'"
              >
                <span v-if="!item.read" class="sr-only">Unread: </span>{{ item.title }}
              </p>
              <p class="mt-0.5 break-words text-small text-content-muted dark:text-[#94A3B8]">
                {{ item.message }}
              </p>
              <div class="mt-2 flex flex-wrap items-center gap-2">
                <StatusBadge :meta="notificationLevel(item.level)" />
                <span class="text-small text-content-subtle dark:text-[#64748B]">
                  {{ formatRelative(item.createdAt) }}
                </span>
              </div>
            </div>

            <div class="flex shrink-0 flex-col items-end gap-1.5">
              <button
                v-if="!item.read"
                type="button"
                class="rounded-control px-2 py-1.5 text-small font-medium text-primary"
                :aria-label="`Mark “${item.title}” as read`"
                @click="notifications.markRead(item.id)"
              >
                Mark read
              </button>
              <button
                v-if="item.deliveryId"
                type="button"
                class="rounded-control px-2 py-1.5 text-small font-medium text-primary"
                :aria-label="`Open the delivery for “${item.title}”`"
                @click="openTarget(item)"
              >
                Open
              </button>
              <button
                type="button"
                class="flex h-11 w-11 items-center justify-center rounded-control text-content-muted transition-colors duration-150 active:bg-surface-muted dark:text-[#94A3B8] dark:active:bg-[#1E293B]"
                :aria-label="`Delete the notification “${item.title}”`"
                @click="requestRemoval(item)"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                  <path d="M4 7h16M9 7V5h6v2M6 7l1 13h10l1-13" stroke-linecap="round" stroke-linejoin="round" />
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
          label="alerts"
          @change="goToPage"
        />
      </BaseCard>

      <BaseCard v-else :padded="false">
        <EmptyState
          icon="notifications"
          :title="notifications.unreadOnly ? 'No unread alerts' : 'No alerts yet'"
          :description="
            notifications.unreadOnly
              ? 'You have read everything. Turn the filter off to see your full history.'
              : 'Assignments and status changes for your deliveries will appear here.'
          "
        />
      </BaseCard>
    </template>

    <ConfirmModal
      :open="pendingRemoval !== null"
      title="Delete this alert?"
      :description="pendingRemoval?.title ?? ''"
      confirmLabel="Delete"
      cancelLabel="Keep it"
      :busy="removing"
      @confirm="confirmRemoval"
      @cancel="cancelRemoval"
    />
  </div>
</template>