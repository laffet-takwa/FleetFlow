<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatCard from '@/components/ui/StatCard.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import { ApiError } from '@/services/api'
import { deliveryApi } from '@/services/deliveryApi'
import { inventoryApi } from '@/services/inventoryApi'
import { orderApi } from '@/services/orderApi'
import { useDeliveryStore } from '@/stores/deliveryStore'
import type { DailyOrderCount, DeliveryKpiResponse, OrderKpiResponse, StockSummary } from '@/types'
import { deliveryLabel, formatDate, formatNumber } from '@/utils/format'
import { deliveryStatus } from '@/utils/status'

/**
 * Operations landing screen.
 *
 * The six KPIs come from three separate services, so they are requested in parallel and
 * settled independently: a slow delivery-service must not hold the order KPIs hostage,
 * and a card whose endpoint failed shows a dash instead of blanking the page.
 */

const UNAVAILABLE = '—'
const CHART_DAYS = 14

const router = useRouter()

type CardIcon = 'orders' | 'truck' | 'check' | 'clock' | 'alert' | 'users'
type CardTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger' | 'info'

interface DashboardCard {
  key: string
  label: string
  icon: CardIcon
  tone: CardTone
  value: string | number
  hint: string
  loading: boolean
  unavailable: boolean
}

const orderKpi = ref<OrderKpiResponse | null>(null)
const deliveryKpi = ref<DeliveryKpiResponse | null>(null)
const stockSummary = ref<StockSummary | null>(null)
const daily = ref<DailyOrderCount[]>([])

const orderError = ref<string | null>(null)
const deliveryError = ref<string | null>(null)
const stockError = ref<string | null>(null)
const chartError = ref<string | null>(null)

const loading = ref(true)
const chartLoading = ref(true)
const activeLoading = ref(true)

const deliveryStore = useDeliveryStore()

function reasonMessage(reason: unknown, fallback: string): string {
  return reason instanceof ApiError ? reason.message : fallback
}

function card(
  key: string,
  label: string,
  icon: CardIcon,
  tone: CardTone,
  value: number | undefined,
  hint: string,
  error: string | null,
): DashboardCard {
  return {
    key,
    label,
    icon,
    tone,
    loading: loading.value,
    unavailable: error !== null && value === undefined,
    value: value === undefined ? UNAVAILABLE : value,
    hint: value === undefined ? (error ? 'Could not load' : 'No data yet') : hint,
  }
}

const cards = computed<DashboardCard[]>(() => {
  const orders = orderKpi.value
  const deliveries = deliveryKpi.value
  const stock = stockSummary.value

  return [
    card(
      'total-orders',
      'Total orders',
      'orders',
      'primary',
      orders?.totalOrders,
      orders ? `${formatNumber(orders.activeOrders)} still in progress` : '',
      orderError.value,
    ),
    card(
      'active-deliveries',
      'Active deliveries',
      'truck',
      'info',
      deliveries?.activeDeliveries,
      deliveries ? `${formatNumber(deliveries.busyDrivers)} drivers on the road` : '',
      deliveryError.value,
    ),
    card(
      'delivered-today',
      'Delivered orders',
      'check',
      'success',
      orders?.deliveredOrders,
      // The order KPI counts DELIVERED across the whole table; only the delivery KPI is
      // day-scoped, so the "today" figure is quoted from there and labelled as such.
      deliveries
        ? `All time · ${formatNumber(deliveries.completedToday)} completed today`
        : 'All delivered orders on record',
      orderError.value,
    ),
    card(
      'pending-orders',
      'Pending orders',
      'clock',
      'warning',
      orders?.pendingOrders,
      orders ? `${formatNumber(orders.cancelledOrders)} cancelled in total` : '',
      orderError.value,
    ),
    card(
      'low-stock',
      'Low stock products',
      'alert',
      stock && stock.lowStock > 0 ? 'warning' : 'neutral',
      stock?.lowStock,
      stock ? `${formatNumber(stock.outOfStock)} out of stock` : '',
      stockError.value,
    ),
    card(
      'available-drivers',
      'Available drivers',
      'users',
      deliveries && deliveries.availableDrivers > 0 ? 'success' : 'neutral',
      deliveries?.availableDrivers,
      deliveries
        ? `${formatNumber(deliveries.availableVehicles)} vehicles free`
        : 'Ready for a new assignment',
      deliveryError.value,
    ),
  ]
})

