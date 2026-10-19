import React, { useEffect, useId, useRef, useState } from 'react'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'

const dateFormat = new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
function updatedTime(item) {
  const date = new Date(item.updatedAt || item.createdAt)
  return Number.isNaN(date.getTime()) ? '' : dateFormat.format(date)
}

export function ConversationHistory({ chat, onSelect, onClose }) {
  const [menuId, setMenuId] = useState(null), [editingId, setEditingId] = useState(null)
  const [title, setTitle] = useState(''), [validation, setValidation] = useState('')
  const menu = useRef(null), panelId = useId()
  useEffect(() => {
    if (!menuId) return
    const dismiss = event => { if (!menu.current?.contains(event.target)) setMenuId(null) }
    const escape = event => {
      if (event.key === 'Escape') { event.stopPropagation(); menu.current?.querySelector('button')?.focus(); setMenuId(null) }
    }
    document.addEventListener('pointerdown', dismiss)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('pointerdown', dismiss)
      document.removeEventListener('keydown', escape)
    }
  }, [menuId])
  async function rename(event, item) {
    event.preventDefault()
    const next = title.trim()
    if (!next || [...next].length > 100) { setValidation('请输入 1–100 字的对话标题。'); return }
    if (await chat.patchConversation(item, { title: next })) { setEditingId(null); setValidation('') }
  }
  const error = validation || chat.listError || chat.error
  return <Dialog title="历史对话" onCancel={onClose} busy={chat.busy} showClose className="conversationHistoryDialog">
    <div className="conversationHistory">
      {error && <div className="conversationHistoryError"><p className="error" role="alert">{error}</p>
        {chat.listError && <button className="link" disabled={chat.listLoading} onClick={() => chat.loadList().catch(() => {})}>重试加载</button>}
      </div>}
      <ScrollArea className="conversationHistoryList" id={panelId} role="region" aria-label="历史对话列表"
        aria-busy={chat.listLoading}>
        {chat.items.map(record => {
          const item = record.id === chat.id && chat.conversation ? { ...record, ...chat.conversation,
            pendingProposalCount: record.pendingProposalCount, conflictedProposalCount: record.conflictedProposalCount } : record
          const current = item.id === chat.id, blocked = chat.busy || Boolean(item.activeRunId)
          const updated = updatedTime(item)
          return <div className={`conversationHistoryRow ${current ? 'current' : ''}`} key={item.id}>
            {editingId === item.id ? <form className="conversationHistoryRename" onSubmit={event => rename(event, item)}>
              <label htmlFor={`${panelId}-title`}>对话标题</label>
              <input id={`${panelId}-title`} autoFocus value={title} disabled={blocked}
                onChange={event => { setTitle(event.target.value); setValidation('') }}
                onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); setEditingId(null); setValidation('') } }} />
              <div className="conversationHistoryRenameActions"><button type="button" className="secondary" disabled={chat.busy}
                onClick={() => { setEditingId(null); setValidation('') }}>取消</button>
                <button type="submit" disabled={blocked || !title.trim()}>保存</button></div>
            </form> : <>
              <button className="conversationHistorySelect" data-conversation-id={item.id} aria-label={`打开对话：${item.title}`} aria-current={current ? 'true' : undefined}
                disabled={chat.busy} onClick={() => onSelect(item.id)}>
                <span className="conversationHistoryTitle">{item.title}</span>
                <span className="conversationHistoryMeta">{updated && <time dateTime={item.updatedAt || item.createdAt}>更新于 {updated}</time>}
                  {current && <span>当前对话</span>}{item.activeRunId && <span>执行中</span>}</span>
                {(item.pendingProposalCount > 0 || item.conflictedProposalCount > 0) && <span className="conversationPendingBadge">
                  {item.pendingProposalCount > 0 && `${item.pendingProposalCount} 份建议待处理`}
                  {item.pendingProposalCount > 0 && item.conflictedProposalCount > 0 && ' · '}
                  {item.conflictedProposalCount > 0 && `${item.conflictedProposalCount} 份需核对`}
                </span>}
              </button>
              <div className="conversationHistoryRowActions" ref={menuId === item.id ? menu : undefined}>
                <Tooltip label="更多操作"><button className="assistantIconButton" aria-label={`更多操作：${item.title}`}
                  aria-expanded={menuId === item.id} aria-controls={menuId === item.id ? `${panelId}-actions` : undefined}
                  disabled={blocked} onClick={() => setMenuId(value => value === item.id ? null : item.id)}><Icon name="more" /></button></Tooltip>
                {menuId === item.id && <div className="conversationHistoryOperations" id={`${panelId}-actions`}>
                  <button onClick={() => { setMenuId(null); setEditingId(item.id); setTitle(item.title); setValidation('') }}>重命名</button>
                </div>}
              </div>
            </>}
          </div>
        })}
        {!chat.listLoading && !chat.listError && !chat.items.length && <p className="conversationHistoryEmpty">暂无历史对话，发送第一条消息即可开始。</p>}
        {chat.listLoading && <p className="conversationHistoryEmpty" role="status">正在加载对话…</p>}
      </ScrollArea>
      {chat.cursor && <button className="link conversationHistoryLoadMore" disabled={chat.listLoading || chat.busy}
        onClick={() => chat.loadList(chat.cursor).catch(() => {})}>加载更多对话</button>}
    </div>
  </Dialog>
}
