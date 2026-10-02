<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import DataTable from '@/components/ui/DataTable.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { ApiError } from '@/services/api'
import { driverApi } from '@/services/deliveryApi'
import { useDebouncedRef } from '@/stores/asyncStore'
import { useDeliveryStore } from '@/stores/deliveryStore'
import type { DriverResponse, DriverStatus } from '@/types'
import { deliveryLabel, pluralize } from '@/utils/format'
import { deliveryStatus, driverStatus } from '@/utils/status'

/**
 * Driver roster.
 *
 * The store's `fetchDrivers` discards its own errors, so the paged list is requested here
 * and written into the same store collection — the state stays in the store while this
 * screen keeps the failure it has to show.
 */

const PAGE_SIZE = 15

const STATUS_FILTERS: { value: DriverStatus | ''; label: string }[] = [
  { value: '', label: 'All' },
  { value: 'AVAILABLE', label: 'Available' },
  { value: 'ON_DELIVERY', label: 'On delivery' },
  { value: 'OFFLINE', label: 'Offline' },
]

const deliveryStore = useDeliveryStore()
const router = useRouter()

const status = ref<DriverStatus | ''>('')
const search = ref('')
const { debounced: debouncedSearch, watchSource: queueSearch } = useDebouncedRef(() => search.value, 300)

/** Last term actually sent to the API, so the debounce cannot re-fire on itself. */
const appliedSearch = ref('')

const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)

const columns = [
  { key: 'driver', label: 'Driver' },
  { key: 'phone', label: 'Phone' },
  { key: 'status', label: 'Status' },
  { key: 'delivery', label: 'Current delivery' },
  { key: 'vehicle', label: 'Vehicle' },
  { key: 'completed', label: 'Completed', align: 'right' as const },
  { key: 'actions', label: 'Actions', align: 'right' as const },
]

const filtersOpen = ref(false)

const chipClass = (active: boolean): string =>
  [
    'inline-flex h-8 items-center rounded-full px-3 text-small font-medium transition-colors duration-150',
    active
      ? 'bg-primary text-white'
      : 'bg-surface text-content-muted ring-1 ring-inset ring-edge hover:bg-surface-muted dark:bg-[#111827] dark:text-[#94A3B8] dark:ring-[#334155] dark:hover:bg-[#1E293B]',
  ].join(' ')

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const result = await driverApi.list({
      page: page.value,
      size: PAGE_SIZE,
      status: status.value || undefined,
      search: search.value.trim() || undefined,
    })
    deliveryStore.drivers = result.content
    totalPages.value = result.totalPages
    totalElements.value = result.totalElements
  } catch (caught) {
    error.value = caught instanceof ApiError ? caught.message : 'The driver roster could not be loaded.'
  } finally {
    loading.value = false
  }
}

function onSearchInput(value: string | number | null): void {
  search.value = String(value ?? '')
  queueSearch(search.value)
}

function selectStatus(next: DriverStatus | ''): void {
  status.value = next
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
  page.value = 0
  void load()
}

/** Hands the roster's driver over as a filter on the deliveries screen. */
function assignTo(delivery: DriverResponse): void {
  void router.push({ name: 'admin-deliveries', query: { driver: String(delivery.id) } })
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
    <BaseCard title="Drivers" subtitle="Who is on the road, free, or offline" :padded="false">
      <template #actions>
        <BaseButton size="sm" variant="ghost" class="lg:hidden" @click="filtersOpen = !filtersOpen">
          {{ filtersOpen ? 'Hide filters' : 'Filters' }}
        </BaseButton>
      </template>

      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div
          class="grid grid-cols-1 gap-3 lg:grid-cols-2"
          :class="filtersOpen ? 'grid' : 'hidden lg:grid'"
        >
          <BaseInput
            :modelValue="search"
            type="search"
            label="Search by name"
            placeholder="First or last name"
            @update:model-value="onSearchInput"
          />
        </div>

        <div class="mt-3 flex flex-wrap items-center gap-2">
          <span class="text-small font-medium text-content-muted dark:text-[#94A3B8]">Status</span>
          <button
            v-for="option in STATUS_FILTERS"
            :key="option.value || 'all'"
            type="button"
            class="rounded-full"
            :class="chipClass(status === option.value)"
            :aria-pressed="status === option.value"
            @click="selectStatus(option.value)"
          >
            {{ option.label }}
          </button>
          <BaseButton size="sm" variant="ghost" @click="clearFilters">Clear</BaseButton>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="deliveryStore.drivers"
        rowKey="id"
        :loading="loading"
        :error="error"
        emptyTitle="No drivers match these filters"
        emptyDescription="Try another status or clear the search to see the whole roster."
        emptyIcon="deliveries"
        emptyActionLabel="Clear filters"
        caption="Driver roster"
        @retry="load"
        @empty-action="clearFilters"
      >
        <template #cell-driver="{ row }">
          <div class="min-w-0">
            <p class="truncate font-medium text-content dark:text-[#F8FAFC]">{{ row.fullName }}</p>
            <p class="truncate text-small text-content-subtle dark:text-[#64748B]">
              Licence {{ row.licenseNumber }}
            </p>
          </div>
        </template>

        <template #cell-phone="{ row }">
          <a
            :href="`tel:${row.phone}`"
            class="text-body text-content-muted hover:text-primary dark:text-[#94A3B8]"
          >
            {{ row.phone }}
          </a>
        </template>

        <template #cell-status="{ row }">
          <StatusBadge :meta="driverStatus(row.status)" />
        </template>

        <template #cell-delivery="{ row }">
          <div v-if="row.currentDeliveryId" class="flex flex-col items-start gap-1">
            <span class="font-mono text-small text-content-muted dark:text-[#94A3B8]">
              {{ deliveryLabel(row.currentDeliveryId) }}
            </span>
            <StatusBadge
              v-if="row.currentDeliveryStatus"
              :meta="deliveryStatus(row.currentDeliveryStatus)"
            />
          </div>
          <span v-else class="text-body text-content-subtle dark:text-[#64748B]">—</span>
        </template>

        <template #cell-vehicle="{ row }">
          <span class="font-mono text-body text-content-muted dark:text-[#94A3B8]">
            {{ row.vehicleRegistration ?? '—' }}
          </span>
        </template>

        <template #cell-completed="{ row }">
          <span class="tabular-nums text-body text-content dark:text-[#F8FAFC]">
            {{ pluralize(row.completedDeliveries, 'delivery', 'deliveries') }}
          </span>
        </template>

        <template #cell-actions="{ row }">
          <BaseButton
            size="sm"
            variant="secondary"
            :aria-label="`Assign deliveries to ${row.fullName}`"
            @click="assignTo(row)"
          >
            Assign
          </BaseButton>
        </template>
      </DataTable>

      <TablePagination
        :page="page"
        :totalPages="totalPages"
        :totalElements="totalElements"
        :size="PAGE_SIZE"
        label="drivers"
        @change="goToPage"
      />
    </BaseCard>
  </div>
</template>