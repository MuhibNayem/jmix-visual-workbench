import {
  AlertTriangle,
  ArrowRight,
  Braces,
  Check,
  ChevronDown,
  CircleDot,
  Code2,
  Database,
  FileCode2,
  GitBranch,
  History,
  Loader2,
  LockKeyhole,
  Maximize2,
  MessageSquareText,
  MousePointer2,
  Play,
  Plus,
  Redo2,
  Repeat2,
  RefreshCw,
  Save,
  Search,
  ShieldCheck,
  Trash2,
  Undo2,
  Variable,
  Workflow,
  X,
  Zap,
  ZoomIn,
  ZoomOut,
} from 'lucide-react'
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type DragEvent,
  type PointerEvent as ReactPointerEvent,
  type ReactNode,
} from 'react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  GraphArtifact,
  LogicConditionOperator,
  LogicEntityOperation,
  LogicNamedValueModel,
  LogicNodeKind,
  LogicNodeModel,
  LogicTransitionBranch,
  LogicTransitionModel,
  LogicValueModel,
  LogicValueSource,
  LogicValueType,
  VisualLogicClassModel,
  VisualLogicMethodModel,
  VisualLogicWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'

const NODE_WIDTH = 184
const NODE_HEIGHT = 72
const CANVAS_WIDTH = 1400
const CANVAS_HEIGHT = 900
const HISTORY_LIMIT = 100

type CatalogEntry = {
  kind: LogicNodeKind
  label: string
  description: string
  group: string
  icon: typeof CircleDot
  accent: string
}

const catalog: CatalogEntry[] = [
  { kind: 'START', label: 'Start', description: 'Method entry point', group: 'Flow', icon: CircleDot, accent: 'text-emerald-300' },
  { kind: 'RETURN', label: 'Return', description: 'Return a typed value', group: 'Flow', icon: ArrowRight, accent: 'text-rose-300' },
  { kind: 'CONDITION', label: 'Decision', description: 'True / false branch', group: 'Flow', icon: GitBranch, accent: 'text-amber-300' },
  { kind: 'FOR_EACH', label: 'For each', description: 'Iterate a typed collection', group: 'Flow', icon: Repeat2, accent: 'text-teal-300' },
  { kind: 'CALL_SUBFLOW', label: 'Call subflow', description: 'Reuse an internal visual method', group: 'Flow', icon: Workflow, accent: 'text-indigo-300' },
  { kind: 'TRY_CATCH', label: 'Try / catch', description: 'Guard subflows and always clean up', group: 'Flow', icon: ShieldCheck, accent: 'text-violet-300' },
  { kind: 'REQUIRE', label: 'Require', description: 'Enforce a business rule', group: 'Flow', icon: ShieldCheck, accent: 'text-orange-300' },
  { kind: 'THROW', label: 'Throw error', description: 'Stop with an exception', group: 'Flow', icon: AlertTriangle, accent: 'text-red-300' },
  { kind: 'CONSTANT', label: 'Set value', description: 'Create a typed variable', group: 'Data', icon: Variable, accent: 'text-sky-300' },
  { kind: 'CREATE_ENTITY', label: 'Create entity', description: 'Instantiate and populate', group: 'Jmix data', icon: Database, accent: 'text-cyan-300' },
  { kind: 'LOAD_ENTITY_BY_ID', label: 'Load by ID', description: 'Constrained DataManager load', group: 'Jmix data', icon: Search, accent: 'text-cyan-300' },
  { kind: 'LOAD_ENTITIES', label: 'Load list', description: 'Bounded JPQL query', group: 'Jmix data', icon: Database, accent: 'text-cyan-200' },
  { kind: 'SET_PROPERTY', label: 'Set property', description: 'Update an entity attribute', group: 'Jmix data', icon: Braces, accent: 'text-blue-300' },
  { kind: 'SAVE_ENTITY', label: 'Save entity', description: 'Persist and retain result', group: 'Jmix data', icon: Save, accent: 'text-emerald-300' },
  { kind: 'REMOVE_ENTITY', label: 'Remove entity', description: 'Delete through DataManager', group: 'Jmix data', icon: Trash2, accent: 'text-red-300' },
  { kind: 'AUTHORIZE_ENTITY', label: 'Authorize entity', description: 'Explicit CRUD permission gate', group: 'Security', icon: LockKeyhole, accent: 'text-violet-300' },
  { kind: 'CALL_SERVICE', label: 'Call service', description: 'Invoke an indexed Spring bean', group: 'Integration', icon: Zap, accent: 'text-fuchsia-300' },
  { kind: 'LOG', label: 'Audit log', description: 'Structured server-side message', group: 'Observability', icon: MessageSquareText, accent: 'text-gray-300' },
]

const conditionOperators: LogicConditionOperator[] = [
  'EQUALS', 'NOT_EQUALS', 'NULL', 'NOT_NULL', 'TRUE', 'FALSE',
  'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN',
  'LESS_THAN_OR_EQUAL', 'CONTAINS',
]
const unaryOperators = new Set<LogicConditionOperator>(['NULL', 'NOT_NULL', 'TRUE', 'FALSE'])
const valueTypes: LogicValueType[] = [
  'STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'UUID', 'LOCAL_DATE',
  'LOCAL_DATE_TIME', 'OFFSET_DATE_TIME', 'INSTANT', 'ENUM', 'ENTITY', 'OBJECT',
]
const valueSources: LogicValueSource[] = ['LITERAL', 'PARAMETER', 'VARIABLE', 'NULL']
const entityOperations: LogicEntityOperation[] = ['CREATE', 'READ', 'UPDATE', 'DELETE']

function id(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
}

function value(source: LogicValueSource = 'LITERAL', type: LogicValueType = 'STRING'): LogicValueModel {
  return { source, type, value: source === 'NULL' ? undefined : '' }
}

function newNode(kind: LogicNodeKind, x: number, y: number): LogicNodeModel {
  const entry = catalog.find((item) => item.kind === kind)
  const base: LogicNodeModel = {
    id: id('node'),
    label: entry?.label ?? kind,
    kind,
    x,
    y,
    fieldValues: [],
    queryParameters: [],
    arguments: [],
    logLevel: 'INFO',
  }
  switch (kind) {
    case 'RETURN':
      return { ...base, value: value('NULL', 'OBJECT') }
    case 'CONSTANT':
      return { ...base, resultVariable: 'value', resultJavaType: 'String', value: value() }
    case 'CREATE_ENTITY':
      return { ...base, resultVariable: 'entity', resultJavaType: 'Object' }
    case 'LOAD_ENTITY_BY_ID':
      return { ...base, resultVariable: 'entity', resultJavaType: 'Object', value: value('PARAMETER', 'UUID') }
    case 'LOAD_ENTITIES':
      return { ...base, resultVariable: 'items', resultJavaType: 'java.util.List<Object>', jpql: 'select e from Entity e', maxResults: 100 }
    case 'SET_PROPERTY':
      return { ...base, targetVariable: 'entity', propertyPath: 'property', value: value() }
    case 'SAVE_ENTITY':
    case 'REMOVE_ENTITY':
      return { ...base, targetVariable: 'entity' }
    case 'CALL_SERVICE':
      return { ...base, beanFieldName: 'applicationService', methodName: 'execute' }
    case 'CALL_SUBFLOW':
      return { ...base, subflowMethod: '', resultJavaType: 'void' }
    case 'FOR_EACH':
      return {
        ...base,
        resultVariable: 'item',
        resultJavaType: 'java.lang.Object',
        indexVariable: 'itemIndex',
        value: value('VARIABLE', 'OBJECT'),
      }
    case 'TRY_CATCH':
      return {
        ...base,
        subflowMethod: '',
        catchMethod: '',
        finallyMethod: '',
        exceptionType: 'java.lang.RuntimeException',
        resultJavaType: 'void',
      }
    case 'CONDITION':
    case 'REQUIRE':
      return {
        ...base,
        condition: { left: value('VARIABLE', 'OBJECT'), operator: 'NOT_NULL' },
        message: kind === 'REQUIRE' ? 'Business rule was not satisfied' : undefined,
      }
    case 'AUTHORIZE_ENTITY':
      return { ...base, entityOperation: 'READ' }
    case 'THROW':
      return { ...base, resultJavaType: 'java.lang.IllegalStateException', message: 'Operation cannot continue' }
    case 'LOG':
      return { ...base, message: 'Operation completed' }
    default:
      return base
  }
}

function newMethod(kind: VisualLogicMethodModel['kind'] = 'ENTRY_POINT'): VisualLogicMethodModel {
  const start = newNode('START', 80, 210)
  const finish = newNode('RETURN', 390, 210)
  return {
    name: 'execute',
    description: 'Visually generated transactional business operation.',
    kind,
    returnJavaType: 'void',
    parameters: [],
    transaction: {
      enabled: kind === 'ENTRY_POINT',
      readOnly: false,
      propagation: 'REQUIRED',
      isolation: 'DEFAULT',
    },
    maximumExecutions: 10_000,
    nodes: [start, finish],
    transitions: [{
      id: id('edge'),
      sourceNodeId: start.id,
      targetNodeId: finish.id,
      branch: 'ALWAYS',
    }],
  }
}