const failedCards = computed(() => cards.value.filter((entry) => entry.unavailable))

// --------------------------------------------------------------------- chart

const CHART_WIDTH = 560
const CHART_HEIGHT = 150
const CHART_LEFT = 26
const CHART_RIGHT = 6
const CHART_TOP = 8
const CHART_BOTTOM = 22

const chart = computed(() => {
  const days = daily.value
  const plotWidth = CHART_WIDTH - CHART_LEFT - CHART_RIGHT
  const plotHeight = CHART_HEIGHT - CHART_TOP - CHART_BOTTOM
  const peak = days.reduce((max, day) => Math.max(max, day.orders), 0)
  const slot = days.length > 0 ? plotWidth / days.length : plotWidth
  const barWidth = Math.max(4, Math.min(24, slot - 10))

  const bars = days.map((day, index) => {
    // A zero day is drawn as a zero-height bar rather than skipped, so a gap in the
    // series can never be mistaken for missing data.
    const height = peak > 0 ? (day.orders / peak) * plotHeight : 0
    return {
      key: day.date,
      x: CHART_LEFT + index * slot + (slot - barWidth) / 2,
      y: CHART_TOP + plotHeight - height,
      width: barWidth,
      height,
      orders: day.orders,
      label: formatDate(day.date),
    }
  })

  const busiest = days.reduce<DailyOrderCount | null>(
    (top, day) => (top === null || day.orders > top.orders ? day : top),
    null,
  )

  const first = bars[0] ?? null
  const middle = bars[Math.floor((bars.length - 1) / 2)] ?? null
  const last = bars[bars.length - 1] ?? null

  return {
    bars,
    peak,
    baseline: CHART_TOP + plotHeight,
    axisTicks: [first, middle, last].filter((bar) => bar !== null),
    busiest,
    total: days.reduce((sum, day) => sum + day.orders, 0),
  }
})

const chartSummary = computed(() => {
  const { total, busiest, bars } = chart.value
  if (bars.length === 0) {
    return ''
  }
  const busiestText = busiest && busiest.orders > 0 ? ` Peak: ${formatNumber(busiest.orders)} on ${formatDate(busiest.date)}.` : ''
  return `${formatNumber(total)} orders in the last ${bars.length} days.${busiestText}`
})

// ------------------------------------------------------------------ lifecycle

async function load(): Promise<void> {
  loading.value = true
  chartLoading.value = true

  const [orders, deliveries, stock, series] = await Promise.allSettled([
    orderApi.kpi(),
    deliveryApi.kpi(),
    inventoryApi.summary(),
    orderApi.dailyAnalytics(CHART_DAYS),
  ])

  orderKpi.value = orders.status === 'fulfilled' ? orders.value : null
  orderError.value = orders.status === 'rejected' ? reasonMessage(orders.reason, 'Order KPIs are unavailable.') : null

  deliveryKpi.value = deliveries.status === 'fulfilled' ? deliveries.value : null
  deliveryError.value =
    deliveries.status === 'rejected' ? reasonMessage(deliveries.reason, 'Delivery KPIs are unavailable.') : null

  stockSummary.value = stock.status === 'fulfilled' ? stock.value : null
  stockError.value = stock.status === 'rejected' ? reasonMessage(stock.reason, 'Stock summary is unavailable.') : null

  daily.value = series.status === 'fulfilled' ? series.value : []
  chartError.value = series.status === 'rejected' ? reasonMessage(series.reason, 'The daily series is unavailable.') : null

  loading.value = false
  chartLoading.value = false
}

async function loadActiveDeliveries(): Promise<void> {
  activeLoading.value = true
  await deliveryStore.fetchActive()
  activeLoading.value = false
}

function retry(): void {
  void load()
  void loadActiveDeliveries()
}

onMounted(() => {
  void load()
  void loadActiveDeliveries()
})
</script>

