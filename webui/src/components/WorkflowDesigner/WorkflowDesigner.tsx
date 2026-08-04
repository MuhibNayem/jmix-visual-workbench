import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle,
  Activity,
  Bot,
  Box,
  CheckCircle2,
  Circle,
  Clock3,
  Database,
  Diamond,
  ExternalLink,
  FileCode2,
  GitBranch,
  GitMerge,
  Loader2,
  Mail,
  Maximize2,
  Play,
  Plus,
  Radio,
  RefreshCw,
  RotateCcw,
  Save,
  ShieldCheck,
  Table2,
  Trash2,
  Undo2,
  UserRound,
  X,
  ZoomIn,
  ZoomOut,
  Redo2,
} from 'lucide-react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  ApplicationGraphResponse,
  GraphArtifact,
  SchemaEntitySnapshot,
  SchemaWorkspaceResponse,
  WorkflowModel,
  WorkflowLoadResponse,
  WorkflowFormData,
  WorkflowListenerModel,
  WorkflowNodeModel,
  WorkflowNodeType,
  WorkflowProcessVariable,
  WorkflowTransitionModel,
  WorkflowVariableMapping,
  WorkspaceChangePreviewResponse,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'
import WorkflowSimulationDialog from './WorkflowSimulationDialog'

const NODE_WIDTH = 168
const NODE_HEIGHT = 66
const CANVAS_WIDTH = 1100
const CANVAS_HEIGHT = 720
const PORT_RADIUS = 8
const ROUTE_CLEARANCE = 44
const HISTORY_LIMIT = 100

type WorkflowPane = 'palette' | 'canvas' | 'inspector'

const nodeCatalog: Array<{
  type: WorkflowNodeType
  label: string
  description: string
  icon: typeof Circle
  accent: string
}> = [
  { type: 'START', label: 'Start', description: 'Process entry and business key', icon: Circle, accent: 'text-emerald-300' },
  { type: 'MESSAGE_START', label: 'Message start', description: 'Start from a uniquely named external message', icon: Mail, accent: 'text-emerald-200' },
  { type: 'SIGNAL_START', label: 'Signal start', description: 'Start from a global or instance signal', icon: Radio, accent: 'text-emerald-200' },
  { type: 'TIMER_START', label: 'Timer start', description: 'Start on a schedule or ISO-8601 cycle', icon: Clock3, accent: 'text-emerald-200' },
  { type: 'ERROR_START', label: 'Error start', description: 'Start an event subprocess from a BPMN error', icon: AlertTriangle, accent: 'text-red-300' },
  { type: 'HUMAN_STATE', label: 'Human state', description: 'Role/assignee task with form', icon: UserRound, accent: 'text-sky-300' },
  { type: 'AUTOMATED_STATE', label: 'Automated state', description: 'Transactional Spring service', icon: Bot, accent: 'text-violet-300' },
  { type: 'SCRIPT_STATE', label: 'Groovy script', description: 'Small server-side calculation or variable setup', icon: FileCode2, accent: 'text-purple-300' },
  { type: 'ENTITY_DATA_STATE', label: 'Entity data', description: 'Load, create, or modify a Jmix entity', icon: Database, accent: 'text-cyan-300' },
  { type: 'EMAIL_STATE', label: 'Jmix email', description: 'Send templated mail with expressions and attachments', icon: Mail, accent: 'text-cyan-200' },
  { type: 'DECISION', label: 'Decision', description: 'Conditional exclusive gateway', icon: Diamond, accent: 'text-amber-300' },
  { type: 'PARALLEL_GATEWAY', label: 'Parallel gateway', description: 'Fork or join all branches', icon: GitBranch, accent: 'text-cyan-300' },
  { type: 'INCLUSIVE_GATEWAY', label: 'Inclusive gateway', description: 'Fork or join selected branches', icon: GitMerge, accent: 'text-teal-300' },
  { type: 'BUSINESS_RULE_STATE', label: 'Decision table', description: 'Evaluate a deployed Jmix DMN table', icon: Table2, accent: 'text-fuchsia-300' },
  { type: 'EMBEDDED_SUBPROCESS', label: 'Subprocess', description: 'Embedded reusable scope with its own start and end', icon: Box, accent: 'text-indigo-200' },
  { type: 'EVENT_SUBPROCESS', label: 'Event subprocess', description: 'Event-triggered interrupting or non-interrupting scope', icon: Activity, accent: 'text-purple-200' },
  { type: 'TRANSACTION_SUBPROCESS', label: 'Transaction', description: 'Transaction scope with cancel and compensation semantics', icon: GitBranch, accent: 'text-amber-200' },
  { type: 'CALL_ACTIVITY', label: 'Call activity', description: 'Invoke a reusable versioned process', icon: Box, accent: 'text-indigo-300' },
  { type: 'TIMER_EVENT', label: 'Timer event', description: 'Wait until a date, duration, or cycle', icon: Clock3, accent: 'text-orange-300' },
  { type: 'MESSAGE_CATCH', label: 'Catch message', description: 'Wait for a correlated external message', icon: Mail, accent: 'text-blue-300' },
  { type: 'SIGNAL_CATCH', label: 'Catch signal', description: 'Wait for a broadcast or instance signal', icon: Radio, accent: 'text-blue-300' },
  { type: 'SIGNAL_THROW', label: 'Throw signal', description: 'Broadcast a synchronous or async signal', icon: Radio, accent: 'text-purple-300' },
  { type: 'COMPENSATION_THROW', label: 'Compensate', description: 'Undo completed work in reverse order', icon: RotateCcw, accent: 'text-pink-300' },
  { type: 'BOUNDARY_TIMER', label: 'SLA boundary', description: 'Interrupt or escalate an overdue activity', icon: Clock3, accent: 'text-orange-200' },
  { type: 'BOUNDARY_MESSAGE', label: 'Message boundary', description: 'Interrupt or branch when a message arrives', icon: Mail, accent: 'text-blue-200' },
  { type: 'BOUNDARY_SIGNAL', label: 'Signal boundary', description: 'React to a broadcast while work is active', icon: Radio, accent: 'text-blue-200' },
  { type: 'BOUNDARY_ERROR', label: 'Error boundary', description: 'Catch a typed BPMN business error', icon: AlertTriangle, accent: 'text-red-300' },
  { type: 'BOUNDARY_COMPENSATION', label: 'Compensation boundary', description: 'Register a compensation handler', icon: RotateCcw, accent: 'text-pink-200' },
  { type: 'BOUNDARY_CANCEL', label: 'Cancel boundary', description: 'Catch cancellation from a transaction subprocess', icon: X, accent: 'text-amber-300' },
  { type: 'ERROR_END', label: 'Error end', description: 'Terminate the path with a typed BPMN error', icon: AlertTriangle, accent: 'text-red-400' },
  { type: 'CANCEL_END', label: 'Cancel end', description: 'Cancel the enclosing transaction subprocess', icon: X, accent: 'text-amber-400' },
  { type: 'TERMINATE_END', label: 'Terminate end', description: 'Terminate all executions in the current scope', icon: X, accent: 'text-rose-400' },
  { type: 'TERMINAL', label: 'Terminal', description: 'Approved, rejected, closed…', icon: CheckCircle2, accent: 'text-rose-300' },
]

const emptyLists = {
  requiredDocuments: [] as string[],
  validationRules: [] as string[],
  sideEffects: [] as string[],
  notifications: [] as string[],
  requiredPermissions: [] as string[],
}

const defaultWorkflow = (): WorkflowModel => ({
  id: 'loan-lifecycle',
  name: 'Loan Lifecycle',
  moduleId: 'loan',
  entityQualifiedName: 'com.company.loan.entity.LoanApp',
  stateAttribute: 'processState',
  candidateStarterGroups: ['hr-operator'],
  candidateStarterUsers: [],
  businessKeyExpression: '${loanApp.id}',
  versionTag: '1.0.0',
  auditLevel: 'REGULATED',
  lanes: [
    { id: 'origination', name: 'Origination', actorRoleCodes: ['hr-operator'] },
    { id: 'risk-control', name: 'Risk & control', actorRoleCodes: ['credit-committee'] },
    { id: 'operations', name: 'Payroll operations', actorRoleCodes: ['payroll-manager'] },
  ],
  executionListeners: [],
  documentation: 'Application, approval, disbursement, settlement, cancellation, and closure lifecycle.',
  nodes: [
    node('start', 'Application received', 'START', 50, 285, { stateValue: 'APPLICATION', laneId: 'origination' }),
    node('review', 'Review application', 'HUMAN_STATE', 270, 285, {
      stateValue: 'UNDER_REVIEW',
      actorRoleCodes: ['hr-operator'],
      formKey: 'loan-application-review',
      requiredDocuments: ['application-form', 'identity-document'],
      validationRules: ['eligibilityService.validate(applicationId)'],
      laneId: 'origination',
    }),
    node('decision', 'Approval decision', 'DECISION', 500, 285, { laneId: 'risk-control' }),
    node('disburse', 'Disburse loan', 'AUTOMATED_STATE', 720, 150, {
      stateValue: 'APPROVED',
      serviceBean: 'loanWorkflowService',
      serviceMethod: 'approveAndDisburse',
      sideEffects: ['create repayment schedule', 'post ledger entry'],
      notifications: ['notify employee and payroll'],
      laneId: 'operations',
    }),
    node('settlement', 'Settle balance', 'HUMAN_STATE', 720, 370, {
      stateValue: 'DISBURSED',
      actorRoleCodes: ['payroll-manager'],
      formKey: 'loan-settlement',
      validationRules: ['outstandingBalance.signum() == 0'],
      laneId: 'operations',
    }),
    node('closed', 'Closed', 'TERMINAL', 940, 370, { stateValue: 'CLOSED', laneId: 'operations' }),
    node('rejected', 'Rejected', 'TERMINAL', 720, 535, { stateValue: 'REJECTED', laneId: 'risk-control' }),
    node('cancelled', 'Cancelled', 'TERMINAL', 500, 535, { stateValue: 'CANCELLED', laneId: 'origination' }),
  ],
  transitions: [
    transition('receive-review', 'start', 'review', 'Submit'),
    transition('review-decision', 'review', 'decision', 'Complete review'),
    transition('decision-approve', 'decision', 'disburse', 'Approve', '${approved == true}', 'approve'),
    transition('decision-reject', 'decision', 'rejected', 'Reject', '${approved == false}', 'reject'),
    transition('review-cancel', 'review', 'cancelled', 'Cancel', '${cancelled == true}', 'cancel'),
    transition('disburse-settlement', 'disburse', 'settlement', 'Begin deductions'),
    transition('settlement-close', 'settlement', 'closed', 'Close', '${outstandingBalance == 0}', 'close'),
  ],
})

function node(
  id: string,
  name: string,
  type: WorkflowNodeType,
  x: number,
  y: number,
  patch: Partial<WorkflowNodeModel> = {},
): WorkflowNodeModel {
  return {
    id,
    name,
    type,
    actorRoleCodes: [],
    processVariables: [],
    entityDataOperation: 'LOAD',
    saveLoadResultAs: 'SINGLE',
    emailContentType: 'HTML',
    emailSendAsync: true,
    emailAttachments: [],
    async: false,
    exclusive: true,
    triggerable: false,
    multiInstanceMode: 'NONE',
    inheritBusinessKey: true,
    inheritVariables: false,
    timerType: 'DURATION',
    cancelActivity: true,
    eventStartInterrupting: true,
    signalScope: 'GLOBAL',
    forCompensation: false,
    executionListeners: [],
    taskListeners: [],
    inputMappings: [],
    outputMappings: [],
    segregationOfDutyNodeIds: [],
    x,
    y,
    width: isSubprocessType(type) ? 360 : NODE_WIDTH,
    height: isSubprocessType(type) ? 220 : NODE_HEIGHT,
    ...emptyLists,
    ...patch,
  }
}

function transition(
  id: string,
  sourceId: string,
  targetId: string,
  name?: string,
  conditionExpression?: string,
  outcomeId?: string,
): WorkflowTransitionModel {
  return {
    id,
    sourceId,
    targetId,
    name,
    conditionExpression,
    outcomeId,
    requiredRoleCodes: [],
    requiredDocuments: [],
    validationRules: [],
    sideEffects: [],
    notifications: [],
  }
}

const csv = (value: string) => value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean)
const joined = (values: string[]) => values.join(', ')
const slug = (value: string) =>
  value.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'state'
const isTerminalType = (type: WorkflowNodeType) =>
  type === 'TERMINAL' || type === 'ERROR_END' || type === 'CANCEL_END' || type === 'TERMINATE_END'
const isStartType = (type: WorkflowNodeType) =>
  type === 'START' || type === 'MESSAGE_START' || type === 'SIGNAL_START' ||
  type === 'TIMER_START' || type === 'ERROR_START'
const isBoundaryType = (type: WorkflowNodeType) =>
  type === 'BOUNDARY_TIMER' || type === 'BOUNDARY_MESSAGE' || type === 'BOUNDARY_SIGNAL' ||
  type === 'BOUNDARY_ERROR' || type === 'BOUNDARY_COMPENSATION' || type === 'BOUNDARY_CANCEL'
const isCompensationBoundary = (type: WorkflowNodeType) => type === 'BOUNDARY_COMPENSATION'
const isSubprocessType = (type: WorkflowNodeType) =>
  type === 'EMBEDDED_SUBPROCESS' || type === 'EVENT_SUBPROCESS' || type === 'TRANSACTION_SUBPROCESS'
const canHaveSequenceOutgoing = (type: WorkflowNodeType) =>
  !isTerminalType(type) && !isCompensationBoundary(type) && type !== 'EVENT_SUBPROCESS'
const canReceiveSequenceFlow = (type: WorkflowNodeType) =>
  !isStartType(type) && !isBoundaryType(type) && type !== 'EVENT_SUBPROCESS'
const supportsMultiInstance = (type: WorkflowNodeType) =>
  type === 'HUMAN_STATE' || type === 'AUTOMATED_STATE' || type === 'SCRIPT_STATE' ||
  type === 'BUSINESS_RULE_STATE' || type === 'EMBEDDED_SUBPROCESS' ||
  type === 'TRANSACTION_SUBPROCESS' || type === 'CALL_ACTIVITY'
const isAttachableActivity = (type: WorkflowNodeType) =>
  type === 'HUMAN_STATE' || type === 'AUTOMATED_STATE' || type === 'SCRIPT_STATE' ||
  type === 'ENTITY_DATA_STATE' || type === 'EMAIL_STATE' || type === 'BUSINESS_RULE_STATE' ||
  isSubprocessType(type) || type === 'CALL_ACTIVITY'
const supportsAsync = (type: WorkflowNodeType) =>
  type === 'HUMAN_STATE' || type === 'AUTOMATED_STATE' || type === 'SCRIPT_STATE' ||
  type === 'ENTITY_DATA_STATE' || type === 'EMAIL_STATE' || type === 'BUSINESS_RULE_STATE' ||
  type === 'EMBEDDED_SUBPROCESS' || type === 'TRANSACTION_SUBPROCESS' || type === 'CALL_ACTIVITY'

const nodeSize = (workflowNode: WorkflowNodeModel) => ({
  width: workflowNode.width || (isSubprocessType(workflowNode.type) ? 360 : NODE_WIDTH),
  height: workflowNode.height || (isSubprocessType(workflowNode.type) ? 220 : NODE_HEIGHT),
})

interface DiagramPoint {
  x: number
  y: number
}

type NodeSide = 'LEFT' | 'RIGHT' | 'TOP' | 'BOTTOM'

interface RoutedWorkflowEdge {
  path: string
  arrowPoints: string
  label: DiagramPoint
  sourceSide: NodeSide
  targetSide: NodeSide
}

const sideVector = (side: NodeSide): DiagramPoint => {
  switch (side) {
    case 'LEFT': return { x: -1, y: 0 }
    case 'RIGHT': return { x: 1, y: 0 }
    case 'TOP': return { x: 0, y: -1 }
    case 'BOTTOM': return { x: 0, y: 1 }
  }
}

const nodeAnchor = (workflowNode: WorkflowNodeModel, side: NodeSide): DiagramPoint => {
  const { width, height } = nodeSize(workflowNode)
  switch (side) {
    case 'LEFT': return { x: workflowNode.x, y: workflowNode.y + height / 2 }
    case 'RIGHT': return { x: workflowNode.x + width, y: workflowNode.y + height / 2 }
    case 'TOP': return { x: workflowNode.x + width / 2, y: workflowNode.y }
    case 'BOTTOM': return { x: workflowNode.x + width / 2, y: workflowNode.y + height }
  }
}

