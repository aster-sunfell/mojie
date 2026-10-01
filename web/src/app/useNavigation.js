import { useCallback, useEffect, useState } from 'react'

function pathIds() {
  const parts = window.location.pathname.split('/').filter(Boolean)
  return { projectId: parts[0] === 'projects' ? parts[1] : null,
    chapterId: parts[2] === 'chapters' ? parts[3] : null }
}

export function useNavigation() {
  const [location, setLocation] = useState(pathIds)
  useEffect(() => {
    const listener = () => setLocation(pathIds())
    window.addEventListener('popstate', listener)
    return () => window.removeEventListener('popstate', listener)
  }, [])
  const navigate = useCallback(path => {
    window.history.pushState({}, '', path)
    setLocation(pathIds())
  }, [])
  return { ...location, navigate }
}
