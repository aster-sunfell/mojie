import React from 'react'
import { useNavigation } from './useNavigation.js'
import { ProjectHome } from '../features/projects/ProjectHome.jsx'
import { Workspace } from '../features/workspace/Workspace.jsx'

export default function App() {
  const { projectId, chapterId, navigate } = useNavigation()
  return projectId
    ? <Workspace projectId={projectId} selectedChapterId={chapterId} navigate={navigate} />
    : <ProjectHome open={id => navigate(`/projects/${id}`)} />
}
