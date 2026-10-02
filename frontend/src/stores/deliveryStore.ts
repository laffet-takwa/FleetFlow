import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'

import { deliveryApi, driverApi, vehicleApi } from '@/services/deliveryApi'
import { ApiError } from '@/services/api'
import { useToastStore } from './toastStore'
import type {
  DeliveryQuery,
  DeliveryResponse,
  DeliveryStatus,
  DriverQuery,
  DriverResponse,
  DriverStatus,
  VehicleResponse,
} from '@/types'

const PAGE_SIZE = 20

/** Deliveries, drivers and vehicles for the operations console and the driver app. */
export const useDeliveryStore = defineStore('delivery', () => {
  const toasts = useToastStore()

  const deliveries = ref<DeliveryResponse[]>([])
  const activeDeliveries = ref<DeliveryResponse[]>([])
  const drivers = ref<DriverResponse[]>([])
  const vehicles = ref<VehicleResponse[]>([])
  const myDeliveries = ref<DeliveryResponse[]>([])

  const loading = ref(false)
  const error = ref<string | null>(null)
  const totalElements = ref(0)
  const totalPages = ref(0)

  const query = reactive<
    Required<Pick<DeliveryQuery, 'page' | 'size' | 'status' | 'driverId' | 'vehicleId' | 'from' | 'to'>>
  >({
    page: 0,
    size: PAGE_SIZE,
    status: '' as DeliveryStatus | '',
    driverId: undefined,
    vehicleId: undefined,
    from: '',
    to: '',
  })

  /** Drivers that can legally be assigned right now; the assign dialog only offers these. */
  const assignableDrivers = computed(() => drivers.value.filter((driver) => driver.status === 'AVAILABLE'))
  const assignableVehicles = computed(() => vehicles.value.filter((vehicle) => vehicle.status === 'AVAILABLE'))

  async function fetchDeliveries(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const page = await deliveryApi.list({ ...query })
      deliveries.value = page.content
      totalElements.value = page.totalElements
      totalPages.value = page.totalPages
    } catch (caught) {
      error.value =
        caught instanceof ApiError ? caught.message : 'Unable to load deliveries right now.'
    } finally {
      loading.value = false
    }
  }

  async function fetchActive(): Promise<void> {
    try {
      activeDeliveries.value = await deliveryApi.active()
    } catch {
      activeDeliveries.value = []
    }
  }

  async function fetchDrivers(query_: DriverQuery = { size: 100 }): Promise<void> {
    try {
      const page = await driverApi.list(query_)
      drivers.value = page.content
    } catch {
      drivers.value = []
    }
  }

  async function fetchVehicles(): Promise<void> {
    try {
      const page = await vehicleApi.list({ size: 100 })
      vehicles.value = page.content
    } catch {
      vehicles.value = []
    }
  }

  async function fetchMyDeliveries(): Promise<void> {
    try {
      myDeliveries.value = await deliveryApi.mine()
    } catch {
      myDeliveries.value = []
    }
  }

  async function assign(deliveryId: number, driverId: number, vehicleId: number): Promise<boolean> {
    try {
      await deliveryApi.assign(deliveryId, driverId, vehicleId)
      toasts.success('Driver and vehicle assigned', 'The driver has been notified.')
      await Promise.all([fetchDeliveries(), fetchActive(), fetchDrivers(), fetchVehicles()])
      return true
    } catch (caught) {
      toasts.fromException('Unable to assign the delivery', caught)
      return false
    }
  }

  async function changeStatus(deliveryId: number, status: DeliveryStatus, reason?: string): Promise<boolean> {
    try {
      await deliveryApi.changeStatus(deliveryId, status, reason)
      toasts.success('Delivery updated', `Status is now ${status.toLowerCase().replace(/_/g, ' ')}.`)
      await Promise.all([fetchDeliveries(), fetchActive()])
      return true
    } catch (caught) {
      toasts.fromException('Unable to update the delivery', caught)
      return false
    }
  }

  async function cancel(deliveryId: number, reason: string): Promise<boolean> {
    try {
      await deliveryApi.cancel(deliveryId, reason)
      toasts.success('Delivery cancelled')
      await Promise.all([fetchDeliveries(), fetchActive()])
      return true
    } catch (caught) {
      toasts.fromException('Unable to cancel the delivery', caught)
      return false
    }
  }

  async function setDriverStatus(status: DriverStatus): Promise<boolean> {
    try {
      const updated = await driverApi.setOwnStatus(status)
      toasts.success(`You are now ${updated.status.toLowerCase().replace(/_/g, ' ')}`)
      await fetchMyDeliveries()
      return true
    } catch (caught) {
      toasts.fromException('Unable to change your status', caught)
      return false
    }
  }

  function resetFilters(): void {
    query.page = 0
    query.status = ''
    query.driverId = undefined
    query.vehicleId = undefined
    query.from = ''
    query.to = ''
  }

  return {
    deliveries,
    activeDeliveries,
    drivers,
    vehicles,
    myDeliveries,
    loading,
    error,
    totalElements,
    totalPages,
    query,
    assignableDrivers,
    assignableVehicles,
    fetchDeliveries,
    fetchActive,
    fetchDrivers,
    fetchVehicles,
    fetchMyDeliveries,
    assign,
    changeStatus,
    cancel,
    setDriverStatus,
    resetFilters,
  }
})