import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'

import { ApiError } from '@/services/api'
import type { AsyncState } from './types'

/**
 * Shared loading/error bookkeeping for screens that fetch on demand.
 *
 * Every data-backed view in the application needs the same three things: is it loading,
 * did it fail, has it ever succeeded. Rolling that into one store keeps the empty state
 * and the retry button honest — a screen cannot accidentally show "no results" while a
 * request is still in flight.
 */
export const useAsyncStore = defineStore('async', () => {
  const states = shallowRef<Record<string, AsyncState>>({})

  function state(key: string): AsyncState {
    return states.value[key] ?? { loading: false, error: null, loaded: false }
  }

  function isLoading(key: string): boolean {
    return state(key).loading
  }

  function hasLoaded(key: string): boolean {
    return state(key).loaded
  }

  function errorFor(key: string): string | null {
    return state(key).error
  }

  function begin(key: string): void {
    states.value = {
      ...states.value,
      [key]: { loading: true, error: null, loaded: states.value[key]?.loaded ?? false },
    }
  }

  function succeed(key: string): void {
    states.value = { ...states.value, [key]: { loading: false, error: null, loaded: true } }
  }

  function fail(key: string, error: unknown): void {
    const message =
      error instanceof ApiError
        ? error.message
        : ((error as Error)?.message ?? 'The request could not be completed.')
    states.value = { ...states.value, [key]: { loading: false, error: message, loaded: false } }
  }

  function reset(key: string): void {
    const next = { ...states.value }
    delete next[key]
    states.value = next
  }

  /**
   * Wraps an async call with the loading/error bookkeeping.
   *
   * @param key stable identifier, usually `${storeName}:${id}`
   */
  async function run<T>(key: string, action: () => Promise<T>): Promise<T | null> {
    begin(key)
    try {
      const result = await action()
      succeed(key)
      return result
    } catch (error) {
      fail(key, error)
      return null
    }
  }

  return { states, state, isLoading, hasLoaded, errorFor, begin, succeed, fail, reset, run }
})

/** Debounce helper for search inputs, so a keystroke does not fire a request each time. */
export function useDebouncedRef<T>(source: () => T, delayMs = 300) {
  const debounced = ref<T>(source())
  let timer: number | undefined

  function watchSource(value: T): void {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      debounced.value = value
    }, delayMs)
  }

  return { debounced, watchSource }
}