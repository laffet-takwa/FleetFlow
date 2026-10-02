<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useNotificationStore } from '@/stores/notificationStore'
import { formatRelative } from '@/utils/format'
import { notificationLevel, toneDotClass } from '@/utils/status'

/**
 * Header notification bell and its dropdown.
 *
 * The count arrives over SSE, so the badge updates while the user is on another screen.
 * The trigger is a real button with `aria-expanded`, and the panel closes on Escape or
 * on an outside click.
 */
const notifications = useNotificationStore()
const router = useRouter()

const open = ref(false)
const panel = ref<HTMLElement | null>(null)

const preview = computed(() => notifications.items.slice(0, 5))

function toggle(): void {
  open.value = !open.value
}

async function onSelect(id: number): Promise<void> {
  await notifications.markRead(id)
  open.value = false
  await router.push('/notifications')
}

function viewAll(): void {
  open.value = false
  void router.push('/notifications')
}

function onWindowClick(event: MouseEvent): void {
  const target = event.target as Node
  if (open.value && panel.value && !panel.value.contains(target)) {
    open.value = false
  }
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && open.value) {
    open.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onWindowClick, true)
  document.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onWindowClick, true)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div ref="panel" class="relative">
    <button
      type="button"
      class="relative flex h-9 w-9 items-center justify-center rounded-control text-content-muted transition-colors duration-150 hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]"
      :aria-expanded="open"
      aria-haspopup="true"
      :aria-label="
        notifications.hasUnread
          ? `Notifications, ${notifications.unreadCount} unread`
          : 'Notifications'
      "
      @click="toggle"
    >
      <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" stroke-linejoin="round" />
        <path d="M10.5 20a2 2 0 0 0 3 0" stroke-linecap="round" />
      </svg>

      <span
        v-if="notifications.hasUnread"
        class="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold leading-none text-white"
      >
        {{ notifications.unreadCount > 99 ? '99+' : notifications.unreadCount }}
      </span>
    </button>

    <div
      v-if="open"
      class="absolute right-0 top-11 z-40 w-80 overflow-hidden rounded-card border border-edge bg-surface shadow-overlay dark:border-[#334155] dark:bg-[#111827]"
    >
      <div class="flex items-center justify-between border-b border-edge px-4 py-2.5 dark:border-[#334155]">
        <p class="card-title">Notifications</p>
        <button
          v-if="notifications.hasUnread"
          type="button"
          class="text-small font-medium text-primary hover:underline"
          @click="notifications.markAllRead()"
        >
          Mark all as read
        </button>
      </div>

      <div
        v-if="preview.length === 0"
        class="px-4 py-8 text-center text-small text-content-muted dark:text-[#94A3B8]"
      >
        You have no notifications yet.
      </div>

      <ul v-else class="max-h-80 divide-y divide-edge overflow-y-auto dark:divide-[#1E293B]">
        <li v-for="item in preview" :key="item.id">
          <button
            type="button"
            class="flex w-full gap-2.5 px-4 py-3 text-left transition-colors hover:bg-surface-muted dark:hover:bg-[#1A2436]"
            @click="onSelect(item.id)"
          >
            <span
              class="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full"
              :class="item.read ? 'bg-transparent' : toneDotClass(notificationLevel(item.level).tone)"
              aria-hidden="true"
            />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-small font-medium text-content dark:text-[#F8FAFC]">
                {{ item.title }}
              </span>
              <span class="mt-0.5 block text-small text-content-muted dark:text-[#94A3B8]">
                {{ item.message }}
              </span>
              <span class="mt-1 block text-small text-content-subtle dark:text-[#64748B]">
                {{ formatRelative(item.createdAt) }}
              </span>
            </span>
          </button>
        </li>
      </ul>

      <div class="border-t border-edge px-4 py-2.5 text-center dark:border-[#334155]">
        <button
          type="button"
          class="text-small font-medium text-primary hover:underline"
          @click="viewAll"
        >
          View all notifications
        </button>
      </div>
    </div>
  </div>
</template>