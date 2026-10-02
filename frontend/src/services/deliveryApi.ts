import { get, post, put } from './api'
import type {
  DeliveryKpiResponse,
  DeliveryQuery,
  DeliveryResponse,
  DeliveryStatus,
  DriverQuery,
  DriverResponse,
  DriverStatus,
  PageResponse,
  UpdateVehicleRequest,
  VehicleQuery,
  VehicleResponse,
} from '@/types'

export const deliveryApi = {
  list: (query: DeliveryQuery, signal?: AbortSignal) =>
    get<PageResponse<DeliveryResponse>>('/deliveries', query as Record<string, unknown>, { signal }),

  active: (signal?: AbortSignal) => get<DeliveryResponse[]>('/deliveries/active', undefined, { signal }),

  get: (id: number, signal?: AbortSignal) =>
    get<DeliveryResponse>(`/deliveries/${id}`, undefined, { signal }),

  mine: (signal?: AbortSignal) => get<DeliveryResponse[]>('/deliveries/mine', undefined, { signal }),

  assign: (id: number, driverId: number, vehicleId: number) =>
    post<DeliveryResponse>(`/deliveries/${id}/assign`, { driverId, vehicleId }),

  changeStatus: (id: number, status: DeliveryStatus, reason?: string) =>
    post<DeliveryResponse>(`/deliveries/${id}/status`, { status, reason }),

  cancel: (id: number, reason: string) =>
    post<DeliveryResponse>(`/deliveries/${id}/cancel`, { status: 'CANCELLED', reason }),

  kpi: (signal?: AbortSignal) => get<DeliveryKpiResponse>('/deliveries/kpi', undefined, { signal }),
}

export const driverApi = {
  list: (query: DriverQuery, signal?: AbortSignal) =>
    get<PageResponse<DriverResponse>>('/drivers', query as Record<string, unknown>, { signal }),

  me: (signal?: AbortSignal) => get<DriverResponse>('/drivers/me', undefined, { signal }),

  get: (id: number, signal?: AbortSignal) =>
    get<DriverResponse>(`/drivers/${id}`, undefined, { signal }),

  setOwnStatus: (status: DriverStatus) =>
    put<DriverResponse>('/drivers/me/status', { status }),
}

export const vehicleApi = {
  list: (query: VehicleQuery, signal?: AbortSignal) =>
    get<PageResponse<VehicleResponse>>('/vehicles', query as Record<string, unknown>, { signal }),

  create: (body: UpdateVehicleRequest) => post<VehicleResponse>('/vehicles', body),

  update: (id: number, body: UpdateVehicleRequest) => put<VehicleResponse>(`/vehicles/${id}`, body),
}