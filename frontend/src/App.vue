<script setup lang="ts">
import { useAuthStore } from '@/stores/authStore'
import ToastHost from '@/components/ui/ToastHost.vue'

const auth = useAuthStore()
</script>

<template>
  <!--
    A full-page skeleton while the session is being restored. Rendering the app before
    the session is known would flash the login screen at a signed-in user on every reload.
  -->
  <div
    v-if="auth.initialising && !auth.sessionChecked"
    class="flex h-full items-center justify-center bg-surface-muted dark:bg-[#0F172A]"
  >
    <div class="flex flex-col items-center gap-3" role="status" aria-label="Starting FleetFlow">
      <svg class="h-7 w-7 animate-spin text-primary" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" opacity="0.25" />
        <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
      </svg>
      <p class="text-body text-content-muted dark:text-[#94A3B8]">Starting FleetFlow…</p>
    </div>
  </div>

  <RouterView v-else />

  <ToastHost />
</template>