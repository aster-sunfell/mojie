import React from 'react'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'

export function ChapterSidebar({ projectId, project, chapters, selectedChapterId, busy, cursor,
  onLeave, onAddChapter, onLoadMore, inert = false }) {
  return <ScrollArea as="nav" className="sidebar" aria-label="章节导航" inert={inert}>
    <button className="back" onClick={() => onLeave('/')}>← 全部作品</button>
    <h1>{project?.title || '加载中…'}</h1>
    <div className="sectionTitle">
      <h2>章节</h2>
      <button className="iconButton" title="新增章节" onClick={onAddChapter} disabled={busy}>＋</button>
    </div>
    <div className="chapterList">{chapters.map(item => <button key={item.id}
      className={selectedChapterId === item.id ? 'selected' : ''}
      onClick={() => onLeave(`/projects/${projectId}/chapters/${item.id}`)}>
      <small>{String(item.chapterOrder).padStart(2, '0')}</small>
      <strong>{item.title}</strong><small>v{item.version}</small>
    </button>)}</div>
    {cursor && <button className="secondary" onClick={onLoadMore}>加载更多章节</button>}
  </ScrollArea>
}
