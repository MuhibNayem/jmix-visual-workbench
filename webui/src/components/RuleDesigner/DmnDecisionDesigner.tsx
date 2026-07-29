import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  CheckCircle2,
  FileCode2,
  FlaskConical,
  GitBranch,
  Loader2,
  Plus,
  Redo2,
  RefreshCw,
  Save,
  Table2,
  Trash2,
  Undo2,
  X,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  DmnCollectOperator,
  DmnConditionModel,
  DmnConditionOperator,
  DmnDecisionModel,
  DmnDecisionRuleModel,
  DmnDecisionWorkspaceResponse,
  DmnHitPolicy,
  DmnSimulationResult,
  DmnValueType,
  WorkspaceChangePreviewResponse,
} from '../../types'

const HISTORY_LIMIT = 100
const valueTypes: DmnValueType[] = ['STRING', 'NUMBER', 'BOOLEAN', 'DATE']
const hitPolicies: DmnHitPolicy[] = [
  'UNIQUE', 'FIRST', 'ANY', 'PRIORITY', 'OUTPUT_ORDER', 'RULE_ORDER', 'COLLECT',
]
const operators: DmnConditionOperator[] = [
  'ANY', 'EQUALS', 'NOT_EQUALS', 'LESS_THAN', 'LESS_THAN_OR_EQUAL',
  'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'BETWEEN',
]
const aggregations: DmnCollectOperator[] = ['NONE', 'SUM', 'MIN', 'MAX', 'COUNT']

function uid(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function inputClass(extra = '') {
  return `w-full min-w-0 rounded border border-surface-border bg-surface px-2 py-1.5 text-xs text-gray-100 outline-none transition focus:border-jmix-500 ${extra}`
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block min-w-0">
      <span className="mb-1 block text-[9px] font-bold uppercase tracking-[0.12em] text-gray-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[9px] leading-4 text-gray-600">{hint}</span>}
    </label>
  )
}

function emptyCondition(): DmnConditionModel {
  return { operator: 'ANY' }
}

function newRule(model: Pick<DmnDecisionModel, 'inputs' | 'outputs'>): DmnDecisionRuleModel {
  const ruleNumber = Date.now().toString(36)
  return {
    id: `rule-${ruleNumber}`,
    description: '',
    enabled: true,
    inputEntries: Object.fromEntries(model.inputs.map((input) => [input.id, emptyCondition()])),
    outputEntries: Object.fromEntries(model.outputs.map((output) => [output.id, ''])),
  }
}

function defaultModel(workspace?: DmnDecisionWorkspaceResponse): DmnDecisionModel {
  const destination = workspace?.destinations.find((item) => item.recommended) ?? workspace?.destinations[0]
  const inputs = [{
    id: 'amount',
    label: 'Requested amount',
    variable: 'amount',
    type: 'NUMBER' as const,
  }]
  const outputs = [{
    id: 'decision',
    label: 'Decision',
    variable: 'decision',
    type: 'STRING' as const,
    predefinedValues: ['REJECT', 'REVIEW', 'APPROVE'],
  }]
  return {
    name: 'Loan eligibility',
    key: 'loanEligibility',
    namespace: 'https://example.com/dmn',
    destinationId: destination?.id ?? '',
    fileName: 'loan-eligibility.dmn',
    hitPolicy: 'UNIQUE',
    collectOperator: 'NONE',
    inputs,
    outputs,
    rules: [{
      id: 'approved-range',
      description: 'Amount within automatic approval limit',
      enabled: true,
      inputEntries: {
        amount: { operator: 'LESS_THAN_OR_EQUAL', value: '100000' },
      },
      outputEntries: { decision: 'APPROVE' },
    }],
    authoringVersion: 1,
    authoringStatus: 'DRAFT',
    description: 'Server-deployed decision used by the loan approval workflow.',
  }
}

function conditionLabel(condition: DmnConditionModel) {
  if (condition.operator === 'ANY') return 'Any value'
  if (condition.operator === 'BETWEEN') return `${condition.value || '…'} to ${condition.secondValue || '…'}`
  const symbols: Partial<Record<DmnConditionOperator, string>> = {
    EQUALS: '=', NOT_EQUALS: '≠', LESS_THAN: '<', LESS_THAN_OR_EQUAL: '≤',
    GREATER_THAN: '>', GREATER_THAN_OR_EQUAL: '≥',
  }
  return `${symbols[condition.operator] ?? condition.operator} ${condition.value || '…'}`
}

