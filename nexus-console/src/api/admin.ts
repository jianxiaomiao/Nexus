import { http, type ApiResponse } from './http'
import type { ListPage } from './listPage'
import type { ApplicationQuota } from './applicationQuota'

export interface AdminUserListItem {
  id: number
  email: string
  displayName: string
  role: string
  status: number
  applicationCount: number
  createdAt: string
}

export interface AdminApplicationListItem {
  id: number
  ownerUserId: number
  ownerEmail: string
  name: string
  status: number
  quota: ApplicationQuota | null
  createdAt: string
  updatedAt: string
}

export async function listAdminUsers(params: { current: number; size: number; email?: string }): Promise<ListPage<AdminUserListItem>> {
  const response = await http.get<ApiResponse<ListPage<AdminUserListItem>>>('/admin/users', { params })
  return response.data.data
}

export async function updateAdminUserStatus(userId: number, status: 0 | 1): Promise<void> {
  await http.patch<ApiResponse<void>>(`/admin/users/${userId}/status`, { status })
}

export async function listAdminApplications(params: { current: number; size: number; ownerUserId?: number }): Promise<ListPage<AdminApplicationListItem>> {
  const response = await http.get<ApiResponse<ListPage<AdminApplicationListItem>>>('/admin/applications', { params })
  return response.data.data
}

export async function updateAdminApplicationStatus(applicationId: number, status: 0 | 1): Promise<void> {
  await http.patch<ApiResponse<void>>(`/admin/applications/${applicationId}/status`, { status })
}
