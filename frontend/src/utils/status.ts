import type {
  DeliveryStatus,
  DriverStatus,
  NotificationLevel,
  OrderStatus,
  StockStatus,
  VehicleStatus,
} from '@/types'

/**
 * One vocabulary for status across the whole application.
 *
 * A status is never rendered as a bare enum constant: every entry carries a human
 * label, a tone and a short glyph, so `OUT_FOR_DELIVERY` is shown as
 * "Out for delivery" with a visible marker beside it. Colour alone never carries the
 * meaning, which is both an accessibility requirement and what keeps the tables
 * readable.
 */

export type StatusTone = 'neutral' | 'info' | 'progress' | 'success' | 'warning' | 'danger'

export interface StatusMeta {
  label: string
  tone: StatusTone
  /** Text glyph, kept monochrome: it reinforces the label instead of replacing it. */
  glyph: string
}

const TONE_CLASS: Record<StatusTone, string> = {
  neutral: 'bg-slate-100 text-slate-700 ring-slate-200 dark:bg-slate-800 dark:text-slate-200 dark:ring-slate-700',
  info: 'bg-sky-50 text-sky-700 ring-sky-200 dark:bg-sky-950 dark:text-sky-300 dark:ring-sky-800',
  progress:
    'bg-blue-50 text-blue-700 ring-blue-200 dark:bg-blue-950 dark:text-blue-300 dark:ring-blue-800',
  success:
    'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-950 dark:text-emerald-300 dark:ring-emerald-800',
  warning:
    'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-950 dark:text-amber-300 dark:ring-amber-800',
  danger:
    'bg-red-50 text-red-700 ring-red-200 dark:bg-red-950 dark:text-red-300 dark:ring-red-800',
}

export function toneClasses(tone: StatusTone): string {
  return TONE_CLASS[tone]
}

const ORDER_STATUS: Record<OrderStatus, StatusMeta> = {
  CREATED: { label: 'Created', tone: 'neutral', glyph: '○' },
  CONFIRMED: { label: 'Confirmed', tone: 'info', glyph: '◔' },
  PROCESSING: { label: 'Processing', tone: 'progress', glyph: '◑' },
  READY_FOR_DELIVERY: { label: 'Ready for delivery', tone: 'progress', glyph: '◕' },
  OUT_FOR_DELIVERY: { label: 'Out for delivery', tone: 'info', glyph: '●' },
  DELIVERED: { label: 'Delivered', tone: 'success', glyph: '✓' },
  CANCELLED: { label: 'Cancelled', tone: 'danger', glyph: '✕' },
}

const DELIVERY_STATUS: Record<DeliveryStatus, StatusMeta> = {
  CREATED: { label: 'Awaiting dispatch', tone: 'neutral', glyph: '○' },
  ASSIGNED: { label: 'Assigned', tone: 'info', glyph: '◔' },
  PICKED_UP: { label: 'Picked up', tone: 'progress', glyph: '◕' },
  IN_TRANSIT: { label: 'In transit', tone: 'info', glyph: '●' },
  DELIVERED: { label: 'Delivered', tone: 'success', glyph: '✓' },
  FAILED: { label: 'Failed', tone: 'danger', glyph: '✕' },
  CANCELLED: { label: 'Cancelled', tone: 'danger', glyph: '⊘' },
}

const DRIVER_STATUS: Record<DriverStatus, StatusMeta> = {
  AVAILABLE: { label: 'Available', tone: 'success', glyph: '●' },
  ON_DELIVERY: { label: 'On delivery', tone: 'info', glyph: '◕' },
  OFFLINE: { label: 'Offline', tone: 'neutral', glyph: '○' },
}

const VEHICLE_STATUS: Record<VehicleStatus, StatusMeta> = {
  AVAILABLE: { label: 'Available', tone: 'success', glyph: '●' },
  IN_USE: { label: 'In use', tone: 'info', glyph: '◕' },
  MAINTENANCE: { label: 'Maintenance', tone: 'warning', glyph: '⚙' },
}

const STOCK_STATUS: Record<StockStatus, StatusMeta> = {
  IN_STOCK: { label: 'In stock', tone: 'success', glyph: '●' },
  LOW_STOCK: { label: 'Low stock', tone: 'warning', glyph: '▲' },
  OUT_OF_STOCK: { label: 'Out of stock', tone: 'danger', glyph: '✕' },
}

const LEVEL: Record<NotificationLevel, StatusMeta> = {
  INFO: { label: 'Info', tone: 'info', glyph: 'ⓘ' },
  SUCCESS: { label: 'Success', tone: 'success', glyph: '✓' },
  WARNING: { label: 'Warning', tone: 'warning', glyph: '▲' },
  ERROR: { label: 'Error', tone: 'danger', glyph: '✕' },
}

export function orderStatus(status: OrderStatus): StatusMeta {
  return ORDER_STATUS[status] ?? { label: status, tone: 'neutral', glyph: '•' }
}

export function deliveryStatus(status: DeliveryStatus): StatusMeta {
  return DELIVERY_STATUS[status] ?? { label: status, tone: 'neutral', glyph: '•' }
}

export function driverStatus(status: DriverStatus): StatusMeta {
  return DRIVER_STATUS[status] ?? { label: status, tone: 'neutral', glyph: '•' }
}

export function vehicleStatus(status: VehicleStatus): StatusMeta {
  return VEHICLE_STATUS[status] ?? { label: status, tone: 'neutral', glyph: '•' }
}

export function stockStatus(status: StockStatus | undefined): StatusMeta {
  return (status ? STOCK_STATUS[status] : undefined) ?? {
    label: 'Unknown',
    tone: 'neutral',
    glyph: '•',
  }
}

export function notificationLevel(level: NotificationLevel): StatusMeta {
  return LEVEL[level] ?? { label: level, tone: 'neutral', glyph: '•' }
}

/** Order states a customer may still cancel, mirroring the backend rule. */
export function isCancellable(status: OrderStatus): boolean {
  return status === 'CREATED' || status === 'CONFIRMED'
}

/** Delivery states a driver may still move forward, mirroring the backend rule. */
export function nextDriverActions(status: DeliveryStatus): { status: DeliveryStatus; label: string }[] {
  switch (status) {
    case 'ASSIGNED':
      return [{ status: 'PICKED_UP', label: 'Mark picked up' }]
    case 'PICKED_UP':
      return [{ status: 'IN_TRANSIT', label: 'Start delivery' }]
    case 'IN_TRANSIT':
      return [{ status: 'DELIVERED', label: 'Mark as delivered' }]
    default:
      return []
  }
}