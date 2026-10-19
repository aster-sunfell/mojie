import React, { useEffect, useRef, useState } from 'react'
import { Markdown } from '../../shared/ui/Markdown.jsx'
import guide from '../../../../docs/user-guide/skills.md?raw'

const template = guide.match(/```markdown\r?\n([\s\S]*?)\r?\n```/)[1]

export function SkillGuide({ onBack }) {
  const heading = useRef(null)
  const [copied, setCopied] = useState(false), [error, setError] = useState('')
  useEffect(() => { heading.current?.focus() }, [])
  async function copy() {
    try {
      await navigator.clipboard.writeText(template)
      setCopied(true); setError('')
    } catch {
      setError('无法自动复制，请选择下方示例内容复制，或下载模板。')
    }
  }
  function download() {
    const url = URL.createObjectURL(new Blob([template + '\n'], { type: 'text/markdown;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url; link.download = 'SKILL.md'; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  return <section className="skillGuide" aria-label="如何创建 Skill">
    <div className="skillGuideActions">
      <button type="button" className="link" ref={heading} onClick={onBack}>返回 Skill 库</button>
      <button type="button" className="secondary" onClick={copy}>复制示例 Skill</button>
      <button type="button" className="link" onClick={download}>下载 SKILL.md 模板</button>
    </div>
    {copied && <p role="status">示例已复制，可保存为 SKILL.md 后导入。</p>}
    {error && <p role="alert">{error}</p>}
    <Markdown content={guide} />
  </section>
}
