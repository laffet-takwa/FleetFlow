# FleetFlow Frontend — Design & Engineering Contract

Binding for every view in this application. Read this before writing a screen.
The foundation (tokens, primitives, stores, API layer, router, layouts, map) is
already implemented — **use it, do not reinvent it**.

Stack: Vue 3.5 `<script setup>` + TypeScript + Vite + Pinia + Vue Router + Tailwind CSS 3 + Leaflet.

---

## 1. What already exists — use it

### Tokens and base CSS (`src/styles/main.css`)

Utility classes: `card`, `card-body`, `page-title`, `section-title`, `card-title`, `muted`, `skeleton`.
Tailwind colours: `primary` (`#2563EB`, `.dark` `primary-dark` `#1D4ED8`), `surface`, `surface.muted`,
`content` (+ `muted` `#64748B`, `subtle` `#94A3B8`), `edge` (`#E2E8F0`), `success` `#16A34A`,
`warning` `#F59E0B`, `danger` `#DC2626`, `info` `#0EA5E9`.
Typography: `text-page-title` (24/28), `text-section-title` (18/24), `text-card-title` (15/20),
`text-body` (14/20), `text-small` (12/16). Radii: `rounded-card` (12px), `rounded-control` (8px).
Spacing is the Tailwind default, which is already an 8px-centred scale (4/8/12/16/24/32/40/48).

Dark mode is `dark:` prefixed and toggled by `components/layout/ThemeToggle.vue`. **Every custom
colour needs a `dark:` counterpart** — a hard-coded `#F8FAFC` in dark mode is a bug.

### Primitives (`src/components/ui/`)

| Component | Use it for |
|---|---|
| `BaseButton` | every button. Props: `variant` (`primary`\|`secondary`\|`ghost`\|`danger`), `size` (`sm`\|`md`\|`lg`\|`touch`), `loading`, `loadingLabel`, `fullWidth`. `#icon` slot. **`touch` size is mandatory on driver screens.** |
| `BaseCard` | every panel. Props: `title`, `subtitle`, `padded` (default true), `as`. `#actions`, `#title` slots. |
| `BaseInput` | text-like inputs. Props: `modelValue`, `label`, `type`, `hint`, `error`, `required`. Wire `error` from the API's per-field violations. |
| `BaseSelect` | every dropdown, including filters. Props: `modelValue`, `label`, `options: {value,label,disabled}[]`, `placeholder`, `clearable`. |
| `StatusBadge` | every status. Props: `meta` (from `@/utils/status`), `size`, `suffix`. |
| `DataTable` | every list/table. Props: `columns`, `rows`, `rowKey`, `loading`, `error`, `empty*`, `clickable`. Cell slots are `#cell-<columnKey>`. It already renders loading / error / empty states — **never write those states by hand.** |
| `TablePagination` | pagination footer. Props: `page`, `totalPages`, `totalElements`, `size`, `label`. |
| `BaseSkeleton` | loading placeholders. Variants `table`\|`cards`\|`lines`. |
| `EmptyState` | successful load with no data. Props: `title`, `description`, `actionLabel`, `icon` (`orders`\|`deliveries`\|`inventory`\|`notifications`\|`search`). |
| `ErrorState` | failed request. Props: `message`, `correlationId`, `retryLabel`. |
| `ConfirmModal` | every destructive action. Props: `open`, `title`, `description`, `confirmLabel`, `cancelLabel`, `tone`, `busy`. |
| `StatCard` | KPI tiles. Props: `label`, `value`, `hint`, `icon` (`orders`\|`truck`\|`check`\|`clock`\|`alert`\|`users`), `tone`, `loading`. |
| `ToastHost` | already mounted in `App.vue`. Drive it through the store. |

### Layout (`src/components/layout/`, `src/layouts/`)

