<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import { ApiError } from '@/services/api'
import { orderApi } from '@/services/orderApi'
import type { DailyOrderCount, OrderKpiResponse, OrderStatus } from '@/types'
import { formatDate, formatDateTime, formatNumber } from '@/utils/format'
import { orderStatus } from '@/utils/status'

/**
 * Trends from the order service.
 *
 * The charts are inline SVG because the dependency list is fixed: a library would ship
 * more weight than the three shapes on this screen. Everything drawn here comes from
 * `GET /orders/analytics/daily` or `GET /orders/kpi` — nothing is smoothed, padded or
 * estimated, and a day with no orders is drawn as zero rather than skipped.
 */

const DAYS = 14
const SAMPLE_SIZE = 100

/** One colour per order status, so the doughnut never reuses a tone for two states. */
const STATUS_COLOR: Record<OrderStatus, string> = {
  CREATED: '#94A3B8',
  CONFIRMED: '#0EA5E9',
  PROCESSING: '#2563EB',
  READY_FOR_DELIVERY: '#1D4ED8',
  OUT_FOR_DELIVERY: '#7C3AED',
  DELIVERED: '#16A34A',
  CANCELLED: '#DC2626',
}

const DELIVERED_COLOR = '#16A34A'
const CANCELLED_COLOR = '#DC2626'
const ORDERS_COLOR = '#2563EB'

const daily = ref<DailyOrderCount[]>([])
const kpi = ref<OrderKpiResponse | null>(null)
const sampleCounts = ref<{ status: OrderStatus; count: number }[]>([])
const sampleTotal = ref(0)
const sampledFrom = ref(0)
const sampleError = ref<string | null>(null)

const loading = ref(true)
const error = ref<string | null>(null)
const loadedAt = ref<string | null>(null)

const CHART_WIDTH = 640
const PLOT_HEIGHT = 150
const AXIS_LEFT = 30
const AXIS_RIGHT = 8
const AXIS_TOP = 10
const AXIS_BOTTOM = 26

const DOUGHNUT = { r: 58, stroke: 22, size: 150 }

function reasonMessage(reason: unknown, fallback: string): string {
  return reason instanceof ApiError ? reason.message : fallback
}

/**
 * Axis maximum rounded to a multiple of four, so the four gridline labels are always
 * distinct whole numbers however small the peak is.
 */
function niceMax(value: number): number {
  const peak = Math.max(4, value)
  const magnitude = 10 ** Math.floor(Math.log10(peak))
  const step = Math.max(1, Math.ceil(peak / 4 / magnitude) * magnitude)
  const total = Math.ceil(peak / step) * step
  return Math.max(4, Math.ceil(total / 4) * 4)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null

  const [series, kpiResult, sample] = await Promise.allSettled([
    orderApi.dailyAnalytics(DAYS),
    orderApi.kpi(),
    orderApi.list({ page: 0, size: SAMPLE_SIZE, sort: 'createdAt,desc' }),
  ])

  if (series.status === 'fulfilled') {
    daily.value = series.value
  } else {
    daily.value = []
    error.value = reasonMessage(series.reason, 'The daily series could not be loaded.')
  }

  kpi.value = kpiResult.status === 'fulfilled' ? kpiResult.value : null

  if (sample.status === 'fulfilled') {
    const counts = new Map<OrderStatus, number>()
    for (const order of sample.value.content) {
      counts.set(order.status, (counts.get(order.status) ?? 0) + 1)
    }
    sampleCounts.value = [...counts.entries()]
      .map(([status, count]) => ({ status, count }))
      .sort((left, right) => right.count - left.count)
    sampleTotal.value = sample.value.content.length
    sampledFrom.value = sample.value.totalElements
    sampleError.value = null
  } else {
    sampleCounts.value = []
    sampleError.value = reasonMessage(sample.reason, 'The status mix could not be sampled.')
  }

  loadedAt.value = new Date().toISOString()
  loading.value = false
}

// ------------------------------------------------------------ orders per day

