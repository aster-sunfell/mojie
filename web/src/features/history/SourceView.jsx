import React, { useCallback, useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'

export function SourceView({ source, step, close }) {
  const [revision, setRevision] = useState(null)
  const [error, setError] = useState('')
  useEffect(() => {
    if (!source?.chapterId) return
    api(`/chapters/${source.chapterId}/revisions/${source.chapterVersion}`).then(setRevision).catch(e => setError(readableError(e)))
  }, [source])
  useEffect(() => { if (revision && source?.lineNumber) document.getElementById('source-line')?.scrollIntoView({ block: 'center' }) }, [revision, source])
  const setting = step?.result?.data?.settings?.find(item => item.settingId === source.settingId)
  const snippet = step?.result?.data?.items?.find(item => item.settingId === source.settingId && item.codePointOffset === source.codepointOffset)
  return <div className="overlay"><ScrollArea className="drawer"><div className="sectionTitle"><h2>历史来源</h2><button className="secondary" onClick={close}>关闭</button></div>
    <p className="muted">{source.chapterId ? `章节版本 ${source.chapterVersion}` : '任务执行时的设定内容'} · {source.kind === 'SEARCH_SNIPPET' ? '搜索摘录' : '完整读取'} · 不会覆盖编辑器草稿</p>
    {error && <p className="error">{error}</p>}
    {setting && <><h3>{setting.title}</h3><pre className="historicalText">{setting.content}</pre></>}
    {snippet && <><h3>{snippet.title}</h3><pre className="historicalText">{snippet.snippet}</pre></>}
    {revision && <ScrollArea className="sourceLines" tabIndex={0} aria-label="来源正文">{revision.content.split('\n').map((line, index) => <div key={index} id={index + 1 === source.lineNumber ? 'source-line' : undefined} className={index + 1 === source.lineNumber ? 'hitLine' : ''}><span>{index + 1}</span><pre>{line || ' '}</pre></div>)}</ScrollArea>}
  </ScrollArea></div>
}
