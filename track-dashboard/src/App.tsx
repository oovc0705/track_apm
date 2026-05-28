import './App.css'
import { Routes, Route } from 'react-router-dom'
import NavLayout from './components/NavLayout'
import Dashboard from './pages/Dashboard'
import TraceDetail from './pages/TraceDetail'
import JvmMonitor from './pages/JvmMonitor'

function App() {
  return (
    <NavLayout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/trace" element={<TraceDetail />} />
        <Route path="/trace/:traceId" element={<TraceDetail />} />
        <Route path="/jvm" element={<JvmMonitor />} />
      </Routes>
    </NavLayout>
  )
}

export default App
