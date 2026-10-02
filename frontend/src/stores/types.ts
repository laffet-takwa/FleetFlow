import { ref } from 'vue'

export type ToastTone = 'success' | 'danger' | 'warning' | 'info'

export interface Toast {
  id: number
  tone: ToastTone
  title: string
  description?: string
  duration?: number
}

export interface AsyncState {
  loading: boolean
  error: string | null
  loaded: boolean
}