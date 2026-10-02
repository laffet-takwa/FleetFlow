import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { authApi } from '@/services/authApi'
import { ApiError, readToken, setUnauthorizedHandler, writeToken } from '@/services/api'
import type { Role, UserResponse } from '@/types'

/**
 * Session state.
 *
 * The token is kept in `localStorage` rather than in a cookie because the API is
 * stateless and token-based; the gateway, not the browser, decides what is reachable.
 * The trade-off is that an XSS bug can read it, which is why the backend keeps every
 * authorisation decision server-side and the CSP forbids inline script.
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserResponse | null>(null)
  const accessToken = ref<string | null>(readToken())
  const initialising = ref(false)
  const sessionChecked = ref(false)

  const isAuthenticated = computed(() => Boolean(accessToken.value))
  const role = computed<Role | null>(() => user.value?.role ?? null)
  const displayName = computed(() =>
    user.value ? `${user.value.firstName} ${user.value.lastName}`.trim() : '',
  )
  const initials = computed(() => {
    if (!user.value) {
      return '?'
    }
    return `${user.value.firstName.charAt(0)}${user.value.lastName.charAt(0)}`.toUpperCase()
  })

  function is(...roles: Role[]): boolean {
    return role.value !== null && roles.includes(role.value)
  }

  const isStaff = computed(() => is('ADMIN', 'OPERATIONS'))
  const isDriver = computed(() => is('DRIVER'))
  const isCustomer = computed(() => is('CUSTOMER'))

  function applySession(response: { accessToken: string; user: UserResponse }): void {
    accessToken.value = response.accessToken
    user.value = response.user
    sessionChecked.value = true
    writeToken(response.accessToken)
  }

  async function login(email: string, password: string): Promise<void> {
    applySession(await authApi.login(email, password))
  }

  async function register(payload: Parameters<typeof authApi.register>[0]): Promise<void> {
    applySession(await authApi.register(payload))
  }

  function clear(): void {
    accessToken.value = null
    user.value = null
    sessionChecked.value = true
    writeToken(null)
  }

  /**
   * Restores the session after a page reload. A token that the server rejects is
   * discarded here rather than leaving the UI in a half-signed-in state.
   */
  async function restore(): Promise<void> {
    if (!accessToken.value || user.value || sessionChecked.value) {
      sessionChecked.value = true
      return
    }
    initialising.value = true
    try {
      user.value = await authApi.me()
    } catch (error) {
      if (error instanceof ApiError && error.isUnauthorized) {
        clear()
      }
      // A transient failure keeps the token: the next navigation can retry, and
      // signing the user out because the network blipped would be worse.
    } finally {
      initialising.value = false
      sessionChecked.value = true
    }
  }

  // The API layer must not depend on Pinia, so the sign-out hook is wired here.
  setUnauthorizedHandler(() => {
    clear()
  })

  return {
    user,
    accessToken,
    initialising,
    sessionChecked,
    isAuthenticated,
    role,
    displayName,
    initials,
    isStaff,
    isDriver,
    isCustomer,
    is,
    login,
    register,
    clear,
    restore,
  }
})