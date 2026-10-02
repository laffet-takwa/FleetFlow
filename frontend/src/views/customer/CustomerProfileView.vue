<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import { ApiError } from '@/services/api'
import { customerApi } from '@/services/customerApi'
import { useToastStore } from '@/stores/toastStore'
import type { CustomerResponse, UpdateProfileRequest } from '@/types'
import { formatDate } from '@/utils/format'

/**
 * Customer profile.
 *
 * The rules below are the ones `UpdateProfileRequest` enforces, duplicated on the client so
 * a mistake is caught before a round trip; the server's own per-field violations still win
 * when they arrive, because they are mapped onto the matching input.
 */

const PHONE_PATTERN = /^\+?[0-9]{6,20}$/

const LIMITS = {
  firstName: { min: 2, max: 80 },
  lastName: { min: 2, max: 80 },
  address: 255,
  city: 80,
  postalCode: 16,
}

const toasts = useToastStore()

const profile = ref<CustomerResponse | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const fieldErrors = ref<Record<string, string>>({})

const form = reactive({
  firstName: '',
  lastName: '',
  phone: '',
  address: '',
  city: '',
  postalCode: '',
})

const saved = reactive({ ...form })

const dirty = computed(() =>
  (Object.keys(form) as (keyof typeof form)[]).some((key) => form[key] !== saved[key]),
)

