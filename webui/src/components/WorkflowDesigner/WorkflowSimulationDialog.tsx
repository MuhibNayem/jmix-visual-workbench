import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, CheckCircle2, Clock3, GitBranch, Play, RefreshCw, ShieldCheck,
  StepForward, UserRound, X, Zap,
} from 'lucide-react'
import type {
  WorkflowModel,
  WorkflowNodeModel,
  WorkflowTransitionModel,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

type SimulationPane = 'context' | 'tokens' | 'trace'

interface SimulationToken {
  id: string
  nodeId: string
  previousNodeId?: string
  waiting?: boolean
  scopeStack?: string[]
  completedSubprocessId?: string
}

interface SimulationLog {
  id: number
  level: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR'
  title: string
  detail: string
  nodeId?: string
}

interface SimulationSession {
  tokens: SimulationToken[]
  logs: SimulationLog[]
  failedNodeIds: string[]
  status: 'READY' | 'RUNNING' | 'COMPLETED' | 'FAILED'
}

const input = 'w-full min-w-0 rounded border border-surface-border bg-surface px-2.5 py-2 text-xs text-gray-100 outline-none focus:border-jmix-500'
const taskTypes = new Set([
  'AUTOMATED_STATE',
  'SCRIPT_STATE',
  'ENTITY_DATA_STATE',
  'EMAIL_STATE',
  'BUSINESS_RULE_STATE',
  'CALL_ACTIVITY',
])
const humanType = 'HUMAN_STATE'
const messageTypes = new Set(['MESSAGE_START', 'MESSAGE_CATCH', 'SIGNAL_START', 'SIGNAL_CATCH', 'ERROR_START'])
const timerTypes = new Set(['TIMER_START', 'TIMER_EVENT'])
const terminalTypes = new Set(['TERMINAL', 'ERROR_END', 'CANCEL_END', 'TERMINATE_END'])
const subprocessTypes = new Set(['EMBEDDED_SUBPROCESS', 'EVENT_SUBPROCESS', 'TRANSACTION_SUBPROCESS'])

function startNodes(workflow: WorkflowModel) {
  return workflow.nodes.filter((node) =>
    (node.type === 'START' || node.type === 'MESSAGE_START' || node.type === 'SIGNAL_START' ||
      node.type === 'TIMER_START' || node.type === 'ERROR_START') &&
    (!node.parentSubprocessId ||
      workflow.nodes.find((parent) => parent.id === node.parentSubprocessId)?.type === 'EVENT_SUBPROCESS'),
  )
}

function transitionLabel(transition: WorkflowTransitionModel, workflow: WorkflowModel) {
  const target = workflow.nodes.find((node) => node.id === transition.targetId)
  return transition.name || transition.outcomeId || target?.name || transition.id
}

function logClass(level: SimulationLog['level']) {
  if (level === 'ERROR') return 'border-red-500/30 bg-red-500/10 text-red-200'
  if (level === 'WARNING') return 'border-amber-500/30 bg-amber-500/10 text-amber-200'
  if (level === 'SUCCESS') return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
  return 'border-surface-border bg-surface text-gray-300'
}

export default function WorkflowSimulationDialog({
  workflow,
  onClose,
  onInspectNode,
}: {
  workflow: WorkflowModel
  onClose: () => void
  onInspectNode: (nodeId: string) => void
}) {
  const starts = useMemo(() => startNodes(workflow), [workflow])
  const [startNodeId, setStartNodeId] = useState(starts[0]?.id ?? '')
  const [username, setUsername] = useState('simulation-user')
  const [rolesText, setRolesText] = useState(
    [...new Set(workflow.nodes.flatMap((node) => node.actorRoleCodes))].join(', '),
  )
  const [businessDate, setBusinessDate] = useState(new Date().toISOString().slice(0, 16))
  const [failureNodeId, setFailureNodeId] = useState('')
  const [selectedTokenId, setSelectedTokenId] = useState('')
  const [selectedTransitions, setSelectedTransitions] = useState<string[]>([])
  const [pane, setPane] = useState<SimulationPane>('tokens')
  const logSequence = useRef(0)
  const tokenSequence = useRef(0)
  const [session, setSession] = useState<SimulationSession>({
    tokens: [],
    logs: [],
    failedNodeIds: [],
    status: 'READY',
  })

  const roles = useMemo(
    () => rolesText.split(/[\n,]/).map((role) => role.trim()).filter(Boolean),
    [rolesText],
  )
  const selectedToken = session.tokens.find((token) => token.id === selectedTokenId)
    ?? session.tokens.find((token) => !token.waiting)
    ?? session.tokens[0]
  const selectedNode = workflow.nodes.find((node) => node.id === selectedToken?.nodeId)
  const outgoing = selectedNode
    ? workflow.transitions.filter((transition) => transition.sourceId === selectedNode.id)
    : []
  const requiresBranchChoice = outgoing.length > 1 &&
    selectedNode?.type !== 'PARALLEL_GATEWAY'

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  const nextToken = (
    nodeId: string,
    previousNodeId?: string,
    scopeStack: string[] = [],
    completedSubprocessId?: string,
  ): SimulationToken => ({
    id: `token-${++tokenSequence.current}`,
    nodeId,
    previousNodeId,
    scopeStack,
    completedSubprocessId,
  })

  const appendLog = (
    logs: SimulationLog[],
    level: SimulationLog['level'],
    title: string,
    detail: string,
    nodeId?: string,
  ) => [...logs, { id: ++logSequence.current, level, title, detail, nodeId }].slice(-300)

  const reset = () => {
    tokenSequence.current = 0
    logSequence.current = 0
    setSession({ tokens: [], logs: [], failedNodeIds: [], status: 'READY' })
    setSelectedTokenId('')
    setSelectedTransitions([])
    setPane('context')
  }

  const begin = () => {
    const start = workflow.nodes.find((node) => node.id === startNodeId)
    if (!start) return
    const eventParent = workflow.nodes.find((node) =>
      node.id === start.parentSubprocessId && node.type === 'EVENT_SUBPROCESS')
    const token = nextToken(start.id, undefined, eventParent ? [eventParent.id] : [])
    setSession({
      tokens: [token],
      failedNodeIds: [],
      status: 'RUNNING',
      logs: [{
        id: ++logSequence.current,
        level: 'INFO',
        title: `Started at ${start.name}`,
        detail: `Actor ${username || 'anonymous'} · roles ${roles.join(', ') || 'none'} · business time ${businessDate || 'not set'}`,
        nodeId: start.id,
      }],
    })
    setSelectedTokenId(token.id)
    setSelectedTransitions([])
    setPane('tokens')
  }

  const advance = () => {
    if (!selectedToken || !selectedNode || session.status !== 'RUNNING') return
    const node = selectedNode
    let logs = session.logs
    let tokens = [...session.tokens]
    let failedNodeIds = [...session.failedNodeIds]

    if (subprocessTypes.has(node.type) && selectedToken.completedSubprocessId !== node.id) {
      const start = workflow.nodes.find((candidate) =>
        candidate.parentSubprocessId === node.id &&
        (candidate.type === 'START' || candidate.type === 'MESSAGE_START' ||
          candidate.type === 'SIGNAL_START' || candidate.type === 'TIMER_START' ||
          candidate.type === 'ERROR_START'))
      if (!start) {
        logs = appendLog(logs, 'ERROR', `Cannot enter ${node.name}`, 'No direct subprocess start event exists.', node.id)
        setSession({ tokens, logs, failedNodeIds, status: 'FAILED' })
        return
      }
      const replacement = nextToken(
        start.id,
        node.id,
        [...(selectedToken.scopeStack ?? []), node.id],
      )
      tokens = tokens.map((token) => token.id === selectedToken.id ? replacement : token)
      logs = appendLog(logs, 'INFO', `Entered ${node.name}`, `Execution moved into the ${node.type.replace(/_/g, ' ').toLowerCase()} scope.`, node.id)
      setSession({ tokens, logs, failedNodeIds, status: 'RUNNING' })
      setSelectedTokenId(replacement.id)
      setSelectedTransitions([])
      return
    }

    if (node.type === humanType) {
      const hasRole = node.actorRoleCodes.length === 0 ||
        node.actorRoleCodes.some((role) => roles.includes(role))
      const hasAssignee = !node.assigneeExpression || Boolean(username.trim())
      if (!hasRole || !hasAssignee) {
        logs = appendLog(
          logs,
          'WARNING',
          `Actor unavailable for ${node.name}`,
          `Required roles: ${node.actorRoleCodes.join(', ') || 'assignee expression'}; simulated roles: ${roles.join(', ') || 'none'}.`,
          node.id,
        )
        setSession({ ...session, logs })
        return
      }
      logs = appendLog(
        logs,
        'SUCCESS',
        `Completed human task: ${node.name}`,
        `Performed by ${username}; required documents ${node.requiredDocuments.join(', ') || 'none'}; validations ${node.validationRules.join(', ') || 'none'}.`,
        node.id,
      )
    } else if (taskTypes.has(node.type)) {
      if (failureNodeId === node.id && !failedNodeIds.includes(node.id)) {
        failedNodeIds.push(node.id)
        const boundary = workflow.nodes.find((candidate) =>
          candidate.type === 'BOUNDARY_ERROR' && candidate.attachedToNodeId === node.id,
        )
        if (boundary) {
          const replacement = nextToken(boundary.id, node.id, selectedToken.scopeStack ?? [])
          tokens = tokens.map((token) => token.id === selectedToken.id ? replacement : token)
          logs = appendLog(
            logs,
            'WARNING',
            `Injected failure in ${node.name}`,
            `The typed error boundary “${boundary.name}” caught the simulated failure.`,
            node.id,
          )
          setSession({ tokens, logs, failedNodeIds, status: 'RUNNING' })
          setSelectedTokenId(replacement.id)
          setSelectedTransitions([])
          return
        }
        logs = appendLog(
          logs,
          'ERROR',
          `Unrecovered failure in ${node.name}`,
          'No attached BPMN error boundary can recover this path.',
          node.id,
        )
        setSession({ tokens, logs, failedNodeIds, status: 'FAILED' })
        return
      }
      logs = appendLog(
        logs,
        'SUCCESS',
        `Executed ${node.name}`,
        node.type === 'AUTOMATED_STATE'
          ? `Called ${node.serviceBean}.${node.serviceMethod || 'execute'}; retry ${node.retryCycle || 'synchronous'}; idempotency ${node.idempotencyKeyExpression || 'not configured'}.`
          : node.type === 'EMAIL_STATE'
            ? `Queued email to ${node.emailTo}; subject ${node.emailSubject}; ${node.emailAttachments.length} attachment expression(s).`
          : node.type === 'ENTITY_DATA_STATE'
            ? `${node.entityDataOperation} entity operation using ${node.entityName || node.jpql || 'configured runtime contract'}.`
            : node.type === 'SCRIPT_STATE'
              ? `Executed Groovy and stored ${node.resultVariable || 'no automatic result variable'}.`
              : `Executed ${node.type.replace(/_/g, ' ').toLowerCase()}.`,
        node.id,
      )
    } else if (messageTypes.has(node.type)) {
      logs = appendLog(
        logs,
        'INFO',
        `Received ${node.name}`,
        `${node.type.includes('MESSAGE') ? 'Message' : 'Signal'} reference ${node.eventReference || 'not configured'} was delivered.`,
        node.id,
      )
    } else if (timerTypes.has(node.type)) {
      logs = appendLog(
        logs,
        'INFO',
        `Timer fired: ${node.name}`,
        `${node.timerType} ${node.timerExpression || 'not configured'} at simulated business time ${businessDate}.`,
        node.id,
      )
    }

    if (terminalTypes.has(node.type)) {
      const scopeStack = selectedToken.scopeStack ?? []
      const enclosingId = scopeStack[scopeStack.length - 1]
      const enclosing = workflow.nodes.find((candidate) => candidate.id === enclosingId)
      if (enclosing && enclosing.type !== 'EVENT_SUBPROCESS') {
        const replacement = nextToken(
          enclosing.id,
          node.id,
          scopeStack.slice(0, -1),
          enclosing.id,
        )
        tokens = tokens.map((token) => token.id === selectedToken.id ? replacement : token)
        logs = appendLog(
          logs,
          'SUCCESS',
          `${enclosing.name} completed`,
          `${node.name} ended the nested scope; execution returned to the parent process.`,
          enclosing.id,
        )
        setSession({ tokens, logs, failedNodeIds, status: 'RUNNING' })
        setSelectedTokenId(replacement.id)
        setSelectedTransitions([])
        return
      }
      tokens = tokens.filter((token) => token.id !== selectedToken.id)
      logs = appendLog(
        logs,
        node.type === 'ERROR_END' ? 'ERROR' : 'SUCCESS',
        `${node.name} reached`,
        node.type === 'ERROR_END'
          ? `Process path ended with BPMN error ${node.eventReference || 'unspecified'}.`
          : `Terminal state ${node.stateValue || node.name} completed.`,
        node.id,
      )
      const status = tokens.length ? 'RUNNING' : node.type === 'ERROR_END' ? 'FAILED' : 'COMPLETED'
      setSession({ tokens, logs, failedNodeIds, status })
      setSelectedTokenId(tokens.find((token) => !token.waiting)?.id ?? tokens[0]?.id ?? '')
      setSelectedTransitions([])
      return
    }

    if (node.type === 'PARALLEL_GATEWAY') {
      const incoming = workflow.transitions.filter((transition) => transition.targetId === node.id)
      const atJoin = tokens.filter((token) => token.nodeId === node.id)
      if (incoming.length > 1 && atJoin.length < incoming.length) {
        tokens = tokens.map((token) =>
          token.id === selectedToken.id ? { ...token, waiting: true } : token,
        )
        logs = appendLog(
          logs,
          'INFO',
          `Waiting at parallel join: ${node.name}`,
          `${atJoin.length}/${incoming.length} branches have arrived.`,
          node.id,
        )
        setSession({ tokens, logs, failedNodeIds, status: 'RUNNING' })
        setSelectedTokenId(tokens.find((token) => !token.waiting)?.id ?? '')
        return
      }
      tokens = tokens.filter((token) => token.nodeId !== node.id)
      const forked = outgoing.map((transition) =>
        nextToken(transition.targetId, node.id, selectedToken.scopeStack ?? []))
      tokens.push(...forked)
      logs = appendLog(
        logs,
        'INFO',
        incoming.length > 1 ? `Parallel branches joined at ${node.name}` : `Parallel branches forked at ${node.name}`,
        `${forked.length} outgoing execution token${forked.length === 1 ? '' : 's'} created.`,
        node.id,
      )
      setSession({
        tokens,
        logs,
        failedNodeIds,
        status: tokens.length ? 'RUNNING' : 'COMPLETED',
      })
      setSelectedTokenId(tokens.find((token) => !token.waiting)?.id ?? tokens[0]?.id ?? '')
      setSelectedTransitions([])
      return
    }

    const selectedOutgoing = outgoing.length <= 1
      ? outgoing
      : outgoing.filter((transition) => selectedTransitions.includes(transition.id))
    if (outgoing.length > 1 && selectedOutgoing.length === 0) {
      logs = appendLog(
        logs,
        'WARNING',
        `Choose a transition from ${node.name}`,
        'The simulator does not guess business conditions. Select the reviewed outcome explicitly.',
        node.id,
      )
      setSession({ ...session, logs })
      return
    }
    const chosen = node.type === 'INCLUSIVE_GATEWAY'
      ? selectedOutgoing
      : selectedOutgoing.slice(0, 1)
    tokens = tokens.filter((token) => token.id !== selectedToken.id)
    const created = chosen.map((transition) =>
      nextToken(transition.targetId, node.id, selectedToken.scopeStack ?? []))
    tokens.push(...created)
    if (chosen.length) {
      logs = appendLog(
        logs,
        'INFO',
        `Advanced from ${node.name}`,
        chosen.map((transition) => `${transitionLabel(transition, workflow)}${transition.conditionExpression ? ` [${transition.conditionExpression}]` : ''}`).join(', '),
        node.id,
      )
    } else {
      logs = appendLog(logs, 'WARNING', `Path stopped at ${node.name}`, 'No outgoing transition exists.', node.id)
    }
    setSession({
      tokens,
      logs,
      failedNodeIds,
      status: tokens.length ? 'RUNNING' : 'COMPLETED',
    })
    setSelectedTokenId(tokens.find((token) => !token.waiting)?.id ?? tokens[0]?.id ?? '')
    setSelectedTransitions([])
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/65 p-1.5 backdrop-blur-sm sm:p-5">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="workflow-simulation-title"
        className="flex h-[min(97vh,860px)] w-[min(98vw,1180px)] min-w-0 flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl sm:h-[min(92vh,860px)] sm:w-[min(96vw,1180px)]"
      >
        <header className="flex min-w-0 flex-wrap items-center gap-3 border-b border-surface-border bg-surface-light px-4 py-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-jmix-500/15 text-jmix-300"><Zap className="h-4 w-4" /></div>
          <div className="min-w-0 flex-1">
            <h2 id="workflow-simulation-title" className="truncate text-sm font-semibold text-gray-100">Workflow Simulation Lab</h2>
            <p className="truncate text-[10px] text-gray-500">Deterministic pre-deployment token simulation · no source or runtime data is changed</p>
          </div>
          <span className={`rounded border px-2 py-1 text-[10px] font-semibold ${
            session.status === 'FAILED' ? 'border-red-500/30 text-red-300'
              : session.status === 'COMPLETED' ? 'border-emerald-500/30 text-emerald-300'
                : 'border-surface-border text-gray-400'
          }`}>{session.status}</span>
          <button aria-label="Close workflow simulation" className="rounded p-2 text-gray-500 hover:bg-surface-lighter hover:text-gray-200" onClick={onClose}><X className="h-4 w-4" /></button>
        </header>

        <ResponsivePaneSwitcher
          id="workflow-simulation-panel"
          value={pane}
          onChange={setPane}
          options={[
            { id: 'context', label: 'Simulation context' },
            { id: 'tokens', label: 'Execution tokens', badge: session.tokens.length },
            { id: 'trace', label: 'Simulation trace', badge: session.logs.length },
          ]}
          label="Workflow simulation panels"
        />

        <div className="grid min-h-0 min-w-0 flex-1 grid-cols-1 overflow-hidden lg:grid-cols-[minmax(14rem,0.75fr)_minmax(18rem,1fr)_minmax(20rem,1.2fr)]">
          <section className={`${pane === 'context' ? 'block' : 'hidden'} min-h-0 overflow-y-auto p-3 lg:block lg:border-r lg:border-surface-border`}>
            <h3 className="mb-3 text-[10px] font-semibold uppercase tracking-wider text-gray-500">Simulation context</h3>
            <div className="space-y-3">
              <label><span className="mb-1 block text-[10px] text-gray-500">Start event</span>
                <select className={input} value={startNodeId} onChange={(event) => setStartNodeId(event.target.value)} disabled={session.status === 'RUNNING'}>
                  {starts.map((node) => <option key={node.id} value={node.id}>{node.name}</option>)}
                </select>
              </label>
              <label><span className="mb-1 block text-[10px] text-gray-500">Acting username</span><input className={input} value={username} onChange={(event) => setUsername(event.target.value)} /></label>
              <label><span className="mb-1 block text-[10px] text-gray-500">Effective role codes</span><textarea rows={3} className={input} value={rolesText} onChange={(event) => setRolesText(event.target.value)} placeholder="maker, checker, payroll-manager" /></label>
              <label><span className="mb-1 block text-[10px] text-gray-500">Business date and time</span><input type="datetime-local" className={input} value={businessDate} onChange={(event) => setBusinessDate(event.target.value)} /></label>
              <label><span className="mb-1 block text-[10px] text-gray-500">Inject one service failure</span>
                <select className={input} value={failureNodeId} onChange={(event) => setFailureNodeId(event.target.value)}>
                  <option value="">No injected failure</option>
                  {workflow.nodes.filter((node) => taskTypes.has(node.type)).map((node) => <option key={node.id} value={node.id}>{node.name}</option>)}
                </select>
              </label>
              <div className="grid grid-cols-2 gap-2">
                <button className="flex items-center justify-center gap-1.5 rounded bg-jmix-600 px-3 py-2 text-xs font-medium text-white hover:bg-jmix-500 disabled:opacity-40" disabled={!startNodeId || session.status === 'RUNNING'} onClick={begin}><Play className="h-3.5 w-3.5" />Start</button>
                <button className="flex items-center justify-center gap-1.5 rounded border border-surface-border px-3 py-2 text-xs text-gray-300 hover:bg-surface-lighter" onClick={reset}><RefreshCw className="h-3.5 w-3.5" />Reset</button>
              </div>
            </div>
            <div className="mt-4 rounded border border-sky-500/20 bg-sky-500/5 p-2 text-[10px] leading-relaxed text-sky-100/70">
              This lab validates graph behavior before deployment. Runtime expressions are shown for explicit human choice; they are not executed inside the IDE.
            </div>
          </section>

          <section className={`${pane === 'tokens' ? 'block' : 'hidden'} min-h-0 overflow-y-auto p-3 lg:block lg:border-r lg:border-surface-border`}>
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500">Active execution tokens</h3>
              <span className="rounded bg-surface-lighter px-1.5 py-0.5 text-[10px] text-gray-500">{session.tokens.length}</span>
            </div>
            <div className="space-y-2">
              {session.tokens.map((token, index) => {
                const node = workflow.nodes.find((candidate) => candidate.id === token.nodeId)
                if (!node) return null
                return (
                  <button
                    key={token.id}
                    className={`w-full rounded border p-3 text-left ${selectedToken?.id === token.id ? 'border-jmix-500 bg-jmix-500/10' : 'border-surface-border bg-surface-light'} ${token.waiting ? 'opacity-60' : ''}`}
                    onClick={() => {
                      setSelectedTokenId(token.id)
                      setSelectedTransitions([])
                    }}
                  >
                    <div className="flex items-center gap-2">
                      <span className="flex h-5 w-5 items-center justify-center rounded-full bg-surface-lighter text-[9px] text-gray-400">{index + 1}</span>
                      <span className="min-w-0 flex-1 truncate text-xs text-gray-200">{node.name}</span>
                      {token.waiting && <Clock3 className="h-3.5 w-3.5 text-amber-300" />}
                    </div>
                    <div className="ml-7 mt-1 text-[9px] uppercase tracking-wide text-gray-600">{node.type.replace(/_/g, ' ')}</div>
                  </button>
                )
              })}
              {!session.tokens.length && <div className="rounded border border-dashed border-surface-border p-5 text-center text-xs text-gray-600">Start a simulation to create the first execution token.</div>}
            </div>

            {selectedNode && (
              <div className="mt-4 rounded border border-surface-border bg-surface-light/60 p-3">
                <div className="mb-2 flex items-center gap-2">
                  {selectedNode.type === humanType ? <UserRound className="h-4 w-4 text-sky-300" /> : <GitBranch className="h-4 w-4 text-jmix-300" />}
                  <div className="min-w-0 flex-1"><div className="truncate text-xs font-semibold text-gray-200">{selectedNode.name}</div><div className="text-[9px] text-gray-600">{selectedNode.id}</div></div>
                  <button className="text-[10px] text-jmix-400 hover:text-jmix-300" onClick={() => onInspectNode(selectedNode.id)}>Inspect</button>
                </div>
                {requiresBranchChoice && (
                  <div className="mb-3 space-y-1.5">
                    <div className="text-[10px] font-semibold text-gray-500">{selectedNode.type === 'INCLUSIVE_GATEWAY' ? 'Choose one or more paths' : 'Choose the outcome'}</div>
                    {outgoing.map((transition) => (
                      <label key={transition.id} className="flex items-start gap-2 rounded border border-surface-border bg-surface p-2 text-[10px] text-gray-300">
                        <input
                          type={selectedNode.type === 'INCLUSIVE_GATEWAY' ? 'checkbox' : 'radio'}
                          name="simulation-transition"
                          checked={selectedTransitions.includes(transition.id)}
                          onChange={(event) => setSelectedTransitions((current) =>
                            selectedNode.type === 'INCLUSIVE_GATEWAY'
                              ? event.target.checked ? [...current, transition.id] : current.filter((id) => id !== transition.id)
                              : [transition.id],
                          )}
                        />
                        <span><span className="block font-medium">{transitionLabel(transition, workflow)}</span>{transition.conditionExpression && <span className="mt-0.5 block font-mono text-[9px] text-gray-600">{transition.conditionExpression}</span>}</span>
                      </label>
                    ))}
                  </div>
                )}
                <button
                  className="flex w-full items-center justify-center gap-2 rounded bg-emerald-600 px-3 py-2 text-xs font-semibold text-white hover:bg-emerald-500 disabled:opacity-40"
                  disabled={selectedToken?.waiting || session.status !== 'RUNNING'}
                  onClick={advance}
                >
                  <StepForward className="h-3.5 w-3.5" />
                  {messageTypes.has(selectedNode.type) ? 'Deliver event' : timerTypes.has(selectedNode.type) ? 'Fire timer' : terminalTypes.has(selectedNode.type) ? 'Complete path' : 'Advance token'}
                </button>
              </div>
            )}
          </section>

          <section className={`${pane === 'trace' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col overflow-hidden lg:flex`}>
            <div className="flex items-center gap-2 border-b border-surface-border px-3 py-2.5">
              <ShieldCheck className="h-4 w-4 text-emerald-300" />
              <h3 className="text-[10px] font-semibold uppercase tracking-wider text-gray-500">Simulation trace</h3>
            </div>
            <div className="min-h-0 flex-1 space-y-2 overflow-y-auto p-3">
              {session.logs.map((entry) => (
                <button
                  key={entry.id}
                  className={`w-full rounded border p-2.5 text-left ${logClass(entry.level)}`}
                  onClick={() => entry.nodeId && onInspectNode(entry.nodeId)}
                >
                  <div className="flex items-center gap-1.5 text-[10px] font-semibold">
                    {entry.level === 'ERROR' || entry.level === 'WARNING' ? <AlertTriangle className="h-3.5 w-3.5" /> : entry.level === 'SUCCESS' ? <CheckCircle2 className="h-3.5 w-3.5" /> : <GitBranch className="h-3.5 w-3.5" />}
                    {entry.title}
                  </div>
                  <div className="mt-1 text-[10px] leading-relaxed opacity-75">{entry.detail}</div>
                </button>
              ))}
              {!session.logs.length && <div className="flex h-full min-h-48 items-center justify-center text-center text-xs text-gray-600">The execution trace will explain every actor, branch, timer, service, recovery path, and terminal result.</div>}
            </div>
          </section>
        </div>
      </div>
    </div>
  )
}