function edgeSides(source: WorkflowNodeModel, target: WorkflowNodeModel): {
  sourceSide: NodeSide
  targetSide: NodeSide
} {
  const sourceSize = nodeSize(source)
  const targetSize = nodeSize(target)
  const sourceCenter = { x: source.x + sourceSize.width / 2, y: source.y + sourceSize.height / 2 }
  const targetCenter = { x: target.x + targetSize.width / 2, y: target.y + targetSize.height / 2 }
  const dx = targetCenter.x - sourceCenter.x
  const dy = targetCenter.y - sourceCenter.y

  // Compare the free gap between node bounds, not only center distance. This
  // prevents a mostly vertical edge from remaining attached to left/right when
  // nodes are moved above one another.
  const horizontalGap = Math.abs(dx) - (sourceSize.width + targetSize.width) / 2
  const verticalGap = Math.abs(dy) - (sourceSize.height + targetSize.height) / 2
  const useHorizontal = horizontalGap >= verticalGap
  if (useHorizontal) {
    return dx >= 0
      ? { sourceSide: 'RIGHT', targetSide: 'LEFT' }
      : { sourceSide: 'LEFT', targetSide: 'RIGHT' }
  }
  return dy >= 0
    ? { sourceSide: 'BOTTOM', targetSide: 'TOP' }
    : { sourceSide: 'TOP', targetSide: 'BOTTOM' }
}

function routeWorkflowEdge(source: WorkflowNodeModel, target: WorkflowNodeModel): RoutedWorkflowEdge {
  const { sourceSide, targetSide } = edgeSides(source, target)
  const start = nodeAnchor(source, sourceSide)
  const targetPort = nodeAnchor(target, targetSide)
  const targetNormal = sideVector(targetSide)
  const arrowTip = {
    x: targetPort.x + targetNormal.x * (PORT_RADIUS - 1),
    y: targetPort.y + targetNormal.y * (PORT_RADIUS - 1),
  }
  let points: DiagramPoint[]
  if (sourceSide === 'LEFT' || sourceSide === 'RIGHT') {
    if (Math.abs(start.y - arrowTip.y) <= 4) {
      points = [start, arrowTip]
    } else {
      const middleX = Math.round((start.x + arrowTip.x) / 2)
      points = [
        start,
        { x: middleX, y: start.y },
        { x: middleX, y: arrowTip.y },
        arrowTip,
      ]
    }
  } else {
    if (Math.abs(start.x - arrowTip.x) <= 4) {
      points = [start, arrowTip]
    } else {
      const middleY = Math.round((start.y + arrowTip.y) / 2)
      points = [
        start,
        { x: start.x, y: middleY },
        { x: arrowTip.x, y: middleY },
        arrowTip,
      ]
    }
  }
  const horizontalSegments = points.slice(0, -1).map((point, index) => ({
    start: point,
    end: points[index + 1],
  })).filter((segment) => segment.start.y === segment.end.y)
  const labelSegment = horizontalSegments.sort((left, right) =>
    Math.abs(right.end.x - right.start.x) - Math.abs(left.end.x - left.start.x),
  )[0]
  const label = labelSegment
    ? {
        x: (labelSegment.start.x + labelSegment.end.x) / 2,
        y: labelSegment.start.y - 9,
      }
    : {
        x: (start.x + arrowTip.x) / 2,
        y: (start.y + arrowTip.y) / 2 - 9,
      }
  return {
    path: roundedOrthogonalPath(points),
    arrowPoints: [
      `${arrowTip.x},${arrowTip.y}`,
      `${arrowTip.x + targetNormal.x * 11 + targetNormal.y * 6},${arrowTip.y + targetNormal.y * 11 + targetNormal.x * 6}`,
      `${arrowTip.x + targetNormal.x * 11 - targetNormal.y * 6},${arrowTip.y + targetNormal.y * 11 - targetNormal.x * 6}`,
    ].join(' '),
    label,
    sourceSide,
    targetSide,
  }
}

function sideTowardPoint(source: WorkflowNodeModel, point: DiagramPoint): NodeSide {
  const virtualTarget: WorkflowNodeModel = {
    ...source,
    id: '__pointer__',
    x: point.x - NODE_WIDTH / 2,
    y: point.y - NODE_HEIGHT / 2,
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
  }
  return edgeSides(source, virtualTarget).sourceSide
}

function containerAtPoint(
  workflow: WorkflowModel,
  x: number,
  y: number,
  excludedIds: Set<string> = new Set(),
): WorkflowNodeModel | undefined {
  return workflow.nodes
    .filter((candidate) => {
      if (!isSubprocessType(candidate.type) || excludedIds.has(candidate.id)) return false
      const size = nodeSize(candidate)
      return x >= candidate.x && x <= candidate.x + size.width &&
        y >= candidate.y && y <= candidate.y + size.height
    })
    .sort((left, right) => {
      const leftSize = nodeSize(left)
      const rightSize = nodeSize(right)
      return leftSize.width * leftSize.height - rightSize.width * rightSize.height
    })[0]
}

function descendantIds(workflow: WorkflowModel, parentId: string): Set<string> {
  const descendants = new Set<string>()
  const queue = [parentId]
  while (queue.length) {
    const current = queue.shift()!
    workflow.nodes.filter((candidate) => candidate.parentSubprocessId === current).forEach((candidate) => {
      if (descendants.add(candidate.id)) queue.push(candidate.id)
    })
  }
  return descendants
}

function routeConnectionPreview(source: WorkflowNodeModel, point: DiagramPoint): string {
  const side = sideTowardPoint(source, point)
  const start = nodeAnchor(source, side)
  const normal = sideVector(side)
  const exit = {
    x: start.x + normal.x * Math.min(ROUTE_CLEARANCE, 28),
    y: start.y + normal.y * Math.min(ROUTE_CLEARANCE, 28),
  }
  const points = side === 'LEFT' || side === 'RIGHT'
    ? [start, exit, { x: exit.x, y: point.y }, point]
    : [start, exit, { x: point.x, y: exit.y }, point]
  return roundedOrthogonalPath(points, 9)
}

const portPosition = (side: NodeSide): React.CSSProperties => {
  switch (side) {
    case 'LEFT': return { left: -8, top: '50%', transform: 'translateY(-50%)' }
    case 'RIGHT': return { right: -8, top: '50%', transform: 'translateY(-50%)' }
    case 'TOP': return { left: '50%', top: -8, transform: 'translateX(-50%)' }
    case 'BOTTOM': return { left: '50%', bottom: -8, transform: 'translateX(-50%)' }
  }
}

function roundedOrthogonalPath(points: DiagramPoint[], radius = 11): string {
  const distinct = points.filter((point, index) =>
    index === 0 || point.x !== points[index - 1].x || point.y !== points[index - 1].y,
  )
  const normalized = distinct.filter((point, index) => {
    if (index === 0 || index === distinct.length - 1) return true
    const previous = distinct[index - 1]
    const next = distinct[index + 1]
    return !(
      (previous.x === point.x && point.x === next.x) ||
      (previous.y === point.y && point.y === next.y)
    )
  })
  if (normalized.length < 2) return ''
  const parts = [`M ${normalized[0].x} ${normalized[0].y}`]
  for (let index = 1; index < normalized.length - 1; index += 1) {
    const previous = normalized[index - 1]
    const corner = normalized[index]
    const next = normalized[index + 1]
    const incomingLength = Math.abs(corner.x - previous.x) + Math.abs(corner.y - previous.y)
    const outgoingLength = Math.abs(next.x - corner.x) + Math.abs(next.y - corner.y)
    const effectiveRadius = Math.min(radius, incomingLength / 2, outgoingLength / 2)
    const before = moveToward(corner, previous, effectiveRadius)
    const after = moveToward(corner, next, effectiveRadius)
    parts.push(`L ${before.x} ${before.y}`)
    parts.push(`Q ${corner.x} ${corner.y} ${after.x} ${after.y}`)
  }
  const last = normalized[normalized.length - 1]
  parts.push(`L ${last.x} ${last.y}`)
  return parts.join(' ')
}

function moveToward(from: DiagramPoint, to: DiagramPoint, distance: number): DiagramPoint {
  if (from.x !== to.x) {
    return { x: from.x + Math.sign(to.x - from.x) * distance, y: from.y }
  }
  return { x: from.x, y: from.y + Math.sign(to.y - from.y) * distance }
}

const btn =
  'inline-flex items-center justify-center gap-1.5 rounded border border-surface-border bg-surface-lighter px-2.5 py-1.5 text-[10px] font-medium text-gray-300 transition hover:border-jmix-500/60 hover:text-jmix-300 disabled:cursor-not-allowed disabled:opacity-40'
const primary =
  'inline-flex items-center justify-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-[10px] font-semibold text-white transition hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-40'
const input = 'w-full min-w-0 py-1.5 text-[11px]'

