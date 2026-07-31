import {
  AlertTriangle,
  Braces,
  Calculator,
  CheckCircle2,
  FileCode2,
  FunctionSquare,
  GitBranch,
  GripVertical,
  Loader2,
  Plus,
  Redo2,
  RefreshCw,
  Save,
  ShieldCheck,
  Table2,
  Trash2,
  Undo2,
  X,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  GraphArtifact,
  RuleDataType,
  RuleExpressionKind,
  RuleExpressionModel,
  RuleParameterModel,
  RuleValueSource,
  VisualRuleKind,
  VisualRuleModel,
  VisualRuleWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'
import { DmnDecisionDesigner } from './DmnDecisionDesigner'

const HISTORY_LIMIT = 100
const dataTypes: RuleDataType[] = [
  'STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'UUID', 'LOCAL_DATE',
  'LOCAL_DATE_TIME', 'OFFSET_DATE_TIME', 'INSTANT', 'ENUM', 'ENTITY', 'OBJECT',
]
const roundingModes = ['HALF_EVEN', 'HALF_UP', 'HALF_DOWN', 'UP', 'DOWN', 'CEILING', 'FLOOR']

type CatalogItem = {
  kind: RuleExpressionKind
  label: string
  description: string
  group: string
  dataType: RuleDataType
  arity: number
}

const catalog: CatalogItem[] = [
  { kind: 'VALUE', label: 'Value', description: 'Literal or input parameter', group: 'Inputs', dataType: 'STRING', arity: 0 },
  { kind: 'PROPERTY', label: 'Entity property', description: 'Indexed entity attribute', group: 'Inputs', dataType: 'STRING', arity: 0 },
  { kind: 'ADD', label: 'Add', description: 'Exact decimal addition', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'SUBTRACT', label: 'Subtract', description: 'Exact decimal subtraction', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'MULTIPLY', label: 'Multiply', description: 'Exact decimal multiplication', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'DIVIDE', label: 'Divide', description: 'Scale-aware safe division', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'ROUND', label: 'Round', description: 'Configured scale and mode', group: 'Money', dataType: 'DECIMAL', arity: 1 },
  { kind: 'MIN', label: 'Minimum', description: 'Smaller decimal', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'MAX', label: 'Maximum', description: 'Larger decimal', group: 'Money', dataType: 'DECIMAL', arity: 2 },
  { kind: 'EQUALS', label: 'Equals', description: 'Null-safe equality', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'GREATER_THAN', label: 'Greater than', description: 'Typed ordered comparison', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'GREATER_THAN_OR_EQUAL', label: 'At least', description: 'Inclusive lower boundary', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'LESS_THAN', label: 'Less than', description: 'Typed ordered comparison', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'AND', label: 'All conditions', description: 'Boolean AND', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'OR', label: 'Any condition', description: 'Boolean OR', group: 'Decisions', dataType: 'BOOLEAN', arity: 2 },
  { kind: 'NOT', label: 'Not', description: 'Invert a condition', group: 'Decisions', dataType: 'BOOLEAN', arity: 1 },
  { kind: 'IF', label: 'If / otherwise', description: 'Typed conditional result', group: 'Decisions', dataType: 'STRING', arity: 3 },
  { kind: 'COALESCE', label: 'First available', description: 'First non-null value', group: 'Null safety', dataType: 'STRING', arity: 2 },
  { kind: 'IS_NULL', label: 'Is missing', description: 'Null check', group: 'Null safety', dataType: 'BOOLEAN', arity: 1 },
  { kind: 'IS_NOT_NULL', label: 'Is present', description: 'Required value check', group: 'Null safety', dataType: 'BOOLEAN', arity: 1 },
  { kind: 'CONCAT', label: 'Join text', description: 'Null-safe concatenation', group: 'Text', dataType: 'STRING', arity: 2 },
  { kind: 'TRIM', label: 'Trim text', description: 'Remove surrounding space', group: 'Text', dataType: 'STRING', arity: 1 },
  { kind: 'UPPER', label: 'Uppercase', description: 'Locale-safe uppercase', group: 'Text', dataType: 'STRING', arity: 1 },
  { kind: 'LENGTH', label: 'Text length', description: 'Character count', group: 'Text', dataType: 'INTEGER', arity: 1 },
  { kind: 'DATE_PLUS_DAYS', label: 'Add days', description: 'Calculate a deadline', group: 'Dates', dataType: 'LOCAL_DATE', arity: 2 },
  { kind: 'DAYS_BETWEEN', label: 'Days between', description: 'Date interval', group: 'Dates', dataType: 'LONG', arity: 2 },
  { kind: 'IN_LIST', label: 'In allowed list', description: 'Membership check', group: 'Collections', dataType: 'BOOLEAN', arity: 3 },
]

function id(prefix: string) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
}

