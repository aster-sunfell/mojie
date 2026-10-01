// @vitest-environment jsdom
import React from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import { Tooltip, TOOLTIP_DELAY } from './Tooltip.jsx'

afterEach(() => { cleanup(); vi.useRealTimers() })

function renderTooltip() {
  render(<Tooltip label="删除"><button>图标</button></Tooltip>)
  return screen.getByRole('button', { name: '图标' })
}

test('shows after a long hover and hides on leave', () => {
  vi.useFakeTimers()
  const trigger = renderTooltip()
  fireEvent.mouseEnter(trigger)
  act(() => { vi.advanceTimersByTime(TOOLTIP_DELAY - 100) })
  expect(screen.queryByRole('tooltip')).toBeNull()
  act(() => { vi.advanceTimersByTime(200) })
  expect(screen.getByRole('tooltip').textContent).toBe('删除')
  fireEvent.mouseLeave(trigger)
  expect(screen.queryByRole('tooltip')).toBeNull()
})

test('a brief hover never shows the tooltip', () => {
  vi.useFakeTimers()
  const trigger = renderTooltip()
  fireEvent.mouseEnter(trigger)
  act(() => { vi.advanceTimersByTime(TOOLTIP_DELAY - 100) })
  fireEvent.mouseLeave(trigger)
  act(() => { vi.advanceTimersByTime(TOOLTIP_DELAY * 2) })
  expect(screen.queryByRole('tooltip')).toBeNull()
})

test('shows on keyboard focus and hides on blur', () => {
  vi.useFakeTimers()
  const trigger = renderTooltip()
  fireEvent.focus(trigger)
  act(() => { vi.advanceTimersByTime(TOOLTIP_DELAY + 100) })
  expect(screen.getByRole('tooltip').textContent).toBe('删除')
  fireEvent.blur(trigger)
  expect(screen.queryByRole('tooltip')).toBeNull()
})
