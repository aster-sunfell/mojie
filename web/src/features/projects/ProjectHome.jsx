import React from 'react'
import { ConfirmDialog } from '../../shared/ui/ConfirmDialog.jsx'
import { ProjectCard } from './ProjectCard.jsx'
import { useProjects } from './useProjects.js'

export function ProjectHome({ open }) {
  const { projects, cursor, title, setTitle, error, removing, setRemoving,
    deleting, load, create, confirmRemove } = useProjects(open)
  return <main className="home"><header><span className="brand">墨界 <small>Mojie</small></span><h1>你的作品</h1><p>从章节开始写作，再让 Agent 查阅证据并给出建议。</p></header>
    <form className="card create" onSubmit={create}><label>新建作品</label><div className="row"><input value={title} onChange={e => setTitle(e.target.value)} maxLength={80} placeholder="作品名称" required /><button>创建</button></div></form>
    {error && <p className="error">{error}</p>}
    <section className="projectGrid">{projects.map(project => <ProjectCard key={project.id} project={project} open={open} onRemove={setRemoving} />)}</section>
    {cursor && <button className="secondary" onClick={() => load(cursor)}>加载更多作品</button>}
    {removing && <ConfirmDialog title="删除作品" busy={deleting} confirmLabel="确认删除" onConfirm={confirmRemove} onCancel={() => setRemoving(null)}>
      <p>确定删除作品《{removing.title}》吗？</p>
      <p><small className="muted">其章节、设定与任务记录将一并删除，此操作无法撤销。</small></p>
    </ConfirmDialog>}
  </main>
}
