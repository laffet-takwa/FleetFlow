import axios, {
  AxiosError,
  AxiosInstance,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios'

import type { ApiErrorBody } from '@/types'

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'

const TOKEN_STORAGE_KEY = 'fleetflow.token'

/**
 * A failure the UI can render. Carries the platform's error contract so a screen can
 * show the server's own wording instead of inventing one, and so the correlation id is
 * available for a support request.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly violations: { field: string; message: string }[]

  constructor(body: ApiErrorBody, fallbackMessage = 'The request could not be completed') {
    super(body.message || fallbackMessage)
    this.name = 'ApiError'
    this.status = body.status ?? 0
    this.code = body.error ?? 'UNKNOWN'
    this.correlationId = body.correlationId
    this.violations = body.violations ?? []
  }

  /** True when the request never reached the platform: offline, DNS, gateway down. */
  get isNetworkError(): boolean {
    return this.status === 0
  }

  get isUnauthorized(): boolean {
    return this.status === 401
  }

  get isForbidden(): boolean {
    return this.status === 403
  }

  get isNotFound(): boolean {
    return this.status === 404
  }

  get isConflict(): boolean {
    return this.status === 409
  }

  /** Field-level message for inline form errors, keyed by the server's field name. */
  violationFor(field: string): string | undefined {
    return this.violations.find((violation) => violation.field === field)?.message
  }
}

/**
 * Listeners invoked when the session turns out to be unusable. Registered by the auth
 * store so this module stays free of any dependency on Pinia.
 */
type UnauthorizedHandler = () => void
let onUnauthorized: UnauthorizedHandler | null = null

export function setUnauthorizedHandler(handler: UnauthorizedHandler): void {
  onUnauthorized = handler
}

export function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_STORAGE_KEY)
  } catch {
    return null
  }
}

export function writeToken(token: string | null): void {
  try {
    if (token) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token)
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  } catch {
    /* Storage denied (private mode): the session simply will not survive a reload. */
  }
}

/** Per-request overrides that must not leak into the next call. */
interface RequestOptions {
  signal?: AbortSignal
  /** Skips the global 401 handler; used by the login screen itself. */
  skipAuthFailureHandler?: boolean
}

export const http: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 20_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = readToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  // A correlation id generated per call is what lets a single user action be traced
  // through the gateway, the services it touches and the Kafka events it produces.
  config.headers.set('X-Correlation-ID', newCorrelationId())
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => response,
  (error: AxiosError<ApiErrorBody>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const apiError = new ApiError(
      body ?? {},
      status === 0 ? 'Unable to reach the FleetFlow API. Check your connection.' : 'The request failed',
    )

    if (apiError.isUnauthorized && !error.config?.__skipAuthFailureHandler) {
      onUnauthorized?.()
    }
    return Promise.reject(apiError)
  },
)

declare module 'axios' {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  export interface AxiosRequestConfig {
    /** Opt out of the global sign-out handler; the login screen needs this. */
    __skipAuthFailureHandler?: boolean
  }
}

export function newCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `web-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** Query parameters for a paginated endpoint, dropping empty values. */
export function toParams<T extends Record<string, unknown>>(query: T): Record<string, string> {
  const params: Record<string, string> = {}
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') {
      continue
    }
    params[key] = String(value)
  }
  return params
}

export async function get<T>(
  url: string,
  params?: Record<string, unknown>,
  options?: RequestOptions,
): Promise<T> {
  const response = await http.get<T>(url, {
    params: toParams(params ?? {}),
    signal: options?.signal,
    __skipAuthFailureHandler: options?.skipAuthFailureHandler,
  })
  return response.data
}

export async function post<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  const response = await http.post<T>(url, body ?? {}, {
    signal: options?.signal,
    __skipAuthFailureHandler: options?.skipAuthFailureHandler,
  })
  return response.data
}

export async function put<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  const response = await http.put<T>(url, body ?? {}, {
    signal: options?.signal,
    __skipAuthFailureHandler: options?.skipAuthFailureHandler,
  })
  return response.data
}

export async function patch<T>(url: string, body?: unknown, options?: RequestOptions): Promise<T> {
  const response = await http.patch<T>(url, body ?? {}, {
    signal: options?.signal,
    __skipAuthFailureHandler: options?.skipAuthFailureHandler,
  })
  return response.data
}

export async function del<T>(url: string, options?: RequestOptions): Promise<T> {
  const response = await http.delete<T>(url, {
    signal: options?.signal,
    __skipAuthFailureHandler: options?.skipAuthFailureHandler,
  })
  return response.data
}

/**
 * Absolute URL for a Server-Sent Events stream.
 *
 * Used by {@link file://./sse.ts}, which reads the stream with `fetch` rather than
 * `EventSource` so the access token can travel in the `Authorization` header. Putting
 * it in the query string instead would leak it into every access log and proxy trace.
 */
export function streamUrl(path: string): string {
  return `${API_BASE_URL}${path}`
}

export { TOKEN_STORAGE_KEY }