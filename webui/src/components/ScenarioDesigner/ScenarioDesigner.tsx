import { useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle, ArrowDown, ArrowUp, CheckCircle2, Code2, Database, FileCheck2,
  Loader2, Play, Plus, RefreshCw, Save, ShieldCheck, Trash2, Workflow,
} from 'lucide-react'
import { bridge } from '../../bridge'
import type {
  GraphArtifact,
  ScenarioAssertionOperator,
  ScenarioFieldValueModel,
  ScenarioStepKind,
  ScenarioStepModel,
  ScenarioTestModel,
  ScenarioValueModel,
  ScenarioValueType,
  ScenarioWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

type Pane = 'journey' | 'step' | 'output'

const valueTypes: ScenarioValueType[] = [
  'STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'UUID', 'LOCAL_DATE',
  'LOCAL_DATETIME', 'OFFSET_DATETIME', 'INSTANT', 'ENUM', 'NULL', 'VARIABLE',
]

const operators: ScenarioAssertionOperator[] = [
  'EQUALS', 'NOT_EQUALS', 'NULL', 'NOT_NULL', 'TRUE', 'FALSE',
  'GREATER_THAN', 'LESS_THAN', 'CONTAINS',
]

const expectedValueOperators = new Set<ScenarioAssertionOperator>([
  'EQUALS', 'NOT_EQUALS', 'GREATER_THAN', 'LESS_THAN', 'CONTAINS',
])

const kindLabels: Record<ScenarioStepKind, string> = {
  SEED_ENTITY: 'Seed entity',
  INVOKE_SERVICE: 'Invoke service',
  ASSERT_PROPERTY: 'Assert property',
  ASSERT_VALUE: 'Assert result value',
  ASSERT_ENTITY_COUNT: 'Assert entity count',
  ASSERT_SERVICE_FAILURE: 'Assert service failure',
}

const fieldClass = 'w-full min-w-0 rounded border border-surface-border bg-surface px-2.5 py-2 text-xs text-gray-100 outline-none transition focus:border-jmix-500'
const labelClass = 'mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500'

function stepId(index: number) {
  return `step${Date.now().toString(36)}${index}${Math.random().toString(36).slice(2, 6)}`
}

function blankValue(type: ScenarioValueType = 'STRING'): ScenarioValueModel {
  return { type, value: type === 'BOOLEAN' ? 'false' : '' }
}

function blankStep(kind: ScenarioStepKind, index: number, entityClass = ''): ScenarioStepModel {
  const base = {
    id: stepId(index),
    label: kindLabels[kind],
    kind,
    actorMode: 'SYSTEM' as const,
    fields: [],
    arguments: [],
  }
  if (kind === 'SEED_ENTITY') {
    return { ...base, entityClass, variableName: `entity${index + 1}` }
  }
  if (kind === 'INVOKE_SERVICE') {
    return { ...base, beanName: '', methodName: '', resultVariable: '' }
  }
  if (kind === 'ASSERT_SERVICE_FAILURE') {
    return {
      ...base,
      beanName: '',
      methodName: '',
      expectedExceptionClass: 'java.lang.IllegalStateException',
      messageContains: '',
    }
  }
  if (kind === 'ASSERT_PROPERTY') {
    return {
      ...base,
      targetVariable: '',
      propertyPath: '',
      operator: 'EQUALS',
      expected: blankValue(),
    }
  }
  if (kind === 'ASSERT_VALUE') {
    return {
      ...base,
      targetVariable: '',
      operator: 'EQUALS',
      expected: blankValue(),
    }
  }
  return {
    ...base,
    entityClass,
    jpql: entityClass ? `select e from ${entityClass.split('.').pop()} e` : '',
    expectedCount: 1,
  }
}

function inferredBeanName(service?: GraphArtifact) {
  const simpleName = (service?.semanticKey ?? service?.displayName ?? 'applicationService').split('.').pop() ?? 'applicationService'
  return simpleName.charAt(0).toLowerCase() + simpleName.slice(1)
}

function defaultScenario(workspace: ScenarioWorkspaceResponse): ScenarioTestModel {
  const destination = workspace.destinations.find((candidate) => candidate.id === workspace.defaultDestinationId)
    ?? workspace.destinations[0]
  return {
    name: 'Business scenario',
    description: 'Integration journey generated from the indexed application model.',
    destinationId: destination?.id ?? '',
    packageName: destination?.defaultPackage ? `${destination.defaultPackage}.scenario` : 'com.example.scenario',
    className: 'BusinessScenarioTest',
    steps: [],
  }
}

function loanLifecycle(workspace: ScenarioWorkspaceResponse): ScenarioTestModel {
  const model = defaultScenario(workspace)
  const entity = workspace.contextArtifacts.find((artifact) => artifact.kind === 'ENTITY')
  const service = workspace.contextArtifacts.find((artifact) => artifact.kind === 'SERVICE')
  const entityClass = entity?.semanticKey ?? ''
  const beanName = inferredBeanName(service)
  const invoke = (methodName: string, label: string, index: number): ScenarioStepModel => ({
    ...blankStep('INVOKE_SERVICE', index, entityClass),
    label,
    beanName,
    methodName,
    arguments: [{ type: 'VARIABLE', value: 'loanApp' }],
  })
  return {
    ...model,
    name: 'Loan lifecycle',
    description: 'Approve → disburse → post ledger → deduct → reconcile → early settle → close.',
    className: 'LoanLifecycleScenarioTest',
    steps: [
      {
        ...blankStep('SEED_ENTITY', 0, entityClass),
        label: 'Create eligible loan application',
        variableName: 'loanApp',
      },
      invoke('approve', 'Approve loan', 1),
      invoke('disburse', 'Disburse approved amount', 2),
      invoke('postLedger', 'Post financial ledger', 3),
      invoke('deductInstallment', 'Deduct payroll installment', 4),
      invoke('reconcile', 'Reconcile loan balance', 5),
      invoke('earlySettle', 'Settle remaining balance early', 6),
      invoke('close', 'Close loan account', 7),
      {
        ...blankStep('ASSERT_PROPERTY', 8, entityClass),
        label: 'Verify workflow is closed',
        targetVariable: 'loanApp',
        propertyPath: 'processState',
        operator: 'EQUALS',
        expected: { type: 'STRING', value: 'CLOSED' },
      },
      {
        ...blankStep('ASSERT_ENTITY_COUNT', 9, entityClass),
        label: 'Verify application remains persisted',
        entityClass,
        jpql: entityClass ? `select e from ${entityClass.split('.').pop()} e` : '',
        expectedCount: 1,
      },
    ],
  }
}

function securityScenario(workspace: ScenarioWorkspaceResponse): ScenarioTestModel {
  const model = defaultScenario(workspace)
  const entityClass = workspace.contextArtifacts.find((artifact) => artifact.kind === 'ENTITY')?.semanticKey ?? ''
  return {
    ...model,
    name: 'Role-scoped data access',
    description: 'Execute an entity visibility assertion as a real application user.',
    className: 'RoleScopedAccessScenarioTest',
    steps: [{
      ...blankStep('ASSERT_ENTITY_COUNT', 0, entityClass),
      label: 'Verify user-visible records',
      actorMode: 'USER',
      username: 'payroll-officer',
      entityClass,
      jpql: entityClass ? `select e from ${entityClass.split('.').pop()} e` : '',
      expectedCount: 0,
    }],
  }
}

function ValueEditor({
  value,
  onChange,
  variables,
}: {
  value: ScenarioValueModel
  onChange: (value: ScenarioValueModel) => void
  variables: string[]
}) {
  const noValue = value.type === 'NULL'
  return (
    <div className="grid min-w-0 grid-cols-[minmax(7rem,0.7fr)_minmax(9rem,1fr)] gap-2">
      <select
        aria-label="Value type"
        className={fieldClass}
        value={value.type}
        onChange={(event) => onChange(blankValue(event.target.value as ScenarioValueType))}
      >
        {valueTypes.map((type) => <option key={type} value={type}>{type.replace(/_/g, ' ')}</option>)}
      </select>
      {value.type === 'VARIABLE' ? (
        <select
          aria-label="Variable"
          className={fieldClass}
          value={value.value ?? ''}
          onChange={(event) => onChange({ ...value, value: event.target.value })}
        >
          <option value="">Select variable…</option>
          {variables.map((variable) => <option key={variable} value={variable}>{variable}</option>)}
        </select>
      ) : (
        <input
          aria-label="Value"
          className={fieldClass}
          disabled={noValue}
          value={noValue ? '' : value.value ?? ''}
          placeholder={value.type === 'ENUM' ? 'CONSTANT' : 'Value'}
          onChange={(event) => onChange({ ...value, value: event.target.value })}
        />
      )}
      {value.type === 'ENUM' && (
        <input
          aria-label="Enum Java type"
          className={`${fieldClass} col-span-2`}
          value={value.javaType ?? ''}
          placeholder="com.company.domain.Status"
          onChange={(event) => onChange({ ...value, javaType: event.target.value })}
        />
      )}
    </div>
  )
}

export default function ScenarioDesigner() {
  const [workspace, setWorkspace] = useState<ScenarioWorkspaceResponse | null>(null)
  const [scenario, setScenario] = useState<ScenarioTestModel | null>(null)
  const [selectedId, setSelectedId] = useState('')
  const [pane, setPane] = useState<Pane>('journey')
  const [loading, setLoading] = useState(true)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [message, setMessage] = useState('')

  const load = async (forceRefresh = false) => {
    setLoading(true)
    setMessage('')
    try {
      const response = await bridge.getScenarioWorkspace(forceRefresh)
      setWorkspace(response)
      setScenario((current) => current ?? defaultScenario(response))
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Unable to load the scenario workspace.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const selectedIndex = scenario?.steps.findIndex((step) => step.id === selectedId) ?? -1
  const selected = selectedIndex >= 0 ? scenario?.steps[selectedIndex] : undefined
  const entities = useMemo(
    () => workspace?.contextArtifacts.filter((artifact) => artifact.kind === 'ENTITY' || artifact.kind === 'DTO') ?? [],
    [workspace],
  )
  const services = useMemo(
    () => workspace?.contextArtifacts.filter((artifact) => artifact.kind === 'SERVICE') ?? [],
    [workspace],
  )
  const variables = useMemo(() => {
    if (!scenario) return []
    return scenario.steps
      .slice(0, selectedIndex < 0 ? scenario.steps.length : selectedIndex)
      .flatMap((step) => [step.variableName, step.resultVariable])
      .filter((value): value is string => Boolean(value))
  }, [scenario, selectedIndex])

  const updateScenario = (change: Partial<ScenarioTestModel>) => {
    setScenario((current) => current ? { ...current, ...change, sourceLocator: change.sourceLocator ?? current.sourceLocator } : current)
    setPreview(null)
  }

  const updateStep = (change: Partial<ScenarioStepModel>) => {
    if (!scenario || selectedIndex < 0) return
    updateScenario({
      steps: scenario.steps.map((step, index) => index === selectedIndex ? { ...step, ...change } : step),
    })
  }

  const addStep = (kind: ScenarioStepKind) => {
    if (!scenario) return
    const next = blankStep(kind, scenario.steps.length, entities[0]?.semanticKey)
    updateScenario({ steps: [...scenario.steps, next] })
    setSelectedId(next.id)
    setPane('step')
  }

  const removeStep = (index: number) => {
    if (!scenario) return
    const next = scenario.steps.filter((_, candidate) => candidate !== index)
    updateScenario({ steps: next })
    setSelectedId(next[Math.min(index, next.length - 1)]?.id ?? '')
  }

  const moveStep = (index: number, direction: -1 | 1) => {
    if (!scenario) return
    const target = index + direction
    if (target < 0 || target >= scenario.steps.length) return
    const next = [...scenario.steps]
    ;[next[index], next[target]] = [next[target], next[index]]
    updateScenario({ steps: next })
  }

  const applyTemplate = (template: 'loan' | 'security' | 'blank') => {
    if (!workspace) return
    const next = template === 'loan'
      ? loanLifecycle(workspace)
      : template === 'security'
        ? securityScenario(workspace)
        : defaultScenario(workspace)
    setScenario(next)
    setSelectedId(next.steps[0]?.id ?? '')
    setPreview(null)
    setPane(next.steps.length ? 'step' : 'journey')
  }

  const previewScenario = async () => {
    if (!scenario) return
    setPreviewing(true)
    setMessage('')
    try {
      const response = await bridge.previewScenarioTest(scenario)
      setPreview(response)
      setPane('output')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Preview failed.')
    } finally {
      setPreviewing(false)
    }
  }

  const applyScenario = async () => {
    if (!scenario || !preview?.accepted || !preview.planDigest) return
    setApplying(true)
    setMessage('')
    try {
      const response = await bridge.applyScenarioTest(scenario, preview.planDigest)
      if (response.success) {
        const successMessage = `Generated ${response.filesChanged.join(', ')}. The change is available in workspace undo.`
        const refreshed = await bridge.getScenarioWorkspace(true)
        setWorkspace(refreshed)
        const generated = refreshed.existingScenarios.find((document) =>
          document.model.packageName === scenario.packageName &&
          document.model.className === scenario.className &&
          document.editable,
        )
        if (generated) {
          setScenario(generated.model)
          setSelectedId(generated.model.steps.find((step) => step.id === selectedId)?.id ?? generated.model.steps[0]?.id ?? '')
        }
        setPreview(null)
        setMessage(successMessage)
      } else {
        setMessage(response.issues.map((issue) => issue.message).join(' '))
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Generation failed.')
    } finally {
      setApplying(false)
    }
  }

  const chooseDestination = (destinationId: string) => {
    if (!workspace || !scenario) return
    const destination = workspace.destinations.find((candidate) => candidate.id === destinationId)
    updateScenario({
      destinationId,
      packageName: destination?.defaultPackage ? `${destination.defaultPackage}.scenario` : scenario.packageName,
    })
  }

  if (loading && !workspace) {
    return <div className="flex h-full items-center justify-center gap-2 text-sm text-gray-400"><Loader2 className="h-4 w-4 animate-spin" />Indexing scenario context…</div>
  }

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden bg-surface">
      <header className="flex min-w-0 flex-wrap items-center gap-3 border-b border-surface-border bg-surface-light px-4 py-3">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <FileCheck2 className="h-4 w-4 text-jmix-400" />
            <h2 className="truncate text-sm font-semibold text-gray-100">Scenario Test Builder</h2>
          </div>
          <p className="mt-0.5 truncate text-[11px] text-gray-500">
            Executable Jmix integration journeys · indexed modules · security context · source-safe round trip
          </p>
        </div>
        <button
          className="flex items-center gap-1.5 rounded border border-surface-border px-2.5 py-1.5 text-xs text-gray-300 hover:bg-surface-lighter"
          onClick={() => void load(true)}
          disabled={loading}
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />Refresh index
        </button>
        <button
          className="flex items-center gap-1.5 rounded bg-jmix-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-500 disabled:opacity-50"
          onClick={() => void previewScenario()}
          disabled={!scenario || previewing}
        >
          {previewing ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Play className="h-3.5 w-3.5" />}
          Preview Java
        </button>
      </header>

      <ResponsivePaneSwitcher
        value={pane}
        onChange={setPane}
        options={[
          { id: 'journey', label: 'Journey', badge: scenario?.steps.length ?? 0 },
          { id: 'step', label: 'Step editor', badge: selectedIndex >= 0 ? selectedIndex + 1 : undefined },
          { id: 'output', label: 'Review & generate', badge: preview?.issues.length },
        ]}
        label="Scenario builder panels"
      />

      {message && (
        <div className={`mx-3 mt-3 rounded border px-3 py-2 text-xs ${message.startsWith('Generated') ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200' : 'border-amber-500/30 bg-amber-500/10 text-amber-200'}`}>
          {message}
        </div>
      )}

      <div className="grid min-h-0 min-w-0 flex-1 grid-cols-1 overflow-hidden lg:grid-cols-[minmax(13rem,0.7fr)_minmax(20rem,1.25fr)_minmax(18rem,1fr)]">
        <section className={`${pane === 'journey' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col overflow-hidden border-r border-surface-border lg:flex`}>
          <div className="border-b border-surface-border p-3">
            <div className="mb-2 flex items-center justify-between">
              <h3 className="text-[11px] font-semibold uppercase tracking-wider text-gray-400">Journey</h3>
              <span className="rounded bg-surface-lighter px-1.5 py-0.5 text-[10px] text-gray-500">{scenario?.steps.length ?? 0} steps</span>
            </div>
            <div className="grid grid-cols-3 gap-1">
              <button className="rounded border border-surface-border px-1.5 py-1.5 text-[10px] text-gray-300 hover:bg-surface-lighter" onClick={() => applyTemplate('loan')}>Loan flow</button>
              <button className="rounded border border-surface-border px-1.5 py-1.5 text-[10px] text-gray-300 hover:bg-surface-lighter" onClick={() => applyTemplate('security')}>Security</button>
              <button className="rounded border border-surface-border px-1.5 py-1.5 text-[10px] text-gray-300 hover:bg-surface-lighter" onClick={() => applyTemplate('blank')}>Blank</button>
            </div>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-2">
            {scenario?.steps.map((step, index) => (
              <div
                key={step.id}
                className={`mb-1.5 rounded border p-2 transition ${selectedId === step.id ? 'border-jmix-500 bg-jmix-500/10' : 'border-surface-border bg-surface-light hover:border-gray-600'}`}
              >
                <button
                  className="w-full min-w-0 text-left"
                  onClick={() => {
                    setSelectedId(step.id)
                    setPane('step')
                  }}
                >
                  <div className="flex items-center gap-2">
                    <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-surface-lighter text-[10px] text-gray-400">{index + 1}</span>
                    <span className="min-w-0 flex-1 truncate text-xs text-gray-200">{step.label}</span>
                  </div>
                  <div className="ml-7 mt-1 text-[9px] uppercase tracking-wide text-gray-600">{kindLabels[step.kind]} · {step.actorMode}</div>
                </button>
                <div className="mt-1.5 flex justify-end gap-1">
                  <button aria-label="Move step up" disabled={index === 0} className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:opacity-25" onClick={() => moveStep(index, -1)}><ArrowUp className="h-3 w-3" /></button>
                  <button aria-label="Move step down" disabled={index === scenario.steps.length - 1} className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200 disabled:opacity-25" onClick={() => moveStep(index, 1)}><ArrowDown className="h-3 w-3" /></button>
                  <button aria-label="Delete step" className="rounded p-1 text-gray-500 hover:bg-red-500/10 hover:text-red-300" onClick={() => removeStep(index)}><Trash2 className="h-3 w-3" /></button>
                </div>
              </div>
            ))}
            {scenario?.steps.length === 0 && (
              <div className="rounded border border-dashed border-surface-border p-5 text-center text-xs text-gray-600">
                Choose a production template or add the first step.
              </div>
            )}
          </div>
          <div className="grid grid-cols-2 gap-1.5 border-t border-surface-border p-2">
            {(Object.keys(kindLabels) as ScenarioStepKind[]).map((kind) => (
              <button key={kind} className="flex min-w-0 items-center gap-1 rounded border border-surface-border px-2 py-1.5 text-[10px] text-gray-300 hover:bg-surface-lighter" onClick={() => addStep(kind)}>
                <Plus className="h-3 w-3 shrink-0" /><span className="truncate">{kindLabels[kind]}</span>
              </button>
            ))}
          </div>
        </section>

        <section className={`${pane === 'step' ? 'block' : 'hidden'} min-h-0 min-w-0 overflow-y-auto border-r border-surface-border p-4 lg:block`}>
          <div className="mb-4 grid min-w-0 grid-cols-1 gap-3 xl:grid-cols-2">
            <label><span className={labelClass}>Scenario name</span><input className={fieldClass} value={scenario?.name ?? ''} onChange={(event) => updateScenario({ name: event.target.value })} /></label>
            <label><span className={labelClass}>Java class</span><input className={fieldClass} value={scenario?.className ?? ''} onChange={(event) => updateScenario({ className: event.target.value })} /></label>
            <label><span className={labelClass}>Target module</span>
              <select className={fieldClass} value={scenario?.destinationId ?? ''} onChange={(event) => chooseDestination(event.target.value)}>
                {workspace?.destinations.map((destination) => (
                  <option key={destination.id} value={destination.id}>{destination.moduleId} · {destination.testSourceRoot}{destination.recommended ? ' · recommended' : ''}</option>
                ))}
              </select>
            </label>
            <label><span className={labelClass}>Test package</span><input className={fieldClass} value={scenario?.packageName ?? ''} onChange={(event) => updateScenario({ packageName: event.target.value })} /></label>
            <label className="xl:col-span-2"><span className={labelClass}>Purpose</span><textarea rows={2} className={fieldClass} value={scenario?.description ?? ''} onChange={(event) => updateScenario({ description: event.target.value })} /></label>
          </div>

          {!selected ? (
            <div className="flex min-h-48 flex-col items-center justify-center rounded border border-dashed border-surface-border text-center">
              <Workflow className="mb-2 h-7 w-7 text-gray-700" />
              <p className="text-sm text-gray-400">Select or add a journey step</p>
              <p className="mt-1 max-w-sm text-[11px] text-gray-600">Every step runs inside the real Jmix Spring context and can use system or named-user security.</p>
            </div>
          ) : (
            <div className="rounded border border-surface-border bg-surface-light/50">
              <div className="flex items-center gap-2 border-b border-surface-border px-3 py-2.5">
                {selected.kind === 'SEED_ENTITY' ? <Database className="h-4 w-4 text-sky-400" /> : selected.kind === 'INVOKE_SERVICE' ? <Workflow className="h-4 w-4 text-violet-400" /> : <CheckCircle2 className="h-4 w-4 text-emerald-400" />}
                <h3 className="text-xs font-semibold text-gray-200">Step {selectedIndex + 1} · {kindLabels[selected.kind]}</h3>
              </div>
              <div className="space-y-3 p-3">
                <label><span className={labelClass}>Step label</span><input className={fieldClass} value={selected.label} onChange={(event) => updateStep({ label: event.target.value })} /></label>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <label><span className={labelClass}>Run as</span>
                    <select className={fieldClass} value={selected.actorMode} onChange={(event) => updateStep({ actorMode: event.target.value as 'SYSTEM' | 'USER' })}>
                      <option value="SYSTEM">System account</option>
                      <option value="USER">Named user</option>
                    </select>
                  </label>
                  {selected.actorMode === 'USER' && <label><span className={labelClass}>Username</span><input className={fieldClass} value={selected.username ?? ''} onChange={(event) => updateStep({ username: event.target.value })} /></label>}
                </div>

                {selected.kind === 'SEED_ENTITY' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Indexed entity</span>
                        <select className={fieldClass} value={selected.entityClass ?? ''} onChange={(event) => updateStep({ entityClass: event.target.value })}>
                          <option value="">Select entity…</option>
                          {entities.map((entity) => <option key={entity.id} value={entity.semanticKey}>{entity.displayName} · {entity.owner.moduleId}</option>)}
                        </select>
                      </label>
                      <label><span className={labelClass}>Save as variable</span><input className={fieldClass} value={selected.variableName ?? ''} onChange={(event) => updateStep({ variableName: event.target.value })} /></label>
                    </div>
                    <FieldValues fields={selected.fields} variables={variables} onChange={(fields) => updateStep({ fields })} />
                  </>
                )}

                {selected.kind === 'INVOKE_SERVICE' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Spring bean</span>
                        <input className={fieldClass} list="scenario-service-beans" value={selected.beanName ?? ''} onChange={(event) => updateStep({ beanName: event.target.value })} />
                        <datalist id="scenario-service-beans">{services.map((service) => <option key={service.id} value={inferredBeanName(service)}>{service.displayName}</option>)}</datalist>
                      </label>
                      <label><span className={labelClass}>Public method</span><input className={fieldClass} value={selected.methodName ?? ''} onChange={(event) => updateStep({ methodName: event.target.value })} /></label>
                      <label className="sm:col-span-2"><span className={labelClass}>Optional result variable</span><input className={fieldClass} value={selected.resultVariable ?? ''} onChange={(event) => updateStep({ resultVariable: event.target.value })} /></label>
                    </div>
                    <Values
                      title="Method arguments"
                      values={selected.arguments}
                      variables={variables}
                      onChange={(argumentsValue) => updateStep({ arguments: argumentsValue })}
                    />
                  </>
                )}

                {selected.kind === 'ASSERT_SERVICE_FAILURE' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Spring bean</span>
                        <input className={fieldClass} list="scenario-service-beans" value={selected.beanName ?? ''} onChange={(event) => updateStep({ beanName: event.target.value })} />
                      </label>
                      <label><span className={labelClass}>Public method</span><input className={fieldClass} value={selected.methodName ?? ''} onChange={(event) => updateStep({ methodName: event.target.value })} /></label>
                      <label><span className={labelClass}>Expected exception class</span><input className={`${fieldClass} font-mono`} value={selected.expectedExceptionClass ?? ''} placeholder="com.company.BusinessException" onChange={(event) => updateStep({ expectedExceptionClass: event.target.value })} /></label>
                      <label><span className={labelClass}>Message contains (optional)</span><input className={fieldClass} value={selected.messageContains ?? ''} onChange={(event) => updateStep({ messageContains: event.target.value })} /></label>
                    </div>
                    <Values
                      title="Method arguments"
                      values={selected.arguments}
                      variables={variables}
                      onChange={(argumentsValue) => updateStep({ arguments: argumentsValue })}
                    />
                  </>
                )}

                {selected.kind === 'ASSERT_PROPERTY' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Target variable</span>
                        <select className={fieldClass} value={selected.targetVariable ?? ''} onChange={(event) => updateStep({ targetVariable: event.target.value })}>
                          <option value="">Select variable…</option>
                          {variables.map((variable) => <option key={variable} value={variable}>{variable}</option>)}
                        </select>
                      </label>
                      <label><span className={labelClass}>Property path</span><input className={fieldClass} value={selected.propertyPath ?? ''} placeholder="status or customer.name" onChange={(event) => updateStep({ propertyPath: event.target.value })} /></label>
                      <label><span className={labelClass}>Assertion</span>
                        <select className={fieldClass} value={selected.operator ?? 'EQUALS'} onChange={(event) => updateStep({ operator: event.target.value as ScenarioAssertionOperator })}>
                          {operators.map((operator) => <option key={operator} value={operator}>{operator.replace(/_/g, ' ')}</option>)}
                        </select>
                      </label>
                    </div>
                    {selected.operator && expectedValueOperators.has(selected.operator) && (
                      <div><span className={labelClass}>Expected value</span><ValueEditor value={selected.expected ?? blankValue()} variables={variables} onChange={(expected) => updateStep({ expected })} /></div>
                    )}
                  </>
                )}

                {selected.kind === 'ASSERT_VALUE' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Result variable</span>
                        <select className={fieldClass} value={selected.targetVariable ?? ''} onChange={(event) => updateStep({ targetVariable: event.target.value })}>
                          <option value="">Select variable…</option>
                          {variables.map((variable) => <option key={variable} value={variable}>{variable}</option>)}
                        </select>
                      </label>
                      <label><span className={labelClass}>Assertion</span>
                        <select className={fieldClass} value={selected.operator ?? 'EQUALS'} onChange={(event) => updateStep({ operator: event.target.value as ScenarioAssertionOperator })}>
                          {operators.map((operator) => <option key={operator} value={operator}>{operator.replace(/_/g, ' ')}</option>)}
                        </select>
                      </label>
                    </div>
                    {selected.operator && expectedValueOperators.has(selected.operator) && (
                      <div><span className={labelClass}>Expected value</span><ValueEditor value={selected.expected ?? blankValue()} variables={variables} onChange={(expected) => updateStep({ expected })} /></div>
                    )}
                  </>
                )}

                {selected.kind === 'ASSERT_ENTITY_COUNT' && (
                  <>
                    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label><span className={labelClass}>Indexed entity</span>
                        <select className={fieldClass} value={selected.entityClass ?? ''} onChange={(event) => updateStep({ entityClass: event.target.value })}>
                          <option value="">Select entity…</option>
                          {entities.map((entity) => <option key={entity.id} value={entity.semanticKey}>{entity.displayName} · {entity.owner.moduleId}</option>)}
                        </select>
                      </label>
                      <label><span className={labelClass}>Expected count</span><input type="number" min={0} className={fieldClass} value={selected.expectedCount ?? 0} onChange={(event) => updateStep({ expectedCount: Number(event.target.value) })} /></label>
                    </div>
                    <label><span className={labelClass}>Read-only JPQL</span><textarea rows={3} className={`${fieldClass} font-mono`} value={selected.jpql ?? ''} onChange={(event) => updateStep({ jpql: event.target.value })} /></label>
                  </>
                )}
              </div>
            </div>
          )}
        </section>

        <section className={`${pane === 'output' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-col overflow-hidden lg:flex`}>
          <div className="border-b border-surface-border p-3">
            <div className="mb-2 flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-emerald-400" />
              <h3 className="text-[11px] font-semibold uppercase tracking-wider text-gray-400">Indexed context</h3>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center">
              <ContextStat label="Modules" value={new Set(workspace?.destinations.map((destination) => destination.moduleId)).size} />
              <ContextStat label="Entities" value={entities.length} />
              <ContextStat label="Services" value={services.length} />
            </div>
            {(workspace?.issues.length ?? 0) > 0 && (
              <div className="mt-2 rounded border border-amber-500/30 bg-amber-500/10 p-2 text-[10px] text-amber-200">
                {workspace?.issues.map((issue) => <div key={issue.code}>{issue.message}</div>)}
              </div>
            )}
          </div>

          <div className="min-h-0 flex-1 overflow-y-auto p-3">
            <div className="mb-3">
              <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">Existing visual scenarios</h3>
              {workspace?.existingScenarios.length ? workspace.existingScenarios.map((document) => (
                <button
                  key={document.locator.relativePath}
                  disabled={!document.editable}
                  title={document.issue}
                  className="mb-1 w-full rounded border border-surface-border p-2 text-left disabled:cursor-not-allowed disabled:opacity-50"
                  onClick={() => {
                    setScenario(document.model)
                    setSelectedId(document.model.steps[0]?.id ?? '')
                    setPreview(null)
                    setPane('step')
                  }}
                >
                  <div className="truncate text-xs text-gray-200">{document.model.name}</div>
                  <div className="mt-0.5 truncate text-[10px] text-gray-600">{document.locator.relativePath}</div>
                  {!document.editable && <div className="mt-1 text-[10px] text-amber-300">Manual Java changes protected</div>}
                </button>
              )) : <p className="rounded border border-dashed border-surface-border p-2 text-[10px] text-gray-600">No generated scenarios discovered yet.</p>}
            </div>

            <h3 className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">Safe change preview</h3>
            {!preview ? (
              <div className="rounded border border-dashed border-surface-border p-5 text-center">
                <Code2 className="mx-auto mb-2 h-6 w-6 text-gray-700" />
                <p className="text-xs text-gray-500">Preview validates every step and shows the exact Java file before writing.</p>
              </div>
            ) : (
              <>
                <div className={`mb-2 rounded border p-2 text-xs ${preview.accepted ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200' : 'border-red-500/30 bg-red-500/10 text-red-200'}`}>
                  {preview.accepted ? <CheckCircle2 className="mr-1 inline h-3.5 w-3.5" /> : <AlertTriangle className="mr-1 inline h-3.5 w-3.5" />}
                  {preview.accepted ? preview.label : 'Generation blocked until the issues below are resolved.'}
                </div>
                {preview.issues.map((issue) => (
                  <div key={`${issue.code}-${issue.message}`} className="mb-1 rounded border border-amber-500/20 bg-amber-500/5 p-2 text-[10px] text-amber-200">
                    <strong>{issue.code}</strong> · {issue.message}
                  </div>
                ))}
                {preview.files.map((file) => (
                  <div key={file.relativePath} className="mb-2 overflow-hidden rounded border border-surface-border">
                    <div className="flex min-w-0 items-center justify-between gap-2 border-b border-surface-border bg-surface-light px-2 py-1.5 text-[10px]">
                      <span className="min-w-0 truncate text-gray-400">{file.relativePath}</span>
                      <span className="shrink-0 rounded bg-surface-lighter px-1.5 py-0.5 text-gray-500">{file.mode}</span>
                    </div>
                    <pre className="max-h-80 min-w-0 overflow-auto whitespace-pre p-2 text-[10px] leading-relaxed text-gray-300">{file.resultContent}</pre>
                  </div>
                ))}
              </>
            )}
          </div>
          <div className="border-t border-surface-border p-3">
            <button
              className="flex w-full items-center justify-center gap-2 rounded bg-emerald-600 px-3 py-2 text-xs font-semibold text-white hover:bg-emerald-500 disabled:cursor-not-allowed disabled:opacity-40"
              disabled={!preview?.accepted || !preview.planDigest || applying}
              onClick={() => void applyScenario()}
            >
              {applying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
              Generate reviewed integration test
            </button>
          </div>
        </section>
      </div>
    </div>
  )
}

