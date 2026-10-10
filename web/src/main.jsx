import React from 'react'
import { createRoot } from 'react-dom/client'
import App from './app/App.jsx'
import { MaintenanceBoundary } from './app/MaintenanceBoundary.jsx'
import './app/styles/index.css'

createRoot(document.getElementById('root')).render(<MaintenanceBoundary><App /></MaintenanceBoundary>)
