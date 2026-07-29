import { useEffect } from 'react'
import { useStore, type ActiveTab } from './store'
import { bridge } from './bridge'
import EntityDesigner from './components/EntityDesigner/EntityDesigner'
import ViewDesigner from './components/ViewDesigner/ViewDesigner'
import CrudWizard from './components/CrudWizard/CrudWizard'
import MenuDesigner from './components/MenuDesigner/MenuDesigner'
import RoleDesigner from './components/RoleDesigner/RoleDesigner'
import WorkflowDesigner from './components/WorkflowDesigner/WorkflowDesigner'
import MigrationPanel from './components/MigrationPanel/MigrationPanel'
import ProjectMap from './components/ProjectMap/ProjectMap'
import ApiDesigner from './components/ApiDesigner/ApiDesigner'
import IntegrationDesigner from './components/IntegrationDesigner/IntegrationDesigner'
import ScenarioDesigner from './components/ScenarioDesigner/ScenarioDesigner'
import LogicDesigner from './components/LogicDesigner/LogicDesigner'
import RuleDesigner from './components/RuleDesigner/RuleDesigner'
import Toast from './components/shared/Toast'

const workspaces: { id: ActiveTab; label: string; icon: string }[] = [
  { id: 'projectMap', label: 'Application Map', icon: '◎' },
  { id: 'entity', label: 'Entity Designer', icon: '◆' },
  { id: 'view', label: 'View Designer', icon: '▦' },
  { id: 'crud', label: 'CRUD Wizard', icon: '⚡' },
  { id: 'menu', label: 'Menu Designer', icon: '☰' },
  { id: 'role', label: 'Role Designer', icon: '🔒' },
  { id: 'api', label: 'API Designer', icon: '⇄' },
  { id: 'integration', label: 'Integration Designer', icon: '⇌' },
  { id: 'workflow', label: 'Workflow Designer', icon: '⇢' },
  { id: 'logic', label: 'Server Logic', icon: '⌘' },
  { id: 'rules', label: 'Rules & Formulas', icon: 'ƒ' },
  { id: 'scenario', label: 'Scenario Tests', icon: '✓' },
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
    <div className="workbench-shell flex h-full w-full min-w-0 max-w-full overflow-hidden bg-surface">
      {/* Sidebar */}
      <nav className="flex w-14 flex-shrink-0 flex-col border-r border-surface-border bg-surface-light min-[1400px]:w-52">
        <div className="border-b border-surface-border p-3">
          <h1 className="text-center text-sm font-bold tracking-wide text-jmix-400 min-[1400px]:text-left">
            <span className="min-[1400px]:hidden">JVW</span>
            <span className="hidden min-[1400px]:inline">JMIX VISUAL WORKBENCH</span>
          </h1>
          <p className="mt-0.5 hidden text-[10px] text-gray-500 min-[1400px]:block">Clean-room prototype</p>
        </div>
        <div className="flex-1 py-2">
          {workspaces.map((workspace) => (
            <button
              key={workspace.id}
              onClick={() => setActiveTab(workspace.id)}
              title={workspace.label}
              aria-label={workspace.label}
              aria-current={activeTab === workspace.id ? 'page' : undefined}
              className={`flex w-full items-center justify-center gap-2 px-3 py-2 text-left text-xs transition-colors min-[1400px]:justify-start ${
                activeTab === workspace.id
                  ? 'bg-jmix-500/15 text-jmix-400 border-r-2 border-jmix-500'
                  : 'text-gray-400 hover:bg-surface-lighter hover:text-gray-200'
              }`}
            >
              <span className="text-sm">{workspace.icon}</span>
              <span className="hidden min-[1400px]:inline">{workspace.label}</span>
            </button>
          ))}
        </div>
        <div className="hidden border-t border-surface-border p-3 text-[10px] text-gray-600 min-[1400px]:block">
          v1.0.0 — Non-certified
        </div>
      </nav>

      {/* Main Content */}
      <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {activeTab === 'projectMap' && <ProjectMap />}
        {activeTab === 'entity' && <EntityDesigner />}
        {activeTab === 'view' && <ViewDesigner />}
        {activeTab === 'crud' && <CrudWizard />}
        {activeTab === 'menu' && <MenuDesigner />}
        {activeTab === 'role' && <RoleDesigner />}
        {activeTab === 'api' && <ApiDesigner />}
        {activeTab === 'integration' && <IntegrationDesigner />}
        {activeTab === 'workflow' && <WorkflowDesigner />}
        {activeTab === 'logic' && <LogicDesigner />}
        {activeTab === 'rules' && <RuleDesigner />}
        {activeTab === 'scenario' && <ScenarioDesigner />}
        {activeTab === 'migration' && <MigrationPanel />}
      </main>

      <Toast />
    </div>
  )
}