function ContextStat({ label, value }: { label: string; value: number }) {
  return <div className="rounded bg-surface-lighter p-2"><div className="text-sm font-semibold text-gray-200">{value}</div><div className="text-[9px] uppercase tracking-wide text-gray-600">{label}</div></div>
}

function FieldValues({
  fields,
  variables,
  onChange,
}: {
  fields: ScenarioFieldValueModel[]
  variables: string[]
  onChange: (fields: ScenarioFieldValueModel[]) => void
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className={labelClass}>Entity field values</span>
        <button className="flex items-center gap-1 text-[10px] text-jmix-400 hover:text-jmix-300" onClick={() => onChange([...fields, { property: '', value: blankValue() }])}><Plus className="h-3 w-3" />Add field</button>
      </div>
      <div className="space-y-2">
        {fields.map((field, index) => (
          <div key={index} className="grid min-w-0 grid-cols-[minmax(7rem,0.6fr)_minmax(13rem,1fr)_auto] items-start gap-2">
            <input className={fieldClass} value={field.property} placeholder="property" onChange={(event) => onChange(fields.map((candidate, candidateIndex) => candidateIndex === index ? { ...candidate, property: event.target.value } : candidate))} />
            <ValueEditor value={field.value} variables={variables} onChange={(value) => onChange(fields.map((candidate, candidateIndex) => candidateIndex === index ? { ...candidate, value } : candidate))} />
            <button aria-label="Remove field" className="mt-1 rounded p-1.5 text-gray-500 hover:bg-red-500/10 hover:text-red-300" onClick={() => onChange(fields.filter((_, candidateIndex) => candidateIndex !== index))}><Trash2 className="h-3.5 w-3.5" /></button>
          </div>
        ))}
      </div>
    </div>
  )
}

function Values({
  title,
  values,
  variables,
  onChange,
}: {
  title: string
  values: ScenarioValueModel[]
  variables: string[]
  onChange: (values: ScenarioValueModel[]) => void
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className={labelClass}>{title}</span>
        <button className="flex items-center gap-1 text-[10px] text-jmix-400 hover:text-jmix-300" onClick={() => onChange([...values, blankValue()])}><Plus className="h-3 w-3" />Add argument</button>
      </div>
      <div className="space-y-2">
        {values.map((value, index) => (
          <div key={index} className="grid min-w-0 grid-cols-[minmax(13rem,1fr)_auto] items-start gap-2">
            <ValueEditor value={value} variables={variables} onChange={(next) => onChange(values.map((candidate, candidateIndex) => candidateIndex === index ? next : candidate))} />
            <button aria-label="Remove argument" className="mt-1 rounded p-1.5 text-gray-500 hover:bg-red-500/10 hover:text-red-300" onClick={() => onChange(values.filter((_, candidateIndex) => candidateIndex !== index))}><Trash2 className="h-3.5 w-3.5" /></button>
          </div>
        ))}
      </div>
    </div>
  )
}
