import { get } from './api'
import type { PageResponse, ProductQuery, ProductResponse } from '@/types'

export const productApi = {
  list: (query: ProductQuery, signal?: AbortSignal) =>
    get<PageResponse<ProductResponse>>('/products', query as Record<string, unknown>, { signal }),

  get: (id: number, signal?: AbortSignal) => get<ProductResponse>(`/products/${id}`, undefined, { signal }),

  categories: (signal?: AbortSignal) => get<string[]>('/products/categories', undefined, { signal }),
}