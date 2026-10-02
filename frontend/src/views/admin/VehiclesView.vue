<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import ConfirmModal from '@/components/ui/ConfirmModal.vue'
import DataTable from '@/components/ui/DataTable.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { ApiError } from '@/services/api'
import { vehicleApi } from '@/services/deliveryApi'
import { useDebouncedRef } from '@/stores/asyncStore'
import { useDeliveryStore } from '@/stores/deliveryStore'
import { useToastStore } from '@/stores/toastStore'
import type { VehicleResponse, VehicleStatus, VehicleType } from '@/types'
import { formatNumber } from '@/utils/format'
import { vehicleStatus } from '@/utils/status'

/**
 * Fleet register.
 *
 * Vehicle types have no formatter in `@/utils/status`, so the label table lives here next
 * to the write form that produces those values.
 */

const PAGE_SIZE = 15

const VEHICLE_TYPE_LABEL: Record<VehicleType, string> = {
  VAN: 'Van',
  MOTORCYCLE: 'Motorcycle',
  TRUCK: 'Truck',
  CAR: 'Car',
}

const STATUS_OPTIONS: { value: VehicleStatus; label: string }[] = [
  { value: 'AVAILABLE', label: 'Available' },
  { value: 'IN_USE', label: 'In use' },
  { value: 'MAINTENANCE', label: 'Maintenance' },
]

const TYPE_OPTIONS = (Object.keys(VEHICLE_TYPE_LABEL) as VehicleType[]).map((type) => ({
  value: type,
  label: VEHICLE_TYPE_LABEL[type],
}))

interface VehicleForm {
  id: number | null
  registrationNumber: string
  type: VehicleType
  capacity: string
  status: VehicleStatus
}

function blankForm(): VehicleForm {
  return { id: null, registrationNumber: '', type: 'VAN', capacity: '', status: 'AVAILABLE' }
}

const deliveryStore = useDeliveryStore()
const toasts = useToastStore()

const status = ref<VehicleStatus | ''>('')
const type = ref<VehicleType | ''>('')
const search = ref('')
const { debounced: debouncedSearch, watchSource: queueSearch } = useDebouncedRef(() => search.value, 300)
const appliedSearch = ref('')

const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)

const filtersOpen = ref(false)

const formOpen = ref(false)
const form = ref<VehicleForm>(blankForm())
const formErrors = ref<Record<string, string>>({})
const saving = ref(false)

const columns = [
  { key: 'registration', label: 'Registration' },
  { key: 'type', label: 'Type' },
  { key: 'capacity', label: 'Capacity', align: 'right' as const },
  { key: 'driver', label: 'Driver' },
  { key: 'status', label: 'Status' },
  { key: 'actions', label: 'Actions', align: 'right' as const },
]

const editing = computed(() => form.value.id !== null)

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await vehicleApi.list({
      page: page.value,
      size: PAGE_SIZE,
      status: status.value || undefined,
      type: type.value || undefined,
      search: appliedSearch.value || undefined,
    })
    deliveryStore.vehicles = result.content
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : 'The fleet could not be loaded.'
  } finally {
    loading.value = false
  }
}

function onSearchInput(value: string | number | null): void {
  search.value = String(value ?? '')
  queueSearch(search.value)
}

function onStatusFilter(value: string | number | null): void {
  status.value = (value ?? '') as VehicleStatus | ''
  page.value = 0
  void load()
}

function onTypeFilter(value: string | number | null): void {
  type.value = (value ?? '') as VehicleType | ''
  page.value = 0
  void load()
}

function goToPage(next: number): void {
  page.value = next
  void load()
}

function clearFilters(): void {
  search.value = ''
  appliedSearch.value = ''
  status.value = ''
  type.value = ''
  page.value = 0
  void load()
}

function openCreate(): void {
  form.value = blankForm()
  formErrors.value = {}
  formOpen.value = true
}

function openEdit(vehicle: VehicleResponse): void {
  form.value = {
    id: vehicle.id,
    registrationNumber: vehicle.registrationNumber,
    type: vehicle.type,
    capacity: String(vehicle.capacity),
    status: vehicle.status,
  }
  formErrors.value = {}
  formOpen.value = true
}

function validate(): boolean {
  const errors: Record<string, string> = {}
  if (!form.value.registrationNumber.trim()) {
    errors.registrationNumber = 'A registration number is required.'
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
    registrationNumber: form.value.registrationNumber.trim(),
    type: form.value.type,
    capacity: Number(form.value.capacity),
    status: form.value.status,
  }
  try {
    if (form.value.id === null) {
      await vehicleApi.create(body)
      toasts.success('Vehicle added', `${body.registrationNumber} joined the fleet.`)
    } else {
      await vehicleApi.update(form.value.id, body)
      toasts.success('Vehicle updated', `${body.registrationNumber} has been saved.`)
    }
    formOpen.value = false
    await load()
  } catch (caught) {
    if (caught instanceof ApiError) {
      for (const violation of caught.violations) {
        formErrors.value[violation.field] = violation.message
      }
      if (caught.violations.length === 0) {
        formErrors.value.registrationNumber = caught.message
      }
    } else {
      formErrors.value.registrationNumber = 'The vehicle could not be saved.'
    }
    toasts.fromException('Unable to save the vehicle', caught)
  } finally {
    saving.value = false
  }
}

