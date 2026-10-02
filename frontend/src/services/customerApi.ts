import { get, put } from './api'
import type { CustomerResponse, PageResponse, UpdateProfileRequest } from '@/types'

export const customerApi = {
  me: (signal?: AbortSignal) => get<CustomerResponse>('/customers/me', undefined, { signal }),

  updateMe: (body: UpdateProfileRequest) => put<CustomerResponse>('/customers/me', body),

  list: (query: { search?: string; page?: number; size?: number }, signal?: AbortSignal) =>
    get<PageResponse<CustomerResponse>>('/customers', query, { signal }),

  get: (id: number, signal?: AbortSignal) =>
    get<CustomerResponse>(`/customers/${id}`, undefined, { signal }),
}