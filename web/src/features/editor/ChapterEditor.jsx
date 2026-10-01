import React, { useEffect, useRef } from 'react'
import { cp } from '../../shared/utils/text.js'
import { Settings } from '../settings/Settings.jsx'
import { History } from '../history/History.jsx'
import { Proposal } from '../proposals/Proposal.jsx'

export function ChapterEditor({ projectId, chapter, draft, saveStatus, dirty, accepting,
  error, showSettings, runBusy, showHistory, onHistoryChange, onSave, onDraftChange,
  review, reviewOpen, onReviewToggle, onEdit, editorSelection, reviewSelection }) {
  const editor = useRef(null)
  useEffect(() => {
    if (reviewOpen || !editorSelection || !editor.current) return
    const element = editor.current
    element.focus()
    if (editorSelection.start != null) {
      element.setSelectionRange(editorSelection.start, editorSelection.end)
      const line = draft.slice(0, editorSelection.start).split('\n').length - 1
      element.scrollTop = Math.max(0, line * 36 - element.clientHeight / 3)
    }
  }, [reviewOpen, editorSelection])
  return <main className="editorArea">
    {showSettings ? <Settings projectId={projectId} disabled={runBusy} /> : chapter ? <>
      <div className="editorHeader">
        <div>
          <span className="eyebrow">CHAPTER {chapter.chapterOrder}</span>
          <h1>{chapter.title}</h1>
          <p>版本 {chapter.version} · {cp(draft)} 字 · {saveStatus}</p>
        </div>
        <div className="row">
          <button className="secondary" onClick={() => onHistoryChange(true)}>查看历史</button>
          <button onClick={onSave} disabled={!dirty || accepting || saveStatus === '保存中'}>保存正文</button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {review?.proposal && <div className="editorTabs" role="group" aria-label="正文与修改审阅">
        <button className={!reviewOpen ? 'selectedTab' : 'secondary'} aria-pressed={!reviewOpen} onClick={() => onReviewToggle(false)}>编辑正文</button>
        <button className={reviewOpen ? 'selectedTab' : 'secondary'} aria-pressed={reviewOpen} onClick={() => onReviewToggle(true)}>审阅修改 · {review.items.filter(item => item.status === 'PENDING').length}</button>
      </div>}
      {review?.proposal && reviewOpen ? <Proposal review={review} draft={draft}
        busy={saveStatus === '保存中'} onEdit={onEdit} selection={reviewSelection} />
        : <textarea ref={editor} className="editor" aria-label="章节正文" value={draft} disabled={accepting}
          onChange={event => onDraftChange(event.target.value)} placeholder="从这里开始写作…" />}
      {showHistory && <History chapterId={chapter.id} close={() => onHistoryChange(false)} />}
    </> : <div className="emptyEditor">从左侧选择章节，或新建第一章。</div>}
  </main>
}
