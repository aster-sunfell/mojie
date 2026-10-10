import React, { useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Drawer } from '../../shared/ui/Drawer.jsx'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import './skills.css'

export function SkillLibrary({ onClose, onChange }) {
  const [items, setItems] = useState([]), [cursor, setCursor] = useState(null)
  const [detail, setDetail] = useState(null), [versions, setVersions] = useState([])
  const [versionCursor, setVersionCursor] = useState(null)
  const [preview, setPreview] = useState(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [capabilities, setCapabilities] = useState(null)
  const [edit, setEdit] = useState(false), [text, setText] = useState('')
  const selection = useRef(0)
  async function load(after = null) {
    const page = await api(`/skills?limit=50${after ? `&cursor=${encodeURIComponent(after)}` : ''}`)
    setItems(current => after ? [...current, ...page.items] : page.items); setCursor(page.nextCursor)
  }
  useEffect(() => { load().catch(e => setError(readableError(e))); api('/skills/capabilities').then(setCapabilities).catch(() => {}) }, [])
  async function action(fn) { setBusy(true); setError(''); try { await fn(); await load(); onChange?.() } catch (e) { setError(readableError(e)) } finally { setBusy(false) } }
  async function inspect(item, version = null) {
    const token = ++selection.current
    const history = await api(`/skills/${item.skill.id}/versions?limit=50`)
    const value = await api(`/skills/${item.skill.id}/versions/${version || item.skill.currentVersionId}`)
    if (token !== selection.current) return
    setVersions(history.items); setVersionCursor(history.nextCursor); setDetail({ ...value, skill: item.skill }); setPreview(null); setEdit(false)
  }
  async function upload(files, update = null) {
    if (!files.length) return
    await action(async () => {
      const form = new FormData()
      Array.from(files).forEach(file => { form.append('files', file); form.append('paths', file.webkitRelativePath || (files.length === 1 && file.name.endsWith('.md') ? 'SKILL.md' : file.name)) })
      const response = await fetch(`/api/v1/skills${update ? `/${update}/versions` : '/import'}`, { method: 'POST', body: form })
      const value = await response.json()
      if (!response.ok) throw new Error(value.message || '上传失败')
      let result = value.data
      while (!['READY', 'FAILED'].includes(result.status)) {
        await new Promise(resolve => setTimeout(resolve, 500)); result = await api(`/skill-uploads/${result.id}`)
      }
      if (result.status === 'FAILED') throw new Error(`${result.errorCode}：${result.errorDetail}`)
      const item = await api(`/skills/${result.skillId}`); await inspect(item)
    })
  }
  async function openFile(file) {
    const token = ++selection.current
    const url = `/api/v1/skills/${detail.skill.id}/versions/${detail.version.id}/files/${file.id}`
    if (!file.mime.startsWith('text/') && file.mime !== 'application/json') { setPreview({ file, url, binary: true }); return }
    const response = await fetch(url)
    if (!response.ok) throw new Error('文件不可用')
    const content = new TextDecoder('utf-8', { fatal: true }).decode(await response.arrayBuffer())
    if (selection.current !== token) return
    setPreview({ file, url, content }); setText(content); setEdit(false)
  }
  return <Drawer title="Skill 库" onClose={onClose}><div className="skillLibrary">
    <p>上传完整技能包，查看文件后启用。每轮可按任务发现，或选择固定版本。</p>
    <div className="row">
      <label className="uploadButton">导入 Markdown / ZIP<input type="file" accept=".md,.zip" disabled={busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label>
      <label className="uploadButton">导入目录<input type="file" webkitdirectory="" multiple disabled={busy} onChange={e => { upload(e.target.files); e.target.value = '' }} /></label>
    </div>
    {capabilities && <p className="muted">上传上限 {capabilities.maxRawUploadBytes / 1048576} MiB · 最多 {capabilities.maxFiles} 个文件 · {capabilities.executor.configured ? 'Python / Node 执行器已配置' : '可读取，脚本执行器未配置'}</p>}
    {busy && <p role="status">正在校验并保存…</p>}{error && <p className="error" role="alert">{error}</p>}
    {items.map(item => <article key={item.skill.id} className="skillCard">
      <button className="link" onClick={() => inspect(item).catch(e => setError(readableError(e)))}>{item.skill.name}</button>
      <p>{item.currentVersion?.metadata.description}</p>
      <small>{item.currentVersion?.status === 'UNAVAILABLE' ? '包丢失或损坏 · ' : ''}{item.skill.enabled ? '已启用' : '已停用'} · {item.currentVersion?.fileCount} 个文件 · {Math.ceil((item.currentVersion?.sizeBytes || 0) / 1024)} KiB</small>
      <p className="muted">版本 {item.skill.currentVersionId}</p>
      <button disabled={busy} className="secondary" onClick={() => action(() => api(`/skills/${item.skill.id}`, 'PATCH', { expectedVersion: item.skill.version, enabled: !item.skill.enabled }))}>{item.skill.enabled ? '停用' : '启用'}</button>
      <label className="uploadButton">上传新版<input type="file" accept=".md,.zip" disabled={busy} onChange={e => { upload(e.target.files, item.skill.id); e.target.value = '' }} /></label>
      <button disabled={busy} className="link danger" onClick={() => { if (window.confirm('从技能库移除此 Skill？历史引用的版本会保留。')) action(() => api(`/skills/${item.skill.id}?expectedVersion=${item.skill.version}`, 'DELETE')) }}>删除</button>
    </article>)}
    {cursor && <button onClick={() => load(cursor)}>加载更多</button>}
    {detail && <section className="skillDetail"><h3>{detail.skill.name} · 包详情</h3>
      <label>版本<select value={detail.version.id} onChange={e => inspect({ skill: detail.skill }, e.target.value).catch(e => setError(readableError(e)))}>{versions.map(v => <option key={v.id} value={v.id}>{v.id} · {new Date(v.createdAt).toLocaleString()}</option>)}</select></label>
      {versionCursor && <button className="link" onClick={async () => { const token = selection.current; try { const page = await api(`/skills/${detail.skill.id}/versions?limit=50&cursor=${encodeURIComponent(versionCursor)}`); if (token === selection.current) { setVersions(current => [...current, ...page.items]); setVersionCursor(page.nextCursor) } } catch (e) { setError(readableError(e)) } }}>更多版本</button>}
      <a href={`/api/v1/skills/${detail.skill.id}/versions/${detail.version.id}/export`} download>导出完整 ZIP</a>
      {detail.version.id !== detail.skill.currentVersionId && <button disabled={busy} onClick={() => action(async () => { const s = await api(`/skills/${detail.skill.id}`, 'PATCH', { expectedVersion: detail.skill.version, currentVersionId: detail.version.id }); setDetail(current => ({ ...current, skill: s })) })}>设为当前版本</button>}
      <pre>{JSON.stringify(detail.version.metadata, null, 2)}</pre>
      <p>{detail.version.compatibility.partialExecution ? '可读取，部分脚本环境不支持' : '支持 Python / Node 脚本；依赖以执行环境为准'}</p>
      <ul className="skillFileTree">{detail.files.map(file => <li key={file.id}><button className="link" onClick={() => openFile(file).catch(e => setError(readableError(e)))}>{file.relativePath}</button><small> {file.sizeBytes} bytes</small></li>)}</ul>
      {preview && <section><h4>{preview.file.relativePath}</h4><a href={preview.url} download>下载文件</a>
        {preview.binary ? <p>二进制资源，按原始字节下载。</p> : <>
          <button className="link" onClick={() => setEdit(!edit)}>{edit ? '取消编辑' : '编辑并发布新版本'}</button>
          {edit ? <><textarea className="skillTextEditor" value={text} onChange={e => setText(e.target.value)} /><button disabled={busy} onClick={() => action(async () => { const result = await api(`/skills/${detail.skill.id}/versions`, 'POST', { baseVersionId: detail.version.id, files: { [preview.file.relativePath]: text } }); if (result.status !== 'READY') throw new Error(result.errorDetail); await inspect(await api(`/skills/${detail.skill.id}`)) })}>保存为新版本</button></> : preview.file.relativePath.endsWith('.md') ? <Markdown content={preview.content} /> : <pre>{preview.content}</pre>}
        </>}
      </section>}
    </section>}
  </div></Drawer>
}
