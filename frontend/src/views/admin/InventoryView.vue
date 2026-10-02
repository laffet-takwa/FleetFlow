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
import { useDebouncedRef } from '@/stores/asyncStore'
import { useInventoryStore } from '@/stores/inventoryStore'
import { productApi } from '@/services/productApi'
import type { InventoryResponse, StockStatus } from '@/types'
import { formatNumber } from '@/utils/format'
import { stockStatus } from '@/utils/status'

/**
 * Stock levels across warehouses.
 *
 * The summary strip is deliberately plain counts rather than another set of tiles: the
 * question this screen answers is "what needs attention", and LOW_STOCK / OUT_OF_STOCK
 * have to be readable before the operator has scrolled anything.
 */

const inventoryStore = useInventoryStore()

const STOCK_OPTIONS: { value: StockStatus; label: string }[] = [
  { value: 'IN_STOCK', label: 'In stock' },
  { value: 'LOW_STOCK', label: 'Low stock' },
  { value: 'OUT_OF_STOCK', label: 'Out of stock' },
]

const search = ref('')
const { debounced: debouncedSearch, watchSource: queueSearch } = useDebouncedRef(() => search.value, 300)

/**
 * `inventoryStore.categories` is derived from the loaded page, so applying a category
 * would collapse the dropdown to that one value. The catalogue's own category endpoint is
 * the stable source; the store's list is kept as the fallback.
 */
const allCategories = ref<string[]>([])

const filtersOpen = ref(false)

const adjustTarget = ref<InventoryResponse | null>(null)
const adjustDelta = ref<number | null>(null)
const adjustReason = ref('')
const adjustError = ref('')
const adjusting = ref(false)

const warehouseOptions = computed(() =>
  inventoryStore.warehouses.map((warehouse) => ({
    value: warehouse.id,
    label: `${warehouse.name} · ${warehouse.city}`,
  })),
)

const categoryOptions = computed(() => {
  const source = allCategories.value.length > 0 ? allCategories.value : inventoryStore.categories
  return source.map((category) => ({ value: category, label: category }))
})

const summaryEntries = computed(() => [
  { status: 'IN_STOCK' as const, count: inventoryStore.summary.inStock },
  { status: 'LOW_STOCK' as const, count: inventoryStore.summary.lowStock },
  { status: 'OUT_OF_STOCK' as const, count: inventoryStore.summary.outOfStock },
])

const columns = [
  { key: 'sku', label: 'SKU' },
  { key: 'product', label: 'Product' },
  { key: 'warehouse', label: 'Warehouse' },
  { key: 'available', label: 'Available', align: 'right' as const },
  { key: 'reserved', label: 'Reserved', align: 'right' as const },
  { key: 'status', label: 'Status' },
  { key: 'actions', label: 'Actions', align: 'right' as const },
]

function refresh(): void {
  inventoryStore.query.page = 0
  void inventoryStore.fetchInventory()
}

function onSearchInput(value: string | number | null): void {
  search.value = String(value ?? '')
  queueSearch(search.value)
}

function onWarehouseChange(value: string | number | null): void {
  inventoryStore.query.warehouseId = value === null || value === '' ? undefined : Number(value)
  refresh()
}

function onStockStatusChange(value: string | number | null): void {
  inventoryStore.query.stockStatus = (value ?? '') as StockStatus | ''
  refresh()
}

function onCategoryChange(value: string | number | null): void {
  inventoryStore.query.category = String(value ?? '')
  refresh()
}

function clearFilters(): void {
  search.value = ''
  inventoryStore.resetFilters()
  void inventoryStore.fetchInventory()
}

function goToPage(next: number): void {
  inventoryStore.query.page = next
  void inventoryStore.fetchInventory()
}

function openAdjust(item: InventoryResponse): void {
  adjustTarget.value = item
  adjustDelta.value = null
  adjustReason.value = ''
  adjustError.value = ''
}

function closeAdjust(): void {
  adjustTarget.value = null
  adjustDelta.value = null
  adjustReason.value = ''
  adjustError.value = ''
}

function onDeltaInput(value: string | number | null): void {
  adjustDelta.value = value === null || value === '' ? null : Number(value)
}

function onReasonInput(value: string | number | null): void {
  adjustReason.value = String(value ?? '')
}

async function confirmAdjust(): Promise<void> {
  const target = adjustTarget.value
  if (!target) {
    return
  }
  const delta = adjustDelta.value
  if (delta === null || Number.isNaN(delta) || delta === 0) {
    adjustError.value = 'Enter a non-zero adjustment, for example 12 to add or -3 to remove.'
    return
  }
  if (!adjustReason.value.trim()) {
    adjustError.value = 'A reason is required so the movement is auditable.'
    return
  }
  adjusting.value = true
  const done = await inventoryStore.adjust(target.id, delta, adjustReason.value.trim())
  adjusting.value = false
  if (done) {
    closeAdjust()
    return
  }
  adjustError.value = 'The adjustment was refused. Check the quantity and try again.'
}

async function loadCategories(): Promise<void> {
  try {
    allCategories.value = await productApi.categories()
  } catch {
    allCategories.value = []
  }
}

watch(debouncedSearch, (value) => {
  const term = value.trim()
  if (term === inventoryStore.query.search) {
    return
  }
  inventoryStore.query.search = term
  refresh()
})

onMounted(() => {
  void inventoryStore.fetchWarehouses()
  void inventoryStore.fetchSummary()
  void loadCategories()
  void inventoryStore.fetchInventory()
})
</script>

