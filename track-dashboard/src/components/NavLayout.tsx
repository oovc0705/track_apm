import { NavLink } from 'react-router-dom';
import { DashboardOutlined, NodeIndexOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { ReactNode } from 'react';
import './NavLayout.css';

export default function NavLayout({ children }: { children: ReactNode }) {
  return (
    <div className="nav-layout">
      <nav className="top-nav">
        <div className="nav-brand">
          <span className="nav-logo">⚡</span>
          <span className="nav-title">LightTrack APM</span>
        </div>
        <div className="nav-links">
          <NavLink to="/" end className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <DashboardOutlined /> <span>Dashboard</span>
          </NavLink>
          <NavLink to="/trace" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <NodeIndexOutlined /> <span>Trace Detail</span>
          </NavLink>
          <NavLink to="/jvm" className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
            <ThunderboltOutlined /> <span>JVM Monitor</span>
          </NavLink>
        </div>
      </nav>
      <main className="nav-content">
        {children}
      </main>
    </div>
  );
}
