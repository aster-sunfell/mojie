import React from 'react'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { timeAgo } from '../../shared/utils/text.js'

export function ProjectCard({ project, open, onRemove }) {
  return <div className="projectCard"><button className="projectOpen" onClick={() => open(project.id)}><span className="seal" aria-hidden="true">{[...project.title.trim()][0] || '墨'}</span><span className="cardBody"><strong>{project.title}</strong><small>更新于 {timeAgo(project.updatedAt)}</small></span></button><Tooltip label="删除" className="cardDeleteWrap"><button className="cardDelete" aria-label="删除" onClick={() => onRemove(project)}><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/></svg></button></Tooltip></div>
}