`AppHeader.vue`, `AppSidebar.vue`, `NotificationBell.vue`, `ThemeToggle.vue`,
`layouts/AdminLayout.vue`, `layouts/CustomerLayout.vue`, `layouts/DriverLayout.vue`.
**Views are just page content.** Never build a sidebar, header, toast host or nav inside a view.

### Map (`src/components/map/TrackingMap.vue`)

Props: `locations`, `destination`, `follow`, `height`, `loading`. Emits `ready`.
Exposes `fitAll()` via `defineExpose`. Use it on every map screen.

### Stores (`src/stores/`)

`authStore`, `orderStore` (+ `useCatalogStore`), `inventoryStore`, `deliveryStore`,
`trackingStore`, `notificationStore`, `toastStore`, `asyncStore`.

**A view holds no business state.** Filters, pagination and fetched collections belong in the
store; the view renders them and dispatches actions. Local `ref`s are only for genuinely
screen-local things (which modal is open, the checkout step, a form buffer).

### API (`src/services/`)

`authApi`, `customerApi`, `orderApi`, `productApi`, `inventoryApi` (+ `warehouseApi`),
`deliveryApi` (+ `driverApi`, `vehicleApi`), `trackingApi`, `notificationApi`, `sse`.
Types in `@/types`. All routes go through the gateway at `/api`.

### Utilities

`@/utils/format` — `formatMoney`, `formatDate`, `formatDateTime`, `formatTime`, `formatRelative`,
`formatNumber`, `pluralize`, `initialsOf`, `orderLabel`, `deliveryLabel`, `toDateInput`, `distanceKm`.
`@/utils/status` — `orderStatus`, `deliveryStatus`, `driverStatus`, `vehicleStatus`, `stockStatus`,
`notificationLevel` (each returns `StatusMeta {label, tone, glyph}`), `toneClasses`, `toneDotClass`,
`isCancellable(status)`, `nextDriverActions(status)`.

**Never render a raw enum constant.** `{{ order.status }}` is a bug; `orderStatus(order.status).label` is right.

---

## 2. Design rules — this is what reviewers notice

**Avoid, all of it:** excessive gradients, glassmorphism, oversized rounded corners, neon colours,
random per-card colours, giant icons, heavy shadows, animation on every component, fake metrics,
a chart that does not correspond to real data, a decorative map.

**Always:** 1px borders to separate surfaces (shadows only for overlays), consistent 20px card
padding, a single accent colour, restrained motion (page fade, skeleton shimmer, toast, modal,
sidebar, marker movement — nothing else).

**Status is never colour alone.** `StatusBadge` already ships a glyph plus a label; keep both.

**Every data view handles four states:** loading (skeleton), error (`ErrorState` + Retry),
empty (`EmptyState` with a next action), loaded. A screen may not show an empty panel that means
"still loading".

**Toasts** for confirmations (`toasts.success(...)`) and failures (`toasts.fromException(...)`).
Errors that block an action also need an inline message near the control.

**Destructive actions always confirm** with `ConfirmModal` (cancel order, cancel delivery,
delete notification, manual status rollback).

**Money** via `formatMoney`. **Dates** via `formatDate`/`formatDateTime`/`formatRelative`.

### Responsive

- ≥1280px desktop, 768–1279px tablet, <768px mobile.
- Tables become stacked cards below `md`. Do not let a table cause horizontal page scroll —
  `DataTable` scrolls internally, and the card layout replaces it on small screens.
- Filter bars collapse behind a "Filters" toggle below `lg`.
- Dashboard KPI grids: `grid-cols-1 sm:grid-cols-2 xl:grid-cols-3`.
- Buttons become `full-width` where the flow is linear (checkout, driver actions).

### Accessibility

Semantic HTML first (`table`, `nav`, `main`, `button`, `label`). Every icon-only control needs
`aria-label`. Icon-only SVG needs `aria-hidden="true"`. Modals need `role="dialog"` +
`aria-modal` (done for you). Visible focus is global. Keyboard users must be able to reach and
operate every action, including table row drill-down (`DataTable` handles this when `clickable`).