export function DmnDecisionDesigner({ onSwitch }: { onSwitch: () => void }) {
  const addToast = useStore((state) => state.addToast)
  const [workspace, setWorkspace] = useState<DmnDecisionWorkspaceResponse>()
  const [model, setModel] = useState<DmnDecisionModel>(() => defaultModel())
  const [selectedRuleId, setSelectedRuleId] = useState<string | undefined>(model.rules[0]?.id)
  const [loading, setLoading] = useState(true)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [simulating, setSimulating] = useState(false)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse>()
  const [simulationInputs, setSimulationInputs] = useState<Record<string, string>>({})
  const [simulation, setSimulation] = useState<DmnSimulationResult>()
  const [history, setHistory] = useState<DmnDecisionModel[]>([])
  const [future, setFuture] = useState<DmnDecisionModel[]>([])

  const selectedRule = model.rules.find((rule) => rule.id === selectedRuleId) ?? model.rules[0]
  const referencedBy = useMemo(
    () => workspace?.workflowReferences.filter((reference) => reference.decisionKey === model.key) ?? [],
    [model.key, workspace],
  )

  const load = useCallback(async (forceRefresh = false) => {
    setLoading(true)
    try {
      const loaded = await bridge.getDmnDecisionWorkspace(forceRefresh)
      setWorkspace(loaded)
      setModel((current) => current.destinationId ? current : defaultModel(loaded))
      if (loaded.issues.length) addToast(loaded.issues[0].message, 'info')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Could not load the DMN workspace.', 'error')
    } finally {
      setLoading(false)
    }
  }, [addToast])

  useEffect(() => {
    void load()
  }, [load])

  const commit = useCallback((updater: (current: DmnDecisionModel) => DmnDecisionModel) => {
    setModel((current) => {
      const next = updater(current)
      if (JSON.stringify(next) === JSON.stringify(current)) return current
      setHistory((items) => [...items.slice(-(HISTORY_LIMIT - 1)), clone(current)])
      setFuture([])
      setPreview(undefined)
      setSimulation(undefined)
      return next
    })
  }, [])

  const updateModel = (patch: Partial<DmnDecisionModel>) =>
    commit((current) => ({ ...current, ...patch }))

  const updateRule = (ruleId: string, updater: (rule: DmnDecisionRuleModel) => DmnDecisionRuleModel) =>
    commit((current) => ({
      ...current,
      rules: current.rules.map((rule) => rule.id === ruleId ? updater(rule) : rule),
    }))

  const undo = () => {
    const previous = history[history.length - 1]
    if (!previous) return
    setHistory((items) => items.slice(0, -1))
    setFuture((items) => [clone(model), ...items].slice(0, HISTORY_LIMIT))
    setModel(previous)
    setSelectedRuleId(previous.rules[0]?.id)
    setPreview(undefined)
    setSimulation(undefined)
  }

  const redo = () => {
    const next = future[0]
    if (!next) return
    setFuture((items) => items.slice(1))
    setHistory((items) => [...items, clone(model)].slice(-HISTORY_LIMIT))
    setModel(next)
    setSelectedRuleId(next.rules[0]?.id)
    setPreview(undefined)
    setSimulation(undefined)
  }

  const openDocument = (path: string) => {
    const document = workspace?.existingDocuments.find((item) => item.locator.relativePath === path)
    if (!document?.model) {
      if (document) addToast(document.issue ?? 'This DMN is protected from visual overwrite.', 'info')
      return
    }
    setModel(clone(document.model))
    setSelectedRuleId(document.model.rules[0]?.id)
    setHistory([])
    setFuture([])
    setPreview(undefined)
    if (!document.editable) addToast(document.issue ?? 'Manual DMN changes make this table read-only.', 'info')
  }

  const addInput = () => {
    const columnId = uid('input')
    commit((current) => ({
      ...current,
      inputs: [...current.inputs, {
        id: columnId,
        label: `Input ${current.inputs.length + 1}`,
        variable: `input${current.inputs.length + 1}`,
        type: 'STRING',
      }],
      rules: current.rules.map((rule) => ({
        ...rule,
        inputEntries: { ...rule.inputEntries, [columnId]: emptyCondition() },
      })),
    }))
  }

  const addOutput = () => {
    const columnId = uid('output')
    commit((current) => ({
      ...current,
      outputs: [...current.outputs, {
        id: columnId,
        label: `Output ${current.outputs.length + 1}`,
        variable: `output${current.outputs.length + 1}`,
        type: 'STRING',
        predefinedValues: [],
      }],
      rules: current.rules.map((rule) => ({
        ...rule,
        outputEntries: { ...rule.outputEntries, [columnId]: '' },
      })),
    }))
  }

  const addDecisionRule = () => {
    const rule = newRule(model)
    updateModel({ rules: [...model.rules, rule] })
    setSelectedRuleId(rule.id)
  }

  const removeInput = (columnId: string) => commit((current) => ({
    ...current,
    inputs: current.inputs.filter((input) => input.id !== columnId),
    rules: current.rules.map((rule) => {
      const entries = { ...rule.inputEntries }
      delete entries[columnId]
      return { ...rule, inputEntries: entries }
    }),
  }))

  const removeOutput = (columnId: string) => commit((current) => ({
    ...current,
    outputs: current.outputs.filter((output) => output.id !== columnId),
    rules: current.rules.map((rule) => {
      const entries = { ...rule.outputEntries }
      delete entries[columnId]
      return { ...rule, outputEntries: entries }
    }),
  }))

  const moveRule = (ruleId: string, direction: -1 | 1) => commit((current) => {
    const index = current.rules.findIndex((rule) => rule.id === ruleId)
    const target = index + direction
    if (index < 0 || target < 0 || target >= current.rules.length) return current
    const rules = [...current.rules]
    ;[rules[index], rules[target]] = [rules[target], rules[index]]
    return { ...current, rules }
  })

  const previewSource = async () => {
    setPreviewing(true)
    try {
      const result = await bridge.previewDmnDecision(model)
      setPreview(result)
      if (!result.accepted) addToast(result.issues[0]?.message ?? 'The decision table needs attention.', 'error')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'DMN preview failed.', 'error')
    } finally {
      setPreviewing(false)
    }
  }

  const applySource = async () => {
    if (!preview?.accepted || !preview.planDigest) return
    setApplying(true)
    try {
      const result = await bridge.applyDmnDecision(model, preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'DMN generation was rejected.', 'error')
        return
      }
      addToast(`Generated ${result.filesChanged.join(', ')}`, 'success')
      setPreview(undefined)
      await load(true)
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'DMN generation failed.', 'error')
    } finally {
      setApplying(false)
    }
  }

  const simulate = async () => {
    setSimulating(true)
    try {
      const result = await bridge.simulateDmnDecision(model, simulationInputs)
      setSimulation(result)
      if (!result.accepted) addToast(result.diagnostics[0]?.message ?? 'Simulation was rejected.', 'error')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'DMN simulation failed.', 'error')
    } finally {
      setSimulating(false)
    }
  }

  if (loading && !workspace) {
    return (
      <div className="flex h-full items-center justify-center gap-3 text-sm text-gray-400">
        <Loader2 className="h-5 w-5 animate-spin text-jmix-400" />
        Mapping resource roots, decisions, and BPMN references…
      </div>
    )
  }

  return (
    <section className="rule-designer-shell relative flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <header className="flex min-w-0 flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light px-3 py-2">
        <div className="mr-auto min-w-[210px]">
          <div className="flex items-center gap-2">
            <Table2 className="h-4 w-4 text-fuchsia-300" />
            <h2 className="truncate text-sm font-semibold text-gray-100">DMN Decision Tables</h2>
            <span className="rounded bg-fuchsia-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase text-fuchsia-200">Jmix · Flowable</span>
          </div>
          <p className="mt-0.5 truncate text-[10px] text-gray-500">Typed rules · hit policies · BPMN impact · source-safe round trip</p>
        </div>
        <button className="btn-secondary" onClick={onSwitch}>Expression rules</button>
        <button className="btn-secondary" onClick={undo} disabled={!history.length} title="Undo"><Undo2 className="h-3.5 w-3.5" /></button>
        <button className="btn-secondary" onClick={redo} disabled={!future.length} title="Redo"><Redo2 className="h-3.5 w-3.5" /></button>
        <button className="btn-secondary flex items-center gap-1.5" onClick={() => void load(true)} disabled={loading}>
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span className="hidden min-[1100px]:inline">Refresh index</span>
        </button>
        <button className="btn-primary flex items-center gap-1.5" onClick={() => void previewSource()} disabled={previewing}>
          {previewing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <FileCode2 className="h-3.5 w-3.5" />}
          Preview DMN
        </button>
      </header>

      <div className="grid min-w-0 grid-cols-1 gap-px border-b border-surface-border bg-surface-border px-px sm:grid-cols-2 xl:grid-cols-6">
        <Field label="Decision name"><input className={inputClass()} value={model.name} onChange={(event) => updateModel({ name: event.target.value })} /></Field>
        <Field label="Stable decision key"><input className={inputClass()} value={model.key} onChange={(event) => updateModel({ key: event.target.value })} /></Field>
        <Field label="Module">
          <select className={inputClass()} disabled={Boolean(model.sourceLocator)} value={model.destinationId} onChange={(event) => updateModel({ destinationId: event.target.value })}>
            {workspace?.destinations.map((destination) => <option key={destination.id} value={destination.id}>{destination.moduleId} · resources/dmn</option>)}
          </select>
        </Field>
        <Field label="File"><input className={inputClass()} disabled={Boolean(model.sourceLocator)} value={model.fileName} onChange={(event) => updateModel({ fileName: event.target.value })} /></Field>
        <Field label="Hit policy">
          <select className={inputClass()} value={model.hitPolicy} onChange={(event) => {
            const hitPolicy = event.target.value as DmnHitPolicy
            updateModel({ hitPolicy, collectOperator: hitPolicy === 'COLLECT' ? model.collectOperator : 'NONE' })
          }}>{hitPolicies.map((policy) => <option key={policy}>{policy}</option>)}</select>
        </Field>
        <Field label="Aggregation">
          <select className={inputClass()} disabled={model.hitPolicy !== 'COLLECT'} value={model.collectOperator} onChange={(event) => updateModel({ collectOperator: event.target.value as DmnCollectOperator })}>
            {aggregations.map((operator) => <option key={operator}>{operator}</option>)}
          </select>
        </Field>
      </div>

      <div className="rule-designer-workspace grid min-h-0 min-w-0 flex-1">
        <aside className="rule-designer-palette flex min-h-0 min-w-0 flex-col border-r border-surface-border bg-surface-light/55">
          <div className="border-b border-surface-border p-3">
            <h3 className="text-xs font-semibold text-gray-200">Decision library</h3>
            <select
              aria-label="Open existing DMN decision"
              className={inputClass('mt-2')}
              value={model.sourceLocator?.relativePath ?? ''}
              onChange={(event) => event.target.value ? openDocument(event.target.value) : setModel(defaultModel(workspace))}
            >
              <option value="">New decision table</option>
              {workspace?.existingDocuments.map((document) => (
                <option key={document.locator.relativePath} value={document.locator.relativePath}>
                  {document.editable ? '●' : '◐'} {document.model?.name ?? document.locator.relativePath.split('/').pop()}
                </option>
              ))}
            </select>
          </div>
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3">
            <div>
              <h4 className="mb-2 text-[9px] font-bold uppercase tracking-[0.14em] text-gray-600">Governance</h4>
              <div className="grid grid-cols-2 gap-2">
                <Field label="Version"><input type="number" min={1} className={inputClass()} value={model.authoringVersion} onChange={(event) => updateModel({ authoringVersion: Number(event.target.value) })} /></Field>
                <Field label="Status">
                  <select className={inputClass()} value={model.authoringStatus} onChange={(event) => updateModel({ authoringStatus: event.target.value as DmnDecisionModel['authoringStatus'] })}>
                    <option>DRAFT</option><option>ACTIVE</option><option>RETIRED</option>
                  </select>
                </Field>
                <Field label="Effective from"><input type="date" className={inputClass()} value={model.effectiveFrom ?? ''} onChange={(event) => updateModel({ effectiveFrom: event.target.value || undefined })} /></Field>
                <Field label="Effective to"><input type="date" className={inputClass()} value={model.effectiveTo ?? ''} onChange={(event) => updateModel({ effectiveTo: event.target.value || undefined })} /></Field>
              </div>
              <Field label="Namespace"><input className={inputClass('mt-2')} value={model.namespace} onChange={(event) => updateModel({ namespace: event.target.value })} /></Field>
              <Field label="Purpose"><textarea className={inputClass('mt-2 min-h-20 resize-y')} value={model.description} onChange={(event) => updateModel({ description: event.target.value })} /></Field>
            </div>
            <div className="border-t border-surface-border pt-3">
              <h4 className="mb-2 flex items-center gap-1.5 text-[9px] font-bold uppercase tracking-[0.14em] text-gray-600"><GitBranch className="h-3 w-3" /> Workflow impact</h4>
              {referencedBy.length ? referencedBy.map((reference) => (
                <div key={`${reference.processId}-${reference.nodeId}`} className="mb-1.5 rounded border border-surface-border bg-surface p-2 text-[10px]">
                  <div className="font-medium text-gray-200">{reference.nodeName}</div>
                  <div className="mt-0.5 truncate text-gray-600">{reference.processId} · {reference.nodeId}</div>
                </div>
              )) : (
                <p className="text-[9px] leading-4 text-gray-600">No indexed BPMN task currently references <strong className="text-gray-400">{model.key}</strong>.</p>
              )}
            </div>
          </div>
          <div className="border-t border-surface-border p-3 text-[9px] leading-4 text-gray-600">
            ● exact owned source · ◐ manual or standard DMN protected from overwrite
          </div>
        </aside>

        <main className="rule-designer-canvas flex min-h-0 min-w-0 flex-col bg-surface">
          <div className="flex flex-wrap items-center gap-2 border-b border-surface-border px-3 py-2">
            <span className="text-[10px] font-medium text-gray-300">Decision matrix</span>
            <span className="text-[9px] text-gray-600">{model.inputs.length} inputs · {model.outputs.length} outputs · {model.rules.length} ordered rules</span>
            <div className="ml-auto flex flex-wrap gap-1.5">
              <button className="btn-secondary flex items-center gap-1 text-[10px]" onClick={addInput}><Plus className="h-3 w-3" /> Input</button>
              <button className="btn-secondary flex items-center gap-1 text-[10px]" onClick={addOutput}><Plus className="h-3 w-3" /> Output</button>
              <button className="btn-primary flex items-center gap-1 text-[10px]" onClick={addDecisionRule}><Plus className="h-3 w-3" /> Rule</button>
            </div>
          </div>
          <div className="min-h-0 min-w-0 flex-1 overflow-auto">
            <table className="w-full min-w-max border-separate border-spacing-0 text-left text-[10px]">
              <thead className="sticky top-0 z-20 bg-surface-light">
                <tr>
                  <th className="sticky left-0 z-30 w-16 border-b border-r border-surface-border bg-surface-light px-2 py-2 text-gray-500">Rule</th>
                  {model.inputs.map((input, index) => (
                    <th key={input.id} className="w-48 border-b border-r border-amber-500/20 bg-amber-500/5 p-2 align-top">
                      <div className="mb-1 flex items-center gap-1 text-[8px] font-bold uppercase tracking-wide text-amber-300/70">Input {index + 1}<button className="ml-auto text-gray-600 hover:text-red-300" onClick={() => removeInput(input.id)}><Trash2 className="h-3 w-3" /></button></div>
                      <input className={inputClass()} value={input.label} onChange={(event) => commit((current) => ({ ...current, inputs: current.inputs.map((item) => item.id === input.id ? { ...item, label: event.target.value } : item) }))} />
                      <div className="mt-1 grid grid-cols-[1fr_78px] gap-1">
                        <input className={inputClass()} value={input.variable} onChange={(event) => commit((current) => ({ ...current, inputs: current.inputs.map((item) => item.id === input.id ? { ...item, variable: event.target.value } : item) }))} />
                        <select className={inputClass()} value={input.type} onChange={(event) => commit((current) => ({ ...current, inputs: current.inputs.map((item) => item.id === input.id ? { ...item, type: event.target.value as DmnValueType } : item) }))}>{valueTypes.map((type) => <option key={type}>{type}</option>)}</select>
                      </div>
                    </th>
                  ))}
                  {model.outputs.map((output, index) => (
                    <th key={output.id} className="w-48 border-b border-r border-emerald-500/20 bg-emerald-500/5 p-2 align-top">
                      <div className="mb-1 flex items-center gap-1 text-[8px] font-bold uppercase tracking-wide text-emerald-300/70">Output {index + 1}<button className="ml-auto text-gray-600 hover:text-red-300" onClick={() => removeOutput(output.id)}><Trash2 className="h-3 w-3" /></button></div>
                      <input className={inputClass()} value={output.label} onChange={(event) => commit((current) => ({ ...current, outputs: current.outputs.map((item) => item.id === output.id ? { ...item, label: event.target.value } : item) }))} />
                      <div className="mt-1 grid grid-cols-[1fr_78px] gap-1">
                        <input className={inputClass()} value={output.variable} onChange={(event) => commit((current) => ({ ...current, outputs: current.outputs.map((item) => item.id === output.id ? { ...item, variable: event.target.value } : item) }))} />
                        <select className={inputClass()} value={output.type} onChange={(event) => commit((current) => ({ ...current, outputs: current.outputs.map((item) => item.id === output.id ? { ...item, type: event.target.value as DmnValueType } : item) }))}>{valueTypes.map((type) => <option key={type}>{type}</option>)}</select>
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {model.rules.map((rule, rowIndex) => {
                  const matched = simulation?.matchedRuleIds.includes(rule.id)
                  const selected = selectedRule?.id === rule.id
                  return (
                    <tr key={rule.id} className={`${selected ? 'bg-jmix-500/10' : ''} ${matched ? 'outline outline-1 -outline-offset-1 outline-emerald-400/60' : ''}`} onClick={() => setSelectedRuleId(rule.id)}>
                      <td className="sticky left-0 z-10 border-b border-r border-surface-border bg-surface-light p-2 align-top">
                        <div className="font-semibold text-gray-300">#{rowIndex + 1}</div>
                        <div className="mt-1 flex gap-0.5">
                          <button className="text-gray-600 hover:text-gray-200" onClick={(event) => { event.stopPropagation(); moveRule(rule.id, -1) }}><ArrowUp className="h-3 w-3" /></button>
                          <button className="text-gray-600 hover:text-gray-200" onClick={(event) => { event.stopPropagation(); moveRule(rule.id, 1) }}><ArrowDown className="h-3 w-3" /></button>
                        </div>
                      </td>
                      {model.inputs.map((input) => {
                        const condition = rule.inputEntries[input.id] ?? emptyCondition()
                        return (
                          <td key={input.id} className="border-b border-r border-surface-border p-2 align-top">
                            <select className={inputClass()} value={condition.operator} onChange={(event) => updateRule(rule.id, (current) => ({
                              ...current,
                              inputEntries: { ...current.inputEntries, [input.id]: { ...condition, operator: event.target.value as DmnConditionOperator } },
                            }))}>{operators.map((operator) => <option key={operator} value={operator}>{operator.replace(/_/g, ' ')}</option>)}</select>
                            {condition.operator !== 'ANY' && (
                              <input className={inputClass('mt-1')} placeholder={input.type.toLowerCase()} value={condition.value ?? ''} onChange={(event) => updateRule(rule.id, (current) => ({
                                ...current,
                                inputEntries: { ...current.inputEntries, [input.id]: { ...condition, value: event.target.value } },
                              }))} />
                            )}
                            {condition.operator === 'BETWEEN' && (
                              <input className={inputClass('mt-1')} placeholder="Upper bound" value={condition.secondValue ?? ''} onChange={(event) => updateRule(rule.id, (current) => ({
                                ...current,
                                inputEntries: { ...current.inputEntries, [input.id]: { ...condition, secondValue: event.target.value } },
                              }))} />
                            )}
                          </td>
                        )
                      })}
                      {model.outputs.map((output) => (
                        <td key={output.id} className="border-b border-r border-surface-border p-2 align-top">
                          <input list={`dmn-priority-${output.id}`} className={inputClass()} value={rule.outputEntries[output.id] ?? ''} onChange={(event) => updateRule(rule.id, (current) => ({
                            ...current,
                            outputEntries: { ...current.outputEntries, [output.id]: event.target.value },
                          }))} />
                          <datalist id={`dmn-priority-${output.id}`}>{output.predefinedValues.map((value) => <option key={value}>{value}</option>)}</datalist>
                        </td>
                      ))}
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {!model.rules.length && <div className="p-12 text-center text-sm text-gray-600">Add the first ordered decision rule.</div>}
          </div>
        </main>

        <aside className="rule-designer-inspector flex min-h-0 min-w-0 flex-col border-l border-surface-border bg-surface-light/55">
          <div className="border-b border-surface-border p-3">
            <h3 className="text-xs font-semibold text-gray-200">Rule &amp; simulation inspector</h3>
            <p className="mt-1 truncate text-[9px] text-gray-600">{selectedRule ? `${selectedRule.id} · ${selectedRule.enabled ? 'enabled' : 'disabled'}` : 'Select a rule'}</p>
          </div>
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3">
            {selectedRule && (
              <div>
                <div className="grid grid-cols-[1fr_auto] gap-2">
                  <Field label="Rule ID"><input className={inputClass()} value={selectedRule.id} onChange={(event) => {
                    const nextId = event.target.value
                    updateRule(selectedRule.id, (rule) => ({ ...rule, id: nextId }))
                    setSelectedRuleId(nextId)
                  }} /></Field>
                  <label className="mt-5 flex items-center gap-1.5 text-[10px] text-gray-400"><input type="checkbox" checked={selectedRule.enabled} onChange={(event) => updateRule(selectedRule.id, (rule) => ({ ...rule, enabled: event.target.checked }))} /> Active</label>
                </div>
                <Field label="Rule meaning"><textarea className={inputClass('mt-2 min-h-16 resize-y')} value={selectedRule.description} onChange={(event) => updateRule(selectedRule.id, (rule) => ({ ...rule, description: event.target.value }))} /></Field>
                <div className="mt-2 space-y-1 rounded border border-surface-border bg-surface p-2">
                  {model.inputs.map((input) => <div key={input.id} className="flex justify-between gap-2 text-[9px]"><span className="truncate text-gray-500">{input.label}</span><span className="text-amber-200">{conditionLabel(selectedRule.inputEntries[input.id] ?? emptyCondition())}</span></div>)}
                  {model.outputs.map((output) => <div key={output.id} className="flex justify-between gap-2 text-[9px]"><span className="truncate text-gray-500">{output.label}</span><span className="text-emerald-200">{selectedRule.outputEntries[output.id] || '…'}</span></div>)}
                </div>
                <button className="mt-2 flex items-center gap-1 text-[10px] text-red-300 hover:text-red-200" onClick={() => {
                  updateModel({ rules: model.rules.filter((rule) => rule.id !== selectedRule.id) })
                  setSelectedRuleId(model.rules.find((rule) => rule.id !== selectedRule.id)?.id)
                }}><Trash2 className="h-3 w-3" /> Delete selected rule</button>
              </div>
            )}

            <div className="border-t border-surface-border pt-4">
              <h4 className="mb-2 text-[9px] font-bold uppercase tracking-[0.14em] text-gray-600">Output priority</h4>
              {model.outputs.map((output) => (
                <Field key={output.id} label={output.label} hint="Highest priority first; required by PRIORITY and OUTPUT ORDER.">
                  <textarea
                    className={inputClass('mb-2 min-h-16 resize-y')}
                    placeholder={'REJECT\nREVIEW\nAPPROVE'}
                    value={output.predefinedValues.join('\n')}
                    onChange={(event) => commit((current) => ({
                      ...current,
                      outputs: current.outputs.map((item) => item.id === output.id
                        ? { ...item, predefinedValues: event.target.value.split(/\r?\n/).map((value) => value.trim()).filter(Boolean) }
                        : item),
                    }))}
                  />
                </Field>
              ))}
            </div>

            <div className="border-t border-surface-border pt-4">
              <h4 className="mb-2 flex items-center gap-1.5 text-[9px] font-bold uppercase tracking-[0.14em] text-gray-600"><FlaskConical className="h-3 w-3" /> Test this decision</h4>
              <div className="space-y-2">
                {model.inputs.map((input) => (
                  <Field key={input.id} label={`${input.label} · ${input.type}`}>
                    <input className={inputClass()} value={simulationInputs[input.variable] ?? ''} onChange={(event) => setSimulationInputs((current) => ({ ...current, [input.variable]: event.target.value }))} />
                  </Field>
                ))}
              </div>
              <button className="btn-primary mt-3 flex w-full items-center justify-center gap-1.5" disabled={simulating} onClick={() => void simulate()}>
                {simulating ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <FlaskConical className="h-3.5 w-3.5" />}
                Run typed simulation
              </button>
              {simulation && (
                <div className={`mt-3 rounded border p-2 ${simulation.accepted ? 'border-emerald-500/30 bg-emerald-500/5' : 'border-red-500/30 bg-red-500/5'}`}>
                  <div className="flex items-center gap-1.5 text-[10px] font-semibold text-gray-200">
                    {simulation.accepted ? <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" /> : <AlertTriangle className="h-3.5 w-3.5 text-red-300" />}
                    {simulation.accepted ? `${simulation.matchedRuleIds.length} rule(s) matched` : 'Simulation rejected'}
                  </div>
                  {simulation.results.map((result, index) => <pre key={index} className="mt-1 overflow-auto text-[9px] leading-4 text-emerald-200">{JSON.stringify(result, null, 2)}</pre>)}
                  {simulation.diagnostics.map((diagnostic) => <p key={`${diagnostic.code}-${diagnostic.ruleIds.join()}`} className="mt-1 text-[9px] leading-4 text-amber-200">{diagnostic.code} — {diagnostic.message}</p>)}
                </div>
              )}
            </div>
          </div>
        </aside>
      </div>

      {preview && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/65 p-3" role="dialog" aria-modal="true" aria-label="Generated DMN preview">
          <div className="flex max-h-[92vh] w-full max-w-5xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl">
            <div className="flex items-center gap-2 border-b border-surface-border px-4 py-3">
              <Table2 className="h-4 w-4 text-fuchsia-300" />
              <div className="min-w-0">
                <h3 className="truncate text-sm font-semibold text-gray-100">{preview.label}</h3>
                <p className="truncate text-[10px] text-gray-500">{preview.files[0]?.relativePath ?? 'Generation rejected'}</p>
              </div>
              <button className="ml-auto text-gray-500 hover:text-gray-200" onClick={() => setPreview(undefined)}><X className="h-4 w-4" /></button>
            </div>
            {preview.issues.length > 0 && (
              <div className="max-h-40 space-y-1 overflow-y-auto border-b border-surface-border bg-amber-950/20 px-4 py-2">
                {preview.issues.map((issue) => <div key={`${issue.code}-${issue.relativePath}`} className="flex gap-2 text-[10px] text-amber-200"><AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" /><span><strong>{issue.code}</strong> — {issue.message}</span></div>)}
              </div>
            )}
            <div className="min-h-0 flex-1 overflow-auto">
              {preview.files.length ? preview.files.map((file) => (
                <div key={file.relativePath}>
                  <div className="sticky top-0 border-b border-surface-border bg-surface-light px-4 py-2 text-[10px] font-medium text-gray-400">{file.mode} · {file.relativePath}</div>
                  <pre className="overflow-x-auto p-4 text-[11px] leading-5 text-gray-300">{file.resultContent}</pre>
                </div>
              )) : <div className="p-8 text-center text-sm text-gray-500">Fix the reported issues, then preview again.</div>}
            </div>
            <div className="flex items-center justify-end gap-2 border-t border-surface-border px-4 py-3">
              <button className="btn-secondary" onClick={() => setPreview(undefined)}>Keep editing</button>
              <button className="btn-primary flex items-center gap-1.5" disabled={!preview.accepted || !preview.planDigest || applying} onClick={() => void applySource()}>
                {applying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : preview.accepted ? <CheckCircle2 className="h-3.5 w-3.5" /> : <Save className="h-3.5 w-3.5" />}
                Apply source-safe DMN
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
