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
  await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
  expect(result.current.run.status).toBe('SUCCEEDED')
  expect(result.current.connection).toBe('closed')
  expect(FakeEventSource.current.closed).toBe(true)
  unmount()
})