export default function WorkflowDesigner() {
  // Selective subscriptions: subscribing to the whole store would re-render
  // this large component on every store change (including each tab switch),
  // which caused hangs during rapid tab switching.
  const addToast = useStore((state) => state.addToast)
  const setIsGenerating = useStore((state) => state.setIsGenerating)
  const isGenerating = useStore((state) => state.isGenerating)
  const [workflow, setWorkflow] = useState<WorkflowModel>(defaultWorkflow)
  const [graph, setGraph] = useState<ApplicationGraphResponse | null>(null)
  const [schema, setSchema] = useState<SchemaWorkspaceResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [selectedNodeId, setSelectedNodeId] = useState<string>('review')
  const [selectedTransitionId, setSelectedTransitionId] = useState<string | null>(null)
  const [existingProcessId, setExistingProcessId] = useState<string | null>(null)
  const [loadingExistingId, setLoadingExistingId] = useState<string | null>(null)
  const [existingLoadResult, setExistingLoadResult] = useState<WorkflowLoadResponse | null>(null)
  const [connectTarget, setConnectTarget] = useState('')
  const [connectingFrom, setConnectingFrom] = useState<string | null>(null)
  const [connectionPoint, setConnectionPoint] = useState<{ x: number; y: number } | null>(null)
  const [zoom, setZoom] = useState(0.8)
  const [pane, setPane] = useState<WorkflowPane>('canvas')
  const [undoStack, setUndoStack] = useState<WorkflowModel[]>([])
  const [redoStack, setRedoStack] = useState<WorkflowModel[]>([])
  const [simulationOpen, setSimulationOpen] = useState(false)
  const [pending, setPending] = useState<{
    workflow: WorkflowModel
    preview: WorkspaceChangePreviewResponse
  } | null>(null)
  const sequence = useRef(1)
  const canvasRef = useRef<HTMLDivElement>(null)
  const canvasViewportRef = useRef<HTMLDivElement>(null)
  const workflowRef = useRef(workflow)
  const undoStackRef = useRef<WorkflowModel[]>([])
  const redoStackRef = useRef<WorkflowModel[]>([])

  useEffect(() => {
    workflowRef.current = workflow
  }, [workflow])

  const syncHistory = useCallback((past: WorkflowModel[], future: WorkflowModel[]) => {
    undoStackRef.current = past
    redoStackRef.current = future
    setUndoStack(past)
    setRedoStack(future)
  }, [])

  const replaceWorkflow = useCallback((next: WorkflowModel) => {
    workflowRef.current = next
    setWorkflow(next)
    syncHistory([], [])
    setPending(null)
  }, [syncHistory])

  const commitWorkflow = useCallback((
    update: WorkflowModel | ((current: WorkflowModel) => WorkflowModel),
  ) => {
    const current = workflowRef.current
    const next = typeof update === 'function' ? update(current) : update
    if (next === current) return
    workflowRef.current = next
    setWorkflow(next)
    syncHistory([...undoStackRef.current, current].slice(-HISTORY_LIMIT), [])
    setPending(null)
  }, [syncHistory])

  const undoVisualChange = useCallback(() => {
    const past = undoStackRef.current
    if (!past.length) return
    const previous = past[past.length - 1]
    const current = workflowRef.current
    workflowRef.current = previous
    setWorkflow(previous)
    syncHistory(past.slice(0, -1), [current, ...redoStackRef.current].slice(0, HISTORY_LIMIT))
    setPending(null)
  }, [syncHistory])

  const redoVisualChange = useCallback(() => {
    const future = redoStackRef.current
    if (!future.length) return
    const next = future[0]
    const current = workflowRef.current
    workflowRef.current = next
    setWorkflow(next)
    syncHistory([...undoStackRef.current, current].slice(-HISTORY_LIMIT), future.slice(1))
    setPending(null)
  }, [syncHistory])

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!(event.metaKey || event.ctrlKey) || event.key.toLowerCase() !== 'z') return
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      event.preventDefault()
      if (event.shiftKey) redoVisualChange()
      else undoVisualChange()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [redoVisualChange, undoVisualChange])

  const load = async (forceRefresh = false) => {
    setLoading(true)
    const [nextGraph, nextSchema] = await Promise.all([
      bridge.getApplicationGraph(forceRefresh),
      bridge.getSchemaWorkspace(forceRefresh),
    ])
    if (!('error' in nextGraph)) setGraph(nextGraph)
    if (!('error' in nextSchema)) setSchema(nextSchema)
    setLoading(false)
  }

  useEffect(() => { void load() }, [])

  const entities = schema?.entities ?? []
  const selectedEntity = entities.find((entity) => entity.qualifiedName === workflow.entityQualifiedName)
  const existingProcesses = graph?.artifacts.filter((artifact) => artifact.kind === 'WORKFLOW_PROCESS') ?? []
  const roleArtifacts = graph?.artifacts.filter((artifact) =>
    artifact.kind === 'RESOURCE_ROLE' || artifact.kind === 'ROW_ROLE',
  ) ?? []
  const serviceArtifacts = graph?.artifacts.filter((artifact) => artifact.kind === 'SERVICE') ?? []
  const decisionArtifacts = graph?.artifacts.filter((artifact) => artifact.kind === 'DECISION_TABLE') ?? []
  const formViewArtifacts = graph?.artifacts.filter((artifact) =>
    artifact.kind === 'VIEW_CONTROLLER' || artifact.kind === 'VIEW_DESCRIPTOR',
  ) ?? []
  const selectedNode = workflow.nodes.find((candidate) => candidate.id === selectedNodeId) ?? null
  const selectedTransition = workflow.transitions.find((candidate) => candidate.id === selectedTransitionId) ?? null
  const issues = useMemo(() => validate(workflow, selectedEntity, roleArtifacts), [workflow, selectedEntity, roleArtifacts])
  const blockingIssues = issues.filter((issue) => issue.level === 'error')
  const selectedExisting = existingProcesses.find((process) => process.id === existingProcessId) ?? null

  const patchWorkflow = (patch: Partial<WorkflowModel>) => {
    setExistingProcessId(null)
    commitWorkflow((current) => ({ ...current, ...patch }))
  }

  const patchNode = (id: string, patch: Partial<WorkflowNodeModel>) => {
    commitWorkflow((current) => ({
      ...current,
      nodes: current.nodes.map((candidate) => candidate.id === id ? { ...candidate, ...patch } : candidate),
    }))
  }

  const patchTransition = (id: string, patch: Partial<WorkflowTransitionModel>) => {
    commitWorkflow((current) => ({
      ...current,
      transitions: current.transitions.map((candidate) =>
        candidate.id === id ? { ...candidate, ...patch } : candidate,
      ),
    }))
  }

  const addNode = (type: WorkflowNodeType, x: number, y: number) => {
    const catalog = nodeCatalog.find((candidate) => candidate.type === type)!
    const idBase = slug(catalog.label)
    let id = `${idBase}-${sequence.current++}`
    while (workflow.nodes.some((candidate) => candidate.id === id)) id = `${idBase}-${sequence.current++}`
    const width = isSubprocessType(type) ? 360 : NODE_WIDTH
    const height = isSubprocessType(type) ? 220 : NODE_HEIGHT
    const clampedX = Math.max(0, Math.min(CANVAS_WIDTH - width, x))
    const clampedY = Math.max(0, Math.min(CANVAS_HEIGHT - height, y))
    const parent = containerAtPoint(workflow, clampedX + width / 2, clampedY + height / 2)
    const created = node(
      id,
      catalog.label,
      type,
      clampedX,
      clampedY,
      type === 'HUMAN_STATE'
        ? { actorRoleCodes: roleArtifacts[0] ? [roleArtifacts[0].displayName] : [] }
        : type === 'AUTOMATED_STATE'
          ? { serviceBean: serviceArtifacts[0]?.displayName }
          : type === 'ENTITY_DATA_STATE'
            ? {
                entityName: entities[0]?.entityName,
                resultVariable: 'entityResult',
                jpql: entities[0] ? `select e from ${entities[0].entityName} e` : '',
                jpqlParametersJson: '[]',
                entityAttributesJson: '[]',
              }
          : type === 'EMAIL_STATE'
            ? {
                emailTo: '${recipientEmail}',
                emailSubject: 'Workflow notification',
                emailContent: '<p>Your workflow item has been updated.</p>',
              }
          : { parentSubprocessId: parent?.id },
    )
    created.parentSubprocessId = parent?.id
    patchWorkflow({ nodes: [...workflow.nodes, created] })
    setSelectedNodeId(created.id)
    setSelectedTransitionId(null)
    setPane('canvas')
  }

  const removeNode = (id: string) => {
    const target = workflow.nodes.find((candidate) => candidate.id === id)
    if (!target || target.type === 'START') {
      addToast('The start state is protected. Add another start before restructuring it.', 'info')
      return
    }
    const removedIds = descendantIds(workflow, id)
    removedIds.add(id)
    patchWorkflow({
      nodes: workflow.nodes.filter((candidate) => !removedIds.has(candidate.id)),
      transitions: workflow.transitions.filter((candidate) =>
        !removedIds.has(candidate.sourceId) && !removedIds.has(candidate.targetId),
      ),
    })
    setSelectedNodeId('start')
    setSelectedTransitionId(null)
  }

  const addTransition = () => {
    if (!selectedNode || !connectTarget || connectTarget === selectedNode.id) return
    createTransitionBetween(selectedNode.id, connectTarget)
    setConnectTarget('')
  }

  const createTransitionBetween = (sourceId: string, targetId: string) => {
    if (sourceId === targetId) {
      addToast('A workflow state cannot connect to itself.', 'info')
      return
    }
    if (workflow.transitions.some((candidate) =>
      candidate.sourceId === sourceId && candidate.targetId === targetId,
    )) {
      const existing = workflow.transitions.find((candidate) =>
        candidate.sourceId === sourceId && candidate.targetId === targetId,
      )!
      setSelectedTransitionId(existing.id)
      setSelectedNodeId('')
      addToast('That transition already exists; its inspector is now selected.', 'info')
      return
    }
    const source = workflow.nodes.find((candidate) => candidate.id === sourceId)
    const target = workflow.nodes.find((candidate) => candidate.id === targetId)
    if (source && !canHaveSequenceOutgoing(source.type)) {
      addToast('This event cannot have an outgoing sequence flow.', 'error')
      return
    }
    if (target && !canReceiveSequenceFlow(target.type)) {
      addToast('Start and boundary events cannot receive sequence flows.', 'error')
      return
    }
    if (source?.parentSubprocessId !== target?.parentSubprocessId) {
      addToast('Sequence flows must stay inside the same process or subprocess scope.', 'error')
      return
    }
    const base = `${sourceId}-${targetId}`
    let id = base
    while (workflow.transitions.some((candidate) => candidate.id === id)) id = `${base}-${sequence.current++}`
    const created = transition(id, sourceId, targetId, 'Continue')
    patchWorkflow({ transitions: [...workflow.transitions, created] })
    setSelectedTransitionId(created.id)
    setSelectedNodeId('')
  }

  useEffect(() => {
    if (!connectingFrom) return
    const toCanvasPoint = (clientX: number, clientY: number) => {
      const bounds = canvasRef.current?.getBoundingClientRect()
      if (!bounds) return null
      return {
        x: Math.max(0, Math.min(CANVAS_WIDTH, (clientX - bounds.left) / zoom)),
        y: Math.max(0, Math.min(CANVAS_HEIGHT, (clientY - bounds.top) / zoom)),
      }
    }
    const handlePointerMove = (event: PointerEvent) => {
      const point = toCanvasPoint(event.clientX, event.clientY)
      if (point) setConnectionPoint(point)
    }
    const handlePointerUp = (event: PointerEvent) => {
      const target = document.elementFromPoint(event.clientX, event.clientY)
        ?.closest<HTMLElement>('[data-workflow-node-id]')
      const targetId = target?.dataset.workflowNodeId
      if (targetId) createTransitionBetween(connectingFrom, targetId)
      setConnectingFrom(null)
      setConnectionPoint(null)
    }
    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp, { once: true })
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
    }
  }, [connectingFrom, zoom])

  const fitCanvas = () => {
    const viewport = canvasViewportRef.current?.getBoundingClientRect()
    if (!viewport) return
    const next = Math.min(
      1,
      Math.max(0.45, Math.min(
        (viewport.width - 28) / CANVAS_WIDTH,
        (viewport.height - 28) / CANVAS_HEIGHT,
      )),
    )
    setZoom(Number(next.toFixed(2)))
    requestAnimationFrame(() => {
      if (canvasViewportRef.current) {
        canvasViewportRef.current.scrollLeft = 0
        canvasViewportRef.current.scrollTop = 0
      }
    })
  }

  const preview = async () => {
    if (blockingIssues.length) {
      addToast(`Resolve ${blockingIssues.length} blocking workflow issue${blockingIssues.length === 1 ? '' : 's'}.`, 'error')
      return
    }
    setIsGenerating(true)
    try {
      const result = await bridge.previewWorkflowGeneration(workflow)
      if (!result.accepted || !result.planDigest) {
        addToast(result.issues[0]?.message ?? 'Workflow preview was rejected.', 'error')
        return
      }
      setPending({ workflow: structuredClone(workflow), preview: result })
      addToast('Source-safe workflow preview is ready.', 'success')
    } finally {
      setIsGenerating(false)
    }
  }

  const apply = async () => {
    if (!pending?.preview.planDigest) return
    setIsGenerating(true)
    try {
      const result = await bridge.applyWorkflowGeneration(pending.workflow, pending.preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'Workflow apply was rejected.', 'error')
        return
      }
      addToast(`Workflow created atomically — ${result.filesChanged.length} file changed.`, 'success')
      setPending(null)
      await load(true)
    } finally {
      setIsGenerating(false)
    }
  }

  const selectExisting = async (process: GraphArtifact) => {
    setLoadingExistingId(process.id)
    setExistingLoadResult(null)
    try {
      const result = await bridge.loadWorkflowModel(
        process.sourceLocator.relativePath,
        process.semanticKey,
        process.owner.moduleId,
      )
      setExistingLoadResult(result)
      if (result.editable && result.workflow) {
        replaceWorkflow(result.workflow)
        setExistingProcessId(null)
        setSelectedNodeId(
          result.workflow.nodes.find((node) => isStartType(node.type))?.id ??
          result.workflow.nodes[0]?.id ??
          '',
        )
        setSelectedTransitionId(null)
        setPane('canvas')
        addToast(`Loaded ${result.workflow.name} for revision-safe round-trip editing.`, 'success')
      } else {
        setExistingProcessId(process.id)
        setSelectedNodeId('')
        setSelectedTransitionId(null)
        addToast(
          result.error ??
          `Read-only: ${result.unsupportedElements.join(', ') || 'the source contains unsupported BPMN semantics'}.`,
          'info',
        )
      }
    } finally {
      setLoadingExistingId(null)
    }
  }

  return (
    <div className="flex h-full min-w-0 flex-col overflow-hidden bg-surface [color-scheme:dark]">
      <header className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/70 px-3 py-2">
        <GitBranch size={15} className="text-jmix-400" />
        <div>
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">Workflow Designer</h2>
          <p className="text-[9px] text-gray-600">Entity-bound BPMN · actors · rules · services · side effects</p>
        </div>
        <button type="button" onClick={() => void load(true)} className={btn}>
          <RefreshCw size={11} className={loading ? 'animate-spin' : ''} /> Refresh project
        </button>
        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          <button
            type="button"
            onClick={() => setSimulationOpen(true)}
            disabled={!!existingProcessId || blockingIssues.length > 0}
            className={btn}
            title={blockingIssues.length ? 'Resolve blocking workflow findings before simulation' : 'Simulate actors, branches, timers, and failures'}
          >
            <Activity size={11} /> Simulate
          </button>
          <span className={`rounded border px-2 py-1 text-[9px] ${
            blockingIssues.length
              ? 'border-red-500/30 bg-red-500/5 text-red-300'
              : issues.length
                ? 'border-amber-500/30 bg-amber-500/5 text-amber-300'
                : 'border-emerald-500/25 bg-emerald-500/5 text-emerald-300'
          }`}>
            {blockingIssues.length ? `${blockingIssues.length} blocking` : issues.length ? `${issues.length} warning${issues.length === 1 ? '' : 's'}` : 'ready'}
          </span>
          <button type="button" onClick={() => void preview()} disabled={isGenerating || !!existingProcessId} className={primary}>
            {isGenerating ? <Loader2 size={11} className="animate-spin" /> : <Play size={11} />}
            Preview BPMN
          </button>
        </div>
      </header>

      {pending && (
        <div className="flex flex-wrap items-center gap-3 border-b border-amber-500/30 bg-amber-500/5 px-3 py-2">
          <ShieldCheck size={13} className="text-amber-300" />
          <div className="min-w-0 flex-1">
            <div className="text-[10px] font-medium text-amber-200">{pending.preview.label}</div>
            <div className="truncate font-mono text-[9px] text-amber-100/60">
              {pending.preview.files.map((file) => file.relativePath).join(' · ')}
            </div>
          </div>
          <button type="button" onClick={() => setPending(null)} className={btn}><X size={11} /> Discard</button>
          <button type="button" onClick={() => void apply()} disabled={isGenerating} className={primary}>
            <Save size={11} /> Apply atomic change
          </button>
        </div>
      )}

      <ResponsivePaneSwitcher
        value={pane}
        onChange={setPane}
        options={[
          { id: 'palette', label: 'Workflows & palette', badge: nodeCatalog.length },
          { id: 'canvas', label: 'Workflow canvas', badge: workflow.nodes.length },
          {
            id: 'inspector',
            label: selectedTransition ? 'Transition inspector' : selectedNode ? 'State inspector' : 'Process inspector',
            badge: issues.length || undefined,
          },
        ]}
        label="Workflow designer panels"
      />

      <div
        className="grid min-h-0 min-w-0 flex-1 grid-cols-1 overflow-hidden lg:grid-cols-[clamp(180px,20vw,260px)_minmax(0,1fr)_clamp(220px,25vw,340px)]"
      >
        <aside className={`${pane === 'palette' ? 'block' : 'hidden'} min-h-0 min-w-0 overflow-y-auto border-r border-surface-border bg-surface-light/55 lg:block`}>
          <SectionTitle title="Project workflows" count={existingProcesses.length} />
          <div className="space-y-1.5 border-b border-surface-border p-2">
            <button
              type="button"
              onClick={() => {
                replaceWorkflow(defaultWorkflow())
                setExistingProcessId(null)
                setExistingLoadResult(null)
                setSelectedNodeId('review')
                setSelectedTransitionId(null)
                setPane('canvas')
              }}
              className={`${btn} w-full`}
            >
              <Plus size={11} /> New connected workflow
            </button>
            {existingProcesses.map((process) => (
              <button
                key={process.id}
                type="button"
                onClick={() => void selectExisting(process)}
                disabled={loadingExistingId === process.id}
                className={`w-full rounded border p-2 text-left ${
                  existingProcessId === process.id
                    ? 'border-jmix-500/60 bg-jmix-500/10'
                    : 'border-surface-border bg-surface hover:border-gray-500'
                }`}
              >
                <div className="flex items-center gap-1 truncate text-[10px] font-medium text-gray-300">
                  {loadingExistingId === process.id && <Loader2 size={10} className="shrink-0 animate-spin" />}
                  <span className="truncate">{process.displayName}</span>
                </div>
                <div className="mt-1 flex items-center justify-between gap-1 text-[8px] text-gray-600">
                  <span className="truncate font-mono">{process.semanticKey}</span>
                  <span>{process.owner.moduleId}</span>
                </div>
              </button>
            ))}
            {!loading && !existingProcesses.length && (
              <p className="rounded border border-surface-border bg-surface p-2 text-[9px] leading-relaxed text-gray-500">
                No existing BPMN definitions were indexed. Create one here or add a `.bpmn20.xml` process to any module.
              </p>
            )}
          </div>

          <SectionTitle title="State palette" count={nodeCatalog.length} />
          <div className="space-y-1.5 p-2">
            {nodeCatalog.map((entry) => {
              const Icon = entry.icon
              return (
                <button
                  key={entry.type}
                  type="button"
                  draggable
                  onDragStart={(event) => {
                    event.dataTransfer.setData('application/jmix-workflow', JSON.stringify({
                      kind: 'palette',
                      type: entry.type,
                    }))
                    event.dataTransfer.effectAllowed = 'copy'
                  }}
                  onClick={() => addNode(entry.type, 360, 120 + workflow.nodes.length * 26)}
                  disabled={!!existingProcessId}
                  className="flex w-full items-start gap-2 rounded border border-surface-border bg-surface p-2 text-left transition hover:border-jmix-500/50 disabled:opacity-40"
                >
                  <Icon size={13} className={`mt-0.5 shrink-0 ${entry.accent}`} />
                  <span className="min-w-0">
                    <span className="block text-[10px] font-medium text-gray-300">{entry.label}</span>
                    <span className="block text-[8px] leading-relaxed text-gray-600">{entry.description}</span>
                  </span>
                </button>
              )
            })}
          </div>

          <SectionTitle title="Connected project context" />
          <div className="space-y-2 p-2 text-[9px]">
            <ContextCount label="Entities" value={entities.length} />
            <ContextCount label="Services" value={serviceArtifacts.length} />
            <ContextCount label="Security roles" value={roleArtifacts.length} />
            <ContextCount
              label="Views touching workflow"
              value={graph?.artifacts.filter((artifact) =>
                artifact.kind === 'VIEW_DESCRIPTOR' &&
                graph.relationships.some((relationship) =>
                  relationship.sourceArtifactId === artifact.id &&
                  relationship.type === 'PARTICIPATES_IN_WORKFLOW',
                ),
              ).length ?? 0}
            />
          </div>
        </aside>

        <main className={`${pane === 'canvas' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col overflow-hidden bg-[#171724] lg:flex`}>
          {selectedExisting ? (
            <ExistingProcessView
              process={selectedExisting}
              graph={graph}
              loadResult={existingLoadResult}
              onOpen={() => void bridge.navigateToSource(selectedExisting.sourceLocator)}
              onClone={() => {
                replaceWorkflow({
                  ...defaultWorkflow(),
                  id: `${slug(selectedExisting.semanticKey)}-v2`,
                  name: `${selectedExisting.displayName} v2`,
                  moduleId: selectedExisting.owner.moduleId,
                })
                setExistingProcessId(null)
                setExistingLoadResult(null)
                setSelectedNodeId('review')
                setSelectedTransitionId(null)
                setPane('canvas')
              }}
            />
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/40 px-3 py-2">
                <span className="text-[10px] font-semibold text-gray-300">{workflow.name}</span>
                <span className="rounded border border-surface-border bg-surface px-1.5 py-0.5 font-mono text-[9px] text-gray-500">
                  {workflow.id}
                </span>
                <span className="text-[9px] text-gray-600">
                  {workflow.nodes.length} states · {workflow.transitions.length} transitions
                </span>
                {workflow.sourceRelativePath && (
                  <span className="rounded border border-emerald-500/25 bg-emerald-500/5 px-1.5 py-0.5 text-[8px] text-emerald-300">
                    revision-safe existing source
                  </span>
                )}
                <button
                  type="button"
                  onClick={() => {
                    setSelectedNodeId('')
                    setSelectedTransitionId(null)
                  }}
                  className={btn}
                >
                  <GitBranch size={10} /> Process properties
                </button>
                <div className="flex items-center overflow-hidden rounded border border-surface-border bg-surface">
                  <button
                    type="button"
                    onClick={undoVisualChange}
                    disabled={!undoStack.length}
                    className="border-r border-surface-border p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-30"
                    title="Undo visual change (Ctrl/Cmd+Z)"
                    aria-label="Undo visual workflow change"
                  >
                    <Undo2 size={11} />
                  </button>
                  <button
                    type="button"
                    onClick={redoVisualChange}
                    disabled={!redoStack.length}
                    className="border-r border-surface-border p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-30"
                    title="Redo visual change (Ctrl/Cmd+Shift+Z)"
                    aria-label="Redo visual workflow change"
                  >
                    <Redo2 size={11} />
                  </button>
                  <button
                    type="button"
                    onClick={() => setZoom((value) => Math.max(0.4, Number((value - 0.1).toFixed(2))))}
                    className="p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200"
                    title="Zoom out"
                    aria-label="Zoom out workflow canvas"
                  >
                    <ZoomOut size={11} />
                  </button>
                  <span className="min-w-10 border-x border-surface-border px-1 text-center text-[8px] text-gray-500">
                    {Math.round(zoom * 100)}%
                  </span>
                  <button
                    type="button"
                    onClick={() => setZoom((value) => Math.min(1.5, Number((value + 0.1).toFixed(2))))}
                    className="p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200"
                    title="Zoom in"
                    aria-label="Zoom in workflow canvas"
                  >
                    <ZoomIn size={11} />
                  </button>
                  <button
                    type="button"
                    onClick={fitCanvas}
                    className="border-l border-surface-border p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200"
                    title="Fit diagram"
                    aria-label="Fit workflow diagram"
                  >
                    <Maximize2 size={11} />
                  </button>
                </div>
                <span className="ml-auto text-[8px] uppercase tracking-wider text-gray-600">
                  Drag palette items or reposition states
                </span>
              </div>
              <div ref={canvasViewportRef} className="min-h-0 flex-1 overflow-auto p-3">
                <div style={{ width: CANVAS_WIDTH * zoom, height: CANVAS_HEIGHT * zoom }}>
                <div
                  ref={canvasRef}
                  className="relative overflow-hidden rounded-lg border border-surface-border bg-[radial-gradient(circle_at_1px_1px,rgba(148,163,184,0.13)_1px,transparent_0)] [background-size:22px_22px]"
                  style={{
                    width: CANVAS_WIDTH,
                    height: CANVAS_HEIGHT,
                    transform: `scale(${zoom})`,
                    transformOrigin: 'top left',
                  }}
                  onDragOver={(event) => {
                    event.preventDefault()
                    event.dataTransfer.dropEffect = 'move'
                  }}
                  onDrop={(event) => {
                    event.preventDefault()
                    const raw = event.dataTransfer.getData('application/jmix-workflow')
                    if (!raw) return
                    const data = JSON.parse(raw) as { kind: 'palette' | 'node'; type?: WorkflowNodeType; id?: string }
                    const bounds = event.currentTarget.getBoundingClientRect()
                    const pointX = (event.clientX - bounds.left) / zoom
                    const pointY = (event.clientY - bounds.top) / zoom
                    const dragged = data.id ? workflow.nodes.find((candidate) => candidate.id === data.id) : undefined
                    const size = dragged ? nodeSize(dragged) : {
                      width: data.type && isSubprocessType(data.type) ? 360 : NODE_WIDTH,
                      height: data.type && isSubprocessType(data.type) ? 220 : NODE_HEIGHT,
                    }
                    const x = pointX - size.width / 2
                    const y = pointY - size.height / 2
                    if (data.kind === 'palette' && data.type) addNode(data.type, x, y)
                    if (data.kind === 'node' && data.id) {
                      const excluded = descendantIds(workflow, data.id)
                      excluded.add(data.id)
                      const parent = containerAtPoint(workflow, pointX, pointY, excluded)
                      patchNode(data.id, {
                        x: Math.max(0, Math.min(CANVAS_WIDTH - size.width, x)),
                        y: Math.max(0, Math.min(CANVAS_HEIGHT - size.height, y)),
                        parentSubprocessId: parent?.id,
                      })
                    }
                  }}
                >
                  {workflow.lanes.length > 0 && workflow.lanes.map((lane, index) => {
                    const laneHeight = CANVAS_HEIGHT / workflow.lanes.length
                    return (
                      <div
                        key={lane.id}
                        className="pointer-events-none absolute left-0 right-0 border-b border-slate-700/40 bg-slate-800/[0.035]"
                        style={{ top: index * laneHeight, height: laneHeight }}
                      >
                        <span className="absolute left-2 top-2 rounded bg-[#171724]/80 px-1.5 py-0.5 text-[8px] font-semibold uppercase tracking-wider text-slate-600">
                          {lane.name}
                        </span>
                      </div>
                    )
                  })}
                  <svg className="absolute inset-0 z-[1] h-full w-full" viewBox={`0 0 ${CANVAS_WIDTH} ${CANVAS_HEIGHT}`}>
                    {workflow.transitions.map((edge) => {
                      const source = workflow.nodes.find((candidate) => candidate.id === edge.sourceId)
                      const target = workflow.nodes.find((candidate) => candidate.id === edge.targetId)
                      if (!source || !target) return null
                      const routed = routeWorkflowEdge(source, target)
                      const selectEdge = (event: React.MouseEvent<SVGElement>) => {
                        event.stopPropagation()
                        setSelectedTransitionId(edge.id)
                        setSelectedNodeId('')
                      }
                      return (
                        <g key={edge.id}>
                          <path
                            data-workflow-edge-hit-id={edge.id}
                            d={routed.path}
                            stroke="transparent"
                            strokeWidth="16"
                            fill="none"
                            style={{ pointerEvents: 'stroke', cursor: 'pointer' }}
                            onClick={selectEdge}
                          />
                          <path
                            data-workflow-edge-id={edge.id}
                            d={routed.path}
                            stroke={selectedTransitionId === edge.id ? '#6c7cff' : '#64748b'}
                            strokeWidth={selectedTransitionId === edge.id ? 3 : 2}
                            fill="none"
                            style={{ pointerEvents: 'none' }}
                          />
                          <polygon
                            points={routed.arrowPoints}
                            fill={selectedTransitionId === edge.id ? '#8b9cff' : '#8790a4'}
                            stroke="#171724"
                            strokeWidth="1"
                            style={{ pointerEvents: 'none' }}
                          />
                          <text
                            x={routed.label.x}
                            y={routed.label.y}
                            textAnchor="middle"
                            fill={selectedTransitionId === edge.id ? '#aab4ff' : '#8790a4'}
                            fontSize="10"
                            style={{ pointerEvents: 'none', paintOrder: 'stroke', stroke: '#171724', strokeWidth: 4 }}
                          >
                            {edge.name}
                          </text>
                          <circle
                            cx={routed.label.x}
                            cy={routed.label.y + 9}
                            r={6}
                            fill="#2d2d3f"
                            stroke="#6c7cff"
                            className="pointer-events-auto cursor-pointer"
                            onClick={selectEdge}
                          />
                        </g>
                      )
                    })}
                    {connectingFrom && connectionPoint && (() => {
                      const source = workflow.nodes.find((candidate) => candidate.id === connectingFrom)
                      if (!source) return null
                      return (
                        <path
                          d={routeConnectionPreview(source, connectionPoint)}
                          stroke="#8b9cff"
                          strokeWidth="2.5"
                          strokeDasharray="7 5"
                          fill="none"
                        />
                      )
                    })()}
                  </svg>
                  {[...workflow.nodes].sort((left, right) =>
                    Number(isSubprocessType(right.type)) - Number(isSubprocessType(left.type)),
                  ).map((workflowNode) => {
                    const entry = nodeCatalog.find((candidate) => candidate.type === workflowNode.type)!
                    const Icon = entry.icon
                    const hasIssue = issues.some((issue) => issue.nodeId === workflowNode.id)
                    const incomingSides = new Set<NodeSide>()
                    const outgoingSides = new Set<NodeSide>()
                    workflow.transitions.forEach((candidate) => {
                      const source = workflow.nodes.find((item) => item.id === candidate.sourceId)
                      const target = workflow.nodes.find((item) => item.id === candidate.targetId)
                      if (!source || !target) return
                      const sides = edgeSides(source, target)
                      if (candidate.targetId === workflowNode.id) incomingSides.add(sides.targetSide)
                      if (candidate.sourceId === workflowNode.id) outgoingSides.add(sides.sourceSide)
                    })
                    const allSides: NodeSide[] = ['LEFT', 'RIGHT', 'TOP', 'BOTTOM']
                    const connectableSides = selectedNodeId === workflowNode.id
                      ? allSides
                      : outgoingSides.size
                        ? [...outgoingSides]
                        : ['RIGHT' as NodeSide]
                    return (
                      <div
                        key={workflowNode.id}
                        data-workflow-node-id={workflowNode.id}
                        role="button"
                        tabIndex={0}
                        draggable
                        onDragStart={(event) => {
                          event.dataTransfer.setData('application/jmix-workflow', JSON.stringify({
                            kind: 'node',
                            id: workflowNode.id,
                          }))
                          event.dataTransfer.effectAllowed = 'move'
                        }}
                        onDragOver={(event) => {
                          const raw = event.dataTransfer.types.includes('application/jmix-workflow')
                          if (raw) event.preventDefault()
                        }}
                        onDrop={(event) => {
                          const raw = event.dataTransfer.getData('application/jmix-workflow')
                          if (!raw) return
                          const data = JSON.parse(raw) as { kind: 'palette' | 'node' | 'edge'; sourceId?: string }
                          if (data.kind !== 'edge' || !data.sourceId) return
                          event.preventDefault()
                          event.stopPropagation()
                          createTransitionBetween(data.sourceId, workflowNode.id)
                        }}
                        onClick={() => {
                          setSelectedNodeId(workflowNode.id)
                          setSelectedTransitionId(null)
                          setPane('inspector')
                        }}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            setSelectedNodeId(workflowNode.id)
                            setSelectedTransitionId(null)
                            setPane('inspector')
                          }
                        }}
                        className={`group absolute flex items-start gap-2 rounded-lg border p-2.5 text-left shadow-lg transition ${
                          selectedNodeId === workflowNode.id
                            ? 'border-jmix-400 bg-jmix-500/15 ring-2 ring-jmix-500/20'
                            : connectingFrom && workflowNode.id !== connectingFrom
                              ? 'border-jmix-400/70 bg-jmix-500/10 ring-2 ring-jmix-500/10'
                            : hasIssue
                              ? 'border-amber-500/50 bg-amber-500/10'
                              : isSubprocessType(workflowNode.type)
                                ? 'border-indigo-400/45 bg-indigo-950/10 hover:border-indigo-300/70'
                                : 'border-surface-border bg-surface-light hover:border-gray-500'
                        } ${isSubprocessType(workflowNode.type) ? 'z-0 border-dashed' : 'z-10'}`}
                        style={{
                          left: workflowNode.x,
                          top: workflowNode.y,
                          width: nodeSize(workflowNode).width,
                          height: nodeSize(workflowNode).height,
                        }}
                      >
                        <Icon size={14} className={`mt-0.5 shrink-0 ${entry.accent}`} />
                        <span className="min-w-0">
                          <span className="block truncate text-[10px] font-semibold text-gray-200">{workflowNode.name}</span>
                          <span className="mt-1 block truncate font-mono text-[8px] text-gray-600">
                            {workflowNode.stateValue || workflowNode.type.replace(/_/g, ' ')}
                          </span>
                        </span>
                        {hasIssue && <AlertTriangle size={11} className="ml-auto shrink-0 text-amber-300" />}
                        {[...incomingSides].map((side) => (
                          <span
                            key={`input-${side}`}
                            data-workflow-port="input"
                            data-workflow-port-side={side.toLowerCase()}
                            aria-hidden="true"
                            className="absolute z-10 rounded-full border-2 border-slate-500 bg-surface shadow-[0_0_0_3px_rgba(23,23,36,0.8)]"
                            style={{ ...portPosition(side), width: 16, height: 16, borderWidth: 2 }}
                            title={`Input joint on ${side.toLowerCase()} edge`}
                          />
                        ))}
                        {canHaveSequenceOutgoing(workflowNode.type) && connectableSides.map((side) => (
                          <button
                            key={`output-${side}`}
                            type="button"
                            data-workflow-port="output"
                            data-workflow-port-side={side.toLowerCase()}
                            onClick={(event) => {
                              event.stopPropagation()
                              setSelectedNodeId(workflowNode.id)
                              setSelectedTransitionId(null)
                            }}
                            onPointerDown={(event) => {
                              event.preventDefault()
                              event.stopPropagation()
                              const bounds = canvasRef.current?.getBoundingClientRect()
                              setConnectingFrom(workflowNode.id)
                              setConnectionPoint(bounds ? {
                                x: (event.clientX - bounds.left) / zoom,
                                y: (event.clientY - bounds.top) / zoom,
                              } : {
                                ...nodeAnchor(workflowNode, side),
                              })
                            }}
                            className={`absolute z-20 cursor-crosshair rounded-full border-2 border-jmix-400 bg-jmix-500 shadow-[0_0_0_3px_rgba(23,23,36,0.8)] transition hover:scale-125 hover:bg-jmix-300 ${
                              outgoingSides.has(side) || selectedNodeId === workflowNode.id ? 'opacity-100' : 'opacity-45 group-hover:opacity-100'
                            }`}
                            style={{ ...portPosition(side), width: 16, height: 16, borderWidth: 2 }}
                            title={`Drag from the ${side.toLowerCase()} joint of ${workflowNode.name}`}
                            aria-label={`Connect from ${workflowNode.name} using ${side.toLowerCase()} joint`}
                          />
                        ))}
                      </div>
                    )
                  })}
                </div>
                </div>
              </div>
            </>
          )}
        </main>

        <aside className={`${pane === 'inspector' ? 'block' : 'hidden'} min-h-0 min-w-0 overflow-y-auto border-l border-surface-border bg-surface-light/55 lg:block`}>
          {selectedExisting ? (
            <ExistingInspector process={selectedExisting} graph={graph} />
          ) : selectedTransition ? (
            <TransitionInspector
              transition={selectedTransition}
              workflow={workflow}
              onPatch={(patch) => patchTransition(selectedTransition.id, patch)}
              onDelete={() => {
                patchWorkflow({
                  transitions: workflow.transitions.filter((candidate) => candidate.id !== selectedTransition.id),
                })
                setSelectedTransitionId(null)
              }}
            />
          ) : selectedNode ? (
            <NodeInspector
              node={selectedNode}
              workflow={workflow}
              roles={roleArtifacts}
              services={serviceArtifacts}
              decisions={decisionArtifacts}
              entities={entities}
              formViews={formViewArtifacts}
              connectTarget={connectTarget}
              onConnectTarget={setConnectTarget}
              onConnect={addTransition}
              onPatch={(patch) => patchNode(selectedNode.id, patch)}
              onDelete={() => removeNode(selectedNode.id)}
              onSelectTransition={(id) => {
                setSelectedTransitionId(id)
                setSelectedNodeId('')
              }}
            />
          ) : (
            <ProcessInspector
              workflow={workflow}
              entities={entities}
              selectedEntity={selectedEntity}
              issues={issues}
              onPatch={patchWorkflow}
            />
          )}
        </aside>
      </div>
      {simulationOpen && (
        <WorkflowSimulationDialog
          workflow={workflow}
          onClose={() => setSimulationOpen(false)}
          onInspectNode={(nodeId) => {
            setSelectedNodeId(nodeId)
            setSelectedTransitionId(null)
            setPane('inspector')
            setSimulationOpen(false)
          }}
        />
      )}
    </div>
  )
}

