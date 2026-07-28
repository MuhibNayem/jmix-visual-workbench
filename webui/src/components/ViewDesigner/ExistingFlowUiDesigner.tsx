import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ArrowDown, ArrowUp, Code2, Database, Layers, Loader2, Play, Plus, RefreshCw, Tag, Trash2,
} from 'lucide-react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  FlowUiElementSnapshot,
  FlowUiDirectTextChangeRequest,
  FlowUiControllerInjectionRequest,
  FlowUiPropertyChangeRequest,
  FlowUiStructureChangeRequest,
  FlowUiWorkspaceResponse,
  GraphSourceLocator,
  WorkspaceChangePreviewResponse,
} from '../../types'

const primaryButton =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50'
const quietButton =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 hover:border-jmix-500/60 hover:text-jmix-300 disabled:opacity-50'
const componentContainers = new Set([
  'layout', 'vbox', 'hbox', 'formLayout', 'gridLayout', 'flexLayout', 'split',
  'tabSheet', 'tab', 'accordion', 'details', 'scroller',
])

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

function CanvasNode({ element, elements, selectedKey, onSelect }: {
  element: FlowUiElementSnapshot
  elements: Map<string, FlowUiElementSnapshot>
  selectedKey: string | null
  onSelect: (key: string) => void
}) {
  const caption = element.attributes.find((attribute) => ['text', 'label', 'caption'].includes(attribute.name))?.value
  const binding = [
    element.attributes.find((attribute) => attribute.name === 'dataContainer')?.value,
    element.attributes.find((attribute) => attribute.name === 'property')?.value,
  ].filter(Boolean).join('.')
  return (
    <div
      onClick={(event) => {
        event.stopPropagation()
        onSelect(element.key)
      }}
      className={`min-w-[7rem] rounded border p-2 ${
        selectedKey === element.key
          ? 'border-jmix-400 bg-jmix-500/10 ring-1 ring-jmix-500/30'
          : 'border-surface-border bg-surface-light/50 hover:border-gray-500'
      } ${element.childKeys.length ? 'w-full' : ''}`}
    >
      <div className="flex items-center justify-between gap-2">
        <span className="truncate font-mono text-[10px] text-gray-300">
          {element.localTag}{element.id ? ` #${element.id}` : ''}
        </span>
        {binding && <span className="truncate text-[9px] text-sky-400">{binding}</span>}
      </div>
      {caption && <div className="mt-1 truncate text-[10px] text-gray-500">{caption}</div>}
      {element.directText && (
        <pre className="mt-1 max-h-16 overflow-hidden whitespace-pre-wrap text-[9px] text-gray-600">
          {element.directText}
        </pre>
      )}
      {element.childKeys.length > 0 && (
        <div className={`mt-2 gap-2 ${element.localTag === 'hbox' ? 'flex flex-wrap' : 'flex flex-col'}`}>
          {element.childKeys.map((key) => {
            const child = elements.get(key)
            return child ? (
              <CanvasNode
                key={key}
                element={child}
                elements={elements}
                selectedKey={selectedKey}
                onSelect={onSelect}
              />
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
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [newProperty, setNewProperty] = useState('')
  const [newValue, setNewValue] = useState('')
  const [newTag, setNewTag] = useState('textField')
  const [newComponentId, setNewComponentId] = useState('')
  const [pending, setPending] = useState<{
    kind: 'property'
    change: FlowUiPropertyChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'structure'
    change: FlowUiStructureChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'text'
    change: FlowUiDirectTextChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | {
    kind: 'controller'
    change: FlowUiControllerInjectionRequest
    preview: WorkspaceChangePreviewResponse
  } | null>(null)

  const load = useCallback(async (target: GraphSourceLocator) => {
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
      setSelectedKey((current) => (
        current && response.document!.elements.some((element) => element.key === current)
          ? current
          : response.document!.rootKey
      ))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The FlowUI workspace request failed.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    setLocator(initialLocator)
    void load(initialLocator)
  }, [initialLocator, load])

  const document = workspace?.document
  const elements = useMemo(
    () => new Map(document?.elements.map((element) => [element.key, element]) ?? []),
    [document],
  )
  const selected = selectedKey ? elements.get(selectedKey) ?? null : null
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

  const previewElementProperty = async (elementKey: string, propertyName: string, value: string) => {
    if (!propertyName.trim()) return
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

  const previewStructure = async (change: Omit<FlowUiStructureChangeRequest, 'sourceLocator'>) => {
    const fullChange: FlowUiStructureChangeRequest = { sourceLocator: locator, ...change }
    const preview = await bridge.previewFlowUiStructureChange(fullChange)
    if (!preview.accepted) {
      addToast(preview.issues[0]?.message ?? 'The structure change was rejected.', 'error')
      return
    }
    if (!preview.planDigest || preview.files.length === 0) {
      addToast('The component is already at that position.', 'info')
      return
    }
    setPending({ kind: 'structure', change: fullChange, preview })
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
    setPending({ kind: 'controller', change, preview })
  }

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
            : await bridge.applyFlowUiControllerInjection(pending.change, pending.preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'The FlowUI source change failed.', 'error')
        return
      }
      if (pending.kind === 'controller') {
        addToast('Component injection added to the existing controller without replacing Java code.', 'success')
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
      addToast('FlowUI XML updated without rewriting unrelated source.', 'success')
      await load(nextLocator)
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
    'VIEW_CONTROLLER', 'VIEW_HANDLER', 'SERVICE', 'SERVICE_METHOD', 'VALIDATOR',
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

  return (
    <div className="flex h-full flex-col bg-surface">
      <header className="flex items-center gap-3 border-b border-surface-border bg-surface-light/60 px-3 py-2">
        <Code2 size={15} className="text-jmix-400" />
        <div className="min-w-0">
          <h2 className="truncate text-xs font-bold uppercase tracking-widest text-gray-300">
            Existing FlowUI · {document.viewId}
          </h2>
          <p className="truncate text-[10px] text-gray-600">{document.relativePath}</p>
        </div>
        <span className="ml-auto rounded border border-emerald-500/30 bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-300">
          Manual source preserved
        </span>
        <button type="button" onClick={() => void load(locator)} className={quietButton}>
          <RefreshCw size={11} /> Refresh
        </button>
        <button type="button" onClick={onClose} className={quietButton}>New view</button>
      </header>

      <div className="flex min-h-0 flex-1">
        <aside className="flex w-64 shrink-0 flex-col border-r border-surface-border bg-surface-light/40">
          <PanelTitle icon={Layers} title="Component tree" count={document.elements.length} />
          <div className="min-h-0 flex-1 overflow-auto py-1">
            {elements.get(document.rootKey) && (
              <ElementTree
                element={elements.get(document.rootKey)!}
                elements={elements}
                selectedKey={selectedKey}
                onSelect={setSelectedKey}
              />
            )}
          </div>
          <div className="max-h-[38%] overflow-auto border-t border-surface-border">
            <PanelTitle icon={Database} title="Data & bindings" />
            <div className="space-y-1 p-2">
              {workspace.dataModel?.containers.map((container) => (
                <div key={container.elementKey} className="rounded border border-surface-border bg-surface p-2">
                  <button
                    type="button"
                    onClick={() => setSelectedKey(container.elementKey)}
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

        <section className="flex min-w-0 flex-1 flex-col">
          <div
            className="flex-1 overflow-auto p-6"
            style={{
              backgroundImage: 'radial-gradient(circle, rgba(69,71,90,0.45) 1px, transparent 1px)',
              backgroundSize: '18px 18px',
            }}
            onClick={() => setSelectedKey(null)}
          >
            <div className="mx-auto flex min-h-full max-w-4xl flex-col gap-3 rounded border border-surface-border bg-surface/80 p-4 shadow-2xl shadow-black/30">
              {canvasRoots.map((element) => (
                <CanvasNode
                  key={element.key}
                  element={element}
                  elements={elements}
                  selectedKey={selectedKey}
                  onSelect={setSelectedKey}
                />
              ))}
            </div>
          </div>
          {pending && (
            <div className="border-t border-amber-500/30 bg-amber-500/5 p-3">
              <div className="flex items-center justify-between gap-3">
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
                          : <>inject {pending.change.componentId} into controller</>}
                  </div>
                </div>
                <div className="flex gap-2">
                  <button type="button" onClick={() => setPending(null)} className={quietButton}>Discard</button>
                  <button type="button" onClick={() => void applyPending()} disabled={applying} className={primaryButton}>
                    {applying ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
                    Apply approved preview
                  </button>
                </div>
              </div>
            </div>
          )}
          <footer className="flex gap-4 border-t border-surface-border px-3 py-1 text-[10px] text-gray-600">
            <span>{document.elements.length} XML elements</span>
            <span>{workspace.contextArtifacts.length} connected artifacts</span>
            <span>{workspace.contextRelationships.length} impact links</span>
            <span className="ml-auto">Revision {document.revisionFingerprint.slice(0, 12)}</span>
          </footer>
        </section>

        <aside className="flex w-72 shrink-0 flex-col overflow-hidden border-l border-surface-border bg-surface-light/40">
          <PanelTitle icon={Tag} title="Exact XML properties" />
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
                          onClick={() => void previewStructure({ operation: 'MOVE_UP', elementKey: selected.key })}
                          className={quietButton}
                          title="Move component up"
                        >
                          <ArrowUp size={10} />
                        </button>
                        <button
                          type="button"
                          onClick={() => void previewStructure({ operation: 'MOVE_DOWN', elementKey: selected.key })}
                          className={quietButton}
                          title="Move component down"
                        >
                          <ArrowDown size={10} />
                        </button>
                        {selected.parentKey !== document.rootKey && (
                          <button
                            type="button"
                            onClick={() => void previewStructure({ operation: 'DELETE', elementKey: selected.key })}
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
                        Preview
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
                {selectedInLayout && selected.id && workspace.controllerModel?.psiSupported && (
                  <div className="border-t border-surface-border pt-2">
                    <div className="text-[9px] uppercase tracking-wider text-gray-600">Java controller</div>
                    {workspace.controllerModel.injections.some((injection) => injection.componentId === selected.id) ? (
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
                      onClick={() => void previewStructure({
                        operation: 'INSERT_CHILD',
                        parentKey: selected.key,
                        tagName: newTag,
                        attributes: newComponentId ? { id: newComponentId } : {},
                      })}
                      className={`${quietButton} mt-1.5`}
                    >
                      <Plus size={10} /> Preview insertion
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
                </div>
              )}
              {workspace.controllerModel?.injections.map((injection) => (
                <button
                  type="button"
                  key={`injection-${injection.fieldName}`}
                  onClick={() => void bridge.navigateToSource(injection.sourceLocator)}
                  className="w-full rounded border border-surface-border bg-surface px-2 py-1.5 text-left hover:border-jmix-500/50"
                >
                  <div className="truncate font-mono text-[10px] text-gray-300">
                    @ViewComponent {injection.fieldName}
                  </div>
                  <div className="mt-0.5 truncate text-[9px] text-gray-600">
                    {injection.componentId} · {injection.type}
                  </div>
                </button>
              ))}
              {workspace.controllerModel?.handlers.map((handler) => (
                <button
                  type="button"
                  key={`handler-${handler.kind}-${handler.methodName}-${handler.target ?? ''}`}
                  onClick={() => void bridge.navigateToSource(handler.sourceLocator)}
                  className="w-full rounded border border-surface-border bg-surface px-2 py-1.5 text-left hover:border-jmix-500/50"
                >
                  <div className="truncate font-mono text-[10px] text-gray-300">
                    @{handler.kind} {handler.methodName}()
                  </div>
                  <div className="mt-0.5 truncate text-[9px] text-gray-600">
                    {[handler.target, handler.subject, ...handler.parameterTypes].filter(Boolean).join(' · ') || 'view lifecycle'}
                  </div>
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
