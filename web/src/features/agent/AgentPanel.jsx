import React, { useEffect, useState } from 'react'
import { statusName } from './status.js'
import { SourceView } from '../history/SourceView.jsx'
import { RunResult } from './RunResult.jsx'
import { Icon } from '../../shared/ui/Icon.jsx'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { ScrollArea } from '../../shared/ui/ScrollArea.jsx'

export function AgentPanel({ task, chapter, review, onOpenReview, embedded = false }) {
  const [source, setSource] = useState(null)
  const { runs, runCursor, runId, run, runError, loadRuns } = task
  useEffect(() => { setSource(null) }, [runId])

  return <aside className="agentPanel" aria-label="写作助手结果">
    {!embedded && <div className="panelHeader">
      <span className="eyebrow">AGENT</span><h2>写作助手</h2>
      <p>分析与修改建议，由你决定是否采纳。</p>
    </div>}
    <ScrollArea className="agentBody">
      <div className="runList">
        <label htmlFor="run-history">历史任务</label>
        <select id="run-history" aria-label="历史任务" value={runId || ''}
          disabled={runs.length === 0} onChange={event => task.selectRun(event.target.value)}>
          {runs.length === 0 && <option value="">暂无任务</option>}
          {runs.map(item => <option key={item.id} value={item.id}>
            {statusName(item.status)} · {new Date(item.queuedAt).toLocaleString()}
          </option>)}
        </select>
        <Tooltip label="刷新任务"><button className="refreshRuns" aria-label="刷新任务" onClick={() => loadRuns()}>
          <Icon name="refresh" size={16} />
        </button></Tooltip>
        {runs.length === 0 && <p className="muted">{chapter ? '当前章节暂无任务' : '先选择一个章节。'}</p>}
      </div>
      {runCursor && <button className="link" onClick={() => loadRuns(runCursor)}>加载更多任务</button>}
      {task.listError && <p className="error" role="alert">{task.listError}</p>}
      {runError && <p className="error" role="alert">{runError}</p>}
      {!run && chapter && <div className="assistantEmpty">
        <Icon name="message" size={28} /><h3>一起打磨这一章</h3>
        <p>在正文下方输入要求，助手会查阅证据并给出分析或修改建议。</p>
      </div>}
      {run && <RunResult task={task} review={review} onOpenReview={onOpenReview} onSource={setSource} />}
    </ScrollArea>
    {source && <SourceView key={source.id} source={source}
      step={run?.steps?.find(item => item.toolExecutionId === source.toolExecutionId)} close={() => setSource(null)} />}
  </aside>
}
