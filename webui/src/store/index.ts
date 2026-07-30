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
    readOnly: false,
    jmixProperty: false,
    dependsOnProperties: [],
    lob: false,
    enumIdType: 'string',
    validations: [],
    annotations: [],
    inBaseFetchPlan: true,
  }
}

function databaseName(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9_]/g, '_')
    .toUpperCase()
}

function nextCopyName(sourceName: string, attributes: AttributeModel[]): string {
  const names = new Set(attributes.map(attribute => attribute.name))
  const stem = `${sourceName || 'field'}Copy`
  if (!names.has(stem)) return stem
  let suffix = 2
  while (names.has(`${stem}${suffix}`)) suffix += 1
  return `${stem}${suffix}`
}

function copyAttributeDraft(
  source: AttributeModel,
  name: string,
  entity: EntityModel,
): AttributeModel {
  const association = source.association
    ? {
        ...source.association,
        cascade: [...source.association.cascade],
        joinColumns: source.association.joinColumns?.map(column => ({ ...column })),
        mappedBy: undefined,
        joinColumnName: ['manyToOne', 'oneToOne'].includes(source.association.associationType)
          ? `${databaseName(name)}_ID`
          : undefined,
        localIdAttributeName: source.association.crossDataStore ? `${name}Id` : undefined,
        generateInverse: false,
        inverseAttributeName: undefined,
        ownershipTransfer: undefined,
        ownershipJoinColumnName: undefined,
        cardinalityChoreography: undefined,
        joinTable: source.association.joinTable
          ? {
              ...source.association.joinTable,
              name: `${databaseName(entity.tableName || entity.className || 'ENTITY')}_${databaseName(name)}_LINK`,
              joinColumns: source.association.joinTable.joinColumns?.map(column => ({ ...column })),
              inverseJoinColumns: source.association.joinTable.inverseJoinColumns
                ?.map(column => ({ ...column })),
            }
          : undefined,
      }
    : undefined
  return {
    ...source,
    name,
    columnName: source.transientFlag || source.association
      ? undefined
      : databaseName(name),
    unique: false,
    dependsOnProperties: [...source.dependsOnProperties],
    validations: source.validations.map(validation => ({
      ...validation,
      groups: validation.groups ? [...validation.groups] : undefined,
    })),
    annotations: source.annotations.map(annotation => ({
      ...annotation,
      parameters: { ...annotation.parameters },
    })),
    association,
  }
}

function defaultEntity(): EntityModel {
  return {
    className: '',
    packageName: 'com.example.app.entity',
    sourceLanguage: 'java',
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
    systemLevel: false,
    annotatedPropertiesOnly: false,
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
  crudEntityLocator: GraphSourceLocator | null
  openCrudDesigner: (locator?: GraphSourceLocator) => void
  closeCrudDesigner: () => void

  projectConfig: ProjectConfig | null
  setProjectConfig: (config: ProjectConfig) => void

  // Entity Designer
  entity: EntityModel
  setEntity: (entity: Partial<EntityModel>) => void
  addAttribute: () => void
  duplicateAttribute: (index: number) => number | null
  moveAttribute: (fromIndex: number, toIndex: number) => boolean
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
  crudEntityLocator: null,
  openCrudDesigner: (locator) => set({
    activeTab: 'crud',
    crudEntityLocator: locator ?? null,
  }),
  closeCrudDesigner: () => set({ crudEntityLocator: null }),

  projectConfig: null,
  setProjectConfig: (config) => set({ projectConfig: config }),

  entity: defaultEntity(),
  setEntity: (partial) => set((s) => ({ entity: { ...s.entity, ...partial } })),
  addAttribute: () => set((s) => ({
    entity: { ...s.entity, attributes: [...s.entity.attributes, defaultAttribute(`field${s.entity.attributes.length + 1}`)] }
  })),
  duplicateAttribute: (index) => {
    const state = get()
    const source = state.entity.attributes[index]
    if (!source) return null
    const name = nextCopyName(source.name, state.entity.attributes)
    const duplicate = copyAttributeDraft(source, name, state.entity)
    const insertionIndex = index + 1
    set({
      entity: {
        ...state.entity,
        attributes: [
          ...state.entity.attributes.slice(0, insertionIndex),
          duplicate,
          ...state.entity.attributes.slice(insertionIndex),
        ],
      },
    })
    return insertionIndex
  },
  moveAttribute: (fromIndex, toIndex) => {
    const state = get()
    if (
      fromIndex < 0 ||
      toIndex < 0 ||
      fromIndex >= state.entity.attributes.length ||
      toIndex >= state.entity.attributes.length ||
      fromIndex === toIndex
    ) {
      return false
    }
    const attributes = [...state.entity.attributes]
    const [moved] = attributes.splice(fromIndex, 1)
    attributes.splice(toIndex, 0, moved)
    set({ entity: { ...state.entity, attributes } })
    return true
  },
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
