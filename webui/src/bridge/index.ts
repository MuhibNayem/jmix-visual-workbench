import type {
  ApplicationGraphResponse,
  FlowUiPropertyChangeRequest,
  FlowUiStructureChangeRequest,
  FlowUiDirectTextChangeRequest,
  FlowUiControllerInjectionRequest,
  FlowUiWorkspaceResponse,
  GenerationResult,
  GraphSourceLocator,
  ProjectConfig,
  SourceNavigationResponse,
  WorkspaceChangeApplyResponse,
  WorkspaceChangePreviewResponse,
  WorkspaceChangeSet,
} from '../types'

type BridgeCallback = (action: string, requestId: string | null, result: any) => void

declare global {
  interface Window {
    javaBridge?: {
      send: (action: string, payload: any, requestId: string) => void
    }
    onBridgeResponse?: (action: string, requestId: string | null, result: any) => void
    onBridgeReady?: () => void
  }
}

class Bridge {
  private listeners: BridgeCallback[] = []
  private ready = false
  private requestSequence = 0
  private pendingQueue: { action: string; payload: any; requestId: string }[] = []

  constructor() {
    window.onBridgeResponse = (action: string, requestId: string | null, result: any) => {
      this.listeners.forEach(cb => cb(action, requestId, result))
    }

    window.onBridgeReady = () => {
      this.ready = true
      this.pendingQueue.forEach(({ action, payload, requestId }) => this.send(action, payload, requestId))
      this.pendingQueue = []
    }

    // If bridge is already available (e.g., dev mode without JCEF)
    if (window.javaBridge) {
      this.ready = true
    }
  }

  send(action: string, payload: any = {}, requestId: string = this.nextRequestId()) {
    if (!this.ready || !window.javaBridge) {
      // In dev mode, simulate response
      if (import.meta.env.DEV) {
        console.log(`[Bridge] ${action}`, payload)
        setTimeout(() => {
          this.listeners.forEach(cb => cb(action, requestId, {
            success: true,
            filesWritten: [`generated/${action}.java`],
            errors: [],
          }))
        }, 300)
        return
      }
      this.pendingQueue.push({ action, payload, requestId })
      return
    }
    window.javaBridge.send(action, payload, requestId)
  }

  onResponse(callback: BridgeCallback) {
    this.listeners.push(callback)
    return () => {
      this.listeners = this.listeners.filter(cb => cb !== callback)
    }
  }

  async request<T = any>(action: string, payload: any = {}): Promise<T> {
    const requestId = this.nextRequestId()
    return new Promise((resolve) => {
      const unsub = this.onResponse((respAction, responseRequestId, result) => {
        if (responseRequestId === requestId && respAction === action) {
          unsub()
          resolve(result)
        }
      })
      this.send(action, payload, requestId)
    })
  }

  private nextRequestId(): string {
    this.requestSequence += 1
    return `jvw-${Date.now().toString(36)}-${this.requestSequence.toString(36)}`
  }

  generateEntity(entity: any) {
    return this.request<GenerationResult>('generateEntity', entity)
  }

  generateCrud(entity: any, options: any) {
    return this.request<GenerationResult>('generateCrud', { entity, options })
  }

  generateView(view: any) {
    return this.request<GenerationResult>('generateView', view)
  }

  generateMigration(migration: any) {
    return this.request<GenerationResult>('generateMigration', migration)
  }

  generateRole(role: any) {
    return this.request<GenerationResult>('generateRole', role)
  }

  generateBpm(entityName: string) {
    return this.request<GenerationResult>('generateBpm', { entityName })
  }

  getProjectConfig() {
    return this.request<ProjectConfig>('getProjectConfig')
  }

  getApplicationGraph(forceRefresh: boolean = false) {
    return this.request<ApplicationGraphResponse>('getApplicationGraph', { forceRefresh })
  }

  navigateToSource(locator: GraphSourceLocator) {
    return this.request<SourceNavigationResponse>('navigateToSource', locator)
  }

  getFlowUiWorkspace(sourceLocator: GraphSourceLocator) {
    return this.request<FlowUiWorkspaceResponse>('getFlowUiWorkspace', { sourceLocator })
  }

  previewFlowUiPropertyChange(change: FlowUiPropertyChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiPropertyChange', change)
  }

  applyFlowUiPropertyChange(change: FlowUiPropertyChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiPropertyChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiStructureChange(change: FlowUiStructureChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiStructureChange', change)
  }

  applyFlowUiStructureChange(change: FlowUiStructureChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiStructureChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiDirectTextChange(change: FlowUiDirectTextChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiDirectTextChange', change)
  }

  applyFlowUiDirectTextChange(change: FlowUiDirectTextChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiDirectTextChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiControllerInjection(change: FlowUiControllerInjectionRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiControllerInjection', change)
  }

  applyFlowUiControllerInjection(change: FlowUiControllerInjectionRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiControllerInjection', {
      change,
      expectedPlanDigest,
    })
  }

  previewWorkspaceChange(changeSet: WorkspaceChangeSet) {
    return this.request<WorkspaceChangePreviewResponse>('previewWorkspaceChange', changeSet)
  }

  applyWorkspaceChange(changeSet: WorkspaceChangeSet, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyWorkspaceChange', {
      changeSet,
      expectedPlanDigest,
    })
  }
}

export const bridge = new Bridge()
