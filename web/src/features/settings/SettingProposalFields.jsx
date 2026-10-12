import React from 'react'

const labels = { type: '类型', title: '标题', content: '设定内容' }
const types = { CHARACTER: '人物', WORLD: '世界观', RULE: '写作约束' }

export function SettingProposalFields({ proposal, form, choices, locked, conflicted, onApply, onIgnore, onApplyAll }) {
  const before = { type: proposal.baseType, title: proposal.baseTitle, content: proposal.baseContent }
  const after = { type: proposal.proposedType, title: proposal.proposedTitle, content: proposal.proposedContent }
  const changed = Object.keys(labels).filter(key => before[key] !== after[key])
  const show = (key, value) => key === 'type' ? types[value] : value
  return <section className="settingProposal" aria-label="设定修改建议">
    <div className="settingProposalHeading"><strong>修改建议 · 基于 v{proposal.baseVersion}</strong>
      <button type="button" className="secondary" disabled={locked || conflicted} onClick={onApplyAll}>应用全部修改</button></div>
    <p>{proposal.reason}</p>
    {conflicted && <p className="error" role="alert">设定已发生变化，这份建议不能覆盖当前版本。请保留当前设定并让助手重新生成建议。</p>}
    {changed.map(key => <div className="settingProposalChange" key={key}>
      <div className="settingProposalHeading"><strong>{labels[key]}</strong><span>{choices[key] === 'APPLIED' ? '已应用到草稿' : choices[key] === 'IGNORED' ? '已忽略 · 未保存' : '待处理'}</span></div>
      <pre className="settingProposalBefore" aria-label={`${labels[key]}修改前`}>{show(key, before[key])}</pre>
      <pre className="settingProposalAfter" aria-label={`${labels[key]}修改建议`}>{show(key, after[key])}</pre>
      <div className="settingProposalActions">
        <button type="button" className="secondary" disabled={locked || conflicted} onClick={() => onApply(key, after[key])}>应用{labels[key]}修改</button>
        <button type="button" className="link" disabled={locked} onClick={() => onIgnore(key)}>忽略{labels[key]}修改</button>
      </div>
      {choices[key] === 'APPLIED' && form[key] !== after[key] && <small>已在下方编辑调整。</small>}
    </div>)}
    <p className="hint">应用只更新下方草稿；保存设定后生效，未应用的建议将一并忽略。</p>
  </section>
}
