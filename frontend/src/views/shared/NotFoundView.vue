<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import { homeRouteFor } from '@/router'
import { useAuthStore } from '@/stores/authStore'

/**
 * 404 screen, also the catch-all route.
 *
 * The attempted path is echoed verbatim so a mistyped or stale link is obvious at a glance
 * rather than silently swallowed by the router.
 */
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const attemptedPath = computed(() => route.fullPath)
const homePath = computed(() => homeRouteFor([auth.role ?? 'CUSTOMER']))

function goBack(): void {
  // A direct link has no history to return to, in which case the home screen is the
  // sensible fallback rather than a dead button.
  if (window.history.length > 1) {
    router.back()
    return
  }
  void router.push(auth.isAuthenticated ? homePath.value : '/login')
}
</script>

<template>
  <div class="flex min-h-full items-center justify-center bg-surface-muted px-4 py-10 dark:bg-[#0F172A] sm:px-6">
    <BaseCard class="w-full max-w-lg" as="section">
      <div class="flex flex-col items-center text-center">
        <span
          class="flex h-12 w-12 items-center justify-center rounded-full bg-blue-50 text-primary dark:bg-blue-950"
          aria-hidden="true"
        >
          <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" stroke-linecap="round" />
          </svg>
        </span>

        <p class="mt-4 text-small font-semibold uppercase tracking-[0.08em] text-content-subtle dark:text-[#64748B]">
          Error 404
        </p>
        <h1 class="mt-1 text-page-title font-semibold text-content dark:text-[#F8FAFC]">
          This page does not exist
        </h1>
        <p class="mt-2 text-body text-content-muted dark:text-[#94A3B8]">
          The address may be mistyped, or the screen may have been renamed.
        </p>

        <p class="mt-4 w-full break-all rounded-control bg-surface-muted px-3 py-2 text-left font-mono text-small text-content-muted dark:bg-[#1E293B] dark:text-[#94A3B8]">
          {{ attemptedPath }}
        </p>

        <div class="mt-5 flex w-full flex-col gap-2 sm:flex-row sm:justify-center">
          <BaseButton v-if="auth.isAuthenticated" variant="primary" size="lg" @click="router.push(homePath)">
            Go to my home
          </BaseButton>
          <RouterLink
            v-else
            to="/login"
            class="inline-flex h-11 items-center justify-center rounded-control bg-primary px-5 text-body font-medium text-white shadow-card transition-colors duration-150 hover:bg-primary-dark"
          >
            Sign in
          </RouterLink>

          <BaseButton variant="secondary" size="lg" @click="goBack">Go back</BaseButton>
        </div>
      </div>
    </BaseCard>
  </div>
</template>