const ordersChart = computed(() => {
  const days = daily.value
  const plotWidth = CHART_WIDTH - AXIS_LEFT - AXIS_RIGHT
  const height = PLOT_HEIGHT - AXIS_TOP - AXIS_BOTTOM
  const max = niceMax(Math.max(0, ...days.map((day) => day.orders)))
  const slot = days.length > 0 ? plotWidth / days.length : plotWidth
  const barWidth = Math.max(4, Math.min(26, slot - 12))
  const baseline = AXIS_TOP + height

  return {
    max,
    baseline,
    total: days.reduce((sum, day) => sum + day.orders, 0),
    ticks: [0, 0.25, 0.5, 0.75, 1].map((ratio) => ({
      value: Math.round(max * ratio),
      y: baseline - height * ratio,
    })),
    bars: days.map((day, index) => {
      const barHeight = max > 0 ? (day.orders / max) * height : 0
      return {
        key: day.date,
        x: AXIS_LEFT + index * slot + (slot - barWidth) / 2,
        y: baseline - barHeight,
        width: barWidth,
        height: barHeight,
        orders: day.orders,
        label: formatDate(day.date),
      }
    }),
  }
})

// --------------------------------------------------- delivered vs cancelled

const outcomeChart = computed(() => {
  const days = daily.value
  const plotWidth = CHART_WIDTH - AXIS_LEFT - AXIS_RIGHT
  const height = PLOT_HEIGHT - AXIS_TOP - AXIS_BOTTOM
  const max = niceMax(Math.max(0, ...days.map((day) => Math.max(day.delivered, day.cancelled))))
  const slot = days.length > 0 ? plotWidth / days.length : plotWidth
  const barWidth = Math.max(2, Math.min(11, slot / 2 - 3))
  const baseline = AXIS_TOP + height

  return {
    max,
    baseline,
    ticks: [0, 0.5, 1].map((ratio) => ({
      value: Math.round(max * ratio),
      y: baseline - height * ratio,
    })),
    groups: days.map((day, index) => {
      const deliveredHeight = max > 0 ? (day.delivered / max) * height : 0
      const cancelledHeight = max > 0 ? (day.cancelled / max) * height : 0
      const left = AXIS_LEFT + index * slot + (slot - (barWidth * 2 + 2)) / 2
      return {
        key: day.date,
        label: formatDate(day.date),
        delivered: {
          x: left,
          y: baseline - deliveredHeight,
          width: barWidth,
          height: deliveredHeight,
          value: day.delivered,
        },
        cancelled: {
          x: left + barWidth + 2,
          y: baseline - cancelledHeight,
          width: barWidth,
          height: cancelledHeight,
          value: day.cancelled,
        },
      }
    }),
  }
})

const outcomeAxisTicks = computed(() => {
  const groups = outcomeChart.value.groups
  const first = groups[0]
  const middle = groups[Math.floor((groups.length - 1) / 2)]
  const last = groups[groups.length - 1]
  return [first, middle, last].filter((group) => group !== undefined)
})

const ordersAxisTicks = computed(() => {
  const bars = ordersChart.value.bars
  const first = bars[0]
  const middle = bars[Math.floor((bars.length - 1) / 2)]
  const last = bars[bars.length - 1]
  return [first, middle, last].filter((bar) => bar !== undefined)
})

// ------------------------------------------------------------------ doughnut

interface DoughnutSlice {
  key: string
  status: OrderStatus
  label: string
  count: number
  share: number
  color: string
  dash: string
  offset: number
}

const doughnut = computed(() => {
  const entries = sampleCounts.value
  const total = entries.reduce((sum, entry) => sum + entry.count, 0)
  if (total === 0) {
    return { slices: [] as DoughnutSlice[], total: 0, radius: DOUGHNUT.r }
  }

  const circumference = 2 * Math.PI * DOUGHNUT.r
  let cursor = 0
  const slices = entries.map<DoughnutSlice>((entry) => {
    const fraction = entry.count / total
    const length = circumference * fraction
    const slice: DoughnutSlice = {
      key: entry.status,
      status: entry.status,
      label: orderStatus(entry.status).label,
      count: entry.count,
      share: fraction,
      color: STATUS_COLOR[entry.status],
      dash: `${length} ${circumference - length}`,
      offset: -cursor,
    }
    cursor += length
    return slice
  })

  return { slices, total, radius: DOUGHNUT.r }
})

