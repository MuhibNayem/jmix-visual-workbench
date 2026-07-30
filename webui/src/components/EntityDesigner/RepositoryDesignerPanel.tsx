import { useMemo, useState } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  ChevronUp,
  LoaderCircle,
  Plus,
  ShieldAlert,
  Trash2,
  XCircle,
} from 'lucide-react'
import type {
  AttributeModel,
  DataRepositoryConfig,
  EntityModel,
  RepositoryMethod,
  RepositoryMethodParameter,
  RepositoryParameterRole,
  RepositorySemanticValidationResponse,
  SchemaRepositoryMethodEvidence,
} from '../../types'

interface RepositoryDesignerPanelProps {
  entity: EntityModel
  onChange: (config: DataRepositoryConfig) => void
  sourceLocked?: boolean
  lockedMethodCount?: number
  methodEvidence?: SchemaRepositoryMethodEvidence[]
  semantics?: RepositorySemanticValidationResponse | null
  semanticsBusy?: boolean
  footer?: React.ReactNode
}

const PARAMETER_ROLES: Array<{ value: RepositoryParameterRole; label: string; type: string }> = [
  { value: 'value', label: 'Query value', type: 'String' },
  { value: 'pageable', label: 'Page + sort', type: 'Pageable' },
  { value: 'sort', label: 'Sort only', type: 'Sort' },
  { value: 'fetchPlan', label: 'Fetch plan', type: 'FetchPlan' },
  { value: 'context', label: 'UI filter context', type: 'JmixDataRepositoryContext' },
]

const RETURN_PRESETS = [
  'List<Entity>',
  'Entity',
  'Optional<Entity>',
  'Page<Entity>',
  'Slice<Entity>',
  'Long',
  'Boolean',
  'List<KeyValueEntity>',
]

function repositoryConfig(entity: EntityModel): DataRepositoryConfig {
  return {
    enabled: entity.dataRepository?.enabled ?? false,
    interfaceName: entity.dataRepository?.interfaceName,
    applyConstraints: entity.dataRepository?.applyConstraints ?? true,
    useNamedParameters: entity.dataRepository?.useNamedParameters ?? true,
    methods: entity.dataRepository?.methods ?? [],
  }
}

function titleCase(value: string): string {
  if (!value) return 'Property'
  return value.charAt(0).toUpperCase() + value.slice(1)
}

function methodType(value: string, entity: EntityModel): string {
  return value.replace(/Entity/g, entity.className || 'Entity')
}

function attributeJvmType(attribute?: AttributeModel): string {
  if (!attribute) return 'String'
  if (attribute.type === 'association' || attribute.type === 'composition') {
    return attribute.association?.relatedEntity?.split('.').pop() || 'Object'
  }
  if (attribute.type === 'enum') return attribute.enumClass?.split('.').pop() || 'String'
  if (attribute.type === 'custom') return attribute.javaTypeName?.split('.').pop() || 'Object'
  const types: Partial<Record<AttributeModel['type'], string>> = {
    string: 'String',
    character: 'Character',
    integer: 'Integer',
    long: 'Long',
    double: 'Double',
    bigDecimal: 'BigDecimal',
    boolean: 'Boolean',
    date: 'Date',
    localDate: 'LocalDate',
    localDateTime: 'LocalDateTime',
    localTime: 'LocalTime',
    offsetTime: 'OffsetTime',
    offsetDateTime: 'OffsetDateTime',
    sqlDate: 'java.sql.Date',
    sqlTime: 'java.sql.Time',
    uuid: 'UUID',
    uri: 'URI',
    byteArray: 'byte[]',
    fileRef: 'FileRef',
    embedded: attribute.embeddedClass?.split('.').pop() || 'Object',
  }
  return types[attribute.type] ?? 'String'
}

function emptyParameter(index: number): RepositoryMethodParameter {
  return {
    name: `value${index + 1}`,
    type: 'String',
    nullable: false,
    role: 'value',
  }
}

