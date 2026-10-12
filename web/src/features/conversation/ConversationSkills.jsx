import React, { useState } from 'react'
import { Dialog } from '../../shared/ui/Dialog.jsx'

export function ConversationSkills({ skills, onClose, onChoose, loadError, onReload }) {
  const [keyword, setKeyword] = useState('')
  const available = skills.filter(item => item.skill.enabled && item.currentVersion?.status !== 'UNAVAILABLE')
  const shown = available.filter(item => `${item.skill.name} ${item.currentVersion?.metadata.description || ''}`.toLocaleLowerCase().includes(keyword.toLocaleLowerCase()))
  return <Dialog title="引用 Skill" onCancel={onClose} showClose closeLabel="关闭 Skill 引用">
    <div className="conversationSkills">
      <p>引用到消息中，助手会读取该 Skill 后处理任务，也能自动发现其他适用技能。</p>
      <input data-autofocus type="search" aria-label="搜索可引用的 Skill" placeholder="搜索名称或用途" value={keyword} onChange={e => setKeyword(e.target.value)} />
      {loadError && <div role="alert"><p className="error">{loadError}</p><button className="link" onClick={onReload}>重试加载</button></div>}
      <div className="skillReferenceList">{shown.map(item => <button type="button" className="skillReferenceOption" key={item.skill.id} aria-label={`引用 ${item.skill.name}`} onClick={() => onChoose(item)}>
        <strong>{item.skill.name}</strong><span>{item.currentVersion?.metadata.description}</span>
      </button>)}</div>
      {!loadError && shown.length === 0 && <p className="muted">{available.length ? '没有匹配的 Skill。' : '暂无可用 Skill，请在“设置 → Skill”中添加并启用。'}</p>}
    </div>
  </Dialog>
}
