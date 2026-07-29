import {
  type CSSProperties,
  type DragEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react'
import {
  ArrowDown, ArrowUp, Boxes, ClipboardPaste, Code2, Copy, Database, ExternalLink,
  GripVertical, Layers, Library, Loader2, Monitor, Play, Plus, Redo2, RefreshCw,
  Repeat2, Scissors, Search, Server, Smartphone, Sparkles, Tag, Tablet, Trash2,
  Undo2, X, Zap, ZoomIn, ZoomOut,
} from 'lucide-react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import {
  flowUiCatalogCategories,
  flowUiComponentCatalog,
  type FlowUiCatalogItem,
} from './FlowUiComponentCatalog'
import type {
  FlowUiElementSnapshot,
  FlowUiDirectTextChangeRequest,
  FlowUiControllerInjectionRequest,
  FlowUiControllerHandlerKind,
  FlowUiControllerHandlerRequest,
  FlowUiPropertyChangeRequest,
  FlowUiStructureChangeRequest,
  FlowUiWorkspaceResponse,
  GraphSourceLocator,
  JmixFlowUiHotDeployRequest,
  JmixRuntimeInspectionResponse,
  JmixRuntimeViewport,
  WorkspaceChangePreviewResponse,
  WorkspaceHistorySnapshot,
} from '../../types'

const primaryButton =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50'
const quietButton =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 hover:border-jmix-500/60 hover:text-jmix-300 disabled:opacity-50'
const componentContainers = new Set([
  'layout', 'vbox', 'hbox', 'formLayout', 'gridLayout', 'flexLayout', 'split',
  'tabSheet', 'tab', 'accordion', 'details', 'scroller', 'formItem', 'dataGrid',
  'treeDataGrid', 'columns', 'actions',
])
const paletteDragType = 'application/x-jmix-flowui-palette'
const elementDragType = 'application/x-jmix-flowui-element'
type CanvasDropPlacement = 'BEFORE' | 'INSIDE' | 'AFTER'
type CanvasViewport = 'DESKTOP' | 'TABLET' | 'MOBILE'
type FlowUiClipboard = {
  mode: 'COPY' | 'CUT'
  sourcePath: string
  elementKey: string
  elementId?: string
  label: string
}
const canvasViewportWidth: Record<CanvasViewport, number> = {
  DESKTOP: 1280,
  TABLET: 768,
  MOBILE: 390,
}
const convertibleLayouts = ['vbox', 'hbox', 'flexLayout', 'formLayout', 'gridLayout'] as const
const typedValueComponents = new Set([
  'textField', 'textArea', 'emailField', 'passwordField', 'integerField', 'bigDecimalField',
  'numberField', 'datePicker', 'dateTimePicker', 'timePicker', 'comboBox', 'entityComboBox',
  'entityPicker', 'valuePicker', 'checkbox', 'radioButtonGroup', 'select',
])
const actionTargetOwners = new Set([
  'dataGrid', 'treeDataGrid', 'entityPicker', 'entityComboBox', 'valuePicker',
  'multiValuePicker', 'multiSelectComboBoxPicker', 'genericFilter', 'userMenu',
  'actionItem', 'textItem', 'componentItem',
])

function actionTargetId(
  action: FlowUiElementSnapshot,
  elements: Map<string, FlowUiElementSnapshot>,
): string | undefined {
  if (action.localTag !== 'action' || !action.id) return undefined
  const ownerIds: string[] = []
  let parentKey = action.parentKey
  while (parentKey) {
    const parent = elements.get(parentKey)
    if (!parent) break
    if (parent.id && actionTargetOwners.has(parent.localTag)) ownerIds.unshift(parent.id)
    parentKey = parent.parentKey
  }
  return [...ownerIds, action.id].join('.')
}

function attributeValue(element: FlowUiElementSnapshot, name: string): string | undefined {
  return element.attributes.find((attribute) => attribute.name === name)?.value
}

function childLayout(element: FlowUiElementSnapshot): {
  className: string
  style?: CSSProperties
} {
  if (element.localTag === 'formLayout') {
    const maxColumns = Math.max(1, Math.min(6, Number(attributeValue(element, 'maxColumns')) || 2))
    const columnWidth = attributeValue(element, 'columnWidth') || '12rem'
    return {
      className: 'grid',
      style: {
        gridTemplateColumns: `repeat(${maxColumns}, minmax(min(100%, ${columnWidth}), 1fr))`,
        gap: attributeValue(element, 'rowSpacing') || '0.5rem',
      },
    }
  }
  if (element.localTag === 'gridLayout') {
    const minimum = attributeValue(element, 'columnMinWidth') || '12rem'
    return {
      className: 'grid',
      style: {
        gridTemplateColumns: `repeat(auto-fit, minmax(min(100%, ${minimum}), 1fr))`,
        gap: attributeValue(element, 'gap') || '0.5rem',
      },
    }
  }
  if (element.localTag === 'hbox') {
    return {
      className: 'flex',
      style: {
        flexWrap: attributeValue(element, 'wrap') === 'true' ? 'wrap' : 'nowrap',
        gap: '0.5rem',
      },
    }
  }
  if (element.localTag === 'flexLayout') {
    const direction = attributeValue(element, 'flexDirection')?.toLowerCase().replace('_', '-') as CSSProperties['flexDirection']
    const wrap = attributeValue(element, 'flexWrap')?.toLowerCase().replace('_', '-') as CSSProperties['flexWrap']
    return {
      className: 'flex',
      style: {
        flexDirection: direction || 'row',
        flexWrap: wrap || 'nowrap',
        gap: '0.5rem',
      },
    }
  }
  return { className: 'flex flex-col', style: { gap: '0.5rem' } }
}

function PanelTitle({ title, count, icon: Icon }: {
  title: string
  count?: number
  icon: typeof Layers
}) {
  return (
    <div className="flex items-center gap-1.5 border-b border-surface-border px-3 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
      <Icon size={12} className="text-jmix-400" />
      {title}
      {count !== undefined && (
        <span className="rounded-full bg-surface-lighter px-1.5 py-px text-[9px] text-gray-400">{count}</span>
      )}
    </div>
  )
}

function ElementTree({ element, elements, selectedKey, onSelect, depth = 0 }: {
  element: FlowUiElementSnapshot
  elements: Map<string, FlowUiElementSnapshot>
  selectedKey: string | null
  onSelect: (key: string) => void
  depth?: number
}) {
  return (
    <div>
      <button
        type="button"
        onClick={() => onSelect(element.key)}
        className={`flex w-full items-center gap-1.5 py-1 pr-2 text-left text-[11px] ${
          selectedKey === element.key ? 'bg-jmix-500/15 text-jmix-300' : 'text-gray-400 hover:bg-surface-lighter'
        }`}
        style={{ paddingLeft: `${8 + depth * 12}px` }}
      >
        <span className={element.childKeys.length ? 'text-gray-600' : 'invisible'}>›</span>
        <span className="truncate font-mono">{element.localTag}</span>
        {element.id && <span className="truncate text-gray-600">#{element.id}</span>}
      </button>
      {element.childKeys.map((key) => {
        const child = elements.get(key)
        return child ? (
          <ElementTree
            key={key}
            element={child}
            elements={elements}
            selectedKey={selectedKey}
            onSelect={onSelect}
            depth={depth + 1}
          />
        ) : null
      })}
    </div>
  )
}

function CanvasNode({
  element,
  elements,
  selectedKey,
  movable,
  onSelect,
  onDropElement,
  onDropCatalog,
}: {
  element: FlowUiElementSnapshot
  elements: Map<string, FlowUiElementSnapshot>
  selectedKey: string | null
  movable: (element: FlowUiElementSnapshot) => boolean
  onSelect: (key: string) => void
  onDropElement: (elementKey: string, targetKey: string, placement: CanvasDropPlacement) => void
  onDropCatalog: (tag: string, targetKey: string, placement: CanvasDropPlacement) => void
}) {
  const [dropPlacement, setDropPlacement] = useState<CanvasDropPlacement | null>(null)
  const caption = element.attributes.find((attribute) => ['text', 'label', 'caption'].includes(attribute.name))?.value
  const binding = [
    element.attributes.find((attribute) => attribute.name === 'dataContainer')?.value,
    element.attributes.find((attribute) => attribute.name === 'property')?.value,
  ].filter(Boolean).join('.')
  const acceptsChildren = componentContainers.has(element.localTag) && !element.selfClosing
  const children = childLayout(element)
  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.stopPropagation()
    const placement = dropPlacement ?? (acceptsChildren ? 'INSIDE' : 'AFTER')
    setDropPlacement(null)
    const movedElement = event.dataTransfer.getData(elementDragType)
    const catalogTag = event.dataTransfer.getData(paletteDragType)
    if (movedElement) onDropElement(movedElement, element.key, placement)
    else if (catalogTag) onDropCatalog(catalogTag, element.key, placement)
  }
  return (
    <div
      draggable={movable(element)}
      onDragStart={(event) => {
        if (!movable(element)) {
          event.preventDefault()
          return
        }
        event.stopPropagation()
        event.dataTransfer.effectAllowed = 'move'
        event.dataTransfer.setData(elementDragType, element.key)
      }}
      onDragEnd={() => setDropPlacement(null)}
      onDragOver={(event) => {
        event.preventDefault()
        event.stopPropagation()
        event.dataTransfer.dropEffect = event.dataTransfer.types.includes(elementDragType) ? 'move' : 'copy'
        const bounds = event.currentTarget.getBoundingClientRect()
        const ratio = bounds.height > 0 ? (event.clientY - bounds.top) / bounds.height : 0.5
        setDropPlacement(
          ratio < 0.24
            ? 'BEFORE'
            : ratio > 0.76
              ? 'AFTER'
              : acceptsChildren
                ? 'INSIDE'
                : ratio < 0.5
                  ? 'BEFORE'
                  : 'AFTER',
        )
      }}
      onDragLeave={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDropPlacement(null)
      }}
      onDrop={handleDrop}
      onClick={(event) => {
        event.stopPropagation()
        onSelect(element.key)
      }}
      className={`min-w-[7rem] rounded border p-2 ${
        dropPlacement === 'INSIDE'
          ? 'border-sky-400 bg-sky-500/15 ring-2 ring-sky-500/30'
          : dropPlacement === 'BEFORE'
            ? 'border-t-sky-400 border-t-4 bg-sky-500/5'
            : dropPlacement === 'AFTER'
              ? 'border-b-sky-400 border-b-4 bg-sky-500/5'
          : selectedKey === element.key
          ? 'border-jmix-400 bg-jmix-500/10 ring-1 ring-jmix-500/30'
          : 'border-surface-border bg-surface-light/50 hover:border-gray-500'
      } ${element.childKeys.length ? 'w-full' : ''} ${movable(element) ? 'cursor-grab active:cursor-grabbing' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 items-center gap-1">
          {movable(element) && <GripVertical size={10} className="shrink-0 text-gray-700" />}
          <span className="truncate font-mono text-[10px] text-gray-300">
            {element.localTag}{element.id ? ` #${element.id}` : ''}
          </span>
        </div>
        {binding && <span className="truncate text-[9px] text-sky-400">{binding}</span>}
      </div>
      {caption && <div className="mt-1 truncate text-[10px] text-gray-500">{caption}</div>}
      {element.directText && (
        <pre className="mt-1 max-h-16 overflow-hidden whitespace-pre-wrap text-[9px] text-gray-600">
          {element.directText}
        </pre>
      )}
      {element.childKeys.length > 0 && (
        <div className={`mt-2 min-w-0 ${children.className}`} style={children.style}>
          {element.childKeys.map((key) => {
            const child = elements.get(key)
            return child ? (
              <div
                key={key}
                className="min-w-0"
                style={{
                  gridColumn: element.localTag === 'formLayout'
                    ? `span ${Math.max(1, Number(attributeValue(child, 'colspan')) || 1)}`
                    : undefined,
                }}
              >
                <CanvasNode
                  element={child}
                  elements={elements}
                  selectedKey={selectedKey}
                  movable={movable}
                  onSelect={onSelect}
                  onDropElement={onDropElement}
                  onDropCatalog={onDropCatalog}
                />
              </div>
            ) : null
          })}
        </div>
      )}
    </div>
  )
}

