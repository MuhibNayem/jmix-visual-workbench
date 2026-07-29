import { create } from 'zustand'
import type {
  AttributeModel,
  EntityModel,
  GenerationResult,
  GraphSourceLocator,
  ProjectConfig,
} from '../types'

export type ActiveTab =
  | 'projectMap'
  | 'entity'
  | 'view'
  | 'crud'
  | 'menu'
  | 'role'
  | 'api'
  | 'integration'
  | 'workflow'
  | 'logic'
  | 'rules'
  | 'scenario'
  | 'migration'

function defaultAttribute(name: string = ''): AttributeModel {
  return {
    name,
    type: 'string',
    mandatory: false,
    unique: false,
    transientFlag: false,
    systemLevel: false,
    validations: [],
    annotations: [],
    inBaseFetchPlan: true,
  }
}

function defaultEntity(): EntityModel {
  return {
    className: '',
    packageName: 'com.example.app.entity',
    dataStore: 'main',
    entityName: '',
    tableName: '',
    entityType: 'entity',
    id: { type: 'uuid', generation: 'jmixGenerated', columnName: 'ID' },
    traits: ['standardEntity'],
    attributes: [],
    indexes: [],
    uniqueConstraints: [],
    lifecycleCallbacks: [],
    entityListeners: [],
    implementsInterfaces: [],
    annotations: [],
    databaseView: false,
    ddlGeneration: {
      enabled: true,
      mode: 'createAndDrop',
      unmappedColumns: [],
      unmappedConstraints: [],
    },
  }
}

interface AppState {
  activeTab: ActiveTab
  setActiveTab: (tab: ActiveTab) => void

  flowUiLocator: GraphSourceLocator | null
  openFlowUiDesigner: (locator: GraphSourceLocator) => void
  closeFlowUiDesigner: () => void

  projectConfig: ProjectConfig | null
  setProjectConfig: (config: ProjectConfig) => void

  // Entity Designer
  entity: EntityModel
  setEntity: (entity: Partial<EntityModel>) => void
  addAttribute: () => void
  updateAttribute: (index: number, attr: Partial<AttributeModel>) => void
  removeAttribute: (index: number) => void
  resetEntity: () => void

  // Generation results
  lastResult: GenerationResult | null
  setLastResult: (result: GenerationResult) => void
  isGenerating: boolean
  setIsGenerating: (v: boolean) => void

  // Toast notifications
  toasts: { id: number; message: string; type: 'success' | 'error' | 'info' }[]
  addToast: (message: string, type: 'success' | 'error' | 'info') => void
  removeToast: (id: number) => void
}

let toastId = 0

export const useStore = create<AppState>((set, get) => ({
  activeTab: 'projectMap',
  setActiveTab: (tab) => set({ activeTab: tab }),
  flowUiLocator: null,
  openFlowUiDesigner: (locator) => set({ activeTab: 'view', flowUiLocator: locator }),
  closeFlowUiDesigner: () => set({ flowUiLocator: null }),

  projectConfig: null,
  setProjectConfig: (config) => set({ projectConfig: config }),

  entity: defaultEntity(),
  setEntity: (partial) => set((s) => ({ entity: { ...s.entity, ...partial } })),
  addAttribute: () => set((s) => ({
    entity: { ...s.entity, attributes: [...s.entity.attributes, defaultAttribute(`field${s.entity.attributes.length + 1}`)] }
  })),
  updateAttribute: (index, attr) => set((s) => ({
    entity: {
      ...s.entity,
      attributes: s.entity.attributes.map((a, i) => i === index ? { ...a, ...attr } : a),
    }
  })),
  removeAttribute: (index) => set((s) => ({
    entity: { ...s.entity, attributes: s.entity.attributes.filter((_, i) => i !== index) }
  })),
  resetEntity: () => set({ entity: defaultEntity() }),

  lastResult: null,
  setLastResult: (result) => set({ lastResult: result }),
  isGenerating: false,
  setIsGenerating: (v) => set({ isGenerating: v }),

  toasts: [],
  addToast: (message, type) => {
    const id = ++toastId
    set((s) => ({ toasts: [...s.toasts, { id, message, type }] }))
    setTimeout(() => get().removeToast(id), 5000)
  },
  removeToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
}))