function literal(dataType: RuleDataType = 'STRING', value = ''): RuleExpressionModel {
  const defaults: Partial<Record<RuleDataType, string>> = {
    INTEGER: '0', LONG: '0', DECIMAL: '0.00', BOOLEAN: 'false', LOCAL_DATE: '2026-01-01',
  }
  return {
    id: id('expression'),
    label: 'Value',
    kind: 'VALUE',
    dataType,
    valueSource: 'LITERAL',
    value: value || defaults[dataType] || '',
    children: [],
  }
}

function expressionFor(item: CatalogItem): RuleExpressionModel {
  const childType = ['AND', 'OR', 'NOT', 'IS_NULL', 'IS_NOT_NULL'].includes(item.kind)
    ? 'BOOLEAN'
    : ['ADD', 'SUBTRACT', 'MULTIPLY', 'DIVIDE', 'ROUND', 'MIN', 'MAX'].includes(item.kind)
      ? 'DECIMAL'
      : item.kind === 'DATE_PLUS_DAYS'
        ? 'LOCAL_DATE'
        : item.kind === 'DAYS_BETWEEN'
          ? 'LOCAL_DATE'
          : 'STRING'
  const children = Array.from({ length: item.arity }, (_, index) => {
    if (item.kind === 'IF' && index === 0) return literal('BOOLEAN', 'true')
    if (item.kind === 'DATE_PLUS_DAYS' && index === 1) return literal('LONG', '1')
    if (['GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN'].includes(item.kind)) return literal('DECIMAL')
    return literal(childType)
  })
  return {
    id: id('expression'),
    label: item.label,
    kind: item.kind,
    dataType: item.dataType,
    valueSource: item.kind === 'VALUE' ? 'LITERAL' : undefined,
    value: item.kind === 'VALUE' ? '' : undefined,
    children,
  }
}

function defaultRule(workspace?: VisualRuleWorkspaceResponse): VisualRuleModel {
  const destination = workspace?.destinations.find((item) => item.recommended) ?? workspace?.destinations[0]
  return {
    name: 'Net payroll amount',
    description: 'Reusable server-enforced financial calculation.',
    kind: 'FORMULA',
    destinationId: destination?.id ?? '',
    packageName: `${destination?.defaultPackage ?? 'com.example.app'}.rule`.replace('.service.rule', '.rule'),
    className: 'NetPayrollAmountRule',
    beanName: 'netPayrollAmountRule',
    methodName: 'evaluate',
    outputJavaType: 'java.math.BigDecimal',
    parameters: [
      { name: 'grossAmount', javaType: 'java.math.BigDecimal', dataType: 'DECIMAL', nullable: false },
      { name: 'deductions', javaType: 'java.math.BigDecimal', dataType: 'DECIMAL', nullable: false },
    ],
    expression: {
      id: id('expression'),
      label: 'Gross minus deductions',
      kind: 'ROUND',
      dataType: 'DECIMAL',
      children: [{
        id: id('expression'),
        label: 'Subtract',
        kind: 'SUBTRACT',
        dataType: 'DECIMAL',
        children: [
          parameterExpression('grossAmount', 'DECIMAL'),
          parameterExpression('deductions', 'DECIMAL'),
        ],
      }],
    },
    decimalScale: 2,
    roundingMode: 'HALF_EVEN',
  }
}

function parameterExpression(name: string, dataType: RuleDataType): RuleExpressionModel {
  return {
    id: id('expression'),
    label: name,
    kind: 'VALUE',
    dataType,
    valueSource: 'PARAMETER',
    parameterName: name,
    children: [],
  }
}

function validatorTemplate(workspace?: VisualRuleWorkspaceResponse): VisualRuleModel {
  const base = defaultRule(workspace)
  const value = parameterExpression('value', 'STRING')
  return {
    ...base,
    name: 'Required employee identifier',
    kind: 'VALIDATOR',
    className: 'EmployeeIdentifierValidator',
    beanName: 'employeeIdentifierValidator',
    outputJavaType: 'boolean',
    parameters: [{ name: 'value', javaType: 'String', dataType: 'STRING', nullable: false }],
    expression: {
      id: id('expression'),
      label: 'At least eight characters',
      kind: 'GREATER_THAN_OR_EQUAL',
      dataType: 'BOOLEAN',
      children: [{
        id: id('expression'),
        label: 'Trimmed length',
        kind: 'LENGTH',
        dataType: 'INTEGER',
        children: [{
          id: id('expression'),
          label: 'Trim input',
          kind: 'TRIM',
          dataType: 'STRING',
          children: [value],
        }],
      }, literal('INTEGER', '8')],
    },
    validationMessage: 'Employee identifier must contain at least eight characters.',
  }
}

