import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/authStore'
import { useNotificationStore } from '@/stores/notificationStore'
import type { Role } from '@/types'

declare module 'vue-router' {
  interface RouteMeta {
    /** Roles allowed to open the route. Absent means any authenticated user. */
    roles?: Role[]
    /** Reachable without a session (login, register, error pages). */
    public?: boolean
    title?: string
    /** Which shell renders the screen. */
    layout?: 'admin' | 'customer' | 'driver' | 'blank'
    /** Used by the header breadcrumb. */
    breadcrumb?: string[]
    /** The layout the user is redirected to after signing in. */
    homeFor?: Role[]
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true, layout: 'blank', title: 'Sign in' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { public: true, layout: 'blank', title: 'Create an account' },
  },

  // ---------------------------------------------------------- operations
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { roles: ['ADMIN', 'OPERATIONS'], layout: 'admin' },
    children: [
      {
        path: '',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
        meta: { title: 'Dashboard', breadcrumb: ['Operations', 'Dashboard'] },
      },
      {
        path: 'orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/OrdersView.vue'),
        meta: { title: 'Orders', breadcrumb: ['Operations', 'Orders'] },
      },
      {
        path: 'orders/:id',
        name: 'admin-order-detail',
        component: () => import('@/views/admin/OrderDetailView.vue'),
        meta: { title: 'Order detail', breadcrumb: ['Operations', 'Orders', 'Detail'] },
      },
      {
        path: 'inventory',
        name: 'admin-inventory',
        component: () => import('@/views/admin/InventoryView.vue'),
        meta: { title: 'Inventory', breadcrumb: ['Operations', 'Inventory'] },
      },
      {
        path: 'warehouses',
        name: 'admin-warehouses',
        component: () => import('@/views/admin/WarehousesView.vue'),
        meta: { title: 'Warehouses', breadcrumb: ['Operations', 'Warehouses'] },
      },
      {
        path: 'drivers',
        name: 'admin-drivers',
        component: () => import('@/views/admin/DriversView.vue'),
        meta: { title: 'Drivers', breadcrumb: ['Operations', 'Drivers'] },
      },
      {
        path: 'vehicles',
        name: 'admin-vehicles',
        component: () => import('@/views/admin/VehiclesView.vue'),
        meta: { title: 'Vehicles', breadcrumb: ['Operations', 'Vehicles'] },
      },
      {
        path: 'deliveries',
        name: 'admin-deliveries',
        component: () => import('@/views/admin/DeliveriesView.vue'),
        meta: { title: 'Deliveries', breadcrumb: ['Operations', 'Deliveries'] },
      },
      {
        path: 'tracking',
        name: 'admin-tracking',
        component: () => import('@/views/admin/LiveTrackingView.vue'),
        meta: { title: 'Live tracking', breadcrumb: ['Operations', 'Live tracking'] },
      },
      {
        path: 'analytics',
        name: 'admin-analytics',
        component: () => import('@/views/admin/AnalyticsView.vue'),
        meta: { title: 'Analytics', breadcrumb: ['Operations', 'Analytics'] },
      },
      {
        path: 'settings',
        name: 'admin-settings',
        component: () => import('@/views/admin/SettingsView.vue'),
        meta: { title: 'Settings', breadcrumb: ['Operations', 'Settings'] },
      },
    ],
  },

  // ------------------------------------------------------------- customer
  {
    path: '/customer',
    component: () => import('@/layouts/CustomerLayout.vue'),
    meta: { roles: ['CUSTOMER'], layout: 'customer' },
    children: [
      {
        path: '',
        name: 'customer-dashboard',
        component: () => import('@/views/customer/CustomerDashboardView.vue'),
        meta: { title: 'Home', breadcrumb: ['Home'] },
      },
      {
        path: 'orders',
        name: 'customer-orders',
        component: () => import('@/views/customer/CustomerOrdersView.vue'),
        meta: { title: 'My orders', breadcrumb: ['My orders'] },
      },
      {
        path: 'orders/:id',
        name: 'customer-order-detail',
        component: () => import('@/views/customer/CustomerOrderDetailView.vue'),
        meta: { title: 'Order detail', breadcrumb: ['My orders', 'Detail'] },
      },
      {
        path: 'checkout',
        name: 'customer-checkout',
        component: () => import('@/views/customer/CheckoutView.vue'),
        meta: { title: 'New order', breadcrumb: ['New order'] },
      },
      {
        path: 'tracking/:deliveryId',
        name: 'customer-tracking',
        component: () => import('@/views/customer/CustomerTrackingView.vue'),
        meta: { title: 'Track delivery', breadcrumb: ['Tracking'] },
      },
      {
        path: 'profile',
        name: 'customer-profile',
        component: () => import('@/views/customer/CustomerProfileView.vue'),
        meta: { title: 'Profile', breadcrumb: ['Profile'] },
      },
    ],
  },

  // --------------------------------------------------------------- driver
  {
    path: '/driver',
    component: () => import('@/layouts/DriverLayout.vue'),
    meta: { roles: ['DRIVER'], layout: 'driver' },
    children: [
      {
        path: '',
        name: 'driver-dashboard',
        component: () => import('@/views/driver/DriverDashboardView.vue'),
        meta: { title: 'Today', breadcrumb: ['Today'] },
      },
      {
        path: 'deliveries',
        name: 'driver-deliveries',
        component: () => import('@/views/driver/DriverDeliveriesView.vue'),
        meta: { title: 'Deliveries', breadcrumb: ['Deliveries'] },
      },
      {
        path: 'deliveries/:id',
        name: 'driver-delivery-detail',
        component: () => import('@/views/driver/DriverDeliveryDetailView.vue'),
        meta: { title: 'Delivery', breadcrumb: ['Deliveries', 'Detail'] },
      },
      {
        path: 'notifications',
        name: 'driver-notifications',
        component: () => import('@/views/driver/DriverNotificationsView.vue'),
        meta: { title: 'Notifications', breadcrumb: ['Notifications'] },
      },
      {
        path: 'profile',
        name: 'driver-profile',
        component: () => import('@/views/driver/DriverProfileView.vue'),
        meta: { title: 'Profile', breadcrumb: ['Profile'] },
      },
    ],
  },

  // ---------------------------------------------------------------- shared
  {
    path: '/notifications',
    name: 'notifications',
    // Wrapped rather than nested: the router resolves layouts through nesting, and
    // this route must render inside whichever shell the caller's role uses.
    component: () => import('@/views/shared/NotificationsShell.vue'),
    meta: { title: 'Notifications', breadcrumb: ['Notifications'] },
  },
  {
    path: '/403',
    name: 'forbidden',
    component: () => import('@/views/shared/ForbiddenView.vue'),
    meta: { layout: 'blank', public: true, title: 'Access denied' },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/shared/NotFoundView.vue'),
    meta: { layout: 'blank', public: true, title: 'Page not found' },
  },
]

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: (_to, _from, saved) => saved ?? { top: 0 },
})

