<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'

import ThemeToggle from '@/components/layout/ThemeToggle.vue'
import NotificationBell from '@/components/layout/NotificationBell.vue'
import { useAuthStore } from '@/stores/authStore'
import { useNotificationStore } from '@/stores/notificationStore'

/**
 * Customer shell: a slim top bar on desktop and a bottom tab bar on mobile.
 *
 * A delivery application is used one-handed on a phone, so the primary destinations sit
 * in thumb reach at the bottom rather than behind a hamburger.
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notifications = useNotificationStore()

const title = computed(() => (route.meta.title as string | undefined) ?? 'FleetFlow')

const TABS = [
  { name: 'customer-dashboard', label: 'Home', icon: '<path d="M4 11 12 4l8 7v9H4z" stroke-linejoin="round"/>' },
  { name: 'customer-orders', label: 'Orders', icon: '<path d="M6 3h9l4 4v14H6z" stroke-linejoin="round"/><path d="M15 3v4h4M9 12h6M9 16h4" stroke-linecap="round"/>' },
  { name: 'customer-checkout', label: 'Order', primary: true, icon: '<path d="M12 5v14M5 12h14" stroke-linecap="round"/>' },
  { name: 'notifications', label: 'Alerts', icon: '<path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" stroke-linejoin="round"/><path d="M10.5 20a2 2 0 0 0 3 0" stroke-linecap="round"/>' },
  { name: 'customer-profile', label: 'Profile', icon: '<circle cx="12" cy="8" r="3.2"/><path d="M5.5 20a6.5 6.5 0 0 1 13 0" stroke-linecap="round"/>' },
] as const

const badgeFor = computed(() => ({
  notifications: notifications.unreadCount,
}))

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
  <div class="flex min-h-full flex-col bg-surface-muted pb-16 lg:pb-0 dark:bg-[#0F172A]">
    <header class="sticky top-0 z-30 border-b border-edge bg-surface/95 backdrop-blur dark:border-[#334155] dark:bg-[#0F172A]/95">
      <div class="mx-auto flex h-16 max-w-5xl items-center gap-3 px-4 sm:px-6">
        <RouterLink :to="{ name: 'customer-dashboard' }" class="flex items-center gap-2.5">
          <span class="flex h-8 w-8 items-center justify-center rounded-control bg-primary text-white" aria-hidden="true">
            <svg class="h-4.5 w-4.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round" />
              <circle cx="7" cy="18" r="1.5" />
              <circle cx="17" cy="18" r="1.5" />
            </svg>
          </span>
          <span class="text-card-title font-semibold tracking-[-0.01em] text-content dark:text-[#F8FAFC]">
            FleetFlow
          </span>
        </RouterLink>

        <nav class="ml-6 hidden items-center gap-1 lg:flex" aria-label="Main">
          <RouterLink
            v-for="tab in TABS"
            :key="tab.name"
            :to="{ name: tab.name }"
            class="rounded-control px-3 py-1.5 text-body transition-colors duration-150"
            :class="
              isActive(tab.name)
                ? 'bg-blue-50 font-medium text-primary dark:bg-blue-950/60 dark:text-blue-300'
                : 'text-content-muted hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B]'
            "
          >
            {{ tab.label }}
          </RouterLink>
        </nav>

        <div class="ml-auto flex items-center gap-1.5">
          <p class="mr-2 hidden text-body text-content-muted sm:block dark:text-[#94A3B8]">
            Welcome back, {{ auth.user?.firstName }}
          </p>
          <ThemeToggle />
          <NotificationBell />
          <button
            type="button"
            class="flex h-9 items-center gap-2 rounded-control px-2 transition-colors hover:bg-surface-muted dark:hover:bg-[#1E293B]"
            :aria-label="`Sign out of FleetFlow as ${auth.displayName}`"
            @click="signOut"
          >
            <span
              class="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-small font-semibold text-white"
              aria-hidden="true"
            >
              {{ auth.initials }}
            </span>
          </button>
        </div>
      </div>
    </header>

    <main class="mx-auto w-full max-w-5xl flex-1 px-4 py-5 sm:px-6 sm:py-6">
      <h1 class="sr-only">{{ title }}</h1>
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <nav
      class="fixed inset-x-0 bottom-0 z-30 border-t border-edge bg-surface/95 backdrop-blur lg:hidden dark:border-[#334155] dark:bg-[#0F172A]/95"
      aria-label="Main"
    >
      <ul class="flex items-stretch justify-around">
        <li v-for="tab in TABS" :key="tab.name" class="flex-1">
          <RouterLink
            :to="{ name: tab.name }"
            class="flex h-full flex-col items-center gap-1 py-2.5 text-small transition-colors duration-150"
            :class="
              isActive(tab.name)
                ? 'text-primary'
                : 'text-content-muted dark:text-[#94A3B8]'
            "
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
                v-if="tab.name === 'notifications' && badgeFor.notifications > 0"
                class="absolute -right-2 -top-1 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold leading-none text-white"
              >
                {{ badgeFor.notifications > 9 ? '9+' : badgeFor.notifications }}
              </span>
            </span>
            {{ tab.label }}
          </RouterLink>
        </li>
      </ul>
    </nav>
  </div>
</template>