function SectionTitle({ title, count }: { title: string; count?: number }) {
  return (
    <div className="flex items-center gap-2 border-b border-surface-border px-3 py-2 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
      {title}
      {count != null && <span className="ml-auto">{count}</span>}
    </div>
  )
}

function ContextCount({ label, value }: { label: string; value: number }) {
  return (
    <div className="flex items-center justify-between rounded border border-surface-border bg-surface px-2 py-1.5">
      <span className="text-gray-500">{label}</span>
      <span className="font-semibold text-gray-300">{value}</span>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[9px] font-semibold uppercase tracking-wider text-gray-600">{label}</span>
      {children}
    </label>
  )
}

function ProcessInspector({
  workflow,
  entities,
  selectedEntity,
  issues,
  onPatch,
}: {
  workflow: WorkflowModel
  entities: SchemaEntitySnapshot[]
  selectedEntity?: SchemaEntitySnapshot
  issues: WorkflowIssue[]
  onPatch: (patch: Partial<WorkflowModel>) => void
}) {
  return (
    <>
      <SectionTitle title="Process properties" />
      <div className="space-y-3 p-3">
        <Field label="Process id">
          <input value={workflow.id} onChange={(event) => onPatch({ id: slug(event.target.value) })} className={`${input} font-mono`} />
        </Field>
        <Field label="Name">
          <input value={workflow.name} onChange={(event) => onPatch({ name: event.target.value })} className={input} />
        </Field>
        <Field label="Target module">
          <select value={workflow.moduleId} onChange={(event) => onPatch({ moduleId: event.target.value })} className={input}>
            {[...new Set(entities.map((entity) => entity.moduleId))].map((moduleId) => (
              <option key={moduleId} value={moduleId}>{moduleId}</option>
            ))}
          </select>
        </Field>
        <Field label="Business entity">
          <select
            value={workflow.entityQualifiedName ?? ''}
            onChange={(event) => {
              const entity = entities.find((candidate) => candidate.qualifiedName === event.target.value)
              onPatch({
                entityQualifiedName: event.target.value || undefined,
                moduleId: entity?.moduleId ?? workflow.moduleId,
                stateAttribute: entity?.attributes.find((attribute) =>
                  /state|status/i.test(attribute.name),
                )?.name,
              })
            }}
            className={input}
          >
            <option value="">Process variables only</option>
            {entities.map((entity) => (
              <option key={entity.artifactId} value={entity.qualifiedName}>
                {entity.className} · {entity.moduleId}
              </option>
            ))}
          </select>
        </Field>
        <Field label="State attribute">
          <select
            value={workflow.stateAttribute ?? ''}
            disabled={!selectedEntity}
            onChange={(event) => onPatch({ stateAttribute: event.target.value || undefined })}
            className={input}
          >
            <option value="">Select mapped state field</option>
            {selectedEntity?.attributes.filter((attribute) => attribute.persistent).map((attribute) => (
              <option key={attribute.artifactId} value={attribute.name}>
                {attribute.name} · {attribute.javaType}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Starter roles">
          <input
            value={joined(workflow.candidateStarterGroups)}
            onChange={(event) => onPatch({ candidateStarterGroups: csv(event.target.value) })}
            className={input}
            placeholder="hr-operator, payroll-manager"
          />
        </Field>
        <Field label="Business key expression">
          <input
            value={workflow.businessKeyExpression ?? ''}
            onChange={(event) => onPatch({ businessKeyExpression: event.target.value })}
            className={`${input} font-mono`}
            placeholder="${entity.id}"
          />
        </Field>
        <div className="grid grid-cols-2 gap-2">
          <Field label="Version tag">
            <input value={workflow.versionTag ?? ''} onChange={(event) => onPatch({ versionTag: event.target.value })} className={`${input} font-mono`} placeholder="1.0.0" />
          </Field>
          <Field label="Audit">
            <select value={workflow.auditLevel} onChange={(event) => onPatch({ auditLevel: event.target.value as WorkflowModel['auditLevel'] })} className={input}>
              <option value="BASIC">Basic</option>
              <option value="FULL">Full history</option>
              <option value="REGULATED">Regulated</option>
            </select>
          </Field>
        </div>
        <Field label="Tenant expression">
          <input value={workflow.tenantExpression ?? ''} onChange={(event) => onPatch({ tenantExpression: event.target.value })} className={`${input} font-mono`} placeholder="${tenantId}" />
        </Field>
        <Field label="Pools / lanes — id: name">
          <textarea
            value={workflow.lanes.map((lane) => `${lane.id}: ${lane.name}`).join('\n')}
            onChange={(event) => {
              const lanes = event.target.value.split('\n').map((line) => {
                const separator = line.indexOf(':')
                const rawId = separator >= 0 ? line.slice(0, separator) : line
                const rawName = separator >= 0 ? line.slice(separator + 1) : line
                const id = slug(rawId)
                const existing = workflow.lanes.find((lane) => lane.id === id)
                return {
                  id,
                  name: rawName.trim() || rawId.trim() || id,
                  actorRoleCodes: existing?.actorRoleCodes ?? [],
                }
              }).filter((lane) => lane.id)
              onPatch({ lanes })
            }}
            rows={3}
            className="w-full resize-y font-mono text-[10px]"
            placeholder={'origination: Origination\nrisk: Risk & compliance'}
          />
        </Field>
        <ListenerListEditor
          label="Process execution listeners"
          value={workflow.executionListeners}
          eventOptions={['start', 'end']}
          onChange={(executionListeners) => onPatch({ executionListeners })}
        />
        <Field label="Documentation">
          <textarea
            value={workflow.documentation ?? ''}
            onChange={(event) => onPatch({ documentation: event.target.value })}
            rows={4}
            className="w-full resize-y text-[11px]"
          />
        </Field>
      </div>
      <SectionTitle title="Readiness findings" count={issues.length} />
      <div className="space-y-1.5 p-2">
        {issues.map((issue, index) => (
          <div key={`${issue.message}-${index}`} className={`rounded border p-2 text-[9px] leading-relaxed ${
            issue.level === 'error'
              ? 'border-red-500/30 bg-red-500/5 text-red-300'
              : 'border-amber-500/30 bg-amber-500/5 text-amber-200'
          }`}>
            {issue.message}
          </div>
        ))}
        {!issues.length && (
          <div className="rounded border border-emerald-500/25 bg-emerald-500/5 p-2 text-[9px] text-emerald-300">
            The process graph is structurally ready for source-safe preview.
          </div>
        )}
      </div>
    </>
  )
}

function NodeInspector({
  node,
  workflow,
  roles,
  services,
  decisions,
  entities,
  formViews,
  connectTarget,
  onConnectTarget,
  onConnect,
  onPatch,
  onDelete,
  onSelectTransition,
}: {
  node: WorkflowNodeModel
  workflow: WorkflowModel
  roles: GraphArtifact[]
  services: GraphArtifact[]
  decisions: GraphArtifact[]
  entities: SchemaEntitySnapshot[]
  formViews: GraphArtifact[]
  connectTarget: string
  onConnectTarget: (id: string) => void
  onConnect: () => void
  onPatch: (patch: Partial<WorkflowNodeModel>) => void
  onDelete: () => void
  onSelectTransition: (id: string) => void
}) {
  const outgoing = workflow.transitions.filter((transition) => transition.sourceId === node.id)
  return (
    <>
      <SectionTitle title="State inspector" />
      <div className="space-y-3 p-3">
        <Field label="State type">
          <select value={node.type} onChange={(event) => onPatch({ type: event.target.value as WorkflowNodeType })} className={input}>
            {nodeCatalog.map((entry) => <option key={entry.type} value={entry.type}>{entry.label}</option>)}
          </select>
        </Field>
        <Field label="Id">
          <input value={node.id} disabled className={`${input} font-mono opacity-60`} title="Ids are stable after creation to protect transitions." />
        </Field>
        <Field label="Display name">
          <input value={node.name} onChange={(event) => onPatch({ name: event.target.value })} className={input} />
        </Field>
        <Field label="Entity state value">
          <input value={node.stateValue ?? ''} onChange={(event) => onPatch({ stateValue: event.target.value.toUpperCase() })} className={`${input} font-mono`} />
        </Field>
        <Field label="Pool / lane">
          <select value={node.laneId ?? ''} onChange={(event) => onPatch({ laneId: event.target.value || undefined })} className={input}>
            <option value="">Unassigned</option>
            {workflow.lanes.map((lane) => <option key={lane.id} value={lane.id}>{lane.name}</option>)}
          </select>
        </Field>
        <Field label="BPMN scope">
          <select
            value={node.parentSubprocessId ?? ''}
            onChange={(event) => onPatch({ parentSubprocessId: event.target.value || undefined })}
            className={input}
          >
            <option value="">Top-level process</option>
            {workflow.nodes.filter((candidate) =>
              candidate.id !== node.id &&
              isSubprocessType(candidate.type) &&
              !descendantIds(workflow, node.id).has(candidate.id),
            ).map((candidate) => (
              <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
            ))}
          </select>
        </Field>
        {isSubprocessType(node.type) && (
          <div className="space-y-2 rounded border border-indigo-500/25 bg-indigo-500/5 p-2">
            <div className="text-[9px] leading-relaxed text-indigo-200/80">
              Nodes dropped inside this container become direct BPMN children. Sequence flows remain scope-safe.
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Canvas width">
                <input type="number" min={240} max={900} value={node.width} onChange={(event) => onPatch({ width: Number(event.target.value) })} className={input} />
              </Field>
              <Field label="Canvas height">
                <input type="number" min={160} max={600} value={node.height} onChange={(event) => onPatch({ height: Number(event.target.value) })} className={input} />
              </Field>
            </div>
          </div>
        )}
        {node.type === 'HUMAN_STATE' && (
          <>
            <Field label="Actor roles">
              <div className="max-h-32 space-y-1 overflow-y-auto rounded border border-surface-border bg-surface p-2">
                {roles.map((role) => (
                  <label key={role.id} className="flex items-center gap-2 text-[9px] text-gray-400">
                    <input
                      type="checkbox"
                      checked={node.actorRoleCodes.includes(role.displayName)}
                      onChange={(event) => onPatch({
                        actorRoleCodes: event.target.checked
                          ? [...node.actorRoleCodes, role.displayName]
                          : node.actorRoleCodes.filter((code) => code !== role.displayName),
                      })}
                    />
                    <span className="truncate">{role.displayName}</span>
                  </label>
                ))}
                {!roles.length && <span className="text-[9px] text-amber-300">No indexed roles; enter an assignee expression.</span>}
              </div>
            </Field>
            <Field label="Assignee expression">
              <input value={node.assigneeExpression ?? ''} onChange={(event) => onPatch({ assigneeExpression: event.target.value })} className={`${input} font-mono`} placeholder="${manager.username}" />
            </Field>
            <Field label="Due date">
              <input value={node.dueDate ?? ''} onChange={(event) => onPatch({ dueDate: event.target.value })} className={`${input} font-mono`} placeholder="PT3D" />
            </Field>
            <Field label="Task priority">
              <input value={node.priority ?? ''} onChange={(event) => onPatch({ priority: event.target.value })} className={`${input} font-mono`} placeholder="${riskPriority}" />
            </Field>
            <Field label="Minimum approvals / quorum">
              <input
                type="number"
                min={1}
                value={node.minimumApprovals ?? ''}
                onChange={(event) => onPatch({ minimumApprovals: event.target.value ? Number(event.target.value) : undefined })}
                className={input}
                placeholder="1"
              />
            </Field>
            <Field label="Segregation of duty — cannot match actor from">
              <select
                multiple
                value={node.segregationOfDutyNodeIds}
                onChange={(event) => onPatch({
                  segregationOfDutyNodeIds: [...event.target.selectedOptions].map((option) => option.value),
                })}
                className={`${input} min-h-20`}
              >
                {workflow.nodes.filter((candidate) =>
                  candidate.id !== node.id && candidate.type === 'HUMAN_STATE',
                ).map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}
              </select>
            </Field>
          </>
        )}
        {(isStartType(node.type) || node.type === 'HUMAN_STATE') && (
          <WorkflowFormEditor
            form={node.formData}
            legacyFormKey={node.formKey}
            processVariables={node.processVariables}
            allowOutcomes={node.type === 'HUMAN_STATE'}
            allowBusinessKey={isStartType(node.type)}
            formViews={formViews}
            onFormChange={(formData) => onPatch({ formData, formKey: undefined })}
            onLegacyFormKeyChange={(formKey) => onPatch({ formKey, formData: undefined })}
            onVariablesChange={(processVariables) => onPatch({ processVariables })}
          />
        )}
        {node.parentSubprocessId &&
          workflow.nodes.find((candidate) => candidate.id === node.parentSubprocessId)?.type === 'EVENT_SUBPROCESS' &&
          node.type !== 'START' && isStartType(node.type) && (
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.eventStartInterrupting} onChange={(event) => onPatch({ eventStartInterrupting: event.target.checked })} />
              Interrupt the enclosing subprocess scope when triggered
            </label>
          )}
        {node.type === 'AUTOMATED_STATE' && (
          <>
            <Field label="Spring service bean">
              <input
                list="workflow-services"
                value={node.serviceBean ?? ''}
                onChange={(event) => onPatch({ serviceBean: event.target.value })}
                className={`${input} font-mono`}
                placeholder="loanWorkflowService"
              />
              <datalist id="workflow-services">
                {services.map((service) => <option key={service.id} value={service.displayName} />)}
              </datalist>
            </Field>
            <Field label="Transactional method">
              <input value={node.serviceMethod ?? ''} onChange={(event) => onPatch({ serviceMethod: event.target.value })} className={`${input} font-mono`} placeholder="approveAndDisburse" />
            </Field>
            <Field label="Idempotency key expression">
              <input value={node.idempotencyKeyExpression ?? ''} onChange={(event) => onPatch({ idempotencyKeyExpression: event.target.value })} className={`${input} font-mono`} placeholder="${businessKey + ':disburse'}" />
            </Field>
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.triggerable} onChange={(event) => onPatch({ triggerable: event.target.checked })} />
              Wait for an external completion trigger
            </label>
          </>
        )}
        {node.type === 'SCRIPT_STATE' && (
          <div className="space-y-3 rounded border border-purple-500/20 bg-purple-500/5 p-2">
            <div className="text-[9px] leading-relaxed text-purple-200/80">
              Jmix executes script tasks as Groovy on the server. Keep financial and security-critical rules in reviewed services or decision tables.
            </div>
            <Field label="Groovy script">
              <textarea
                value={node.script ?? ''}
                onChange={(event) => onPatch({ script: event.target.value })}
                rows={8}
                className="w-full resize-y font-mono text-[10px]"
                placeholder={'def result = amount.setScale(2)\nreturn result'}
              />
            </Field>
            <Field label="Optional result variable">
              <input
                value={node.resultVariable ?? ''}
                onChange={(event) => onPatch({ resultVariable: event.target.value })}
                className={`${input} font-mono`}
                placeholder="calculatedAmount"
              />
            </Field>
          </div>
        )}
        {node.type === 'ENTITY_DATA_STATE' && (
          <div className="space-y-3 rounded border border-cyan-500/20 bg-cyan-500/5 p-2">
            <div className="text-[9px] leading-relaxed text-cyan-100/80">
              Native Jmix entity-data task. It compiles to Flowable service-task fields understood by the Jmix BPM runtime.
            </div>
            <Field label="Entity operation">
              <select
                value={node.entityDataOperation}
                onChange={(event) => onPatch({
                  entityDataOperation: event.target.value as WorkflowNodeModel['entityDataOperation'],
                  entityAttributesJson: node.entityAttributesJson || '[]',
                  jpqlParametersJson: node.jpqlParametersJson || '[]',
                })}
                className={input}
              >
                <option value="LOAD">Load entities with JPQL</option>
                <option value="CREATE">Create entity</option>
                <option value="MODIFY">Modify process-variable entity</option>
              </select>
            </Field>
            <Field label="Indexed Jmix entity">
              <select
                value={node.entityName ?? ''}
                onChange={(event) => {
                  const selected = entities.find((entity) => entity.entityName === event.target.value)
                  onPatch({
                    entityName: event.target.value,
                    jpql: node.entityDataOperation === 'LOAD' && selected
                      ? `select e from ${selected.entityName} e`
                      : node.jpql,
                  })
                }}
                className={input}
              >
                <option value="">Select entity…</option>
                {entities.map((entity) => (
                  <option key={entity.artifactId} value={entity.entityName}>
                    {entity.className} · {entity.moduleId} · {entity.storeName}
                  </option>
                ))}
              </select>
            </Field>
            {node.entityDataOperation === 'LOAD' ? (
              <>
                <Field label="Read-only JPQL">
                  <textarea
                    value={node.jpql ?? ''}
                    onChange={(event) => onPatch({ jpql: event.target.value })}
                    rows={4}
                    className="w-full resize-y font-mono text-[10px]"
                    placeholder="select e from app_Order e where e.status = :status"
                  />
                </Field>
                <Field label="Result process variable">
                  <input value={node.resultVariable ?? ''} onChange={(event) => onPatch({ resultVariable: event.target.value })} className={`${input} font-mono`} placeholder="orders" />
                </Field>
                <Field label="Save result as">
                  <select value={node.saveLoadResultAs} onChange={(event) => onPatch({ saveLoadResultAs: event.target.value as WorkflowNodeModel['saveLoadResultAs'] })} className={input}>
                    <option value="SINGLE">Single entity</option>
                    <option value="COLLECTION">Collection</option>
                  </select>
                </Field>
                <Field label="JPQL parameters (Jmix JSON array)">
                  <textarea value={node.jpqlParametersJson ?? '[]'} onChange={(event) => onPatch({ jpqlParametersJson: event.target.value })} rows={4} className="w-full resize-y font-mono text-[10px]" placeholder={'[{"name":"status","valueType":"processVariable","value":"targetStatus"}]'} />
                </Field>
              </>
            ) : (
              <>
                {node.entityDataOperation === 'MODIFY' ? (
                  <Field label="Entity process variable">
                    <input value={node.entityVariable ?? ''} onChange={(event) => onPatch({ entityVariable: event.target.value })} className={`${input} font-mono`} placeholder="loanApp" />
                  </Field>
                ) : (
                  <Field label="Optional result process variable">
                    <input value={node.resultVariable ?? ''} onChange={(event) => onPatch({ resultVariable: event.target.value })} className={`${input} font-mono`} placeholder="createdEntity" />
                  </Field>
                )}
                <Field label="Entity attributes (Jmix JSON array)">
                  <textarea value={node.entityAttributesJson ?? '[]'} onChange={(event) => onPatch({ entityAttributesJson: event.target.value })} rows={6} className="w-full resize-y font-mono text-[10px]" placeholder={'[{"name":"status","valueType":"directValue","value":"NEW"}]'} />
                </Field>
              </>
            )}
          </div>
        )}
        {node.type === 'EMAIL_STATE' && (
          <div className="space-y-3 rounded border border-cyan-500/20 bg-cyan-500/5 p-2">
            <div className="text-[9px] leading-relaxed text-cyan-100/80">
              Native Jmix email task. Values may be literals or Flowable expressions and are emitted as the public <span className="font-mono">jmix-send-email</span> field contract.
            </div>
            <Field label="To">
              <input value={node.emailTo ?? ''} onChange={(event) => onPatch({ emailTo: event.target.value })} className={`${input} font-mono`} placeholder="${recipientEmail}" />
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Cc">
                <input value={node.emailCc ?? ''} onChange={(event) => onPatch({ emailCc: event.target.value })} className={`${input} font-mono`} />
              </Field>
              <Field label="Bcc">
                <input value={node.emailBcc ?? ''} onChange={(event) => onPatch({ emailBcc: event.target.value })} className={`${input} font-mono`} />
              </Field>
            </div>
            <Field label="From">
              <input value={node.emailFrom ?? ''} onChange={(event) => onPatch({ emailFrom: event.target.value })} className={`${input} font-mono`} placeholder="${mailFrom}" />
            </Field>
            <Field label="Subject">
              <input value={node.emailSubject ?? ''} onChange={(event) => onPatch({ emailSubject: event.target.value })} className={input} />
            </Field>
            <Field label="Content">
              <textarea value={node.emailContent ?? ''} onChange={(event) => onPatch({ emailContent: event.target.value })} rows={6} className="w-full resize-y font-mono text-[10px]" />
            </Field>
            <Field label="Content type">
              <select value={node.emailContentType} onChange={(event) => onPatch({ emailContentType: event.target.value as WorkflowNodeModel['emailContentType'] })} className={input}>
                <option value="HTML">HTML · text/html</option>
                <option value="PLAIN_TEXT">Plain text · text/plain</option>
              </select>
            </Field>
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.emailSendAsync} onChange={(event) => onPatch({ emailSendAsync: event.target.checked })} />
              Send asynchronously through Jmix email queue
            </label>
            <Field label="Attachments">
              <div className="space-y-2">
                {node.emailAttachments.map((attachment, index) => (
                  <div key={`${attachment.id}-${index}`} className="grid grid-cols-[0.7fr_0.8fr_1.4fr_auto] gap-1">
                    <input value={attachment.id} onChange={(event) => onPatch({
                      emailAttachments: node.emailAttachments.map((item, itemIndex) =>
                        itemIndex === index ? { ...item, id: event.target.value } : item),
                    })} className={`${input} font-mono`} placeholder="id" />
                    <input value={attachment.name ?? ''} onChange={(event) => onPatch({
                      emailAttachments: node.emailAttachments.map((item, itemIndex) =>
                        itemIndex === index ? { ...item, name: event.target.value || undefined } : item),
                    })} className={input} placeholder="name" />
                    <input value={attachment.expression} onChange={(event) => onPatch({
                      emailAttachments: node.emailAttachments.map((item, itemIndex) =>
                        itemIndex === index ? { ...item, expression: event.target.value } : item),
                    })} className={`${input} font-mono`} placeholder="${documentFileRef}" />
                    <button type="button" onClick={() => onPatch({
                      emailAttachments: node.emailAttachments.filter((_, itemIndex) => itemIndex !== index),
                    })} className={btn}><X size={10} /></button>
                  </div>
                ))}
                <button type="button" onClick={() => onPatch({
                  emailAttachments: [...node.emailAttachments, {
                    id: `attachment-${node.emailAttachments.length + 1}`,
                    expression: '${attachment}',
                  }],
                })} className={`${btn} w-full`}><Plus size={10} /> Add attachment</button>
              </div>
            </Field>
          </div>
        )}
        {node.type === 'BUSINESS_RULE_STATE' && (
          <Field label="Deployed DMN decision table key">
            <input
              list="workflow-indexed-dmn-decisions"
              value={node.decisionTableKey ?? ''}
              onChange={(event) => onPatch({ decisionTableKey: event.target.value })}
              className={`${input} font-mono`}
              placeholder="loan-risk-decision"
            />
            <datalist id="workflow-indexed-dmn-decisions">
              {decisions.map((decision) => (
                <option key={decision.id} value={decision.semanticKey}>{decision.displayName} · {decision.owner.moduleId}</option>
              ))}
            </datalist>
            <p className={`mt-1 text-[8px] ${
              !node.decisionTableKey || decisions.some((decision) => decision.semanticKey === node.decisionTableKey)
                ? 'text-gray-600'
                : 'text-amber-300'
            }`}>
              {!node.decisionTableKey
                ? `${decisions.length} indexed decision table(s) available.`
                : decisions.some((decision) => decision.semanticKey === node.decisionTableKey)
                  ? 'Resolved to an indexed production-resource DMN decision.'
                  : 'No indexed DMN decision currently resolves this key; deployed external decisions remain allowed.'}
            </p>
          </Field>
        )}
        {node.type === 'CALL_ACTIVITY' && (
          <>
            <Field label="Called process key">
              <input value={node.calledElement ?? ''} onChange={(event) => onPatch({ calledElement: event.target.value })} className={`${input} font-mono`} placeholder="kyc-review-process" />
            </Field>
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.inheritBusinessKey} onChange={(event) => onPatch({ inheritBusinessKey: event.target.checked })} />
              Inherit business key
            </label>
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.inheritVariables} onChange={(event) => onPatch({ inheritVariables: event.target.checked })} />
              Inherit all process variables
            </label>
          </>
        )}
        {(node.type === 'TIMER_START' || node.type === 'TIMER_EVENT' || node.type === 'BOUNDARY_TIMER') && (
          <>
            <Field label="Timer type">
              <select value={node.timerType} onChange={(event) => onPatch({ timerType: event.target.value as WorkflowNodeModel['timerType'] })} className={input}>
                <option value="DURATION">Duration</option>
                <option value="DATE">Date</option>
                <option value="CYCLE">Cycle</option>
              </select>
            </Field>
            <Field label="ISO-8601 timer or expression">
              <input value={node.timerExpression ?? ''} onChange={(event) => onPatch({ timerExpression: event.target.value })} className={`${input} font-mono`} placeholder="PT24H or ${slaDuration}" />
            </Field>
          </>
        )}
        {(node.type === 'MESSAGE_START' || node.type === 'MESSAGE_CATCH' || node.type === 'BOUNDARY_MESSAGE') && (
          <Field label="Message definition id">
            <input value={node.eventReference ?? ''} onChange={(event) => onPatch({ eventReference: slug(event.target.value) })} className={`${input} font-mono`} placeholder="loan-payment-received" />
          </Field>
        )}
        {(node.type === 'SIGNAL_START' || node.type === 'SIGNAL_CATCH' ||
          node.type === 'SIGNAL_THROW' || node.type === 'BOUNDARY_SIGNAL') && (
          <>
            <Field label="Signal definition id">
              <input value={node.eventReference ?? ''} onChange={(event) => onPatch({ eventReference: slug(event.target.value) })} className={`${input} font-mono`} placeholder="portfolio-freeze" />
            </Field>
            <Field label="Signal scope">
              <select value={node.signalScope} onChange={(event) => onPatch({ signalScope: event.target.value as WorkflowNodeModel['signalScope'] })} className={input}>
                <option value="GLOBAL">Global broadcast</option>
                <option value="PROCESS_INSTANCE">Process instance</option>
              </select>
            </Field>
            {node.type === 'SIGNAL_THROW' && (
              <label className="flex items-center gap-2 text-[9px] text-gray-400">
                <input type="checkbox" checked={node.async} onChange={(event) => onPatch({ async: event.target.checked })} />
                Publish asynchronously after transaction commit
              </label>
            )}
          </>
        )}
        {node.type === 'COMPENSATION_THROW' && (
          <Field label="Compensate only this activity (optional)">
            <select value={node.compensationActivityRef ?? ''} onChange={(event) => onPatch({ compensationActivityRef: event.target.value || undefined })} className={input}>
              <option value="">Entire current scope</option>
              {workflow.nodes.filter((candidate) => isAttachableActivity(candidate.type)).map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
              ))}
            </select>
          </Field>
        )}
        {isBoundaryType(node.type) && (
          <>
            <Field label="Attached activity">
              <select value={node.attachedToNodeId ?? ''} onChange={(event) => onPatch({ attachedToNodeId: event.target.value })} className={input}>
                <option value="">Select activity…</option>
                {workflow.nodes.filter((candidate) =>
                  candidate.id !== node.id && isAttachableActivity(candidate.type),
                ).map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}
              </select>
            </Field>
            {!isCompensationBoundary(node.type) && (
              <label className="flex items-center gap-2 text-[9px] text-gray-400">
                <input type="checkbox" checked={node.cancelActivity} onChange={(event) => onPatch({ cancelActivity: event.target.checked })} />
                Interrupt attached activity
              </label>
            )}
          </>
        )}
        {(node.type === 'ERROR_START' || node.type === 'BOUNDARY_ERROR' || node.type === 'ERROR_END') && (
          <Field label="BPMN error reference">
            <input value={node.eventReference ?? ''} onChange={(event) => onPatch({ eventReference: event.target.value })} className={`${input} font-mono`} placeholder="risk-policy-violation" />
          </Field>
        )}
        {node.type === 'BOUNDARY_COMPENSATION' && (
          <Field label="Compensation handler">
            <select value={node.compensationHandlerNodeId ?? ''} onChange={(event) => onPatch({ compensationHandlerNodeId: event.target.value || undefined })} className={input}>
              <option value="">Select handler activity…</option>
              {workflow.nodes.filter((candidate) =>
                candidate.id !== node.id && isAttachableActivity(candidate.type),
              ).map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.name}</option>)}
            </select>
          </Field>
        )}
        {isAttachableActivity(node.type) && (
          <label className="flex items-center gap-2 text-[9px] text-gray-400">
            <input type="checkbox" checked={node.forCompensation} onChange={(event) => onPatch({ forCompensation: event.target.checked })} />
            This activity is a compensation handler
          </label>
        )}
        {(node.type === 'DECISION' || node.type === 'INCLUSIVE_GATEWAY') && (
          <Field label="Default transition">
            <select value={node.defaultTransitionId ?? ''} onChange={(event) => onPatch({ defaultTransitionId: event.target.value || undefined })} className={input}>
              <option value="">No default</option>
              {outgoing.map((edge) => <option key={edge.id} value={edge.id}>{edge.name || edge.id}</option>)}
            </select>
          </Field>
        )}
        {supportsAsync(node.type) && (
          <div className="space-y-2 rounded border border-surface-border bg-surface p-2">
            <label className="flex items-center gap-2 text-[9px] text-gray-400">
              <input type="checkbox" checked={node.async} onChange={(event) => onPatch({ async: event.target.checked })} />
              Async continuation / durable transaction boundary
            </label>
            {node.async && (
              <>
                <label className="flex items-center gap-2 text-[9px] text-gray-400">
                  <input type="checkbox" checked={node.exclusive} onChange={(event) => onPatch({ exclusive: event.target.checked })} />
                  Exclusive job per process instance
                </label>
                <Field label="Failed-job retry cycle">
                  <input value={node.retryCycle ?? ''} onChange={(event) => onPatch({ retryCycle: event.target.value })} className={`${input} font-mono`} placeholder="R5/PT5M" />
                </Field>
              </>
            )}
          </div>
        )}
        {supportsMultiInstance(node.type) && (
          <div className="space-y-2 rounded border border-surface-border bg-surface p-2">
            <Field label="Multi-instance execution">
              <select value={node.multiInstanceMode} onChange={(event) => onPatch({ multiInstanceMode: event.target.value as WorkflowNodeModel['multiInstanceMode'] })} className={input}>
                <option value="NONE">Single</option>
                <option value="SEQUENTIAL">Sequential</option>
                <option value="PARALLEL">Parallel</option>
              </select>
            </Field>
            {node.multiInstanceMode !== 'NONE' && (
              <>
                <Field label="Collection expression">
                  <input value={node.collectionExpression ?? ''} onChange={(event) => onPatch({ collectionExpression: event.target.value })} className={`${input} font-mono`} placeholder="${approvers}" />
                </Field>
                <Field label="Element variable">
                  <input value={node.elementVariable ?? ''} onChange={(event) => onPatch({ elementVariable: event.target.value })} className={`${input} font-mono`} placeholder="approver" />
                </Field>
                <Field label="Completion / quorum expression">
                  <input value={node.completionCondition ?? ''} onChange={(event) => onPatch({ completionCondition: event.target.value })} className={`${input} font-mono`} placeholder="${nrOfCompletedInstances >= 2}" />
                </Field>
              </>
            )}
          </div>
        )}
        <ListenerListEditor
          label="Execution listeners"
          value={node.executionListeners}
          eventOptions={['start', 'end', 'take']}
          onChange={(executionListeners) => onPatch({ executionListeners })}
        />
        {node.type === 'HUMAN_STATE' && (
          <ListenerListEditor
            label="User-task listeners"
            value={node.taskListeners}
            eventOptions={['create', 'assignment', 'complete', 'delete']}
            onChange={(taskListeners) => onPatch({ taskListeners })}
          />
        )}
        {node.type === 'CALL_ACTIVITY' && (
          <>
            <VariableMappingEditor
              label="Input variable mappings"
              value={node.inputMappings}
              onChange={(inputMappings) => onPatch({ inputMappings })}
            />
            <VariableMappingEditor
              label="Output variable mappings"
              value={node.outputMappings}
              onChange={(outputMappings) => onPatch({ outputMappings })}
            />
          </>
        )}
        <ListField label="Required documents" value={node.requiredDocuments} onChange={(value) => onPatch({ requiredDocuments: value })} />
        <ListField label="Server validations" value={node.validationRules} onChange={(value) => onPatch({ validationRules: value })} />
        <ListField label="Side effects" value={node.sideEffects} onChange={(value) => onPatch({ sideEffects: value })} />
        <ListField label="Notifications" value={node.notifications} onChange={(value) => onPatch({ notifications: value })} />
        <ListField label="Required permissions" value={node.requiredPermissions} onChange={(value) => onPatch({ requiredPermissions: value })} />
        <Field label="Documentation">
          <textarea value={node.documentation ?? ''} onChange={(event) => onPatch({ documentation: event.target.value })} rows={3} className="w-full resize-y text-[11px]" />
        </Field>
        <button type="button" onClick={onDelete} className={`${btn} w-full text-red-300 hover:border-red-500/50`}>
          <Trash2 size={11} /> Delete state and connected transitions
        </button>
      </div>
      <SectionTitle title="Outgoing transitions" count={outgoing.length} />
      <div className="space-y-1.5 p-2">
        {outgoing.map((edge) => (
          <button key={edge.id} type="button" onClick={() => onSelectTransition(edge.id)} className="w-full rounded border border-surface-border bg-surface p-2 text-left hover:border-jmix-500/50">
            <div className="flex items-center gap-1 text-[9px] text-gray-300">
              <GitMerge size={10} className="text-jmix-400" />
              <span className="truncate">{edge.name || edge.id}</span>
            </div>
            <div className="mt-1 truncate font-mono text-[8px] text-gray-600">→ {edge.targetId}</div>
          </button>
        ))}
        {canHaveSequenceOutgoing(node.type) && (
          <div className="grid grid-cols-[1fr_auto] gap-1.5">
            <select value={connectTarget} onChange={(event) => onConnectTarget(event.target.value)} className={input}>
              <option value="">Connect to…</option>
              {workflow.nodes.filter((candidate) =>
                candidate.id !== node.id && canReceiveSequenceFlow(candidate.type),
              ).map((candidate) => (
                <option key={candidate.id} value={candidate.id}>{candidate.name}</option>
              ))}
            </select>
            <button type="button" onClick={onConnect} disabled={!connectTarget} className={btn}><Plus size={11} /></button>
          </div>
        )}
      </div>
    </>
  )
}

