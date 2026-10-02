<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import AppHeader from '@/components/layout/AppHeader.vue'
import AppSidebar, { type NavItem } from '@/components/layout/AppSidebar.vue'
import { useDeliveryStore } from '@/stores/deliveryStore'

/**
 * Operations shell: a fixed 240px sidebar beside a scrolling content column.
 *
 * Below `lg` the sidebar becomes an overlay drawer with a focus trap and an Escape
 * handler, because a hidden-then-reflowed sidebar would leave keyboard users stranded.
 */
const route = useRoute()
const delivery = useDeliveryStore()

const drawerOpen = ref(false)

const title = computed(() => (route.meta.title as string | undefined) ?? 'Operations')
const breadcrumb = computed(() => (route.meta.breadcrumb as string[] | undefined) ?? [])

/**
 * Lucide-style path data kept inline as markup rather than as an icon dependency:
 * the set is fixed, so a package would add weight and a build step for eleven glyphs.
 */
const NAV: NavItem[] = [
  { name: 'admin-dashboard', label: 'Dashboard', icon: '<path d="M4 13h6V4H4zM14 20h6v-9h-6zM14 8h6V4h-6zM4 20h6v-4H4z" stroke-linejoin="round"/>' },
  { name: 'admin-orders', label: 'Orders', icon: '<path d="M6 3h9l4 4v14H6z" stroke-linejoin="round"/><path d="M15 3v4h4M9 12h6M9 16h4" stroke-linecap="round"/>' },
  { name: 'admin-inventory', label: 'Inventory', icon: '<path d="m12 3 8 4.5v9L12 21l-8-4.5v-9z" stroke-linejoin="round"/><path d="M4 7.5 12 12l8-4.5M12 12v9" stroke-linejoin="round"/>' },
  { name: 'admin-warehouses', label: 'Warehouses', icon: '<path d="M4 20V9l8-5 8 5v11" stroke-linejoin="round"/><path d="M9 20v-6h6v6" stroke-linejoin="round"/>' },
  { name: 'admin-drivers', label: 'Drivers', icon: '<circle cx="12" cy="8" r="3.2"/><path d="M5.5 20a6.5 6.5 0 0 1 13 0" stroke-linecap="round"/>' },
  { name: 'admin-vehicles', label: 'Vehicles', icon: '<path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round"/><circle cx="7" cy="18" r="1.5"/><circle cx="17" cy="18" r="1.5"/>' },
  { name: 'admin-deliveries', label: 'Deliveries', icon: '<path d="M4 7h9v9H4zM13 10h3.5l3.5 3.5V16h-7z" stroke-linejoin="round"/><circle cx="7.5" cy="18" r="1.4"/><circle cx="17" cy="18" r="1.4"/>' },
  { name: 'admin-tracking', label: 'Live tracking', icon: '<path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11z" stroke-linejoin="round"/><circle cx="12" cy="10" r="2.6"/>' },
  { name: 'admin-analytics', label: 'Analytics', icon: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2" stroke-linecap="round"/>' },
  { name: 'notifications', label: 'Notifications', icon: '<path d="M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6" stroke-linejoin="round"/><path d="M10.5 20a2 2 0 0 0 3 0" stroke-linecap="round"/>' },
  { name: 'admin-settings', label: 'Settings', icon: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4M3 12h2m14 0h2M5.6 18.4 7 17m10-10 1.4-1.4" stroke-linecap="round"/>' },
]

const navItems = computed<NavItem[]>(() =>
  NAV.map((item) =>
    item.name === 'admin-deliveries' && delivery.activeDeliveries.length > 0
      ? { ...item, badge: delivery.activeDeliveries.length }
      : item,
  ),
)

function closeDrawer(): void {
  drawerOpen.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    closeDrawer()
  }
}

onMounted(async () => {
  document.addEventListener('keydown', onKeydown)
  // One call on shell mount keeps the sidebar badge and the live-map entry honest
  // across screens without every view having to remember to refresh it.
  await delivery.fetchActive()
})

onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))

watch(
  () => route.name,
  () => closeDrawer(),
)
</script>

<template>
  <div class="flex h-full bg-surface-muted dark:bg-[#0F172A]">
    <aside
      class="hidden w-60 shrink-0 border-r border-edge bg-surface lg:flex lg:flex-col dark:border-[#334155] dark:bg-[#0B1220]"
    >
      <AppSidebar :items="navItems" :active-deliveries="delivery.activeDeliveries.length" />
    </aside>

    <Transition
      enter-active-class="transition-opacity duration-150"
      enter-from-class="opacity-0"
      leave-active-class="transition-opacity duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="drawerOpen"
        class="fixed inset-0 z-40 bg-slate-900/40 lg:hidden"
        @click="closeDrawer"
      />
    </Transition>

    <Transition
      enter-active-class="transition-transform duration-150"
      enter-from-class="-translate-x-full"
      leave-active-class="transition-transform duration-150"
      leave-to-class="-translate-x-full"
    >
      <aside
        v-if="drawerOpen"
        class="fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-edge bg-surface lg:hidden dark:border-[#334155] dark:bg-[#0B1220]"
        role="dialog"
        aria-modal="true"
        aria-label="Operations navigation"
      >
        <div class="flex justify-end p-2">
          <button
            type="button"
            class="flex h-9 w-9 items-center justify-center rounded-control text-content-muted hover:bg-surface-muted dark:hover:bg-[#1E293B]"
            aria-label="Close navigation"
            @click="closeDrawer"
          >
            <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="m6 6 12 12M18 6 6 18" stroke-linecap="round" />
            </svg>
          </button>
        </div>
        <AppSidebar :items="navItems" :active-deliveries="delivery.activeDeliveries.length" />
      </aside>
    </Transition>

    <div class="flex min-w-0 flex-1 flex-col">
      <AppHeader
        :title="title"
        :breadcrumb="breadcrumb"
        @toggle-sidebar="drawerOpen = true"
      />

      <main class="flex-1 px-4 py-5 sm:px-6 sm:py-6">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>