function newModel(workspace?: VisualLogicWorkspaceResponse): VisualLogicClassModel {
  const destination = workspace?.destinations.find((item) => item.id === workspace.defaultDestinationId)
    ?? workspace?.destinations[0]
  return {
    name: 'Application operation',
    description: 'Typed, source-safe Jmix server logic.',
    destinationId: destination?.id ?? '',
    packageName: destination?.defaultPackage ?? 'com.example.app.service',
    className: 'ApplicationOperationService',
    beanName: 'applicationOperationService',
    methods: [newMethod()],
  }
}

function anchor(
  source: LogicNodeModel,
  target: LogicNodeModel,
  fromSource: boolean,
): { x: number; y: number } {
  const sx = source.x + NODE_WIDTH / 2
  const sy = source.y + NODE_HEIGHT / 2
  const tx = target.x + NODE_WIDTH / 2
  const ty = target.y + NODE_HEIGHT / 2
  const dx = tx - sx
  const dy = ty - sy
  const center = fromSource ? { x: sx, y: sy } : { x: tx, y: ty }
  const direction = fromSource ? { x: dx, y: dy } : { x: -dx, y: -dy }
  const halfW = NODE_WIDTH / 2
  const halfH = NODE_HEIGHT / 2
  const scale = Math.min(
    direction.x === 0 ? Number.POSITIVE_INFINITY : halfW / Math.abs(direction.x),
    direction.y === 0 ? Number.POSITIVE_INFINITY : halfH / Math.abs(direction.y),
  )
  return {
    x: center.x + direction.x * scale,
    y: center.y + direction.y * scale,
  }
}

function edgePath(source: LogicNodeModel, target: LogicNodeModel) {
  const a = anchor(source, target, true)
  const b = anchor(source, target, false)
  const horizontal = Math.abs(b.x - a.x) >= Math.abs(b.y - a.y)
  const bend = Math.max(48, (horizontal ? Math.abs(b.x - a.x) : Math.abs(b.y - a.y)) * 0.45)
  const c1 = horizontal
    ? { x: a.x + Math.sign(b.x - a.x || 1) * bend, y: a.y }
    : { x: a.x, y: a.y + Math.sign(b.y - a.y || 1) * bend }
  const c2 = horizontal
    ? { x: b.x - Math.sign(b.x - a.x || 1) * bend, y: b.y }
    : { x: b.x, y: b.y - Math.sign(b.y - a.y || 1) * bend }
  return {
    d: `M ${a.x} ${a.y} C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${b.x} ${b.y}`,
    source: a,
    target: b,
    labelX: (a.x + b.x) / 2,
    labelY: (a.y + b.y) / 2,
  }
}

function clone<T>(item: T): T {
  return JSON.parse(JSON.stringify(item)) as T
}

type LogicTraceStep = {
  nodeId: string
  label: string
  kind: LogicNodeKind
  route?: string
}

function traceMethod(
  method: VisualLogicMethodModel,
  branches: Record<string, 'TRUE' | 'FALSE'>,
  iterations: Record<string, number>,
): { steps: LogicTraceStep[]; stoppedByGuard: boolean } {
  const start = method.nodes.find((node) => node.kind === 'START')
  if (!start) return { steps: [], stoppedByGuard: false }
  const nodes = new Map(method.nodes.map((node) => [node.id, node]))
  const outgoing = new Map<string, LogicTransitionModel[]>()
  method.transitions.forEach((edge) => {
    const edges = outgoing.get(edge.sourceNodeId) ?? []
    edges.push(edge)
    outgoing.set(edge.sourceNodeId, edges)
  })
  const loopVisits: Record<string, number> = {}
  const steps: LogicTraceStep[] = []
  let current: LogicNodeModel | undefined = start
  const guard = Math.min(500, Math.max(1, method.maximumExecutions ?? 10_000))
  while (current && steps.length < guard) {
    let route: LogicTransitionBranch | undefined
    if (current.kind === 'CONDITION') {
      route = branches[current.id] ?? 'TRUE'
    } else if (current.kind === 'FOR_EACH') {
      const completed: number = loopVisits[current.id] ?? 0
      const configured: number = Math.max(0, Math.min(25, iterations[current.id] ?? 1))
      route = completed < configured ? 'ITEM' : 'DONE'
      if (route === 'ITEM') loopVisits[current.id] = completed + 1
    }
    const callTarget = current.kind === 'CALL_SUBFLOW'
      ? current.subflowMethod
      : current.kind === 'TRY_CATCH'
        ? `try ${current.subflowMethod ?? 'unconfigured'}`
        : undefined
    steps.push({
      nodeId: current.id,
      label: current.label,
      kind: current.kind,
      route: callTarget ?? route,
    })
    if (current.kind === 'RETURN' || current.kind === 'THROW') break
    const edges: LogicTransitionModel[] = outgoing.get(current.id) ?? []
    const nextEdge: LogicTransitionModel | undefined = route
      ? edges.find((edge) => edge.branch === route)
      : edges.find((edge) => edge.branch === 'ALWAYS') ?? edges[0]
    current = nextEdge ? nodes.get(nextEdge.targetNodeId) : undefined
  }
  return {
    steps,
    stoppedByGuard: Boolean(current && steps.length >= guard),
  }
}

function inputClass(extra = '') {
  return `w-full min-w-0 rounded border border-surface-border bg-surface px-2 py-1.5 text-xs text-gray-100 outline-none transition focus:border-jmix-500 ${extra}`
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 flex items-center justify-between text-[10px] font-semibold uppercase tracking-wide text-gray-500">
        {label}
      </span>
      {children}
      {hint && <span className="mt-1 block text-[10px] leading-4 text-gray-600">{hint}</span>}
    </label>
  )
}

function ValueEditor({
  value: current,
  onChange,
  compact = false,
}: {
  value: LogicValueModel
  onChange: (next: LogicValueModel) => void
  compact?: boolean
}) {
  return (
    <div className={`grid gap-2 ${compact ? 'grid-cols-2' : 'grid-cols-2'}`}>
      <select
        className={inputClass()}
        value={current.source}
        onChange={(event) => onChange({
          ...current,
          source: event.target.value as LogicValueSource,
          value: event.target.value === 'NULL' ? undefined : current.value ?? '',
        })}
      >
        {valueSources.map((source) => <option key={source}>{source}</option>)}
      </select>
      <select
        className={inputClass()}
        value={current.type}
        onChange={(event) => onChange({ ...current, type: event.target.value as LogicValueType })}
      >
        {valueTypes.map((type) => <option key={type}>{type}</option>)}
      </select>
      {current.source !== 'NULL' && (
        <input
          className={inputClass('col-span-2')}
          value={current.value ?? ''}
          placeholder={
            current.source === 'VARIABLE' ? 'variable name'
              : current.source === 'PARAMETER' ? 'method parameter'
                : 'literal value'
          }
          onChange={(event) => onChange({ ...current, value: event.target.value })}
        />
      )}
      {(current.type === 'ENUM' || current.type === 'ENTITY' || current.type === 'OBJECT') && (
        <input
          className={inputClass('col-span-2')}
          value={current.javaType ?? ''}
          placeholder="Qualified Java type"
          onChange={(event) => onChange({ ...current, javaType: event.target.value })}
        />
      )}
    </div>
  )
}

