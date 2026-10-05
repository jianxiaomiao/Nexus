import { http, type ApiResponse } from './http'

export interface Application {
  id: number
  name: string
  status: number
  createdAt: string
  updatedAt: string
}

export interface CreateApplicationRequest {
  name: string
}

export interface CreateApplicationResponse {
  id: number
  name: string
}

export interface UpdateApplicationRequest {
  id: number
  name?: string
  status?: 0 | 1
}

export async function listApplications(): Promise<Application[]> {
  const response = await http.get<ApiResponse<Application[]>>('/application')
  return response.data.data
}

export async function createApplication(request: CreateApplicationRequest): Promise<CreateApplicationResponse> {
  const response = await http.post<ApiResponse<CreateApplicationResponse>>('/application/create', request)
  return response.data.data
}

export async function updateApplication(request: UpdateApplicationRequest): Promise<Application> {
  const response = await http.put<ApiResponse<Application>>('/application', request)
  return response.data.data
}

export async function deleteApplication(id: number): Promise<void> {
  await http.delete(`/application/${id}`)
}