function TransitionInspector({
  transition: edge,
  workflow,
  onPatch,
  onDelete,
}: {
  transition: WorkflowTransitionModel
  workflow: WorkflowModel
  onPatch: (patch: Partial<WorkflowTransitionModel>) => void
  onDelete: () => void
}) {
  return (
    <>
      <SectionTitle title="Transition inspector" />
      <div className="space-y-3 p-3">
        <div className="rounded border border-surface-border bg-surface p-2 text-[9px] text-gray-400">
          <span className="font-mono">{edge.sourceId}</span> → <span className="font-mono">{edge.targetId}</span>
        </div>
        <Field label="Transition name">
          <input value={edge.name ?? ''} onChange={(event) => onPatch({ name: event.target.value })} className={input} />
        </Field>
        <Field label="Target state">
          <select value={edge.targetId} onChange={(event) => onPatch({ targetId: event.target.value })} className={input}>
            {workflow.nodes.filter((node) =>
              node.id !== edge.sourceId && canReceiveSequenceFlow(node.type),
            ).map((node) => (
              <option key={node.id} value={node.id}>{node.name}</option>
            ))}
          </select>
        </Field>
        <Field label="Flowable condition">
          <textarea
            value={edge.conditionExpression ?? ''}
            onChange={(event) => onPatch({ conditionExpression: event.target.value })}
            rows={3}
            className="w-full resize-y font-mono text-[10px]"
            placeholder="${approved == true}"
          />
        </Field>
        <Field label="Form outcome id">
          <input value={edge.outcomeId ?? ''} onChange={(event) => onPatch({ outcomeId: event.target.value })} className={`${input} font-mono`} placeholder="approve" />
        </Field>
        <ListField label="Required actor roles" value={edge.requiredRoleCodes} onChange={(value) => onPatch({ requiredRoleCodes: value })} />
        <ListField label="Required documents" value={edge.requiredDocuments} onChange={(value) => onPatch({ requiredDocuments: value })} />
        <ListField label="Server validations" value={edge.validationRules} onChange={(value) => onPatch({ validationRules: value })} />
        <ListField label="Transactional side effects" value={edge.sideEffects} onChange={(value) => onPatch({ sideEffects: value })} />
        <ListField label="Notifications" value={edge.notifications} onChange={(value) => onPatch({ notifications: value })} />
        <button type="button" onClick={onDelete} className={`${btn} w-full text-red-300 hover:border-red-500/50`}>
          <Trash2 size={11} /> Delete transition
        </button>
      </div>
    </>
  )
}

