<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import BaseButton from '@/components/ui/BaseButton.vue'
import BaseCard from '@/components/ui/BaseCard.vue'
import BaseInput from '@/components/ui/BaseInput.vue'
import BaseSelect from '@/components/ui/BaseSelect.vue'
import BaseSkeleton from '@/components/ui/BaseSkeleton.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ErrorState from '@/components/ui/ErrorState.vue'
import StatusBadge from '@/components/ui/StatusBadge.vue'
import TablePagination from '@/components/ui/TablePagination.vue'
import { ApiError } from '@/services/api'
import { customerApi } from '@/services/customerApi'
import { useCatalogStore, useOrderStore } from '@/stores/orderStore'
import { useDebouncedRef } from '@/stores/asyncStore'
import { useToastStore } from '@/stores/toastStore'
import type { OrderResponse, ProductResponse } from '@/types'
import { formatMoney, orderLabel, pluralize } from '@/utils/format'
import { stockStatus } from '@/utils/status'

/**
 * Checkout: 1 Products → 2 Review → 3 Address → 4 Confirm.
 *
 * The catalogue comes from the shared catalog store, but the basket is genuinely local to
 * this screen — it is a draft that is discarded until the order is created, and no other
 * screen reads it. Pricing shown here is recomputed locally for display only; the order
 * service recalculates authoritatively when the order is created.
 */

const STEPS = ['Products', 'Review', 'Address', 'Confirm'] as const

/** Mirrors `fleetflow.pricing.delivery-fee`; the server value is authoritative. */
const DELIVERY_FEE_ESTIMATE = 8
const ESTIMATE_CURRENCY = 'TND'

const MAX_QUANTITY = 99

interface CartLine {
  productId: number
  name: string
  price: number
  quantity: number
  /** Stock ceiling captured when the line was added, so a review stepper can honour it too. */
  maxQuantity: number
}

const catalog = useCatalogStore()
const orderStore = useOrderStore()
const toasts = useToastStore()

const step = ref(1)
const cart = ref<CartLine[]>([])
const reviewError = ref('')

const createdOrder = ref<OrderResponse | null>(null)
const placing = ref(false)
const placeError = ref<string | null>(null)

const profileLoading = ref(false)
const profileError = ref<string | null>(null)

const address = reactive({ deliveryAddress: '', city: '', postalCode: '' })
const addressErrors = reactive({ deliveryAddress: '', city: '', postalCode: '' })

const { debounced: debouncedSearch, watchSource: queueSearch } = useDebouncedRef(
  () => catalog.search,
  300,
)

const categoryChoices = computed(() =>
  catalog.categories.map((category) => ({ value: category, label: category })),
)

const cartCount = computed(() => cart.value.reduce((total, line) => total + line.quantity, 0))

const subtotal = computed(() =>
  round3(cart.value.reduce((total, line) => total + line.price * line.quantity, 0)),
)

const estimatedTotal = computed(() => round3(subtotal.value + DELIVERY_FEE_ESTIMATE))

const stepTitle = computed(() => `Step ${step.value} of ${STEPS.length}: ${STEPS[step.value - 1]}`)

/** TND carries three decimals; summing in floating point would otherwise leak 8.300000000000001. */
function round3(value: number): number {
  return Math.round(value * 1000) / 1000
}

function isOutOfStock(product: ProductResponse): boolean {
  if (product.stockStatus === 'OUT_OF_STOCK') {
    return true
  }
  return product.availableQuantity !== undefined && product.availableQuantity <= 0
}

function isOrderable(product: ProductResponse): boolean {
  return product.active !== false && !isOutOfStock(product)
}

function ceilingFor(product: ProductResponse): number {
  if (product.availableQuantity === undefined) {
    return MAX_QUANTITY
  }
  return Math.min(MAX_QUANTITY, Math.max(product.availableQuantity, 1))
}

function quantityOf(productId: number): number {
  return cart.value.find((line) => line.productId === productId)?.quantity ?? 0
}

function addProduct(product: ProductResponse): void {
  const existing = cart.value.find((line) => line.productId === product.id)
  if (existing) {
    if (existing.quantity < existing.maxQuantity) {
      existing.quantity += 1
    }
    return
  }
  cart.value = [
    ...cart.value,
    {
      productId: product.id,
      name: product.name,
      price: product.price,
      quantity: 1,
      maxQuantity: ceilingFor(product),
    },
  ]
}

