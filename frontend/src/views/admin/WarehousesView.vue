<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { ApiError } from '@/services/api'
import { warehouseApi } from '@/services/inventoryApi'
import { useInventoryStore } from '@/stores/inventoryStore'
import { useToastStore } from '@/stores/toastStore'
import type { WarehouseResponse } from '@/types'
import { formatNumber, pluralize } from '@/utils/format'
import type { StatusMeta } from '@/utils/status'

/**
 * Warehouse register.
 *
 * `@/utils/status` has no warehouse vocabulary, so the server's status string is mapped
 * here into the same `StatusMeta` shape StatusBadge already consumes rather than printing
 * a raw enum.
 */

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: 'Active' },
  { value: 'INACTIVE', label: 'Inactive' },
]

interface WarehouseForm {
  id: number | null
  name: string
  address: string
  city: string
  capacity: string
  status: string
}

function statusMeta(status: string): StatusMeta {
  if (status.toUpperCase() === 'ACTIVE') {
    return { label: 'Active', tone: 'success', glyph: '●' }
  }
  return { label: status ? 'Inactive' : 'Unknown', tone: 'neutral', glyph: '○' }
}

const inventoryStore = useInventoryStore()
const toasts = useToastStore()

const loading = ref(true)
const error = ref<string | null>(null)

const formOpen = ref(false)
const form = ref<WarehouseForm>(blankForm())
const formErrors = ref<Record<string, string>>({})
const saving = ref(false)

function blankForm(): WarehouseForm {
  return { id: null, name: '', address: '', city: '', capacity: '', status: 'ACTIVE' }
}

const statusOptions = computed(() => STATUS_OPTIONS)
const editing = computed(() => form.value.id !== null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    // Written straight into the store's collection; its own fetcher swallows the error
    // this screen is required to show.
    inventoryStore.warehouses = await warehouseApi.list()
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : 'Warehouses could not be loaded.'
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  form.value = blankForm()
  formErrors.value = {}
  formOpen.value = true
}

function openEdit(warehouse: WarehouseResponse): void {
  form.value = {
    id: warehouse.id,
    name: warehouse.name,
    address: warehouse.address,
    city: warehouse.city,
    capacity: String(warehouse.capacity),
    status: warehouse.status,
  }
  formErrors.value = {}
  formOpen.value = true
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  if (!form.value.name.trim()) {
    errors.name = 'A warehouse name is required.'
  }
  if (!form.value.address.trim()) {
    errors.address = 'A street address is required.'
  }
  if (!form.value.city.trim()) {
    errors.city = 'A city is required.'
  }
  const capacity = Number(form.value.capacity)
  if (!Number.isFinite(capacity) || capacity <= 0) {
    errors.capacity = 'Capacity must be a positive number.'
  }
  formErrors.value = errors
  return Object.keys(errors).length === 0
}

async function save(): Promise<void> {
  if (!validate()) {
    return
  }
  saving.value = true
  const body = {
    name: form.value.name.trim(),
    address: form.value.address.trim(),
    city: form.value.city.trim(),
    capacity: Number(form.value.capacity),
  }

  try {
    if (form.value.id === null) {
      await warehouseApi.create(body)
      toasts.success('Warehouse created', `${body.name} can now hold stock.`)
    } else {
      await warehouseApi.update(form.value.id, { ...body, status: form.value.status as 'ACTIVE' | 'INACTIVE' })
      toasts.success('Warehouse updated', `${body.name} has been saved.`)
    }
    formOpen.value = false
    await load()
  } catch (caught) {
    if (caught instanceof ApiError) {
      for (const violation of caught.violations) {
        formErrors.value[violation.field] = violation.message
      }
      if (caught.violations.length === 0) {
        formErrors.value.name = caught.message
      }
    } else {
      formErrors.value.name = 'The warehouse could not be saved.'
    }
    toasts.fromException('Unable to save the warehouse', caught)
  } finally {
    saving.value = false
  }
}

