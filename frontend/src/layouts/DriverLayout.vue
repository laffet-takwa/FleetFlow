<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/authStore'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { useNotificationStore } from '@/stores/notificationStore'

/**
 * Driver shell: four destinations, full width, thumb reachable.
 *
 * A driver uses this with one hand, in a van, in sunlight. Navigation is a bottom bar
 * only, there is no sidebar, and the active delivery stays reachable from every screen.
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const delivery = useDeliveryStore()
const notifications = useNotificationStore()

const activeDelivery = computed(
  () =>
    delivery.myDeliveries.find(
      (item) => item.status === 'ASSIGNED' || item.status === 'PICKED_UP' || item.status === 'IN_TRANSIT',
    ) ?? null,
)

const TABS = [
  { name: 'driver-dashboard', label: 'Home', icon: '<path d="M4 11 12 4l8 7v9H4z" stroke-linejoin="round"/>' },
  { name: 'driver-deliveries', label: 'Deliveries', icon: '<path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round"/><circle cx="7" cy="18" r="1.4"/><circle cx="17" cy="18" r="1.4"/>' },
  { name: 'driver-notifications', label: 'Alerts', icon: '<path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" stroke-linejoin="round"/><path d="M10.5 20a2 2 0 0 0 3 0" stroke-linecap="round"/>' },
  { name: 'driver-profile', label: 'Profile', icon: '<circle cx="12" cy="8" r="3.2"/><path d="M5.5 20a6.5 6.5 0 0 1 13 0" stroke-linecap="round"/>' },
] as const

function isActive(name: string): boolean {
  return route.name === name
}

async function signOut(): Promise<void> {
  auth.clear()
  notifications.reset()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="flex min-h-full flex-col bg-surface-muted pb-20 dark:bg-[#0F172A]">
    <header class="sticky top-0 z-30 border-b border-edge bg-surface/95 backdrop-blur dark:border-[#334155] dark:bg-[#0F172A]/95">
      <div class="mx-auto flex h-16 max-w-3xl items-center gap-3 px-4">
        <div class="min-w-0 flex-1">
          <p class="text-section-title font-semibold leading-tight text-content dark:text-[#F8FAFC]">
            FleetFlow Driver
          </p>
          <p class="truncate text-small text-content-muted dark:text-[#94A3B8]">
            {{ auth.user?.firstName }} {{ auth.user?.lastName }}
          </p>
        </div>

        <button
          v-if="activeDelivery"
          type="button"
          class="flex h-9 items-center gap-1.5 rounded-control bg-primary px-3 text-small font-medium text-white transition-colors hover:bg-primary-dark"
          @click="router.push({ name: 'driver-delivery-detail', params: { id: activeDelivery.id } })"
        >
          Active delivery
          <span aria-hidden="true">→</span>
        </button>

        <button
          type="button"
          class="flex h-9 w-9 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-surface-muted dark:hover:bg-[#1E293B]"
          :aria-label="`Sign out as ${auth.displayName}`"
          @click="signOut"
        >
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M15 17v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v2" stroke-linecap="round" />
            <path d="M19 12H9m10 0-3-3m3 3-3 3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </header>

    <main class="mx-auto w-full max-w-3xl flex-1 px-4 py-5">
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <nav
      class="fixed inset-x-0 bottom-0 z-30 border-t border-edge bg-surface/95 backdrop-blur dark:border-[#334155] dark:bg-[#0F172A]/95"
      aria-label="Main"
    >
      <ul class="mx-auto flex max-w-3xl items-stretch justify-around">
        <li v-for="tab in TABS" :key="tab.name" class="flex-1">
          <RouterLink
            :to="{ name: tab.name }"
            class="flex h-full flex-col items-center gap-1 py-3 text-small transition-colors duration-150"
            :class="isActive(tab.name) ? 'text-primary' : 'text-content-muted dark:text-[#94A3B8]'"
            :aria-current="isActive(tab.name) ? 'page' : undefined"
          >
            <span class="relative">
              <svg
                class="h-5 w-5"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                aria-hidden="true"
                v-html="tab.icon"
              />
              <span
                v-if="tab.name === 'driver-notifications' && notifications.unreadCount > 0"
                class="absolute -right-2 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold leading-none text-white"
              >
                {{ notifications.unreadCount > 9 ? '9+' : notifications.unreadCount }}
              </span>
            </span>
            {{ tab.label }}
          </RouterLink>
        </li>
      </ul>
    </nav>
  </div>
</template>