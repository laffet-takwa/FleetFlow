<script setup lang="ts">
import { RouterLink, useRoute } from 'vue-router'

/**
 * Operations sidebar.
 *
 * One flat list of destinations at a single weight, because this is a working tool:
 * a driver or an operator scanning for the next screen should not have to read the
 * navigation. Icons are 18px Lucide-style strokes, never oversized.
 */
export interface NavItem {
  name: string
  label: string
  icon: string
  badge?: number
}

defineProps<{
  items: NavItem[]
  /** Live delivery count shown next to the map entry. */
  activeDeliveries?: number
}>()

const route = useRoute()

function isActive(name: string): boolean {
  return route.name === name
}
</script>

<template>
  <nav class="flex h-full flex-col" aria-label="Operations">
    <div class="flex items-center gap-2.5 px-5 py-4">
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
    </div>

    <ul class="flex-1 space-y-0.5 overflow-y-auto px-3 pb-4">
      <li v-for="item in items" :key="item.name">
        <RouterLink
          :to="{ name: item.name }"
          class="group flex items-center gap-3 rounded-control px-3 py-2 text-body transition-colors duration-150"
          :class="
            isActive(item.name)
              ? 'bg-blue-50 font-medium text-primary dark:bg-blue-950/60 dark:text-blue-300'
              : 'text-content-muted hover:bg-surface-muted hover:text-content dark:text-[#94A3B8] dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]'
          "
          :aria-current="isActive(item.name) ? 'page' : undefined"
        >
          <svg
            class="h-[18px] w-[18px] shrink-0"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            aria-hidden="true"
            v-html="item.icon"
          />
          <span class="flex-1 truncate">{{ item.label }}</span>
          <span
            v-if="item.badge"
            class="rounded-full bg-primary px-1.5 py-0.5 text-[11px] font-semibold leading-none text-white"
          >
            {{ item.badge > 99 ? '99+' : item.badge }}
          </span>
        </RouterLink>
      </li>
    </ul>

    <p
      v-if="activeDeliveries"
      class="border-t border-edge px-5 py-3 text-small text-content-subtle dark:border-[#334155] dark:text-[#64748B]"
    >
      <span class="font-medium text-content-muted dark:text-[#94A3B8]">
        {{ activeDeliveries }} active {{ activeDeliveries === 1 ? 'delivery' : 'deliveries' }}
      </span>
      right now
    </p>
  </nav>
</template>