export default function ExistingFlowUiDesigner({ initialLocator, onClose }: {
  initialLocator: GraphSourceLocator
  onClose: () => void
}) {
  const addToast = useStore((state) => state.addToast)
  const openFlowUiDesigner = useStore((state) => state.openFlowUiDesigner)
  const [locator, setLocator] = useState(initialLocator)
  const [workspace, setWorkspace] = useState<FlowUiWorkspaceResponse | null>(null)
  const [runtime, setRuntime] = useState<JmixRuntimeInspectionResponse | null>(null)
  const [runtimeLoading, setRuntimeLoading] = useState(false)
  const [runtimePanelOpen, setRuntimePanelOpen] = useState(false)
  const [selectedRuntimeTargetId, setSelectedRuntimeTargetId] = useState<string | null>(null)
  const runtimeInspectionSequence = useRef(0)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [newProperty, setNewProperty] = useState('')
  const [newValue, setNewValue] = useState('')
  const [newTag, setNewTag] = useState('textField')
  const [newComponentId, setNewComponentId] = useState('')
  const [catalogQuery, setCatalogQuery] = useState('')
  const [catalogCategory, setCatalogCategory] = useState<string>('All')
  const [canvasDropActive, setCanvasDropActive] = useState(false)
  const [liveDesign, setLiveDesign] = useState(true)
  const [structureBusy, setStructureBusy] = useState(false)
  const [canvasViewport, setCanvasViewport] = useState<CanvasViewport>('DESKTOP')
  const [canvasZoom, setCanvasZoom] = useState(75)
  const [clipboard, setClipboard] = useState<FlowUiClipboard | null>(null)
  const [wrapLayout, setWrapLayout] = useState<(typeof convertibleLayouts)[number]>('flexLayout')
  const [history, setHistory] = useState<WorkspaceHistorySnapshot>({
    canUndo: false,
    undoDepth: 0,
    canRedo: false,
    redoDepth: 0,
  })
  const [historyBusy, setHistoryBusy] = useState(false)
  const [pending, setPending] = useState<{
    kind: 'property'
    change: FlowUiPropertyChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'structure'
    change: FlowUiStructureChangeRequest
    preview: WorkspaceChangePreviewResponse
    preferredElementId?: string
  } | {
    kind: 'text'
    change: FlowUiDirectTextChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'controllerInjection'
    change: FlowUiControllerInjectionRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'controllerHandler'
    change: FlowUiControllerHandlerRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'hotDeploy'
    change: JmixFlowUiHotDeployRequest
    preview: WorkspaceChangePreviewResponse
  } | null>(null)
  const selectElement = (key: string) => {
    setSelectedKey(key)
  }
  const refreshHistory = useCallback(async () => {
    const snapshot = await bridge.getWorkspaceHistory()
    setHistory(snapshot)
  }, [])

  const inspectRuntime = useCallback(async (target: GraphSourceLocator) => {
    const sequence = ++runtimeInspectionSequence.current
    setRuntimeLoading(true)
    try {
      const response = await bridge.inspectJmixRuntime(target)
      if (sequence !== runtimeInspectionSequence.current) return
      setRuntime(response)
      setSelectedRuntimeTargetId((current) => {
        if (current && response.targets.some((candidate) => candidate.id === current)) return current
        return response.targets.find((candidate) => candidate.preferred && candidate.reachable)?.id
          ?? response.targets.find((candidate) => candidate.reachable)?.id
          ?? response.targets.find((candidate) => candidate.preferred)?.id
          ?? response.targets[0]?.id
          ?? null
      })
    } catch (cause) {
      if (sequence !== runtimeInspectionSequence.current) return
      setRuntime({
        accepted: false,
        targets: [],
        issues: [{
          code: 'JVW-RUNTIME-INSPECTION-FAILED',
          message: cause instanceof Error ? cause.message : 'Runtime inspection failed.',
        }],
      })
    } finally {
      if (sequence === runtimeInspectionSequence.current) setRuntimeLoading(false)
    }
  }, [])

  const load = useCallback(async (target: GraphSourceLocator, preferredElementId?: string) => {
    setLoading(true)
    setError(null)
    setPending(null)
    try {
      const response = await bridge.getFlowUiWorkspace(target)
      setWorkspace(response)
      if (!response.accepted || !response.document) {
        setError(response.issues[0]?.message ?? 'The existing FlowUI descriptor could not be loaded.')
        return
      }
      void inspectRuntime(target)
      setSelectedKey((current) => (
        preferredElementId
          ? response.document!.elements.find((element) => element.id === preferredElementId)?.key
            ?? response.document!.rootKey
          : current && response.document!.elements.some((element) => element.key === current)
            ? current
            : response.document!.rootKey
      ))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The FlowUI workspace request failed.')
    } finally {
      setLoading(false)
    }
  }, [inspectRuntime])

  useEffect(() => {
    setLocator(initialLocator)
    void load(initialLocator)
  }, [initialLocator, load])

  useEffect(() => {
    void refreshHistory()
  }, [refreshHistory])

  const document = workspace?.document
  const elements = useMemo(
    () => new Map(document?.elements.map((element) => [element.key, element]) ?? []),
    [document],
  )
  const selected = selectedKey ? elements.get(selectedKey) ?? null : null
  const controllerIssueCount = (
    workspace?.controllerModel?.injections.reduce(
      (count, injection) => count + (injection.issues?.length ?? 0),
      0,
    ) ?? 0
  ) + (
    workspace?.controllerModel?.handlers.reduce(
      (count, handler) => count + (handler.issues?.length ?? 0),
      0,
    ) ?? 0
  )
  const selectedRuntimeTarget = runtime?.targets.find((target) => target.id === selectedRuntimeTargetId)
    ?? runtime?.targets[0]
  const layout = document?.elements.find((element) => element.localTag === 'layout')
  const layoutKeys = useMemo(() => {
    const keys = new Set<string>()
    const visit = (key: string) => {
      if (keys.has(key)) return
      keys.add(key)
      elements.get(key)?.childKeys.forEach(visit)
    }
    if (layout) visit(layout.key)
    return keys
  }, [elements, layout])
  const selectedInLayout = selected ? layoutKeys.has(selected.key) : false
  const canvasRoots = layout
    ? layout.childKeys.map((key) => elements.get(key)).filter((element): element is FlowUiElementSnapshot => Boolean(element))
    : document
      ? [elements.get(document.rootKey)].filter((element): element is FlowUiElementSnapshot => Boolean(element))
      : []
  const preferredCatalogParent = selected && selectedInLayout &&
    componentContainers.has(selected.localTag) && !selected.selfClosing
    ? selected
    : layout
  const catalogItems = useMemo(() => {
    const query = catalogQuery.trim().toLowerCase()
    return flowUiComponentCatalog.filter((item) => {
      if (catalogCategory !== 'All' && item.category !== catalogCategory) return false
      if (!query) return true
      return [
        item.tag,
        item.label,
        item.category,
        item.description,
        ...(item.keywords ?? []),
      ].some((value) => value.toLowerCase().includes(query))
    })
  }, [catalogCategory, catalogQuery])

  const previewElementProperty = async (elementKey: string, propertyName: string, value: string) => {
    if (!propertyName.trim()) return
    if (structureBusy) {
      addToast('Finishing the previous visual edit. Try again in a moment.', 'info')
      return
    }
    const change: FlowUiPropertyChangeRequest = {
      sourceLocator: locator,
      elementKey,
      propertyName: propertyName.trim(),
      value,
    }
    const preview = await bridge.previewFlowUiPropertyChange(change)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'The property change was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('The source already has this value.', 'info')
      return
    }
    if (liveDesign) {
      setStructureBusy(true)
      try {
        const result = await bridge.applyFlowUiPropertyChange(change, preview.planDigest)
        if (!result.success) {
          addToast(result.issues[0]?.message ?? 'The visual property change was rejected.', 'error')
          return
        }
        const nextFingerprint = preview.files[0]?.afterFingerprint
        if (!nextFingerprint) {
          addToast('The updated descriptor revision was unavailable.', 'error')
          return
        }
        const nextLocator = { ...locator, revisionFingerprint: nextFingerprint }
        setLocator(nextLocator)
        openFlowUiDesigner(nextLocator)
        setPending(null)
        addToast(`${preview.label}. Undo is available.`, 'success')
        await refreshHistory()
        await load(nextLocator, elements.get(elementKey)?.id)
      } finally {
        setStructureBusy(false)
      }
      return
    }
    setPending({ kind: 'property', change, preview })
  }

  const previewProperty = async (propertyName: string, value: string) => {
    if (!selected) return
    await previewElementProperty(selected.key, propertyName, value)
  }

  const previewDirectText = async (elementKey: string, value: string) => {
    const change: FlowUiDirectTextChangeRequest = { sourceLocator: locator, elementKey, value }
    const preview = await bridge.previewFlowUiDirectTextChange(change)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'The query change was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('The query already has this value.', 'info')
      return
    }
    setPending({ kind: 'text', change, preview })
  }

  const previewStructure = async (
    change: Omit<FlowUiStructureChangeRequest, 'sourceLocator'>,
    preferredElementId?: string,
  ): Promise<boolean> => {
    if (structureBusy) {
      addToast('Finishing the previous layout gesture. Try again in a moment.', 'info')
      return false
    }
    const fullChange: FlowUiStructureChangeRequest = { sourceLocator: locator, ...change }
    const preview = await bridge.previewFlowUiStructureChange(fullChange)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'The structure change was rejected.', 'error')
      return false
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('The component is already at that position.', 'info')
      return false
    }
    if (liveDesign) {
      setStructureBusy(true)
      try {
        const result = await bridge.applyFlowUiStructureChange(fullChange, preview.planDigest)
        if (!result.success) {
          addToast(result.issues[0]?.message ?? 'The visual layout gesture was rejected.', 'error')
          return false
        }
        const nextFingerprint = preview.files[0]?.afterFingerprint
        if (!nextFingerprint) {
          addToast('The updated descriptor revision was unavailable.', 'error')
          return false
        }
        const nextLocator = { ...locator, revisionFingerprint: nextFingerprint }
        setLocator(nextLocator)
        openFlowUiDesigner(nextLocator)
        setPending(null)
        addToast(`${preview.label}. Undo is available.`, 'success')
        await refreshHistory()
        await load(nextLocator, preferredElementId)
      } finally {
        setStructureBusy(false)
      }
      return true
    }
    setPending({ kind: 'structure', change: fullChange, preview, preferredElementId })
    return true
  }

  const suggestedComponentId = (tag: string) => {
    const used = new Set(document?.elements.map((element) => element.id).filter(Boolean) ?? [])
    const stem = tag.split(':').pop() ?? tag
    const normalized = stem.replace(/[^A-Za-z0-9_$]/g, '') || 'component'
    let suffix = 1
    while (used.has(`${normalized}${suffix}`)) suffix += 1
    return `${normalized}${suffix}`
  }

  const catalogAttributes = (item: FlowUiCatalogItem, parentKey: string) => {
    const attributes = { ...(item.defaultAttributes ?? {}) }
    const parent = elements.get(parentKey)
    const parentBinding = parent
      ? workspace?.dataModel?.bindings.find((binding) => binding.elementKey === parent.key)
      : undefined
    const parentContainer = parentBinding
      ? workspace?.dataModel?.containers.find((container) => container.id === parentBinding.containerId)
      : undefined
    const collectionContainer = (
      parentContainer?.kind.toLowerCase().includes('collection')
        ? parentContainer
        : workspace?.dataModel?.containers.find((container) => container.kind.toLowerCase().includes('collection'))
    )
    if (item.binding === 'COLLECTION' && collectionContainer) {
      attributes.dataContainer = collectionContainer.id
    } else if (item.binding === 'LOADER' && collectionContainer?.loaderId) {
      attributes.dataLoader = collectionContainer.loaderId
    }
    if (!['columns', 'formItem'].includes(item.tag)) {
      attributes.id = suggestedComponentId(item.tag)
    }
    return attributes
  }

  const previewCatalogInsert = async (
    item: FlowUiCatalogItem,
    parentKey?: string,
    beforeElementKey?: string,
  ) => {
    const targetKey = parentKey ?? preferredCatalogParent?.key
    if (!targetKey) {
      addToast('Select a layout container before adding a component.', 'info')
      return
    }
    const target = elements.get(targetKey)
    if (!target || target.selfClosing || !componentContainers.has(target.localTag)) {
      addToast('Drop components into an open layout or structural container.', 'error')
      return
    }
    if (item.allowedParents?.length && !item.allowedParents.includes(target.localTag)) {
      addToast(`${item.label} belongs inside ${item.allowedParents.join(' or ')}.`, 'error')
      return
    }
    const attributes = catalogAttributes(item, targetKey)
    await previewStructure({
      operation: 'INSERT_CHILD',
      parentKey: targetKey,
      tagName: item.tag,
      attributes,
      childCapable: item.childCapable ?? false,
      beforeElementKey,
    }, attributes.id)
  }

  const resolveCanvasPlacement = (
    targetKey: string,
    placement: CanvasDropPlacement,
  ): { parentKey: string, beforeElementKey?: string } | null => {
    const target = elements.get(targetKey)
    if (!target) return null
    if (placement === 'INSIDE') {
      return componentContainers.has(target.localTag) && !target.selfClosing
        ? { parentKey: target.key }
        : null
    }
    const parent = target.parentKey ? elements.get(target.parentKey) : undefined
    if (!parent || !componentContainers.has(parent.localTag) || parent.selfClosing) return null
    if (placement === 'BEFORE') {
      return { parentKey: parent.key, beforeElementKey: target.key }
    }
    const targetIndex = parent.childKeys.indexOf(target.key)
    return {
      parentKey: parent.key,
      beforeElementKey: parent.childKeys[targetIndex + 1],
    }
  }

  const previewCanvasReparent = async (
    elementKey: string,
    targetKey: string,
    placement: CanvasDropPlacement = 'INSIDE',
  ) => {
    if (elementKey === targetKey) return
    const resolved = resolveCanvasPlacement(targetKey, placement)
    if (!resolved) {
      addToast('This drop position cannot contain the selected component.', 'error')
      return
    }
    const moved = elements.get(elementKey)
    const parent = elements.get(resolved.parentKey)
    if (!moved || !parent || !layoutKeys.has(moved.key) || !layoutKeys.has(parent.key)) return
    await previewStructure({
      operation: 'REPARENT',
      elementKey,
      parentKey: resolved.parentKey,
      beforeElementKey: resolved.beforeElementKey,
    }, moved.id)
  }

  const previewCanvasCatalogDrop = async (
    tag: string,
    targetKey: string,
    placement: CanvasDropPlacement = 'INSIDE',
  ) => {
    const item = flowUiComponentCatalog.find((candidate) => candidate.tag === tag)
    const resolved = resolveCanvasPlacement(targetKey, placement)
    if (item && resolved) {
      await previewCatalogInsert(item, resolved.parentKey, resolved.beforeElementKey)
    }
  }

  const uniqueElementId = (base: string) => {
    const used = new Set(document?.elements.map((element) => element.id).filter(Boolean) ?? [])
    let candidate = base
    let suffix = 2
    while (used.has(candidate)) {
      candidate = `${base}${suffix}`
      suffix += 1
    }
    return candidate
  }

  const copiedElementId = (source: FlowUiElementSnapshot) =>
    source.id ? uniqueElementId(`${source.id}Copy`) : undefined

  const captureSelection = (mode: FlowUiClipboard['mode']) => {
    if (!selected || !selectedInLayout || !selected.parentKey) {
      addToast('Select a component inside the view layout first.', 'info')
      return
    }
    setClipboard({
      mode,
      sourcePath: locator.relativePath,
      elementKey: selected.key,
      elementId: selected.id,
      label: selected.id ?? selected.localTag,
    })
    addToast(
      mode === 'COPY'
        ? `${selected.id ?? selected.localTag} copied. Select a destination and paste.`
        : `${selected.id ?? selected.localTag} marked for move. Paste to complete the cut.`,
      'success',
    )
  }

  const resolveClipboardSource = () => {
    if (!clipboard || clipboard.sourcePath !== locator.relativePath) return undefined
    return clipboard.elementId
      ? document?.elements.find((element) => element.id === clipboard.elementId)
      : document?.elements.find((element) => element.key === clipboard.elementKey)
  }

  const currentPastePlacement = () => {
    if (!selected || !selectedInLayout) return null
    if (componentContainers.has(selected.localTag) && !selected.selfClosing) {
      return { parentKey: selected.key, beforeElementKey: undefined as string | undefined }
    }
    const parent = selected.parentKey ? elements.get(selected.parentKey) : undefined
    if (!parent || !componentContainers.has(parent.localTag) || parent.selfClosing) return null
    const index = parent.childKeys.indexOf(selected.key)
    return {
      parentKey: parent.key,
      beforeElementKey: parent.childKeys[index + 1],
    }
  }

  const pasteSelection = async () => {
    if (!clipboard) {
      addToast('Copy or cut a component before pasting.', 'info')
      return
    }
    const source = resolveClipboardSource()
    const placement = currentPastePlacement()
    if (!source) {
      addToast('The copied component changed or belongs to another screen. Copy it again.', 'error')
      return
    }
    if (!placement) {
      addToast('Select an open layout or a component beside the desired paste position.', 'info')
      return
    }
    const preferredId = clipboard.mode === 'COPY' ? copiedElementId(source) : source.id
    const changed = await previewStructure({
      operation: clipboard.mode === 'COPY' ? 'COPY_SUBTREE' : 'REPARENT',
      elementKey: source.key,
      parentKey: placement.parentKey,
      beforeElementKey: placement.beforeElementKey,
    }, preferredId)
    if (changed && clipboard.mode === 'CUT' && liveDesign) setClipboard(null)
  }

  const duplicateSelection = async () => {
    if (!selected?.parentKey || !selectedInLayout) return
    const parent = elements.get(selected.parentKey)
    if (!parent) return
    const index = parent.childKeys.indexOf(selected.key)
    await previewStructure({
      operation: 'COPY_SUBTREE',
      elementKey: selected.key,
      parentKey: parent.key,
      beforeElementKey: parent.childKeys[index + 1],
    }, copiedElementId(selected))
  }

  const wrapSelection = async () => {
    if (!selected?.parentKey || !selectedInLayout) return
    const wrapperId = uniqueElementId(`${selected.id ?? selected.localTag}Wrapper`)
    await previewStructure({
      operation: 'WRAP',
      elementKey: selected.key,
      tagName: wrapLayout,
      attributes: {
        id: wrapperId,
        width: '100%',
        ...(wrapLayout === 'flexLayout' ? { flexWrap: 'WRAP' } : {}),
      },
    }, wrapperId)
  }

  const convertSelection = async (tagName: (typeof convertibleLayouts)[number]) => {
    if (!selected || selected.localTag === tagName) return
    await previewStructure({
      operation: 'CONVERT_LAYOUT',
      elementKey: selected.key,
      tagName,
    }, selected.id)
  }

  const previewControllerInjection = async () => {
    if (!selected?.id || !workspace?.controllerModel?.psiSupported) return
    const change: FlowUiControllerInjectionRequest = {
      controllerLocator: {
        relativePath: workspace.controllerModel.relativePath,
        revisionFingerprint: workspace.controllerModel.revisionFingerprint,
      },
      componentId: selected.id,
      componentTag: selected.localTag,
      entityClass: selectedContainer?.entityClass,
    }
    const preview = await bridge.previewFlowUiControllerInjection(change)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'Controller injection was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('This component is already injected into the controller.', 'info')
      return
    }
    setPending({ kind: 'controllerInjection', change, preview })
  }

  const previewControllerHandler = async (
    kind: FlowUiControllerHandlerKind,
    component?: FlowUiElementSnapshot,
  ) => {
    if (!workspace?.controllerModel?.psiSupported) return
    const loaderContainer = component
      ? workspace.dataModel?.containers.find((container) => container.loaderElementKey === component.key)
      : undefined
    const change: FlowUiControllerHandlerRequest = {
      controllerLocator: {
        relativePath: workspace.controllerModel.relativePath,
        revisionFingerprint: workspace.controllerModel.revisionFingerprint,
      },
      kind,
      componentId: component?.id,
      componentTag: component?.localTag,
      targetId: component ? actionTargetId(component, elements) : undefined,
      entityClass: loaderContainer?.entityClass,
    }
    const preview = await bridge.previewFlowUiControllerHandler(change)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'Controller handler generation was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('An equivalent controller handler already exists.', 'info')
      return
    }
    setPending({ kind: 'controllerHandler', change, preview })
  }

  const openRuntimePreview = async (viewport: JmixRuntimeViewport) => {
    if (!selectedRuntimeTarget || !document) return
    const response = await bridge.openJmixRuntimePreview(
      selectedRuntimeTarget.previewUrl,
      `${document.viewId} · ${selectedRuntimeTarget.moduleId}`,
      viewport,
    )
    addToast(response.message, response.success ? 'success' : 'error')
  }

  const previewHotDeploy = async () => {
    if (!selectedRuntimeTarget) return
    const change: JmixFlowUiHotDeployRequest = {
      descriptorLocator: locator,
      targetId: selectedRuntimeTarget.id,
    }
    const preview = await bridge.previewFlowUiHotDeploy(change)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'FlowUI hot deployment was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('The runtime resource and cache reset are already staged.', 'info')
      return
    }
    setPending({ kind: 'hotDeploy', change, preview })
  }

  const performHistoryChange = useCallback(async (direction: 'undo' | 'redo') => {
    const preferredElementId = selected?.id
    setHistoryBusy(true)
    try {
      const response = direction === 'undo'
        ? await bridge.undoWorkspaceChange()
        : await bridge.redoWorkspaceChange()
      setHistory(response.history)
      if (!response.success) {
        addToast(response.issues[0]?.message ?? response.message, response.issues.length ? 'error' : 'info')
        return
      }
      setPending(null)
      const descriptorRevision = response.revisions[locator.relativePath]
      const nextLocator = descriptorRevision
        ? { ...locator, revisionFingerprint: descriptorRevision }
        : locator
      if (descriptorRevision) {
        setLocator(nextLocator)
        openFlowUiDesigner(nextLocator)
      }
      addToast(response.message, 'success')
      await load(nextLocator, preferredElementId)
    } finally {
      setHistoryBusy(false)
    }
  }, [addToast, load, locator, openFlowUiDesigner, selected?.id])

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null
      if (target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
      if (event.key === 'Escape' && clipboard) {
        event.preventDefault()
        setClipboard(null)
        addToast('Component clipboard cleared.', 'info')
        return
      }
      if (event.metaKey || event.ctrlKey) {
        const key = event.key.toLowerCase()
        if (key === 'c' && selected?.parentKey && selectedInLayout) {
          event.preventDefault()
          captureSelection('COPY')
          return
        }
        if (key === 'x' && selected?.parentKey && selectedInLayout) {
          event.preventDefault()
          captureSelection('CUT')
          return
        }
        if (key === 'v' && clipboard) {
          event.preventDefault()
          void pasteSelection()
          return
        }
        if (key === 'd' && selected?.parentKey && selectedInLayout) {
          event.preventDefault()
          void duplicateSelection()
          return
        }
      }
      if ((event.key === 'Delete' || event.key === 'Backspace') &&
        selected?.parentKey &&
        selectedInLayout &&
        selected.parentKey !== document?.rootKey
      ) {
        event.preventDefault()
        void previewStructure(
          { operation: 'DELETE', elementKey: selected.key },
          elements.get(selected.parentKey)?.id,
        )
        return
      }
      if (!(event.metaKey || event.ctrlKey)) return
      const key = event.key.toLowerCase()
      const redo = (key === 'z' && event.shiftKey) || key === 'y'
      const undo = key === 'z' && !event.shiftKey
      if ((undo && history.canUndo) || (redo && history.canRedo)) {
        event.preventDefault()
        void performHistoryChange(redo ? 'redo' : 'undo')
      }
    }
    window.addEventListener('keydown', handleShortcut)
    return () => window.removeEventListener('keydown', handleShortcut)
  }, [
    document?.rootKey,
    clipboard,
    history.canRedo,
    history.canUndo,
    performHistoryChange,
    selected,
    selectedInLayout,
  ])

  const applyPending = async () => {
    if (!pending?.preview.planDigest) return
    setApplying(true)
    try {
      const result = pending.kind === 'property'
        ? await bridge.applyFlowUiPropertyChange(pending.change, pending.preview.planDigest)
        : pending.kind === 'structure'
          ? await bridge.applyFlowUiStructureChange(pending.change, pending.preview.planDigest)
          : pending.kind === 'text'
            ? await bridge.applyFlowUiDirectTextChange(pending.change, pending.preview.planDigest)
            : pending.kind === 'controllerInjection'
              ? await bridge.applyFlowUiControllerInjection(pending.change, pending.preview.planDigest)
              : pending.kind === 'controllerHandler'
                ? await bridge.applyFlowUiControllerHandler(pending.change, pending.preview.planDigest)
                : await bridge.applyFlowUiHotDeploy(pending.change, pending.preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'The FlowUI source change failed.', 'error')
        return
      }
      if (pending.kind === 'hotDeploy') {
        addToast('Descriptor staged and the verified Jmix view cache reset was signaled.', 'success')
        setPending(null)
        await refreshHistory()
        await inspectRuntime(locator)
        return
      }
      if (pending.kind === 'controllerInjection' || pending.kind === 'controllerHandler') {
        addToast(
          pending.kind === 'controllerInjection'
            ? 'Component injection added without replacing Java code.'
            : 'Controller handler added without replacing Java code.',
          'success',
        )
        await refreshHistory()
        await load(locator)
        return
      }
      const nextFingerprint = pending.preview.files[0]?.afterFingerprint
      if (!nextFingerprint) {
        addToast('The updated source revision was unavailable.', 'error')
        return
      }
      const nextLocator = { ...locator, revisionFingerprint: nextFingerprint }
      setLocator(nextLocator)
      openFlowUiDesigner(nextLocator)
      setNewProperty('')
      setNewValue('')
      setNewComponentId('')
      if (pending.kind === 'structure' &&
        pending.change.operation === 'REPARENT' &&
        clipboard?.mode === 'CUT' &&
        clipboard.elementKey === pending.change.elementKey
      ) {
        setClipboard(null)
      }
      addToast('FlowUI XML updated without rewriting unrelated source.', 'success')
      await refreshHistory()
      await load(
        nextLocator,
        pending.kind === 'structure' ? pending.preferredElementId : undefined,
      )
    } finally {
      setApplying(false)
    }
  }

  if (loading && !workspace) {
    return (
      <div className="flex h-full items-center justify-center gap-2 bg-surface text-sm text-gray-400">
        <Loader2 size={16} className="animate-spin" /> Loading revision-safe FlowUI workspace…
      </div>
    )
  }
  if (error || !document || !workspace) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 bg-surface p-8 text-center">
        <div className="max-w-xl rounded border border-red-500/40 bg-red-500/10 p-4 text-sm text-red-200">
          {error ?? 'The FlowUI descriptor is unavailable.'}
        </div>
        <div className="flex gap-2">
          <button type="button" onClick={() => void load(locator)} className={primaryButton}>
            <RefreshCw size={13} /> Retry
          </button>
          <button type="button" onClick={onClose} className={quietButton}>New view designer</button>
        </div>
      </div>
    )
  }

  const logicKinds = new Set([
    'VIEW_CONTROLLER', 'VIEW_HANDLER', 'BUSINESS_RULE', 'SERVICE', 'SERVICE_METHOD', 'VALIDATOR',
    'SECURITY_POLICY', 'WORKFLOW_PROCESS', 'WORKFLOW_STATE',
  ])
  const dataKinds = new Set(['DATA_CONTAINER', 'DATA_LOADER', 'FETCH_PLAN', 'JPQL_QUERY', 'ENTITY'])
  const selectedBinding = selected
    ? workspace.dataModel?.bindings.find((binding) => binding.elementKey === selected.key)
    : undefined
  const selectedContainer = selectedBinding
    ? workspace.dataModel?.containers.find((container) => container.id === selectedBinding.containerId)
    : undefined
  const selectedFieldOptions = workspace.dataModel?.entityFields.filter((field) => {
    if (!selectedContainer?.entityClass) return true
    return field.entitySemanticKey === selectedContainer.entityClass ||
      field.entitySemanticKey.endsWith(`.${selectedContainer.entityClass}`)
  }) ?? []
  const selectedParent = selected?.parentKey ? elements.get(selected.parentKey) : undefined

  return (
    <div className="view-designer-shell flex h-full min-w-0 flex-col bg-surface">
      <header className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/60 px-3 py-2 sm:gap-3">
        <Code2 size={15} className="text-jmix-400" />
        <div className="min-w-0">
          <h2 className="truncate text-xs font-bold uppercase tracking-widest text-gray-300">
            Existing FlowUI · {document.viewId}
          </h2>
          <p className="truncate text-[10px] text-gray-600">{document.relativePath}</p>
        </div>
        <span className="rounded border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-300 sm:ml-auto">
          Manual source preserved
        </span>
        <button
          type="button"
          onClick={() => setLiveDesign((enabled) => !enabled)}
          className={`${quietButton} ${
            liveDesign ? 'border-sky-500/50 bg-sky-500/10 text-sky-300' : ''
          }`}
          title={
            liveDesign
              ? 'Layout gestures apply immediately after revision-safe validation and remain undoable.'
              : 'Layout gestures wait for explicit source review.'
          }
        >
          {structureBusy
            ? <Loader2 size={11} className="animate-spin" />
            : <Sparkles size={11} />}
          {liveDesign ? 'Live arrange' : 'Review gestures'}
        </button>
        <button
          type="button"
          onClick={() => setRuntimePanelOpen((open) => !open)}
          className={`${quietButton} ${
            selectedRuntimeTarget?.reachable ? 'border-emerald-500/40 text-emerald-300' : ''
          }`}
        >
          {runtimeLoading
            ? <Loader2 size={11} className="animate-spin" />
            : <Server size={11} />}
          {selectedRuntimeTarget?.reachable
            ? `Runtime ${selectedRuntimeTarget.httpStatus ?? 'live'}`
            : runtimeLoading
              ? 'Detecting runtime'
              : 'Runtime offline'}
        </button>
        <div className="flex items-center rounded border border-surface-border bg-surface">
          <button
            type="button"
            onClick={() => void performHistoryChange('undo')}
            disabled={!history.canUndo || historyBusy}
            className="inline-flex items-center gap-1 border-r border-surface-border px-2 py-1 text-[10px] text-gray-400 hover:text-jmix-300 disabled:opacity-35"
            title={history.undoLabel ? `Undo: ${history.undoLabel} (⌘/Ctrl+Z)` : 'Nothing to undo'}
          >
            <Undo2 size={11} /> <span className="hidden sm:inline">Undo</span>
            {history.undoDepth > 0 && <span className="text-[8px] text-gray-600">{history.undoDepth}</span>}
          </button>
          <button
            type="button"
            onClick={() => void performHistoryChange('redo')}
            disabled={!history.canRedo || historyBusy}
            className="inline-flex items-center gap-1 px-2 py-1 text-[10px] text-gray-400 hover:text-jmix-300 disabled:opacity-35"
            title={history.redoLabel ? `Redo: ${history.redoLabel} (⌘/Ctrl+Shift+Z)` : 'Nothing to redo'}
          >
            <Redo2 size={11} /> <span className="hidden sm:inline">Redo</span>
            {history.redoDepth > 0 && <span className="text-[8px] text-gray-600">{history.redoDepth}</span>}
          </button>
        </div>
        <button type="button" onClick={() => void load(locator)} className={quietButton}>
          <RefreshCw size={11} /> Refresh
        </button>
        <button type="button" onClick={onClose} className={quietButton}>New view</button>
      </header>

      {runtimePanelOpen && (
        <div className="border-b border-surface-border bg-surface-light/80 px-3 py-2">
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
              <Server size={12} className="text-jmix-400" /> Real Jmix runtime
            </div>
            {runtime?.targets.length ? (
              <select
                value={selectedRuntimeTarget?.id ?? ''}
                onChange={(event) => setSelectedRuntimeTargetId(event.target.value)}
                className="min-w-0 max-w-full flex-1 py-1 text-[10px] sm:min-w-64"
              >
                {runtime.targets.map((target) => (
                  <option key={target.id} value={target.id}>
                    {target.reachable ? '●' : '○'} {target.moduleId} · {target.profile} · {target.baseUrl}
                  </option>
                ))}
              </select>
            ) : (
              <span className="text-[10px] text-amber-300/80">
                {runtimeLoading
                  ? 'Inspecting application modules and local ports…'
                  : runtime?.issues[0]?.message ?? 'No runnable local Jmix target was detected.'}
              </span>
            )}
            <button
              type="button"
              onClick={() => void inspectRuntime(locator)}
              disabled={runtimeLoading}
              className={quietButton}
            >
              <RefreshCw size={10} className={runtimeLoading ? 'animate-spin' : ''} /> Detect
            </button>
            {selectedRuntimeTarget && (
              <>
                {(['DESKTOP', 'TABLET', 'MOBILE'] as const).map((viewport) => (
                  <button
                    type="button"
                    key={viewport}
                    onClick={() => void openRuntimePreview(viewport)}
                    className={quietButton}
                  >
                    <ExternalLink size={10} /> {viewport.toLowerCase()}
                  </button>
                ))}
                <button
                  type="button"
                  onClick={() => void previewHotDeploy()}
                  disabled={!selectedRuntimeTarget.reachable || !selectedRuntimeTarget.hotDeploySupported}
                  title={selectedRuntimeTarget.hotDeployMessage}
                  className={primaryButton}
                >
                  <Zap size={11} /> Preview hot deploy
                </button>
              </>
            )}
          </div>
          {selectedRuntimeTarget && (
            <div className="mt-1.5 grid gap-1 text-[9px] text-gray-500 md:grid-cols-2">
              <div className="truncate">
                Route: <span className="font-mono text-gray-400">{selectedRuntimeTarget.previewUrl}</span>
                {' · '}
                {selectedRuntimeTarget.responseTimeMillis} ms
              </div>
              <div className="truncate">
                Config: {selectedRuntimeTarget.configSources.join(', ') || 'Spring defaults'}
              </div>
              {selectedRuntimeTarget.warnings.map((warning) => (
                <div key={warning} className="text-amber-300/70">⚠ {warning}</div>
              ))}
              {!selectedRuntimeTarget.hotDeploySupported && selectedRuntimeTarget.hotDeployMessage && (
                <div className="text-amber-300/70">Hot deploy unavailable: {selectedRuntimeTarget.hotDeployMessage}</div>
              )}
            </div>
          )}
        </div>
      )}

      <div className="view-designer-workspace relative flex min-h-0 flex-1 overflow-hidden">
        <aside
          aria-label="FlowUI component palette and structure"
          className="view-designer-left flex min-h-0 flex-col border-r border-surface-border bg-surface-light"
        >
          <div className="flex min-h-0 basis-[48%] flex-col">
            <div className="flex items-center border-b border-surface-border">
              <div className="min-w-0 flex-1">
                <PanelTitle icon={Library} title="Components" count={flowUiComponentCatalog.length} />
              </div>
            </div>
            <div className="border-b border-surface-border p-2">
              <label className="flex min-w-0 items-center gap-1.5 rounded border border-surface-border bg-surface px-2">
                <Search size={11} className="shrink-0 text-gray-600" />
                <input
                  value={catalogQuery}
                  onChange={(event) => setCatalogQuery(event.target.value)}
                  placeholder="Search components…"
                  className="min-w-0 flex-1 border-0 bg-transparent px-0 py-1.5 text-[10px] outline-none"
                />
                {catalogQuery && (
                  <button type="button" onClick={() => setCatalogQuery('')} aria-label="Clear component search">
                    <X size={10} className="text-gray-600 hover:text-gray-300" />
                  </button>
                )}
              </label>
              <div className="mt-1.5 flex max-w-full gap-1 overflow-x-auto pb-1">
                {(['All', ...flowUiCatalogCategories] as const).map((category) => (
                  <button
                    type="button"
                    key={category}
                    onClick={() => setCatalogCategory(category)}
                    className={`shrink-0 rounded border px-2 py-1 text-[9px] ${
                      catalogCategory === category
                        ? 'border-jmix-500/50 bg-jmix-500/10 text-jmix-300'
                        : 'border-surface-border text-gray-500 hover:text-gray-300'
                    }`}
                  >
                    {category}
                  </button>
                ))}
              </div>
              <div className="mt-1 truncate text-[9px] text-gray-600">
                Drop into{' '}
                <span className="font-mono text-gray-400">
                  {preferredCatalogParent?.id ?? preferredCatalogParent?.localTag ?? 'a selected container'}
                </span>
              </div>
            </div>
            <div className="min-h-0 flex-1 space-y-1 overflow-y-auto p-2">
              {catalogItems.map((item) => {
                const targetTag = preferredCatalogParent?.localTag
                const parentAllowed = !item.allowedParents?.length ||
                  (targetTag !== undefined && item.allowedParents.includes(targetTag))
                return (
                  <button
                    type="button"
                    key={item.tag}
                    draggable={parentAllowed}
                    disabled={!preferredCatalogParent || !parentAllowed}
                    onDragStart={(event) => {
                      event.dataTransfer.effectAllowed = 'copy'
                      event.dataTransfer.setData(paletteDragType, item.tag)
                    }}
                    onClick={() => void previewCatalogInsert(item)}
                    title={
                      parentAllowed
                        ? `${item.description} Drag onto a compatible container or click to add.`
                        : `Requires ${item.allowedParents?.join(' or ')}.`
                    }
                    className="group flex w-full min-w-0 items-start gap-2 rounded border border-surface-border bg-surface p-2 text-left hover:border-jmix-500/50 disabled:cursor-not-allowed disabled:opacity-35"
                  >
                    <GripVertical size={11} className="mt-0.5 shrink-0 text-gray-700 group-hover:text-jmix-400" />
                    <span className="min-w-0 flex-1">
                      <span className="flex min-w-0 items-center justify-between gap-1.5">
                        <span className="truncate text-[10px] font-medium text-gray-300">{item.label}</span>
                        <span className="shrink-0 font-mono text-[8px] text-gray-600">&lt;{item.tag}&gt;</span>
                      </span>
                      <span className="mt-0.5 line-clamp-2 block text-[9px] leading-relaxed text-gray-600">
                        {item.description}
                      </span>
                    </span>
                  </button>
                )
              })}
              {!catalogItems.length && (
                <div className="rounded border border-dashed border-surface-border p-4 text-center text-[10px] text-gray-600">
                  No core FlowUI component matches this search.
                </div>
              )}
            </div>
          </div>

          <div className="flex min-h-0 flex-1 flex-col border-t border-surface-border">
            <PanelTitle icon={Layers} title="Component tree" count={document.elements.length} />
            <div className="min-h-0 flex-1 overflow-auto py-1">
              {elements.get(document.rootKey) && (
                <ElementTree
                  element={elements.get(document.rootKey)!}
                  elements={elements}
                  selectedKey={selectedKey}
                  onSelect={selectElement}
                />
              )}
            </div>
          </div>
          <div className="max-h-[28%] overflow-auto border-t border-surface-border">
            <PanelTitle icon={Database} title="Data & bindings" />
            <div className="space-y-1 p-2">
              {workspace.dataModel?.containers.map((container) => (
                <div key={container.elementKey} className="rounded border border-surface-border bg-surface p-2">
                  <button
                    type="button"
                    onClick={() => selectElement(container.elementKey)}
                    className="flex w-full items-center justify-between gap-2 text-left"
                  >
                    <span className="truncate font-mono text-[10px] text-jmix-300">{container.id}</span>
                    <span className="text-[9px] text-gray-600">{container.kind}</span>
                  </button>
                  <div className="mt-1 truncate text-[9px] text-gray-500">
                    {container.entityClass ?? 'key/value data'} · {container.fetchPlan ?? 'no fetch plan'}
                  </div>
                  {container.queryElementKey && (
                    <div className="mt-2">
                      <textarea
                        key={`${container.queryElementKey}-${container.query}`}
                        defaultValue={container.query ?? ''}
                        rows={3}
                        className="w-full resize-y font-mono text-[9px]"
                        aria-label={`${container.id} JPQL query`}
                      />
                      <button
                        type="button"
                        onClick={(event) => {
                          const textarea = event.currentTarget.previousElementSibling as HTMLTextAreaElement
                          void previewDirectText(container.queryElementKey!, textarea.value)
                        }}
                        className={`${quietButton} mt-1`}
                      >
                        Preview JPQL update
                      </button>
                      <div className="mt-1 text-[9px] text-gray-600">
                        {(workspace.dataModel?.queryParameters
                          .filter((parameter) => parameter.queryElementKey === container.queryElementKey)
                          .map((parameter) => `:${parameter.name}`)
                          .join(', ')) || 'No named parameters'}
                      </div>
                    </div>
                  )}
                </div>
              ))}
              {!workspace.dataModel?.containers.length && (
                <p className="p-2 text-[10px] text-gray-600">No FlowUI data containers were found.</p>
              )}
              <div className="border-t border-surface-border pt-2">
                {workspace.contextArtifacts.filter((artifact) => dataKinds.has(artifact.kind)).slice(0, 8).map((artifact) => (
                  <button
                    type="button"
                    key={artifact.id}
                    onClick={() => void bridge.navigateToSource(artifact.sourceLocator)}
                    className="mb-1 w-full truncate text-left text-[9px] text-gray-600 hover:text-jmix-300"
                  >
                    {artifact.kind.replace(/_/g, ' ')} · {artifact.displayName}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </aside>

        <section className="view-designer-canvas flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="border-b border-surface-border bg-surface-light/70">
            <div className="flex min-w-0 flex-wrap items-center gap-2 px-3 py-2">
              <div className="min-w-0 flex-1 truncate text-[10px] text-gray-500">
                Canvas · drop into{' '}
                <span className="font-mono text-gray-300">
                  {preferredCatalogParent?.id ?? preferredCatalogParent?.localTag ?? 'select a container'}
                </span>
                {structureBusy && <span className="ml-2 text-sky-300">Synchronizing XML…</span>}
              </div>
              <div className="flex items-center rounded border border-surface-border bg-surface">
                {([
                  ['DESKTOP', Monitor],
                  ['TABLET', Tablet],
                  ['MOBILE', Smartphone],
                ] as const).map(([viewport, Icon]) => (
                  <button
                    type="button"
                    key={viewport}
                    aria-label={`${viewport.toLowerCase()} canvas preview`}
                    onClick={() => setCanvasViewport(viewport)}
                    className={`border-r border-surface-border p-1.5 last:border-r-0 ${
                      canvasViewport === viewport ? 'bg-jmix-500/15 text-jmix-300' : 'text-gray-600 hover:text-gray-300'
                    }`}
                  >
                    <Icon size={11} />
                  </button>
                ))}
              </div>
              <div className="flex items-center rounded border border-surface-border bg-surface">
                <button
                  type="button"
                  aria-label="Zoom canvas out"
                  onClick={() => setCanvasZoom((zoom) => Math.max(40, zoom - 10))}
                  className="p-1.5 text-gray-600 hover:text-gray-300"
                >
                  <ZoomOut size={11} />
                </button>
                <span className="w-9 text-center text-[9px] text-gray-500">{canvasZoom}%</span>
                <button
                  type="button"
                  aria-label="Zoom canvas in"
                  onClick={() => setCanvasZoom((zoom) => Math.min(120, zoom + 10))}
                  className="p-1.5 text-gray-600 hover:text-gray-300"
                >
                  <ZoomIn size={11} />
                </button>
              </div>
            </div>
          </div>
          <div
            className="flex-1 overflow-auto p-3 sm:p-6"
            style={{
              backgroundImage: 'radial-gradient(circle, rgba(69,71,90,0.45) 1px, transparent 1px)',
              backgroundSize: '18px 18px',
            }}
            onClick={() => setSelectedKey(null)}
          >
            <div
              className="mx-auto min-h-full"
              style={{
                width: `${canvasViewportWidth[canvasViewport] * canvasZoom / 100}px`,
                minWidth: `${canvasViewportWidth[canvasViewport] * canvasZoom / 100}px`,
              }}
            >
              <div
                className={`flex min-h-full flex-col gap-3 rounded border bg-surface/80 p-3 shadow-2xl shadow-black/30 sm:p-4 ${
                  canvasDropActive ? 'border-sky-400 ring-2 ring-sky-500/25' : 'border-surface-border'
                }`}
                style={{
                  width: `${canvasViewportWidth[canvasViewport]}px`,
                  zoom: canvasZoom / 100,
                } as CSSProperties}
                onDragOver={(event) => {
                  if (!layout || structureBusy) return
                  event.preventDefault()
                  event.dataTransfer.dropEffect = event.dataTransfer.types.includes(elementDragType) ? 'move' : 'copy'
                  setCanvasDropActive(true)
                }}
                onDragLeave={(event) => {
                  if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setCanvasDropActive(false)
                }}
                onDrop={(event) => {
                  if (!layout || structureBusy) return
                  event.preventDefault()
                  event.stopPropagation()
                  setCanvasDropActive(false)
                  const movedElement = event.dataTransfer.getData(elementDragType)
                  const catalogTag = event.dataTransfer.getData(paletteDragType)
                  if (movedElement) void previewCanvasReparent(movedElement, layout.key)
                  else if (catalogTag) void previewCanvasCatalogDrop(catalogTag, layout.key)
                }}
              >
                {!canvasRoots.length && (
                  <div className="flex min-h-48 flex-col items-center justify-center rounded border border-dashed border-surface-border p-6 text-center">
                    <Library size={22} className="text-jmix-500/60" />
                    <div className="mt-2 text-xs text-gray-400">Start this screen visually</div>
                    <div className="mt-1 max-w-sm text-[10px] leading-relaxed text-gray-600">
                      Drag a responsive form, flex/grid layout, table, or field here. Live arrange validates and updates
                      the exact XML revision immediately; Undo restores accidental additions, moves, and deletions.
                    </div>
                  </div>
                )}
                {canvasRoots.map((element) => (
                  <CanvasNode
                    key={element.key}
                    element={element}
                    elements={elements}
                    selectedKey={selectedKey}
                    movable={(candidate) => !structureBusy && (
                      candidate.parentKey !== layout?.key || layoutKeys.has(candidate.key)
                    )}
                    onSelect={selectElement}
                    onDropElement={(elementKey, targetKey, placement) => {
                      void previewCanvasReparent(elementKey, targetKey, placement)
                    }}
                    onDropCatalog={(tag, targetKey, placement) => {
                      void previewCanvasCatalogDrop(tag, targetKey, placement)
                    }}
                  />
                ))}
              </div>
            </div>
          </div>
          {pending && (
            <div className="border-t border-amber-500/30 bg-amber-500/5 p-3">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <div className="text-xs font-medium text-amber-200">{pending.preview.label}</div>
                  <div className="mt-0.5 text-[10px] text-amber-100/60">
                    {pending.preview.files[0]?.relativePath} · exact {pending.preview.files[0]?.appliedEditCount} edit
                  </div>
                  <div className="mt-1 font-mono text-[10px] text-amber-100">
                    {pending.kind === 'property'
                      ? <>{pending.change.propertyName} → &quot;{pending.change.value}&quot;</>
                      : pending.kind === 'structure'
                        ? <>{pending.change.operation.replace(/_/g, ' ').toLowerCase()} {pending.change.tagName ?? ''}</>
                        : pending.kind === 'text'
                          ? <>update direct query/text value</>
                          : pending.kind === 'controllerInjection'
                            ? <>inject {pending.change.componentId} into controller</>
                            : pending.kind === 'controllerHandler'
                              ? <>generate {pending.change.kind.replace(/_/g, ' ').toLowerCase()} handler</>
                              : <>stage descriptor and verified Jmix cache-reset trigger</>}
                  </div>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button type="button" onClick={() => setPending(null)} className={quietButton}>Discard</button>
                  <button type="button" onClick={() => void applyPending()} disabled={applying} className={primaryButton}>
                    {applying ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
                    Apply approved preview
                  </button>
                </div>
              </div>
            </div>
          )}
          <footer className="flex flex-wrap gap-x-4 gap-y-1 border-t border-surface-border px-3 py-1 text-[10px] text-gray-600">
            <span>{document.elements.length} XML elements</span>
            <span>{workspace.contextArtifacts.length} connected artifacts</span>
            <span>{workspace.contextRelationships.length} impact links</span>
            <span className="ml-auto">Revision {document.revisionFingerprint.slice(0, 12)}</span>
          </footer>
        </section>

        <aside
          aria-label="FlowUI property inspector"
          className="view-designer-right flex min-h-0 flex-col overflow-hidden border-l border-surface-border bg-surface-light"
        >
          <div className="flex items-center border-b border-surface-border">
            <div className="min-w-0 flex-1">
              <PanelTitle icon={Tag} title="Exact XML properties" />
            </div>
          </div>
          <div className="max-h-[55%] overflow-auto p-3">
            {!selected ? (
              <p className="py-8 text-center text-[11px] text-gray-500">Select an XML element to inspect it.</p>
            ) : (
              <div className="space-y-2">
                <div className="rounded border border-surface-border bg-surface px-2 py-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-mono text-[11px] text-jmix-300">{selected.tagName}</div>
                    {selected.parentKey && selectedInLayout && (
                      <div className="flex gap-0.5">
                        <button
                          type="button"
                          onClick={() => void previewStructure(
                            { operation: 'MOVE_UP', elementKey: selected.key },
                            selected.id,
                          )}
                          className={quietButton}
                          title="Move component up"
                        >
                          <ArrowUp size={10} />
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewStructure(
                            { operation: 'MOVE_DOWN', elementKey: selected.key },
                            selected.id,
                          )}
                          className={quietButton}
                          title="Move component down"
                        >
                          <ArrowDown size={10} />
                        </button>
                        {selected.parentKey !== document.rootKey && (
                          <button
                            type="button"
                            onClick={() => void previewStructure(
                              { operation: 'DELETE', elementKey: selected.key },
                              selectedParent?.id,
                            )}
                            className={`${quietButton} hover:border-red-500/60 hover:text-red-300`}
                            title="Delete component"
                          >
                            <Trash2 size={10} />
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                  <div className="mt-0.5 text-[9px] text-gray-600">
                    Source bytes {selected.sourceStart}–{selected.sourceEnd}
                  </div>
                </div>
                {selectedInLayout && selected.parentKey && (
                  <div className="rounded border border-violet-500/20 bg-violet-500/5 p-2">
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-[9px] font-semibold uppercase tracking-wider text-violet-300/80">
                        Arrange &amp; reuse
                      </div>
                      <span className="text-[8px] text-gray-600">Source-safe · undoable</span>
                    </div>
                    <div className="mt-1.5 grid grid-cols-4 gap-1">
                      <button
                        type="button"
                        onClick={() => captureSelection('COPY')}
                        className={quietButton}
                        title="Copy component subtree (⌘/Ctrl+C)"
                      >
                        <Copy size={10} /> <span className="hidden xl:inline">Copy</span>
                      </button>
                      <button
                        type="button"
                        onClick={() => captureSelection('CUT')}
                        className={quietButton}
                        title="Cut component for repositioning (⌘/Ctrl+X)"
                      >
                        <Scissors size={10} /> <span className="hidden xl:inline">Cut</span>
                      </button>
                      <button
                        type="button"
                        onClick={() => void pasteSelection()}
                        disabled={!clipboard}
                        className={quietButton}
                        title="Paste into the selected container or after the selected component (⌘/Ctrl+V)"
                      >
                        <ClipboardPaste size={10} /> <span className="hidden xl:inline">Paste</span>
                      </button>
                      <button
                        type="button"
                        onClick={() => void duplicateSelection()}
                        className={quietButton}
                        title="Duplicate beside this component (⌘/Ctrl+D)"
                      >
                        <Boxes size={10} /> <span className="hidden xl:inline">Clone</span>
                      </button>
                    </div>
                    {clipboard && (
                      <div className="mt-1.5 flex items-center justify-between gap-2 rounded bg-surface/70 px-1.5 py-1 text-[8px]">
                        <span className="truncate text-violet-300">
                          {clipboard.mode === 'CUT' ? 'Moving' : 'Copied'}: {clipboard.label}
                        </span>
                        <button
                          type="button"
                          onClick={() => setClipboard(null)}
                          className="text-gray-600 hover:text-gray-300"
                          title="Clear component clipboard"
                        >
                          <X size={9} />
                        </button>
                      </div>
                    )}
                    <div className="mt-1.5 flex gap-1">
                      <select
                        value={wrapLayout}
                        onChange={(event) => setWrapLayout(event.target.value as (typeof convertibleLayouts)[number])}
                        className="min-w-0 flex-1 py-1 text-[9px]"
                        aria-label="Wrapper layout"
                      >
                        {convertibleLayouts.map((tag) => (
                          <option key={tag} value={tag}>Wrap in {tag}</option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={() => void wrapSelection()}
                        className={quietButton}
                        title="Wrap the selected component without recreating it"
                      >
                        <Boxes size={10} /> Wrap
                      </button>
                    </div>
                    {convertibleLayouts.includes(selected.localTag as (typeof convertibleLayouts)[number]) && (
                      <label className="mt-1.5 flex items-center gap-1 text-[9px] text-gray-500">
                        <Repeat2 size={10} className="shrink-0" />
                        Convert layout
                        <select
                          value={selected.localTag}
                          onChange={(event) => void convertSelection(
                            event.target.value as (typeof convertibleLayouts)[number],
                          )}
                          className="min-w-0 flex-1 py-1 text-[9px]"
                        >
                          {convertibleLayouts.map((tag) => <option key={tag} value={tag}>{tag}</option>)}
                        </select>
                      </label>
                    )}
                    <p className="mt-1.5 text-[8px] leading-3 text-gray-600">
                      Nested IDs and internal component references are renamed when copied. Cut/paste repositions the exact XML subtree.
                    </p>
                  </div>
                )}
                {selectedInLayout && (
                  <div className="rounded border border-sky-500/20 bg-sky-500/5 p-2">
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-[9px] font-semibold uppercase tracking-wider text-sky-300/80">
                        Visual layout &amp; responsiveness
                      </div>
                      <span className="truncate font-mono text-[8px] text-gray-600">
                        in {selectedParent?.id ?? selectedParent?.localTag ?? 'layout'}
                      </span>
                    </div>
                    <div className="mt-2 grid grid-cols-2 gap-1.5">
                      <label className="text-[9px] text-gray-500">
                        Width
                        <select
                          value={attributeValue(selected, 'width') ?? 'AUTO'}
                          onChange={(event) => void previewProperty('width', event.target.value)}
                          className="mt-0.5 w-full py-1 text-[10px]"
                        >
                          {attributeValue(selected, 'width') &&
                            !['AUTO', '100%', '50%', '25rem'].includes(attributeValue(selected, 'width')!) && (
                              <option value={attributeValue(selected, 'width')}>{attributeValue(selected, 'width')}</option>
                          )}
                          <option value="AUTO">Content</option>
                          <option value="100%">Full width</option>
                          <option value="50%">Half width</option>
                          <option value="25rem">25 rem</option>
                        </select>
                      </label>
                      <label className="text-[9px] text-gray-500">
                        Align self
                        <select
                          value={attributeValue(selected, 'alignSelf') ?? 'AUTO'}
                          onChange={(event) => void previewProperty('alignSelf', event.target.value)}
                          className="mt-0.5 w-full py-1 text-[10px]"
                        >
                          {['AUTO', 'START', 'CENTER', 'END', 'STRETCH'].map((value) => <option key={value}>{value}</option>)}
                        </select>
                      </label>
                      {selectedParent?.localTag === 'formLayout' && (
                        <label className="col-span-2 text-[9px] text-gray-500">
                          Column span
                          <select
                            value={attributeValue(selected, 'colspan') ?? '1'}
                            onChange={(event) => void previewProperty('colspan', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            {[1, 2, 3, 4, 5, 6].map((value) => <option key={value}>{value}</option>)}
                          </select>
                        </label>
                      )}
                    </div>

                    {selected.localTag === 'formLayout' && (
                      <div className="mt-2 grid grid-cols-2 gap-1.5 border-t border-sky-500/15 pt-2">
                        <label className="text-[9px] text-gray-500">
                          Responsive
                          <select
                            value={attributeValue(selected, 'autoResponsive') ?? 'false'}
                            onChange={(event) => void previewProperty('autoResponsive', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            <option value="true">Automatic</option>
                            <option value="false">Explicit steps</option>
                          </select>
                        </label>
                        <label className="text-[9px] text-gray-500">
                          Maximum columns
                          <select
                            value={attributeValue(selected, 'maxColumns') ?? '2'}
                            onChange={(event) => void previewProperty('maxColumns', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            {[1, 2, 3, 4, 5, 6].map((value) => <option key={value}>{value}</option>)}
                          </select>
                        </label>
                        <label className="text-[9px] text-gray-500">
                          Minimum columns
                          <select
                            value={attributeValue(selected, 'minColumns') ?? '1'}
                            onChange={(event) => void previewProperty('minColumns', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            {[1, 2, 3, 4].map((value) => <option key={value}>{value}</option>)}
                          </select>
                        </label>
                        <label className="text-[9px] text-gray-500">
                          Column width
                          <input
                            key={`${selected.key}-columnWidth-${attributeValue(selected, 'columnWidth')}`}
                            defaultValue={attributeValue(selected, 'columnWidth') ?? '12rem'}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') void previewProperty('columnWidth', event.currentTarget.value)
                            }}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          />
                        </label>
                      </div>
                    )}

                    {selected.localTag === 'gridLayout' && (
                      <div className="mt-2 grid grid-cols-2 gap-1.5 border-t border-sky-500/15 pt-2">
                        <label className="text-[9px] text-gray-500">
                          Minimum cell width
                          <input
                            key={`${selected.key}-columnMinWidth-${attributeValue(selected, 'columnMinWidth')}`}
                            defaultValue={attributeValue(selected, 'columnMinWidth') ?? '19rem'}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') void previewProperty('columnMinWidth', event.currentTarget.value)
                            }}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          />
                        </label>
                        <label className="text-[9px] text-gray-500">
                          Gap
                          <input
                            key={`${selected.key}-gap-${attributeValue(selected, 'gap')}`}
                            defaultValue={attributeValue(selected, 'gap') ?? '0.5rem'}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') void previewProperty('gap', event.currentTarget.value)
                            }}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          />
                        </label>
                      </div>
                    )}

                    {selected.localTag === 'flexLayout' && (
                      <div className="mt-2 grid grid-cols-2 gap-1.5 border-t border-sky-500/15 pt-2">
                        <label className="text-[9px] text-gray-500">
                          Direction
                          <select
                            value={attributeValue(selected, 'flexDirection') ?? 'ROW'}
                            onChange={(event) => void previewProperty('flexDirection', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            {['ROW', 'ROW_REVERSE', 'COLUMN', 'COLUMN_REVERSE'].map((value) => <option key={value}>{value}</option>)}
                          </select>
                        </label>
                        <label className="text-[9px] text-gray-500">
                          Wrap
                          <select
                            value={attributeValue(selected, 'flexWrap') ?? 'NOWRAP'}
                            onChange={(event) => void previewProperty('flexWrap', event.target.value)}
                            className="mt-0.5 w-full py-1 text-[10px]"
                          >
                            {['NOWRAP', 'WRAP', 'WRAP_REVERSE'].map((value) => <option key={value}>{value}</option>)}
                          </select>
                        </label>
                      </div>
                    )}
                    <p className="mt-2 text-[8px] leading-3 text-gray-600">
                      Jmix-native layout properties compile to responsive XML. Absolute screen coordinates are intentionally
                      avoided because they break accessibility and device resizing.
                    </p>
                  </div>
                )}
                {selected.attributes.map((attribute) => (
                  <label key={attribute.name} className="block">
                    <span className="text-[9px] uppercase tracking-wider text-gray-600">{attribute.name}</span>
                    <div className="mt-0.5 flex gap-1">
                      <input
                        key={`${selected.key}-${attribute.name}-${attribute.value}`}
                        defaultValue={attribute.value}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter') void previewProperty(attribute.name, event.currentTarget.value)
                        }}
                        className="min-w-0 flex-1 py-1 text-[10px]"
                      />
                      <button
                        type="button"
                        disabled={attribute.name === 'xmlns' || attribute.name.startsWith('xmlns:')}
                        onClick={(event) => {
                          const input = event.currentTarget.previousElementSibling as HTMLInputElement
                          void previewProperty(attribute.name, input.value)
                        }}
                        className={quietButton}
                      >
                        {liveDesign ? 'Apply' : 'Preview'}
                      </button>
                    </div>
                  </label>
                ))}
                {selectedInLayout && workspace.dataModel && (
                  <div className="border-t border-surface-border pt-2">
                    <div className="text-[9px] uppercase tracking-wider text-gray-600">Smart entity binding</div>
                    <label className="mt-1 block text-[9px] text-gray-500">
                      Data container
                      <select
                        value={selectedBinding?.containerId ?? ''}
                        onChange={(event) => void previewElementProperty(selected.key, 'dataContainer', event.target.value)}
                        className="mt-0.5 w-full py-1 text-[10px]"
                      >
                        <option value="">— select —</option>
                        {workspace.dataModel.containers.map((container) => (
                          <option key={container.elementKey} value={container.id}>
                            {container.id} · {container.entityClass ?? container.kind}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="mt-1 block text-[9px] text-gray-500">
                      Entity property
                      <select
                        value={selectedBinding?.property ?? ''}
                        disabled={!selectedBinding?.containerId}
                        onChange={(event) => void previewElementProperty(selected.key, 'property', event.target.value)}
                        className="mt-0.5 w-full py-1 text-[10px]"
                      >
                        <option value="">— select —</option>
                        {selectedFieldOptions.map((field) => (
                          <option key={field.artifactId} value={field.name}>
                            {field.name}{field.type ? ` · ${field.type}` : ''}
                          </option>
                        ))}
                      </select>
                    </label>
                  </div>
                )}
                {selected.id && workspace.controllerModel?.psiSupported && (
                  <div className="border-t border-surface-border pt-2">
                    <div className="text-[9px] uppercase tracking-wider text-gray-600">Java controller</div>
                    {selectedInLayout && (
                      workspace.controllerModel.injections.some((injection) => injection.componentId === selected.id) ? (
                        <button
                          type="button"
                          onClick={() => {
                            const injection = workspace.controllerModel!.injections
                              .find((candidate) => candidate.componentId === selected.id)
                            if (injection) void bridge.navigateToSource(injection.sourceLocator)
                          }}
                          className={`${quietButton} mt-1.5`}
                        >
                          Open existing @ViewComponent
                        </button>
                      ) : (
                        <button
                          type="button"
                          onClick={() => void previewControllerInjection()}
                          className={`${quietButton} mt-1.5`}
                        >
                          Preview Inject to Controller
                        </button>
                      )
                    )}
                    {selected.localTag === 'button' && (
                      <button
                        type="button"
                        onClick={() => void previewControllerHandler('BUTTON_CLICK', selected)}
                        className={`${quietButton} mt-1.5`}
                      >
                        Preview Click Handler
                      </button>
                    )}
                    {selected.localTag === 'action' && (
                      <button
                        type="button"
                        onClick={() => void previewControllerHandler('ACTION_PERFORMED', selected)}
                        className={`${quietButton} mt-1.5`}
                      >
                        Preview Action Handler
                      </button>
                    )}
                    {selectedInLayout && typedValueComponents.has(selected.localTag) && (
                      <div className="mt-1.5 grid grid-cols-2 gap-1">
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COMPONENT_TYPED_VALUE_CHANGE', selected)}
                          className={quietButton}
                        >
                          Typed change
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COMPONENT_VALUE_CHANGE', selected)}
                          className={quietButton}
                        >
                          Raw change
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COMPONENT_VALIDATOR', selected)}
                          className={`${quietButton} col-span-2`}
                        >
                          Preview UI validator skeleton
                        </button>
                        <p className="col-span-2 text-[9px] leading-relaxed text-amber-300/70">
                          UI validation is not a server control. Enforce payroll and financial rules in a transactional
                          service as well.
                        </p>
                      </div>
                    )}
                    {selected.localTag === 'loader' && workspace.dataModel?.containers.some(
                      (container) => container.loaderElementKey === selected.key &&
                        container.kind.toLowerCase().includes('collection'),
                    ) && (
                      <div className="mt-1.5 grid grid-cols-2 gap-1">
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COLLECTION_LOADER_PRE_LOAD', selected)}
                          className={quietButton}
                        >
                          Pre-load
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COLLECTION_LOADER_POST_LOAD', selected)}
                          className={quietButton}
                        >
                          Post-load
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewControllerHandler('COLLECTION_LOADER_LOAD_DELEGATE', selected)}
                          className={`${quietButton} col-span-2`}
                        >
                          Preview load delegate
                        </button>
                      </div>
                    )}
                  </div>
                )}
                <div className="border-t border-surface-border pt-2">
                  <div className="text-[9px] uppercase tracking-wider text-gray-600">Add property</div>
                  <input
                    value={newProperty}
                    onChange={(event) => setNewProperty(event.target.value)}
                    placeholder="property name"
                    className="mt-1 w-full py-1 font-mono text-[10px]"
                  />
                  <input
                    value={newValue}
                    onChange={(event) => setNewValue(event.target.value)}
                    placeholder="value"
                    className="mt-1 w-full py-1 text-[10px]"
                  />
                  <button
                    type="button"
                    onClick={() => void previewProperty(newProperty, newValue)}
                    disabled={!newProperty.trim()}
                    className={`${quietButton} mt-1.5`}
                  >
                    Preview exact insertion
                  </button>
                </div>
                {selectedInLayout && componentContainers.has(selected.localTag) && !selected.selfClosing && (
                  <div className="border-t border-surface-border pt-2">
                    <div className="text-[9px] uppercase tracking-wider text-gray-600">Insert child component</div>
                    <select
                      value={newTag}
                      onChange={(event) => setNewTag(event.target.value)}
                      className="mt-1 w-full py-1 text-[10px]"
                    >
                      {[
                        'vbox', 'hbox', 'formLayout', 'tabSheet', 'textField', 'textArea',
                        'bigDecimalField', 'datePicker', 'entityPicker', 'dataGrid',
                        'genericFilter', 'button', 'span', 'fileUploadField',
                      ].map((tagName) => <option key={tagName} value={tagName}>{tagName}</option>)}
                    </select>
                    <input
                      value={newComponentId}
                      onChange={(event) => setNewComponentId(event.target.value.replace(/\s+/g, ''))}
                      placeholder="component id (optional)"
                      className="mt-1 w-full py-1 font-mono text-[10px]"
                    />
                    <button
                      type="button"
                      onClick={() => void previewStructure(
                        {
                          operation: 'INSERT_CHILD',
                          parentKey: selected.key,
                          tagName: newTag,
                          attributes: newComponentId ? { id: newComponentId } : {},
                          childCapable: flowUiComponentCatalog.find((item) => item.tag === newTag)?.childCapable ?? false,
                        },
                        newComponentId || undefined,
                      )}
                      className={`${quietButton} mt-1.5`}
                    >
                      <Plus size={10} /> {liveDesign ? 'Insert component' : 'Preview insertion'}
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto border-t border-surface-border">
            <PanelTitle icon={Code2} title="Controller & business logic" />
            <div className="space-y-1 p-2">
              {workspace.controllerModel && (
                <div className="mb-2 rounded border border-surface-border bg-surface p-2">
                  <button
                    type="button"
                    onClick={() => void bridge.navigateToSource({
                      relativePath: workspace.controllerModel!.relativePath,
                      revisionFingerprint: workspace.controllerModel!.revisionFingerprint,
                    })}
                    className="w-full text-left"
                  >
                    <div className="truncate font-mono text-[10px] text-jmix-300">
                      {workspace.controllerModel.className}
                    </div>
                    <div className="mt-0.5 text-[9px] text-gray-600">
                      PSI {workspace.controllerModel.psiSupported ? 'connected' : 'read-only'} · {workspace.controllerModel.language}
                    </div>
                  </button>
                  {workspace.controllerModel.message && (
                    <p className="mt-1 text-[9px] leading-relaxed text-amber-300/70">
                      {workspace.controllerModel.message}
                    </p>
                  )}
                  {workspace.controllerModel.psiSupported && (
                    <>
                      <div className={`mt-2 rounded border px-2 py-1.5 text-[9px] ${
                        controllerIssueCount > 0
                          ? 'border-red-500/40 bg-red-500/10 text-red-200'
                          : 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
                      }`}>
                        {controllerIssueCount > 0
                          ? `${controllerIssueCount} native controller contract issue${controllerIssueCount === 1 ? '' : 's'}`
                          : 'Native controller contracts verified'}
                      </div>
                      <div className="mt-2 grid grid-cols-2 gap-1">
                        {([
                          ['VIEW_INIT', 'Init'],
                          ['VIEW_BEFORE_SHOW', 'Before show'],
                          ['VIEW_READY', 'Ready'],
                          ['VIEW_ATTACH', 'Attach'],
                          ['VIEW_BEFORE_CLOSE', 'Before close'],
                          ['VIEW_AFTER_CLOSE', 'After close'],
                          ['VIEW_DETACH', 'Detach'],
                          ['VIEW_QUERY_PARAMETERS_CHANGE', 'Query parameters'],
                        ] as const).map(([kind, label]) => (
                          <button
                            type="button"
                            key={kind}
                            onClick={() => void previewControllerHandler(kind)}
                            className={quietButton}
                          >
                            + {label}
                          </button>
                        ))}
                      </div>
                    </>
                  )}
                </div>
              )}
              {workspace.controllerModel?.injections.map((injection) => (
                <button
                  type="button"
                  key={`injection-${injection.fieldName}`}
                  onClick={() => void bridge.navigateToSource(injection.sourceLocator)}
                  className={`w-full rounded border bg-surface px-2 py-1.5 text-left ${
                    injection.issues?.length
                      ? 'border-red-500/40 hover:border-red-400/70'
                      : 'border-surface-border hover:border-jmix-500/50'
                  }`}
                >
                  <div className="truncate font-mono text-[10px] text-gray-300">
                    @ViewComponent {injection.fieldName}
                  </div>
                  <div className="mt-0.5 truncate text-[9px] text-gray-600">
                    {injection.componentId} · {injection.type}
                  </div>
                  {injection.issues?.map((issue) => (
                    <div
                      key={`${injection.fieldName}-${issue.code}`}
                      className={`mt-1 text-[9px] leading-snug ${
                        issue.severity === 'ERROR' ? 'text-red-300' : 'text-amber-300'
                      }`}
                    >
                      {issue.message}
                    </div>
                  ))}
                </button>
              ))}
              {workspace.controllerModel?.handlers.map((handler) => (
                <button
                  type="button"
                  key={`handler-${handler.kind}-${handler.methodName}-${handler.target ?? ''}`}
                  onClick={() => void bridge.navigateToSource(handler.sourceLocator)}
                  className={`w-full rounded border bg-surface px-2 py-1.5 text-left ${
                    handler.issues?.length
                      ? 'border-red-500/40 hover:border-red-400/70'
                      : 'border-surface-border hover:border-jmix-500/50'
                  }`}
                >
                  <div className="truncate font-mono text-[10px] text-gray-300">
                    @{handler.kind} {handler.methodName}()
                  </div>
                  <div className="mt-0.5 truncate text-[9px] text-gray-600">
                    {[handler.target, handler.subject, ...handler.parameterTypes].filter(Boolean).join(' · ') || 'view lifecycle'}
                  </div>
                  {handler.issues?.map((issue) => (
                    <div
                      key={`${handler.methodName}-${issue.code}`}
                      className={`mt-1 text-[9px] leading-snug ${
                        issue.severity === 'ERROR' ? 'text-red-300' : 'text-amber-300'
                      }`}
                    >
                      {issue.message}
                    </div>
                  ))}
                </button>
              ))}
              {workspace.contextArtifacts.filter((artifact) => logicKinds.has(artifact.kind)).map((artifact) => (
                <button
                  type="button"
                  key={artifact.id}
                  onClick={() => void bridge.navigateToSource(artifact.sourceLocator)}
                  className="w-full rounded border border-surface-border bg-surface px-2 py-1.5 text-left hover:border-jmix-500/50"
                >
                  <div className="truncate text-[10px] text-gray-300">{artifact.displayName}</div>
                  <div className="mt-0.5 flex items-center justify-between gap-2 text-[9px] text-gray-600">
                    <span>{artifact.kind.replace(/_/g, ' ')}</span>
                    <span className="truncate">{artifact.owner.moduleId}</span>
                  </div>
                </button>
              ))}
              {!workspace.contextArtifacts.some((artifact) => logicKinds.has(artifact.kind)) && (
                <p className="p-2 text-[10px] text-gray-600">No connected Java or policy artifacts were indexed.</p>
              )}
            </div>
          </div>
        </aside>
      </div>
    </div>
  )
}
