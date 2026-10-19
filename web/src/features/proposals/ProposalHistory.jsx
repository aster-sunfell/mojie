import React from 'react'
import { Dialog } from '../../shared/ui/Dialog.jsx'
import { lineRange } from './merge.js'
import { InlineText } from './InlineText.jsx'

export function ProposalHistory({ proposal, items, onClose }) {
  return <Dialog title="修改记录" onCancel={onClose} showClose size="wide" className="proposalHistoryDialog">
    <p className="muted">原文取自生成建议时的版本，完整正文以章节历史和当前正文为准。</p>
    {items.filter(item => item.status !== 'PENDING' && !item.unsaved).map((item, index) => {
      const range = lineRange(proposal.baseContent, item.start, item.end)
      const original = proposal.baseContent.slice(range.start, range.end)
      const suggested = item.editedContent ?? proposal.baseContent.slice(range.start, item.start) + item.after
        + proposal.baseContent.slice(item.end, range.end)
      const applied = item.status === 'APPLIED'
      return <section className="proposalHistoryItem" key={item.id}>
        <h3>修改 {items.findIndex(value => value.id === item.id) + 1} · {applied ? '已应用' : '已忽略'}
          {item.savedVersion != null && <small>版本 {item.savedVersion}</small>}</h3>
        <p>建议基准原文</p>
        <div className="diffLine diffBefore"><InlineText readOnly startLine={item.lineNumber} label={`记录 ${index + 1} 原文`} value={original || '（原无文字）'} /></div>
        <p>{applied ? '采纳的建议' : '未采纳的建议'}</p>
        <div className="diffLine diffAfter"><InlineText readOnly startLine={item.lineNumber} label={`记录 ${index + 1} 建议`} value={suggested || '（删除此处文字）'} /></div>
      </section>
    })}
  </Dialog>
}
