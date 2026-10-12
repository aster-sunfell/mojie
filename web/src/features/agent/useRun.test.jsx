// @vitest-environment jsdom
import React from 'react'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { useRun } from './useRun.js'

class FakeEventSource extends EventTarget {
  static current
  constructor(url) { super(); this.url = url; this.closed = false; FakeEventSource.current = this }
  close() { this.closed = true }
  emit(type, payload) {
    const event = new Event(type)
    Object.defineProperty(event, 'data', { value: JSON.stringify(payload) })
    this.dispatchEvent(event)
  }
}
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); FakeEventSource.current = null })

test('replay ignores duplicate events and stale detail snapshots', async () => {
  globalThis.EventSource = FakeEventSource
  const snapshots = [
    { id: 'run-1', status: 'RUNNING', lastEventSeq: 2 },
    { id: 'run-1', status: 'RUNNING', lastEventSeq: 2 },
    { id: 'run-1', status: 'SUCCEEDED', lastEventSeq: 3 }
  ]
  globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ data: snapshots.shift() }) }))
  const onChange = vi.fn()
  const { result, unmount } = renderHook(() => useRun('run-1', onChange))
  await waitFor(() => expect(result.current.run?.lastEventSeq).toBe(2))
  expect(FakeEventSource.current.url).toContain('afterSeq=2')
  await act(async () => { FakeEventSource.current.emit('tool.finished', { runId: 'run-1', seq: 3 }) })
  await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  expect(result.current.run.lastEventSeq).toBe(2)
  expect(onChange).toHaveBeenCalledTimes(1)
  act(() => FakeEventSource.current.emit('tool.finished', { runId: 'run-1', seq: 3 }))
  expect(fetch).toHaveBeenCalledTimes(2)
  await act(async () => { await result.current.refresh() })
  expect(result.current.run.status).toBe('SUCCEEDED')
  expect(onChange).toHaveBeenCalledTimes(2)
  unmount()
  expect(FakeEventSource.current.closed).toBe(true)
})

test('polling recovers a terminal task while the event stream is disconnected', async () => {
  globalThis.EventSource = FakeEventSource
  const snapshots = [
    { id: 'run-2', status: 'RUNNING', lastEventSeq: 2 },
    { id: 'run-2', status: 'SUCCEEDED', lastEventSeq: 3 }
  ]
  globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ data: snapshots.shift() }) }))
  const { result, unmount } = renderHook(() => useRun('run-2'))
  await waitFor(() => expect(result.current.run?.status).toBe('RUNNING'))
  vi.useFakeTimers()
  act(() => FakeEventSource.current.onerror())
  expect(result.current.connection).toBe('reconnecting')
  await act(async () => { await vi.advanceTimersByTimeAsync(11_999) })
  expect(fetch).toHaveBeenCalledTimes(1)
  await act(async () => { await vi.advanceTimersByTimeAsync(1) })
  expect(result.current.run.status).toBe('SUCCEEDED')
  expect(result.current.connection).toBe('closed')
  expect(FakeEventSource.current.closed).toBe(true)
  await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
  expect(fetch).toHaveBeenCalledTimes(2)
  unmount()
})

test('late detail refresh for a previously selected task cannot replace the current task', async () => {
  let finishOldRefresh
  const oldRefresh = new Promise(resolve => { finishOldRefresh = resolve })
  let firstOldRead = true
  globalThis.fetch = vi.fn(url => {
    if (url === '/api/v1/agent/runs/run-1') {
      if (firstOldRead) {
        firstOldRead = false
        return Promise.resolve({ ok: true, status: 200, json: async () => ({ data: { id: 'run-1', status: 'SUCCEEDED', lastEventSeq: 1 } }) })
      }
      return oldRefresh
    }
    if (url === '/api/v1/agent/runs/run-2')
      return Promise.resolve({ ok: true, status: 200, json: async () => ({ data: { id: 'run-2', status: 'SUCCEEDED', lastEventSeq: 1 } }) })
    throw new Error(`Unexpected request ${url}`)
  })
  const onChange = vi.fn()
  const { result, rerender } = renderHook(({ id }) => useRun(id, onChange), { initialProps: { id: 'run-1' } })
  await waitFor(() => expect(result.current.run?.id).toBe('run-1'))
  let pending
  act(() => { pending = result.current.refresh() })
  rerender({ id: 'run-2' })
  await waitFor(() => expect(result.current.run?.id).toBe('run-2'))
  await act(async () => {
    finishOldRefresh({ ok: true, status: 200, json: async () => ({ data: { id: 'run-1', status: 'FAILED', lastEventSeq: 2 } }) })
    await pending
  })
  expect(result.current.run.id).toBe('run-2')
  expect(onChange.mock.calls.map(([snapshot]) => snapshot.id)).toEqual(['run-1', 'run-2'])
})

