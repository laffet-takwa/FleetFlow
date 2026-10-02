<script setup lang="ts">
import { onMounted, ref } from 'vue'

/**
 * Dark mode toggle.
 *
 * The `dark` class is applied to `<html>` and the choice is persisted in localStorage.
 * `index.html` reads the same key before first paint, so the toggle never causes a flash
 * of the wrong theme on reload.
 */
const isDark = ref(false)
const STORAGE_KEY = 'fleetflow.theme'

function apply(dark: boolean): void {
  isDark.value = dark
  document.documentElement.classList.toggle('dark', dark)
  try {
    localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light')
  } catch {
    // Storage denied: the theme still applies for this session, it just will not persist.
  }
}

function toggle(): void {
  apply(!isDark.value)
}

onMounted(() => {
  apply(document.documentElement.classList.contains('dark'))
})
</script>

<template>
  <button
    type="button"
    class="flex h-9 w-9 items-center justify-center rounded-control text-content-muted transition-colors duration-150 hover:bg-surface-muted hover:text-content dark:hover:bg-[#1E293B] dark:hover:text-[#F8FAFC]"
    :aria-label="isDark ? 'Switch to light theme' : 'Switch to dark theme'"
    :aria-pressed="isDark"
    @click="toggle"
  >
    <svg v-if="isDark" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 3v2m0 14v2M5.6 5.6l1.4 1.4m10 10 1.4 1.4M3 12h2m14 0h2M5.6 18.4 7 17m10-10 1.4-1.4" stroke-linecap="round" />
    </svg>
    <svg v-else class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
      <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z" stroke-linejoin="round" />
    </svg>
  </button>
</template>