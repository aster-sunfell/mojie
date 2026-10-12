// @vitest-environment jsdom
import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, test } from 'vitest'
import { ConversationActivity } from './ConversationActivity.jsx'

afterEach(cleanup)

test('streamed text is visible while running and is replaced by committed commentary without duplication', () => {
  const run = { id: 'run', status: 'RUNNING', steps: [], activities: [],
    output: { modelCallIndex: 1, text: '我会先查阅作品信息。', status: 'STARTED' } }
  const { rerender } = render(<ConversationActivity run={run} chapterNames={{}} />)
  expect(screen.getByText('我会先查阅作品信息。')).toBeTruthy()
  expect(screen.getByText('生成中')).toBeTruthy()
  rerender(<ConversationActivity run={{ ...run, output: { ...run.output, status: 'SUCCEEDED', finishReason: 'TOOL_CALLS' },
    activities: [{ id: 'commentary', modelCallIndex: 1, text: run.output.text }] }} chapterNames={{}} />)
  expect(screen.getAllByText('我会先查阅作品信息。')).toHaveLength(1)
  expect(screen.queryByText('生成中')).toBeNull()
})

test('unfinished drafts remain marked after failure and disappear when the final answer succeeds', () => {
  const run = { id: 'run', status: 'FAILED', activities: [], steps: [],
    output: { modelCallIndex: 2, text: '尚未完成的分析', status: 'FAILED' } }
  const { rerender } = render(<ConversationActivity run={run} chapterNames={{}} />)
  expect(screen.getByText('尚未完成的分析')).toBeTruthy()
  expect(screen.getByText('未完成的回复')).toBeTruthy()
  rerender(<ConversationActivity run={{ ...run, status: 'SUCCEEDED', analysis: '正式回答' }} chapterNames={{}} />)
  expect(screen.queryByText('尚未完成的分析')).toBeNull()
  expect(screen.queryByText('未完成的回复')).toBeNull()
})
