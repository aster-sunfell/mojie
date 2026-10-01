import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'

export function Settings({ projectId, disabled }) {
  const [items, setItems] = useState([])
  const [cursor, setCursor] = useState(null)
  const [form, setForm] = useState({ type: 'CHARACTER', title: '', content: '' })
  const [editing, setEditing] = useState(null)
  const [keyword, setKeyword] = useState('')
  const [error, setError] = useState('')
  const activeKeyword = useRef(keyword)
  const requestNumber = useRef(0)
  activeKeyword.current = keyword
  const load = useCallback((after = null) => {
    const request = ++requestNumber.current
    const params = new URLSearchParams({ limit: '50' })
    if (activeKeyword.current) params.set('keyword', activeKeyword.current)
    if (after) params.set('cursor', after)
    return api(`/projects/${projectId}/settings?${params}`)
      .then(page => {
        if (request !== requestNumber.current) return
        setItems(current => after ? [...current, ...page.items] : page.items)
        setCursor(page.nextCursor)
        setError('')
      })
      .catch(e => { if (request === requestNumber.current) setError(readableError(e)) })
  }, [projectId])
  useEffect(() => { load() }, [load, keyword])
  async function save(event) {
    event.preventDefault()
    try {
      if (editing) await api(`/settings/${editing}`, 'PATCH', form)
      else await api(`/projects/${projectId}/settings`, 'POST', form)
      setForm({ type: 'CHARACTER', title: '', content: '' }); setEditing(null); setError(''); await load()
    } catch (e) { setError(readableError(e)) }
  }
  async function remove(id) {
    if (!window.confirm('确定删除这条设定？')) return
    try { await api(`/settings/${id}`, 'DELETE'); await load() }
    catch (e) { setError(readableError(e)) }
  }
  return <section className="settings"><div className="sectionTitle"><h2>作品设定</h2>{disabled && <small>任务运行期间暂不可修改设定</small>}</div>
    <input value={keyword} onChange={e => { setKeyword(e.target.value); setCursor(null) }} placeholder="筛选标题或正文" />
    {error && <p className="error">{error}</p>}
    <div className="settingList">{items.map(item => <article className="setting" key={item.id}>
      <small>{({ CHARACTER: '人物', WORLD: '世界观', RULE: '写作约束' })[item.type]}</small><strong>{item.title}</strong><p>{item.content}</p>
      <div className="row"><button className="link" disabled={disabled} onClick={() => { setEditing(item.id); setForm({ type: item.type, title: item.title, content: item.content }) }}>编辑</button><button className="link danger" disabled={disabled} onClick={() => remove(item.id)}>删除</button></div>
    </article>)}{items.length === 0 && <p className="muted">{keyword ? '没有匹配的设定' : '暂无设定'}</p>}</div>
    {cursor && <button className="secondary" onClick={() => load(cursor)}>加载更多设定</button>}
    <form onSubmit={save} className="settingForm"><h3>{editing ? '编辑设定' : '新增设定'}</h3>
      <select disabled={disabled} value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}><option value="CHARACTER">人物</option><option value="WORLD">世界观</option><option value="RULE">写作约束</option></select>
      <input disabled={disabled} value={form.title} maxLength={80} required placeholder="标题" onChange={e => setForm({ ...form, title: e.target.value })} />
      <textarea disabled={disabled} value={form.content} maxLength={2000} required placeholder="设定内容" onChange={e => setForm({ ...form, content: e.target.value })} />
      <div className="row"><button disabled={disabled}>保存设定</button>{editing && <button type="button" className="secondary" onClick={() => { setEditing(null); setForm({ type: 'CHARACTER', title: '', content: '' }) }}>取消</button>}</div>
    </form>
  </section>
}
