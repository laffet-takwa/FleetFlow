<script setup lang="ts">
import { computed } from 'vue'

import AdminLayout from '@/layouts/AdminLayout.vue'
import CustomerLayout from '@/layouts/CustomerLayout.vue'
import DriverLayout from '@/layouts/DriverLayout.vue'
import NotificationsView from '@/views/shared/NotificationsView.vue'
import { useAuthStore } from '@/stores/authStore'

/**
 * Renders the notification centre inside the caller's own shell.
 *
 * The router resolves layouts by nesting, so a top-level route has no shell of its own.
 * Rather than duplicating `/notifications` under three parents, this picks the shell
 * from the caller's role — the notification centre belongs to all three roles, and the
 * navigation around it must match whichever interface the user came from.
 */
const auth = useAuthStore()

const shell = computed(() => {
  if (auth.isStaff) {
    return AdminLayout
  }
  return auth.isDriver ? DriverLayout : CustomerLayout
})
</script>

<template>
  <component :is="shell">
    <NotificationsView />
  </component>
</template>