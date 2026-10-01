export function statusName(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已停止' })[status] || status
}

export const active = status => status === 'QUEUED' || status === 'RUNNING'
