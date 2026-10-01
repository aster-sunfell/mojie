import React, { useRef, useState } from 'react'
import { readableError } from '../../shared/api/client.js'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import { projectsApi } from './projects.api.js'
import styles from './ProjectHome.module.css'
import dialogStyles from '../../shared/ui/Dialog.module.css'

export function CreateProjectDialog({ onCreated, onCancel }) {
  const [title, setTitle] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef(false)

  async function create(event) {
    event.preventDefault()
    if (!title.trim() || pending.current) return
    pending.current = true; setBusy(true); setError('')
    try {
      const project = await projectsApi.create(title.trim())
      onCreated(project.id)
    } catch (failure) { setError(readableError(failure)) }
    finally { pending.current = false; setBusy(false) }
  }

  return <Dialog title="新建作品" busy={busy} onCancel={onCancel}>
    <p>为你的新作品起一个名字。</p>
    <form onSubmit={create}>
      <div className={styles.createField}>
        <label htmlFor="project-title">作品名称</label>
        <input id="project-title" data-autofocus value={title} disabled={busy}
          onChange={event => setTitle(event.target.value)} maxLength={80}
          placeholder="输入作品名称" required />
      </div>
      {error && <p className="error" role="alert">{error}</p>}
      <div className={dialogStyles.actions}>
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>取消</button>
        <button type="submit" disabled={busy || !title.trim()}>{busy ? '创建中…' : '创建作品'}</button>
      </div>
    </form>
  </Dialog>
}