test('text deltas extend a recovered draft without querying details or duplicating replayed text', async () => {
  globalThis.EventSource = FakeEventSource
  const initial = { id: 'stream', status: 'RUNNING', lastEventSeq: 4,
    output: { modelCallIndex: 1, text: '已经生成', status: 'STARTED', lastEventSeq: 4 } }
  let snapshot = initial
  globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ data: snapshot }) }))
  const onChange = vi.fn()
  const { result, unmount } = renderHook(() => useRun('stream', onChange))
  await waitFor(() => expect(result.current.run?.output.text).toBe('已经生成'))
  expect(FakeEventSource.current.url).toContain('afterSeq=4')
  const delta = { runId: 'stream', seq: 5, payload: { callIndex: 1, text: '的正文😀' } }
  act(() => FakeEventSource.current.emit('model.text.delta', delta))
  expect(result.current.run.output.text).toBe('已经生成的正文😀')
  expect(fetch).toHaveBeenCalledTimes(1)
  act(() => {
    FakeEventSource.current.emit('model.text.delta', delta)
    FakeEventSource.current.emit('model.text.delta', { ...delta, runId: 'other', seq: 6 })
  })
  expect(result.current.run.output.text).toBe('已经生成的正文😀')
  await act(async () => { await result.current.refresh() })
  expect(result.current.run.output.text).toBe('已经生成的正文😀') // Old snapshot cannot erase new text.
  snapshot = { ...initial, lastEventSeq: 7, output: { modelCallIndex: 2, text: '最终', status: 'STARTED' } }
  await act(async () => { await result.current.refresh() })
  act(() => FakeEventSource.current.emit('model.text.delta', { runId: 'stream', seq: 8, payload: { callIndex: 2, text: '回答' } }))
  expect(result.current.run.output.text).toBe('最终回答')
  snapshot = { ...snapshot, status: 'SUCCEEDED', lastEventSeq: 9, analysis: '最终回答' }
  await act(async () => { FakeEventSource.current.emit('run.succeeded', { runId: 'stream', seq: 9 }) })
  await waitFor(() => expect(result.current.run.status).toBe('SUCCEEDED'))
  expect(FakeEventSource.current.closed).toBe(true)
  unmount()
})

test('a new model call uses a separate draft and stale fragments cannot replace it', async () => {
  globalThis.EventSource = FakeEventSource
  globalThis.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ data:
    { id: 'stream-2', status: 'RUNNING', lastEventSeq: 2, output: { modelCallIndex: 1, text: '先查阅', status: 'STARTED' } } }) }))
  const { result, unmount } = renderHook(() => useRun('stream-2'))
  await waitFor(() => expect(result.current.run?.id).toBe('stream-2'))
  act(() => FakeEventSource.current.emit('model.text.delta', { runId: 'stream-2', seq: 5, payload: { callIndex: 2, text: '根据查阅结果' } }))
  expect(result.current.run.output.text).toBe('根据查阅结果')
  act(() => FakeEventSource.current.emit('model.text.delta', { runId: 'stream-2', seq: 6, payload: { callIndex: 1, text: '旧调用' } }))
  expect(result.current.run.output.text).toBe('根据查阅结果')
  expect(fetch).toHaveBeenCalledTimes(1)
  unmount()
})