function defaultMethod(
  entity: EntityModel,
  queryType: 'derived' | 'jpql',
): RepositoryMethod {
  const attribute = entity.attributes.find(candidate =>
    !candidate.transientFlag && candidate.name.trim()) ?? entity.attributes[0]
  const property = attribute?.name || 'name'
  const parameter = {
    name: property,
    type: attributeJvmType(attribute),
    bindingName: property,
    nullable: false,
    role: 'value' as const,
  }
  if (queryType === 'derived') {
    return {
      name: `findBy${titleCase(property)}`,
      returnType: `List<${entity.className || 'Entity'}>`,
      parameters: [parameter],
      queryType,
      queryProperties: [],
      queryHints: [],
    }
  }
  const entityName = entity.entityName || entity.className || 'Entity'
  return {
    name: `findBy${titleCase(property)}Query`,
    returnType: `List<${entity.className || 'Entity'}>`,
    parameters: [parameter],
    query: `select e from ${entityName} e where e.${property} = :${property}`,
    queryType,
    queryProperties: [],
    queryHints: [],
  }
}

function queryParameterIssue(method: RepositoryMethod, named: boolean): string | null {
  if (method.queryType !== 'jpql') return null
  const query = method.query ?? ''
  const values = method.parameters.filter(parameter => parameter.role === 'value')
  if (named) {
    const queryNames = [...query.matchAll(/(?<!:):([A-Za-z_$][A-Za-z0-9_$]*)/g)]
      .map(match => match[1])
    const declared = values.map(parameter => parameter.bindingName?.trim() || parameter.name.trim())
    const querySet = [...new Set(queryNames)].sort()
    const declaredSet = [...new Set(declared)].sort()
    return JSON.stringify(querySet) === JSON.stringify(declaredSet) && declared.length === declaredSet.length
      ? null
      : `JPQL bindings ${querySet.join(', ') || 'none'} do not match method bindings ${declaredSet.join(', ') || 'none'}.`
  }
  const positions = [...query.matchAll(/\?([1-9][0-9]*)/g)]
    .map(match => Number(match[1]))
  const expected = values.map((_, index) => index + 1)
  return JSON.stringify([...new Set(positions)].sort()) === JSON.stringify(expected)
    ? null
    : `Use positional parameters ?1 through ?${values.length}.`
}

