import React, { useState } from 'react'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { ProjectCard } from './ProjectCard.jsx'
import { ProjectSearch } from './ProjectSearch.jsx'
import { CreateProjectDialog } from './CreateProjectDialog.jsx'
import { useProjects } from './useProjects.js'
import styles from './ProjectHome.module.css'

export function ProjectHome({ open }) {
  const [keyword, setKeyword] = useState('')
  const [creating, setCreating] = useState(false)
  const { projects, cursor, error, loading, loadingMore, removing, setRemoving,
    deleting, load, confirmRemove } = useProjects(keyword)

  return <main className={styles.home}>
    <header className={styles.header}>
      <span className="brand">墨界 <small>Mojie</small></span>
      <div className={styles.heading}>
        <div><h1>你的作品</h1><p>从章节开始写作，再让 Agent 查阅证据并给出建议。</p></div>
        <button className={styles.createButton} onClick={() => setCreating(true)}>
          <Icon name="plus" />新建作品
        </button>
      </div>
    </header>
    <ProjectSearch value={keyword} onChange={setKeyword} />
    {error && <div className={styles.error} role="alert">
      <p className="error">{error}</p>
      <button className="secondary" onClick={() => load()}>重试加载</button>
    </div>}
    <section className={styles.grid} aria-label="作品列表" aria-busy={loading || loadingMore}>
      {projects.map(project => <ProjectCard key={project.id} project={project} open={open} onRemove={setRemoving} />)}
    </section>
    <div role="status" aria-live="polite">
      {loading && <p className={styles.empty}>正在加载作品…</p>}
      {!loading && !error && projects.length === 0 && <div className={styles.empty}>
        <p>{keyword.trim() ? '没有匹配的作品' : '暂无作品，开始创作你的第一部作品吧。'}</p>
        {keyword.trim()
          ? <button className="secondary" onClick={() => setKeyword('')}>清空搜索</button>
          : <button className="secondary" onClick={() => setCreating(true)}>新建作品</button>}
      </div>}
    </div>
    {cursor && <div className={styles.pagination}>
      <button className="secondary" disabled={loadingMore} onClick={() => load(cursor)}>
        {loadingMore ? '加载中…' : '加载更多作品'}
      </button>
    </div>}
    {creating && <CreateProjectDialog onCreated={open} onCancel={() => setCreating(false)} />}
    {removing && <ConfirmDialog title="删除作品" busy={deleting} confirmLabel="确认删除"
      onConfirm={confirmRemove} onCancel={() => setRemoving(null)}>
      <p>确定删除作品《{removing.title}》吗？</p>
      <p><small className="muted">其章节、设定与任务记录将一并删除，此操作无法撤销。</small></p>
    </ConfirmDialog>}
  </main>
}
