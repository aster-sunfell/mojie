import React, { useState } from 'react'
import { useNavigation } from './useNavigation.js'
import { ProjectHome } from '../features/projects/ProjectHome.jsx'
import { Workspace } from '../features/workspace/Workspace.jsx'
import { AppSettings } from '../features/preferences/AppSettings.jsx'

export default function App() {
  const { projectId, chapterId, navigate } = useNavigation()
  const [settingsOpen, setSettingsOpen] = useState(false), [skillRevision, setSkillRevision] = useState(0)
  return <>
    <div inert={settingsOpen}>
      {projectId
        ? <Workspace key={projectId} projectId={projectId} selectedChapterId={chapterId} navigate={navigate}
          onSettings={() => setSettingsOpen(true)} applicationSettingsOpen={settingsOpen} skillRevision={skillRevision} />
        : <ProjectHome open={id => navigate(`/projects/${id}`)} onSettings={() => setSettingsOpen(true)} />}
    </div>
    {settingsOpen && <AppSettings onClose={() => setSettingsOpen(false)} onSkillsChange={() => setSkillRevision(value => value + 1)} />}
  </>
}
