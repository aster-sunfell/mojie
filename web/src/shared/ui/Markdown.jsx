import React from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { ScrollArea } from './ScrollArea.jsx'
import styles from './Markdown.module.css'

const remarkPlugins = [remarkGfm]
const components = {
  a({ node, href, children, ...props }) {
    if (!href) return <span>{children}</span>
    const external = /^(?:https?:)?\/\//i.test(href)
    return <a {...props} href={href} target={external ? '_blank' : undefined}
      rel={external ? 'noopener noreferrer' : undefined}>{children}</a>
  },
  pre({ node, ...props }) {
    return <ScrollArea as="pre" tabIndex={0} aria-label="代码块" {...props} />
  },
  table({ node, ...props }) {
    return <ScrollArea orientation="horizontal" className={styles.tableViewport}
      tabIndex={0} aria-label="表格内容"><table {...props} /></ScrollArea>
  },
  img({ node, ...props }) {
    return <img {...props} loading="lazy" decoding="async" />
  },
}

/** Read-only CommonMark/GFM content. Raw HTML is skipped; URLs use the parser's safe defaults. */
export function Markdown({ content = '', className = '', ...props }) {
  return <div {...props} className={[styles.markdown, className].filter(Boolean).join(' ')}>
    <ReactMarkdown remarkPlugins={remarkPlugins} components={components} skipHtml>
      {content ?? ''}
    </ReactMarkdown>
  </div>
}