function setQuantity(productId: number, quantity: number): void {
  if (quantity < 1) {
    removeProduct(productId)
    return
  }
  cart.value = cart.value.map((line) =>
    line.productId === productId
      ? { ...line, quantity: Math.min(quantity, line.maxQuantity) }
      : line,
  )
}

function removeProduct(productId: number): void {
  cart.value = cart.value.filter((line) => line.productId !== productId)
}

function onSearchInput(value: string | number | null): void {
  catalog.search = String(value ?? '')
  queueSearch(catalog.search)
}

function onCategoryChange(value: string | number | null): void {
  catalog.category = value === null ? '' : String(value)
  catalog.page = 0
  void catalog.fetchProducts()
}

function goToCataloguePage(page: number): void {
  catalog.page = page
  void catalog.fetchProducts()
}

function clearCatalogueFilters(): void {
  catalog.resetFilters()
  void catalog.fetchProducts()
}

watch(debouncedSearch, () => {
  catalog.page = 0
  void catalog.fetchProducts()
})

function validateAddress(): boolean {
  addressErrors.deliveryAddress = address.deliveryAddress.trim()
    ? address.deliveryAddress.trim().length <= 255
      ? ''
      : 'Keep the address under 255 characters'
    : 'Enter the street address to deliver to'
  addressErrors.city = address.city.trim()
    ? address.city.trim().length <= 80
      ? ''
      : 'Keep the city under 80 characters'
    : 'Enter a city'
  addressErrors.postalCode = address.postalCode.trim()
    ? address.postalCode.trim().length <= 16
      ? ''
      : 'Keep the postal code under 16 characters'
    : 'Enter a postal code'

  return (
    !addressErrors.deliveryAddress && !addressErrors.city && !addressErrors.postalCode
  )
}

function next(): void {
  if (step.value === 2 && cart.value.length === 0) {
    reviewError.value = 'Add at least one product before continuing.'
    toasts.warning('Your basket is empty', 'Pick a product before reviewing the order.')
    return
  }
  if (step.value === 3 && !validateAddress()) {
    toasts.warning('Check the delivery address', 'Fill in the highlighted fields to continue.')
    return
  }
  reviewError.value = ''
  step.value = Math.min(step.value + 1, STEPS.length)
  window.scrollTo({ top: 0 })
}

function back(): void {
  reviewError.value = ''
  step.value = Math.max(step.value - 1, 1)
  window.scrollTo({ top: 0 })
}

async function placeOrder(): Promise<void> {
  if (cart.value.length === 0) {
    step.value = 1
    return
  }
  if (!validateAddress()) {
    step.value = 3
    return
  }

  placing.value = true
  placeError.value = null

  const created = await orderStore.createOrder({
    items: cart.value.map((line) => ({ productId: line.productId, quantity: line.quantity })),
    deliveryAddress: address.deliveryAddress.trim(),
    city: address.city.trim(),
    postalCode: address.postalCode.trim(),
  })

  placing.value = false

  if (!created) {
    // The store already raised a toast; the inline copy is what blocks the button.
    placeError.value =
      'The order could not be placed. Nothing has been charged — please check the basket and try again.'
    return
  }

  createdOrder.value = created
  cart.value = []
}

async function loadProfile(): Promise<void> {
  profileLoading.value = true
  profileError.value = null
  try {
    const profile = await customerApi.me()
    // Prefill only where the field is still empty, so a reloaded profile never overwrites typing.
    if (!address.deliveryAddress) {
      address.deliveryAddress = profile.address ?? ''
    }
    if (!address.city) {
      address.city = profile.city ?? ''
    }
    if (!address.postalCode) {
      address.postalCode = profile.postalCode ?? ''
    }
  } catch (caught) {
    profileError.value =
      caught instanceof ApiError
        ? caught.message
        : 'Your saved address could not be loaded. Type it in instead.'
  } finally {
    profileLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([catalog.fetchProducts(), catalog.fetchCategories()])
  void loadProfile()
})
</script>

