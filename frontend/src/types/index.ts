/**
 * Types mirroring the DTOs exposed by the FleetFlow services.
 *
 * These are hand maintained rather than generated because the gateway aggregates eight
 * services; if a contract drifts, `npm run typecheck` on the consuming screen is the
 * first place it shows up.
 */

// ------------------------------------------------------------------ shared

export type Role = 'ADMIN' | 'OPERATIONS' | 'DRIVER' | 'CUSTOMER'

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

export interface ApiErrorBody {
  timestamp?: string
  status?: number
  error?: string
  message?: string
  path?: string
  correlationId?: string
  violations?: { field: string; message: string }[]
}

// -------------------------------------------------------------------- auth

export interface UserResponse {
  id: number
  firstName: string
  lastName: string
  email: string
  phone: string
  role: Role
  enabled: boolean
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresIn: number
  user: UserResponse
}

export interface RegisterRequest {
  firstName: string
  lastName: string
  email: string
  phone: string
  password: string
  address?: string
}

// ---------------------------------------------------------------- customer

export interface CustomerResponse {
  id: number
  userId: number
  firstName: string
  lastName: string
  email: string
  phone: string
  address?: string
  city?: string
  postalCode?: string
  createdAt: string
  updatedAt: string
}

export interface UpdateProfileRequest {
  firstName: string
  lastName: string
  phone: string
  address?: string
  city?: string
  postalCode?: string
}

// -------------------------------------------------------------- warehouse

export type StockStatus = 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK'

export interface ProductResponse {
  id: number
  sku: string
  name: string
  description?: string
  category: string
  price: number
  active: boolean
  availableQuantity?: number
  reservedQuantity?: number
  stockStatus?: StockStatus
}

export interface InventoryResponse {
  id: number
  warehouseId: number
  warehouseName: string
  productId: number
  productSku: string
  productName: string
  category: string
  availableQuantity: number
  reservedQuantity: number
  stockStatus: StockStatus
  updatedAt: string
}

export interface WarehouseResponse {
  id: number
  name: string
  address: string
  city: string
  capacity: number
  status: string
  distinctProductCount: number
}

export interface StockSummary {
  inStock: number
  lowStock: number
  outOfStock: number
  totalProducts: number
}

export interface CreateWarehouseRequest {
  name: string
  address: string
  city: string
  capacity: number
}

export interface UpdateWarehouseRequest extends CreateWarehouseRequest {
  status?: 'ACTIVE' | 'INACTIVE'
}

// ------------------------------------------------------------------ order

export type OrderStatus =
  | 'CREATED'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'READY_FOR_DELIVERY'
  | 'OUT_FOR_DELIVERY'
  | 'DELIVERED'
  | 'CANCELLED'

export interface OrderItemResponse {
  id: number
  productId: number
  productName: string
  quantity: number
  unitPrice: number
  lineSubtotal: number
}

export interface OrderTimelineEntry {
  status: OrderStatus
  previousStatus?: OrderStatus
  source: 'CUSTOMER' | 'OPERATIONS' | 'SYSTEM'
  note?: string
  changedAt: string
}

export interface OrderResponse {
  id: number
  customerId: number
  status: OrderStatus
  subtotal: number
  deliveryFee: number
  totalAmount: number
  currency: string
  itemCount: number
  deliveryAddress: string
  city: string
  postalCode: string
  deliveryId?: number
  cancelledReason?: string
  createdAt: string
  updatedAt: string
  items: OrderItemResponse[]
  timeline: OrderTimelineEntry[]
}

export interface CreateOrderRequest {
  items: { productId: number; quantity: number }[]
  deliveryAddress: string
  city: string
  postalCode: string
}

export interface CancelOrderRequest {
  reason?: string
}

export interface OrderKpiResponse {
  totalOrders: number
  activeOrders: number
  deliveredOrders: number
  pendingOrders: number
  cancelledOrders: number
}

export interface DailyOrderCount {
  date: string
  orders: number
  delivered: number
  cancelled: number
}

// ---------------------------------------------------------------- delivery

