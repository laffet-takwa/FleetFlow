import { API_BASE_URL, newCorrelationId, readToken } from './api'

/**
 * Minimal Server-Sent Events client built on `fetch`.
 *
 * `EventSource` cannot set request headers, and the usual workaround of putting the
 * access token in the query string would write it into every access log and proxy
 * trace on its way through the gateway. Reading the response body with `fetch` keeps
 * the token in the `Authorization` header while still giving us the standard SSE
 * framing.
 */

export type SseStatus = 'connecting' | 'open' | 'closed' | 'error'

export interface SseMessage<T = unknown> {
  event: string
  data: T
  id?: string
}

export interface SseHandlers<T = unknown> {
  onMessage: (message: SseMessage<T>) => void
  onStatus?: (status: SseStatus, detail?: string) => void
  /** Called before each automatic reconnect so the caller can reset its UI. */
  onReconnecting?: () => void
}

export interface SseStream {
  close: () => void
}

const INITIAL_BACKOFF_MS = 1_000
const MAX_BACKOFF_MS = 15_000

/**
 * Opens an authenticated SSE stream and keeps it open with exponential backoff.
 *
 * @param path path relative to the API base, e.g. `/tracking/12/stream`
 */
export function openSseStream<T = unknown>(path: string, handlers: SseHandlers<T>): SseStream {
  const url = `${API_BASE_URL}${path}`
  let controller: AbortController | null = null
  let closed = false
  let attempt = 0

  const status = (next: SseStatus, detail?: string) => handlers.onStatus?.(next, detail)

  const connect = async (): Promise<void> => {
    if (closed) {
      return
    }
    const token = readToken()
    if (!token) {
      status('error', 'Not signed in')
      return
    }

    controller = new AbortController()
    status(attempt === 0 ? 'connecting' : 'connecting')

    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          Accept: 'text/event-stream',
          Authorization: `Bearer ${token}`,
          'X-Correlation-ID': newCorrelationId(),
          'Cache-Control': 'no-cache',
        },
        signal: controller.signal,
      })

      if (!response.ok || !response.body) {
        status('error', `Stream rejected with HTTP ${response.status}`)
        scheduleReconnect()
        return
      }

      attempt = 0
      status('open')
      await readStream(response.body, handlers, () => {
        // The server closed cleanly; this is a normal end of stream, not a failure.
        status('closed')
        scheduleReconnect()
      })
    } catch (error) {
      if (closed || (error instanceof DOMException && error.name === 'AbortError')) {
        return
      }
      status('error', 'Connection lost')
      scheduleReconnect()
    }
  }

  const scheduleReconnect = () => {
    if (closed) {
      return
    }
    attempt += 1
    const delay = Math.min(INITIAL_BACKOFF_MS * 2 ** (attempt - 1), MAX_BACKOFF_MS)
    handlers.onReconnecting?.()
    window.setTimeout(() => void connect(), delay)
  }

  void connect()

  return {
    close() {
      closed = true
      controller?.abort()
    },
  }
}

async function readStream<T>(
  body: ReadableStream<Uint8Array>,
  handlers: SseHandlers<T>,
  onEnd: () => void,
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        onEnd()
        return
      }
      buffer += decoder.decode(value, { stream: true })

      // Frames are separated by a blank line. A partial frame stays in the buffer
      // until the rest of it arrives, which happens constantly on a slow network.
      let boundary = buffer.indexOf('\n\n')
      while (boundary !== -1) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        dispatch<T>(frame, handlers)
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    reader.releaseLock()
  }
}

function dispatch<T>(frame: string, handlers: SseHandlers<T>): void {
  const lines = frame.split('\n')
  let event = 'message'
  let id: string | undefined
  const dataLines: string[] = []

  for (const line of lines) {
    if (line.startsWith(':')) {
      continue
    }
    const separator = line.indexOf(':')
    const field = separator === -1 ? line : line.slice(0, separator)
    const value = separator === -1 ? '' : line.slice(separator + 1).replace(/^ /, '')

    if (field === 'event') {
      event = value
    } else if (field === 'data') {
      dataLines.push(value)
    } else if (field === 'id') {
      id = value
    }
  }

  if (dataLines.length === 0) {
    return
  }
  const raw = dataLines.join('\n')

  let data: unknown = raw
  if (raw !== '[DONE]') {
    try {
      data = JSON.parse(raw)
    } catch {
      // A keep-alive comment or a plain-text payload: pass it through untouched.
    }
  }
  handlers.onMessage({ event, data: data as T, id })
}