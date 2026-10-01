export async function api(path, method = 'GET', body) {
  const response = await fetch(`/api/v1${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  if (response.status === 204) return null
  let value
  try { value = await response.json() } catch { throw new Error('服务器返回了无法读取的响应') }
  if (!response.ok) {
    const error = new Error(value.message || '请求失败')
    error.code = value.code
    error.status = response.status
    throw error
  }
  return value.data
}

export function readableError(error) {
  const names = {
    VERSION_CONFLICT: '章节版本已变化，请先查看最新正文。',
    RESOURCE_BUSY: '作品有正在运行的任务或待确认建议。',
    RUN_CAPACITY_EXCEEDED: '任务队列已满，请稍后重试。',
    MODEL_ERROR: '模型尚未配置或本次模型请求失败。',
    PROPOSAL_STATE_CONFLICT: '建议状态已经变化，请刷新后重试。',
    IDEMPOTENCY_CONFLICT: '本次提交标识已用于不同内容。'
  }
  return names[error?.code] || error?.message || '请求失败，请稍后重试。'
}
