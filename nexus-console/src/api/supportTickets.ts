import { http, type ApiResponse } from './http'
import type { ListPage } from './listPage'

export interface SupportTicket {
  id: string
  title: string
  description: string
  applicationId: number | null
  createdAt: string
  deliveryStatus: 'PENDING' | 'DELIVERED' | 'BLOCKED'
  flowOpsTicketNo: string | null
}

export interface SupportSubmission {
  title: string
  description: string
}

export async function fetchSupportTickets(current: number, size: number, signal: AbortSignal) {
  const response = await http.get<ApiResponse<ListPage<SupportTicket>>>('/support/tickets', {
    params: { current, size },
    signal,
  })
  return response.data.data
}

export async function submitSupportTicket(input: SupportSubmission, key: string) {
  const response = await http.post<ApiResponse<SupportTicket>>('/support/tickets', input, {
    headers: { 'Idempotency-Key': key },
  })
  return response.data.data
}
