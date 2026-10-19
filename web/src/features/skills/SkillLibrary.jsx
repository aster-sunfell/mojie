import React, { useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import { loadSkillList } from './skills.api.js'
import { SkillGuide } from './SkillGuide.jsx'
import './skills.css'

export function SkillLibrary({ onChange, onBusyChange, onDirtyChange }) {
  const [items, setItems] = useState([]), [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState(''), [filter, setFilter] = useState(''), [adding, setAdding] = useState(false)
  const [detail, setDetail] = useState(null), [versions, setVersions] = useState([]), [versionCursor, setVersionCursor] = useState(null)
  const [preview, setPreview] = useState(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [capabilities, setCapabilities] = useState(null), [edit, setEdit] = useState(false), [text, setText] = useState('')
  const [guideOpen, setGuideOpen] = useState(false)
  const guideOpener = useRef(null)
  const selection = useRef(0), pending = useRef(false)
  const dirty = edit && text !== preview?.content
  useEffect(() => { onBusyChange?.(busy) }, [busy, onBusyChange])
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])
  async function load() {
    const all = await loadSkillList()
    setItems(all)
    setDetail(current => current ? { ...current, skill: all.find(item => item.skill.id === current.skill.id)?.skill || current.skill } : null)
  }
  useEffect(() => {
    let disposed = false
    loadSkillList().then(value => { if (!disposed) setItems(value) }).catch(e => { if (!disposed) setError(readableError(e)) })
      .finally(() => { if (!disposed) setLoading(false) })
    api('/skills/capabilities').then(value => { if (!disposed) setCapabilities(value) }).catch(() => {})
    return () => { disposed = true; selection.current++ }
  }, [])
  function canDiscard() { return !dirty || window.confirm('Skill 编辑尚未保存，放弃修改？') }
  async function action(fn) {
    if (pending.current) return
    pending.current = true; setBusy(true); setError('')
    try { await fn(); await load(); onChange?.() }
    catch (e) { setError(readableError(e)) }
    finally { pending.current = false; setBusy(false) }
  }
  async function previewFile(value, file, token) {
    const url = `/api/v1/skills/${value.skill.id}/versions/${value.version.id}/files/${file.id}`
    if (!file.mime.startsWith('text/') && file.mime !== 'application/json') {
      if (selection.current === token) { setPreview({ file, url, binary: true }); setEdit(false) }
      return
    }
    const response = await fetch(url)
    if (!response.ok) throw new Error('文件不可用')
    const content = new TextDecoder('utf-8', { fatal: true }).decode(await response.arrayBuffer())
    if (selection.current !== token) return
    setPreview({ file, url, content }); setText(content); setEdit(false)
  }
  async function inspect(item, version = null, force = false) {
    if (!force && !canDiscard()) return
    const token = ++selection.current
    const [history, value] = await Promise.all([
      api(`/skills/${item.skill.id}/versions?limit=50`),
      api(`/skills/${item.skill.id}/versions/${version || item.skill.currentVersionId}`),
    ])
    if (token !== selection.current) return
    const selected = { ...value, skill: item.skill }
    setVersions(history.items); setVersionCursor(history.nextCursor); setDetail(selected); setPreview(null); setEdit(false)
    const body = value.files.find(file => file.relativePath === 'SKILL.md')
    if (body) await previewFile(selected, body, token)
  }
  async function upload(files, update = null) {
    if (!files.length || !canDiscard()) return
    await action(async () => {
      const form = new FormData()
      Array.from(files).forEach(file => {
        form.append('files', file)
        form.append('paths', file.webkitRelativePath || (files.length === 1 && file.name.endsWith('.md') ? 'SKILL.md' : file.name))
      })
      const response = await fetch(`/api/v1/skills${update ? `/${update}/versions` : '/import'}`, { method: 'POST', body: form })
      const value = await response.json()
      if (!response.ok) { const e = new Error(value.message || '上传失败'); e.code = value.code; throw e }
      let result = value.data
      while (!['READY', 'FAILED'].includes(result.status)) {
        await new Promise(resolve => setTimeout(resolve, 500)); result = await api(`/skill-uploads/${result.id}`)
      }
      if (result.status === 'FAILED') throw new Error(`${result.errorCode}：${result.errorDetail}`)
      setAdding(false)
      await inspect(await api(`/skills/${result.skillId}`), null, true)
    })
  }
  const shown = items.filter(item => (!filter || item.skill.enabled === (filter === 'enabled'))
    && `${item.skill.name} ${item.currentVersion?.metadata.description || ''}`.toLocaleLowerCase().includes(keyword.trim().toLocaleLowerCase()))
  return <div className="skillLibrary">
    {guideOpen && <SkillGuide onBack={() => { setGuideOpen(false); requestAnimationFrame(() => guideOpener.current?.focus()) }} />}
    <div hidden={guideOpen}>
    <div className="skillLibraryHeading"><div><h3>Skill</h3><p>助手会根据任务自动发现并按需加载已启用的 Skill，也可以在消息中用 $ 引用。</p></div>
      <button type="button" disabled={busy} aria-expanded={adding} onClick={() => setAdding(value => !value)}>添加 Skill</button>
    </div>
    <div className="skillScope">
      <p>Skill 用于按需加载角色、写作方法和参考资料，助手可结合当前作品的章节与设定进行分析。不支持运行脚本或调用外部服务；正文和设定的修改由你审阅后保存。</p>
      <button type="button" className="link" ref={guideOpener} disabled={busy} onClick={() => setGuideOpen(true)}>如何创建 Skill →</button>
    </div>
    {adding && <div className="skillImport"><p>支持 Markdown、ZIP 或目录。导入后预览指导并启用，所有作品共用此技能库。</p>
      <div className="row"><label className="uploadButton">导入 Markdown / ZIP<input aria-label="导入 Markdown / ZIP" type="file" accept=".md,.zip" disabled={busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label>
        <label className="uploadButton">导入目录<input aria-label="导入目录" type="file" webkitdirectory="" multiple disabled={busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label></div>
    </div>}
    <div className="skillLibraryFilters"><input type="search" aria-label="搜索 Skill" placeholder="搜索名称或用途" value={keyword} onChange={e => setKeyword(e.target.value)} />
      <select aria-label="Skill 状态" value={filter} onChange={e => setFilter(e.target.value)}><option value="">全部状态</option><option value="enabled">已启用</option><option value="disabled">已停用</option></select></div>
    {busy && <p role="status">正在校验并保存…</p>}{error && <div role="alert"><p className="error">{error}</p><button className="link" disabled={busy} onClick={() => { setError(''); load().catch(e => setError(readableError(e))) }}>重试加载</button></div>}
    {loading && <p role="status">正在加载 Skill…</p>}
    {!loading && !error && shown.length === 0 && <p className="skillEmpty">{items.length ? '没有匹配的 Skill。' : '还没有 Skill，点击“添加 Skill”导入。'}</p>}
    <div className="skillLibraryBody"><div className="skillLibraryList">{shown.map(item => <button type="button" key={item.skill.id} className={`skillCard ${detail?.skill.id === item.skill.id ? 'current' : ''}`}
      aria-current={detail?.skill.id === item.skill.id ? 'true' : undefined} disabled={busy}
      onClick={() => inspect(item).catch(e => setError(readableError(e)))}>
      <strong>{item.skill.name}</strong><span>{item.currentVersion?.metadata.description}</span>
      <small>{item.currentVersion?.status === 'UNAVAILABLE' ? '包不可用 · ' : ''}{item.skill.enabled ? '已启用' : '已停用'}</small>
    </button>)}</div>
    {detail && <section className="skillDetail" aria-label={`${detail.skill.name} 详情`}>
      <div className="skillDetailHeading"><h3>{detail.skill.name}</h3><button type="button" className="link" disabled={busy} onClick={() => { if (canDiscard()) { selection.current++; setDetail(null); setPreview(null); setEdit(false) } }}>关闭详情</button></div>
      <p>{detail.version.metadata.description}</p>
      {detail.version.compatibility?.warnings?.length > 0 && <div className="skillCompatibility" role="note" aria-label="此 Skill 的能力限制">
        <p>此包包含不支持的能力，请预览指导并调整相关步骤；导入成功表示文件已保存。</p>
        <ul>{detail.version.compatibility.warnings.map(warning => <li key={warning}>{warning}</li>)}</ul>
        {detail.version.compatibility.scripts?.length > 0 && <p>脚本文件：{detail.version.compatibility.scripts.join('、')}</p>}
      </div>}
      <div className="skillDetailActions"><button disabled={busy} className="secondary" onClick={() => action(() => api(`/skills/${detail.skill.id}`, 'PATCH', { expectedVersion: detail.skill.version, enabled: !detail.skill.enabled }))}>{detail.skill.enabled ? '停用' : '启用'}</button>
        <label className="uploadButton">上传新版<input aria-label="上传新版" type="file" accept=".md,.zip" disabled={busy} onChange={e => { upload(e.target.files, detail.skill.id); e.target.value = '' }} /></label>
        <button disabled={busy} className="link danger" onClick={() => { if (window.confirm('从技能库移除此 Skill？历史版本和使用记录会保留。')) action(async () => { await api(`/skills/${detail.skill.id}?expectedVersion=${detail.skill.version}`, 'DELETE'); selection.current++; setDetail(null); setPreview(null); setEdit(false) }) }}>删除</button></div>
      {preview && <section className="skillPreview"><h4>{preview.file.relativePath}</h4>
        {preview.binary ? <a href={preview.url} download>下载文件</a> : <>
          {edit ? <><textarea aria-label="Skill 文件内容" className="skillTextEditor" disabled={busy} value={text} onChange={e => setText(e.target.value)} />
            <div className="row"><button disabled={busy} onClick={() => action(async () => {
              const result = await api(`/skills/${detail.skill.id}/versions`, 'POST', { baseVersionId: detail.version.id, files: { [preview.file.relativePath]: text } })
              if (result.status !== 'READY') throw new Error(result.errorDetail)
              await inspect(await api(`/skills/${detail.skill.id}`), null, true)
            })}>保存为新版本</button><button className="secondary" disabled={busy} onClick={() => { if (canDiscard()) { setText(preview.content); setEdit(false) } }}>取消编辑</button></div></>
            : <>{preview.file.relativePath.endsWith('.md') ? <Markdown content={preview.file.relativePath === 'SKILL.md' ? preview.content.replace(/^---\r?\n[\s\S]*?\r?\n---(?:\r?\n|$)/, '') : preview.content} /> : <pre>{preview.content}</pre>}
              <button className="link" disabled={busy} onClick={() => setEdit(true)}>编辑并发布新版本</button></>}
        </>}
      </section>}
      <details className="skillAdvanced"><summary>高级：版本与文件</summary>
        <label>版本<select aria-label="Skill 版本" disabled={busy} value={detail.version.id} onChange={e => inspect({ skill: detail.skill }, e.target.value).catch(e => setError(readableError(e)))}>{versions.map(v => <option key={v.id} value={v.id}>{new Date(v.createdAt).toLocaleString()} · {v.id}</option>)}</select></label>
        {versionCursor && <button className="link" disabled={busy} onClick={async () => { const token = selection.current; try { const page = await api(`/skills/${detail.skill.id}/versions?limit=50&cursor=${encodeURIComponent(versionCursor)}`); if (token === selection.current) { setVersions(current => [...current, ...page.items]); setVersionCursor(page.nextCursor) } } catch (e) { setError(readableError(e)) } }}>更多版本</button>}
        <a href={`/api/v1/skills/${detail.skill.id}/versions/${detail.version.id}/export`} download>导出完整 ZIP</a>
        {detail.version.id !== detail.skill.currentVersionId && <button disabled={busy} onClick={() => action(() => api(`/skills/${detail.skill.id}`, 'PATCH', { expectedVersion: detail.skill.version, currentVersionId: detail.version.id }))}>设为当前版本</button>}
        <ul className="skillFileTree">{detail.files.map(file => <li key={file.id}><button className="link" disabled={busy} onClick={() => { if (canDiscard()) previewFile(detail, file, ++selection.current).catch(e => setError(readableError(e))) }}>{file.relativePath}</button><small> {file.sizeBytes} bytes</small></li>)}</ul>
        <pre>{JSON.stringify(detail.version.metadata, null, 2)}</pre>
        <p>按需读取指导和参考文本。脚本文件仅保存、预览和导出，不执行。</p>
      </details>
    </section>}</div>
    {capabilities && <details className="skillAdvanced"><summary>导入限制</summary><p>上传上限 {capabilities.maxRawUploadBytes / 1048576} MiB · 最多 {capabilities.maxFiles} 个文件</p></details>}
    </div>
  </div>
}
