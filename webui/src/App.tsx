import { useEffect } from 'react'
import { useStore, type ActiveTab } from './store'
import { bridge } from './bridge'
import EntityDesigner from './components/EntityDesigner/EntityDesigner'
import ViewDesigner from './components/ViewDesigner/ViewDesigner'
import CrudWizard from './components/CrudWizard/CrudWizard'
import MenuDesigner from './components/MenuDesigner/MenuDesigner'
import RoleDesigner from './components/RoleDesigner/RoleDesigner'
import MigrationPanel from './components/MigrationPanel/MigrationPanel'
import Toast from './components/shared/Toast'

const tabs: { id: ActiveTab; label: string; icon: string }[] = [
  { id: 'entity', label: 'Entity Designer', icon: '◆' },
  { id: 'view', label: 'View Designer', icon: '▦' },
  { id: 'crud', label: 'CRUD Wizard', icon: '⚡' },
  { id: 'menu', label: 'Menu Designer', icon: '☰' },
  { id: 'role', label: 'Role Designer', icon: '🔒' },
  { id: 'migration', label: 'Migrations', icon: '🗄' },
]

export default function App() {
  const { activeTab, setActiveTab, setProjectConfig } = useStore()

  useEffect(() => {
    bridge.getProjectConfig().then((config) => {
      if (config && !('error' in config)) {
        setProjectConfig(config)
      }
    })
  }, [])

  return (
    <div className="flex h-screen w-screen overflow-hidden bg-surface">
      {/* Sidebar */}
      <nav className="w-52 flex-shrink-0 bg-surface-light border-r border-surface-border flex flex-col">
        <div className="p-3 border-b border-surface-border">
          <h1 className="text-sm font-bold text-jmix-400 tracking-wide">JMIX VISUAL WORKBENCH</h1>
          <p className="text-[10px] text-gray-500 mt-0.5">Clean-room prototype</p>
        </div>
        <div className="flex-1 py-2">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`w-full text-left px-3 py-2 text-xs flex items-center gap-2 transition-colors ${
                activeTab === tab.id
                  ? 'bg-jmix-500/15 text-jmix-400 border-r-2 border-jmix-500'
                  : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
              }`}
            >
              <span className="text-sm">{tab.icon}</span>
              {tab.label}
            </button>
          ))}
        </div>
        <div className="p-3 border-t border-surface-border text-[10px] text-gray-600">
          v1.0.0 — Non-certified
        </div>
      </nav>

      {/* Main Content */}
      <main className="flex-1 overflow-hidden flex flex-col">
        {activeTab === 'entity' && <EntityDesigner />}
        {activeTab === 'view' && <ViewDesigner />}
        {activeTab === 'crud' && <CrudWizard />}
        {activeTab === 'menu' && <MenuDesigner />}
        {activeTab === 'role' && <RoleDesigner />}
        {activeTab === 'migration' && <MigrationPanel />}
      </main>

      <Toast />
    </div>
  )
}
