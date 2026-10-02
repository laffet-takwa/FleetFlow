<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import { homeRouteFor } from '@/router'
import { ApiError } from '@/services/api'
import { useAuthStore } from '@/stores/authStore'
import { useToastStore } from '@/stores/toastStore'
import type { Role } from '@/types'

/**
 * Sign-in screen.
 *
 * `meta.layout` is `blank`, so no shell is rendered and this view owns its own centred
 * frame; it reuses the shared primitives for everything else so the form looks identical
 * to the rest of the product.
 */
const auth = useAuthStore()
const toasts = useToastStore()
const route = useRoute()
const router = useRouter()

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/

const DEMO_PASSWORD = 'Password123!'

interface DemoAccount {
  email: string
  role: Role
  description: string
}

const ROLE_LABEL: Record<Role, string> = {
  ADMIN: 'Administrator',
  OPERATIONS: 'Operations',
  DRIVER: 'Driver',
  CUSTOMER: 'Customer',
}

const DEMO_ACCOUNTS: DemoAccount[] = [
  {
    email: 'admin@fleetflow.local',
    role: 'ADMIN',
    description: 'Every operations screen, including settings',
  },
  {
    email: 'operations@fleetflow.local',
    role: 'OPERATIONS',
    description: 'Orders, deliveries, drivers and inventory',
  },
  {
    email: 'driver1@fleetflow.local',
    role: 'DRIVER',
    description: "Today's route and delivery actions",
  },
  {
    email: 'customer1@fleetflow.local',
    role: 'CUSTOMER',
    description: 'Place an order and track its delivery',
  },
]

const form = reactive({ email: '', password: '' })
const emailError = ref('')
const passwordError = ref('')
const submitError = ref('')
const loading = ref(false)

function validate(): boolean {
  const email = form.email.trim()
  let ok = true

  if (!email) {
    emailError.value = 'Enter your email address'
    ok = false
  } else if (email.length > 180 || !EMAIL_PATTERN.test(email)) {
    emailError.value = 'Enter a valid email address'
    ok = false
  }

  if (!form.password) {
    passwordError.value = 'Enter your password'
    ok = false
  }

  return ok
}

/** Only same-site paths are honoured, so `?redirect=` cannot bounce a user off-site. */
function safeRedirect(): string {
  const target = route.query.redirect
  if (typeof target !== 'string') {
    return ''
  }
  return target.startsWith('/') && !target.startsWith('//') ? target : ''
}

async function onSubmit(): Promise<void> {
  submitError.value = ''
  emailError.value = ''
  passwordError.value = ''

  if (!validate()) {
    toasts.error('Check the highlighted fields', 'Fix the sign-in form and try again.')
    return
  }

  loading.value = true
  try {
    await auth.login(form.email.trim(), form.password)
    const redirect = safeRedirect()
    await router.push(redirect || homeRouteFor([auth.role ?? 'CUSTOMER']))
  } catch (error) {
    // The API layer already returns a readable, user-facing message; a stack trace or a
    // raw status code is never surfaced here.
    const message =
      error instanceof ApiError
        ? error.message
        : 'Unable to reach FleetFlow right now. Check your connection and try again.'
    const emailViolation = error instanceof ApiError ? error.violationFor('email') : undefined

    submitError.value = emailViolation ?? message
    if (emailViolation) {
      emailError.value = emailViolation
    }
    toasts.error('Sign in failed', emailViolation ?? message)
  } finally {
    loading.value = false
  }
}

function useDemoAccount(account: DemoAccount): void {
  form.email = account.email
  form.password = DEMO_PASSWORD
  emailError.value = ''
  passwordError.value = ''
  submitError.value = ''
}
</script>

