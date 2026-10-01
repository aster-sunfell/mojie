import React, { useLayoutEffect, useRef } from 'react'
import { cp } from '../../shared/utils/text.js'
import { Icon } from '../../shared/ui/Icon.jsx'
import { Tooltip } from '../../shared/ui/Tooltip.jsx'
import { DEFAULT_INSTRUCTION } from './useAgentTask.js'
import styles from './AgentComposer.module.css'

const examples = [
  { label: '检查设定冲突', instruction: DEFAULT_INSTRUCTION },
  { label: '核对前文', instruction: '查阅前文，检查当前章节中的人物经历、时间顺序与情节是否连贯；请说明依据。' },
]

export function AgentComposer({ chapter, task, dirty, onSave, saveStatus }) {
  const input = useRef(null)
  const composing = useRef(false)
  const count = cp(task.instruction)
  const disabled = task.busy || Boolean(task.blockedReason) || task.taskActive
  const actionLabel = task.busy ? '正在发送' : task.taskActive
    ? task.cancelRequested ? '正在停止' : '停止任务' : '发送要求'

  useLayoutEffect(() => {
    const element = input.current
    element.style.height = 'auto'
    element.style.height = `${Math.min(Math.max(element.scrollHeight, 76), 172)}px`
  }, [task.instruction])

  function send(event) {
    event.preventDefault()
    if (!disabled) task.submit()
  }

  return <section className={styles.composer} aria-label="写作助手输入区">
    <div className={styles.inner}>
      <div className={styles.context}>
        <span className={styles.chapter} title={chapter.title}>{chapter.title}</span>
        <span>版本 {chapter.version} · {dirty ? '未保存' : '已保存'}</span>
      </div>
      <div className={styles.examples}>
        {examples.map(example => <button type="button" key={example.label}
          disabled={task.busy} onClick={() => { task.setInstruction(example.instruction); input.current.focus() }}>
          {example.label}
        </button>)}
      </div>
      <form className={styles.inputBox} onSubmit={send} aria-busy={task.busy}>
        <textarea ref={input} id="instruction" aria-label="给写作助手的要求"
          aria-describedby="agent-input-help" placeholder="告诉助手，你想检查或改进什么…"
          rows={2} value={task.instruction} disabled={task.busy}
          onChange={event => task.setInstruction(event.target.value)}
          onCompositionStart={() => { composing.current = true }}
          onCompositionEnd={() => { composing.current = false }}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey && !composing.current && !event.nativeEvent.isComposing && event.keyCode !== 229) {
              event.preventDefault()
              if (!disabled) task.submit()
            }
          }} />
        <div className={styles.footer}>
          <small className={count > task.maxLength ? styles.limit : styles.count}>
            {count} / {task.maxLength}
          </small>
          <small className={styles.shortcut}>Enter 发送 · Shift+Enter 换行</small>
          <Tooltip label={actionLabel}>
            <button type={task.taskActive ? 'button' : 'submit'} className={styles.send}
              aria-label={actionLabel} disabled={task.busy || (task.taskActive ? task.cancelRequested : disabled)}
              onClick={task.taskActive ? task.cancel : undefined}>
              <span className={task.busy || task.cancelRequested ? styles.spinner : undefined}>
                <Icon name={task.busy || task.cancelRequested ? 'loading' : task.taskActive ? 'stop' : 'send'} size={20} />
              </span>
            </button>
          </Tooltip>
        </div>
      </form>
      <div id="agent-input-help" className={styles.help}>
        {dirty ? <><span>正文未保存，保存后才能发送。</span>
          <button type="button" aria-label="保存正文以发送要求" disabled={saveStatus === '保存中'} onClick={onSave}>
            {saveStatus === '保存中' ? '保存中…' : '保存正文'}
          </button></> : <span>{task.taskActive
          ? task.cancelRequested ? '正在停止任务，请稍候…' : '正在分析，可在右侧查看结果。'
          : task.blockedReason && task.instruction.trim() ? task.blockedReason : '每次发送基于当前已保存章节独立分析。'}</span>}
      </div>
      {task.error && <p className={styles.error} role="alert">{task.error}</p>}
    </div>
  </section>
}
