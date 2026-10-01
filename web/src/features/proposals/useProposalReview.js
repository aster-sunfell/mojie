import { useEffect, useRef, useState } from 'react'
import { api, readableError } from '../../shared/api/client.js'
import { locateChanges, reviewReference, reviewStatus } from './merge.js'

export function useProposalReview({ chapterId, run, draftRef, onDraftChange, onDirtyChange }) {
  const [sessions, setSessions] = useState({})
  const sessionsRef = useRef({})
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [loading, setLoading] = useState(false)
  const [retry, setRetry] = useState(0)
  const lastRetry = useRef(0)
  const saveEpoch = useRef(0)
  const id = run?.status === 'SUCCEEDED' ? run.proposalId : null
  function update(transform) {
    sessionsRef.current = transform(sessionsRef.current)
    onDirtyChange(Object.values(sessionsRef.current).some(value => Object.keys(value.choices).length > 0))
    setSessions(sessionsRef.current)
  }
  useEffect(() => {
    sessionsRef.current = {}; setSessions({}); setError(''); setNotice('')
    onDirtyChange(false)
  }, [chapterId])
  useEffect(() => {
    let cancelled = false
    const epoch = saveEpoch.current
    const forced = lastRetry.current !== retry
    lastRetry.current = retry
    setError(''); setNotice(''); setLoading(false)
    if (!id || (sessionsRef.current[id] && !forced)) return
    setLoading(true)
    api(`/edit-proposals/${id}`).then(proposal => {
      if (cancelled || epoch !== saveEpoch.current) return
      if (proposal.targetChapterId && proposal.targetChapterId !== chapterId) return
      if (!Array.isArray(proposal.items)) throw new Error('修改审阅需要新版后端，请重启后端后重试。')
      update(current => {
        const choices = { ...current[id]?.choices }
        for (const item of proposal.items) if (item.status !== 'PENDING') delete choices[item.id]
        return { ...current, [id]: { proposal, choices } }
      })
      if (forced) setNotice('已同步建议处理状态，你的正文草稿仍然保留。')
    }).catch(e => { if (!cancelled) setError(readableError(e)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [id, chapterId, retry])
  const session = sessions[id]
  const items = (session?.proposal.items || []).map(item => ({ ...item,
    status: session.choices[item.id] || item.status, unsaved: !!session.choices[item.id] }))
  const dirty = Object.values(sessions).some(value => Object.keys(value.choices).length > 0)

  function choose(itemIds, status) {
    update(current => {
      const value = current[id]
      if (!value) return current
      const choices = { ...value.choices }
      for (const itemId of itemIds) choices[itemId] = status
      return { ...current, [id]: { ...value, choices } }
    })
  }
  function apply(itemIds) {
    if (!session) return
    const original = draftRef.current
    let next = original, conflicts = 0, included = 0
    const applied = []
    const reference = reviewReference(session.proposal.baseContent, items)
    const locations = locateChanges(reference.base, original, reference.items.filter(item => item.status === 'PENDING'))
    const located = reference.items.filter(item => item.status === 'PENDING' && itemIds.includes(item.id))
      .map(item => ({ item, location: locations.get(item.id) }))
      .sort((a, b) => (b.location.start ?? -1) - (a.location.start ?? -1))
    // Locate everything against the same draft, then splice right-to-left without shifting offsets.
    for (const { item, location: result } of located) {
      if (result.status === 'CONFLICT') { conflicts++; continue }
      if (result.status === 'INCLUDED') included++
      else next = next.slice(0, result.start) + item.after + next.slice(result.end)
      applied.push(item.id)
    }
    if (applied.length) { onDraftChange(next); choose(applied, 'APPLIED') }
    setNotice(`${applied.length} 处已应用到草稿${included ? `（其中 ${included} 处正文已包含）` : ''}${conflicts ? `，${conflicts} 处需要手动确认，已保留当前内容` : ''}。保存正文后生效。`)
  }
  function snapshot() {
    return Object.entries(sessionsRef.current).filter(([, value]) => Object.keys(value.choices).length)
      .map(([proposalId, value]) => ({ proposalId, decisions: Object.entries(value.choices)
        .map(([itemId, status]) => ({ itemId: Number(itemId), status })) }))
  }
  function commit(reviews, version) {
    saveEpoch.current++
    update(current => {
      const next = { ...current }
      for (const review of reviews) {
        const value = next[review.proposalId]
        if (!value) continue
        const choices = { ...value.choices }
        const saved = new Map(review.decisions.map(decision => [decision.itemId, decision.status]))
        const items = value.proposal.items.map(item => saved.has(item.id)
          ? { ...item, status: saved.get(item.id), savedVersion: version } : item)
        for (const decision of review.decisions) if (choices[decision.itemId] === decision.status) delete choices[decision.itemId]
        const status = reviewStatus(items)
        next[review.proposalId] = { choices, proposal: { ...value.proposal, items, status,
          appliedVersion: status === 'APPLIED' ? version : null } }
      }
      return next
    })
    setNotice('正文和修改处理状态已保存。剩余建议可以继续处理。')
  }
  async function dismiss() {
    setLoading(true); setError('')
    try {
      const result = await api(`/edit-proposals/${id}/reject`, 'POST')
      update(current => ({ ...current, [id]: { ...current[id], proposal: { ...current[id].proposal, ...result } } }))
    } catch (e) { setError(readableError(e)) }
    finally { setLoading(false) }
  }
  return { id, proposal: session?.proposal, items, dirty, loading, error, notice, apply,
    dismiss,
    markHandled: itemId => { choose([itemId], 'APPLIED'); setNotice('已记录为手动处理。请保存正文以保存当前内容和处理状态。') },
    ignore: itemIds => { choose(itemIds, 'IGNORED'); setNotice('已忽略所选修改；保存正文后记录处理状态。') },
    restore: itemId => update(current => {
      const value = current[id], choices = { ...value.choices }; delete choices[itemId]
      return { ...current, [id]: { ...value, choices } }
    }),
    snapshot, commit, retry: () => setRetry(value => value + 1) }
}