export default function RepositoryDesignerPanel({
  entity,
  onChange,
  sourceLocked = false,
  lockedMethodCount = 0,
  methodEvidence = [],
  semantics,
  semanticsBusy = false,
  footer,
}: RepositoryDesignerPanelProps) {
  const config = repositoryConfig(entity)
  const [expandedMethod, setExpandedMethod] = useState<number | null>(
    config.methods.length ? 0 : null,
  )
  const [propertyDrafts, setPropertyDrafts] = useState<Record<number, string>>({})
  const update = (patch: Partial<DataRepositoryConfig>) => onChange({ ...config, ...patch })
  const updateMethod = (index: number, patch: Partial<RepositoryMethod>) => {
    const methods = config.methods.map((method, candidate) =>
      candidate === index ? { ...method, ...patch } : method)
    update({ methods })
  }
  const addMethod = (queryType: 'derived' | 'jpql') => {
    const methods = [...config.methods, defaultMethod(entity, queryType)]
    update({ enabled: true, methods })
    setExpandedMethod(methods.length - 1)
  }
  const removeMethod = (index: number) => {
    update({ methods: config.methods.filter((_, candidate) => candidate !== index) })
    setExpandedMethod(current => {
      if (current === index) return null
      if (current !== null && current > index) return current - 1
      return current
    })
  }
  const addDerivedCondition = (index: number) => {
    const property = semantics?.propertyPaths.find(candidate =>
      candidate.path === propertyDrafts[index])
    if (!property) return
    const method = config.methods[index]
    const orderByIndex = method.name.indexOf('OrderBy')
    const predicateName = orderByIndex >= 0
      ? method.name.slice(0, orderByIndex)
      : method.name
    const orderBySuffix = orderByIndex >= 0
      ? method.name.slice(orderByIndex)
      : ''
    const name = predicateName.includes('By')
      ? `${predicateName}And${property.derivedToken}${orderBySuffix}`
      : `findBy${property.derivedToken}${orderBySuffix}`
    const parameterName = property.path.split('.').pop() || `value${method.parameters.length + 1}`
    updateMethod(index, {
      name,
      parameters: [
        ...method.parameters,
        {
          name: parameterName,
          type: property.javaType,
          bindingName: parameterName,
          nullable: false,
          role: 'value',
        },
      ],
    })
    setPropertyDrafts(current => ({ ...current, [index]: '' }))
  }
  const signatureCount = useMemo(() => {
    const counts = new Map<string, number>()
    config.methods.forEach(method => {
      const signature = `${method.name}(${method.parameters.map(parameter => parameter.type).join(',')})`
      counts.set(signature, (counts.get(signature) ?? 0) + 1)
    })
    return counts
  }, [config.methods])

  return (
    <section className="rounded-lg border border-surface-border bg-surface/70 p-3">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h4 className="text-xs font-semibold text-gray-200">Data Repository</h4>
          <p className="mt-1 max-w-2xl text-[10px] leading-relaxed text-gray-500">
            Build type-safe Jmix repository methods with paging, UI filter context, fetch plans,
            aggregate results, and visible security behavior.
          </p>
        </div>
        <label className="flex shrink-0 items-center gap-2 text-[10px] text-gray-300">
          <input
            type="checkbox"
            checked={config.enabled}
            disabled={sourceLocked}
            onChange={event => update({ enabled: event.target.checked })}
          />
          Generate repository
        </label>
      </div>

      {config.enabled && (
        <div className="mt-3 space-y-3">
          <div
            role="status"
            className={`flex min-w-0 flex-wrap items-start gap-2 rounded border p-2.5 ${
              semanticsBusy
                ? 'border-sky-500/25 bg-sky-500/5 text-sky-100'
                : semantics?.accepted === false
                  ? 'border-red-500/30 bg-red-500/[0.07] text-red-100'
                  : (semantics?.diagnostics.some(diagnostic => diagnostic.severity === 'warning'))
                    ? 'border-amber-500/30 bg-amber-500/[0.07] text-amber-100'
                    : 'border-emerald-500/25 bg-emerald-500/5 text-emerald-100'
            }`}
          >
            {semanticsBusy
              ? <LoaderCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" />
              : semantics?.accepted === false
                ? <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                : semantics?.diagnostics.some(diagnostic => diagnostic.severity === 'warning')
                  ? <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  : <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
            <span className="min-w-0 flex-1">
              <strong className="block text-[10px]">
                {semanticsBusy
                  ? 'Checking repository semantics…'
                  : semantics?.accepted === false
                    ? 'Source preview blocked by semantic errors'
                    : semantics
                      ? `Entity-aware contract valid · ${semantics.propertyPaths.length} paths indexed`
                      : 'Entity semantics become live after selecting an indexed entity'}
              </strong>
              <span className="mt-0.5 block text-[9px] leading-relaxed opacity-70">
                Derived names, nested properties, JPQL paths, parameter arity/types, result shape,
                fetch-plan compatibility, and security bypasses are checked by the IntelliJ backend.
              </span>
            </span>
          </div>

          {semantics && semantics.diagnostics.filter(diagnostic =>
            diagnostic.methodIndex === undefined).map(diagnostic => (
              <div
                key={`${diagnostic.code}-${diagnostic.message}`}
                className={`rounded border px-2.5 py-2 text-[9px] leading-relaxed ${
                  diagnostic.severity === 'error'
                    ? 'border-red-500/25 bg-red-500/5 text-red-100'
                    : 'border-amber-500/25 bg-amber-500/5 text-amber-100'
                }`}
              >
                {diagnostic.message}
              </div>
            ))}
          <div className="grid min-w-0 gap-3 sm:grid-cols-2">
            <label className="min-w-0 text-[10px] text-gray-500">
              Interface name
              <input
                value={config.interfaceName ?? ''}
                disabled={sourceLocked}
                onChange={event => update({ interfaceName: event.target.value || undefined })}
                placeholder={`${entity.className || 'Entity'}Repository`}
                className="mt-1 w-full min-w-0 font-mono"
              />
            </label>
            <label className="min-w-0 text-[10px] text-gray-500">
              JPQL parameter style
              <select
                value={config.useNamedParameters ? 'named' : 'positional'}
                onChange={event => update({ useNamedParameters: event.target.value === 'named' })}
                className="mt-1 w-full min-w-0"
              >
                <option value="named">Named — :employeeNumber</option>
                <option value="positional">Positional — ?1</option>
              </select>
            </label>
          </div>

          <label className={`flex items-start gap-2 rounded border p-2.5 text-[10px] ${
            config.applyConstraints
              ? 'border-emerald-500/20 bg-emerald-500/5 text-emerald-200'
              : 'border-red-500/40 bg-red-500/10 text-red-200'
          }`}>
            <input
              type="checkbox"
              checked={config.applyConstraints}
              disabled={sourceLocked}
              onChange={event => update({ applyConstraints: event.target.checked })}
              className="mt-0.5"
            />
            <span>
              <strong className="block">
                {config.applyConstraints ? 'Security constraints enforced' : 'Security constraints bypassed'}
              </strong>
              <span className="mt-0.5 block opacity-75">
                {config.applyConstraints
                  ? 'Repository loads use constrained DataManager and respect row-level policies.'
                  : 'This generates @ApplyConstraints(false) and uses UnconstrainedDataManager. Restrict this repository to audited infrastructure code.'}
              </span>
            </span>
            {!config.applyConstraints && <ShieldAlert className="ml-auto h-4 w-4 shrink-0" />}
          </label>

          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h5 className="text-[10px] font-semibold uppercase tracking-wider text-gray-400">
                Query methods · {config.methods.length}
              </h5>
              <p className="mt-0.5 text-[9px] text-gray-600">
                Derived methods cover routine filters; JPQL handles joins and aggregate projections.
              </p>
            </div>
            <div className="flex flex-wrap gap-1.5">
              <button
                type="button"
                onClick={() => addMethod('derived')}
                className="flex items-center gap-1 rounded border border-jmix-500/30 bg-jmix-500/10 px-2 py-1 text-[10px] text-jmix-200"
              >
                <Plus className="h-3 w-3" /> Derived method
              </button>
              <button
                type="button"
                onClick={() => addMethod('jpql')}
                className="flex items-center gap-1 rounded border border-surface-border px-2 py-1 text-[10px] text-gray-300"
              >
                <Plus className="h-3 w-3" /> JPQL method
              </button>
            </div>
          </div>

          {config.methods.length === 0 && (
            <div className="rounded border border-dashed border-surface-border p-4 text-center text-[10px] text-gray-500">
              No custom methods. The repository still includes Jmix CRUD, paging, fetch-plan, and
              repository-context operations.
            </div>
          )}

          <div className="space-y-2">
            {config.methods.map((method, index) => {
              const expanded = expandedMethod === index
              const existingMethod = index < lockedMethodCount
              const sourceEvidence = methodEvidence.find(evidence => evidence.methodIndex === index)
              const sourceOwned = existingMethod && sourceEvidence?.editable !== true
              const safelyEditable = existingMethod && sourceEvidence?.editable === true
              const signature = `${method.name}(${method.parameters.map(parameter => parameter.type).join(',')})`
              const duplicate = (signatureCount.get(signature) ?? 0) > 1
              const parameterIssue = queryParameterIssue(method, config.useNamedParameters)
              const securityBypass = method.applyConstraints === false
              const methodDiagnostics = semantics?.diagnostics.filter(diagnostic =>
                diagnostic.methodIndex === index) ?? []
              const semanticMethod = semantics?.methods.find(candidate =>
                candidate.methodIndex === index)
              const semanticError = methodDiagnostics.some(diagnostic =>
                diagnostic.blocking)
              return (
                <article
                  key={`${method.name}-${index}`}
                  className={`min-w-0 rounded border ${
                    duplicate || parameterIssue || securityBypass || methodDiagnostics.length > 0
                      ? 'border-amber-500/30'
                      : 'border-surface-border'
                  } bg-surface`}
                >
                  <div className="flex min-w-0 items-center gap-2 p-2.5">
                    <button
                      type="button"
                      onClick={() => setExpandedMethod(expanded ? null : index)}
                      aria-expanded={expanded}
                      className="flex min-w-0 flex-1 items-center gap-2 text-left"
                    >
                      {expanded
                        ? <ChevronUp className="h-3.5 w-3.5 shrink-0 text-gray-500" />
                        : <ChevronDown className="h-3.5 w-3.5 shrink-0 text-gray-500" />}
                      <span className="min-w-0">
                        <span className="block truncate font-mono text-[10px] text-gray-200">
                          {method.name || 'unnamedMethod'}
                        </span>
                        <span className="block truncate text-[9px] text-gray-600">
                          {method.queryType === 'derived' ? 'Derived query' : 'Explicit JPQL'} · {method.returnType}
                        </span>
                      </span>
                    </button>
                    {securityBypass && (
                      <span title="Method bypasses Jmix security constraints">
                        <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-red-300" />
                      </span>
                    )}
                    {semanticError && (
                      <span title="This method has entity-semantic errors">
                        <XCircle className="h-3.5 w-3.5 shrink-0 text-red-300" />
                      </span>
                    )}
                    {existingMethod && (
                      <span className={`shrink-0 rounded px-1.5 py-0.5 text-[8px] uppercase tracking-wide ${
                        safelyEditable
                          ? 'bg-emerald-500/10 text-emerald-200/75'
                          : 'bg-surface-light text-gray-500'
                      }`}>
                        {safelyEditable ? 'Existing · metadata editable' : 'Source-owned'}
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={() => removeMethod(index)}
                      aria-label={`Remove ${method.name || 'repository method'}`}
                      disabled={existingMethod}
                      className="rounded p-1 text-red-400 hover:bg-red-500/10 disabled:cursor-not-allowed disabled:opacity-25"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  </div>

                  {expanded && (
                    <fieldset
                      disabled={sourceOwned}
                      className="min-w-0 space-y-3 border-t border-surface-border p-3 disabled:opacity-65"
                    >
                      {sourceOwned && (
                        <div className="rounded border border-surface-border bg-surface-light/50 p-2 text-[9px] leading-relaxed text-gray-500">
                          {sourceEvidence?.issue ||
                            'This declaration contains source-owned constructs. Add a new method here or use IntelliJ source tools.'}
                        </div>
                      )}
                      {safelyEditable && (
                        <div className="rounded border border-emerald-500/25 bg-emerald-500/5 p-2 text-[9px] leading-relaxed text-emerald-100/75">
                          Query, bindings, fetch plan, documentation, hints and method security are revision-safe.
                          Callable name, return type and parameter contract remain locked to protect callers.
                        </div>
                      )}
                      {(duplicate || parameterIssue) && (
                        <div className="rounded border border-amber-500/30 bg-amber-500/10 p-2 text-[10px] text-amber-200">
                          {duplicate ? 'Another method has the same JVM signature. ' : ''}
                          {parameterIssue}
                        </div>
                      )}
                      {methodDiagnostics.length > 0 && (
                        <div className="space-y-1.5">
                          {methodDiagnostics.map(diagnostic => (
                            <div
                              key={`${diagnostic.code}-${diagnostic.message}`}
                              className={`rounded border px-2.5 py-2 text-[9px] leading-relaxed ${
                                diagnostic.severity === 'error'
                                  ? 'border-red-500/25 bg-red-500/5 text-red-100'
                                  : 'border-amber-500/25 bg-amber-500/5 text-amber-100'
                              }`}
                            >
                              <span className="font-medium">{diagnostic.message}</span>
                              {diagnostic.sourceOwned && (
                                <span className="ml-1 rounded bg-white/5 px-1.5 py-0.5 text-[8px] uppercase tracking-wide opacity-70">
                                  Source-owned · advisory
                                </span>
                              )}
                              {diagnostic.suggestions.length > 0 && (
                                <span className="mt-1 block opacity-70">
                                  Try: {diagnostic.suggestions.join(', ')}
                                </span>
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                      {semanticMethod && (
                        <div className="flex min-w-0 flex-wrap gap-1.5 text-[8px]">
                          <span className="rounded bg-surface-light px-2 py-1 text-gray-400">
                            {semanticMethod.resultKind} result
                          </span>
                          <span className="rounded bg-surface-light px-2 py-1 text-gray-400">
                            {semanticMethod.expectedValueParameters} value parameter(s)
                          </span>
                          {semanticMethod.propertyPaths.map(path => (
                            <span
                              key={path}
                              className="max-w-full truncate rounded bg-violet-500/10 px-2 py-1 font-mono text-violet-200/75"
                            >
                              {path}
                            </span>
                          ))}
                        </div>
                      )}
                      {method.queryType === 'derived' && semantics && !existingMethod && (
                        <div className="grid min-w-0 gap-2 rounded border border-violet-500/20 bg-violet-500/[0.04] p-2 sm:grid-cols-[minmax(0,1fr)_auto]">
                          <label className="min-w-0 text-[9px] text-violet-100/70">
                            Add an entity property condition
                            <select
                              value={propertyDrafts[index] ?? ''}
                              onChange={event => setPropertyDrafts(current => ({
                                ...current,
                                [index]: event.target.value,
                              }))}
                              className="mt-1 w-full min-w-0 font-mono text-[9px]"
                            >
                              <option value="">Choose an indexed property…</option>
                              {semantics.propertyPaths
                                .filter(property => !property.collection)
                                .map(property => (
                                  <option key={property.path} value={property.path}>
                                    {property.path} · {property.javaType}
                                  </option>
                                ))}
                            </select>
                          </label>
                          <button
                            type="button"
                            disabled={!propertyDrafts[index]}
                            onClick={() => addDerivedCondition(index)}
                            className="self-end rounded border border-violet-400/25 px-2.5 py-1.5 text-[9px] text-violet-100 disabled:opacity-35"
                          >
                            Add condition
                          </button>
                        </div>
                      )}
                      <div className="grid min-w-0 gap-3 sm:grid-cols-2 xl:grid-cols-3">
                        <label className="min-w-0 text-[10px] text-gray-500">
                          Method name
                          <input
                            value={method.name}
                            disabled={existingMethod}
                            onChange={event => updateMethod(index, { name: event.target.value })}
                            className="mt-1 w-full min-w-0 font-mono"
                          />
                        </label>
                        <label className="min-w-0 text-[10px] text-gray-500">
                          Result type
                          <input
                            value={method.returnType}
                            disabled={existingMethod}
                            onChange={event => updateMethod(index, { returnType: event.target.value })}
                            list={`repository-return-${index}`}
                            className="mt-1 w-full min-w-0 font-mono"
                          />
                          <datalist id={`repository-return-${index}`}>
                            {RETURN_PRESETS.map(value => (
                              <option key={value} value={methodType(value, entity)} />
                            ))}
                          </datalist>
                        </label>
                        <label className="min-w-0 text-[10px] text-gray-500">
                          Shared fetch plan
                          <input
                            value={method.fetchPlan ?? ''}
                            onChange={event => updateMethod(index, {
                              fetchPlan: event.target.value || undefined,
                            })}
                            placeholder="employee-summary"
                            className="mt-1 w-full min-w-0 font-mono"
                          />
                        </label>
                      </div>
                      <label className="block min-w-0 text-[10px] text-gray-500">
                        Developer intent
                        <input
                          value={method.description ?? ''}
                          disabled={existingMethod}
                          onChange={event => updateMethod(index, {
                            description: event.target.value || undefined,
                          })}
                          placeholder="Explain the business purpose and expected result."
                          className="mt-1 w-full min-w-0"
                        />
                      </label>

                      {method.queryType === 'jpql' && (
                        <div className="min-w-0 space-y-2">
                          <label className="block min-w-0 text-[10px] text-gray-500">
                            Read-only JPQL
                            <textarea
                              value={method.query ?? ''}
                              onChange={event => updateMethod(index, { query: event.target.value })}
                              rows={4}
                              spellCheck={false}
                              className="mt-1 w-full min-w-0 resize-y font-mono text-[10px]"
                            />
                          </label>
                          <label className="block min-w-0 text-[10px] text-gray-500">
                            Aggregate property names
                            <input
                              value={method.queryProperties.join(', ')}
                              onChange={event => updateMethod(index, {
                                queryProperties: event.target.value
                                  .split(',')
                                  .map(value => value.trim())
                                  .filter(Boolean),
                              })}
                              placeholder="department, employeeCount"
                              className="mt-1 w-full min-w-0 font-mono"
                            />
                            <span className="mt-1 block text-[9px] text-gray-600">
                              Multiple scalar columns require a List&lt;KeyValueEntity&gt; result.
                            </span>
                          </label>
                        </div>
                      )}

                      <div className="rounded border border-surface-border bg-surface-light/40 p-2.5">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div>
                            <h6 className="text-[10px] font-semibold text-gray-300">Parameters</h6>
                            <p className="text-[9px] text-gray-600">
                              Special roles generate the correct Jmix/Spring type automatically.
                            </p>
                          </div>
                          <button
                            type="button"
                            disabled={existingMethod}
                            onClick={() => updateMethod(index, {
                              parameters: [
                                ...method.parameters,
                                emptyParameter(method.parameters.length),
                              ],
                            })}
                            className="rounded border border-surface-border px-2 py-1 text-[9px] text-gray-300"
                          >
                            + Parameter
                          </button>
                        </div>
                        <div className="mt-2 space-y-2">
                          {method.parameters.map((parameter, parameterIndex) => (
                            <div
                              key={`${parameter.name}-${parameterIndex}`}
                              className="grid min-w-0 gap-2 rounded border border-surface-border p-2 sm:grid-cols-2 xl:grid-cols-[1fr_1fr_1fr_auto_auto]"
                            >
                              <input
                                value={parameter.name}
                                disabled={existingMethod}
                                onChange={event => {
                                  const parameters = method.parameters.map((candidate, candidateIndex) =>
                                    candidateIndex === parameterIndex
                                      ? { ...candidate, name: event.target.value }
                                      : candidate)
                                  updateMethod(index, { parameters })
                                }}
                                aria-label="Parameter name"
                                placeholder="name"
                                className="min-w-0 font-mono text-[10px]"
                              />
                              <select
                                value={parameter.role}
                                disabled={existingMethod}
                                onChange={event => {
                                  const role = event.target.value as RepositoryParameterRole
                                  const roleConfig = PARAMETER_ROLES.find(candidate => candidate.value === role)
                                  const parameters = method.parameters.map((candidate, candidateIndex) =>
                                    candidateIndex === parameterIndex
                                      ? {
                                          ...candidate,
                                          role,
                                          type: role === 'value' ? candidate.type : roleConfig?.type ?? candidate.type,
                                          nullable: role === 'fetchPlan' ? candidate.nullable : false,
                                        }
                                      : candidate)
                                  updateMethod(index, { parameters })
                                }}
                                aria-label="Parameter role"
                                className="min-w-0 text-[10px]"
                              >
                                {PARAMETER_ROLES.map(role => (
                                  <option key={role.value} value={role.value}>{role.label}</option>
                                ))}
                              </select>
                              <input
                                value={parameter.type}
                                onChange={event => {
                                  const parameters = method.parameters.map((candidate, candidateIndex) =>
                                    candidateIndex === parameterIndex
                                      ? { ...candidate, type: event.target.value }
                                      : candidate)
                                  updateMethod(index, { parameters })
                                }}
                                disabled={existingMethod || parameter.role !== 'value'}
                                aria-label="Parameter JVM type"
                                placeholder="String"
                                className="min-w-0 font-mono text-[10px] disabled:opacity-60"
                              />
                              <label className="flex items-center gap-1 text-[9px] text-gray-500">
                                <input
                                  type="checkbox"
                                  checked={parameter.nullable}
                                  disabled={existingMethod || !['value', 'fetchPlan'].includes(parameter.role)}
                                  onChange={event => {
                                    const parameters = method.parameters.map((candidate, candidateIndex) =>
                                      candidateIndex === parameterIndex
                                        ? { ...candidate, nullable: event.target.checked }
                                        : candidate)
                                    updateMethod(index, { parameters })
                                  }}
                                />
                                Nullable
                              </label>
                              <button
                                type="button"
                                disabled={existingMethod}
                                onClick={() => updateMethod(index, {
                                  parameters: method.parameters.filter(
                                    (_, candidateIndex) => candidateIndex !== parameterIndex,
                                  ),
                                })}
                                aria-label={`Remove parameter ${parameter.name}`}
                                className="justify-self-end rounded p-1 text-red-400"
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                              </button>
                              {method.queryType === 'jpql' &&
                                config.useNamedParameters &&
                                parameter.role === 'value' && (
                                  <input
                                    value={parameter.bindingName ?? ''}
                                    onChange={event => {
                                      const parameters = method.parameters.map((candidate, candidateIndex) =>
                                        candidateIndex === parameterIndex
                                          ? {
                                              ...candidate,
                                              bindingName: event.target.value || undefined,
                                            }
                                          : candidate)
                                      updateMethod(index, { parameters })
                                    }}
                                    aria-label="JPQL binding name"
                                    placeholder={`JPQL binding: ${parameter.name}`}
                                    className="min-w-0 font-mono text-[9px] sm:col-span-2 xl:col-span-5"
                                  />
                                )}
                            </div>
                          ))}
                        </div>
                      </div>

                      <div className="grid min-w-0 gap-3 lg:grid-cols-2">
                        <label className="min-w-0 text-[10px] text-gray-500">
                          Method security override
                          <select
                            value={method.applyConstraints === undefined
                              ? 'inherit'
                              : method.applyConstraints ? 'enforce' : 'bypass'}
                            onChange={event => updateMethod(index, {
                              applyConstraints: event.target.value === 'inherit'
                                ? undefined
                                : event.target.value === 'enforce',
                            })}
                            className={`mt-1 w-full min-w-0 ${
                              method.applyConstraints === false ? 'border-red-500/50 text-red-200' : ''
                            }`}
                          >
                            <option value="inherit">Inherit repository setting</option>
                            <option value="enforce">Always enforce constraints</option>
                            <option value="bypass">Bypass constraints — privileged</option>
                          </select>
                        </label>
                        <div className="min-w-0">
                          <div className="flex items-center justify-between gap-2">
                            <span className="text-[10px] text-gray-500">Query hints</span>
                            <button
                              type="button"
                              onClick={() => updateMethod(index, {
                                queryHints: [...method.queryHints, { name: '', value: '' }],
                              })}
                              className="text-[9px] text-jmix-300"
                            >
                              + Hint
                            </button>
                          </div>
                          <div className="mt-1 space-y-1.5">
                            {method.queryHints.map((hint, hintIndex) => (
                              <div
                                key={`${hint.name}-${hintIndex}`}
                                className="grid min-w-0 grid-cols-[1fr_1fr_auto] gap-1.5"
                              >
                                <input
                                  value={hint.name}
                                  onChange={event => updateMethod(index, {
                                    queryHints: method.queryHints.map((candidate, candidateIndex) =>
                                      candidateIndex === hintIndex
                                        ? { ...candidate, name: event.target.value }
                                        : candidate),
                                  })}
                                  aria-label="Query hint name"
                                  placeholder="Hint name"
                                  className="min-w-0 font-mono text-[9px]"
                                />
                                <input
                                  value={hint.value}
                                  onChange={event => updateMethod(index, {
                                    queryHints: method.queryHints.map((candidate, candidateIndex) =>
                                      candidateIndex === hintIndex
                                        ? { ...candidate, value: event.target.value }
                                        : candidate),
                                  })}
                                  aria-label="Query hint value"
                                  placeholder="Value"
                                  className="min-w-0 font-mono text-[9px]"
                                />
                                <button
                                  type="button"
                                  onClick={() => updateMethod(index, {
                                    queryHints: method.queryHints.filter(
                                      (_, candidateIndex) => candidateIndex !== hintIndex,
                                    ),
                                  })}
                                  aria-label="Remove query hint"
                                  className="rounded p-1 text-red-400"
                                >
                                  <Trash2 className="h-3.5 w-3.5" />
                                </button>
                              </div>
                            ))}
                          </div>
                        </div>
                      </div>
                    </fieldset>
                  )}
                </article>
              )
            })}
          </div>
          {footer}
        </div>
      )}
    </section>
  )
}