async function load(): Promise<void> {
  loading.value = true
  loadError.value = null
  try {
    const loaded = await customerApi.me()
    profile.value = loaded
    form.firstName = loaded.firstName
    form.lastName = loaded.lastName
    form.phone = loaded.phone
    form.address = loaded.address ?? ''
    form.city = loaded.city ?? ''
    form.postalCode = loaded.postalCode ?? ''
    Object.assign(saved, form)
  } catch (caught) {
    profile.value = null
    loadError.value =
      caught instanceof ApiError ? caught.message : 'Your profile could not be loaded.'
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  const errors: Record<string, string> = {}

  const firstName = form.firstName.trim()
  if (!firstName) {
    errors.firstName = 'Enter your first name'
  } else if (firstName.length < LIMITS.firstName.min || firstName.length > LIMITS.firstName.max) {
    errors.firstName = `Your first name must be between ${LIMITS.firstName.min} and ${LIMITS.firstName.max} characters`
  }

  const lastName = form.lastName.trim()
  if (!lastName) {
    errors.lastName = 'Enter your last name'
  } else if (lastName.length < LIMITS.lastName.min || lastName.length > LIMITS.lastName.max) {
    errors.lastName = `Your last name must be between ${LIMITS.lastName.min} and ${LIMITS.lastName.max} characters`
  }

  const phone = form.phone.trim()
  if (!phone) {
    errors.phone = 'Enter a phone number so the driver can reach you'
  } else if (!PHONE_PATTERN.test(phone)) {
    errors.phone = 'Use 6 to 20 digits, optionally starting with +'
  }

  if (form.address.trim().length > LIMITS.address) {
    errors.address = `Keep the address under ${LIMITS.address} characters`
  }
  if (form.city.trim().length > LIMITS.city) {
    errors.city = `Keep the city under ${LIMITS.city} characters`
  }
  if (form.postalCode.trim().length > LIMITS.postalCode) {
    errors.postalCode = `Keep the postal code under ${LIMITS.postalCode} characters`
  }

  fieldErrors.value = errors
  return Object.keys(errors).length === 0
}

function discard(): void {
  Object.assign(form, saved)
  fieldErrors.value = {}
  saveError.value = null
}

async function save(): Promise<void> {
  saveError.value = null
  fieldErrors.value = {}
  if (!validate()) {
    toasts.warning('Check the highlighted fields', 'Fix the form and try again.')
    return
  }

  const payload: UpdateProfileRequest = {
    firstName: form.firstName.trim(),
    lastName: form.lastName.trim(),
    phone: form.phone.trim(),
    address: form.address.trim() || undefined,
    city: form.city.trim() || undefined,
    postalCode: form.postalCode.trim() || undefined,
  }

  saving.value = true
  try {
    const updated = await customerApi.updateMe(payload)
    profile.value = updated
    form.firstName = updated.firstName
    form.lastName = updated.lastName
    form.phone = updated.phone
    form.address = updated.address ?? ''
    form.city = updated.city ?? ''
    form.postalCode = updated.postalCode ?? ''
    Object.assign(saved, form)
    toasts.success('Profile updated', 'Your details have been saved.')
  } catch (caught) {
    // The server's per-field violations are the authority; they replace the client guesses.
    if (caught instanceof ApiError) {
      fieldErrors.value = {
        firstName: caught.violationFor('firstName') ?? '',
        lastName: caught.violationFor('lastName') ?? '',
        phone: caught.violationFor('phone') ?? '',
        address: caught.violationFor('address') ?? '',
        city: caught.violationFor('city') ?? '',
        postalCode: caught.violationFor('postalCode') ?? '',
      }
      saveError.value = caught.message
    } else {
      saveError.value = 'Your profile could not be saved. Please try again.'
    }
    toasts.fromException('Unable to save your profile', caught)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="space-y-4">
    <header>
      <h2 class="page-title">Profile</h2>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        The details your driver uses to reach you.
      </p>
    </header>

    <BaseCard v-if="loading" :padded="false" as="section">
      <BaseSkeleton class="px-5 py-5" variant="lines" :rows="6" label="Loading your profile" />
    </BaseCard>

    <BaseCard v-else-if="loadError" :padded="false" as="section">
      <ErrorState :message="loadError" retryLabel="Try again" @retry="load" />
    </BaseCard>

    <template v-else-if="profile">
      <BaseCard as="section" title="Account">
        <dl class="space-y-3">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <dt class="text-small text-content-muted dark:text-[#94A3B8]">Email address</dt>
            <dd class="text-body text-content dark:text-[#F8FAFC]">{{ profile.email }}</dd>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-2">
            <dt class="text-small text-content-muted dark:text-[#94A3B8]">Customer since</dt>
            <dd class="text-body text-content dark:text-[#F8FAFC]">
              {{ formatDate(profile.createdAt) }}
            </dd>
          </div>
        </dl>

        <p class="mt-4 rounded-control border border-edge bg-surface-muted px-3 py-2.5 text-small text-content-muted dark:border-[#334155] dark:bg-[#0F172A] dark:text-[#94A3B8]">
          Your email address and password are managed by the FleetFlow account service. The
          password cannot be changed from here — use your account settings for that.
        </p>
      </BaseCard>

      <BaseCard as="section" title="Your details">
        <form novalidate class="space-y-4" @submit.prevent="save">
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <BaseInput
              v-model="form.firstName"
              label="First name"
              autocomplete="given-name"
              required
              :error="fieldErrors.firstName ?? ''"
              :disabled="saving"
            />
            <BaseInput
              v-model="form.lastName"
              label="Last name"
              autocomplete="family-name"
              required
              :error="fieldErrors.lastName ?? ''"
              :disabled="saving"
            />
          </div>

          <BaseInput
            v-model="form.phone"
            label="Phone number"
            type="tel"
            inputmode="tel"
            autocomplete="tel"
            placeholder="+21620100101"
            hint="The driver calls this number if they cannot find the address."
            required
            :error="fieldErrors.phone ?? ''"
            :disabled="saving"
          />

          <BaseInput
            v-model="form.address"
            label="Street address"
            autocomplete="street-address"
            placeholder="12 Rue Habib Bourguiba"
            :error="fieldErrors.address ?? ''"
            :disabled="saving"
          />

          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <BaseInput
              v-model="form.city"
              label="City"
              autocomplete="address-level2"
              placeholder="Tunis"
              :error="fieldErrors.city ?? ''"
              :disabled="saving"
            />
            <BaseInput
              v-model="form.postalCode"
              label="Postal code"
              autocomplete="postal-code"
              inputmode="numeric"
              placeholder="1000"
              :error="fieldErrors.postalCode ?? ''"
              :disabled="saving"
            />
          </div>

          <p
            v-if="saveError"
            class="rounded-control border border-red-200 bg-red-50 px-3 py-2.5 text-small text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
            role="alert"
          >
            {{ saveError }}
          </p>

          <div class="flex flex-col-reverse gap-2 border-t border-edge pt-4 sm:flex-row sm:justify-end dark:border-[#334155]">
            <BaseButton
              variant="secondary"
              size="lg"
              fullWidth
              :disabled="!dirty || saving"
              @click="discard"
            >
              Discard changes
            </BaseButton>
            <BaseButton
              type="submit"
              variant="primary"
              size="lg"
              fullWidth
              :loading="saving"
              loadingLabel="Saving…"
            >
              Save changes
            </BaseButton>
          </div>
        </form>
      </BaseCard>
    </template>
  </div>
</template>