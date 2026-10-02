<script setup lang="ts">
/**
 * Standard card. Every panel in the application is one of these, which is what keeps
 * padding, border radius and shadow identical across four different screens.
 */
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    padded?: boolean
    as?: 'section' | 'article' | 'aside' | 'div'
  }>(),
  { padded: true, as: 'section' },
)
</script>

<template>
  <component :is="as" class="card">
    <header
      v-if="title || $slots.actions"
      class="flex items-start justify-between gap-4 border-b border-edge px-5 py-4 dark:border-[#334155]"
    >
      <div class="min-w-0">
        <h2 v-if="title" class="card-title">{{ title }}</h2>
        <p v-if="subtitle" class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
          {{ subtitle }}
        </p>
        <slot name="title" />
      </div>
      <div v-if="$slots.actions" class="flex shrink-0 items-center gap-2">
        <slot name="actions" />
      </div>
    </header>
    <div :class="padded ? 'card-body' : ''">
      <slot />
    </div>
  </component>
</template>