export type DriverStatus = 'AVAILABLE' | 'ON_DELIVERY' | 'OFFLINE'
export type VehicleStatus = 'AVAILABLE' | 'IN_USE' | 'MAINTENANCE'
export type VehicleType = 'VAN' | 'MOTORCYCLE' | 'TRUCK' | 'CAR'

export type DeliveryStatus =
  | 'CREATED'
  | 'ASSIGNED'
  | 'PICKED_UP'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED'

export interface DriverResponse {
  id: number
  userId: number
  fullName: string
  licenseNumber: string
  phone: string
  status: DriverStatus
  currentDeliveryId?: number
  currentDeliveryStatus?: DeliveryStatus
  completedDeliveries: number
  vehicleRegistration?: string
}

export interface VehicleResponse {
  id: number
  registrationNumber: string
  type: VehicleType
  capacity: number
  status: VehicleStatus
  driverId?: number
  driverName?: string
  currentDeliveryId?: number
}

export interface DeliveryResponse {
  id: number
  orderId: number
  customerId: number
  driverId?: number
  driverUserId?: number
  driverName?: string
  vehicleId?: number
  vehicleRegistration?: string
  vehicleType?: VehicleType
  status: DeliveryStatus
  pickupAddress: string
  deliveryAddress: string
  city: string
  postalCode: string
  customerName?: string
  customerPhone?: string
  failureReason?: string
  proofOfDelivery?: string
  scheduledAt?: string
  startedAt?: string
  completedAt?: string
  createdAt: string
  updatedAt: string
}

export interface DeliveryKpiResponse {
  activeDeliveries: number
  availableDrivers: number
  busyDrivers: number
  availableVehicles: number
  completedToday: number
  failedDeliveries: number
}

// ---------------------------------------------------------------- tracking

export interface LocationResponse {
  deliveryId: number
  driverId: number
  customerId: number
  latitude: number
  longitude: number
  speedKph?: number
  heading?: number
  recordedAt: string
  receivedAt: string
}

export interface LocationUpdateRequest {
  deliveryId: number
  latitude: number
  longitude: number
  speedKph?: number
  heading?: number
  recordedAt?: string
}

export interface TrackedDeliveryResponse {
  deliveryId: number
  orderId: number
  customerId: number
  driverId: number
  driverName?: string
  status: DeliveryStatus
  destination?: string
  city?: string
  latestLocation?: LocationResponse
  locationCount: number
  online: boolean
  lastLocationAt?: string
}

export interface TrackingHistoryResponse {
  deliveryId: number
  count: number
  locations: LocationResponse[]
}

// ----------------------------------------------------------- notification

export type NotificationLevel = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'

export interface NotificationResponse {
  id: number
  type: string
  title: string
  message: string
  orderId?: number
  deliveryId?: number
  level: NotificationLevel
  read: boolean
  createdAt: string
}

// ----------------------------------------------------------------- shared

export interface PagedQuery {
  page?: number
  size?: number
}

export interface OrderQuery extends PagedQuery {
  status?: OrderStatus | ''
  search?: string
  from?: string
  to?: string
  sort?: string
}

export interface DeliveryQuery extends PagedQuery {
  status?: DeliveryStatus | ''
  driverId?: number
  vehicleId?: number
  from?: string
  to?: string
}

export interface InventoryQuery extends PagedQuery {
  warehouseId?: number
  search?: string
  stockStatus?: StockStatus | ''
  category?: string
}

export interface DriverQuery extends PagedQuery {
  status?: DriverStatus | ''
  search?: string
}

export interface VehicleQuery extends PagedQuery {
  status?: VehicleStatus | ''
  type?: VehicleType | ''
  search?: string
}

export interface UpdateVehicleRequest {
  registrationNumber: string
  type: VehicleType
  capacity: number
  status: VehicleStatus
}

export interface UpdateDeliveryStatusRequest {
  status: DeliveryStatus
  reason?: string
}

export interface AssignDeliveryRequest {
  driverId: number
  vehicleId: number
}

export interface ProductQuery extends PagedQuery {
  search?: string
  category?: string
  active?: boolean
}