<template>
  <div class="space-y-5">
    <section aria-labelledby="dashboard-kpis">
      <h2 id="dashboard-kpis" class="section-title">Today at a glance</h2>

      <div class="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <StatCard
          v-for="entry in cards"
          :key="entry.key"
          :label="entry.label"
          :value="entry.value"
          :hint="entry.hint"
          :icon="entry.icon"
          :tone="entry.tone"
          :loading="entry.loading"
        />
      </div>

      <div
        v-if="failedCards.length > 0"
        class="mt-3 flex flex-wrap items-center gap-3 rounded-control border border-edge bg-surface px-4 py-3 dark:border-[#334155] dark:bg-[#111827]"
        role="alert"
      >
        <p class="text-small text-content-muted dark:text-[#94A3B8]">
          <span class="font-medium text-content dark:text-[#F8FAFC]">
            {{ failedCards.length }}
          </span>
          of the six metrics could not be loaded. The rest of the dashboard is unaffected.
        </p>
        <BaseButton size="sm" variant="secondary" @click="retry">Retry metrics</BaseButton>
      </div>
    </section>

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-3">
      <BaseCard
        class="xl:col-span-2"
        title="Active deliveries"
        subtitle="Everything currently moving through the network"
        :padded="false"
      >
        <template #actions>
          <RouterLink
            :to="{ name: 'admin-deliveries' }"
            class="text-small font-medium text-primary hover:underline"
          >
            All deliveries
          </RouterLink>
        </template>

        <BaseSkeleton v-if="activeLoading" variant="lines" :rows="5" label="Loading active deliveries" />

        <ul v-else-if="deliveryStore.activeDeliveries.length > 0" class="divide-y divide-edge dark:divide-[#1E293B]">
          <li
            v-for="delivery in deliveryStore.activeDeliveries"
            :key="delivery.id"
            class="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3"
          >
            <span class="font-mono text-small font-medium text-content dark:text-[#F8FAFC]">
              {{ deliveryLabel(delivery.id) }}
            </span>
            <span class="min-w-0 flex-1 truncate text-body text-content dark:text-[#F8FAFC]">
              {{ delivery.driverName ?? 'No driver assigned' }}
            </span>
            <StatusBadge :meta="deliveryStatus(delivery.status)" />
            <RouterLink
              :to="{ name: 'admin-deliveries' }"
              class="text-small font-medium text-primary hover:underline"
            >
              View
            </RouterLink>
          </li>
        </ul>

        <EmptyState
          v-else
          title="No active deliveries"
          description="Nothing is on the road right now. New deliveries appear here as soon as they are created."
          icon="deliveries"
          actionLabel="Open deliveries"
          @action="router.push({ name: 'admin-deliveries' })"
        />
      </BaseCard>

      <BaseCard
        title="Orders per day"
        subtitle="Last 14 days, from the order service"
        :padded="false"
      >
        <template #actions>
          <RouterLink
            :to="{ name: 'admin-analytics' }"
            class="text-small font-medium text-primary hover:underline"
          >
            Analytics
          </RouterLink>
        </template>

        <div class="card-body">
          <BaseSkeleton v-if="chartLoading" variant="lines" :rows="4" label="Loading the daily order series" />

          <ErrorState
            v-else-if="chartError"
            :message="chartError"
            retryLabel="Try again"
            @retry="retry"
          />

          <template v-else>
            <svg
              class="w-full"
              :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
              role="img"
              :aria-label="`Orders per day for the last ${CHART_DAYS} days. ${chartSummary}`"
            >
              <line
                :x1="CHART_LEFT"
                :x2="CHART_WIDTH - CHART_RIGHT"
                :y1="chart.baseline"
                :y2="chart.baseline"
                class="stroke-edge dark:stroke-[#334155]"
                stroke-width="1"
              />
              <text
                :x="CHART_LEFT - 5"
                :y="chart.baseline + 3"
                text-anchor="end"
                class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
              >
                0
              </text>
              <text
                v-if="chart.peak > 0"
                :x="CHART_LEFT - 5"
                :y="CHART_TOP + 7"
                text-anchor="end"
                class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
              >
                {{ chart.peak }}
              </text>

              <g>
                <rect
                  v-for="bar in chart.bars"
                  :key="bar.key"
                  :x="bar.x"
                  :y="bar.y"
                  :width="bar.width"
                  :height="bar.height"
                  rx="2"
                  class="fill-primary dark:fill-primary-dark"
                >
                  <title>{{ bar.label }}: {{ bar.orders }} orders</title>
                </rect>
              </g>

              <text
                v-for="tick in chart.axisTicks"
                :key="tick.key"
                :x="tick.x + tick.width / 2"
                :y="CHART_HEIGHT - 6"
                text-anchor="middle"
                class="fill-content-subtle text-[10px] dark:fill-[#64748B]"
              >
                {{ tick.label }}
              </text>
            </svg>

            <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">{{ chartSummary }}</p>
          </template>
        </div>
      </BaseCard>
    </div>
  </div>
</template>