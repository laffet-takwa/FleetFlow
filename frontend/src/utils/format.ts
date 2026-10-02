/**
 * Formatting helpers.
 *
 * Money is handled as a number on the wire and formatted once, here, so the same
 * amount never renders two different ways on two screens.
 */

const CURRENCY_LOCALE = 'en-TN'

export function formatMoney(amount: number | string | null | undefined, currency = 'TND'): string {
  const value = typeof amount === 'string' ? Number(amount) : amount
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  // TND is conventionally written with three decimals; other currencies use two.
  const digits = currency.toUpperCase() === 'TND' ? 3 : 2
  return `${value.toLocaleString(CURRENCY_LOCALE, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })} ${currency}`
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return value.toLocaleString(CURRENCY_LOCALE)
}

export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleDateString(CURRENCY_LOCALE, { day: '2-digit', month: 'short', year: 'numeric' })
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleString(CURRENCY_LOCALE, {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      })
}

export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString(CURRENCY_LOCALE, { hour: '2-digit', minute: '2-digit' })
}

/** Relative time for notification lists, falling back to an absolute date past a week. */
export function formatRelative(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  const timestamp = new Date(value).getTime()
  if (Number.isNaN(timestamp)) {
    return '—'
  }

  const seconds = Math.round((Date.now() - timestamp) / 1000)
  if (seconds < 45) {
    return 'just now'
  }

  const units: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, 'second'],
    [3600, 'minute'],
    [86400, 'hour'],
    [604800, 'day'],
  ]

  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  if (seconds < 3600) {
    return formatter.format(-Math.round(seconds / 60), 'minute')
  }
  if (seconds < 86400) {
    return formatter.format(-Math.round(seconds / 3600), 'hour')
  }
  if (seconds < 604800) {
    return formatter.format(-Math.round(seconds / 86400), 'day')
  }
  return formatDate(value)
}

/** `2026-10-02` for date inputs; returns '' when the value is unusable. */
export function toDateInput(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toISOString().slice(0, 10)
}

export function pluralize(count: number, singular: string, plural?: string): string {
  return `${count} ${count === 1 ? singular : (plural ?? `${singular}s`)}`
}

export function initialsOf(name: string | null | undefined): string {
  if (!name) {
    return '?'
  }
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('')
}

/** `delivery:1024` style labels used throughout the operations screens. */
export function orderLabel(id: number): string {
  return `#${id}`
}

export function deliveryLabel(id: number | null | undefined): string {
  return id === null || id === undefined ? '—' : `#${id}`
}

/** Distance in kilometres between two WGS84 points, used for the live ETA readout. */
export function distanceKm(
  from: { latitude: number; longitude: number },
  to: { latitude: number; longitude: number },
): number {
  const EARTH_RADIUS_KM = 6371
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180
  const dLat = toRadians(to.latitude - from.latitude)
  const dLon = toRadians(to.longitude - from.longitude)
  const lat1 = toRadians(from.latitude)
  const lat2 = toRadians(to.latitude)

  const a =
    Math.sin(dLat / 2) ** 2 + Math.sin(dLon / 2) ** 2 * Math.cos(lat1) * Math.cos(lat2)
  return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a))
}