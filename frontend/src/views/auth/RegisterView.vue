<script setup lang="ts">
import { reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import { homeRouteFor } from '@/router'
import { ApiError } from '@/services/api'
import { useAuthStore } from '@/stores/authStore'
import { useToastStore } from '@/stores/toastStore'

/**
 * Registration screen.
 *
 * The validation rules below mirror the auth service's bean validation, so a mistake is
 * caught before a round trip; the server still decides, and its per-field violations are
 * mapped back onto the matching input.
 */
const auth = useAuthStore()
const toasts = useToastStore()
const router = useRouter()

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/
const PHONE_PATTERN = /^\+?[0-9]{6,20}$/
const NAME_MIN = 2
const NAME_MAX = 80
const EMAIL_MAX = 180
const PASSWORD_MIN = 8
const PASSWORD_MAX = 72

const PASSWORD_RULE = `Use ${PASSWORD_MIN}–${PASSWORD_MAX} characters, including at least one letter and one digit.`

const form = reactive({
  firstName: '',
  lastName: '',
  email: '',
  phone: '',
  password: '',
  address: '',
})

/** Field-level messages: local validation first, server violations as the fallback. */
const errors = ref<Record<string, string>>({})
const submitError = ref('')
const loading = ref(false)

function isValidName(value: string): boolean {
  const length = value.trim().length
  return length >= NAME_MIN && length <= NAME_MAX
}

function isValidPassword(value: string): boolean {
  return (
    value.length >= PASSWORD_MIN &&
    value.length <= PASSWORD_MAX &&
    /[a-zA-Z]/.test(value) &&
    /[0-9]/.test(value)
  )
}

function validate(): boolean {
  const next: Record<string, string> = {}

  if (!isValidName(form.firstName)) {
    next.firstName = `Enter a first name of ${NAME_MIN}–${NAME_MAX} characters`
  }
  if (!isValidName(form.lastName)) {
    next.lastName = `Enter a last name of ${NAME_MIN}–${NAME_MAX} characters`
  }

  const email = form.email.trim()
  if (!email) {
    next.email = 'Enter your email address'
  } else if (email.length > EMAIL_MAX || !EMAIL_PATTERN.test(email)) {
    next.email = `Enter a valid email address of at most ${EMAIL_MAX} characters`
  }

  const phone = form.phone.trim()
  if (!phone) {
    next.phone = 'Enter your phone number'
  } else if (!PHONE_PATTERN.test(phone)) {
    next.phone = 'Enter a phone number as +<country code><number>, digits only'
  }

  if (!isValidPassword(form.password)) {
    next.password = PASSWORD_RULE
  }

  errors.value = next
  return Object.keys(next).length === 0
}

function errorFor(field: string): string {
  return errors.value[field] ?? ''
}

async function onSubmit(): Promise<void> {
  submitError.value = ''

  if (!validate()) {
    toasts.error('Check the highlighted fields', 'Fix the form and try again.')
    return
  }

  loading.value = true
  errors.value = {}
  try {
    await auth.register({
      firstName: form.firstName.trim(),
      lastName: form.lastName.trim(),
      email: form.email.trim(),
      phone: form.phone.trim(),
      password: form.password,
      address: form.address.trim() || undefined,
    })
    toasts.success('Welcome to FleetFlow', 'Your account is ready.')
    await router.push(homeRouteFor([auth.role ?? 'CUSTOMER']))
  } catch (error) {
    if (error instanceof ApiError) {
      // A rejection such as a duplicate email arrives as a field violation, so it is shown
      // beside the input that caused it rather than only as a toast.
      const mapped: Record<string, string> = {}
      for (const violation of error.violations) {
        if (!mapped[violation.field]) {
          mapped[violation.field] = violation.message
        }
      }
      errors.value = mapped
      submitError.value = error.message
      toasts.error('Registration failed', error.message)
    } else {
      const message = 'Unable to reach FleetFlow right now. Check your connection and try again.'
      submitError.value = message
      toasts.error('Registration failed', message)
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="flex min-h-full flex-col bg-surface-muted dark:bg-[#0F172A]">
    <div class="mx-auto w-full max-w-xl flex-1 px-4 py-8 sm:px-6 sm:py-10 lg:py-14">
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

      <BaseCard
        title="Create your account"
        subtitle="New accounts are customer accounts: place orders and track deliveries."
      >
        <form novalidate class="space-y-4" @submit.prevent="onSubmit">
          <div class="grid gap-4 sm:grid-cols-2">
            <BaseInput
              v-model="form.firstName"
              label="First name"
              autocomplete="given-name"
              required
              :error="errorFor('firstName')"
              :disabled="loading"
            />
            <BaseInput
              v-model="form.lastName"
              label="Last name"
              autocomplete="family-name"
              required
              :error="errorFor('lastName')"
              :disabled="loading"
            />
          </div>

          <BaseInput
            v-model="form.email"
            label="Email address"
            type="email"
            inputmode="email"
            autocomplete="email"
            placeholder="you@example.com"
            required
            :error="errorFor('email')"
            :disabled="loading"
          />

          <BaseInput
            v-model="form.phone"
            label="Phone number"
            type="tel"
            inputmode="tel"
            autocomplete="tel"
            placeholder="+21620123456"
            hint="Used by your driver to arrange the delivery."
            required
            :error="errorFor('phone')"
            :disabled="loading"
          />

          <div>
            <BaseInput
              v-model="form.password"
              label="Password"
              type="password"
              autocomplete="new-password"
              required
              :error="errorFor('password')"
              :disabled="loading"
            />
            <!--
              `BaseInput` swaps its hint for the error, so the rule is rendered here to stay
              readable while the field is both empty of a violation and in an error state.
            -->
            <p
              v-if="form.password.length > 0"
              class="mt-1.5 text-small text-content-muted dark:text-[#94A3B8]"
            >
              {{ PASSWORD_RULE }}
            </p>
          </div>

          <BaseInput
            v-model="form.address"
            label="Delivery address"
            autocomplete="street-address"
            placeholder="Street, city, postal code"
            hint="Optional. You can add it later from your profile."
            :error="errorFor('address')"
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
            loadingLabel="Creating your account…"
          >
            Create account
          </BaseButton>

          <p class="text-center text-small text-content-muted dark:text-[#94A3B8]">
            Already have an account?
            <RouterLink
              to="/login"
              class="font-medium text-primary hover:underline dark:text-blue-300"
            >
              Sign in
            </RouterLink>
          </p>
        </form>
      </BaseCard>
    </div>
  </div>
</template>