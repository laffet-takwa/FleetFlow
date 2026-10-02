import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'

import { orderApi } from '@/services/orderApi'
import { productApi } from '@/services/productApi'
import { ApiError } from '@/services/api'
import { useToastStore } from './toastStore'
import type { CreateOrderRequest, OrderQuery, OrderResponse, OrderStatus, ProductResponse } from '@/types'

const PAGE_SIZE = 15

/**
 * Order state for both the customer shop and the operations console.
 *
 * Filters and pagination live here rather than in the view so that a link, a refresh
 * or a second screen always asks for the same slice of data.
 */
export const useOrderStore = defineStore('orders', () => {
  const toasts = useToastStore()

  const orders = ref<OrderResponse[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const totalPages = ref(0)

  const query = reactive<Required<Pick<OrderQuery, 'page' | 'size' | 'status' | 'search' | 'from' | 'to'>>>({
    page: 0,
    size: PAGE_SIZE,
    status: '' as OrderStatus | '',
    search: '',
    from: '',
    to: '',
  })

  let inFlight: AbortController | null = null

  const isEmpty = computed(() => !loading.value && orders.value.length === 0)

  async function fetchOrders(): Promise<void> {
    inFlight?.abort()
    inFlight = new AbortController()
    loading.value = true
    error.value = null
    try {
      const page = await orderApi.list({ ...query }, inFlight.signal)
      orders.value = page.content
      totalElements.value = page.totalElements
      totalPages.value = page.totalPages
    } catch (caught) {
      if (caught instanceof DOMException && caught.name === 'AbortError') {
        return
      }
      error.value =
        caught instanceof ApiError ? caught.message : 'Unable to load orders right now.'
    } finally {
      loading.value = false
    }
  }

  function resetFilters(): void {
    query.page = 0
    query.status = ''
    query.search = ''
    query.from = ''
    query.to = ''
  }

  async function goToPage(page: number): Promise<void> {
    query.page = page
    await fetchOrders()
  }

  async function createOrder(payload: CreateOrderRequest): Promise<OrderResponse | null> {
    try {
      const created = await orderApi.create(payload)
      toasts.success(`Order #${created.id} created`, 'We are reserving your items now.')
      return created
    } catch (caught) {
      toasts.fromException('Unable to create the order', caught)
      return null
    }
  }

  async function cancelOrder(id: number, reason: string): Promise<boolean> {
    try {
      await orderApi.cancel(id, { reason })
      toasts.success(`Order #${id} cancelled`)
      await fetchOrders()
      return true
    } catch (caught) {
      toasts.fromException(`Unable to cancel order #${id}`, caught)
      return false
    }
  }

  async function advanceStatus(id: number, status: OrderStatus, note?: string): Promise<boolean> {
    try {
      await orderApi.updateStatus(id, status, note)
      toasts.success(`Order #${id} moved to ${status.toLowerCase().replace(/_/g, ' ')}`)
      await fetchOrders()
      return true
    } catch (caught) {
      toasts.fromException(`Unable to update order #${id}`, caught)
      return false
    }
  }

  return {
    orders,
    loading,
    error,
    totalElements,
    totalPages,
    query,
    isEmpty,
    fetchOrders,
    resetFilters,
    goToPage,
    createOrder,
    cancelOrder,
    advanceStatus,
  }
})

/** Catalogue state shared by the checkout steps and the product pickers. */
export const useCatalogStore = defineStore('catalog', () => {
  const products = ref<ProductResponse[]>([])
  const categories = ref<string[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const search = ref('')
  const category = ref('')
  const totalElements = ref(0)
  const totalPages = ref(0)
  const page = ref(0)
  const pageSize = 12

  async function fetchProducts(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await productApi.list({
        page: page.value,
        size: pageSize,
        search: search.value || undefined,
        category: category.value || undefined,
        active: true,
      })
      products.value = result.content
      totalElements.value = result.totalElements
      totalPages.value = result.totalPages
    } catch (caught) {
      error.value =
        caught instanceof ApiError ? caught.message : 'Unable to load the catalogue right now.'
    } finally {
      loading.value = false
    }
  }

  async function fetchCategories(): Promise<void> {
    try {
      categories.value = await productApi.categories()
    } catch {
      // The category filter is an optimisation; an empty list still allows browsing.
      categories.value = []
    }
  }

  function resetFilters(): void {
    search.value = ''
    category.value = ''
    page.value = 0
  }

  return {
    products,
    categories,
    loading,
    error,
    search,
    category,
    totalElements,
    totalPages,
    page,
    pageSize,
    fetchProducts,
    fetchCategories,
    resetFilters,
  }
})