function ListField({ label, value, onChange }: { label: string; value: string[]; onChange: (value: string[]) => void }) {
  return (
    <Field label={label}>
      <textarea
        value={joined(value)}
        onChange={(event) => onChange(csv(event.target.value))}
        rows={2}
        className="w-full resize-y text-[10px]"
        placeholder="Comma or line separated"
      />
    </Field>
  )
}

function ListenerListEditor({
  label,
  value,
  eventOptions,
  onChange,
}: {
  label: string
  value: WorkflowListenerModel[]
  eventOptions: string[]
  onChange: (value: WorkflowListenerModel[]) => void
}) {
  const patch = (index: number, next: Partial<WorkflowListenerModel>) => {
    onChange(value.map((listener, candidateIndex) =>
      candidateIndex === index ? { ...listener, ...next } : listener,
    ))
  }
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[9px] font-semibold uppercase tracking-wider text-gray-600">{label}</span>
        <button
          type="button"
          onClick={() => onChange([...value, {
            event: eventOptions[0] ?? 'start',
            implementationType: 'EXPRESSION',
            implementation: '',
          }])}
          className="rounded border border-surface-border p-1 text-gray-500 hover:border-jmix-500/50 hover:text-jmix-300"
          aria-label={`Add ${label.toLowerCase()}`}
        >
          <Plus size={10} />
        </button>
      </div>
      {value.map((listener, index) => (
        <div key={`${listener.event}-${index}`} className="space-y-1 rounded border border-surface-border bg-surface p-1.5">
          <div className="grid grid-cols-[0.8fr_1.2fr_auto] gap-1">
            <select value={listener.event} onChange={(event) => patch(index, { event: event.target.value })} className="min-w-0 py-1 text-[9px]">
              {eventOptions.map((event) => <option key={event} value={event}>{event}</option>)}
            </select>
            <select
              value={listener.implementationType}
              onChange={(event) => patch(index, {
                implementationType: event.target.value as WorkflowListenerModel['implementationType'],
              })}
              className="min-w-0 py-1 text-[9px]"
            >
              <option value="EXPRESSION">Expression</option>
              <option value="DELEGATE_EXPRESSION">Delegate</option>
              <option value="CLASS">Java class</option>
            </select>
            <button type="button" onClick={() => onChange(value.filter((_, candidateIndex) => candidateIndex !== index))} className="p-1 text-red-300" aria-label="Remove listener">
              <Trash2 size={10} />
            </button>
          </div>
          <input
            value={listener.implementation}
            onChange={(event) => patch(index, { implementation: event.target.value })}
            className={`${input} font-mono`}
            placeholder="${auditService.onEvent(execution)}"
          />
        </div>
      ))}
      {!value.length && <div className="text-[8px] text-gray-600">No listeners configured.</div>}
    </div>
  )
}

