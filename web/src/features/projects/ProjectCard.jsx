import React from 'react'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { timeAgo } from '../../shared/utils/text.js'
import styles from './ProjectCard.module.css'

export function ProjectCard({ project, open, onRemove }) {
  const tone = [...String(project.id)].reduce((hash, character) => hash + character.codePointAt(0), 0) % 5

  return <article className={styles.card} data-tone={tone} aria-label={`作品：${project.title}`}>
    <button type="button" className={styles.open} onClick={() => open(project.id)}
      aria-label={`打开作品《${project.title}》`}>
      <span className={styles.cover}>
        <span className={styles.imprint} aria-hidden="true">MOJIE</span>
        <strong>{project.title}</strong>
        <span className={styles.seal} aria-hidden="true">墨</span>
      </span>
      <small className={styles.updated}>最近编辑 · {timeAgo(project.updatedAt)}</small>
      {(project.pendingProposalCount > 0 || project.conflictedProposalCount > 0) && <small className={styles.pending}>
        {project.pendingProposalCount > 0 && `${project.pendingProposalCount} 份建议待处理`}
        {project.pendingProposalCount > 0 && project.conflictedProposalCount > 0 && ' · '}
        {project.conflictedProposalCount > 0 && `${project.conflictedProposalCount} 份需核对`}
      </small>}
    </button>
    <div className={styles.actions}>
      <Tooltip label="删除">
        <button type="button" className={styles.delete} aria-label="删除" onClick={() => onRemove(project)}>
          <Icon name="trash" size={16} />
        </button>
      </Tooltip>
    </div>
  </article>
}