<template>
  <div class="space-y-4">
    <header>
      <h2 class="page-title">New order</h2>
      <p class="mt-0.5 text-body text-content-muted dark:text-[#94A3B8]">
        Choose your products, confirm the address and we will deliver.
      </p>
    </header>

    <!-- Progress: the step number is never the only cue, the step name travels with it. -->
    <nav aria-label="Checkout progress">
      <p class="sr-only" aria-live="polite">{{ stepTitle }}</p>
      <ol class="flex items-center gap-1.5">
        <li
          v-for="(label, index) in STEPS"
          :key="label"
          class="flex min-w-0 flex-1 items-center gap-1.5"
          :aria-current="index + 1 === step ? 'step' : undefined"
        >
          <span
            class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full text-small font-semibold"
            :class="
              index + 1 < step
                ? 'bg-primary text-white'
                : index + 1 === step
                  ? 'bg-primary text-white ring-4 ring-primary/20'
                  : 'border border-edge bg-surface text-content-subtle dark:border-[#334155] dark:bg-[#111827] dark:text-[#64748B]'
            "
          >
            <svg
              v-if="index + 1 < step"
              class="h-3.5 w-3.5"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.5"
              aria-hidden="true"
            >
              <path d="m5 13 4 4 10-10" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
            <template v-else>{{ index + 1 }}</template>
          </span>
          <span
            class="hidden min-w-0 truncate text-small sm:block"
            :class="
              index + 1 === step
                ? 'font-medium text-content dark:text-[#F8FAFC]'
                : 'text-content-muted dark:text-[#94A3B8]'
            "
          >
            {{ label }}
          </span>
          <span
            v-if="index < STEPS.length - 1"
            class="h-px flex-1 bg-edge dark:bg-[#334155]"
            aria-hidden="true"
          />
        </li>
      </ol>
      <p class="mt-2 text-small text-content-muted sm:hidden dark:text-[#94A3B8]">
        Step {{ step }} of {{ STEPS.length }}: {{ STEPS[step - 1] }}
      </p>
    </nav>

    <!-- Success replaces the flow rather than sitting above it. -->
    <BaseCard v-if="createdOrder" as="section">
      <div class="py-4 text-center">
        <span
          class="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-emerald-50 text-success dark:bg-emerald-950"
          aria-hidden="true"
        >
          <svg class="h-6 w-6" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="m5 13 4 4 10-10" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </span>
        <h3 class="mt-3 text-section-title font-semibold text-content dark:text-[#F8FAFC]">
          Your order is in
        </h3>
        <p class="mt-1.5 text-body text-content-muted dark:text-[#94A3B8]">
          We have recorded order
          <span class="font-mono font-medium text-content dark:text-[#F8FAFC]">
            {{ orderLabel(createdOrder.id) }}
          </span>
          for
          {{ formatMoney(createdOrder.totalAmount, createdOrder.currency) }}. Pay the driver in
          cash when it arrives.
        </p>

        <div class="mx-auto mt-5 flex max-w-sm flex-col gap-2">
          <RouterLink
            :to="{ name: 'customer-order-detail', params: { id: createdOrder.id } }"
            class="inline-flex h-11 w-full items-center justify-center rounded-control bg-primary text-body font-medium text-white transition-colors duration-150 hover:bg-primary-dark"
          >
            View this order
          </RouterLink>
          <RouterLink
            :to="{ name: 'customer-dashboard' }"
            class="inline-flex h-11 w-full items-center justify-center rounded-control bg-surface text-body font-medium text-content ring-1 ring-inset ring-edge transition-colors duration-150 hover:bg-surface-muted dark:bg-[#111827] dark:text-[#F8FAFC] dark:ring-[#334155] dark:hover:bg-[#1E293B]"
          >
            Back to home
          </RouterLink>
        </div>
      </div>
    </BaseCard>

    <template v-else>
      <!-- Step 1: products -->
      <BaseCard v-if="step === 1" as="section">
        <template #title>
          <h2 class="card-title">Choose your products</h2>
          <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
            {{ pluralize(cartCount, 'item') }} in your basket
          </p>
        </template>

        <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <BaseInput
            :modelValue="catalog.search"
            label="Search products"
            type="search"
            inputmode="search"
            placeholder="Search by name"
            @update:model-value="onSearchInput"
          />
          <BaseSelect
            :modelValue="catalog.category"
            label="Category"
            placeholder="All categories"
            :options="categoryChoices"
            @update:model-value="onCategoryChange"
          />
        </div>

        <BaseSkeleton
          v-if="catalog.loading"
          class="mt-4"
          variant="cards"
          :rows="3"
          label="Loading the catalogue"
        />

        <ErrorState
          v-else-if="catalog.error"
          class="mt-4"
          :message="catalog.error"
          retryLabel="Try again"
          @retry="catalog.fetchProducts()"
        />

        <EmptyState
          v-else-if="catalog.products.length === 0"
          class="mt-2"
          title="No products match"
          description="Try a different search term or category."
          actionLabel="Clear the filters"
          icon="search"
          @action="clearCatalogueFilters"
        />

        <template v-else>
          <ul class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <li v-for="product in catalog.products" :key="product.id" class="card">
              <div class="card-body flex h-full flex-col">
                <div class="flex items-start justify-between gap-2">
                  <h3 class="card-title">{{ product.name }}</h3>
                  <StatusBadge
                    v-if="product.stockStatus && !isOrderable(product)"
                    :meta="stockStatus(product.stockStatus)"
                  />
                </div>

                <p class="mt-1 text-small text-content-muted dark:text-[#94A3B8]">
                  {{ product.category }}
                </p>

                <p class="mt-2 text-body font-semibold text-content dark:text-[#F8FAFC]">
                  {{ formatMoney(product.price, ESTIMATE_CURRENCY) }}
                </p>

                <p
                  v-if="product.description"
                  class="mt-1.5 line-clamp-2 text-small text-content-muted dark:text-[#94A3B8]"
                >
                  {{ product.description }}
                </p>

                <div class="mt-3 flex-1" />

                <div v-if="quantityOf(product.id) === 0" class="mt-3">
                  <BaseButton
                    variant="primary"
                    size="lg"
                    fullWidth
                    :disabled="!isOrderable(product)"
                    :aria-label="`Add ${product.name} to the basket`"
                    @click="addProduct(product)"
                  >
                    {{ isOrderable(product) ? 'Add to basket' : 'Unavailable' }}
                  </BaseButton>
                </div>

                <div v-else class="mt-3">
                  <p class="mb-1.5 text-small font-medium text-content dark:text-[#F8FAFC]">
                    {{ quantityOf(product.id) }} in basket
                  </p>
                  <div class="flex items-center gap-2">
                    <BaseButton
                      variant="secondary"
                      size="lg"
                      class="px-3"
                      :aria-label="`Remove one ${product.name} from the basket`"
                      @click="setQuantity(product.id, quantityOf(product.id) - 1)"
                    >
                      <template #icon="{ iconClass }">
                        <svg
                          :class="iconClass"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="2"
                          aria-hidden="true"
                        >
                          <path d="M5 12h14" stroke-linecap="round" />
                        </svg>
                      </template>
                    </BaseButton>
                    <p class="min-w-8 flex-1 text-center text-body font-semibold text-content dark:text-[#F8FAFC]">
                      {{ quantityOf(product.id) }}
                    </p>
                    <BaseButton
                      variant="secondary"
                      size="lg"
                      class="px-3"
                      :disabled="quantityOf(product.id) >= ceilingFor(product)"
                      :aria-label="`Add one more ${product.name} to the basket`"
                      @click="setQuantity(product.id, quantityOf(product.id) + 1)"
                    >
                      <template #icon="{ iconClass }">
                        <svg
                          :class="iconClass"
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="2"
                          aria-hidden="true"
                        >
                          <path d="M12 5v14M5 12h14" stroke-linecap="round" />
                        </svg>
                      </template>
                    </BaseButton>
                  </div>
                  <p
                    v-if="quantityOf(product.id) >= ceilingFor(product)"
                    class="mt-1.5 text-small text-warning"
                  >
                    That is all the stock available.
                  </p>
                </div>

                <p
                  v-if="product.active === false"
                  class="mt-2 text-small text-content-muted dark:text-[#94A3B8]"
                >
                  No longer on sale.
                </p>
              </div>
            </li>
          </ul>

          <TablePagination
            :page="catalog.page"
            :totalPages="catalog.totalPages"
            :totalElements="catalog.totalElements"
            :size="catalog.pageSize"
            label="products"
            @change="goToCataloguePage"
          />
        </template>

        <div class="mt-4 border-t border-edge pt-4 dark:border-[#334155]">
          <BaseButton
            variant="primary"
            size="lg"
            fullWidth
            @click="next"
          >
            Review order
          </BaseButton>
        </div>
      </BaseCard>

      <!-- Step 2: review -->
      <BaseCard v-else-if="step === 2" as="section">
        <template #title>
          <h2 class="card-title">Review your basket</h2>
          <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
            {{ pluralize(cartCount, 'item') }}
          </p>
        </template>

        <EmptyState
          v-if="cart.length === 0"
          title="Your basket is empty"
          description="Add at least one product before continuing to the delivery address."
          actionLabel="Back to products"
          icon="search"
          @action="back"
        />

        <template v-else>
          <ul class="divide-y divide-edge rounded-control border border-edge dark:divide-[#1E293B] dark:border-[#334155]">
            <li
              v-for="line in cart"
              :key="line.productId"
              class="flex flex-wrap items-center gap-3 px-4 py-3"
            >
              <div class="min-w-0 flex-1">
                <p class="truncate text-body font-medium text-content dark:text-[#F8FAFC]">
                  {{ line.name }}
                </p>
                <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
                  {{ formatMoney(line.price, ESTIMATE_CURRENCY) }} each ·
                  {{ formatMoney(line.price * line.quantity, ESTIMATE_CURRENCY) }}
                </p>
              </div>

              <div class="flex items-center gap-2">
                <BaseButton
                  variant="secondary"
                  size="sm"
                  class="px-2.5"
                  :aria-label="`Remove one ${line.name} from the basket`"
                  @click="setQuantity(line.productId, line.quantity - 1)"
                >
                  <template #icon="{ iconClass }">
                    <svg
                      :class="iconClass"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      aria-hidden="true"
                    >
                      <path d="M5 12h14" stroke-linecap="round" />
                    </svg>
                  </template>
                </BaseButton>
                <p class="min-w-7 text-center text-body font-semibold text-content dark:text-[#F8FAFC]">
                  {{ line.quantity }}
                </p>
                <BaseButton
                  variant="secondary"
                  size="sm"
                  class="px-2.5"
                  :disabled="line.quantity >= line.maxQuantity"
                  :aria-label="`Add one more ${line.name} to the basket`"
                  @click="setQuantity(line.productId, line.quantity + 1)"
                >
                  <template #icon="{ iconClass }">
                    <svg
                      :class="iconClass"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                      aria-hidden="true"
                    >
                      <path d="M12 5v14M5 12h14" stroke-linecap="round" />
                    </svg>
                  </template>
                </BaseButton>
                <BaseButton
                  variant="ghost"
                  size="sm"
                  :aria-label="`Remove ${line.name} from the basket`"
                  @click="removeProduct(line.productId)"
                >
                  Remove
                </BaseButton>
              </div>
            </li>
          </ul>

          <p
            v-if="reviewError"
            class="mt-3 rounded-control border border-red-200 bg-red-50 px-3 py-2 text-small text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
            role="alert"
          >
            {{ reviewError }}
          </p>

          <dl class="mt-4 ml-auto max-w-xs space-y-1.5">
            <div class="flex items-baseline justify-between gap-4">
              <dt class="text-body text-content-muted dark:text-[#94A3B8]">Subtotal</dt>
              <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">
                {{ formatMoney(subtotal, ESTIMATE_CURRENCY) }}
              </dd>
            </div>
          </dl>
        </template>

        <div class="mt-5 flex flex-col-reverse gap-2 border-t border-edge pt-4 sm:flex-row dark:border-[#334155]">
          <BaseButton variant="secondary" size="lg" fullWidth @click="back">
            Back to products
          </BaseButton>
          <BaseButton variant="primary" size="lg" fullWidth @click="next">
            Continue to address
          </BaseButton>
        </div>
      </BaseCard>

      <!-- Step 3: address -->
      <BaseCard v-else-if="step === 3" as="section">
        <template #title>
          <h2 class="card-title">Delivery address</h2>
          <p class="mt-0.5 text-small text-content-muted dark:text-[#94A3B8]">
            Where should we drop the order off?
          </p>
        </template>

        <BaseSkeleton
          v-if="profileLoading"
          variant="lines"
          :rows="4"
          label="Loading your saved address"
        />

        <div
          v-else-if="profileError"
          class="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-control border border-amber-200 bg-amber-50 px-3 py-2.5 dark:border-amber-800 dark:bg-amber-950"
          role="alert"
        >
          <p class="text-small text-amber-800 dark:text-amber-300">
            {{ profileError }}
          </p>
          <BaseButton size="sm" variant="secondary" @click="loadProfile">Retry</BaseButton>
        </div>

        <div class="space-y-4">
          <BaseInput
            v-model="address.deliveryAddress"
            label="Street address"
            placeholder="12 Rue Habib Bourguiba"
            autocomplete="street-address"
            required
            :error="addressErrors.deliveryAddress"
          />
          <div class="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <BaseInput
              v-model="address.city"
              label="City"
              placeholder="Tunis"
              autocomplete="address-level2"
              required
              :error="addressErrors.city"
            />
            <BaseInput
              v-model="address.postalCode"
              label="Postal code"
              placeholder="1000"
              autocomplete="postal-code"
              inputmode="numeric"
              required
              :error="addressErrors.postalCode"
            />
          </div>
        </div>

        <div class="mt-5 flex flex-col-reverse gap-2 border-t border-edge pt-4 sm:flex-row dark:border-[#334155]">
          <BaseButton variant="secondary" size="lg" fullWidth @click="back">
            Back to review
          </BaseButton>
          <BaseButton variant="primary" size="lg" fullWidth @click="next">
            Continue to confirm
          </BaseButton>
        </div>
      </BaseCard>

      <!-- Step 4: confirm -->
      <BaseCard v-else as="section">
        <template #title>
          <h2 class="card-title">Confirm and place the order</h2>
        </template>

        <section aria-labelledby="checkout-address-recap">
          <h3 id="checkout-address-recap" class="text-small font-semibold text-content dark:text-[#F8FAFC]">
            Delivering to
          </h3>
          <address class="mt-1 text-body not-italic text-content-muted dark:text-[#94A3B8]">
            {{ address.deliveryAddress }}<br />
            {{ address.city }} {{ address.postalCode }}
          </address>
        </section>

        <section class="mt-5" aria-labelledby="checkout-items-recap">
          <h3 id="checkout-items-recap" class="text-small font-semibold text-content dark:text-[#F8FAFC]">
            {{ pluralize(cartCount, 'item') }}
          </h3>
          <ul class="mt-1.5 divide-y divide-edge rounded-control border border-edge dark:divide-[#1E293B] dark:border-[#334155]">
            <li
              v-for="line in cart"
              :key="line.productId"
              class="flex items-center justify-between gap-3 px-4 py-2.5 text-body"
            >
              <span class="min-w-0 truncate text-content dark:text-[#F8FAFC]">
                {{ line.quantity }} × {{ line.name }}
              </span>
              <span class="shrink-0 text-content-muted dark:text-[#94A3B8]">
                {{ formatMoney(line.price * line.quantity, ESTIMATE_CURRENCY) }}
              </span>
            </li>
          </ul>
        </section>

        <dl class="mt-5 ml-auto max-w-xs space-y-1.5">
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-body text-content-muted dark:text-[#94A3B8]">Subtotal</dt>
            <dd class="text-body text-content dark:text-[#F8FAFC]">
              {{ formatMoney(subtotal, ESTIMATE_CURRENCY) }}
            </dd>
          </div>
          <div class="flex items-baseline justify-between gap-4">
            <dt class="text-body text-content-muted dark:text-[#94A3B8]">Delivery</dt>
            <dd class="text-body text-content dark:text-[#F8FAFC]">
              {{ formatMoney(DELIVERY_FEE_ESTIMATE, ESTIMATE_CURRENCY) }}
            </dd>
          </div>
          <div class="flex items-baseline justify-between gap-4 border-t border-edge pt-1.5 dark:border-[#334155]">
            <dt class="text-body font-semibold text-content dark:text-[#F8FAFC]">Estimated total</dt>
            <dd class="text-body font-semibold text-content dark:text-[#F8FAFC]">
              {{ formatMoney(estimatedTotal, ESTIMATE_CURRENCY) }}
            </dd>
          </div>
        </dl>

        <p class="mt-2 text-small text-content-muted dark:text-[#94A3B8]">
          The final total is recalculated and confirmed by the server when the order is placed.
        </p>

        <section
          class="mt-5 rounded-card border border-edge p-4 dark:border-[#334155]"
          aria-labelledby="checkout-payment"
        >
          <h3 id="checkout-payment" class="card-title">Payment method</h3>
          <p class="mt-1 text-body text-content dark:text-[#F8FAFC]">
            Cash on Delivery — pay the driver in cash when your order arrives. No card details
            are collected now.
          </p>
        </section>

        <p
          v-if="placeError"
          class="mt-4 rounded-control border border-red-200 bg-red-50 px-3 py-2.5 text-small text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300"
          role="alert"
        >
          {{ placeError }}
        </p>

        <div class="mt-5 flex flex-col-reverse gap-2 border-t border-edge pt-4 sm:flex-row dark:border-[#334155]">
          <BaseButton variant="secondary" size="lg" fullWidth :disabled="placing" @click="back">
            Back to address
          </BaseButton>
          <BaseButton
            variant="primary"
            size="lg"
            fullWidth
            :loading="placing"
            loadingLabel="Placing your order…"
            @click="placeOrder"
          >
            Place order
          </BaseButton>
        </div>
      </BaseCard>
    </template>
  </div>
</template>