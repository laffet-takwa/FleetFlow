import { get, post, put } from './api'
import type {
  CreateWarehouseRequest,
  InventoryQuery,
  InventoryResponse,
  PageResponse,
  StockSummary,
  UpdateWarehouseRequest,
  WarehouseResponse,
} from '@/types'

export const inventoryApi = {
  list: (query: InventoryQuery, signal?: AbortSignal) =>
    get<PageResponse<InventoryResponse>>('/inventory', query as Record<string, unknown>, { signal }),

  adjust: (id: number, quantityDelta: number, reason: string) =>
    put<InventoryResponse>(`/inventory/${id}`, { quantityDelta, reason }),

  lowStock: (signal?: AbortSignal) => get<InventoryResponse[]>('/inventory/low-stock', undefined, { signal }),

  summary: (signal?: AbortSignal) => get<StockSummary>('/inventory/summary', undefined, { signal }),
}

export const warehouseApi = {
  list: (signal?: AbortSignal) => get<WarehouseResponse[]>('/warehouses', undefined, { signal }),

  create: (body: CreateWarehouseRequest) => post<WarehouseResponse>('/warehouses', body),

  update: (id: number, body: UpdateWarehouseRequest) =>
    put<WarehouseResponse>(`/warehouses/${id}`, body),
}