const isSample = computed(() => sampledFrom.value > sampleTotal.value)

// ---------------------------------------------------------- kpi breakdown

const kpiBreakdown = computed(() => {
  const value = kpi.value
  if (!value) {
    return []
  }
  // Buckets straight from OrderKpiService: ACTIVE is the five in-flight states and
  // PENDING (created + confirmed) is counted inside it, so these four do not sum to total.
  const entries = [
    { key: 'delivered', label: 'Delivered orders, all time', count: value.deliveredOrders, tone: 'success' as const },
    { key: 'active', label: 'Active orders, in flight', count: value.activeOrders, tone: 'progress' as const },
    { key: 'pending', label: 'Pending orders, awaiting action', count: value.pendingOrders, tone: 'warning' as const },
    { key: 'cancelled', label: 'Cancelled orders, all time', count: value.cancelledOrders, tone: 'danger' as const },
  ]
  const max = Math.max(1, ...entries.map((entry) => entry.count))
  return entries.map((entry) => ({
    ...entry,
    percent: Math.round((entry.count / max) * 100),
  }))
})

onMounted(load)
</script>

<template>
  <div class="space-y-4">
    <BaseSkeleton v-if="loading" variant="lines" :rows="8" label="Loading analytics" />

    <ErrorState v-else-if="error" :message="error" retryLabel="Try again" @retry="load" />

    <template v-else>
      <div class="grid grid-cols-1 gap-4 xl:grid-cols-2">
        <BaseCard title="Orders per day" :subtitle="`Last ${DAYS} days · ${formatNumber(ordersChart.total)} orders`">
          <svg
            class="w-full"
            :viewBox="`0 0 ${CHART_WIDTH} ${PLOT_HEIGHT}`"
            role="img"
            :aria-label="`Bar chart of orders per day for the last ${DAYS} days. ${formatNumber(ordersChart.total)} orders in total.`"
          >
            <g>
              <line
                v-for="tick in ordersChart.ticks"
                :key="tick.value"
                :x1="AXIS_LEFT"
                :x2="CHART_WIDTH - AXIS_RIGHT"
                :y1="tick.y"
                :y2="tick.y"
                class="stroke-edge dark:stroke-[#1E293B]"
                stroke-width="1"
              />
              <text
                v-for="tick in ordersChart.ticks"
                :key="`label-${tick.value}`"
                :x="AXIS_LEFT - 6"
                :y="tick.y + 3"
                text-anchor="end"
                class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
              >
                {{ tick.value }}
              </text>
            </g>

            <line
              :x1="AXIS_LEFT"
              :x2="CHART_WIDTH - AXIS_RIGHT"
              :y1="ordersChart.baseline"
              :y2="ordersChart.baseline"
              class="stroke-edge-strong dark:stroke-[#334155]"
              stroke-width="1"
            />

            <rect
              v-for="bar in ordersChart.bars"
              :key="bar.key"
              :x="bar.x"
              :y="bar.y"
              :width="bar.width"
              :height="bar.height"
              :fill="ORDERS_COLOR"
            >
              <title>{{ bar.label }}: {{ bar.orders }} orders</title>
            </rect>

            <text
              v-for="tick in ordersAxisTicks"
              :key="`x-${tick.key}`"
              :x="tick.x + tick.width / 2"
              :y="PLOT_HEIGHT - 6"
              text-anchor="middle"
              class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
            >
              {{ tick.label }}
            </text>
          </svg>
        </BaseCard>

        <BaseCard title="Delivered vs cancelled" :subtitle="`Per day, last ${DAYS} days`">
          <div class="mb-2 flex items-center gap-4">
            <span class="flex items-center gap-1.5 text-small text-content-muted dark:text-[#94A3B8]">
              <span class="h-2.5 w-2.5 rounded-sm" :style="{ backgroundColor: DELIVERED_COLOR }" aria-hidden="true" />
              Delivered
            </span>
            <span class="flex items-center gap-1.5 text-small text-content-muted dark:text-[#94A3B8]">
              <span class="h-2.5 w-2.5 rounded-sm" :style="{ backgroundColor: CANCELLED_COLOR }" aria-hidden="true" />
              Cancelled
            </span>
          </div>

          <svg
            class="w-full"
            :viewBox="`0 0 ${CHART_WIDTH} ${PLOT_HEIGHT}`"
            role="img"
            aria-label="Grouped bar chart of delivered and cancelled orders per day"
          >
            <line
              v-for="tick in outcomeChart.ticks"
              :key="tick.value"
              :x1="AXIS_LEFT"
              :x2="CHART_WIDTH - AXIS_RIGHT"
              :y1="tick.y"
              :y2="tick.y"
              class="stroke-edge dark:stroke-[#1E293B]"
              stroke-width="1"
            />
            <text
              v-for="tick in outcomeChart.ticks"
              :key="`label-${tick.value}`"
              :x="AXIS_LEFT - 6"
              :y="tick.y + 3"
              text-anchor="end"
              class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
            >
              {{ tick.value }}
            </text>

            <line
              :x1="AXIS_LEFT"
              :x2="CHART_WIDTH - AXIS_RIGHT"
              :y1="outcomeChart.baseline"
              :y2="outcomeChart.baseline"
              class="stroke-edge-strong dark:stroke-[#334155]"
              stroke-width="1"
            />

            <template v-for="group in outcomeChart.groups" :key="group.key">
              <rect
                :x="group.delivered.x"
                :y="group.delivered.y"
                :width="group.delivered.width"
                :height="group.delivered.height"
                :fill="DELIVERED_COLOR"
              >
                <title>{{ group.label }}: {{ group.delivered.value }} delivered</title>
              </rect>
              <rect
                :x="group.cancelled.x"
                :y="group.cancelled.y"
                :width="group.cancelled.width"
                :height="group.cancelled.height"
                :fill="CANCELLED_COLOR"
              >
                <title>{{ group.label }}: {{ group.cancelled.value }} cancelled</title>
              </rect>
            </template>

            <text
              v-for="tick in outcomeAxisTicks"
              :key="`x-${tick.key}`"
              :x="tick.delivered.x + (tick.cancelled.x - tick.delivered.x + tick.cancelled.width) / 2"
              :y="PLOT_HEIGHT - 6"
              text-anchor="middle"
              class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
            >
              {{ tick.label }}
            </text>
          </svg>
        </BaseCard>
      </div>

      <div class="grid grid-cols-1 gap-4 xl:grid-cols-[320px,1fr]">
        <BaseCard
          title="Status mix"
          :subtitle="
            sampleError
              ? 'Sample unavailable'
              : isSample
                ? `Counted from ${formatNumber(sampleTotal)} of ${formatNumber(sampledFrom)} orders`
                : `All ${formatNumber(sampleTotal)} orders on record`
          "
        >
          <template v-if="doughnut.slices.length > 0">
            <div class="flex items-center gap-4">
              <svg
                class="h-36 w-36 shrink-0"
                :viewBox="`0 0 ${DOUGHNUT.size} ${DOUGHNUT.size}`"
                role="img"
                :aria-label="`Doughnut chart of the order status mix across ${formatNumber(doughnut.total)} orders.`"
              >
                <circle
                  :cx="DOUGHNUT.size / 2"
                  :cy="DOUGHNUT.size / 2"
                  :r="doughnut.radius"
                  fill="none"
                  class="stroke-edge dark:stroke-[#1E293B]"
                  :stroke-width="DOUGHNUT.stroke"
                />
                <circle
                  v-for="slice in doughnut.slices"
                  :key="slice.key"
                  :cx="DOUGHNUT.size / 2"
                  :cy="DOUGHNUT.size / 2"
                  :r="doughnut.radius"
                  fill="none"
                  :stroke="slice.color"
                  :stroke-width="DOUGHNUT.stroke"
                  :stroke-dasharray="slice.dash"
                  :stroke-dashoffset="slice.offset"
                  :transform="`rotate(-90 ${DOUGHNUT.size / 2} ${DOUGHNUT.size / 2})`"
                >
                  <title>{{ slice.label }}: {{ slice.count }} orders</title>
                </circle>
              </svg>

              <ul class="min-w-0 flex-1 space-y-1.5">
                <li
                  v-for="slice in doughnut.slices"
                  :key="slice.key"
                  class="flex items-center gap-2 text-small"
                >
                  <span class="h-2.5 w-2.5 shrink-0 rounded-sm" :style="{ backgroundColor: slice.color }" aria-hidden="true" />
                  <span class="min-w-0 flex-1 truncate text-content-muted dark:text-[#94A3B8]">{{ slice.label }}</span>
                  <span class="tabular-nums font-medium text-content dark:text-[#F8FAFC]">
                    {{ slice.count }}
                    <span class="text-content-subtle dark:text-[#64748B]">
                      ({{ Math.round(slice.share * 100) }}%)
                    </span>
                  </span>
                </li>
              </ul>
            </div>

            <p class="mt-3 text-small text-content-subtle dark:text-[#64748B]">
              The API has no status-distribution endpoint, so this ring is counted from a
              single page of the register
              <template v-if="isSample">
                (up to {{ SAMPLE_SIZE }} orders), not from every page. The register holds
                {{ formatNumber(sampledFrom) }} orders in total.
              </template>
              <template v-else>which is the whole register.</template>
            </p>
          </template>

          <p v-else class="text-body text-content-muted dark:text-[#94A3B8]">
            {{ sampleError ?? 'No orders were returned for the status mix.' }}
          </p>
        </BaseCard>

        <BaseCard
          title="Order KPI counters"
          subtitle="Straight from GET /orders/kpi — counts, not a distribution"
        >
          <p class="mb-3 text-small text-content-muted dark:text-[#94A3B8]">
            These are four counters, not a distribution. Pending is counted inside Active
            (created and confirmed are both in flight), so they do not add up to the order
            total and no percentage is claimed from them.
          </p>

          <ul v-if="kpiBreakdown.length > 0" class="space-y-3">
            <li v-for="entry in kpiBreakdown" :key="entry.key">
              <div class="flex items-baseline justify-between gap-3">
                <span class="text-body text-content dark:text-[#F8FAFC]">{{ entry.label }}</span>
                <span class="tabular-nums text-body font-medium text-content dark:text-[#F8FAFC]">
                  {{ formatNumber(entry.count) }}
                </span>
              </div>
              <div class="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-surface-muted dark:bg-[#1E293B]">
                <div
                  class="h-full rounded-full"
                  :class="{
                    'bg-success': entry.tone === 'success',
                    'bg-primary': entry.tone === 'progress',
                    'bg-warning': entry.tone === 'warning',
                    'bg-danger': entry.tone === 'danger',
                  }"
                  :style="{ width: `${Math.max(entry.percent, entry.count > 0 ? 2 : 0)}%` }"
                  aria-hidden="true"
                />
              </div>
            </li>
          </ul>

          <p v-else class="text-body text-content-muted dark:text-[#94A3B8]">
            The order KPI endpoint did not answer, so no counters are shown.
          </p>
        </BaseCard>
      </div>

      <p class="px-1 text-small text-content-subtle dark:text-[#64748B]">
        Data as of {{ formatDateTime(loadedAt) }} · every figure above is returned by the order
        service; nothing here is estimated.
      </p>
    </template>
  </div>
</template>