<template>
  <div class="flex min-h-full flex-col bg-surface-muted dark:bg-[#0F172A]">
    <div
      class="mx-auto grid w-full max-w-4xl flex-1 items-start gap-6 px-4 py-8 sm:px-6 sm:py-10 lg:grid-cols-[minmax(0,26rem)_minmax(0,20rem)] lg:justify-center lg:gap-8 lg:py-16"
    >
      <div class="w-full">
        <div class="mb-6 flex items-start gap-3">
          <span
            class="flex h-11 w-11 shrink-0 items-center justify-center rounded-card bg-primary text-white"
            aria-hidden="true"
          >
            <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round" />
              <circle cx="7" cy="18" r="1.5" />
              <circle cx="17" cy="18" r="1.5" />
            </svg>
          </span>
          <div class="min-w-0">
            <p class="text-section-title font-semibold tracking-[-0.01em] text-content dark:text-[#F8FAFC]">
              FleetFlow
            </p>
            <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
              From warehouse to doorstep, every delivery in motion.
            </p>
          </div>
        </div>

        <BaseCard title="Sign in" subtitle="Use your work email address to continue.">
          <form novalidate class="space-y-4" @submit.prevent="onSubmit">
            <BaseInput
              v-model="form.email"
              label="Email address"
              type="email"
              inputmode="email"
              autocomplete="username"
              placeholder="you@fleetflow.local"
              required
              :error="emailError"
              :disabled="loading"
            />

            <BaseInput
              v-model="form.password"
              label="Password"
              type="password"
              autocomplete="current-password"
              placeholder="Your password"
              required
              :error="passwordError"
              :disabled="loading"
            />

            <p
              v-if="submitError"
              class="rounded-control border border-red-200 bg-red-50 px-3 py-2.5 text-small text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
              role="alert"
            >
              {{ submitError }}
            </p>

            <BaseButton
              type="submit"
              variant="primary"
              size="lg"
              fullWidth
              :loading="loading"
              loadingLabel="Signing in…"
            >
              Sign in
            </BaseButton>

            <p class="text-center text-small text-content-muted dark:text-[#94A3B8]">
              New to FleetFlow?
              <RouterLink
                to="/register"
                class="font-medium text-primary hover:underline dark:text-blue-300"
              >
                Create an account
              </RouterLink>
            </p>
          </form>
        </BaseCard>
      </div>

      <BaseCard
        title="Demo accounts"
        subtitle="Select a row to fill the form above."
      >
        <template #actions>
          <span
            class="whitespace-nowrap rounded-full bg-surface-muted px-2 py-0.5 text-small text-content-muted ring-1 ring-inset ring-edge dark:bg-[#1E293B] dark:text-[#94A3B8] dark:ring-[#334155]"
          >
            Development only
          </span>
        </template>
        <ul class="-mx-1 divide-y divide-edge dark:divide-[#1E293B]">
          <li v-for="account in DEMO_ACCOUNTS" :key="account.email">
            <button
              type="button"
              class="group flex w-full items-start gap-3 rounded-control px-1 py-3 text-left transition-colors duration-150 hover:bg-surface-muted dark:hover:bg-[#1A2436]"
              :aria-label="`Fill the sign-in form with ${account.email}`"
              @click="useDemoAccount(account)"
            >
              <span class="min-w-0 flex-1">
                <span class="block truncate text-body font-medium text-content dark:text-[#F8FAFC]">
                  {{ account.email }}
                </span>
                <span class="mt-0.5 block text-small text-content-muted dark:text-[#94A3B8]">
                  {{ ROLE_LABEL[account.role] }} · {{ account.description }}
                </span>
              </span>
              <svg
                class="mt-0.5 h-4 w-4 shrink-0 text-content-subtle transition-colors group-hover:text-primary dark:group-hover:text-blue-300"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.8"
                aria-hidden="true"
              >
                <path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </li>
        </ul>

        <div class="mt-4 border-t border-edge pt-4 dark:border-[#1E293B]">
          <p class="text-small text-content-muted dark:text-[#94A3B8]">
            Shared password
            <span class="font-mono text-content dark:text-[#F8FAFC]">{{ DEMO_PASSWORD }}</span>
          </p>
          <p class="mt-2 text-small text-content-subtle dark:text-[#64748B]">
            These four accounts are recreated by the seed data every time the platform
            starts locally.
          </p>
        </div>
      </BaseCard>
    </div>
  </div>
</template>