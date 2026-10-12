import React, { useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Dialog } from '../../shared/ui/Dialog.jsx'

export function NewChapterProposal({ proposalId, disabled, onClose, onSaved, onStatusChange }) {
  const [proposal, setProposal] = useState(null)
  const [form, setForm] = useState({ title: '', content: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [retry, setRetry] = useState(0)
  const pending = useRef(false)
  const dirty = proposal?.status === 'PENDING' && (form.title !== proposal.proposedTitle || form.content !== proposal.proposedContent)
  const locked = disabled || busy || !proposal || proposal.status !== 'PENDING'
  useEffect(() => {
    let disposed = false
    setProposal(null); setError('')
    api(`/edit-proposals/${proposalId}`).then(value => {
      if (value.operation !== 'CREATE' || value.targetType !== 'CHAPTER') throw new Error('这份建议不是新建章节。')
      if (!disposed) { setProposal(value); setForm({ title: value.proposedTitle, content: value.proposedContent }) }
    }).catch(e => { if (!disposed) setError(readableError(e)) })
    return () => { disposed = true }
  }, [proposalId, retry])
  useEffect(() => {
    const check = event => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', check)
    return () => window.removeEventListener('beforeunload', check)
  }, [dirty])
  function close() { if (!pending.current && (!dirty || window.confirm('章节草稿尚未保存，确定放弃修改？'))) onClose() }
  async function save(event) {
    event.preventDefault()
    if (locked || pending.current) return
    pending.current = true; setBusy(true); setError('')
    try {
      const result = await api(`/edit-proposals/${proposalId}/chapter`, 'PUT', form)
      setProposal(current => ({ ...current, status: 'APPLIED', createdChapterId: result.targetChapterId }))
      onStatusChange(proposalId, 'APPLIED')
      await onSaved(result.targetChapterId)
    } catch (e) { setError(readableError(e)) }
    finally { pending.current = false; setBusy(false) }
  }
  async function reject() {
    if (locked || pending.current || dirty && !window.confirm('放弃草稿修改并忽略这份建议？')) return
    pending.current = true; setBusy(true); setError('')
    try {
      await api(`/edit-proposals/${proposalId}/reject`, 'POST')
      setProposal(current => ({ ...current, status: 'REJECTED' })); onStatusChange(proposalId, 'REJECTED')
    } catch (e) { setError(readableError(e)) }
    finally { pending.current = false; setBusy(false) }
  }
  return <Dialog title="审阅新章节" size="wide" showClose closeLabel="关闭章节建议" busy={busy} onCancel={close}>
    {error && <p className="error" role="alert">{error}</p>}
    {!proposal && !error && <p role="status">正在加载章节建议…</p>}
    {!proposal && error && <button onClick={() => setRetry(value => value + 1)}>重试加载建议</button>}
    {proposal && <form className="settingForm" onSubmit={save}>
      <div className="settingFields">
        <p>{proposal.status === 'APPLIED' ? '这份章节建议已保存。' : proposal.status === 'REJECTED' ? '这份章节建议已忽略。' : '编辑草稿后保存，新章节将追加到作品末尾。'}</p>
        <p>{proposal.reason}</p>
        <label htmlFor="new-chapter-title">章节标题</label>
        <input id="new-chapter-title" value={form.title} disabled={locked} maxLength={120} required onChange={event => setForm({ ...form, title: event.target.value })} />
        <label htmlFor="new-chapter-content">章节正文</label>
        <textarea id="new-chapter-content" value={form.content} disabled={locked} maxLength={6000} required onChange={event => setForm({ ...form, content: event.target.value })} />
        <small>{form.content.length} / 6000</small>
      </div>
      <div className="settingActions">
        {proposal.status === 'PENDING' && <><button type="button" className="link" disabled={locked} onClick={reject}>忽略建议</button>
          <button type="submit" disabled={locked || !form.title.trim() || !form.content.trim()}>{busy ? '保存中…' : '保存新章节'}</button></>}
        {proposal.status === 'APPLIED' && <button type="button" disabled={busy} onClick={() => onSaved(proposal.createdChapterId)}>查看已创建章节</button>}
        <button type="button" className="secondary" disabled={busy} onClick={close}>关闭</button>
      </div>
    </form>}
  </Dialog>
}
