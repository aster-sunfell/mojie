import { maintenanceBlocked, reportMaintenance } from './maintenance.js'

export async function api(path, method = 'GET', body) {
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) && maintenanceBlocked()) {
    const error = new Error('墨界正在维护，请稍后再试。')
    error.code = 'MAINTENANCE_MODE'
    error.status = 503
    throw error
  }
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (response.status === 204) return null
  let value
  try { value = await response.json() } catch { throw new Error('服务器返回了无法读取的响应') }
  if (!response.ok) {
    if (value.code === 'MAINTENANCE_MODE') reportMaintenance()
    const error = new Error(value.message || '请求失败')
    error.code = value.code
    error.status = response.status
    throw error
  }
  return value.data
}

export function readableError(error) {
  const names = {
    MAINTENANCE_MODE: '墨界正在维护，请稍后再试。',
    CONVERSATION_VERSION_CONFLICT: '对话已有新内容，请刷新后重发。',
    CONVERSATION_MODEL_UNAVAILABLE: '此对话原来的模型配置不可用，请新建对话。',
    SKILL_VERSION_CONFLICT: 'Skill 版本或名称冲突，请检查本轮选择与固定版本。',
    SKILL_UNAVAILABLE: '选择的 Skill 已停用，请取消选择或重新启用。',
    VERSION_CONFLICT: '章节版本已变化，请先查看最新正文。',
    RESOURCE_BUSY: '作品有正在运行的任务或待确认建议。',
    RUN_CAPACITY_EXCEEDED: '任务队列已满，请稍后重试。',
    MODEL_METADATA_UNAVAILABLE: '模型信息暂不可用，请稍后重试；章节和设定仍可编辑。',
    MODEL_ERROR: '模型尚未配置或本次模型请求失败。',
    PROPOSAL_STATE_CONFLICT: '建议状态已经变化，请刷新后重试。',
    IDEMPOTENCY_CONFLICT: '本次提交标识已用于不同内容。'
  }
  return names[error?.code] || error?.message || '请求失败，请稍后重试。'
}
