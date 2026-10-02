<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

import type { LocationResponse } from '@/types'

/**
 * Live delivery map.
 *
 * Leaflet with OpenStreetMap tiles: no API key, nothing to configure, and it works
 * offline against a local tile cache if the demo has no connection.
 *
 * Markers are built as `divIcon`s rather than image markers. Leaflet's default icon
 * resolves its images relative to the CSS, which breaks under bundlers, and a DOM icon
 * also lets the driver marker be visually distinct from the destination without two
 * sprite sheets.
 */
const props = withDefaults(
  defineProps<{
    /** Newest first is not required; only the newest point drives the driver marker. */
    locations: LocationResponse[]
    destination?: { latitude: number; longitude: number; label?: string } | null
    /** Follow the driver marker as new positions arrive. */
    follow?: boolean
    height?: string
    /** Rendered while the map has no position to centre on yet. */
    loading?: boolean
  }>(),
  {
    destination: null,
    follow: true,
    height: '360px',
    loading: false,
  },
)

const emit = defineEmits<{ ready: [] }>()

const container = ref<HTMLElement | null>(null)
const map = shallowRef<L.Map | null>(null)

let driverMarker: L.Marker | null = null
let destinationMarker: L.Marker | null = null
let routeLine: L.Polyline | null = null

/** Tunis is the demo geography; used only before the first position arrives. */
const DEFAULT_CENTER: [number, number] = [36.8065, 10.1815]

function driverIcon(): L.DivIcon {
  return L.divIcon({
    className: 'fleetflow-marker-wrapper',
    html: `
      <span class="fleetflow-marker fleetflow-marker--driver" role="img" aria-label="Driver position">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M3 8h11v8H3zM14 11h3.5l3 3v2h-6.5z" stroke-linejoin="round"/>
          <circle cx="7" cy="18" r="1.4"/><circle cx="17" cy="18" r="1.4"/>
        </svg>
      </span>`,
    iconSize: [34, 34],
    iconAnchor: [17, 17],
  })
}

function destinationIcon(): L.DivIcon {
  return L.divIcon({
    className: 'fleetflow-marker-wrapper',
    html: `
      <span class="fleetflow-marker fleetflow-marker--destination" role="img" aria-label="Drop-off destination">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2">
          <path d="M12 21s7-6.3 7-11a7 7 0 1 0-14 0c0 4.7 7 11 7 11z" stroke-linejoin="round"/>
          <circle cx="12" cy="10" r="2.4"/>
        </svg>
      </span>`,
    iconSize: [30, 30],
    iconAnchor: [15, 28],
  })
}

function currentPosition(): L.LatLng | null {
  const latest = props.locations[props.locations.length - 1]
  return latest ? L.latLng(latest.latitude, latest.longitude) : null
}

function renderRoute(): void {
  if (!map.value) {
    return
  }
  const points = props.locations.map((location) =>
    L.latLng(location.latitude, location.longitude),
  )

  if (routeLine) {
    routeLine.setLatLngs(points)
    return
  }
  if (points.length < 2) {
    return
  }
  routeLine = L.polyline(points, {
    color: '#2563EB',
    weight: 3,
    opacity: 0.75,
    lineJoin: 'round',
  }).addTo(map.value)
}

function renderMarkers(): void {
  const instance = map.value
  if (!instance) {
    return
  }
  const position = currentPosition()

  if (position) {
    if (driverMarker) {
      driverMarker.setLatLng(position)
    } else {
      driverMarker = L.marker(position, { icon: driverIcon(), zIndexOffset: 1000 }).addTo(instance)
    }
    if (props.follow) {
      instance.panTo(position, { animate: true, duration: 0.4 })
    }
  }

  if (props.destination) {
    const target = L.latLng(props.destination.latitude, props.destination.longitude)
    if (destinationMarker) {
      destinationMarker.setLatLng(target)
    } else {
      destinationMarker = L.marker(target, {
        icon: destinationIcon(),
        zIndexOffset: 500,
      })
        .bindPopup(props.destination.label ?? 'Drop-off', { closeButton: false })
        .addTo(instance)
    }
  }

  renderRoute()
}

function fitAll(): void {
  const instance = map.value
  if (!instance) {
    return
  }
  const points = props.locations.map((location) => L.latLng(location.latitude, location.longitude))
  if (props.destination) {
    points.push(L.latLng(props.destination.latitude, props.destination.longitude))
  }
  if (points.length === 0) {
    instance.setView(DEFAULT_CENTER, 12)
    return
  }
  if (points.length === 1) {
    instance.setView(points[0], 14)
    return
  }
  instance.fitBounds(L.latLngBounds(points), { padding: [40, 40], maxZoom: 15 })
}

onMounted(() => {
  if (!container.value) {
    return
  }
  map.value = L.map(container.value, {
    center: DEFAULT_CENTER,
    zoom: 12,
    zoomControl: true,
    attributionControl: true,
  })

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors',
  }).addTo(map.value)

  renderMarkers()
  if (!props.follow) {
    fitAll()
  }
  emit('ready')
})

onBeforeUnmount(() => {
  map.value?.remove()
  map.value = null
  driverMarker = null
  destinationMarker = null
  routeLine = null
})

watch(
  () => props.locations.length,
  () => renderMarkers(),
)

watch(
  () => props.destination,
  () => renderMarkers(),
)

watch(
  () => props.follow,
  (follow) => {
    if (!follow) {
      fitAll()
    }
  },
)

defineExpose({ fitAll })
</script>

<template>
  <div class="relative overflow-hidden rounded-card border border-edge dark:border-[#334155]">
    <div
      v-show="!loading"
      ref="container"
      class="w-full"
      :style="{ height }"
      role="application"
      aria-label="Live delivery map"
    />

    <div
      v-if="loading"
      class="flex flex-col items-center justify-center gap-2 bg-surface-muted text-content-muted dark:bg-[#111827] dark:text-[#94A3B8]"
      :style="{ height }"
      role="status"
    >
      <svg class="h-5 w-5 animate-spin text-primary" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" opacity="0.25" />
        <path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
      </svg>
      <p class="text-small">Loading the map…</p>
    </div>

    <div
      v-if="!loading && locations.length === 0"
      class="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-1.5 bg-surface/85 text-center dark:bg-[#0F172A]/85"
    >
      <p class="text-body font-medium text-content dark:text-[#F8FAFC]">Waiting for the first position</p>
      <p class="max-w-xs text-small text-content-muted dark:text-[#94A3B8]">
        The driver's location appears here as soon as tracking starts.
      </p>
    </div>
  </div>
</template>

<style>
/*
 * Marker styles live unscoped because Leaflet renders the icons into its own pane,
 * outside the component's scoped attribute selector.
 */
.fleetflow-marker-wrapper {
  background: none;
  border: 0;
}

.fleetflow-marker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 9999px;
  box-shadow: 0 2px 6px rgb(15 23 42 / 0.28);
}

.fleetflow-marker--driver {
  background: #2563eb;
  color: #fff;
}

.fleetflow-marker--destination {
  width: 30px;
  height: 30px;
  background: #0f172a;
  color: #fff;
}
</style>