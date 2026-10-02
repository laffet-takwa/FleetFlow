<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'

import { ApiError } from '@/services/api'
import { driverApi } from '@/services/deliveryApi'
import { useAuthStore } from '@/stores/authStore'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { useNotificationStore } from '@/stores/notificationStore'
import { initialsOf } from '@/utils/format'
import { driverStatus } from '@/utils/status'

import type { DriverResponse, DriverStatus } from '@/types'

const router = useRouter()
const auth = useAuthStore()
const delivery = useDeliveryStore()
const notifications = useNotificationStore()

const driver = ref<DriverResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
const statusError = ref<string | null>(null)
const statusBusy = ref<DriverStatus | null>(null)
const signingOut = ref(false)

const statusMeta = computed(() => driverStatus(driver.value?.status ?? 'OFFLINE'))

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    driver.value = await driverApi.me()
  } catch (caught) {
    error.value =
      caught instanceof ApiError ? caught.message : 'Unable to load your driver record right now.'
  } finally {
    loading.value = false
  }
}

async function setStatus(next: DriverStatus): Promise<void> {
  if (statusBusy.value) {
    return
  }
  statusBusy.value = next
  statusError.value = null
  const changed = await delivery.setDriverStatus(next)
  statusBusy.value = null

  if (changed) {
    driver.value = await driverApi.me().catch(() => driver.value)
    return
  }
  statusError.value =
    'Your status was not changed. Operations may need to close the delivery in progress first.'
}

async function signOut(): Promise<void> {
  signingOut.value = true
  auth.clear()
  notifications.reset()
  await router.push({ name: 'login' })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="space-y-4">
    <header>
      <h1 class="page-title">Profile</h1>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        Your driver record and availability.
      </p>
    </header>

    <BaseSkeleton v-if="loading" variant="lines" :rows="5" />

    <ErrorState v-else-if="error" :message="error" retryLabel="Try again" @retry="load" />

    <template v-else-if="driver">
      <BaseCard>
        <div class="flex items-center gap-3.5">
          <span
            class="flex h-14 w-14 shrink-0 items-center justify-center rounded-full bg-blue-50 text-section-title font-bold text-primary dark:bg-blue-950 dark:text-blue-300"
            aria-hidden="true"
          >
            {{ initialsOf(driver.fullName) }}
          </span>
          <div class="min-w-0">
            <p class="break-words text-section-title font-semibold leading-tight text-content dark:text-[#F8FAFC]">
              {{ driver.fullName }}
            </p>
            <div class="mt-1.5"><StatusBadge :meta="statusMeta" /></div>
          </div>
        </div>

        <dl class="mt-5 divide-y divide-edge dark:divide-[#1E293B]">
          <div class="flex items-baseline justify-between gap-3 py-3">
            <dt class="text-small font-medium text-content-muted dark:text-[#94A3B8]">
              Licence number
            </dt>
            <dd class="break-all text-right text-body font-semibold text-content dark:text-[#F8FAFC]">
              {{ driver.licenseNumber }}
            </dd>
          </div>
          <div class="flex items-baseline justify-between gap-3 py-3">
            <dt class="text-small font-medium text-content-muted dark:text-[#94A3B8]">Phone</dt>
            <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">
              <a
                :href="`tel:${driver.phone.replace(/\s+/g, '')}`"
                class="text-primary"
              >{{ driver.phone }}</a>
            </dd>
          </div>
          <div class="flex items-baseline justify-between gap-3 py-3">
            <dt class="text-small font-medium text-content-muted dark:text-[#94A3B8]">Vehicle</dt>
            <dd class="break-all text-right text-body font-semibold text-content dark:text-[#F8FAFC]">
              {{ driver.vehicleRegistration ?? 'Not assigned' }}
            </dd>
          </div>
          <div class="flex items-baseline justify-between gap-3 py-3">
            <dt class="text-small font-medium text-content-muted dark:text-[#94A3B8]">Deliveries</dt>
            <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">
              {{ driver.completedDeliveries }} completed
            </dd>
          </div>
        </dl>

        <p class="mt-4 rounded-control bg-surface-muted px-3 py-2.5 text-small text-content-muted dark:bg-[#1E293B] dark:text-[#94A3B8]">
          Your name, licence and vehicle are maintained by operations. Ask dispatch to change
          them; this screen is read-only on purpose.
        </p>
      </BaseCard>

      <BaseCard title="Availability">
        <div
          v-if="statusError"
          role="alert"
          class="mb-4 rounded-control bg-red-50 px-3 py-2.5 text-small text-danger dark:bg-red-950"
        >
          {{ statusError }}
        </div>

        <div class="grid grid-cols-2 gap-3" role="group" aria-label="Change your availability">
          <BaseButton
            size="touch"
            :variant="driver.status === 'AVAILABLE' ? 'primary' : 'secondary'"
            :disabled="driver.status === 'AVAILABLE'"
            :loading="statusBusy === 'AVAILABLE'"
            loadingLabel="Saving…"
            @click="setStatus('AVAILABLE')"
          >
            Available
          </BaseButton>
          <BaseButton
            size="touch"
            :variant="driver.status === 'OFFLINE' ? 'primary' : 'secondary'"
            :disabled="driver.status === 'OFFLINE'"
            :loading="statusBusy === 'OFFLINE'"
            loadingLabel="Saving…"
            @click="setStatus('OFFLINE')"
          >
            Go offline
          </BaseButton>
        </div>
      </BaseCard>

      <BaseCard title="How this app works">
        <ul class="space-y-3 text-body text-content-muted dark:text-[#94A3B8]">
          <li>
            <span class="font-semibold text-content dark:text-[#F8FAFC]">Available</span> —
            operations can assign you a delivery at any time.
          </li>
          <li>
            <span class="font-semibold text-content dark:text-[#F8FAFC]">On delivery</span> —
            set for you when a delivery is assigned. Close it before going back to available.
          </li>
          <li>
            <span class="font-semibold text-content dark:text-[#F8FAFC]">Offline</span> — you
            stop receiving assignments. Use it at the end of a shift.
          </li>
          <li>
            <span class="font-semibold text-content dark:text-[#F8FAFC]">Location</span> — this
            build has no GPS hardware. On a picked-up or in-transit delivery, the map is fed by a
            clearly labelled demo simulation you start and stop yourself.
          </li>
        </ul>
      </BaseCard>

      <BaseButton size="touch" variant="danger" fullWidth :loading="signingOut" @click="signOut">
        Sign out
      </BaseButton>
    </template>
  </div>
</template>