/** Where a user lands after signing in, based on their role. */
export function homeRouteFor(roles: Role[]): string {
  if (roles.includes('ADMIN') || roles.includes('OPERATIONS')) {
    return '/admin'
  }
  if (roles.includes('DRIVER')) {
    return '/driver'
  }
  return '/customer'
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (!auth.sessionChecked) {
    await auth.restore()
  }

  const isPublic = to.meta.public === true

  if (!auth.isAuthenticated) {
    if (isPublic) {
      return true
    }
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.name === 'login' || to.name === 'register') {
    return { path: homeRouteFor([auth.role ?? 'CUSTOMER']) }
  }

  const allowed = to.meta.roles
  if (allowed && allowed.length > 0) {
    // `to.matched` covers the parent layout's roles when the child declares none.
    const required = to.matched.flatMap((record) => record.meta.roles ?? [])
    if (required.length > 0 && !required.some((role) => auth.is(role))) {
      return { name: 'forbidden', query: { from: to.fullPath } }
    }
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} · FleetFlow` : 'FleetFlow'
})

/** Subscribes the signed-in user to the notification stream once per session. */
export function startNotificationStream(): void {
  const auth = useAuthStore()
  const notifications = useNotificationStore()
  if (auth.isAuthenticated) {
    void notifications.refreshUnreadCount()
    notifications.subscribe()
  }
}

export default router