import React, { useState } from 'react'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { SkillLibrary } from '../skills/SkillLibrary.jsx'
import './preferences.css'

export function AppSettings({ onClose, onSkillsChange }) {
  const [busy, setBusy] = useState(false), [dirty, setDirty] = useState(false)
  function close() {
    if (busy || dirty && !window.confirm('Skill 编辑尚未保存，放弃修改并关闭设置？')) return
    onClose()
  }
  return <Dialog title="设置" size="wide" className="appSettingsDialog" busy={busy}
    showClose closeLabel="关闭设置" onCancel={close}>
    <div className="appSettingsLayout">
      <nav className="appSettingsNavigation" aria-label="设置分类">
        <button type="button" aria-current="page"><Icon name="skill" />Skill</button>
      </nav>
      <section className="appSettingsContent" aria-label="Skill 设置">
        <SkillLibrary onChange={onSkillsChange} onBusyChange={setBusy} onDirtyChange={setDirty} />
      </section>
    </div>
  </Dialog>
}