---

## 3. Per-surface requirements

### Auth (`views/auth/`) — agent A
- `LoginView`: email + password, submit loading state, inline validation, error message from
  the API (never a stack trace), link to register, and a **demo credentials panel** listing the
  four seeded accounts with a click-to-fill button for each.
- `RegisterView`: first/last name, email, phone, password, address. Client-side validation
  mirroring the server rules (password ≥8 with a letter and a digit, phone `^\+?[0-9]{6,20}$`).
  On success route to the role home via `homeRouteFor([role])` from `@/router`.

### Shared (`views/shared/`) — agent A
- `ForbiddenView` (`/403`): explains the role requirement, shows who the user is, links home.
- `NotFoundView`: 404 with a route back.
- `NotificationsView` (`/notifications`): list + unread filter + "Mark all as read" + delete,
  paging, and a clear empty state. Reachable from every role.

### Operations (`views/admin/`) — agent B
Routes: `/admin` (dashboard), `/orders`, `/orders/:id`, `/inventory`, `/warehouses`, `/drivers`,
`/vehicles`, `/deliveries`, `/tracking`, `/analytics`, `/settings`.

- **Dashboard**: `StatCard` row from `orderApi.kpi()`, `deliveryApi.kpi()`, `inventoryApi.summary()`
  — total orders, active deliveries, delivered today, pending orders, low-stock products,
  available drivers. Then an active-deliveries list and a compact real-data chart. **Every number
  comes from an API response.** Load the KPI endpoints in parallel and let each card show its own
  skeleton rather than blocking the page on one slow endpoint.
- **Orders**: `DataTable` with search (order id or customer), status filter, date range,
  pagination, sort. Columns: Order, Customer, Items, Amount, Status, Created, Delivery, Actions.
  `OrderResponse` has `customerId`, not a name: resolve names by fetching the customer list once
  with `customerApi.list({size:100})` and building a `Map<number, string>` for the page. Do not
  fetch per row. A `search` value already in `route.query.search` must prefill the input.
- **Order detail**: customer, items table, subtotal/delivery/total, delivery block, and the
  `timeline` array rendered as a vertical stepper with done/current/pending states.
  Cancel is behind `ConfirmModal` and only when `isCancellable(status)`.
  Staff status advance uses `PATCH /orders/{id}/status` with a confirm.
- **Inventory**: `DataTable` with low stock visible immediately. Filters: warehouse, stock status,
  category, search. Status badges must make LOW_STOCK and OUT_OF_STOCK obvious. An adjust action
  opens a modal posting `inventoryApi.adjust(id, delta, reason)`.
- **Warehouses**: list of warehouses with capacity and distinct product count; create/edit forms.
- **Drivers**: filters All / Available / On delivery / Offline, search, per-row current delivery,
  vehicle and completed count. An "Assign" action jumps to the deliveries screen filtered by driver.
- **Vehicles**: registration, type, capacity, driver, status; create/edit.
- **Deliveries**: active deliveries with columns Delivery, Order, Driver, Vehicle, Status, Started,
  Destination, Actions. Assign driver + vehicle in one modal, using only `assignableDrivers` /
  `assignableVehicles` (already filtered to AVAILABLE). Cancel behind a confirm.
- **Live tracking** (`/admin/tracking`): full-width `TrackingMap` fed by `trackingStore.active`
  plus per-delivery SSE. Left panel lists active deliveries; clicking one centres the map and shows
  driver, status and last update. Must render sensibly on mobile (panel above map).
- **Analytics**: line chart (orders per day), bar chart (delivered vs cancelled per day), doughnut
  (status mix). Build the charts as inline SVG components from real data — **do not add a chart
  library**; the dependency list is fixed. Days with zero activity must appear as zero, not a gap.
- **Settings**: theme toggle, notification of demo mode, API base URL read-only display, sign out.

