<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import ThemeToggle from '@/components/layout/ThemeToggle.vue'
import { API_BASE_URL } from '@/services/api'
import { useAuthStore } from '@/stores/authStore'
import { useToastStore } from '@/stores/toastStore'
import type { Role } from '@/types'
import type { StatusMeta } from '@/utils/status'

/**
 * Session and environment settings.
 *
 * There is nothing here an operator can change beyond the theme and signing out: driver
 * records, stock levels and warehouse data are all owned by services, so the screen says
 * so rather than offering controls that would silently do nothing.
 */

const ROLE_META: Record<Role, StatusMeta> = {
  ADMIN: { label: 'Administrator', tone: 'danger', glyph: '★' },
  OPERATIONS: { label: 'Operations', tone: 'info', glyph: '●' },
  DRIVER: { label: 'Driver', tone: 'progress', glyph: '→' },
  CUSTOMER: { label: 'Customer', tone: 'neutral', glyph: '○' },
}

const auth = useAuthStore()
const toasts = useToastStore()
const router = useRouter()

const roleMeta = computed<StatusMeta | null>(() => {
  const role = auth.role
  return role ? (ROLE_META[role] ?? null) : null
})

const apiUrl = computed(() => API_BASE_URL)

async function signOut(): Promise<void> {
  auth.clear()
  toasts.info('Signed out', 'See you next time.')
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="mx-auto max-w-3xl space-y-4">
    <BaseCard title="Your account" subtitle="The identity this console is acting as">
      <div class="flex items-start gap-4">
        <span
          class="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-primary text-body font-semibold text-white"
          aria-hidden="true"
        >
          {{ auth.initials }}
        </span>
        <div class="min-w-0 flex-1">
          <p class="text-section-title font-semibold text-content dark:text-[#F8FAFC]">
            {{ auth.displayName || 'Not signed in' }}
          </p>
          <dl class="mt-2 grid grid-cols-1 gap-x-6 gap-y-2 sm:grid-cols-2">
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Email</dt>
              <dd class="truncate text-body text-content dark:text-[#F8FAFC]">
                {{ auth.user?.email ?? '—' }}
              </dd>
            </div>
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Phone</dt>
              <dd class="text-body text-content dark:text-[#F8FAFC]">{{ auth.user?.phone ?? '—' }}</dd>
            </div>
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Role</dt>
              <dd>
                <StatusBadge v-if="roleMeta" :meta="roleMeta" />
                <span v-else class="text-body text-content-muted">Unknown</span>
              </dd>
            </div>
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Session</dt>
              <dd class="text-body text-content dark:text-[#F8FAFC]">
                Token held for this browser
              </dd>
            </div>
          </dl>
        </div>
      </div>
    </BaseCard>

    <BaseCard title="Appearance" subtitle="Applies to this browser and is remembered on reload">
      <div class="flex items-center justify-between gap-4">
        <div>
          <p class="text-body font-medium text-content dark:text-[#F8FAFC]">Theme</p>
          <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
            Light or dark. Charts and status colours have a dark counterpart, so nothing turns
            into a white slab in dark mode.
          </p>
        </div>
        <ThemeToggle />
      </div>
    </BaseCard>

    <BaseCard title="Environment" subtitle="What this console is talking to">
      <dl class="space-y-3">
        <div>
          <dt class="text-small text-content-muted dark:text-[#94A3B8]">API base URL</dt>
          <dd>
            <code
              class="mt-1 block select-all rounded-control border border-edge bg-surface-muted px-3 py-2 font-mono text-body text-content dark:border-[#334155] dark:bg-[#0F172A] dark:text-[#F8FAFC]"
            >
              {{ apiUrl }}
            </code>
          </dd>
          <p class="mt-1.5 text-small text-content-subtle dark:text-[#64748B]">
            Read only. Every request goes through the gateway at this address; no service is
            called directly from the browser.
          </p>
        </div>
      </dl>
    </BaseCard>

    <BaseCard title="Demo data" subtitle="This deployment runs on seeded data">
      <p class="text-body text-content-muted dark:text-[#94A3B8]">
        Orders, drivers, vehicles, warehouses and stock levels are seeded by the platform on first
        boot, and the live map has no real GPS behind it — positions come from the driver's on-screen
        simulation. Nothing here reflects a real fleet, and any change you make only lives in this
        environment.
      </p>
      <p class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
        The four seeded accounts and their passwords are listed in the project README; use the
        operations account to see everything on these screens.
      </p>
    </BaseCard>

    <BaseCard title="Session" subtitle="Sign out of this console">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <p class="text-small text-content-muted dark:text-[#94A3B8]">
          Signing out clears the stored access token on this device only.
        </p>
        <BaseButton variant="danger" @click="signOut">Sign out</BaseButton>
      </div>
    </BaseCard>
  </div>
</template>