function VariableMappingEditor({
  label,
  value,
  onChange,
}: {
  label: string
  value: WorkflowVariableMapping[]
  onChange: (value: WorkflowVariableMapping[]) => void
}) {
  const patch = (index: number, next: Partial<WorkflowVariableMapping>) => {
    onChange(value.map((mapping, candidateIndex) =>
      candidateIndex === index ? { ...mapping, ...next } : mapping,
    ))
  }
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[9px] font-semibold uppercase tracking-wider text-gray-600">{label}</span>
        <button
          type="button"
          onClick={() => onChange([...value, { source: '', target: '' }])}
          className="rounded border border-surface-border p-1 text-gray-500 hover:border-jmix-500/50 hover:text-jmix-300"
          aria-label={`Add ${label.toLowerCase()}`}
        >
          <Plus size={10} />
        </button>
      </div>
      {value.map((mapping, index) => {
        const expressionMode = mapping.sourceExpression != null
        return (
          <div key={index} className="grid grid-cols-[0.75fr_1fr_1fr_auto] gap-1 rounded border border-surface-border bg-surface p-1.5">
            <select
              value={expressionMode ? 'expression' : 'variable'}
              onChange={(event) => patch(index, event.target.value === 'expression'
                ? { source: undefined, sourceExpression: mapping.source ?? '' }
                : { source: mapping.sourceExpression ?? '', sourceExpression: undefined })}
              className="min-w-0 py-1 text-[8px]"
            >
              <option value="variable">Variable</option>
              <option value="expression">Expression</option>
            </select>
            <input
              value={expressionMode ? mapping.sourceExpression ?? '' : mapping.source ?? ''}
              onChange={(event) => patch(index, expressionMode
                ? { sourceExpression: event.target.value }
                : { source: event.target.value })}
              className="min-w-0 py-1 font-mono text-[9px]"
              placeholder={expressionMode ? '${entity.id}' : 'source'}
            />
            <input
              value={mapping.target}
              onChange={(event) => patch(index, { target: event.target.value })}
              className="min-w-0 py-1 font-mono text-[9px]"
              placeholder="target"
            />
            <button type="button" onClick={() => onChange(value.filter((_, candidateIndex) => candidateIndex !== index))} className="p-1 text-red-300" aria-label="Remove variable mapping">
              <Trash2 size={10} />
            </button>
          </div>
        )
      })}
      {!value.length && <div className="text-[8px] text-gray-600">No variable mappings configured.</div>}
    </div>
  )
}

