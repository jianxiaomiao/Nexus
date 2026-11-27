import { isAxiosError, isCancel } from 'axios'

export function mutationUnknown(error: unknown): boolean {
  if (error instanceof MutationResultUnknown) return true
  if (isCancel(error)) return false // request gates cancel before dispatch; page writes do not abort on leave
  return isAxiosError(error) && (!!error.request && !error.response || (error.response?.status ?? 0) >= 500)
}
export class MutationResultUnknown extends Error {
  constructor() { super('MUTATION_RESULT_UNKNOWN') }
}
export function needsReconcile(error: unknown): boolean {
  return mutationUnknown(error) || isAxiosError(error) && ['RESOURCE_CHANGED', 'API_KEY_ROTATION_CONFLICT',
    'WEBHOOK_SECRET_ROTATION_CONFLICT'].includes(error.response?.data?.code)
}
export const unknownMutationMessage = '操作结果尚未确认，请先刷新核对，不要直接重复提交。'
