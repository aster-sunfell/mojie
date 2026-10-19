// Keep the text being edited/read in place while review chrome above it shrinks.
// Native scroll anchoring is not consistent for focused, auto-sized textareas.
export function preserveReadingPosition(root) {
  const area = root?.closest('.editorArea')
  if (!area) return () => {}
  const focused = area.ownerDocument.activeElement
  const viewport = area.getBoundingClientRect()
  const anchor = focused?.matches('textarea') && area.contains(focused) ? focused
    : [...area.querySelectorAll('textarea')].find(element => {
      const rect = element.getBoundingClientRect()
      return rect.height && rect.bottom > viewport.top && rect.top < viewport.bottom
    })
  if (!anchor) return () => {}
  let top = anchor.getBoundingClientRect().top
  let frame
  let stopped = false
  const until = performance.now() + 260
  const stop = () => {
    stopped = true; cancelAnimationFrame(frame)
    for (const type of ['wheel', 'touchmove', 'pointerdown', 'keydown']) area.removeEventListener(type, interrupt)
  }
  const interrupt = event => {
    if (event.type !== 'keydown' || ['PageUp', 'PageDown', 'Home', 'End', 'ArrowUp', 'ArrowDown'].includes(event.key)) stop()
  }
  const update = () => {
    if (stopped || !anchor.isConnected || performance.now() > until) { stop(); return }
    const next = anchor.getBoundingClientRect().top
    area.scrollTop += next - top
    top = anchor.getBoundingClientRect().top
    frame = requestAnimationFrame(update)
  }
  for (const type of ['wheel', 'touchmove', 'pointerdown', 'keydown']) area.addEventListener(type, interrupt, { passive: true })
  frame = requestAnimationFrame(update)
  return stop
}
