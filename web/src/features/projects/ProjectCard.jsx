import React from 'react'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { timeAgo } from '../../shared/utils/text.js'
import styles from './ProjectCard.module.css'

export function ProjectCard({ project, open, onRemove }) {
  return <article className={styles.card} aria-label={`作品：${project.title}`}>
    <button type="button" className={styles.open} onClick={() => open(project.id)}
      aria-label={`打开作品《${project.title}》`}>
      <span className={styles.seal} aria-hidden="true">{[...project.title.trim()][0] || '墨'}</span>
      <span className={styles.body}>
        <strong>{project.title}</strong>
        <small>更新于 {timeAgo(project.updatedAt)}</small>
      </span>
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
