import React from 'react'
import { cp } from '../../shared/utils/text.js'
import { Settings } from '../settings/Settings.jsx'
import { History } from '../history/History.jsx'
import { Proposal } from '../proposals/Proposal.jsx'
import { InlineText } from '../proposals/InlineText.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'

export function ChapterEditor({ projectId, chapter, draft, saveStatus, dirty, accepting,
  error, showSettings, runBusy, showHistory, onHistoryChange, onSave, onDraftChange,
  review }) {
  return <ScrollArea as="main" className="editorArea">
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
      {review?.proposal ? <Proposal review={review} draft={draft}
        busy={saveStatus === '保存中'} onDraftChange={onDraftChange} />
        : <div className="editorDocument"><InlineText className="editor" label="章节正文" value={draft} disabled={accepting}
          onChange={onDraftChange} placeholder="从这里开始写作…" /></div>}
      {showHistory && <History chapterId={chapter.id} close={() => onHistoryChange(false)} />}
    </> : <div className="emptyEditor">从左侧选择章节，或新建第一章。</div>}
  </ScrollArea>
}
