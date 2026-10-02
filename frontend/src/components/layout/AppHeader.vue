<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/authStore'
import { useToastStore } from '@/stores/toastStore'
import NotificationBell from './NotificationBell.vue'

/**
 * Operations header: page title, breadcrumb and the account controls.
 *
 * The breadcrumb is rendered from the route meta rather than from a per-screen store,
 * so a deep link and a click through the sidebar always show the same trail.
 */
defineProps<{
  title: string
  breadcrumb?: string[]
}>()

defineEmits<{ toggleSidebar: [] }>()

const auth = useAuthStore()
const toasts = useToastStore()
const router = useRouter()

const menuOpen = ref(false)
const searchOpen = ref(false)
const search = ref('')

const roleLabel = computed(() => auth.role ?? '')

function onSearch(): void {
  const term = search.value.trim()
  if (!term) {
    return
  }
  // Contextual search only: the orders screen already knows how to filter by id or name.
  void router.push({ name: 'admin-orders', query: { search: term } })
  searchOpen.value = false
  search.value = ''
}

async function signOut(): Promise<void> {
  menuOpen.value = false
  auth.clear()
  toasts.info('Signed out', 'See you next time.')
  await router.push({ name: 'login' })
}
</script>

<template>
  <header class="sticky top-0 z-30 border-b border-edge bg-surface/95 backdrop-blur dark:border-[#334155] dark:bg-[#0F172A]/95">
    <div class="flex h-16 items-center gap-3 px-4 sm:px-6">
      <button
        type="button"
        class="-ml-2 flex h-9 w-9 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-surface-muted lg:hidden dark:hover:bg-[#1E293B]"
        aria-label="Open navigation"
        @click="$emit('toggleSidebar')"
      >
        <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path d="M4 7h16M4 12h16M4 17h16" stroke-linecap="round" />
        </svg>
      </button>

      <div class="min-w-0 flex-1">
        <h1 class="truncate text-section-title font-semibold text-content dark:text-[#F8FAFC]">
          {{ title }}
        </h1>
        <nav v-if="breadcrumb && breadcrumb.length" aria-label="Breadcrumb">
          <ol class="flex items-center gap-1.5 text-small text-content-muted dark:text-[#94A3B8]">
            <li v-for="(crumb, index) in breadcrumb" :key="crumb" class="flex items-center gap-1.5">
              <span :aria-current="index === breadcrumb.length - 1 ? 'page' : undefined">{{ crumb }}</span>
              <svg
                v-if="index < breadcrumb.length - 1"
                class="h-3 w-3 text-content-subtle"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.5"
                aria-hidden="true"
              >
                <path d="m9 6 6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </li>
          </ol>
        </nav>
      </div>

      <div class="flex items-center gap-1.5">
        <form
          v-if="searchOpen"
          class="hidden sm:block"
          role="search"
          @submit.prevent="onSearch"
        >
          <input
            v-model="search"
            type="search"
            placeholder="Search orders…"
            aria-label="Search orders by id or customer"
            class="h-9 w-52 rounded-control border border-edge bg-surface px-3 text-body text-content placeholder:text-content-subtle focus:border-primary focus:outline-none focus:ring-2 focus:ring-primary/30 dark:bg-[#0F172A] dark:text-[#F8FAFC] dark:border-[#334155]"
            @keydown.esc="searchOpen = false"
          />
        </form>

        <button
          type="button"
          class="hidden h-9 w-9 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-surface-muted hover:text-content sm:flex dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]"
          :aria-label="searchOpen ? 'Close search' : 'Search orders'"
          :aria-pressed="searchOpen"
          @click="searchOpen = !searchOpen"
        >
          <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" stroke-linecap="round" />
          </svg>
        </button>

        <ThemeToggle />
        <NotificationBell />

        <div class="relative">
          <button
            type="button"
            class="ml-1 flex items-center gap-2 rounded-control py-1 pl-1 pr-2 transition-colors hover:bg-surface-muted dark:hover:bg-[#1E293B]"
            :aria-expanded="menuOpen"
            aria-haspopup="true"
            @click="menuOpen = !menuOpen"
          >
            <span
              class="flex h-8 w-8 items-center justify-center rounded-full bg-primary text-small font-semibold text-white"
              aria-hidden="true"
            >
              {{ auth.initials }}
            </span>
            <span class="hidden text-left lg:block">
              <span class="block text-small font-medium leading-tight text-content dark:text-[#F8FAFC]">
                {{ auth.displayName }}
              </span>
              <span class="block text-small leading-tight text-content-subtle dark:text-[#64748B]">
                {{ roleLabel }}
              </span>
            </span>
            <svg class="h-4 w-4 text-content-subtle" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <path d="m6 9 6 6 6-6" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>

          <div
            v-if="menuOpen"
            class="absolute right-0 top-12 z-40 w-56 overflow-hidden rounded-card border border-edge bg-surface shadow-overlay dark:border-[#334155] dark:bg-[#111827]"
          >
            <div class="border-b border-edge px-4 py-3 dark:border-[#334155]">
              <p class="text-small font-semibold text-content dark:text-[#F8FAFC]">
                {{ auth.displayName }}
              </p>
              <p class="truncate text-small text-content-muted dark:text-[#94A3B8]">
                {{ auth.user?.email }}
              </p>
            </div>
            <div class="p-1.5">
              <button
                type="button"
                class="flex w-full items-center gap-2.5 rounded-control px-3 py-2 text-left text-body text-content-muted transition-colors hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]"
                @click="signOut"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                  <path d="M15 17v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v2" stroke-linecap="round" />
                  <path d="M19 12H9m10 0-3-3m3 3-3 3" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                Sign out
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </header>
</template>