function predicateTemplate(workspace?: VisualRuleWorkspaceResponse): VisualRuleModel {
  const base = defaultRule(workspace)
  return {
    ...base,
    name: 'Loan amount eligibility',
    kind: 'PREDICATE',
    className: 'LoanAmountEligibilityRule',
    beanName: 'loanAmountEligibilityRule',
    outputJavaType: 'boolean',
    parameters: [
      { name: 'requestedAmount', javaType: 'java.math.BigDecimal', dataType: 'DECIMAL', nullable: false },
      { name: 'eligibleLimit', javaType: 'java.math.BigDecimal', dataType: 'DECIMAL', nullable: false },
    ],
    expression: {
      id: id('expression'),
      label: 'Requested amount is within limit',
      kind: 'LESS_THAN_OR_EQUAL',
      dataType: 'BOOLEAN',
      children: [
        parameterExpression('requestedAmount', 'DECIMAL'),
        parameterExpression('eligibleLimit', 'DECIMAL'),
      ],
    },
  }
}

function dateTemplate(workspace?: VisualRuleWorkspaceResponse): VisualRuleModel {
  const base = defaultRule(workspace)
  return {
    ...base,
    name: 'Settlement deadline',
    className: 'SettlementDeadlineRule',
    beanName: 'settlementDeadlineRule',
    outputJavaType: 'java.time.LocalDate',
    parameters: [
      { name: 'approvedOn', javaType: 'java.time.LocalDate', dataType: 'LOCAL_DATE', nullable: false },
      { name: 'settlementDays', javaType: 'long', dataType: 'LONG', nullable: false },
    ],
    expression: {
      id: id('expression'),
      label: 'Approved date plus settlement days',
      kind: 'DATE_PLUS_DAYS',
      dataType: 'LOCAL_DATE',
      children: [
        parameterExpression('approvedOn', 'LOCAL_DATE'),
        parameterExpression('settlementDays', 'LONG'),
      ],
    },
  }
}

function findExpression(root: RuleExpressionModel, expressionId?: string): RuleExpressionModel | undefined {
  if (!expressionId) return undefined
  if (root.id === expressionId) return root
  for (const child of root.children) {
    const found = findExpression(child, expressionId)
    if (found) return found
  }
  return undefined
}

function replaceExpression(
  root: RuleExpressionModel,
  expressionId: string,
  replacement: RuleExpressionModel,
): RuleExpressionModel {
  if (root.id === expressionId) return replacement
  return {
    ...root,
    children: root.children.map((child) => replaceExpression(child, expressionId, replacement)),
  }
}