### Customer (`views/customer/`) — agent C
Routes: `/customer` (dashboard), `/orders`, `/orders/:id`, `/checkout`, `/tracking/:deliveryId`, `/profile`.

- **Dashboard**: welcome, the active delivery with a "Track delivery" button linking to
  `/customer/tracking/{deliveryId}` (order responses carry `deliveryId` once assigned), and recent orders.
- **Checkout**: four explicit steps — 1 Products, 2 Review, 3 Address, 4 Confirm — with a visible
  progress indicator and the ability to go back. Payment is `Cash on Delivery`, stated as such, with
  no payment UI. Products come from `useCatalogStore`; the review step recomputes the server's
  pricing rules locally for display only (the server recalculates authoritatively). Validate a
  non-empty cart before advancing. On success show the created order and clear the cart.
- **Orders**: cards (not a table) with order id, date, item count, amount, `StatusBadge`, and
  Track / View actions. Track only when `deliveryId` exists.
- **Order detail**: items, totals, address, timeline, cancel when `isCancellable`.
- **Tracking** (`/customer/tracking/:deliveryId`): "Back to orders", order id, status, `TrackingMap`,
  a delivery-info panel (driver, vehicle, status, connection status, last updated), and the
  timeline. Subscribe with `trackingStore.subscribe(id)` and `unsubscribe()` in `onBeforeUnmount`.
  Show connection state from `trackingStore.connection` and staleness from `isStale`.
- **Profile**: `GET /customers/me`, edit form, `PUT /customers/me`, password hint that passwords are
  managed in the auth service.

### Driver (`views/driver/`) — agent D
Routes: `/driver` (dashboard), `/deliveries`, `/deliveries/:id`, `/notifications`, `/profile`.

- **Mobile-first, high contrast, large touch targets** (`BaseButton size="touch"` for primary actions).
  Minimal navigation — the shell already provides it.
- **Dashboard**: "Good morning, {name}", current status control (`AVAILABLE` / `OFFLINE` via
  `deliveryStore.setDriverStatus`), the active delivery card with an "Open delivery" button, and
  today's summary (deliveries, completed, active) computed from `deliveryStore.myDeliveries`.
- **Delivery detail**: a large current-status indicator, customer block (name, address, phone as a
  `tel:` link), items, vehicle, and a primary action from `nextDriverActions(status)` using
  `size="touch"`. Secondary actions: Call customer (`tel:`), View map, Report problem.
  Every transition goes through `ConfirmModal` and `deliveryStore.changeStatus`.
- **Location simulation** (dev convenience, must exist because there is no GPS hardware):
  a clearly labelled **"DEMO SIMULATION"** banner, a Start/Stop control, and a configurable update
  interval defaulting to **3 seconds**. It generates a smooth sequence of coordinates from the last
  known position toward the destination (add a small random jitter so the route looks organic) and
  posts each one with `trackingApi.publishLocation`. Keep the timer in the view and clear it on
  unmount. Never label it as real GPS.
- **Deliveries**: cards grouped into Active / Completed, each opening the detail screen.
- **Profile**: driver record from `driverApi.me()` — name, licence, phone — plus status control
  and sign out. No profile editing: the driver record is owned by operations.

---

## 4. Definition of done

- `npm run typecheck` passes. `tsconfig.app.json` sets `strict`, `noUnusedLocals`,
  `noUnusedParameters`, `noImplicitOverride` and `verbatimModuleSyntax`, so an unused import or a
  non-`import type` of a type will fail the build. Use `import type { X } from '...'` for types.
- `npm run build` succeeds.
- Every view you write handles loading, error, empty and loaded.
- No new npm dependency. No `console.log` left behind. No `any` unless genuinely unavoidable,
  and then with a one-line comment saying why.
- `<script setup lang="ts">` only. No Options API.
- Comments explain *why*, sparsely. Do not narrate the template.
- Icons: inline SVG, 1.8 stroke width, `aria-hidden="true"` when decorative, 18–20px in the sidebar,
  never larger than 24px in content.