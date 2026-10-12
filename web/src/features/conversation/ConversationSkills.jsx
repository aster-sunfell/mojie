import React, { useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { Dialog } from '../../shared/ui/Dialog.jsx'

export function ConversationSkills({ chat, skills, onClose, onLibrary, loadError }) {
  const [versions, setVersions] = useState({}), [error, setError] = useState(''), [loading, setLoading] = useState(false)
  const current = useRef(chat)
  current.current = chat
  async function choose(item) {
    const id = chat.id
    setLoading(true); setError('')
    try {
      const history = versions[item.skill.id] || (await api(`/skills/${item.skill.id}/versions?limit=50`)).items
      if (current.current.id !== id) return
      setVersions(value => ({ ...value, [item.skill.id]: history }))
      const selected = current.current.draft.selected
      chat.updateDraft({ selected: selected.some(s => s.skillId === item.skill.id)
        ? selected.filter(s => s.skillId !== item.skill.id)
        : [...selected, { skillId: item.skill.id, versionId: chat.pins.find(s => s.skillId === item.skill.id)?.versionId || item.skill.currentVersionId }] })
    } catch (e) { setError(readableError(e)) } finally { setLoading(false) }
  }
  const fixedDisabled = chat.taskActive || chat.busy || chat.conversation?.status !== 'ACTIVE'
  return <Dialog title="本轮 Skill 与对话固定项" onCancel={onClose}>
    <div className="conversationSkills">
      <label className="skillCheck"><input type="checkbox" checked={chat.conversation?.discoveryEnabled ?? chat.draft.discoveryEnabled}
        disabled={chat.taskActive || chat.busy || chat.conversation?.status === 'ARCHIVED'} onChange={event => chat.conversation
          ? chat.patch({ discoveryEnabled: event.target.checked }) : chat.updateDraft({ discoveryEnabled: event.target.checked })} />允许自动发现其他 Skill</label>
      {(error || loadError || chat.error) && <p className="error" role="alert">{error || loadError || chat.error}</p>}
      {chat.pins.map(pin => <div className="pinnedSkill" key={pin.skillId}><span>固定：{skills.find(s => s.skill.id === pin.skillId)?.skill.name || pin.skillId}</span>
        <button className="link" disabled={fixedDisabled} onClick={() => chat.action(() => api(`/conversations/${chat.id}/skills`, 'PUT', chat.pins.filter(p => p.skillId !== pin.skillId)))}>取消固定</button></div>)}
      {skills.filter(s => s.skill.enabled).map(item => {
        const choice = chat.draft.selected.find(s => s.skillId === item.skill.id)
        return <div key={item.skill.id} className="skillChoice"><label className="skillCheck"><input type="checkbox" checked={Boolean(choice)} disabled={chat.busy || loading}
          onChange={() => choose(item)} />{item.skill.name}</label>
          {choice && <><select aria-label={`${item.skill.name} 版本`} disabled={chat.busy} value={choice.versionId}
            onChange={event => chat.updateDraft({ selected: chat.draft.selected.map(s => s.skillId === item.skill.id ? { ...s, versionId: event.target.value } : s) })}>
            {!(versions[item.skill.id] || []).some(v => v.id === choice.versionId) && <option value={choice.versionId}>{choice.versionId}</option>}
            {(versions[item.skill.id] || []).map(v => <option key={v.id} value={v.id}>{v.id}</option>)}</select>
            <button className="link" disabled={fixedDisabled}
              onClick={() => chat.action(() => api(`/conversations/${chat.id}/skills`, 'PUT', [...chat.pins.filter(p => p.skillId !== item.skill.id), choice]))}>固定到对话</button></>}
        </div>
      })}
      {skills.filter(s => s.skill.enabled).length === 0 && <p className="muted">暂无已启用 Skill，可以到 Skill 库导入并启用。</p>}
      <div className="row"><button className="secondary" onClick={onLibrary}>Skill 库</button><button onClick={onClose}>完成</button></div>
    </div>
  </Dialog>
}
