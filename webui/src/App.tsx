import { useEffect, useRef, useState } from 'react'
import { useStore, type ActiveTab } from './store'
import { bridge } from './bridge'
import type { GraphSourceLocator } from './types'
import EntityDesigner from './components/EntityDesigner/EntityDesigner'
import { existingEntityModel } from './components/EntityDesigner/entityModelAdapter'
import ViewDesigner from './components/ViewDesigner/ViewDesigner'
import CrudWizard from './components/CrudWizard/CrudWizard'
import MenuDesigner from './components/MenuDesigner/MenuDesigner'
import RoleDesigner from './components/RoleDesigner/RoleDesigner'
import WorkflowDesigner from './components/WorkflowDesigner/WorkflowDesigner'
import MigrationPanel from './components/MigrationPanel/MigrationPanel'
import ProjectMap from './components/ProjectMap/ProjectMap'
import ProjectProperties from './components/ProjectProperties/ProjectProperties'
import ApiDesigner from './components/ApiDesigner/ApiDesigner'
import IntegrationDesigner from './components/IntegrationDesigner/IntegrationDesigner'
import ScenarioDesigner from './components/ScenarioDesigner/ScenarioDesigner'
import LogicDesigner from './components/LogicDesigner/LogicDesigner'
import RuleDesigner from './components/RuleDesigner/RuleDesigner'
import Toast from './components/shared/Toast'