function updateExpression(
  root: RuleExpressionModel,
  expressionId: string,
  patch: Partial<RuleExpressionModel>,
): RuleExpressionModel {
  if (root.id === expressionId) return { ...root, ...patch }
  return {
    ...root,
    children: root.children.map((child) => updateExpression(child, expressionId, patch)),
  }
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

function ExpressionTree({
  expression,
  selectedId,
  onSelect,
  onDropExpression,
}: {
  expression: RuleExpressionModel
  selectedId?: string
  onSelect: (id: string) => void
  onDropExpression: (id: string, kind: RuleExpressionKind) => void
}) {
  const selected = expression.id === selectedId
  return (
    <div className="relative">
      <button
        className={`relative z-10 flex w-full min-w-0 items-center gap-2 rounded-lg border px-3 py-2.5 text-left transition ${
          selected
            ? 'border-jmix-400 bg-jmix-500/15 shadow-[0_0_0_2px_rgba(45,212,191,0.08)]'
            : 'border-surface-border bg-surface-light hover:border-gray-500'
        }`}
        onClick={() => onSelect(expression.id)}
        onDragOver={(event) => event.preventDefault()}
        onDrop={(event) => {
          event.preventDefault()
          event.stopPropagation()
          const kind = event.dataTransfer.getData('application/jvw-rule-expression') as RuleExpressionKind
          onDropExpression(expression.id, kind)
        }}
      >
        <GripVertical className="h-3.5 w-3.5 shrink-0 text-gray-600" />
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[11px] font-semibold text-gray-200">{expression.label || expression.kind}</span>
          <span className="block truncate text-[9px] text-gray-500">{expression.kind.replace(/_/g, ' ')} · {expression.dataType}</span>
        </span>
        <span className="rounded bg-surface px-1.5 py-0.5 text-[8px] font-bold text-gray-500">{expression.children.length}</span>
      </button>
      {expression.children.length > 0 && (
        <div className="ml-5 border-l border-surface-border pl-3 pt-2">
          <div className="space-y-2">
            {expression.children.map((child) => (
              <ExpressionTree
                key={child.id}
                expression={child}
                selectedId={selectedId}
                onSelect={onSelect}
                onDropExpression={onDropExpression}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function VisualRuleDesigner({ onSwitch }: { onSwitch: () => void }) {
  const addToast = useStore((state) => state.addToast)
  const [workspace, setWorkspace] = useState<VisualRuleWorkspaceResponse>()
  const [model, setModel] = useState<VisualRuleModel>(() => defaultRule())
  const [selectedId, setSelectedId] = useState(model.expression.id)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse>()
  const [history, setHistory] = useState<VisualRuleModel[]>([])
  const [future, setFuture] = useState<VisualRuleModel[]>([])

  const selected = findExpression(model.expression, selectedId) ?? model.expression
  const entities = useMemo(
    () => workspace?.contextArtifacts.filter((artifact) => ['ENTITY', 'DTO'].includes(artifact.kind)) ?? [],
    [workspace],
  )
  const groupedCatalog = useMemo(() => {
    const query = search.trim().toLowerCase()
    return catalog
      .filter((item) => !query || `${item.label} ${item.description} ${item.group}`.toLowerCase().includes(query))
      .reduce<Record<string, CatalogItem[]>>((groups, item) => {
        ;(groups[item.group] ??= []).push(item)
        return groups
      }, {})
  }, [search])

  const load = useCallback(async (forceRefresh = false) => {
    setLoading(true)
    try {
      const loaded = await bridge.getVisualRuleWorkspace(forceRefresh)
      setWorkspace(loaded)
      setModel((current) => current.destinationId ? current : defaultRule(loaded))
      if (loaded.issues.length) addToast(loaded.issues[0].message, 'info')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Could not load the indexed rule workspace.', 'error')
    } finally {
      setLoading(false)
    }
  }, [addToast])

  useEffect(() => {
    void load()
  }, [load])

  const commit = useCallback((updater: (current: VisualRuleModel) => VisualRuleModel) => {
    setModel((current) => {
      const next = updater(current)
      if (JSON.stringify(next) === JSON.stringify(current)) return current
      setHistory((items) => [...items.slice(-(HISTORY_LIMIT - 1)), clone(current)])
      setFuture([])
      setPreview(undefined)
      return next
    })
  }, [])

  const updateModel = (patch: Partial<VisualRuleModel>) => commit((current) => ({ ...current, ...patch }))
  const updateSelected = (patch: Partial<RuleExpressionModel>) => commit((current) => ({
    ...current,
    expression: updateExpression(current.expression, selected.id, patch),
  }))
  const replaceAt = (targetId: string, replacement: RuleExpressionModel) => {
    const replacementWithId = { ...replacement, id: targetId }
    commit((current) => ({
      ...current,
      expression: replaceExpression(current.expression, targetId, replacementWithId),
    }))
    setSelectedId(targetId)
  }
  const replaceSelected = (replacement: RuleExpressionModel) => replaceAt(selected.id, replacement)
  const useTemplate = (factory: (workspace?: VisualRuleWorkspaceResponse) => VisualRuleModel) => {
    const next = factory(workspace)
    setHistory((items) => [...items.slice(-(HISTORY_LIMIT - 1)), clone(model)])
    setFuture([])
    setModel(next)
    setSelectedId(next.expression.id)
    setPreview(undefined)
  }
  const undo = () => {
    const previous = history[history.length - 1]
    if (!previous) return
    setHistory((items) => items.slice(0, -1))
    setFuture((items) => [clone(model), ...items].slice(0, HISTORY_LIMIT))
    setModel(previous)
    setSelectedId(previous.expression.id)
    setPreview(undefined)
  }
  const redo = () => {
    const next = future[0]
    if (!next) return
    setFuture((items) => items.slice(1))
    setHistory((items) => [...items, clone(model)].slice(-HISTORY_LIMIT))
    setModel(next)
    setSelectedId(next.expression.id)
    setPreview(undefined)
  }
  const addParameter = (preset?: GraphArtifact) => {
    const simple = preset?.displayName ?? `input${model.parameters.length + 1}`
    const javaType = preset?.semanticKey ?? 'String'
    const parameter: RuleParameterModel = {
      name: simple.charAt(0).toLowerCase() + simple.slice(1).replace(/\W/g, ''),
      javaType,
      dataType: preset ? 'ENTITY' : 'STRING',
      nullable: false,
    }
    updateModel({ parameters: [...model.parameters, parameter] })
  }
  const previewSource = async () => {
    setPreviewing(true)
    try {
      const result = await bridge.previewVisualRule(model)
      setPreview(result)
      if (!result.accepted) addToast(result.issues[0]?.message ?? 'The rule needs attention.', 'error')
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Rule preview failed.', 'error')
    } finally {
      setPreviewing(false)
    }
  }
  const applySource = async () => {
    if (!preview?.accepted || !preview.planDigest) return
    setApplying(true)
    try {
      const result = await bridge.applyVisualRule(model, preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'Rule generation was rejected.', 'error')
        return
      }
      addToast(`Generated ${result.filesChanged.join(', ')}`, 'success')
      setPreview(undefined)
      await load(true)
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Rule generation failed.', 'error')
    } finally {
      setApplying(false)
    }
  }
  const openDocument = (path: string) => {
    const document = workspace?.existingDocuments.find((item) => item.locator.relativePath === path)
    if (!document) return
    setModel(clone(document.model))
    setSelectedId(document.model.expression.id)
    setHistory([])
    setFuture([])
    setPreview(undefined)
    if (!document.editable) addToast(document.issue ?? 'Manual Java changes make this rule read-only.', 'info')
  }

  if (loading && !workspace) {
    return (
      <div className="flex h-full items-center justify-center gap-3 text-sm text-gray-400">
        <Loader2 className="h-5 w-5 animate-spin text-jmix-400" />
        Mapping modules, entities, attributes, rules, and source ownership…
      </div>
    )
  }

  return (
    <section className="rule-designer-shell relative flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <header className="flex min-w-0 flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light px-3 py-2">
        <div className="mr-auto min-w-[210px]">
          <div className="flex items-center gap-2">
            <FunctionSquare className="h-4 w-4 text-jmix-400" />
            <h2 className="truncate text-sm font-semibold text-gray-100">Rules &amp; Formulas</h2>
            <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[9px] font-semibold uppercase text-emerald-300">Server enforced</span>
          </div>
          <p className="mt-0.5 truncate text-[10px] text-gray-500">Pure typed Java · reusable everywhere · manual code is never overwritten</p>
        </div>
        <button className="btn-secondary flex items-center gap-1.5" onClick={onSwitch}>
          <Table2 className="h-3.5 w-3.5" />
          DMN tables
        </button>
        <select
          aria-label="Open existing visual rule"
          className={inputClass('max-w-52')}
          value={model.sourceLocator?.relativePath ?? ''}
          onChange={(event) => event.target.value ? openDocument(event.target.value) : useTemplate(defaultRule)}
        >
          <option value="">New visual rule</option>
          {workspace?.existingDocuments.map((document) => (
            <option key={document.locator.relativePath} value={document.locator.relativePath}>
              {document.editable ? '●' : '◐'} {document.model.name}
            </option>
          ))}
        </select>
        <button className="btn-secondary" onClick={undo} disabled={!history.length} title="Undo"><Undo2 className="h-3.5 w-3.5" /></button>
        <button className="btn-secondary" onClick={redo} disabled={!future.length} title="Redo"><Redo2 className="h-3.5 w-3.5" /></button>
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
        <Field label="Rule name"><input className={inputClass()} value={model.name} onChange={(event) => updateModel({ name: event.target.value })} /></Field>
        <Field label="Kind">
          <select className={inputClass()} value={model.kind} onChange={(event) => {
            const kind = event.target.value as VisualRuleKind
            updateModel({
              kind,
              outputJavaType: kind === 'FORMULA' ? model.outputJavaType : 'boolean',
              validationMessage: kind === 'VALIDATOR' ? model.validationMessage ?? 'The value is not valid.' : undefined,
            })
          }}>
            <option value="FORMULA">Formula</option>
            <option value="PREDICATE">Predicate</option>
            <option value="VALIDATOR">FlowUI validator</option>
          </select>
        </Field>
        <Field label="Module">
          <select className={inputClass()} disabled={Boolean(model.sourceLocator)} value={model.destinationId} onChange={(event) => {
            const destination = workspace?.destinations.find((item) => item.id === event.target.value)
            updateModel({ destinationId: event.target.value, packageName: destination?.defaultPackage ?? model.packageName })
          }}>
            {workspace?.destinations.map((destination) => <option key={destination.id} value={destination.id}>{destination.moduleId} · {destination.sourceRoot}</option>)}
          </select>
        </Field>
        <Field label="Package"><input className={inputClass()} disabled={Boolean(model.sourceLocator)} value={model.packageName} onChange={(event) => updateModel({ packageName: event.target.value })} /></Field>
        <Field label="Java class"><input className={inputClass()} disabled={Boolean(model.sourceLocator)} value={model.className} onChange={(event) => updateModel({ className: event.target.value })} /></Field>
        <Field label="Spring bean"><input className={inputClass()} value={model.beanName} onChange={(event) => updateModel({ beanName: event.target.value })} /></Field>
      </div>

      <div className="rule-designer-workspace grid min-h-0 min-w-0 flex-1">
        <aside className="rule-designer-palette flex min-h-0 min-w-0 flex-col border-r border-surface-border bg-surface-light/55">
          <div className="border-b border-surface-border p-3">
            <h3 className="mb-2 flex items-center gap-2 text-xs font-semibold text-gray-200"><Calculator className="h-3.5 w-3.5 text-jmix-400" /> Start from a proven pattern</h3>
            <div className="grid grid-cols-2 gap-1.5">
              <button className="btn-secondary text-[10px]" onClick={() => useTemplate(defaultRule)}>Money</button>
              <button className="btn-secondary text-[10px]" onClick={() => useTemplate(predicateTemplate)}>Eligibility</button>
              <button className="btn-secondary text-[10px]" onClick={() => useTemplate(validatorTemplate)}>Validation</button>
              <button className="btn-secondary text-[10px]" onClick={() => useTemplate(dateTemplate)}>Deadline</button>
            </div>
            <input className={inputClass('mt-2')} placeholder="Find an expression…" value={search} onChange={(event) => setSearch(event.target.value)} />
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto p-2">
            {Object.entries(groupedCatalog).map(([group, items]) => (
              <div key={group} className="mb-4">
                <h4 className="mb-1 px-1 text-[9px] font-bold uppercase tracking-[0.16em] text-gray-600">{group}</h4>
                <div className="space-y-1">
                  {items.map((item) => (
                    <button
                      key={item.kind}
                      draggable
                      onDragStart={(event) => event.dataTransfer.setData('application/jvw-rule-expression', item.kind)}
                      onDoubleClick={() => replaceSelected(expressionFor(item))}
                      onClick={() => replaceSelected(expressionFor(item))}
                      className="flex w-full cursor-grab items-start gap-2 rounded border border-transparent px-2 py-2 text-left hover:border-surface-border hover:bg-surface-lighter"
                    >
                      <Braces className="mt-0.5 h-3.5 w-3.5 shrink-0 text-jmix-400" />
                      <span className="min-w-0">
                        <span className="block text-[11px] font-medium text-gray-200">{item.label}</span>
                        <span className="block text-[9px] leading-4 text-gray-600">{item.description}</span>
                      </span>
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
          <div className="border-t border-surface-border p-3 text-[9px] leading-4 text-gray-600">
            Select a tree node, then click or drag a building block to replace it. Every change stays typed.
          </div>
        </aside>

        <main
          className="rule-designer-canvas flex min-h-0 min-w-0 flex-col bg-surface"
          onDragOver={(event) => event.preventDefault()}
          onDrop={(event) => {
            event.preventDefault()
            const kind = event.dataTransfer.getData('application/jvw-rule-expression') as RuleExpressionKind
            const item = catalog.find((candidate) => candidate.kind === kind)
            if (item) replaceSelected(expressionFor(item))
          }}
        >
          <div className="flex flex-wrap items-center gap-2 border-b border-surface-border px-3 py-2">
            <GitBranch className="h-3.5 w-3.5 text-gray-500" />
            <span className="text-[10px] text-gray-500">Expression tree · evaluated on the server · no scripts, reflection, network, or unconstrained data</span>
            <span className="ml-auto rounded bg-surface-light px-2 py-1 text-[9px] text-gray-500">{model.parameters.length} inputs</span>
          </div>
          <div className="min-h-0 flex-1 overflow-auto bg-[radial-gradient(circle_at_1px_1px,rgba(148,163,184,0.10)_1px,transparent_0)] bg-[size:20px_20px] p-4">
            <div className="mx-auto max-w-3xl">
              <ExpressionTree
                expression={model.expression}
                selectedId={selected.id}
                onSelect={setSelectedId}
                onDropExpression={(targetId, kind) => {
                  const item = catalog.find((candidate) => candidate.kind === kind)
                  if (item) replaceAt(targetId, expressionFor(item))
                }}
              />
              <div className="mt-5 rounded-lg border border-dashed border-surface-border bg-surface-light/40 p-3">
                <div className="flex items-center gap-2 text-[10px] font-semibold text-gray-300">
                  <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
                  Enterprise execution boundary
                </div>
                <p className="mt-1 text-[9px] leading-4 text-gray-600">
                  Generated as a Spring component for views, services, REST endpoints, workflows, listeners, and tests.
                  Critical validation is server-side; FlowUI validator rules implement the Jmix Validator contract.
                </p>
              </div>
            </div>
          </div>
        </main>

        <aside className="rule-designer-inspector flex min-h-0 min-w-0 flex-col border-l border-surface-border bg-surface-light/55">
          <div className="border-b border-surface-border p-3">
            <h3 className="text-xs font-semibold text-gray-200">Expression inspector</h3>
            <p className="mt-1 truncate text-[9px] text-gray-600">{selected.kind} · {selected.id}</p>
          </div>
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-3">
            <Field label="Readable label"><input className={inputClass()} value={selected.label} onChange={(event) => updateSelected({ label: event.target.value })} /></Field>
            <Field label="Result type">
              <select className={inputClass()} value={selected.dataType} onChange={(event) => updateSelected({ dataType: event.target.value as RuleDataType })}>
                {dataTypes.map((type) => <option key={type}>{type}</option>)}
              </select>
            </Field>
            {selected.kind === 'VALUE' && (
              <>
                <Field label="Value source">
                  <select className={inputClass()} value={selected.valueSource ?? 'LITERAL'} onChange={(event) => updateSelected({ valueSource: event.target.value as RuleValueSource })}>
                    <option value="LITERAL">Literal</option>
                    <option value="PARAMETER">Input parameter</option>
                    <option value="NULL">Null</option>
                  </select>
                </Field>
                {selected.valueSource === 'PARAMETER' ? (
                  <Field label="Input parameter">
                    <select className={inputClass()} value={selected.parameterName ?? ''} onChange={(event) => {
                      const parameter = model.parameters.find((item) => item.name === event.target.value)
                      updateSelected({ parameterName: event.target.value, dataType: parameter?.dataType ?? selected.dataType })
                    }}>
                      <option value="">Select input…</option>
                      {model.parameters.map((parameter) => <option key={parameter.name}>{parameter.name}</option>)}
                    </select>
                  </Field>
                ) : selected.valueSource !== 'NULL' && (
                  <Field label="Literal value"><input className={inputClass()} value={selected.value ?? ''} onChange={(event) => updateSelected({ value: event.target.value })} /></Field>
                )}
              </>
            )}
            {selected.kind === 'PROPERTY' && (
              <>
                <Field label="Entity input">
                  <select className={inputClass()} value={selected.parameterName ?? ''} onChange={(event) => updateSelected({ parameterName: event.target.value })}>
                    <option value="">Select entity input…</option>
                    {model.parameters.filter((item) => ['ENTITY', 'OBJECT'].includes(item.dataType)).map((parameter) => <option key={parameter.name}>{parameter.name}</option>)}
                  </select>
                </Field>
                <Field label="Indexed property path">
                  <input className={inputClass()} list="rule-indexed-properties" value={selected.propertyPath ?? ''} onChange={(event) => updateSelected({ propertyPath: event.target.value })} />
                  <datalist id="rule-indexed-properties">
                    {workspace?.contextArtifacts.filter((artifact) => artifact.kind === 'ENTITY_ATTRIBUTE').map((artifact) => (
                      <option key={artifact.id} value={artifact.semanticKey.split('.').pop() ?? artifact.semanticKey} />
                    ))}
                  </datalist>
                </Field>
                {['ENTITY', 'ENUM', 'OBJECT'].includes(selected.dataType) && (
                  <Field label="Java result type"><input className={inputClass()} value={selected.javaType ?? ''} onChange={(event) => updateSelected({ javaType: event.target.value })} /></Field>
                )}
              </>
            )}

            <div className="border-t border-surface-border pt-4">
              <div className="mb-2 flex items-center justify-between">
                <h4 className="text-[10px] font-bold uppercase tracking-wide text-gray-500">Rule inputs</h4>
                <button className="btn-secondary flex items-center gap-1 px-2 py-1 text-[9px]" onClick={() => addParameter()}><Plus className="h-3 w-3" /> Input</button>
              </div>
              <div className="space-y-2">
                {model.parameters.map((parameter, index) => (
                  <div key={`${parameter.name}-${index}`} className="rounded border border-surface-border bg-surface p-2">
                    <div className="flex gap-1">
                      <input className={inputClass()} value={parameter.name} onChange={(event) => {
                        const next = [...model.parameters]
                        next[index] = { ...parameter, name: event.target.value }
                        updateModel({ parameters: next })
                      }} />
                      <button className="text-gray-600 hover:text-red-300" onClick={() => updateModel({ parameters: model.parameters.filter((_, candidate) => candidate !== index) })}><Trash2 className="h-3.5 w-3.5" /></button>
                    </div>
                    <div className="mt-1 grid grid-cols-2 gap-1">
                      <select className={inputClass()} value={parameter.dataType} onChange={(event) => {
                        const next = [...model.parameters]
                        next[index] = { ...parameter, dataType: event.target.value as RuleDataType }
                        updateModel({ parameters: next })
                      }}>{dataTypes.map((type) => <option key={type}>{type}</option>)}</select>
                      <input className={inputClass()} value={parameter.javaType} onChange={(event) => {
                        const next = [...model.parameters]
                        next[index] = { ...parameter, javaType: event.target.value }
                        updateModel({ parameters: next })
                      }} />
                    </div>
                  </div>
                ))}
              </div>
              {entities.length > 0 && (
                <Field label="Add indexed entity" hint="Creates a strongly typed entity input.">
                  <select className={inputClass()} value="" onChange={(event) => {
                    const entity = entities.find((item) => item.id === event.target.value)
                    if (entity) addParameter(entity)
                  }}>
                    <option value="">Choose entity…</option>
                    {entities.map((entity) => <option key={entity.id} value={entity.id}>{entity.displayName} · {entity.owner.moduleId}</option>)}
                  </select>
                </Field>
              )}
            </div>

            <div className="border-t border-surface-border pt-4">
              <h4 className="mb-2 text-[10px] font-bold uppercase tracking-wide text-gray-500">Compiler settings</h4>
              <div className="grid grid-cols-2 gap-2">
                <Field label="Output Java type"><input className={inputClass()} value={model.outputJavaType} onChange={(event) => updateModel({ outputJavaType: event.target.value })} /></Field>
                <Field label="Method"><input className={inputClass()} value={model.methodName} onChange={(event) => updateModel({ methodName: event.target.value })} /></Field>
                <Field label="Decimal scale"><input type="number" min={0} max={18} className={inputClass()} value={model.decimalScale} onChange={(event) => updateModel({ decimalScale: Number(event.target.value) })} /></Field>
                <Field label="Rounding">
                  <select className={inputClass()} value={model.roundingMode} onChange={(event) => updateModel({ roundingMode: event.target.value })}>
                    {roundingModes.map((mode) => <option key={mode}>{mode}</option>)}
                  </select>
                </Field>
              </div>
              {model.kind === 'VALIDATOR' && (
                <Field label="Validation message" hint="Thrown as Jmix ValidationException on the server.">
                  <textarea className={inputClass('mt-2 min-h-20 resize-y')} value={model.validationMessage ?? ''} onChange={(event) => updateModel({ validationMessage: event.target.value })} />
                </Field>
              )}
            </div>
          </div>
          <div className="border-t border-surface-border p-3 text-[9px] text-gray-600">
            {model.sourceLocator ? 'Existing owned source · stale/manual edits block generation' : 'New source · preview required before apply'}
          </div>
        </aside>
      </div>

      {preview && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/65 p-3" role="dialog" aria-modal="true" aria-label="Generated rule Java preview">
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
              <div className="max-h-36 space-y-1 overflow-y-auto border-b border-surface-border bg-amber-950/20 px-4 py-2">
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
                  <div className="sticky top-0 border-b border-surface-border bg-surface-light px-4 py-2 text-[10px] font-medium text-gray-400">{file.mode} · {file.relativePath}</div>
                  <pre className="overflow-x-auto p-4 text-[11px] leading-5 text-gray-300">{file.resultContent}</pre>
                </div>
              )) : <div className="p-8 text-center text-sm text-gray-500">Fix the reported issues, then preview again.</div>}
            </div>
            <div className="flex items-center justify-end gap-2 border-t border-surface-border px-4 py-3">
              <button className="btn-secondary" onClick={() => setPreview(undefined)}>Keep editing</button>
              <button className="btn-primary flex items-center gap-1.5" disabled={!preview.accepted || !preview.planDigest || applying} onClick={() => void applySource()}>
                {applying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : preview.accepted ? <CheckCircle2 className="h-3.5 w-3.5" /> : <Save className="h-3.5 w-3.5" />}
                Apply source-safe change
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

export default function RuleDesigner() {
  const [mode, setMode] = useState<'EXPRESSION' | 'DMN'>('EXPRESSION')
  return mode === 'DMN'
    ? <DmnDecisionDesigner onSwitch={() => setMode('EXPRESSION')} />
    : <VisualRuleDesigner onSwitch={() => setMode('DMN')} />
}
