import { get, post } from './api'
import type { AuthResponse, RegisterRequest, UserResponse } from '@/types'

export const authApi = {
  register: (body: RegisterRequest) => post<AuthResponse>('/auth/register', body, {
    // A failed registration must not trigger the global sign-out handler.
    skipAuthFailureHandler: true,
  }),

  login: (email: string, password: string) =>
    post<AuthResponse>('/auth/login', { email, password }, { skipAuthFailureHandler: true }),

  me: () => get<UserResponse>('/auth/me'),

  listUsers: (params: { role?: string; search?: string; page?: number; size?: number }) =>
    get<import('@/types').PageResponse<UserResponse>>('/auth/users', params),
}