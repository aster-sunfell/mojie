import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'

export function History({ chapterId, close }) {
  const [revisions, setRevisions] = useState([])
  const [cursor, setCursor] = useState(null)
  const [selected, setSelected] = useState(null)
  const [error, setError] = useState('')
  const load = useCallback((after = null) => api(`/chapters/${chapterId}/revisions?limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
    .then(page => { setRevisions(current => after ? [...current, ...page.items] : page.items); setCursor(page.nextCursor) })
    .catch(e => setError(readableError(e))), [chapterId])
  useEffect(() => { load() }, [load])
  async function view(version) {
    try { setSelected(await api(`/chapters/${chapterId}/revisions/${version}`)) }
    catch (e) { setError(readableError(e)) }
  }
  return <div className="overlay"><div className="drawer"><div className="sectionTitle"><h2>章节历史</h2><button className="secondary" onClick={close}>关闭</button></div>
    {error && <p className="error">{error}</p>}
    <div className="historyList">{revisions.map(item => <button key={item.version} onClick={() => view(item.version)} className={selected?.version === item.version ? 'selected' : ''}>版本 {item.version} · {{ CREATE: '创建', MANUAL: '手动编辑', AGENT: 'AI 采纳', MIXED: 'AI 建议与手动编辑' }[item.source] || item.source} <small>{new Date(item.createdAt).toLocaleString()}</small></button>)}</div>
    {cursor && <button className="secondary" onClick={() => load(cursor)}>加载更多版本</button>}
    {selected && <><h3>只读版本 {selected.version}</h3><pre className="historicalText">{selected.content || '（空章节）'}</pre></>}
  </div></div>
}