function WorkflowFormEditor({
  form,
  legacyFormKey,
  processVariables,
  allowOutcomes,
  allowBusinessKey,
  formViews,
  onFormChange,
  onLegacyFormKeyChange,
  onVariablesChange,
}: {
  form?: WorkflowFormData
  legacyFormKey?: string
  processVariables: WorkflowProcessVariable[]
  allowOutcomes: boolean
  allowBusinessKey: boolean
  formViews: GraphArtifact[]
  onFormChange: (form?: WorkflowFormData) => void
  onLegacyFormKeyChange: (formKey?: string) => void
  onVariablesChange: (variables: WorkflowProcessVariable[]) => void
}) {
  const createForm = (type: WorkflowFormData['type']): WorkflowFormData => ({
    type,
    openMode: 'DIALOG',
    fields: [],
    outcomes: [],
  })
  const patchForm = (patch: Partial<WorkflowFormData>) => {
    onFormChange({ ...(form ?? createForm('NO_FORM')), ...patch })
  }
  return (
    <div className="space-y-2 rounded border border-surface-border bg-surface p-2">
      <Field label="Jmix process form">
        <select
          value={form?.type ?? (legacyFormKey ? 'LEGACY' : 'UNCONFIGURED')}
          onChange={(event) => {
            if (event.target.value === 'UNCONFIGURED') {
              onFormChange(undefined)
              onLegacyFormKeyChange(undefined)
            } else if (event.target.value === 'LEGACY') {
              onFormChange(undefined)
            } else {
              onLegacyFormKeyChange(undefined)
              onFormChange(createForm(event.target.value as WorkflowFormData['type']))
            }
          }}
          className={input}
        >
          <option value="UNCONFIGURED">Not configured</option>
          <option value="NO_FORM">No form / API completion</option>
          <option value="INPUT_DIALOG">Generated input dialog</option>
          <option value="JMIX_VIEW">Jmix FlowUI process view</option>
          <option value="CUSTOM">Custom form adapter</option>
          <option value="LEGACY">Legacy Flowable form key</option>
        </select>
      </Field>
      {(form?.type === 'JMIX_VIEW' || form?.type === 'CUSTOM') && (
        <Field label={form.type === 'JMIX_VIEW' ? 'Process view id' : 'Custom form id'}>
          <input
            list="workflow-form-views"
            value={form.screenId ?? ''}
            onChange={(event) => patchForm({ screenId: event.target.value })}
            className={`${input} font-mono`}
            placeholder="loan-approval-task-form"
          />
          <datalist id="workflow-form-views">
            {formViews.map((view) => <option key={view.id} value={view.semanticKey} />)}
          </datalist>
        </Field>
      )}
      {form && form.type !== 'NO_FORM' && (
        <Field label="Open mode">
          <select value={form.openMode} onChange={(event) => patchForm({ openMode: event.target.value as WorkflowFormData['openMode'] })} className={input}>
            <option value="DIALOG">Dialog</option>
            <option value="NAVIGATE">Navigate</option>
          </select>
        </Field>
      )}
      {legacyFormKey != null && (
        <Field label="Legacy Flowable form key">
          <input value={legacyFormKey} onChange={(event) => onLegacyFormKeyChange(event.target.value || undefined)} className={`${input} font-mono`} />
        </Field>
      )}
      {allowBusinessKey && form && form.type !== 'NO_FORM' && (
        <div className="grid grid-cols-2 gap-1.5">
          <Field label="Business key source">
            <input value={form.businessKeySource ?? ''} onChange={(event) => patchForm({ businessKeySource: event.target.value })} className={`${input} font-mono`} placeholder="expression" />
          </Field>
          <Field label="Business key">
            <input value={form.businessKey ?? ''} onChange={(event) => patchForm({ businessKey: event.target.value })} className={`${input} font-mono`} placeholder="${loan.id}" />
          </Field>
        </div>
      )}
      {form?.type === 'INPUT_DIALOG' && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-[9px] font-semibold uppercase tracking-wider text-gray-600">
            Form fields
            <button
              type="button"
              onClick={() => patchForm({
                fields: [...form.fields, {
                  id: `field-${form.fields.length + 1}`,
                  caption: 'New field',
                  type: 'string',
                  editable: true,
                  required: false,
                  properties: {},
                }],
              })}
              className="rounded border border-surface-border p-1 text-gray-500 hover:text-jmix-300"
              aria-label="Add input-dialog field"
            >
              <Plus size={10} />
            </button>
          </div>
          {form.fields.map((field, index) => {
            const patchField = (patch: Partial<typeof field>) => patchForm({
              fields: form.fields.map((candidate, candidateIndex) =>
                candidateIndex === index ? { ...candidate, ...patch } : candidate,
              ),
            })
            const entityField = field.type === 'entity' || field.type === 'entity-list'
            return (
              <div key={`${field.id}-${index}`} className="space-y-1 rounded border border-surface-border bg-surface-light/50 p-1.5">
                <div className="grid grid-cols-[1fr_1fr_auto] gap-1">
                  <input value={field.id} onChange={(event) => patchField({ id: slug(event.target.value) })} className="min-w-0 py-1 font-mono text-[9px]" placeholder="variable" />
                  <select value={field.type} onChange={(event) => patchField({ type: event.target.value })} className="min-w-0 py-1 text-[9px]">
                    {['string', 'multiline-string', 'decimal', 'long', 'boolean', 'date', 'dateTime', 'entity', 'entity-list', 'file', 'platform-enum', 'custom-enum'].map((type) => (
                      <option key={type} value={type}>{type}</option>
                    ))}
                  </select>
                  <button type="button" onClick={() => patchForm({ fields: form.fields.filter((_, candidateIndex) => candidateIndex !== index) })} className="p-1 text-red-300" aria-label="Remove form field">
                    <Trash2 size={10} />
                  </button>
                </div>
                <input value={field.caption} onChange={(event) => patchField({ caption: event.target.value })} className="w-full py-1 text-[9px]" placeholder="Caption" />
                <div className="flex gap-3 text-[8px] text-gray-500">
                  <label className="flex items-center gap-1"><input type="checkbox" checked={field.editable} onChange={(event) => patchField({ editable: event.target.checked })} /> Editable</label>
                  <label className="flex items-center gap-1"><input type="checkbox" checked={field.required} onChange={(event) => patchField({ required: event.target.checked })} /> Required</label>
                </div>
                {entityField && (
                  <div className="grid grid-cols-2 gap-1">
                    <input
                      value={field.properties.entityName ?? ''}
                      onChange={(event) => patchField({ properties: { ...field.properties, entityName: event.target.value } })}
                      className="min-w-0 py-1 font-mono text-[8px]"
                      placeholder="Entity name"
                    />
                    <select
                      value={field.properties.uiComponent ?? 'entityPicker'}
                      onChange={(event) => patchField({ properties: { ...field.properties, uiComponent: event.target.value } })}
                      className="min-w-0 py-1 text-[8px]"
                    >
                      <option value="entityPicker">Entity picker</option>
                      <option value="comboBox">Combo box</option>
                    </select>
                    {field.properties.uiComponent === 'comboBox' && (
                      <textarea
                        value={field.properties.query ?? ''}
                        onChange={(event) => patchField({ properties: { ...field.properties, query: event.target.value } })}
                        className="col-span-2 min-h-12 w-full resize-y font-mono text-[8px]"
                        placeholder="select e from Entity e order by e.name"
                      />
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
      {allowOutcomes && form && form.type !== 'NO_FORM' && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-[9px] font-semibold uppercase tracking-wider text-gray-600">
            Form outcomes
            <button
              type="button"
              onClick={() => patchForm({
                outcomes: [...form.outcomes, {
                  id: `outcome-${form.outcomes.length + 1}`,
                  caption: 'Outcome',
                  icon: 'CHECK',
                }],
              })}
              className="rounded border border-surface-border p-1 text-gray-500 hover:text-jmix-300"
              aria-label="Add form outcome"
            >
              <Plus size={10} />
            </button>
          </div>
          {form.outcomes.map((outcome, index) => {
            const patchOutcome = (patch: Partial<typeof outcome>) => patchForm({
              outcomes: form.outcomes.map((candidate, candidateIndex) =>
                candidateIndex === index ? { ...candidate, ...patch } : candidate,
              ),
            })
            return (
              <div key={`${outcome.id}-${index}`} className="grid grid-cols-[1fr_1fr_0.7fr_auto] gap-1">
                <input value={outcome.id} onChange={(event) => patchOutcome({ id: slug(event.target.value) })} className="min-w-0 py-1 font-mono text-[8px]" placeholder="approve" />
                <input value={outcome.caption} onChange={(event) => patchOutcome({ caption: event.target.value })} className="min-w-0 py-1 text-[8px]" placeholder="Approve" />
                <input value={outcome.icon ?? ''} onChange={(event) => patchOutcome({ icon: event.target.value })} className="min-w-0 py-1 font-mono text-[8px]" placeholder="CHECK" />
                <button type="button" onClick={() => patchForm({ outcomes: form.outcomes.filter((_, candidateIndex) => candidateIndex !== index) })} className="p-1 text-red-300" aria-label="Remove outcome">
                  <Trash2 size={10} />
                </button>
              </div>
            )
          })}
        </div>
      )}
      {allowBusinessKey && (
        <div className="space-y-1.5">
          <div className="flex items-center justify-between text-[9px] font-semibold uppercase tracking-wider text-gray-600">
            Declared process variables
            <button
              type="button"
              onClick={() => onVariablesChange([...processVariables, { name: `variable${processVariables.length + 1}`, type: 'string' }])}
              className="rounded border border-surface-border p-1 text-gray-500 hover:text-jmix-300"
              aria-label="Add process variable"
            >
              <Plus size={10} />
            </button>
          </div>
          {processVariables.map((variable, index) => (
            <div key={`${variable.name}-${index}`} className="grid grid-cols-[1fr_1fr_auto] gap-1">
              <input
                value={variable.name}
                onChange={(event) => onVariablesChange(processVariables.map((candidate, candidateIndex) =>
                  candidateIndex === index ? { ...candidate, name: event.target.value } : candidate,
                ))}
                className="min-w-0 py-1 font-mono text-[8px]"
                placeholder="variable name"
              />
              <input
                value={variable.type}
                onChange={(event) => onVariablesChange(processVariables.map((candidate, candidateIndex) =>
                  candidateIndex === index ? { ...candidate, type: event.target.value } : candidate,
                ))}
                className="min-w-0 py-1 font-mono text-[8px]"
                placeholder="string"
              />
              <button type="button" onClick={() => onVariablesChange(processVariables.filter((_, candidateIndex) => candidateIndex !== index))} className="p-1 text-red-300" aria-label="Remove process variable">
                <Trash2 size={10} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function ExistingProcessView({
  process,
  graph,
  loadResult,
  onOpen,
  onClone,
}: {
  process: GraphArtifact
  graph: ApplicationGraphResponse | null
  loadResult: WorkflowLoadResponse | null
  onOpen: () => void
  onClone: () => void
}) {
  const stateIds = new Set(
    graph?.relationships.filter((relationship) =>
      relationship.sourceArtifactId === process.id &&
      relationship.type === 'PARTICIPATES_IN_WORKFLOW' &&
      relationship.targetArtifactId,
    ).map((relationship) => relationship.targetArtifactId!) ?? [],
  )
  const states = graph?.artifacts.filter((artifact) => stateIds.has(artifact.id)) ?? []
  const transitions = graph?.relationships.filter((relationship) =>
    stateIds.has(relationship.sourceArtifactId) &&
    !!relationship.targetArtifactId &&
    stateIds.has(relationship.targetArtifactId) &&
    relationship.type === 'TRANSITIONS_TO',
  ) ?? []
  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light/40 px-3 py-2">
        <FileCode2 size={12} className="text-jmix-400" />
        <span className="text-[10px] font-semibold text-gray-300">{process.displayName}</span>
        <span className="rounded border border-surface-border bg-surface px-1.5 py-0.5 font-mono text-[9px] text-gray-500">{process.semanticKey}</span>
        <span className="text-[9px] text-gray-600">{states.length} states · {transitions.length} transitions · {process.owner.moduleId}</span>
        <button type="button" onClick={onClone} className={`${btn} ml-auto`}><GitBranch size={11} /> Clone as new version</button>
        <button type="button" onClick={onOpen} className={btn}><ExternalLink size={11} /> Open source</button>
      </div>
      {loadResult && !loadResult.editable && (
        <div className="border-b border-amber-500/25 bg-amber-500/5 px-3 py-2 text-[9px] leading-relaxed text-amber-200">
          Round-trip editing is locked to protect source semantics.
          {loadResult.error
            ? ` ${loadResult.error}`
            : ` Unsupported: ${loadResult.unsupportedElements.join(', ') || 'unrecognized BPMN extensions'}.`}
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-auto p-5">
        <div className="mx-auto grid max-w-4xl grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-3">
          {states.map((state) => (
            <div key={state.id} className="rounded-lg border border-surface-border bg-surface-light p-3">
              <div className="text-[10px] font-semibold text-gray-300">{state.displayName}</div>
              <div className="mt-1 font-mono text-[8px] text-gray-600">{state.semanticKey.split('#').pop()}</div>
              <div className="mt-2 text-[8px] uppercase tracking-wider text-jmix-400">{state.summary}</div>
              <div className="mt-2 space-y-1">
                {transitions.filter((edge) => edge.sourceArtifactId === state.id).map((edge) => {
                  const target = states.find((candidate) => candidate.id === edge.targetArtifactId)
                  return (
                    <div key={`${edge.sourceArtifactId}-${edge.targetArtifactId}`} className="text-[8px] text-gray-500">
                      → {target?.displayName ?? 'unresolved target'}
                    </div>
                  )
                })}
              </div>
            </div>
          ))}
          {!states.length && (
            <div className="rounded border border-amber-500/30 bg-amber-500/5 p-3 text-[10px] text-amber-200">
              This process was indexed, but its state graph is incomplete. Open the source to inspect unsupported BPMN elements.
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function ExistingInspector({ process, graph }: { process: GraphArtifact; graph: ApplicationGraphResponse | null }) {
  const incoming = graph?.relationships.filter((relationship) => relationship.targetArtifactId === process.id) ?? []
  const connected = incoming.map((relationship) =>
    graph?.artifacts.find((artifact) => artifact.id === relationship.sourceArtifactId),
  ).filter((artifact): artifact is GraphArtifact => !!artifact)
  return (
    <>
      <SectionTitle title="Existing process impact" />
      <div className="space-y-2 p-3">
        <ContextCount label="Connected artifacts" value={connected.length} />
        <ContextCount label="Diagnostics" value={process.diagnostics.length} />
        <div className="rounded border border-surface-border bg-surface p-2">
          <div className="text-[8px] uppercase tracking-wider text-gray-600">Source</div>
          <div className="mt-1 break-all font-mono text-[9px] text-gray-400">{process.sourceLocator.relativePath}</div>
        </div>
        {connected.map((artifact) => (
          <button
            key={artifact.id}
            type="button"
            onClick={() => void bridge.navigateToSource(artifact.sourceLocator)}
            className="w-full rounded border border-surface-border bg-surface p-2 text-left hover:border-jmix-500/50"
          >
            <div className="text-[9px] font-medium text-gray-300">{artifact.displayName}</div>
            <div className="mt-1 text-[8px] uppercase tracking-wider text-gray-600">{artifact.kind.replace(/_/g, ' ')}</div>
          </button>
        ))}
      </div>
    </>
  )
}

interface WorkflowIssue {
  level: 'error' | 'warning'
  message: string
  nodeId?: string
}

function isJsonArray(value: string | undefined, optional = false) {
  if (!value?.trim()) return optional
  try {
    return Array.isArray(JSON.parse(value))
  } catch {
    return false
  }
}

function validate(
  workflow: WorkflowModel,
  entity: SchemaEntitySnapshot | undefined,
  roleArtifacts: GraphArtifact[],
): WorkflowIssue[] {
  const issues: WorkflowIssue[] = []
  if (!/^[A-Za-z][A-Za-z0-9_-]{2,127}$/.test(workflow.id)) {
    issues.push({ level: 'error', message: 'Process id must be 3-128 safe BPMN identifier characters.' })
  }
  const startNodes = workflow.nodes.filter((node) => !node.parentSubprocessId && isStartType(node.type))
  if (!startNodes.length || startNodes.filter((node) => node.type === 'START').length > 1) {
    issues.push({ level: 'error', message: 'The top-level process requires a start event, with no more than one plain start.' })
  }
  if (!workflow.nodes.some((node) => isTerminalType(node.type))) {
    issues.push({ level: 'error', message: 'At least one terminal state is required.' })
  }
  if (workflow.entityQualifiedName && !entity) {
    issues.push({ level: 'error', message: 'The bound business entity is no longer indexed.' })
  }
  if (entity && (!workflow.stateAttribute || !entity.attributes.some((attribute) => attribute.name === workflow.stateAttribute))) {
    issues.push({ level: 'warning', message: 'Select a persistent entity state/status field to make workflow state visible outside BPM runtime.' })
  }
  const outgoing = new Set(workflow.transitions.map((transition) => transition.sourceId))
  const incoming = new Set(workflow.transitions.map((transition) => transition.targetId))
  const nodesById = new Map(workflow.nodes.map((node) => [node.id, node]))
  const laneIds = workflow.lanes.map((lane) => lane.id)
  if (new Set(laneIds).size !== laneIds.length) {
    issues.push({ level: 'error', message: 'Lane ids must be unique.' })
  }
  workflow.executionListeners.forEach((listener) => {
    if (!['start', 'end'].includes(listener.event) || !listener.implementation.trim()) {
      issues.push({ level: 'error', message: 'Process listeners require a start/end event and implementation.' })
    }
  })
  workflow.nodes.forEach((node) => {
    if (node.parentSubprocessId && !isSubprocessType(nodesById.get(node.parentSubprocessId)?.type as WorkflowNodeType)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} references a missing subprocess scope.` })
    }
    const seenParents = new Set([node.id])
    let parentId = node.parentSubprocessId
    while (parentId && !seenParents.has(parentId)) {
      seenParents.add(parentId)
      parentId = nodesById.get(parentId)?.parentSubprocessId
    }
    if (parentId) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} participates in a subprocess parent cycle.` })
    }
    if (canHaveSequenceOutgoing(node.type) && !outgoing.has(node.id)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has no outgoing transition.` })
    }
    if (!canHaveSequenceOutgoing(node.type) && outgoing.has(node.id)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} cannot have an outgoing sequence flow.` })
    }
    if (node.type === 'HUMAN_STATE' && !node.actorRoleCodes.length && !node.assigneeExpression) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has no actor role or assignee expression.` })
    }
    if (node.type === 'AUTOMATED_STATE' && !node.serviceBean) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has no transactional Spring service bean.` })
    }
    if (node.type === 'SCRIPT_STATE' && !node.script?.trim()) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a non-empty Groovy script.` })
    }
    if (node.type === 'SCRIPT_STATE' && node.resultVariable &&
      !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(node.resultVariable)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has an invalid result process variable.` })
    }
    if (node.type === 'ENTITY_DATA_STATE') {
      if (node.entityDataOperation === 'LOAD') {
        if (!node.jpql?.trim().toLowerCase().startsWith('select ') ||
          /\b(update|delete|insert|merge|drop|alter|truncate)\b/i.test(node.jpql ?? '')) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires bounded read-only JPQL beginning with select.` })
        }
        if (!node.resultVariable || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(node.resultVariable)) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a valid result process variable.` })
        }
        if (!isJsonArray(node.jpqlParametersJson, true)) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} JPQL parameters must be a JSON array.` })
        }
      } else {
        if (!node.entityName || !/^[A-Za-z_][A-Za-z0-9_.]{0,255}$/.test(node.entityName)) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires an indexed Jmix entity name.` })
        }
        if (node.entityDataOperation === 'MODIFY' &&
          (!node.entityVariable || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(node.entityVariable))) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires the entity process variable to modify.` })
        }
        if (!isJsonArray(node.entityAttributesJson)) {
          issues.push({ level: 'error', nodeId: node.id, message: `${node.name} entity attributes must be a JSON array.` })
        }
      }
    }
    if (node.type === 'BUSINESS_RULE_STATE' && !node.decisionTableKey) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} must reference a deployed DMN decision table.` })
    }
    if (node.type === 'CALL_ACTIVITY' && !node.calledElement) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} must reference a reusable process key.` })
    }
    if (node.type === 'EMAIL_STATE' && (
      !node.emailTo?.trim() || !node.emailSubject?.trim() || !node.emailContent?.trim() ||
      node.emailAttachments.some((attachment) => !attachment.id.trim() || !attachment.expression.trim())
    )) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires recipients, subject, content, and valid attachment expressions.` })
    }
    if (node.type === 'EMAIL_STATE' && node.multiInstanceMode !== 'NONE') {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} cannot be multi-instance in the Jmix email task contract.` })
    }
    if ((node.type === 'TIMER_START' || node.type === 'TIMER_EVENT' || node.type === 'BOUNDARY_TIMER') && !node.timerExpression) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires an ISO-8601 timer or runtime expression.` })
    }
    if (isBoundaryType(node.type)) {
      if (!node.attachedToNodeId || !workflow.nodes.some((candidate) =>
        candidate.id === node.attachedToNodeId &&
        isAttachableActivity(candidate.type) &&
        candidate.parentSubprocessId === node.parentSubprocessId,
      )) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} must attach to an existing activity.` })
      }
      if (incoming.has(node.id)) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} is a boundary event and cannot have an incoming sequence flow.` })
      }
    }
    if (node.type === 'BOUNDARY_CANCEL' && (
      nodesById.get(node.attachedToNodeId ?? '')?.type !== 'TRANSACTION_SUBPROCESS' ||
      !node.cancelActivity
    )) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} must interrupt a transaction subprocess.` })
    }
    if (node.type === 'CANCEL_END' &&
      nodesById.get(node.parentSubprocessId ?? '')?.type !== 'TRANSACTION_SUBPROCESS') {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} is valid only inside a transaction subprocess.` })
    }
    if (node.type === 'ERROR_START' &&
      nodesById.get(node.parentSubprocessId ?? '')?.type !== 'EVENT_SUBPROCESS') {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} is valid only inside an event subprocess.` })
    }
    if ((node.type === 'ERROR_START' || node.type === 'BOUNDARY_ERROR' || node.type === 'ERROR_END') && !node.eventReference) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a typed BPMN error reference.` })
    }
    if ((node.type === 'MESSAGE_START' || node.type === 'MESSAGE_CATCH' || node.type === 'BOUNDARY_MESSAGE') && !node.eventReference) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a message definition.` })
    }
    if ((node.type === 'SIGNAL_START' || node.type === 'SIGNAL_CATCH' ||
      node.type === 'SIGNAL_THROW' || node.type === 'BOUNDARY_SIGNAL') && !node.eventReference) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a signal definition.` })
    }
    if (node.type === 'BOUNDARY_COMPENSATION' &&
      (!node.compensationHandlerNodeId || !workflow.nodes.some((candidate) =>
        candidate.id === node.compensationHandlerNodeId && isAttachableActivity(candidate.type),
      ))) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a compensation handler activity.` })
    }
    if (node.laneId && !workflow.lanes.some((lane) => lane.id === node.laneId)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} references a missing lane.` })
    }
    node.executionListeners.forEach((listener) => {
      if (!['start', 'end', 'take'].includes(listener.event) || !listener.implementation.trim()) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has an invalid execution listener.` })
      }
    })
    node.taskListeners.forEach((listener) => {
      if (node.type !== 'HUMAN_STATE' ||
        !['create', 'assignment', 'complete', 'delete'].includes(listener.event) ||
        !listener.implementation.trim()) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has an invalid user-task listener.` })
      }
    })
    ;[...node.inputMappings, ...node.outputMappings].forEach((mapping) => {
      if (node.type !== 'CALL_ACTIVITY' || !mapping.target.trim() ||
        (!!mapping.source === !!mapping.sourceExpression)) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has an invalid call-activity variable mapping.` })
      }
    })
    if (node.processVariables.length) {
      if (!isStartType(node.type) ||
        new Set(node.processVariables.map((variable) => variable.name)).size !== node.processVariables.length ||
        node.processVariables.some((variable) => !variable.name.trim() || !variable.type.trim())) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has invalid or duplicate process-variable declarations.` })
      }
    }
    if (node.formData) {
      const form = node.formData
      if ((!isStartType(node.type) && node.type !== 'HUMAN_STATE') || node.formKey) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has conflicting or unsupported form configuration.` })
      }
      if ((form.type === 'JMIX_VIEW' || form.type === 'CUSTOM') && !form.screenId?.trim()) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a process view/custom form id.` })
      }
      if (form.type !== 'INPUT_DIALOG' && form.fields.length) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} can define inline fields only for an input-dialog form.` })
      }
      if (node.type !== 'HUMAN_STATE' && form.outcomes.length) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} can define form outcomes only on a human task.` })
      }
      if (new Set(form.fields.map((field) => field.id)).size !== form.fields.length ||
        form.fields.some((field) => !field.id.trim() || !field.caption.trim() || !field.type.trim())) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has invalid or duplicate input-dialog fields.` })
      }
      if (new Set(form.outcomes.map((outcome) => outcome.id)).size !== form.outcomes.length ||
        form.outcomes.some((outcome) => !outcome.id.trim() || !outcome.caption.trim())) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has invalid or duplicate form outcomes.` })
      }
    }
    if (node.async && supportsAsync(node.type) && !node.retryCycle) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} is asynchronous but has no explicit retry cycle.` })
    }
    if (node.type === 'AUTOMATED_STATE' && node.async && !node.idempotencyKeyExpression) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} is retryable and requires an idempotency key.` })
    }
    if (node.multiInstanceMode !== 'NONE' && !node.loopCardinality && !node.collectionExpression) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires a loop cardinality or collection expression.` })
    }
    if (node.multiInstanceMode !== 'NONE' && !supportsMultiInstance(node.type)) {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} does not support multi-instance execution in Jmix BPM.` })
    }
    if (node.minimumApprovals && node.minimumApprovals > 1 && node.multiInstanceMode === 'NONE') {
      issues.push({ level: 'error', nodeId: node.id, message: `${node.name} requires multi-instance execution for an approval quorum.` })
    }
    node.segregationOfDutyNodeIds.forEach((guardedNodeId) => {
      if (!workflow.nodes.some((candidate) => candidate.id === guardedNodeId && candidate.type === 'HUMAN_STATE')) {
        issues.push({ level: 'error', nodeId: node.id, message: `${node.name} has an invalid segregation-of-duty reference.` })
      }
    })
    node.actorRoleCodes.filter((code) =>
      roleArtifacts.length > 0 && !roleArtifacts.some((role) => role.displayName === code || role.semanticKey === code),
    ).forEach((code) => {
      issues.push({ level: 'warning', nodeId: node.id, message: `${node.name} references role “${code}”, which is not indexed in source roles.` })
    })
    if (node.stateValue && workflow.nodes.some((candidate) =>
      candidate.id !== node.id && candidate.stateValue === node.stateValue,
    )) {
      issues.push({ level: 'warning', nodeId: node.id, message: `Entity state value ${node.stateValue} is used by more than one workflow state.` })
    }
  })
  workflow.nodes.filter((node) => isSubprocessType(node.type)).forEach((subprocess) => {
    const children = workflow.nodes.filter((node) => node.parentSubprocessId === subprocess.id)
    if (!children.length) {
      issues.push({ level: 'error', nodeId: subprocess.id, message: `${subprocess.name} has no contained BPMN elements.` })
      return
    }
    if (subprocess.type === 'EVENT_SUBPROCESS') {
      const eventStarts = children.filter((node) =>
        node.type === 'MESSAGE_START' || node.type === 'SIGNAL_START' ||
        node.type === 'TIMER_START' || node.type === 'ERROR_START')
      if (children.some((node) => node.type === 'START') || !eventStarts.length) {
        issues.push({ level: 'error', nodeId: subprocess.id, message: `${subprocess.name} requires an event start and cannot contain a plain start.` })
      }
    } else if (children.filter((node) => node.type === 'START').length !== 1) {
      issues.push({ level: 'error', nodeId: subprocess.id, message: `${subprocess.name} requires exactly one direct plain start event.` })
    }
  })
  const signalScopes = new Map<string, WorkflowNodeModel['signalScope']>()
  workflow.nodes.filter((node) =>
    node.type === 'SIGNAL_START' || node.type === 'SIGNAL_CATCH' ||
    node.type === 'SIGNAL_THROW' || node.type === 'BOUNDARY_SIGNAL',
  ).forEach((node) => {
    if (!node.eventReference) return
    const existing = signalScopes.get(node.eventReference)
    if (existing && existing !== node.signalScope) {
      issues.push({ level: 'error', nodeId: node.id, message: `Signal ${node.eventReference} uses conflicting scopes.` })
    }
    signalScopes.set(node.eventReference, node.signalScope)
  })
  workflow.transitions.forEach((edge) => {
    if (!workflow.nodes.some((node) => node.id === edge.sourceId) ||
      !workflow.nodes.some((node) => node.id === edge.targetId)) {
      issues.push({ level: 'error', message: `Transition ${edge.id} references a missing state.` })
    }
    const source = workflow.nodes.find((node) => node.id === edge.sourceId)
    const target = workflow.nodes.find((node) => node.id === edge.targetId)
    if (source && target && source.parentSubprocessId !== target.parentSubprocessId) {
      issues.push({ level: 'error', nodeId: source.id, message: `${edge.name || edge.id} crosses a BPMN scope boundary.` })
    }
    if (source?.type === 'EVENT_SUBPROCESS' || target?.type === 'EVENT_SUBPROCESS') {
      issues.push({ level: 'error', nodeId: source?.id, message: `${edge.name || edge.id} cannot connect externally to an event subprocess.` })
    }
    if ((source?.type === 'DECISION' || source?.type === 'INCLUSIVE_GATEWAY') &&
      source.defaultTransitionId !== edge.id && !edge.conditionExpression) {
      issues.push({ level: 'error', nodeId: source.id, message: `${edge.name || edge.id} requires a condition or must be the gateway default.` })
    }
  })
  return issues
}
