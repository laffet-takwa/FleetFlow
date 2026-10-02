import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'

import { inventoryApi, warehouseApi } from '@/services/inventoryApi'
import { ApiError } from '@/services/api'
import { useToastStore } from './toastStore'
import type { InventoryQuery, InventoryResponse, StockStatus, WarehouseResponse } from '@/types'

const PAGE_SIZE = 20

/** Inventory and warehouse state for the operations console. */
export const useInventoryStore = defineStore('inventory', () => {
  const toasts = useToastStore()

  const items = ref<InventoryResponse[]>([])
  const warehouses = ref<WarehouseResponse[]>([])
  const summary = ref({ inStock: 0, lowStock: 0, outOfStock: 0, totalProducts: 0 })
  const loading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const totalPages = ref(0)

  const query = reactive<Required<Pick<InventoryQuery, 'page' | 'size' | 'warehouseId' | 'search' | 'stockStatus' | 'category'>>>({
    page: 0,
    size: PAGE_SIZE,
    warehouseId: undefined as number | undefined,
    search: '',
    stockStatus: '' as StockStatus | '',
    category: '',
  })

  const categories = computed(() =>
    [...new Set(items.value.map((item) => item.category).filter(Boolean))].sort(),
  )

  const lowStockCount = computed(() => summary.value.lowStock + summary.value.outOfStock)

  async function fetchInventory(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const page = await inventoryApi.list({ ...query })
      items.value = page.content
      totalElements.value = page.totalElements
      totalPages.value = page.totalPages
    } catch (caught) {
      error.value =
        caught instanceof ApiError ? caught.message : 'Unable to load inventory right now.'
    } finally {
      loading.value = false
    }
  }

  async function fetchSummary(): Promise<void> {
    try {
      summary.value = await inventoryApi.summary()
    } catch {
      // The KPI strip degrades to zeros rather than blocking the page.
      summary.value = { inStock: 0, lowStock: 0, outOfStock: 0, totalProducts: 0 }
    }
  }

  async function fetchWarehouses(): Promise<void> {
    try {
      warehouses.value = await warehouseApi.list()
    } catch {
      warehouses.value = []
    }
  }

  async function adjust(id: number, quantityDelta: number, reason: string): Promise<boolean> {
    try {
      const updated = await inventoryApi.adjust(id, quantityDelta, reason)
      toasts.success('Inventory updated', `${updated.productName}: ${updated.availableQuantity} available`)
      await Promise.all([fetchInventory(), fetchSummary()])
      return true
    } catch (caught) {
      toasts.fromException('Unable to update inventory', caught)
      return false
    }
  }

  function resetFilters(): void {
    query.page = 0
    query.warehouseId = undefined
    query.search = ''
    query.stockStatus = ''
    query.category = ''
  }

  return {
    items,
    warehouses,
    summary,
    loading,
    error,
    totalElements,
    totalPages,
    query,
    categories,
    lowStockCount,
    fetchInventory,
    fetchSummary,
    fetchWarehouses,
    adjust,
    resetFilters,
  }
})