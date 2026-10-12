import React, { useCallback, useEffect, useId, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { SettingProposalFields } from './SettingProposalFields.jsx'

const types = { CHARACTER: '人物', WORLD: '世界观', RULE: '写作约束' }
const emptyForm = () => ({ type: 'CHARACTER', title: '', content: '' })
const fields = item => ({ type: item.type, title: item.title, content: item.content })

export function Settings({ projectId, disabled = false, proposalId = null, onProposalChange, onClose }) {
  const [items, setItems] = useState([])
  const [cursor, setCursor] = useState(null)
  const [selected, setSelected] = useState(null)
  const [mode, setMode] = useState('view')
  const [form, setForm] = useState(emptyForm)
  const [keyword, setKeyword] = useState('')
  const [type, setType] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [listError, setListError] = useState('')
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [mobileDetail, setMobileDetail] = useState(false)
  const [proposal, setProposal] = useState(null)
  const [choices, setChoices] = useState({})
  const [history, setHistory] = useState(null)
  const [proposalLoading, setProposalLoading] = useState(Boolean(proposalId))
  const [proposalRetry, setProposalRetry] = useState(0)
  const historyRequest = useRef(0)
  const query = useRef({ keyword, type })
  const requestNumber = useRef(0)
  const pending = useRef(false)
  const formRef = useRef(null)
  const fieldId = useId()
  query.current = { keyword, type }
  const baseline = ['edit', 'review'].includes(mode) && selected ? fields(selected) : emptyForm()
  const dirty = mode !== 'view' && (Object.keys(baseline).some(key => form[key] !== baseline[key]) || mode === 'review' && Object.keys(choices).length > 0)
  const locked = disabled || busy || proposalLoading
  const conflicted = proposal && (proposal.status === 'CONFLICTED' || proposal.baseVersion !== selected?.version)

  useEffect(() => {
    if (!proposalId) return
    let disposed = false
    setProposalLoading(true); setError('')
    ;(async () => {
      const value = await api(`/edit-proposals/${proposalId}`)
      if (value.targetType !== 'SETTING' || value.projectId !== projectId) throw new Error('这份建议不属于当前作品的设定。')
      const setting = await api(`/settings/${value.targetSettingId}`)
      if (disposed) return
      setProposal(value); setSelected(setting); setForm(fields(setting)); setChoices({}); setHistory(null)
      setMode(value.status === 'PENDING' || value.status === 'CONFLICTED' ? 'review' : 'view'); setMobileDetail(true)
      if (value.status === 'APPLIED') setNotice('这份设定建议已处理并保存。')
      if (value.status === 'REJECTED') setNotice('这份设定建议已忽略。')
    })().catch(failure => { if (!disposed) setError(readableError(failure)) })
      .finally(() => { if (!disposed) setProposalLoading(false) })
    return () => { disposed = true }
  }, [projectId, proposalId, proposalRetry])

  const load = useCallback(async (after = null) => {
    const request = ++requestNumber.current
    const params = new URLSearchParams({ limit: '50' })
    if (query.current.keyword) params.set('keyword', query.current.keyword)
    if (query.current.type) params.set('type', query.current.type)
    if (after) params.set('cursor', after)
    setLoading(true)
    try {
      const page = await api(`/projects/${projectId}/settings?${params}`)
      if (request !== requestNumber.current) return
      setItems(current => after
        ? [...new Map([...current, ...page.items].map(item => [item.id, item])).values()]
        : page.items)
      setCursor(page.nextCursor)
      setListError('')
    } catch (failure) {
      if (request === requestNumber.current) setListError(readableError(failure))
    } finally {
      if (request === requestNumber.current) setLoading(false)
    }
  }, [projectId])

  useEffect(() => {
    setItems([]); setCursor(null)
    load()
    return () => { requestNumber.current++ }
  }, [load, keyword, type])
  useEffect(() => {
    if (!selected && mode === 'view' && items.length) setSelected(items[0])
  }, [items, selected, mode])
  useEffect(() => {
    setHistory(null); historyRequest.current++
    return () => { historyRequest.current++ }
  }, [selected?.id, selected?.version])
  useEffect(() => {
    if (mode !== 'view') formRef.current?.querySelector('input')?.focus()
  }, [mode])
  useEffect(() => {
    const check = event => { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', check)
    return () => window.removeEventListener('beforeunload', check)
  }, [dirty])
  useEffect(() => {
    const key = event => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return
      event.preventDefault(); event.stopImmediatePropagation()
      if (mode !== 'view' && !locked && dirty) formRef.current?.requestSubmit()
    }
    window.addEventListener('keydown', key, true)
    return () => window.removeEventListener('keydown', key, true)
  }, [mode, locked, dirty])

  function canDiscard() {
    return !pending.current && (!dirty || window.confirm('设定修改尚未保存，确定放弃修改？'))
  }
  function close() {
    if (canDiscard()) onClose()
  }
  function select(item) {
    if (!canDiscard()) return
    setSelected(item); setMode('view'); setMobileDetail(true)
    setError(''); setNotice('')
    setProposal(null); setChoices({}); setHistory(null)
  }
  function add() {
    if (locked || !canDiscard()) return
    setMode('new'); setForm(emptyForm()); setMobileDetail(true)
    setError(''); setNotice('')
    setProposal(null); setChoices({}); setHistory(null)
  }
  function edit() {
    if (locked || !selected) return
    setForm(fields(selected)); setMode('edit'); setError(''); setNotice('')
  }
  function cancelEdit() {
    if (!canDiscard()) return
    setMode('view'); setError(''); setNotice('')
    setProposal(null); setChoices({})
  }
  function back() {
    if (!canDiscard()) return
    setMode('view'); setMobileDetail(false); setError(''); setNotice('')
    setProposal(null); setChoices({})
  }
  async function save(event) {
    event.preventDefault()
    if (locked || pending.current || !dirty || mode === 'review' && conflicted || !form.title.trim() || !form.content.trim()) return
    pending.current = true; setBusy(true); setError(''); setNotice('')
    try {
      let value
      if (mode === 'review') {
        await api(`/edit-proposals/${proposal.id}/setting`, 'PUT', { ...form, expectedVersion: proposal.baseVersion })
        value = await api(`/settings/${selected.id}`)
        onProposalChange?.(proposal.id, 'APPLIED')
      } else value = mode === 'edit'
        ? await api(`/settings/${selected.id}`, 'PATCH', { ...form, expectedVersion: selected.version })
        : await api(`/projects/${projectId}/settings`, 'POST', form)
      setSelected(value); setMode('view'); setNotice('设定已保存')
      setChoices({}); setProposal(null); setHistory(null)
      await load()
    } catch (failure) {
      setError(readableError(failure))
      if (mode === 'review' && failure.status === 409) {
        setProposal(current => ({ ...current, status: 'CONFLICTED' }))
        onProposalChange?.(proposal.id, 'CONFLICTED')
      }
    }
    finally { pending.current = false; setBusy(false) }
  }
  async function rejectProposal() {
    if (locked || pending.current || !proposal || !canDiscard()) return
    pending.current = true; setBusy(true); setError('')
    try {
      await api(`/edit-proposals/${proposal.id}/reject`, 'POST')
      onProposalChange?.(proposal.id, 'REJECTED')
      setProposal(null); setChoices({}); setMode('view'); setNotice('设定建议已忽略')
    } catch (failure) { setError(readableError(failure)) }
    finally { pending.current = false; setBusy(false) }
  }
  async function loadHistory() {
    const request = ++historyRequest.current
    try {
      const value = await api(`/settings/${selected.id}/revisions`)
      if (request === historyRequest.current) setHistory(value)
    } catch (failure) { if (request === historyRequest.current) setError(readableError(failure)) }
  }
  async function remove() {
    if (locked || pending.current || !selected || !window.confirm(`确定删除“${selected.title}”？`)) return
    pending.current = true; setBusy(true); setError(''); setNotice('')
    try {
      await api(`/settings/${selected.id}`, 'DELETE')
      setItems(current => current.filter(item => item.id !== selected.id))
      setSelected(null); setMode('view'); setMobileDetail(false)
      await load()
    } catch (failure) { setError(readableError(failure)) }
    finally { pending.current = false; setBusy(false) }
  }

  return <Dialog title="作品设定" size="wide" showClose closeLabel="关闭作品设定"
    className="settingsDialog" busy={busy} onCancel={close}>
    {disabled && <p className="settingsReadOnly" role="status">任务运行期间可查看设定，暂不可修改。</p>}
    <div className={`settingsBody${mobileDetail ? ' settingsShowDetail' : ''}`}>
      <section className="settingsNavigation" aria-label="设定导航">
        <div className="settingsFilters">
          <label className="settingsSearch">
            <Icon name="search" size={17} />
            <input data-autofocus aria-label="搜索设定" value={keyword}
              onChange={event => setKeyword(event.target.value)} placeholder="筛选标题或正文" />
          </label>
          <select aria-label="设定类型筛选" value={type} onChange={event => setType(event.target.value)}>
            <option value="">全部类型</option>
            {Object.entries(types).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </div>
        <ScrollArea className="settingList" orientation="vertical" aria-label="设定列表" aria-busy={loading}>
          {listError && <p className="error" role="alert">{listError}<button className="link" onClick={() => load()}>重试</button></p>}
          {items.map(item => <button type="button" key={item.id} className="settingListItem"
            aria-label={`${types[item.type]}：${item.title}`}
            aria-current={mode !== 'new' && selected?.id === item.id ? 'true' : undefined} disabled={busy || proposalLoading} onClick={() => select(item)}>
            <small>{types[item.type]}</small><strong>{item.title}</strong><span>{item.content}</span>
          </button>)}
          {loading && <p className="settingsListHint" role="status">加载设定中…</p>}
          {!loading && !listError && items.length === 0 && <p className="settingsListHint">
            {keyword || type ? '没有匹配的设定' : '暂无设定，添加人物、世界观或写作约束。'}
          </p>}
          {cursor && <button className="secondary settingsLoadMore" disabled={loading || busy} onClick={() => load(cursor)}>加载更多设定</button>}
        </ScrollArea>
        <div className="settingsNavigationFooter">
          <button className="settingsAdd" disabled={locked} onClick={add}><Icon name="plus" size={17} />新增设定</button>
        </div>
      </section>
      <section className="settingsDetailPane" aria-label="设定详情">
        <button type="button" className="link settingsBack" disabled={busy} onClick={back}>← 返回设定列表</button>
        {error && <p className="error settingsFeedback" role="alert">{error}</p>}
        {notice && <p className="success settingsFeedback" role="status">{notice}</p>}
        {proposalLoading && <p className="settingsFeedback" role="status">正在加载设定建议…</p>}
        {proposalId && error && !proposal && <button className="link" disabled={locked} onClick={() => { if (canDiscard()) setProposalRetry(value => value + 1) }}>重试加载建议</button>}
        {mode === 'view' ? selected ? <>
          <ScrollArea className="settingDetail" orientation="vertical">
            <span className="settingType">{types[selected.type]}</span>
            <h3>{selected.title}</h3>
            <p className="settingContent">{selected.content}</p>
            <details onToggle={event => { if (event.currentTarget.open && !history) loadHistory() }}>
              <summary>设定历史 · v{selected.version}</summary>
              {history?.map(item => <article className="settingRevision" key={item.version}>
                <strong>v{item.version} · {types[item.type]} · {item.title}</strong>
                <small>{({ CREATE: '初始版本', MANUAL: '手动保存', AGENT: '助手建议' })[item.source]}</small>
                <pre>{item.content}</pre>
              </article>)}
            </details>
          </ScrollArea>
          <div className="settingActions">
            <button className="link danger" disabled={locked} onClick={remove}>删除设定</button>
            <button className="secondary" disabled={locked} onClick={edit}>编辑设定</button>
          </div>
        </> : <div className="settingsEmptyDetail"><Icon name="book" size={32} />
          <h3>选择或新建设定</h3><p>从左侧选择设定，或新增人物、世界观与写作约束。</p>
        </div> : <form ref={formRef} onSubmit={save} className="settingForm" aria-label={mode === 'review' ? '审阅设定修改' : mode === 'edit' ? '编辑设定' : '新增设定'}>
          <ScrollArea className="settingFields" orientation="vertical">
            {mode === 'review' && proposal && <SettingProposalFields proposal={proposal} form={form} choices={choices}
              locked={locked} conflicted={conflicted}
              onApply={(key, value) => { setForm(current => ({ ...current, [key]: value })); setChoices(current => ({ ...current, [key]: 'APPLIED' })) }}
              onIgnore={key => { setForm(current => ({ ...current, [key]: fields(selected)[key] })); setChoices(current => ({ ...current, [key]: 'IGNORED' })) }}
              onApplyAll={() => { setForm({ type: proposal.proposedType, title: proposal.proposedTitle, content: proposal.proposedContent }); setChoices({ type: 'APPLIED', title: 'APPLIED', content: 'APPLIED' }) }} />}
            <h3>{mode === 'review' ? '设定草稿' : mode === 'edit' ? '编辑设定' : '新增设定'}</h3>
            <label htmlFor={`${fieldId}-type`}>类型</label>
            <select id={`${fieldId}-type`} disabled={locked} value={form.type} onChange={event => setForm({ ...form, type: event.target.value })}>
              {Object.entries(types).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
            <label htmlFor={`${fieldId}-title`}>标题</label>
            <input id={`${fieldId}-title`} disabled={locked} value={form.title} maxLength={80} required placeholder="例如：林舟"
              onChange={event => setForm({ ...form, title: event.target.value })} />
            <label htmlFor={`${fieldId}-content`}>设定内容</label>
            <textarea id={`${fieldId}-content`} disabled={locked} value={form.content} maxLength={2000} required placeholder="记录人物经历、世界规则或写作要求…"
              onChange={event => setForm({ ...form, content: event.target.value })} />
            <small className="settingCharacterCount">{form.content.length} / 2000</small>
          </ScrollArea>
          <div className="settingActions">
            <span className="settingDraftStatus">{dirty ? '未保存' : ''}</span>
            {mode === 'review' && <button type="button" className="link" disabled={locked} onClick={rejectProposal}>忽略建议</button>}
            <button type="button" className="secondary" disabled={busy} onClick={cancelEdit}>取消</button>
            <button type="submit" disabled={locked || !dirty || mode === 'review' && conflicted || !form.title.trim() || !form.content.trim()}>{busy ? '保存中…' : '保存设定'}</button>
          </div>
        </form>}
      </section>
    </div>
  </Dialog>
}
