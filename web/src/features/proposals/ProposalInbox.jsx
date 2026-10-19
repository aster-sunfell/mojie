import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import './inbox.css'

const dates = new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
function createdTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : dates.format(date)
}

export function ProposalInbox({ projectId, counts, onRefresh, onReview, onConversation, onClose }) {
  const [items, setItems] = useState([]), [cursor, setCursor] = useState(null)
  const [filter, setFilter] = useState('all'), [loading, setLoading] = useState(true)
  const [error, setError] = useState(''), [notice, setNotice] = useState(''), [busy, setBusy] = useState(null)
  const request = useRef(0)
  const load = useCallback(async (after = null) => {
    const token = ++request.current
    setLoading(true); setError('')
    try {
      const page = await api(`/projects/${projectId}/edit-proposals?limit=20&filter=${filter}${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
      if (token !== request.current) return
      setItems(current => after ? [...new Map([...current, ...page.items].map(item => [item.id, item])).values()] : page.items)
      setCursor(page.nextCursor)
    } catch (e) { if (token === request.current) setError(readableError(e)) }
    finally { if (token === request.current) setLoading(false) }
  }, [projectId, filter])
  useEffect(() => {
    setItems([]); setCursor(null); setNotice(''); load()
    return () => { request.current++ }
  }, [load])
  async function review(item) {
    if (busy) return
    setBusy(item.id); setError(''); setNotice('')
    try {
      const proposal = await api(`/edit-proposals/${item.id}`)
      if (!['PENDING', 'PARTIALLY_APPLIED', 'CONFLICTED'].includes(proposal.status)) {
        setNotice('这份建议已处理，列表已更新。'); onRefresh(); await load(); return
      }
      if (await onReview({ ...item, ...proposal, proposalId: item.id, status: 'SUCCEEDED' })) onClose()
    } catch (e) { setError(readableError(e)) }
    finally { setBusy(null) }
  }
  const groups = new Map()
  for (const item of items) {
    const key = `${item.targetType}:${item.targetChapterId || item.targetSettingId || item.id}`
    if (!groups.has(key)) groups.set(key, { title: item.targetTitle || '未命名目标', type: item.targetType,
      order: item.chapterOrder ?? Infinity, items: [] })
    groups.get(key).items.push(item)
  }
  const ordered = [...groups.values()].sort((a, b) => (a.type === 'CHAPTER' ? 0 : 1) - (b.type === 'CHAPTER' ? 0 : 1) || a.order - b.order)
  return <Dialog title="待处理建议" onCancel={onClose} showClose busy={!!busy} size="wide" className="proposalInboxDialog">
    <div className="proposalInboxIntro">
      <p>{counts ? `待处理 ${counts.pendingCount} 份 · 需核对 ${counts.conflictedCount} 份 · 涉及 ${counts.targetCount} 个目标` : '汇总本作品所有对话产生的未处理建议。'}</p>
      <button className="link" disabled={loading || !!busy} onClick={() => { onRefresh(); load() }}>刷新列表</button>
    </div>
    <div className="proposalInboxFilters" role="group" aria-label="筛选建议">
      {[['all', '全部'], ['pending', '待处理'], ['conflicted', '需核对']].map(([value, label]) =>
        <button key={value} className="secondary" aria-pressed={filter === value} disabled={!!busy}
          onClick={() => setFilter(value)}>{label}</button>)}
    </div>
    {error && <div className="proposalInboxError"><p className="error" role="alert">{error}</p>
      <button className="link" disabled={loading || !!busy} onClick={() => { onRefresh(); load() }}>重试加载建议</button></div>}
    {notice && <p className="hint" role="status">{notice}</p>}
    <div className="proposalInboxGroups" aria-busy={loading}>
      {ordered.map(group => <section key={`${group.type}:${group.items[0].id}`} className="proposalInboxGroup">
        <h3><span>{group.type === 'SETTING' ? '设定' : '章节'} · {group.title}</span><small>{group.items.length} 份建议</small></h3>
        {group.items.map(item => <article className="proposalInboxItem" data-proposal-id={item.id} key={item.id}>
          <div className="proposalInboxItemMeta"><span className={item.status === 'CONFLICTED' ? 'conflictState' : ''}>
            {item.status === 'CONFLICTED' ? '需核对' : item.status === 'PARTIALLY_APPLIED' ? '部分已处理' : '待处理'}
          </span><span>{item.operation === 'CREATE' ? `新建${item.targetType === 'SETTING' ? '设定' : '章节'}`
            : item.remainingItemCount != null ? `剩余 ${item.remainingItemCount} 处修改` : '设定修改'}</span>
            <time dateTime={item.createdAt}>{createdTime(item.createdAt)}</time></div>
          <p className="proposalInboxReason" title={item.reason}>{item.reason || '请打开建议查看修改内容。'}</p>
          <div className="proposalInboxItemBottom"><span className="proposalInboxSource">来源：{item.conversationTitle || '独立任务'}</span>
            <div className="proposalInboxActions">
              {item.conversationId && <button className="link" disabled={!!busy} onClick={() => { onConversation(item); onClose() }}>查看对话</button>}
              <button disabled={!!busy} onClick={() => review(item)}>{busy === item.id ? '正在打开…' : '审阅'}</button>
            </div></div>
        </article>)}
      </section>)}
      {loading && <p className="proposalInboxEmpty" role="status">正在加载建议…</p>}
      {!loading && !error && !items.length && <p className="proposalInboxEmpty">
        {filter === 'all' ? '没有待处理建议，可以继续写作。' : filter === 'conflicted' ? '没有需要核对的建议。' : '没有待处理建议。'}
      </p>}
    </div>
    {cursor && <button className="secondary proposalInboxMore" disabled={loading || !!busy} onClick={() => load(cursor)}>加载更多建议</button>}
  </Dialog>
}