function onText(field: 'name' | 'address' | 'city' | 'capacity') {
  return (value: string | number | null): void => {
    form.value = { ...form.value, [field]: String(value ?? '') }
  }
}

function onStatus(value: string | number | null): void {
  form.value = { ...form.value, status: String(value ?? 'ACTIVE') }
}

onMounted(load)
</script>

<template>
  <div class="space-y-4">
    <BaseCard title="Warehouses" subtitle="Every site that holds FleetFlow stock" :padded="false">
      <template #actions>
        <BaseButton variant="primary" size="sm" @click="openCreate">New warehouse</BaseButton>
      </template>

      <BaseSkeleton v-if="loading" class="px-5 py-4" variant="cards" :rows="3" label="Loading warehouses" />

      <ErrorState
        v-else-if="error"
        class="px-5 py-4"
        :message="error"
        retryLabel="Try again"
        @retry="load"
      />

      <div
        v-else-if="inventoryStore.warehouses.length > 0"
        class="grid grid-cols-1 gap-4 p-5 lg:grid-cols-2 2xl:grid-cols-3"
      >
        <article
          v-for="warehouse in inventoryStore.warehouses"
          :key="warehouse.id"
          class="rounded-card border border-edge p-4 dark:border-[#334155]"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <h3 class="card-title truncate">{{ warehouse.name }}</h3>
              <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                {{ warehouse.address }}, {{ warehouse.city }}
              </p>
            </div>
            <StatusBadge :meta="statusMeta(warehouse.status)" />
          </div>

          <dl class="mt-3.5 grid grid-cols-2 gap-3 border-t border-edge pt-3 dark:border-[#1E293B]">
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Capacity</dt>
              <dd class="text-body font-medium text-content dark:text-[#F8FAFC]">
                {{ formatNumber(warehouse.capacity) }} units
              </dd>
            </div>
            <div>
              <dt class="text-small text-content-muted dark:text-[#94A3B8]">Products held</dt>
              <dd class="text-body font-medium text-content dark:text-[#F8FAFC]">
                {{ pluralize(warehouse.distinctProductCount, 'product') }}
              </dd>
            </div>
          </dl>

          <div class="mt-3.5 flex justify-end">
            <BaseButton size="sm" variant="secondary" @click="openEdit(warehouse)">Edit</BaseButton>
          </div>
        </article>
      </div>

      <EmptyState
        v-else
        title="No warehouses yet"
        description="A warehouse has to exist before stock can be tracked against it."
        icon="inventory"
        actionLabel="Create the first warehouse"
        @action="openCreate"
      />
    </BaseCard>

    <ConfirmModal
      :open="formOpen"
      tone="primary"
      :title="editing ? `Edit ${form.name || 'warehouse'}` : 'New warehouse'"
      description="Capacity guides replenishment; it is not validated against live stock by the client."
      :confirmLabel="editing ? 'Save changes' : 'Create warehouse'"
      cancelLabel="Discard"
      :busy="saving"
      @cancel="formOpen = false"
      @confirm="save"
    >
      <div class="mt-3 space-y-3">
        <BaseInput
          :modelValue="form.name"
          label="Name"
          required
          :error="formErrors.name"
          @update:model-value="onText('name')"
        />
        <BaseInput
          :modelValue="form.address"
          label="Address"
          required
          :error="formErrors.address"
          @update:model-value="onText('address')"
        />
        <BaseInput
          :modelValue="form.city"
          label="City"
          required
          :error="formErrors.city"
          @update:model-value="onText('city')"
        />
        <BaseInput
          :modelValue="form.capacity"
          type="number"
          label="Capacity (units)"
          required
          :error="formErrors.capacity"
          @update:model-value="onText('capacity')"
        />
        <BaseSelect
          v-if="editing"
          :modelValue="form.status"
          label="Status"
          :options="statusOptions"
          @update:model-value="onStatus"
        />
      </div>
    </ConfirmModal>
  </div>
</template>