<template>
  <div class="space-y-4">
    <BaseCard :padded="false">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-2 border-b border-edge px-5 py-3.5 dark:border-[#334155]">
        <p class="text-small font-medium text-content-muted dark:text-[#94A3B8]">Across all warehouses</p>
        <StatusBadge
          v-for="entry in summaryEntries"
          :key="entry.status"
          :meta="stockStatus(entry.status)"
          :suffix="formatNumber(entry.count)"
        />
        <span class="text-small text-content-subtle dark:text-[#64748B]">
          {{ formatNumber(inventoryStore.summary.totalProducts) }} products tracked
        </span>
      </div>

      <div class="border-b border-edge px-5 py-4 dark:border-[#334155]">
        <div class="flex items-center justify-between gap-3">
          <h2 class="card-title">Stock levels</h2>
          <BaseButton size="sm" variant="ghost" class="lg:hidden" @click="filtersOpen = !filtersOpen">
            {{ filtersOpen ? 'Hide filters' : 'Filters' }}
          </BaseButton>
        </div>

        <div
          class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4"
          :class="filtersOpen ? 'grid' : 'hidden lg:grid'"
        >
          <BaseInput
            :modelValue="search"
            type="search"
            label="Search"
            placeholder="SKU or product name"
            @update:model-value="onSearchInput"
          />
          <BaseSelect
            :modelValue="inventoryStore.query.warehouseId ?? null"
            label="Warehouse"
            :options="warehouseOptions"
            clearable
            @update:model-value="onWarehouseChange"
          />
          <BaseSelect
            :modelValue="inventoryStore.query.stockStatus"
            label="Stock status"
            :options="STOCK_OPTIONS"
            clearable
            @update:model-value="onStockStatusChange"
          />
          <BaseSelect
            :modelValue="inventoryStore.query.category"
            label="Category"
            :options="categoryOptions"
            clearable
            @update:model-value="onCategoryChange"
          />
        </div>

        <div class="mt-3">
          <BaseButton size="sm" variant="ghost" @click="clearFilters">Clear filters</BaseButton>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="inventoryStore.items"
        rowKey="id"
        :loading="inventoryStore.loading"
        :error="inventoryStore.error"
        emptyTitle="No stock records match these filters"
        emptyDescription="Pick another warehouse or clear the filters to see the full register."
        emptyIcon="inventory"
        emptyActionLabel="Clear filters"
        caption="Stock levels by warehouse"
        @retry="inventoryStore.fetchInventory()"
        @empty-action="clearFilters"
      >
        <template #cell-sku="{ row }">
          <span class="font-mono text-small text-content-muted dark:text-[#94A3B8]">{{ row.productSku }}</span>
        </template>

        <template #cell-product="{ row }">
          <div class="min-w-0">
            <p class="truncate font-medium text-content dark:text-[#F8FAFC]">{{ row.productName }}</p>
            <p class="truncate text-small text-content-subtle dark:text-[#64748B]">{{ row.category }}</p>
          </div>
        </template>

        <template #cell-warehouse="{ row }">
          <span class="whitespace-nowrap text-body text-content-muted dark:text-[#94A3B8]">
            {{ row.warehouseName }}
          </span>
        </template>

        <template #cell-available="{ row }">
          <span
            class="font-medium tabular-nums"
            :class="
              row.stockStatus === 'OUT_OF_STOCK'
                ? 'text-danger'
                : row.stockStatus === 'LOW_STOCK'
                  ? 'text-amber-700 dark:text-amber-300'
                  : 'text-content dark:text-[#F8FAFC]'
            "
          >
            {{ formatNumber(row.availableQuantity) }}
          </span>
        </template>

        <template #cell-reserved="{ row }">
          <span class="tabular-nums text-content-muted dark:text-[#94A3B8]">
            {{ formatNumber(row.reservedQuantity) }}
          </span>
        </template>

        <template #cell-status="{ row }">
          <StatusBadge :meta="stockStatus(row.stockStatus)" />
        </template>

        <template #cell-actions="{ row }">
          <BaseButton
            size="sm"
            variant="secondary"
            :aria-label="`Adjust stock for ${row.productName}`"
            @click="openAdjust(row)"
          >
            Adjust
          </BaseButton>
        </template>
      </DataTable>

      <TablePagination
        :page="inventoryStore.query.page"
        :totalPages="inventoryStore.totalPages"
        :totalElements="inventoryStore.totalElements"
        :size="inventoryStore.query.size"
        label="stock records"
        @change="goToPage"
      />
    </BaseCard>

    <ConfirmModal
      :open="adjustTarget !== null"
      tone="primary"
      title="Adjust stock"
      :description="
        adjustTarget === null
          ? ''
          : `${adjustTarget.productName} — ${adjustTarget.warehouseName} currently holds ${adjustTarget.availableQuantity} available.`
      "
      confirmLabel="Apply adjustment"
      cancelLabel="Discard"
      :busy="adjusting"
      @cancel="closeAdjust"
      @confirm="confirmAdjust"
    >
      <div class="mt-3 space-y-3">
        <BaseInput
          :modelValue="adjustDelta"
          type="number"
          label="Signed adjustment"
          placeholder="12 to add, -3 to remove"
          required
          @update:model-value="onDeltaInput"
        />
        <BaseInput
          :modelValue="adjustReason"
          label="Reason"
          placeholder="Stock count correction, damaged goods, restock…"
          required
          @update:model-value="onReasonInput"
        />
        <p v-if="adjustError" class="text-small text-danger" role="alert">{{ adjustError }}</p>
      </div>
    </ConfirmModal>
  </div>
</template>