export default function LogicDesigner() {
  const addToast = useStore((state) => state.addToast)
  const [workspace, setWorkspace] = useState<VisualLogicWorkspaceResponse>()
  const [model, setModel] = useState<VisualLogicClassModel>(() => newModel())
  const [methodIndex, setMethodIndex] = useState(0)
  const [selectedNodeId, setSelectedNodeId] = useState<string>()
  const [selectedEdgeId, setSelectedEdgeId] = useState<string>()
  const [connectingFrom, setConnectingFrom] = useState<string>()
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse>()
  const [history, setHistory] = useState<VisualLogicClassModel[]>([])
  const [future, setFuture] = useState<VisualLogicClassModel[]>([])
  const [drag, setDrag] = useState<{ nodeId: string; offsetX: number; offsetY: number }>()
  const [canvasZoom, setCanvasZoom] = useState(1)
  const [traceOpen, setTraceOpen] = useState(false)
  const [traceBranches, setTraceBranches] = useState<Record<string, 'TRUE' | 'FALSE'>>({})
  const [traceIterations, setTraceIterations] = useState<Record<string, number>>({})
  const canvasRef = useRef<HTMLDivElement>(null)

  const method = model.methods[methodIndex] ?? model.methods[0]
  const selectedNode = method?.nodes.find((node) => node.id === selectedNodeId)
  const selectedEdge = method?.transitions.find((edge) => edge.id === selectedEdgeId)
  const trace = useMemo(
    () => method ? traceMethod(method, traceBranches, traceIterations) : { steps: [], stoppedByGuard: false },
    [method, traceBranches, traceIterations],
  )
  const tracedNodeIds = useMemo(() => new Set(trace.steps.map((step) => step.nodeId)), [trace.steps])
  const entities = useMemo(
    () => workspace?.contextArtifacts.filter((artifact) => ['ENTITY', 'DTO'].includes(artifact.kind)) ?? [],
    [workspace],
  )
  const services = useMemo(
    () => workspace?.contextArtifacts.filter((artifact) =>
      ['SERVICE', 'REPOSITORY', 'VALIDATOR', 'SOURCE_TYPE'].includes(artifact.kind),
    ) ?? [],
    [workspace],
  )
  const groupedCatalog = useMemo(() => {
    const query = search.trim().toLowerCase()
    const visible = query
      ? catalog.filter((item) => `${item.label} ${item.description} ${item.group}`.toLowerCase().includes(query))
      : catalog
    return visible.reduce<Record<string, CatalogEntry[]>>((groups, item) => {
      ;(groups[item.group] ??= []).push(item)
      return groups
    }, {})
  }, [search])

  const load = useCallback(async (forceRefresh = false) => {
    setLoading(true)
    try {
      const loaded = await bridge.getVisualLogicWorkspace(forceRefresh)
      setWorkspace(loaded)
      setModel((current) => current.destinationId ? current : newModel(loaded))
      if (loaded.issues.length) {
        addToast(loaded.issues[0].message, 'info')
      }
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Could not load server logic workspace.', 'error')
    } finally {
      setLoading(false)
    }
  }, [addToast])

  useEffect(() => {
    void load()
  }, [load])

  const commit = useCallback((updater: (current: VisualLogicClassModel) => VisualLogicClassModel) => {
    setModel((current) => {
      const next = updater(current)
      if (JSON.stringify(next) === JSON.stringify(current)) return current
      setHistory((items) => [...items.slice(-(HISTORY_LIMIT - 1)), clone(current)])
      setFuture([])
      setPreview(undefined)
      return next
    })
  }, [])

  const updateModel = (patch: Partial<VisualLogicClassModel>) => commit((current) => ({ ...current, ...patch }))
  const updateMethod = (patch: Partial<VisualLogicMethodModel>) => commit((current) => ({
    ...current,
    methods: current.methods.map((item, index) => index === methodIndex ? { ...item, ...patch } : item),
  }))
  const updateNode = (nodeId: string, patch: Partial<LogicNodeModel>) => updateMethod({
    nodes: method.nodes.map((node) => node.id === nodeId ? { ...node, ...patch } : node),
  })
  const updateEdge = (edgeId: string, patch: Partial<LogicTransitionModel>) => updateMethod({
    transitions: method.transitions.map((edge) => edge.id === edgeId ? { ...edge, ...patch } : edge),
  })

  const undo = () => {
    const previous = history[history.length - 1]
    if (!previous) return
    setHistory((items) => items.slice(0, -1))
    setFuture((items) => [clone(model), ...items].slice(0, HISTORY_LIMIT))
    setModel(previous)
    setPreview(undefined)
  }
  const redo = () => {
    const next = future[0]
    if (!next) return
    setFuture((items) => items.slice(1))
    setHistory((items) => [...items, clone(model)].slice(-HISTORY_LIMIT))
    setModel(next)
    setPreview(undefined)
  }

  const addNode = (kind: LogicNodeKind, x = 180, y = 180) => {
    const created = newNode(kind, Math.max(8, x), Math.max(8, y))
    updateMethod({ nodes: [...method.nodes, created] })
    setSelectedNodeId(created.id)
    setSelectedEdgeId(undefined)
  }

  const removeSelection = () => {
    if (selectedEdgeId) {
      updateMethod({ transitions: method.transitions.filter((edge) => edge.id !== selectedEdgeId) })
      setSelectedEdgeId(undefined)
      return
    }
    if (!selectedNodeId || method.nodes.find((node) => node.id === selectedNodeId)?.kind === 'START') return
    updateMethod({
      nodes: method.nodes.filter((node) => node.id !== selectedNodeId),
      transitions: method.transitions.filter(
        (edge) => edge.sourceNodeId !== selectedNodeId && edge.targetNodeId !== selectedNodeId,
      ),
    })
    setSelectedNodeId(undefined)
  }

  const connect = (targetId: string) => {
    if (!connectingFrom || connectingFrom === targetId) {
      setConnectingFrom(undefined)
      return
    }
    const source = method.nodes.find((node) => node.id === connectingFrom)
    const existing = method.transitions.some(
      (edge) => edge.sourceNodeId === connectingFrom && edge.targetNodeId === targetId,
    )
    if (!source || existing) {
      setConnectingFrom(undefined)
      return
    }
    let branch: LogicTransitionBranch = 'ALWAYS'
    if (source.kind === 'CONDITION') {
      branch = method.transitions.some((edge) => edge.sourceNodeId === source.id && edge.branch === 'TRUE')
        ? 'FALSE'
        : 'TRUE'
    } else if (source.kind === 'FOR_EACH') {
      branch = method.transitions.some((edge) => edge.sourceNodeId === source.id && edge.branch === 'ITEM')
        ? 'DONE'
        : 'ITEM'
    }
    const created: LogicTransitionModel = {
      id: id('edge'),
      sourceNodeId: connectingFrom,
      targetNodeId: targetId,
      branch,
    }
    updateMethod({ transitions: [...method.transitions, created] })
    setSelectedEdgeId(created.id)
    setSelectedNodeId(undefined)
    setConnectingFrom(undefined)
  }

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    const kind = event.dataTransfer.getData('application/jvw-logic-node') as LogicNodeKind
    if (!catalog.some((item) => item.kind === kind) || !canvasRef.current) return
    const rect = canvasRef.current.getBoundingClientRect()
    addNode(
      kind,
      (event.clientX - rect.left + canvasRef.current.scrollLeft) / canvasZoom - NODE_WIDTH / 2,
      (event.clientY - rect.top + canvasRef.current.scrollTop) / canvasZoom - NODE_HEIGHT / 2,
    )
  }

  const beginNodeDrag = (event: ReactPointerEvent, node: LogicNodeModel) => {
    if ((event.target as HTMLElement).closest('[data-port]')) return
    const rect = canvasRef.current?.getBoundingClientRect()
    if (!rect) return
    event.currentTarget.setPointerCapture(event.pointerId)
    setDrag({
      nodeId: node.id,
      offsetX: (event.clientX - rect.left + (canvasRef.current?.scrollLeft ?? 0)) / canvasZoom - node.x,
      offsetY: (event.clientY - rect.top + (canvasRef.current?.scrollTop ?? 0)) / canvasZoom - node.y,
    })
  }

  const moveNode = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!drag || !canvasRef.current) return
    const rect = canvasRef.current.getBoundingClientRect()
    updateNode(drag.nodeId, {
      x: Math.max(0, Math.min(CANVAS_WIDTH - NODE_WIDTH, (event.clientX - rect.left + canvasRef.current.scrollLeft) / canvasZoom - drag.offsetX)),
      y: Math.max(0, Math.min(CANVAS_HEIGHT - NODE_HEIGHT, (event.clientY - rect.top + canvasRef.current.scrollTop) / canvasZoom - drag.offsetY)),
    })
  }

  const setZoom = (next: number) => {
    const canvas = canvasRef.current
    const bounded = Math.max(0.5, Math.min(1.5, next))
    if (!canvas) {
      setCanvasZoom(bounded)
      return
    }
    const centerX = (canvas.scrollLeft + canvas.clientWidth / 2) / canvasZoom
    const centerY = (canvas.scrollTop + canvas.clientHeight / 2) / canvasZoom
    setCanvasZoom(bounded)
    requestAnimationFrame(() => {
      canvas.scrollLeft = Math.max(0, centerX * bounded - canvas.clientWidth / 2)
      canvas.scrollTop = Math.max(0, centerY * bounded - canvas.clientHeight / 2)
    })
  }

  const fitCanvas = () => {
    const canvas = canvasRef.current
    if (!canvas || !method.nodes.length) return
    const left = Math.min(...method.nodes.map((node) => node.x))
    const top = Math.min(...method.nodes.map((node) => node.y))
    const right = Math.max(...method.nodes.map((node) => node.x + NODE_WIDTH))
    const bottom = Math.max(...method.nodes.map((node) => node.y + NODE_HEIGHT))
    const padding = 48
    const next = Math.max(
      0.5,
      Math.min(
        1.25,
        (canvas.clientWidth - padding * 2) / Math.max(1, right - left),
        (canvas.clientHeight - padding * 2) / Math.max(1, bottom - top),
      ),
    )
    setCanvasZoom(next)
    requestAnimationFrame(() => {
      canvas.scrollLeft = Math.max(0, left * next - padding)
      canvas.scrollTop = Math.max(0, top * next - padding)
    })
  }

  const previewSource = async () => {
    setPreviewing(true)
    try {
      const result = await bridge.previewVisualLogic(model)
      setPreview(result)
      if (!result.accepted) addToast(result.issues[0]?.message ?? 'The model is not ready to generate.', 'error')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Preview failed.', 'error')
    } finally {
      setPreviewing(false)
    }
  }

  const applySource = async () => {
    if (!preview?.accepted || !preview.planDigest) return
    setApplying(true)
    try {
      const result = await bridge.applyVisualLogic(model, preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'Generation was rejected.', 'error')
        return
      }
      addToast(`Generated ${result.filesChanged.join(', ')}`, 'success')
      setPreview(undefined)
      await load(true)
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Generation failed.', 'error')
    } finally {
      setApplying(false)
    }
  }

  const openDocument = (path: string) => {
    const document = workspace?.existingDocuments.find((item) => item.locator.relativePath === path)
    if (!document) return
    setModel(clone(document.model))
    setMethodIndex(0)
    setSelectedNodeId(undefined)
    setSelectedEdgeId(undefined)
    setHistory([])
    setFuture([])
    setPreview(undefined)
    if (!document.editable) addToast(document.issue ?? 'Manual changes make this service read-only.', 'info')
  }

  const addMethod = () => {
    const created = newMethod('SUBFLOW')
    created.name = `subflow${model.methods.length + 1}`
    created.description = 'Reusable visual subflow executed inside its caller transaction.'
    commit((current) => ({ ...current, methods: [...current.methods, created] }))
    setMethodIndex(model.methods.length)
    setSelectedNodeId(undefined)
    setSelectedEdgeId(undefined)
  }

  if (loading && !workspace) {
    return (
      <div className="flex h-full items-center justify-center gap-3 text-sm text-gray-400">
        <Loader2 className="h-5 w-5 animate-spin text-jmix-400" />
        Mapping entities, services, security, and source destinations…
      </div>
    )
  }

  return (
    <section className="logic-designer-shell flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <header className="flex min-w-0 flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light px-3 py-2">
        <div className="mr-auto min-w-[220px]">
          <div className="flex items-center gap-2">
            <Code2 className="h-4 w-4 text-jmix-400" />
            <h2 className="truncate text-sm font-semibold text-gray-100">Typed Server Logic</h2>
            <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase text-emerald-300">
              Java 17+
            </span>
          </div>
          <p className="mt-0.5 truncate text-[10px] text-gray-500">
            Constrained Jmix data access · explicit authorization · transactional source-safe generation
          </p>
        </div>
        <select
          className={inputClass('max-w-52')}
          aria-label="Open visual service"
          value={model.sourceLocator?.relativePath ?? ''}
          onChange={(event) => event.target.value
            ? openDocument(event.target.value)
            : setModel(newModel(workspace))}
        >
          <option value="">New visual service</option>
          {workspace?.existingDocuments.map((document) => (
            <option key={document.locator.relativePath} value={document.locator.relativePath}>
              {document.editable ? '●' : '◐'} {document.model.name}
            </option>
          ))}
        </select>
        <button className="btn-secondary" onClick={undo} disabled={!history.length} title="Undo visual edit">
          <Undo2 className="h-3.5 w-3.5" />
        </button>
        <button className="btn-secondary" onClick={redo} disabled={!future.length} title="Redo visual edit">
          <Redo2 className="h-3.5 w-3.5" />
        </button>
        <button className="btn-secondary flex items-center gap-1.5" onClick={() => void load(true)} disabled={loading}>
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span className="hidden min-[1100px]:inline">Refresh index</span>
        </button>
        <button className="btn-primary flex items-center gap-1.5" onClick={() => void previewSource()} disabled={previewing}>
          {previewing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <FileCode2 className="h-3.5 w-3.5" />}
          Preview Java
        </button>
      </header>

      <div className="grid min-w-0 grid-cols-1 gap-px border-b border-surface-border bg-surface-border px-px sm:grid-cols-2 xl:grid-cols-6">
        <Field label="Service name">
          <input className={inputClass()} value={model.name} onChange={(event) => updateModel({ name: event.target.value })} />
        </Field>
        <Field label="Module destination">
          <select
            className={inputClass()}
            value={model.destinationId}
            disabled={Boolean(model.sourceLocator)}
            onChange={(event) => {
              const destination = workspace?.destinations.find((item) => item.id === event.target.value)
              updateModel({
                destinationId: event.target.value,
                packageName: destination?.defaultPackage ?? model.packageName,
              })
            }}
          >
            {workspace?.destinations.map((destination) => (
              <option key={destination.id} value={destination.id}>
                {destination.moduleId} · {destination.sourceRoot}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Package">
          <input className={inputClass()} value={model.packageName} disabled={Boolean(model.sourceLocator)} onChange={(event) => updateModel({ packageName: event.target.value })} />
        </Field>
        <Field label="Java class">
          <input className={inputClass()} value={model.className} disabled={Boolean(model.sourceLocator)} onChange={(event) => updateModel({ className: event.target.value })} />
        </Field>
        <Field label="Spring bean">
          <input className={inputClass()} value={model.beanName} onChange={(event) => updateModel({ beanName: event.target.value })} />
        </Field>
        <Field label="Method">
          <div className="flex gap-1">
            <select className={inputClass()} value={methodIndex} onChange={(event) => setMethodIndex(Number(event.target.value))}>
              {model.methods.map((item, index) => (
                <option key={`${item.name}-${index}`} value={index}>
                  {item.kind === 'SUBFLOW' ? '↳ ' : '◆ '}{item.name}
                </option>
              ))}
            </select>
            <button className="btn-secondary px-2" onClick={addMethod} title="Add method"><Plus className="h-3.5 w-3.5" /></button>
          </div>
        </Field>
      </div>

      <div className="logic-designer-workspace grid min-h-0 min-w-0 flex-1">
        <aside className="logic-designer-palette flex min-h-0 min-w-0 flex-col border-r border-surface-border bg-surface-light/55">
          <div className="border-b border-surface-border p-3">
            <div className="mb-2 flex items-center gap-2 text-xs font-semibold text-gray-200">
              <Braces className="h-3.5 w-3.5 text-jmix-400" />
              Building blocks
            </div>
            <div className="relative">
              <Search className="absolute left-2 top-2 h-3.5 w-3.5 text-gray-600" />
              <input
                className={inputClass('pl-7')}
                placeholder="Find an operation…"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-2">
            {Object.entries(groupedCatalog).map(([group, entries]) => (
              <div key={group} className="mb-4">
                <h3 className="mb-1.5 px-1 text-[9px] font-bold uppercase tracking-[0.16em] text-gray-600">{group}</h3>
                <div className="space-y-1">
                  {entries.map((item) => {
                    const Icon = item.icon
                    return (
                      <button
                        key={item.kind}
                        draggable
                        onDragStart={(event) => {
                          event.dataTransfer.effectAllowed = 'copy'
                          event.dataTransfer.setData('application/jvw-logic-node', item.kind)
                        }}
                        onDoubleClick={() => addNode(item.kind)}
                        className="group flex w-full cursor-grab items-start gap-2 rounded border border-transparent px-2 py-2 text-left hover:border-surface-border hover:bg-surface-lighter active:cursor-grabbing"
                      >
                        <Icon className={`mt-0.5 h-4 w-4 shrink-0 ${item.accent}`} />
                        <span className="min-w-0">
                          <span className="block text-[11px] font-medium text-gray-200">{item.label}</span>
                          <span className="block text-[9px] leading-4 text-gray-600">{item.description}</span>
                        </span>
                      </button>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
          <div className="border-t border-surface-border p-3 text-[10px] leading-4 text-gray-600">
            Drag a block onto the canvas. Double-click adds it to the visible area.
          </div>
        </aside>

        <main className="logic-designer-canvas flex min-h-0 min-w-0 flex-col bg-surface">
          <div className="flex min-w-0 flex-wrap items-center gap-2 border-b border-surface-border px-3 py-2">
            <MousePointer2 className="h-3.5 w-3.5 text-gray-500" />
            <span className="text-[10px] text-gray-500">
              Drag nodes · click a node’s output dot, then a target input · click a line to edit its branch
            </span>
            {connectingFrom && (
              <button className="flex items-center gap-1 rounded bg-jmix-500/15 px-2 py-1 text-[10px] text-jmix-300" onClick={() => setConnectingFrom(undefined)}>
                Connecting… <X className="h-3 w-3" />
              </button>
            )}
            <div className="ml-auto flex items-center gap-1 rounded border border-surface-border bg-surface-light p-0.5">
              <button
                className="flex items-center gap-1 rounded px-1.5 py-1 text-[9px] text-emerald-300 hover:bg-emerald-500/10"
                onClick={() => setTraceOpen(true)}
                title="Dry-run the graph without executing side effects"
              >
                <Play className="h-3 w-3" />
                Trace
              </button>
              <button
                className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:opacity-30"
                disabled={canvasZoom <= 0.5}
                onClick={() => setZoom(canvasZoom - 0.1)}
                title="Zoom out"
              >
                <ZoomOut className="h-3.5 w-3.5" />
              </button>
              <button
                className="min-w-11 rounded px-1 py-0.5 text-[9px] tabular-nums text-gray-400 hover:bg-surface-lighter hover:text-gray-200"
                onClick={() => setZoom(1)}
                title="Reset zoom"
              >
                {Math.round(canvasZoom * 100)}%
              </button>
              <button
                className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:opacity-30"
                disabled={canvasZoom >= 1.5}
                onClick={() => setZoom(canvasZoom + 0.1)}
                title="Zoom in"
              >
                <ZoomIn className="h-3.5 w-3.5" />
              </button>
              <button
                className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200"
                onClick={fitCanvas}
                title="Fit diagram"
              >
                <Maximize2 className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
          <div
            ref={canvasRef}
            className="relative min-h-0 flex-1 overflow-auto bg-[radial-gradient(circle_at_1px_1px,rgba(148,163,184,0.11)_1px,transparent_0)] bg-[size:20px_20px]"
            onDragOver={(event) => {
              event.preventDefault()
              event.dataTransfer.dropEffect = 'copy'
            }}
            onDrop={onDrop}
            onPointerMove={moveNode}
            onPointerUp={() => setDrag(undefined)}
            onPointerCancel={() => setDrag(undefined)}
            onClick={(event) => {
              if (event.target === event.currentTarget) {
                setSelectedNodeId(undefined)
                setSelectedEdgeId(undefined)
              }
            }}
          >
            <div
              className="relative"
              style={{ width: CANVAS_WIDTH * canvasZoom, height: CANVAS_HEIGHT * canvasZoom }}
            >
              <div
                className="absolute left-0 top-0"
                style={{
                  width: CANVAS_WIDTH,
                  height: CANVAS_HEIGHT,
                  transform: `scale(${canvasZoom})`,
                  transformOrigin: 'top left',
                }}
              >
              <svg className="pointer-events-none absolute inset-0 h-full w-full overflow-visible">
                <defs>
                  <marker id="logic-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" className="fill-gray-500" />
                  </marker>
                  <marker id="logic-arrow-selected" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                    <path d="M 0 0 L 10 5 L 0 10 z" className="fill-jmix-400" />
                  </marker>
                </defs>
                {method.transitions.map((edge) => {
                  const source = method.nodes.find((node) => node.id === edge.sourceNodeId)
                  const target = method.nodes.find((node) => node.id === edge.targetNodeId)
                  if (!source || !target) return null
                  const path = edgePath(source, target)
                  const selected = selectedEdgeId === edge.id
                  return (
                    <g key={edge.id}>
                      <path
                        d={path.d}
                        fill="none"
                        stroke="transparent"
                        strokeWidth={18}
                        className="pointer-events-auto cursor-pointer"
                        onClick={(event) => {
                          event.stopPropagation()
                          setSelectedEdgeId(edge.id)
                          setSelectedNodeId(undefined)
                        }}
                      />
                      <path
                        d={path.d}
                        fill="none"
                        strokeWidth={selected ? 2.5 : 1.6}
                        className={selected ? 'stroke-jmix-400' : 'stroke-gray-600'}
                        markerEnd={selected ? 'url(#logic-arrow-selected)' : 'url(#logic-arrow)'}
                      />
                      <circle
                        cx={path.source.x}
                        cy={path.source.y}
                        r={selected ? 4.5 : 3}
                        className={selected ? 'fill-jmix-300 stroke-surface' : 'fill-gray-500 stroke-surface'}
                        strokeWidth={2}
                      />
                      <circle
                        cx={path.target.x}
                        cy={path.target.y}
                        r={selected ? 4.5 : 3}
                        className={selected ? 'fill-jmix-300 stroke-surface' : 'fill-gray-500 stroke-surface'}
                        strokeWidth={2}
                      />
                      {edge.branch !== 'ALWAYS' && (
                        <g transform={`translate(${path.labelX},${path.labelY})`}>
                          <rect
                            x={-22}
                            y={-10}
                            width={44}
                            height={19}
                            rx={9}
                            className={
                              edge.branch === 'TRUE' || edge.branch === 'ITEM'
                                ? 'fill-emerald-950 stroke-emerald-700'
                                : edge.branch === 'DONE'
                                  ? 'fill-indigo-950 stroke-indigo-700'
                                  : 'fill-rose-950 stroke-rose-700'
                            }
                          />
                          <text
                            textAnchor="middle"
                            y={3}
                            className={
                              edge.branch === 'TRUE' || edge.branch === 'ITEM'
                                ? 'fill-emerald-300 text-[9px]'
                                : edge.branch === 'DONE'
                                  ? 'fill-indigo-300 text-[9px]'
                                  : 'fill-rose-300 text-[9px]'
                            }
                          >{edge.branch}</text>
                        </g>
                      )}
                    </g>
                  )
                })}
              </svg>
              {method.nodes.map((node) => {
                const entry = catalog.find((item) => item.kind === node.kind)
                const Icon = entry?.icon ?? CircleDot
                const selected = selectedNodeId === node.id
                const traced = traceOpen && tracedNodeIds.has(node.id)
                const connectionSource = method.nodes.find((candidate) => candidate.id === connectingFrom)
                const inputAnchor = connectionSource && connectionSource.id !== node.id
                  ? anchor(connectionSource, node, false)
                  : { x: node.x, y: node.y + NODE_HEIGHT / 2 }
                const outputAnchor = { x: node.x + NODE_WIDTH, y: node.y + NODE_HEIGHT / 2 }
                return (
                  <div key={node.id}>
                    {node.kind !== 'START' && (
                      <button
                        data-port="input"
                        title={connectingFrom ? `Connect to ${node.label}` : 'Input connection'}
                        aria-label={connectingFrom ? `Connect to ${node.label}` : `Input for ${node.label}`}
                        onClick={(event) => {
                          event.stopPropagation()
                          if (connectingFrom) connect(node.id)
                        }}
                        className={`absolute z-30 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 ${
                          connectingFrom ? 'border-jmix-300 bg-jmix-500 shadow-[0_0_0_4px_rgba(45,212,191,0.12)]' : 'border-gray-500 bg-surface-lighter'
                        }`}
                        style={{ left: inputAnchor.x, top: inputAnchor.y }}
                      />
                    )}
                    {node.kind !== 'RETURN' && node.kind !== 'THROW' && (
                      <button
                        data-port="output"
                        title="Start a connection"
                        aria-label={`Connect from ${node.label}`}
                        onClick={(event) => {
                          event.stopPropagation()
                          setConnectingFrom(node.id)
                          setSelectedNodeId(node.id)
                        }}
                        className={`absolute z-30 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 ${
                          connectingFrom === node.id ? 'border-jmix-200 bg-jmix-400 shadow-[0_0_0_4px_rgba(45,212,191,0.16)]' : 'border-gray-500 bg-surface-lighter hover:border-jmix-300'
                        }`}
                        style={{ left: outputAnchor.x, top: outputAnchor.y }}
                      />
                    )}
                    <button
                      onPointerDown={(event) => beginNodeDrag(event, node)}
                      onClick={(event) => {
                        event.stopPropagation()
                        if (connectingFrom && node.kind !== 'START') {
                          connect(node.id)
                        } else {
                          setSelectedNodeId(node.id)
                          setSelectedEdgeId(undefined)
                        }
                      }}
                      className={`absolute z-20 flex cursor-move items-center gap-3 rounded-lg border px-3 text-left shadow-lg transition ${
                        selected
                          ? 'border-jmix-400 bg-jmix-500/15 shadow-jmix-950/40'
                          : traced
                            ? 'border-emerald-400 bg-emerald-500/10 shadow-[0_0_0_3px_rgba(52,211,153,0.08)]'
                          : 'border-surface-border bg-surface-light hover:border-gray-500'
                      }`}
                      style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                    >
                      <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-md bg-surface ${entry?.accent ?? 'text-gray-300'}`}>
                        <Icon className="h-4 w-4" />
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-[11px] font-semibold text-gray-100">{node.label}</span>
                        <span className="mt-0.5 block truncate text-[9px] text-gray-500">{entry?.description ?? node.kind}</span>
                      </span>
                    </button>
                  </div>
                )
              })}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3 border-t border-surface-border px-3 py-1.5 text-[9px] text-gray-600">
            <span>{method.nodes.length} blocks</span>
            <span>{method.transitions.length} connections</span>
            <span>{method.parameters.length} parameters</span>
            <span className="ml-auto flex items-center gap-1 text-emerald-400"><Check className="h-3 w-3" /> constrained DataManager</span>
          </div>
        </main>

        <aside className="logic-designer-inspector flex min-h-0 min-w-0 flex-col border-l border-surface-border bg-surface-light/55">
          <div className="flex items-center gap-2 border-b border-surface-border px-3 py-2">
            <ChevronDown className="h-3.5 w-3.5 text-jmix-400" />
            <h3 className="text-xs font-semibold text-gray-200">
              {selectedNode ? 'Block properties' : selectedEdge ? 'Connection properties' : 'Method properties'}
            </h3>
            {(selectedNode || selectedEdge) && (
              <button className="ml-auto text-gray-500 hover:text-red-300 disabled:opacity-30" onClick={removeSelection} disabled={selectedNode?.kind === 'START'} title="Remove selection">
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            )}
          </div>
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3">
            {selectedNode ? (
              <NodeInspector
                node={selectedNode}
                entities={entities}
                services={services}
                methods={model.methods}
                onChange={(patch) => updateNode(selectedNode.id, patch)}
              />
            ) : selectedEdge ? (
              <>
                <Field label="Source">
                  <input className={inputClass()} value={method.nodes.find((node) => node.id === selectedEdge.sourceNodeId)?.label ?? ''} disabled />
                </Field>
                <Field label="Target">
                  <input className={inputClass()} value={method.nodes.find((node) => node.id === selectedEdge.targetNodeId)?.label ?? ''} disabled />
                </Field>
                <Field label="Branch" hint="Decisions use TRUE/FALSE. For-each loops use ITEM/DONE.">
                  <select className={inputClass()} value={selectedEdge.branch} onChange={(event) => updateEdge(selectedEdge.id, { branch: event.target.value as LogicTransitionBranch })}>
                    <option>ALWAYS</option><option>TRUE</option><option>FALSE</option><option>ITEM</option><option>DONE</option>
                  </select>
                </Field>
              </>
            ) : (
              <MethodInspector
                method={method}
                onChange={updateMethod}
                onRemove={() => {
                  if (model.methods.length === 1) return
                  commit((current) => ({ ...current, methods: current.methods.filter((_, index) => index !== methodIndex) }))
                  setMethodIndex(Math.max(0, methodIndex - 1))
                }}
                removable={model.methods.length > 1}
              />
            )}
          </div>
          <div className="border-t border-surface-border p-3">
            <div className="mb-2 flex items-center justify-between text-[10px] text-gray-500">
              <span className="flex items-center gap-1"><History className="h-3 w-3" /> Round-trip ownership</span>
              <span>{model.sourceLocator ? 'Existing source' : 'New source'}</span>
            </div>
            <button className="btn-secondary w-full" onClick={() => {
              setSelectedNodeId(undefined)
              setSelectedEdgeId(undefined)
            }}>
              Method & transaction settings
            </button>
          </div>
        </aside>
      </div>

      {preview && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/65 p-3" role="dialog" aria-modal="true" aria-label="Generated Java preview">
          <div className="flex max-h-[92vh] w-full max-w-5xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl">
            <div className="flex items-center gap-2 border-b border-surface-border px-4 py-3">
              <FileCode2 className="h-4 w-4 text-jmix-400" />
              <div className="min-w-0">
                <h3 className="truncate text-sm font-semibold text-gray-100">{preview.label}</h3>
                <p className="truncate text-[10px] text-gray-500">{preview.files[0]?.relativePath ?? 'Generation rejected'}</p>
              </div>
              <button className="ml-auto text-gray-500 hover:text-gray-200" onClick={() => setPreview(undefined)}><X className="h-4 w-4" /></button>
            </div>
            {preview.issues.length > 0 && (
              <div className="max-h-32 space-y-1 overflow-y-auto border-b border-surface-border bg-amber-950/20 px-4 py-2">
                {preview.issues.map((issue) => (
                  <div key={`${issue.code}-${issue.relativePath}`} className="flex gap-2 text-[10px] text-amber-200">
                    <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
                    <span><strong>{issue.code}</strong> — {issue.message}</span>
                  </div>
                ))}
              </div>
            )}
            <div className="min-h-0 flex-1 overflow-auto">
              {preview.files.length ? preview.files.map((file) => (
                <div key={file.relativePath}>
                  <div className="sticky top-0 border-b border-surface-border bg-surface-light px-4 py-2 text-[10px] font-medium text-gray-400">
                    {file.mode} · {file.relativePath}
                  </div>
                  <pre className="overflow-x-auto p-4 text-[11px] leading-5 text-gray-300">{file.resultContent}</pre>
                </div>
              )) : (
                <div className="p-8 text-center text-sm text-gray-500">Fix the reported model issues, then preview again.</div>
              )}
            </div>
            <div className="flex items-center justify-end gap-2 border-t border-surface-border px-4 py-3">
              <button className="btn-secondary" onClick={() => setPreview(undefined)}>Keep editing</button>
              <button className="btn-primary flex items-center gap-1.5" disabled={!preview.accepted || !preview.planDigest || applying} onClick={() => void applySource()}>
                {applying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
                Apply source-safe change
              </button>
            </div>
          </div>
        </div>
      )}

      {traceOpen && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/65 p-3" role="dialog" aria-modal="true" aria-label="Deterministic execution trace">
          <div className="flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl">
            <div className="flex items-center gap-2 border-b border-surface-border px-4 py-3">
              <Play className="h-4 w-4 text-emerald-400" />
              <div className="min-w-0">
                <h3 className="truncate text-sm font-semibold text-gray-100">Deterministic dry-run · {method.name}</h3>
                <p className="truncate text-[10px] text-gray-500">Inspects control flow only. No database, service, queue, or workflow side effect is executed.</p>
              </div>
              <button className="ml-auto text-gray-500 hover:text-gray-200" onClick={() => setTraceOpen(false)}><X className="h-4 w-4" /></button>
            </div>
            <div className="grid min-h-0 flex-1 grid-cols-1 overflow-y-auto md:grid-cols-[280px_minmax(0,1fr)] md:overflow-hidden">
              <div className="space-y-4 border-b border-surface-border p-4 md:overflow-y-auto md:border-b-0 md:border-r">
                <div>
                  <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400">Path inputs</h4>
                  <p className="mt-1 text-[10px] leading-4 text-gray-600">Choose decision outcomes and bounded collection size.</p>
                </div>
                {method.nodes.filter((node) => node.kind === 'CONDITION').map((node) => (
                  <Field key={node.id} label={node.label}>
                    <select
                      className={inputClass()}
                      value={traceBranches[node.id] ?? 'TRUE'}
                      onChange={(event) => setTraceBranches((current) => ({
                        ...current,
                        [node.id]: event.target.value as 'TRUE' | 'FALSE',
                      }))}
                    >
                      <option>TRUE</option>
                      <option>FALSE</option>
                    </select>
                  </Field>
                ))}
                {method.nodes.filter((node) => node.kind === 'FOR_EACH').map((node) => (
                  <Field key={node.id} label={`${node.label} iterations`} hint="Limited to 25 for an inspectable trace.">
                    <input
                      type="number"
                      min={0}
                      max={25}
                      className={inputClass()}
                      value={traceIterations[node.id] ?? 1}
                      onChange={(event) => setTraceIterations((current) => ({
                        ...current,
                        [node.id]: Number(event.target.value),
                      }))}
                    />
                  </Field>
                ))}
                {!method.nodes.some((node) => node.kind === 'CONDITION' || node.kind === 'FOR_EACH') && (
                  <div className="rounded border border-surface-border bg-surface-light p-3 text-[10px] leading-4 text-gray-500">
                    This method has one deterministic structural path.
                  </div>
                )}
              </div>
              <div className="min-h-0 space-y-2 p-4 md:overflow-y-auto">
                <div className="mb-3 flex items-center justify-between">
                  <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-400">Execution path</h4>
                  <span className="rounded bg-emerald-500/10 px-2 py-1 text-[9px] text-emerald-300">{trace.steps.length} steps</span>
                </div>
                {trace.steps.map((step, index) => (
                  <button
                    key={`${step.nodeId}-${index}`}
                    className="flex w-full items-center gap-3 rounded-lg border border-surface-border bg-surface-light/60 p-3 text-left hover:border-emerald-700"
                    onClick={() => {
                      setSelectedNodeId(step.nodeId)
                      setSelectedEdgeId(undefined)
                      setTraceOpen(false)
                    }}
                  >
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-emerald-500/10 text-[10px] font-bold text-emerald-300">{index + 1}</span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-[11px] font-semibold text-gray-200">{step.label}</span>
                      <span className="mt-0.5 block truncate text-[9px] text-gray-600">{step.kind.replace(/_/g, ' ')}</span>
                    </span>
                    {step.route && <span className="rounded bg-surface px-2 py-1 text-[9px] text-gray-400">{step.route}</span>}
                  </button>
                ))}
                {trace.stoppedByGuard && (
                  <div className="flex gap-2 rounded border border-amber-700/50 bg-amber-950/25 p-3 text-[10px] text-amber-200">
                    <AlertTriangle className="h-3.5 w-3.5 shrink-0" />
                    The trace reached this method’s execution guard.
                  </div>
                )}
              </div>
            </div>
            <div className="flex items-center justify-between gap-2 border-t border-surface-border px-4 py-3 text-[10px] text-gray-600">
              <span>Click a step to select its block on the canvas.</span>
              <button className="btn-secondary" onClick={() => setTraceOpen(false)}>Return to canvas</button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

function MethodInspector({
  method,
  onChange,
  onRemove,
  removable,
}: {
  method: VisualLogicMethodModel
  onChange: (patch: Partial<VisualLogicMethodModel>) => void
  onRemove: () => void
  removable: boolean
}) {
  return (
    <>
      <Field label="Method role" hint="Entry points are public Spring operations. Subflows are private and reusable inside this service.">
        <select
          className={inputClass()}
          value={method.kind ?? 'ENTRY_POINT'}
          onChange={(event) => {
            const kind = event.target.value as VisualLogicMethodModel['kind']
            onChange({
              kind,
              transaction: {
                ...method.transaction,
                enabled: kind === 'ENTRY_POINT',
              },
            })
          }}
        >
          <option value="ENTRY_POINT">Public entry point</option>
          <option value="SUBFLOW">Reusable subflow</option>
        </select>
      </Field>
      <Field label="Method name"><input className={inputClass()} value={method.name} onChange={(event) => onChange({ name: event.target.value })} /></Field>
      <Field label="Return Java type"><input className={inputClass()} value={method.returnJavaType} onChange={(event) => onChange({ returnJavaType: event.target.value })} /></Field>
      <Field label="Description"><textarea className={inputClass('min-h-16 resize-y')} value={method.description} onChange={(event) => onChange({ description: event.target.value })} /></Field>
      <div>
        <div className="mb-2 flex items-center justify-between">
          <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">Parameters</span>
          <button className="text-jmix-400 hover:text-jmix-300" onClick={() => onChange({
            parameters: [...method.parameters, { name: `arg${method.parameters.length + 1}`, javaType: 'String', nullable: false }],
          })}><Plus className="h-3.5 w-3.5" /></button>
        </div>
        <div className="space-y-2">
          {method.parameters.map((parameter, index) => (
            <div key={index} className="grid grid-cols-[1fr_1.3fr_auto] gap-1">
              <input className={inputClass()} value={parameter.name} onChange={(event) => onChange({
                parameters: method.parameters.map((item, itemIndex) => itemIndex === index ? { ...item, name: event.target.value } : item),
              })} />
              <input className={inputClass()} value={parameter.javaType} onChange={(event) => onChange({
                parameters: method.parameters.map((item, itemIndex) => itemIndex === index ? { ...item, javaType: event.target.value } : item),
              })} />
              <button className="px-1 text-gray-600 hover:text-red-300" onClick={() => onChange({ parameters: method.parameters.filter((_, itemIndex) => itemIndex !== index) })}><X className="h-3.5 w-3.5" /></button>
            </div>
          ))}
          {!method.parameters.length && <p className="text-[10px] text-gray-600">No method inputs yet.</p>}
        </div>
      </div>
      <div className="rounded-lg border border-surface-border bg-surface/45 p-3">
        {method.kind === 'SUBFLOW' ? (
          <p className="text-[10px] leading-4 text-indigo-200">
            This private subflow participates in its caller’s transaction. It cannot create a separate proxy transaction.
          </p>
        ) : (
          <>
        <label className="flex items-center justify-between text-xs text-gray-200">
          Transaction boundary
          <input type="checkbox" checked={method.transaction.enabled} onChange={(event) => onChange({ transaction: { ...method.transaction, enabled: event.target.checked } })} />
        </label>
        {method.transaction.enabled && (
          <div className="mt-3 space-y-3">
            <Field label="Propagation">
              <select className={inputClass()} value={method.transaction.propagation} onChange={(event) => onChange({ transaction: { ...method.transaction, propagation: event.target.value as typeof method.transaction.propagation } })}>
                {['REQUIRED', 'REQUIRES_NEW', 'SUPPORTS', 'MANDATORY', 'NOT_SUPPORTED', 'NEVER', 'NESTED'].map((item) => <option key={item}>{item}</option>)}
              </select>
            </Field>
            <Field label="Isolation">
              <select className={inputClass()} value={method.transaction.isolation} onChange={(event) => onChange({ transaction: { ...method.transaction, isolation: event.target.value as typeof method.transaction.isolation } })}>
                {['DEFAULT', 'READ_UNCOMMITTED', 'READ_COMMITTED', 'REPEATABLE_READ', 'SERIALIZABLE'].map((item) => <option key={item}>{item}</option>)}
              </select>
            </Field>
            <label className="flex items-center justify-between text-[11px] text-gray-400">
              Read only
              <input type="checkbox" checked={method.transaction.readOnly} onChange={(event) => onChange({ transaction: { ...method.transaction, readOnly: event.target.checked } })} />
            </label>
          </div>
        )}
          </>
        )}
      </div>
      <Field label="Execution guard" hint="Prevents accidental infinite cycles.">
        <input type="number" min={1} max={1_000_000} className={inputClass()} value={method.maximumExecutions} onChange={(event) => onChange({ maximumExecutions: Number(event.target.value) })} />
      </Field>
      <button className="btn-secondary w-full text-red-300" disabled={!removable} onClick={onRemove}>Remove method</button>
    </>
  )
}

function NodeInspector({
  node,
  entities,
  services,
  methods,
  onChange,
}: {
  node: LogicNodeModel
  entities: GraphArtifact[]
  services: GraphArtifact[]
  methods: VisualLogicMethodModel[]
  onChange: (patch: Partial<LogicNodeModel>) => void
}) {
  const entityKinds = new Set<LogicNodeKind>(['CREATE_ENTITY', 'LOAD_ENTITY_BY_ID', 'LOAD_ENTITIES', 'AUTHORIZE_ENTITY'])
  const variableKinds = new Set<LogicNodeKind>([
    'CONSTANT', 'CREATE_ENTITY', 'LOAD_ENTITY_BY_ID', 'LOAD_ENTITIES',
    'CALL_SERVICE', 'CALL_SUBFLOW', 'FOR_EACH', 'TRY_CATCH',
  ])
  const valueKinds = new Set<LogicNodeKind>([
    'CONSTANT', 'LOAD_ENTITY_BY_ID', 'SET_PROPERTY', 'RETURN', 'FOR_EACH',
  ])
  const subflows = methods.filter((method) => method.kind === 'SUBFLOW')
  return (
    <>
      <div className="flex items-center gap-2 rounded-lg border border-surface-border bg-surface/50 p-2">
        <span className="rounded bg-jmix-500/15 px-2 py-1 text-[9px] font-bold text-jmix-300">{node.kind.replace(/_/g, ' ')}</span>
        <span className="truncate text-[9px] text-gray-600">{node.id}</span>
      </div>
      <Field label="Label"><input className={inputClass()} value={node.label} onChange={(event) => onChange({ label: event.target.value })} /></Field>

      {entityKinds.has(node.kind) && (
        <Field label="Indexed entity" hint="Only entities found in the current application graph are accepted.">
          <select className={inputClass()} value={node.entityClass ?? ''} onChange={(event) => {
            const chosen = event.target.value
            onChange({
              entityClass: chosen,
              resultJavaType: variableKinds.has(node.kind) && chosen ? chosen : node.resultJavaType,
            })
          }}>
            <option value="">Choose entity…</option>
            {entities.map((entity) => <option key={entity.id} value={entity.semanticKey}>{entity.displayName} · {entity.owner.moduleId}</option>)}
          </select>
        </Field>
      )}

      {variableKinds.has(node.kind) && (
        <>
          <Field label="Result variable"><input className={inputClass()} value={node.resultVariable ?? ''} onChange={(event) => onChange({ resultVariable: event.target.value })} /></Field>
          <Field label="Result Java type"><input className={inputClass()} value={node.resultJavaType ?? ''} onChange={(event) => onChange({ resultJavaType: event.target.value })} /></Field>
        </>
      )}

      {valueKinds.has(node.kind) && (
        <Field label={node.kind === 'LOAD_ENTITY_BY_ID' ? 'Entity ID value' : node.kind === 'FOR_EACH' ? 'Collection value' : 'Value'}>
          <ValueEditor value={node.value ?? value()} onChange={(next) => onChange({ value: next })} />
        </Field>
      )}

      {(node.kind === 'CREATE_ENTITY') && (
        <NamedValuesEditor
          title="Initial field values"
          items={node.fieldValues}
          onChange={(fieldValues) => onChange({ fieldValues })}
        />
      )}

      {node.kind === 'LOAD_ENTITIES' && (
        <>
          <Field label="JPQL" hint="The generator requires a SELECT query and binds every parameter.">
            <textarea className={inputClass('min-h-24 resize-y font-mono')} value={node.jpql ?? ''} onChange={(event) => onChange({ jpql: event.target.value })} />
          </Field>
          <Field label="Maximum results">
            <input type="number" min={1} max={100_000} className={inputClass()} value={node.maxResults ?? 100} onChange={(event) => onChange({ maxResults: Number(event.target.value) })} />
          </Field>
          <NamedValuesEditor title="Query parameters" items={node.queryParameters} onChange={(queryParameters) => onChange({ queryParameters })} />
        </>
      )}

      {node.kind === 'FOR_EACH' && (
        <Field label="Index variable" hint="Optional zero-based Integer variable available to loop blocks.">
          <input className={inputClass()} value={node.indexVariable ?? ''} onChange={(event) => onChange({ indexVariable: event.target.value })} />
        </Field>
      )}

      {(node.kind === 'SET_PROPERTY') && (
        <>
          <Field label="Target variable"><input className={inputClass()} value={node.targetVariable ?? ''} onChange={(event) => onChange({ targetVariable: event.target.value })} /></Field>
          <Field label="Property path"><input className={inputClass()} value={node.propertyPath ?? ''} onChange={(event) => onChange({ propertyPath: event.target.value })} /></Field>
        </>
      )}

      {(node.kind === 'SAVE_ENTITY' || node.kind === 'REMOVE_ENTITY') && (
        <Field label="Entity variable"><input className={inputClass()} value={node.targetVariable ?? ''} onChange={(event) => onChange({ targetVariable: event.target.value })} /></Field>
      )}

      {node.kind === 'CALL_SERVICE' && (
        <>
          <Field label="Indexed service type">
            <select className={inputClass()} value={node.beanClass ?? ''} onChange={(event) => {
              const artifact = services.find((item) => item.semanticKey === event.target.value)
              const suggested = artifact?.displayName
                ? artifact.displayName.charAt(0).toLowerCase() + artifact.displayName.slice(1)
                : node.beanFieldName
              onChange({ beanClass: event.target.value, beanFieldName: suggested })
            }}>
              <option value="">Choose service…</option>
              {services.map((service) => <option key={service.id} value={service.semanticKey}>{service.displayName} · {service.owner.moduleId}</option>)}
            </select>
          </Field>
          <Field label="Injected field"><input className={inputClass()} value={node.beanFieldName ?? ''} onChange={(event) => onChange({ beanFieldName: event.target.value })} /></Field>
          <Field label="Method name"><input className={inputClass()} value={node.methodName ?? ''} onChange={(event) => onChange({ methodName: event.target.value })} /></Field>
          <div>
            <div className="mb-2 flex items-center justify-between">
              <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">Arguments</span>
              <button className="text-jmix-400" onClick={() => onChange({ arguments: [...node.arguments, value('VARIABLE', 'OBJECT')] })}><Plus className="h-3.5 w-3.5" /></button>
            </div>
            <div className="space-y-2">
              {node.arguments.map((argument, index) => (
                <div key={index} className="rounded border border-surface-border p-2">
                  <div className="mb-1 flex justify-end"><button className="text-gray-600 hover:text-red-300" onClick={() => onChange({ arguments: node.arguments.filter((_, itemIndex) => itemIndex !== index) })}><X className="h-3 w-3" /></button></div>
                  <ValueEditor value={argument} compact onChange={(next) => onChange({ arguments: node.arguments.map((item, itemIndex) => itemIndex === index ? next : item) })} />
                </div>
              ))}
            </div>
          </div>
        </>
      )}

      {node.kind === 'CALL_SUBFLOW' && (
        <>
          <Field label="Reusable subflow">
            <select className={inputClass()} value={node.subflowMethod ?? ''} onChange={(event) => {
              const target = subflows.find((candidate) => candidate.name === event.target.value)
              onChange({
                subflowMethod: event.target.value,
                resultJavaType: target?.returnJavaType ?? 'void',
                resultVariable: target?.returnJavaType === 'void'
                  ? undefined
                  : node.resultVariable || 'result',
              })
            }}>
              <option value="">Choose subflow…</option>
              {subflows.map((subflow) => (
                <option key={subflow.name} value={subflow.name}>
                  {subflow.name}({subflow.parameters.map((parameter) => parameter.javaType).join(', ')}) → {subflow.returnJavaType}
                </option>
              ))}
            </select>
          </Field>
          <ArgumentValuesEditor
            title="Subflow arguments"
            items={node.arguments}
            onChange={(arguments_) => onChange({ arguments: arguments_ })}
          />
        </>
      )}

      {node.kind === 'TRY_CATCH' && (
        <>
          <Field label="Try subflow">
            <select className={inputClass()} value={node.subflowMethod ?? ''} onChange={(event) => {
              const target = subflows.find((candidate) => candidate.name === event.target.value)
              onChange({
                subflowMethod: event.target.value,
                resultJavaType: target?.returnJavaType ?? 'void',
                resultVariable: target?.returnJavaType === 'void'
                  ? undefined
                  : node.resultVariable || 'result',
              })
            }}>
              <option value="">Choose subflow…</option>
              {subflows.map((subflow) => <option key={subflow.name} value={subflow.name}>{subflow.name} → {subflow.returnJavaType}</option>)}
            </select>
          </Field>
          <Field label="Caught exception type">
            <input className={inputClass()} value={node.exceptionType ?? ''} onChange={(event) => onChange({ exceptionType: event.target.value })} />
          </Field>
          <Field label="Catch subflow" hint="Its final parameter must be the selected exception type.">
            <select className={inputClass()} value={node.catchMethod ?? ''} onChange={(event) => onChange({ catchMethod: event.target.value || undefined })}>
              <option value="">Rethrow after finally</option>
              {subflows.map((subflow) => <option key={subflow.name} value={subflow.name}>{subflow.name} → {subflow.returnJavaType}</option>)}
            </select>
          </Field>
          <Field label="Finally subflow" hint="Optional void subflow that always runs.">
            <select className={inputClass()} value={node.finallyMethod ?? ''} onChange={(event) => onChange({ finallyMethod: event.target.value || undefined })}>
              <option value="">No finally subflow</option>
              {subflows.filter((subflow) => subflow.returnJavaType === 'void').map((subflow) => (
                <option key={subflow.name} value={subflow.name}>{subflow.name}</option>
              ))}
            </select>
          </Field>
          <ArgumentValuesEditor
            title="Shared try/finally arguments"
            items={node.arguments}
            onChange={(arguments_) => onChange({ arguments: arguments_ })}
          />
        </>
      )}

      {(node.kind === 'CONDITION' || node.kind === 'REQUIRE') && (
        <>
          <Field label="Left value"><ValueEditor value={node.condition?.left ?? value('VARIABLE', 'OBJECT')} onChange={(left) => onChange({ condition: { ...(node.condition ?? { operator: 'NOT_NULL' }), left } })} /></Field>
          <Field label="Operator">
            <select className={inputClass()} value={node.condition?.operator ?? 'NOT_NULL'} onChange={(event) => {
              const operator = event.target.value as LogicConditionOperator
              onChange({ condition: {
                left: node.condition?.left ?? value('VARIABLE', 'OBJECT'),
                operator,
                right: unaryOperators.has(operator) ? undefined : node.condition?.right ?? value(),
              } })
            }}>
              {conditionOperators.map((operator) => <option key={operator}>{operator}</option>)}
            </select>
          </Field>
          {!unaryOperators.has(node.condition?.operator ?? 'NOT_NULL') && (
            <Field label="Right value"><ValueEditor value={node.condition?.right ?? value()} onChange={(right) => onChange({ condition: { ...(node.condition ?? { left: value('VARIABLE', 'OBJECT'), operator: 'EQUALS' }), right } })} /></Field>
          )}
          {node.kind === 'REQUIRE' && <Field label="Failure message"><input className={inputClass()} value={node.message ?? ''} onChange={(event) => onChange({ message: event.target.value })} /></Field>}
        </>
      )}

      {node.kind === 'AUTHORIZE_ENTITY' && (
        <Field label="Required entity operation">
          <select className={inputClass()} value={node.entityOperation ?? 'READ'} onChange={(event) => onChange({ entityOperation: event.target.value as LogicEntityOperation })}>
            {entityOperations.map((operation) => <option key={operation}>{operation}</option>)}
          </select>
        </Field>
      )}

      {node.kind === 'THROW' && (
        <>
          <Field label="Exception Java type"><input className={inputClass()} value={node.resultJavaType ?? ''} onChange={(event) => onChange({ resultJavaType: event.target.value })} /></Field>
          <Field label="Message"><input className={inputClass()} value={node.message ?? ''} onChange={(event) => onChange({ message: event.target.value })} /></Field>
        </>
      )}

      {node.kind === 'LOG' && (
        <>
          <Field label="Level">
            <select className={inputClass()} value={node.logLevel} onChange={(event) => onChange({ logLevel: event.target.value as LogicNodeModel['logLevel'] })}>
              {['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'].map((level) => <option key={level}>{level}</option>)}
            </select>
          </Field>
          <Field label="Message"><input className={inputClass()} value={node.message ?? ''} onChange={(event) => onChange({ message: event.target.value })} /></Field>
        </>
      )}
    </>
  )
}

function NamedValuesEditor({
  title,
  items,
  onChange,
}: {
  title: string
  items: LogicNamedValueModel[]
  onChange: (items: LogicNamedValueModel[]) => void
}) {
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">{title}</span>
        <button className="text-jmix-400" onClick={() => onChange([...items, { name: '', value: value() }])}><Plus className="h-3.5 w-3.5" /></button>
      </div>
      <div className="space-y-2">
        {items.map((item, index) => (
          <div key={index} className="rounded border border-surface-border p-2">
            <div className="mb-2 flex gap-1">
              <input className={inputClass()} value={item.name} placeholder="property / parameter" onChange={(event) => onChange(items.map((candidate, itemIndex) => itemIndex === index ? { ...candidate, name: event.target.value } : candidate))} />
              <button className="px-1 text-gray-600 hover:text-red-300" onClick={() => onChange(items.filter((_, itemIndex) => itemIndex !== index))}><X className="h-3.5 w-3.5" /></button>
            </div>
            <ValueEditor value={item.value} compact onChange={(next) => onChange(items.map((candidate, itemIndex) => itemIndex === index ? { ...candidate, value: next } : candidate))} />
          </div>
        ))}
        {!items.length && <p className="text-[10px] text-gray-600">No values configured.</p>}
      </div>
    </div>
  )
}

function ArgumentValuesEditor({
  title,
  items,
  onChange,
}: {
  title: string
  items: LogicValueModel[]
  onChange: (items: LogicValueModel[]) => void
}) {
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">{title}</span>
        <button className="text-jmix-400" onClick={() => onChange([...items, value('VARIABLE', 'OBJECT')])}>
          <Plus className="h-3.5 w-3.5" />
        </button>
      </div>
      <div className="space-y-2">
        {items.map((argument, index) => (
          <div key={index} className="rounded border border-surface-border p-2">
            <div className="mb-1 flex justify-end">
              <button className="text-gray-600 hover:text-red-300" onClick={() => onChange(items.filter((_, itemIndex) => itemIndex !== index))}>
                <X className="h-3 w-3" />
              </button>
            </div>
            <ValueEditor
              value={argument}
              compact
              onChange={(next) => onChange(items.map((item, itemIndex) => itemIndex === index ? next : item))}
            />
          </div>
        ))}
        {!items.length && <p className="text-[10px] text-gray-600">No arguments configured.</p>}
      </div>
    </div>
  )
}