function onRegistrationInput(value: string | number | null): void {
  form.value = { ...form.value, registrationNumber: String(value ?? '') }
}

function onCapacityInput(value: string | number | null): void {
  form.value = { ...form.value, capacity: String(value ?? '') }
}

function onFormType(value: string | number | null): void {
  form.value = { ...form.value, type: (value || 'VAN') as VehicleType }
}

function onFormStatus(value: string | number | null): void {
  form.value = { ...form.value, status: (value || 'AVAILABLE') as VehicleStatus }
}

watch(debouncedSearch, (value) => {
  const term = value.trim()
  if (term === appliedSearch.value) {
    return
  }
  appliedSearch.value = term
  page.value = 0
  void load()
})

onMounted(load)
</script>

<template>
  <div class="space-y-4">
    <BaseCard title="Fleet" subtitle="Vehicles available for assignment" :padded="false">
      <template #actions>
        <BaseButton size="sm" variant="primary" @click="openCreate">Add vehicle</BaseButton>
        <BaseButton size="sm" variant="ghost" class="lg:hidden" @click="filtersOpen = !filtersOpen">
          {{ filtersOpen ? 'Hide filters' : 'Filters' }}
        </BaseButton>
      </template>

      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div
          class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3"
          :class="filtersOpen ? 'grid' : 'hidden lg:grid'"
        >
          <BaseInput
            :modelValue="search"
            type="search"
            label="Search"
            placeholder="Registration"
            @update:model-value="onSearchInput"
          />
          <BaseSelect
            :modelValue="status"
            label="Status"
            :options="STATUS_OPTIONS"
            clearable
            @update:model-value="onStatusFilter"
          />
          <BaseSelect
            :modelValue="type"
            label="Type"
            :options="TYPE_OPTIONS"
            clearable
            @update:model-value="onTypeFilter"
          />
        </div>

        <div class="mt-3">
          <BaseButton size="sm" variant="ghost" @click="clearFilters">Clear filters</BaseButton>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="deliveryStore.vehicles"
        rowKey="id"
        :loading="loading"
        :error="error"
        emptyTitle="No vehicles match these filters"
        emptyDescription="Clear the filters, or add the vehicle you expected to find."
        emptyIcon="deliveries"
        emptyActionLabel="Clear filters"
        caption="Fleet register"
        @retry="load"
        @empty-action="clearFilters"
      >
        <template #cell-registration="{ row }">
          <span class="font-mono text-body font-medium text-content dark:text-[#F8FAFC]">
            {{ row.registrationNumber }}
          </span>
        </template>

        <template #cell-type="{ row }">
          <span class="text-body text-content-muted dark:text-[#94A3B8]">
            {{ VEHICLE_TYPE_LABEL[row.type] }}
          </span>
        </template>

        <template #cell-capacity="{ row }">
          <span class="tabular-nums text-body text-content dark:text-[#F8FAFC]">
            {{ formatNumber(row.capacity) }}
          </span>
        </template>

        <template #cell-driver="{ row }">
          <span class="text-body text-content-muted dark:text-[#94A3B8]">{{ row.driverName ?? '—' }}</span>
        </template>

        <template #cell-status="{ row }">
          <StatusBadge :meta="vehicleStatus(row.status)" />
        </template>

        <template #cell-actions="{ row }">
          <BaseButton
            size="sm"
            variant="secondary"
            :aria-label="`Edit vehicle ${row.registrationNumber}`"
            @click="openEdit(row)"
          >
            Edit
          </BaseButton>
        </template>
      </DataTable>

      <TablePagination
        :page="page"
        :totalPages="totalPages"
        :totalElements="totalElements"
        :size="PAGE_SIZE"
        label="vehicles"
        @change="goToPage"
      />
    </BaseCard>

    <ConfirmModal
      :open="formOpen"
      tone="primary"
      :title="editing ? `Edit ${form.registrationNumber || 'vehicle'}` : 'Add a vehicle'"
      description="Only a vehicle the service reports as available can be assigned to a delivery."
      :confirmLabel="editing ? 'Save changes' : 'Add vehicle'"
      cancelLabel="Discard"
      :busy="saving"
      @cancel="formOpen = false"
      @confirm="save"
    >
      <div class="mt-3 space-y-3">
        <BaseInput
          :modelValue="form.registrationNumber"
          label="Registration number"
          required
          :error="formErrors.registrationNumber"
          @update:model-value="onRegistrationInput"
        />
        <BaseSelect
          :modelValue="form.type"
          label="Type"
          :options="TYPE_OPTIONS"
          required
          @update:model-value="onFormType"
        />
        <BaseInput
          :modelValue="form.capacity"
          type="number"
          label="Capacity (units)"
          required
          :error="formErrors.capacity"
          @update:model-value="onCapacityInput"
        />
        <BaseSelect
          :modelValue="form.status"
          label="Status"
          :options="STATUS_OPTIONS"
          required
          @update:model-value="onFormStatus"
        />
      </div>
    </ConfirmModal>
  </div>
</template>