const workspaces: { id: ActiveTab; label: string; icon: string }[] = [
  { id: 'projectProperties', label: 'Project Configuration', icon: '⚙' },
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
  const {
    activeTab,
    setActiveTab,
    setProjectConfig,
    setEntity,
    addToast,
    flowUiLocator,
    openFlowUiDesigner,
    closeFlowUiDesigner,
    resetEntity,
    openCrudDesigner,
    crudEntityLocator,
  } = useStore()
  const launchSequence = useRef(0)
  const nativeFlowUiEditor = window.location.pathname === '/flowui-editor.html'
  const nativeEntityEditor = window.location.pathname === '/entity-editor.html'
  const [entityEditorLocator, setEntityEditorLocator] = useState<GraphSourceLocator | null>(() => {
    const context = bridge.getLaunchContext()
    return context?.surface === 'ENTITY_EDITOR'
      ? context.sourceLocator ?? null
      : null
  })
  const [entityDesignerKey, setEntityDesignerKey] = useState(0)
  const [workspaceRevision, setWorkspaceRevision] = useState(0)
  const developmentEditorWidthParameter = import.meta.env.DEV
    ? new URLSearchParams(window.location.search).get('editorWidth')
    : null
  const developmentEditorWidth = developmentEditorWidthParameter === null
    ? Number.NaN
    : Number(developmentEditorWidthParameter)
  const developmentEditorStyle =
    Number.isFinite(developmentEditorWidth)
    ? {
        width: `${Math.max(320, Math.min(1920, Math.round(developmentEditorWidth)))}px`,
      }
    : undefined

  useEffect(() => {
    bridge.getProjectConfig().then((config) => {
      if (config && !('error' in config)) {
        setProjectConfig(config)
      }
    })
  }, [])

  useEffect(() => {
    const handleIndexUpdate = (event: Event) => {
      const update = (event as CustomEvent<{ changedFiles?: number; incremental?: boolean }>).detail
      setWorkspaceRevision((current) => current + 1)
      const changedFiles = update?.changedFiles ?? 0
      if (changedFiles > 0) {
        addToast(
          `Project model synchronized incrementally from ${changedFiles} changed ${changedFiles === 1 ? 'file' : 'files'}.`,
          'info',
        )
      }
    }
    window.addEventListener('jmix-workbench-index-updated', handleIndexUpdate)
    return () => window.removeEventListener('jmix-workbench-index-updated', handleIndexUpdate)
  }, [addToast])

  useEffect(() => {
    const applyLaunchContext = (context: ReturnType<typeof bridge.getLaunchContext>) => {
      const sequence = ++launchSequence.current
      switch (context?.surface) {
        case 'FLOW_UI_EDITOR':
          if (context.sourceLocator) openFlowUiDesigner(context.sourceLocator)
          break
        case 'ENTITY_EDITOR':
          if (context.sourceLocator) setEntityEditorLocator(context.sourceLocator)
          break
        case 'ENTITY_DESIGNER':
          resetEntity()
          setEntityEditorLocator(null)
          setEntityDesignerKey(current => current + 1)
          setActiveTab('entity')
          break
        case 'VIEW_DESIGNER':
          closeFlowUiDesigner()
          setActiveTab('view')
          break
        case 'CRUD_DESIGNER':
          if (!context.sourceLocator) {
            openCrudDesigner()
            break
          }
          void bridge.getSchemaWorkspace()
            .then((workspace) => {
              if (sequence !== launchSequence.current) return
              const snapshot = workspace.entities.find(candidate =>
                candidate.sourceLocator.relativePath === context.sourceLocator?.relativePath &&
                candidate.sourceLocator.revisionFingerprint === context.sourceLocator?.revisionFingerprint,
              )
              if (!snapshot) {
                addToast(
                  'The selected entity changed after indexing. Refresh the Entity Designer before creating views.',
                  'error',
                )
                return
              }
              const store = workspace.stores.find(candidate =>
                candidate.moduleId === snapshot.moduleId &&
                candidate.name === snapshot.storeName,
              )
              setEntity(existingEntityModel(snapshot, store?.id))
              openCrudDesigner(snapshot.sourceLocator)
            })
            .catch((error) => {
              if (sequence === launchSequence.current) {
                addToast(`Cannot open the entity view workflow: ${error.message}`, 'error')
              }
            })
          break
        case 'PROJECT_PROPERTIES':
          setActiveTab('projectProperties')
          break
      }
    }
    applyLaunchContext(bridge.getLaunchContext())
    return bridge.onLaunchContext(applyLaunchContext)
  }, [
    addToast,
    closeFlowUiDesigner,
    openCrudDesigner,
    openFlowUiDesigner,
    resetEntity,
    setActiveTab,
    setEntity,
  ])

  if (nativeFlowUiEditor) {
    return (
      <div
        className="workbench-shell flex h-full w-full min-w-0 max-w-full overflow-hidden bg-surface"
        style={developmentEditorStyle}
      >
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {flowUiLocator
            ? <ViewDesigner editorSurface />
            : (
              <div className="flex h-full items-center justify-center bg-surface text-xs text-gray-400">
                Connecting the native FlowUI editor…
              </div>
            )}
        </main>
        <Toast />
      </div>
    )
  }

  if (nativeEntityEditor) {
    return (
      <div
        className="workbench-shell flex h-full w-full min-w-0 max-w-full overflow-hidden bg-surface"
        style={developmentEditorStyle}
      >
        <main className="flex min-w-0 flex-1 flex-col overflow-hidden">
          {entityEditorLocator
            ? (
              <EntityDesigner
                key={entityDesignerKey}
                editorSurface
                sourceLocator={entityEditorLocator}
              />
            )
            : (
              <div className="flex h-full items-center justify-center bg-surface text-xs text-gray-400">
                Connecting the native Entity Designer…
              </div>
            )}
        </main>
        <Toast />
      </div>
    )
  }

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
              onClick={() => {
                if (workspace.id === 'crud') {
                  openCrudDesigner()
                } else {
                  setActiveTab(workspace.id)
                }
              }}
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
      <main key={workspaceRevision} className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {activeTab === 'projectProperties' && <ProjectProperties />}
        {activeTab === 'projectMap' && <ProjectMap />}
        {activeTab === 'entity' && <EntityDesigner key={entityDesignerKey} />}
        {activeTab === 'view' && <ViewDesigner />}
        {activeTab === 'crud' && (
          <CrudWizard
            key={crudEntityLocator?.revisionFingerprint ?? 'new-entity'}
          />
        )}
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
