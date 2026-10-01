import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'

export function Proposal({ id, dirty, refreshChapter, refreshRun, onAcceptanceBusy }) {
  const [proposal, setProposal] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [refreshFailed, setRefreshFailed] = useState(false)
  useEffect(() => {
    setProposal(null); setRefreshFailed(false)
    api(`/edit-proposals/${id}`).then(setProposal).catch(e => setError(readableError(e)))
  }, [id])
  async function decide(action) {
    setBusy(true); setError('')
    if (action === 'accept') onAcceptanceBusy(true)
    try {
      const result = await api(`/edit-proposals/${id}/${action}`, 'POST')
      setProposal(current => ({ ...current, status: result.status, appliedVersion: result.appliedVersion }))
      const checks = await Promise.allSettled([
        api(`/edit-proposals/${id}`),
        action === 'accept' ? refreshChapter() : Promise.resolve(),
        refreshRun(),
      ])
      if (checks[0].status === 'fulfilled') setProposal(checks[0].value)
      if (action === 'accept' && checks[1].status === 'rejected') {
        setRefreshFailed(true)
        setError('建议已采纳，但正文刷新失败。请重试刷新正文。')
      } else if (checks[0].status === 'rejected' || checks[2].status === 'rejected') {
        setRefreshFailed(false)
        setError('建议状态已更新，但详情刷新失败。')
      } else setRefreshFailed(false)
    } catch (e) {
      setError(readableError(e))
      api(`/edit-proposals/${id}`).then(setProposal).catch(() => {})
    } finally { setBusy(false); if (action === 'accept') onAcceptanceBusy(false) }
  }
  async function retryRefresh() {
    setBusy(true)
    try {
      await refreshChapter(); setRefreshFailed(false); setError('')
      try { await refreshRun() } catch { setError('正文已刷新，但任务详情刷新失败。') }
    } catch { setError('正文刷新仍未完成，请稍后重试。') }
    finally { setBusy(false) }
  }
  if (!proposal) return error && <p className="error">{error}</p>
  return <section className="proposal"><h3>修改建议 <span className="badge">{proposal.status}</span></h3><p>{proposal.reason}</p>
    <p className="muted">基于章节版本 {proposal.baseVersion}，采纳前会再次检查当前版本。</p>
    <div className="comparison"><div><strong>任务基准原文</strong><pre>{proposal.baseContent}</pre></div><div><strong>建议稿</strong><pre>{proposal.proposedContent}</pre></div></div>
    {error && <p className="error">{error}</p>}
    {refreshFailed && <button className="secondary" disabled={busy} onClick={retryRefresh}>重试刷新正文</button>}
    {proposal.status === 'PENDING' && <div className="row"><button disabled={dirty || busy} onClick={() => decide('accept')}>整体采纳</button><button className="secondary" disabled={busy} onClick={() => decide('reject')}>拒绝</button>{dirty && <small>请先保存或放弃本地修改</small>}</div>}
    {proposal.status === 'APPLIED' && <p className="success">已保存为章节版本 {proposal.appliedVersion}</p>}
  </section>
}
