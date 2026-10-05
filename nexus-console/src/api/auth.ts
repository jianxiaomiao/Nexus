import { http, type ApiResponse } from './http'

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

export async function login(request: LoginRequest): Promise<ApiResponse<LoginResponse>> {
  const response = await http.post<ApiResponse<LoginResponse>>('/auth/login', request)
  return response.data
}

export interface RegisterRequest {
  email: string
  displayName: string
  password: string
}

export interface RegisterResponse {
  email: string
  displayName: string
}

export async function register(request: RegisterRequest): Promise<ApiResponse<RegisterResponse>> {
  const response = await http.post<ApiResponse<RegisterResponse>>('/auth/register', request)
  return response.data
}
