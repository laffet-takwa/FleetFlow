<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import { homeRouteFor } from '@/router'
import { useAuthStore } from '@/stores/authStore'
import { useToastStore } from '@/stores/toastStore'
import type { Role } from '@/types'

/**
 * 403 screen.
 *
 * The route guard sends a signed-in user here with the path they were refused, so the
 * required roles are resolved from the router rather than duplicated as copy: the page
 * states the real rule, not a generic apology, and blames neither the user nor the app.
 */
const auth = useAuthStore()
const toasts = useToastStore()
const route = useRoute()
const router = useRouter()

const ROLE_LABEL: Record<Role, string> = {
  ADMIN: 'Administrator',
  OPERATIONS: 'Operations',
  DRIVER: 'Driver',
  CUSTOMER: 'Customer',
}

const attemptedPath = computed(() =>
  typeof route.query.from === 'string' ? route.query.from : '',
)

const requiredRoles = computed<Role[]>(() => {
  if (!attemptedPath.value) {
    return []
  }
  const resolved = router.resolve(attemptedPath.value)
  return [...new Set(resolved.matched.flatMap((record) => record.meta.roles ?? []))]
})

const requirementText = computed(() => {
  const labels = requiredRoles.value.map((role) => ROLE_LABEL[role])
  if (labels.length === 0) {
    return 'This screen is reserved for a different kind of account.'
  }
  return `This screen is limited to ${labels.join(' and ')} accounts.`
})

const homePath = computed(() => homeRouteFor([auth.role ?? 'CUSTOMER']))

async function goHome(): Promise<void> {
  await router.push(homePath.value)
}

async function signOut(): Promise<void> {
  auth.clear()
  toasts.info('Signed out', 'Sign in with a different account to continue.')
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="flex min-h-full items-center justify-center bg-surface-muted px-4 py-10 dark:bg-[#0F172A] sm:px-6">
    <BaseCard class="w-full max-w-lg" as="section">
      <div class="flex flex-col items-center text-center">
        <span
          class="flex h-12 w-12 items-center justify-center rounded-full bg-amber-50 text-warning dark:bg-amber-950"
          aria-hidden="true"
        >
          <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <rect x="5" y="11" width="14" height="9" rx="2" stroke-linejoin="round" />
            <path d="M8.5 11V8a3.5 3.5 0 1 1 7 0v3" stroke-linecap="round" />
          </svg>
        </span>

        <p class="mt-4 text-small font-semibold uppercase tracking-[0.08em] text-content-subtle dark:text-[#64748B]">
          Error 403
        </p>
        <h1 class="mt-1 text-page-title font-semibold text-content dark:text-[#F8FAFC]">
          You do not have access to this page
        </h1>
        <p class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">{{ requirementText }}</p>

        <p
          v-if="attemptedPath"
          class="mt-3 max-w-full break-all rounded-control bg-surface-muted px-3 py-2 font-mono text-small text-content-muted dark:bg-[#1E293B] dark:text-[#94A3B8]"
        >
          {{ attemptedPath }}
        </p>

        <div class="mt-5 w-full rounded-control border border-edge px-4 py-3 text-left dark:border-[#334155]">
          <p class="text-small text-content-muted dark:text-[#94A3B8]">Signed in as</p>
          <p v-if="auth.user" class="mt-0.5 text-body font-medium text-content dark:text-[#F8FAFC]">
            {{ auth.displayName }}
            <span class="font-normal text-content-muted dark:text-[#94A3B8]">
              · {{ auth.role ? ROLE_LABEL[auth.role] : 'Unknown role' }}
            </span>
          </p>
          <p v-else class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
            Nobody — this browser has no active session.
          </p>
          <p v-if="auth.user?.email" class="mt-0.5 text-small text-content-subtle dark:text-[#64748B]">
            {{ auth.user.email }}
          </p>
        </div>

        <div class="mt-5 flex w-full flex-col gap-2 sm:flex-row sm:justify-center">
          <BaseButton v-if="auth.isAuthenticated" variant="primary" size="lg" @click="goHome">
            Go to my home
          </BaseButton>
          <RouterLink
            v-else
            to="/login"
            class="inline-flex h-11 items-center justify-center rounded-control bg-primary px-5 text-body font-medium text-white shadow-card transition-colors duration-150 hover:bg-primary-dark"
          >
            Sign in
          </RouterLink>

          <BaseButton v-if="auth.isAuthenticated" variant="secondary" size="lg" @click="signOut">
            Sign out
          </BaseButton>
        </div>

        <p class="mt-4 text-small text-content-muted dark:text-[#94A3B8]">
          Need access to this screen? Ask an administrator to change your role.
        </p>
      </div>
    </BaseCard>
  </div>
</template>