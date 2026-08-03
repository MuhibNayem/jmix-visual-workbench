import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import {
  AlertTriangle, CheckCircle2, ChevronRight, Clock3, Code2, Database, ExternalLink,
  FileJson2, KeyRound, Loader2, Play, Plus, RefreshCw, Save, Search, Server, ShieldCheck,
  ShieldX, Trash2, Workflow, X,
} from 'lucide-react'
import { bridge } from '../../bridge'
import type {
  RestApiInvocationResponse,
  RestApiContractAdditionRequest,
  RestApiContractMutationRequest,
  RestApiContractParameterInput,
  RestApiOperationKind,
  RestApiOperationSnapshot,
  RestApiWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

type Pane = 'operations' | 'contract' | 'request'
type KindFilter = 'ALL' | RestApiOperationKind

interface SavedInvocation {
  id: string
  name: string
  operationId: string
  method: string
  path: string
  body: string
  baseUrl: string
}

const STORAGE_KEY = 'jvw-rest-invocations-v1'
const kindStyle: Record<RestApiOperationKind, string> = {
  CONTROLLER: 'border-violet-500/30 bg-violet-500/10 text-violet-200',
  SERVICE: 'border-sky-500/30 bg-sky-500/10 text-sky-200',
  QUERY: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200',
}

function loadSavedInvocations(): SavedInvocation[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
    return Array.isArray(parsed)
      ? parsed.filter((item): item is SavedInvocation => (
          item && typeof item.id === 'string' && typeof item.name === 'string' &&
          typeof item.operationId === 'string' && typeof item.path === 'string'
        )).slice(0, 100)
      : []
  } catch {
    return []
  }
}

function initialBody(operation: RestApiOperationSnapshot): string {
  if (operation.parameters.length === 0) return '{}'
  return JSON.stringify(
    Object.fromEntries(operation.parameters.map((parameter) => [
      parameter.name,
      parameter.javaType.endsWith('[]') ? [] : parameter.javaType.includes('Boolean') ? false : '',
    ])),
    null,
    2,
  )
}

function formatBody(body: string): string {
  try {
    return JSON.stringify(JSON.parse(body), null, 2)
  } catch {
    return body
  }
}

export default function ApiDesigner() {
  const [workspace, setWorkspace] = useState<RestApiWorkspaceResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [kind, setKind] = useState<KindFilter>('ALL')
  const [selectedId, setSelectedId] = useState('')
  const [pane, setPane] = useState<Pane>('operations')
  const [baseUrl, setBaseUrl] = useState('http://localhost:8080')
  const [method, setMethod] = useState('GET')
  const [path, setPath] = useState('/')
  const [token, setToken] = useState('')
  const [body, setBody] = useState('{}')
  const [sending, setSending] = useState(false)
  const [response, setResponse] = useState<RestApiInvocationResponse | null>(null)
  const [saved, setSaved] = useState<SavedInvocation[]>(loadSavedInvocations)
  const [saveName, setSaveName] = useState('')
  const [authoring, setAuthoring] = useState(false)
  const [authorMode, setAuthorMode] = useState<'ADD' | 'UPDATE' | 'REMOVE'>('ADD')
  const [contractKind, setContractKind] = useState<'SERVICE' | 'QUERY'>('SERVICE')
  const [configId, setConfigId] = useState('')
  const [serviceName, setServiceName] = useState('')
  const [methodName, setMethodName] = useState('')
  const [queryName, setQueryName] = useState('')
  const [entityName, setEntityName] = useState('')
  const [fetchPlan, setFetchPlan] = useState('_base')
  const [jpql, setJpql] = useState('')
  const [contractParameters, setContractParameters] = useState<RestApiContractParameterInput[]>([])
  const [mutationTarget, setMutationTarget] = useState<RestApiContractMutationRequest['target'] | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [authorError, setAuthorError] = useState<string | null>(null)
  const [pendingContract, setPendingContract] = useState<{
    change: RestApiContractAdditionRequest | RestApiContractMutationRequest
    mode: 'ADD' | 'UPDATE' | 'REMOVE'
    preview: WorkspaceChangePreviewResponse
  } | null>(null)

  const load = async (forceRefresh: boolean = false) => {
    setLoading(true)
    setError(null)
    try {
      const next = await bridge.getRestApiWorkspace(forceRefresh)
      if (next.error) {
        setError(next.error)
        return
      }
      setWorkspace(next)
      setSelectedId((current) => (
        current && next.operations.some((operation) => operation.artifactId === current)
          ? current
          : next.operations[0]?.artifactId ?? ''
      ))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'REST API workspace request failed.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const selected = workspace?.operations.find((operation) => operation.artifactId === selectedId) ?? null
  const normalizedQuery = query.trim().toLowerCase()
  const matchingOperations = useMemo(() => (
    workspace?.operations.filter((operation) => (
      (kind === 'ALL' || operation.kind === kind) &&
      (!normalizedQuery || [
        operation.displayName,
        operation.path,
        operation.moduleId,
        ...operation.entityNames,
      ].some((value) => value.toLowerCase().includes(normalizedQuery)))
    )) ?? []
  ), [workspace, kind, normalizedQuery])
  const visibleOperations = useMemo(() => matchingOperations.slice(0, 300), [matchingOperations])
  const selectedFindings = workspace?.findings.filter((finding) => (
    !finding.operationId || finding.operationId === selectedId
  )) ?? []
  const compatibleConfigs = workspace?.configs.filter((config) => (
    config.kind === (contractKind === 'SERVICE' ? 'SERVICES' : 'QUERIES')
  )) ?? []
  const selectedConfig = compatibleConfigs.find((config) => config.artifactId === configId)
    ?? compatibleConfigs[0]
  const selectedEditableConfig = selected && selected.kind !== 'CONTROLLER'
    ? workspace?.configs.find((config) => (
        config.sourceLocator.relativePath === selected.sourceLocator.relativePath &&
        config.kind === (selected.kind === 'SERVICE' ? 'SERVICES' : 'QUERIES')
      ))
    : undefined

  useEffect(() => {
    setConfigId((current) => (
      compatibleConfigs.some((config) => config.artifactId === current)
        ? current
        : compatibleConfigs[0]?.artifactId ?? ''
    ))
  }, [contractKind, workspace?.graphDigest])

  const selectOperation = (operation: RestApiOperationSnapshot) => {
    setSelectedId(operation.artifactId)
    setMethod(operation.methods[0] === 'REQUEST' ? 'GET' : operation.methods[0])
    setPath(operation.path)
    setBody(initialBody(operation))
    setResponse(null)
    setPane('contract')
  }

  const invoke = async () => {
    setSending(true)
    setResponse(null)
    try {
      setResponse(await bridge.invokeRestApi({
        baseUrl,
        path,
        method,
        headers: {
          Accept: 'application/json',
          ...(method === 'GET' ? {} : { 'Content-Type': 'application/json' }),
          ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}),
        },
        body: method === 'GET' ? '' : body,
        timeoutMillis: 15_000,
      }))
    } catch (cause) {
      setResponse({
        accepted: false,
        durationMillis: 0,
        headers: {},
        body: '',
        truncated: false,
        errorCode: 'JVW-REST-INVOKE-BRIDGE-FAILED',
        message: cause instanceof Error ? cause.message : 'REST invocation failed.',
      })
    } finally {
      setSending(false)
    }
  }

  const persistSaved = (next: SavedInvocation[]) => {
    setSaved(next)
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  }

  const saveInvocation = () => {
    if (!selected) return
    const invocation: SavedInvocation = {
      id: `saved:${Date.now()}`,
      name: saveName.trim() || `${method} ${selected.displayName}`,
      operationId: selected.artifactId,
      method,
      path,
      body,
      baseUrl,
    }
    persistSaved([invocation, ...saved].slice(0, 100))
    setSaveName('')
  }

  const restoreInvocation = (invocation: SavedInvocation) => {
    const operation = workspace?.operations.find((candidate) => candidate.artifactId === invocation.operationId)
    if (operation) setSelectedId(operation.artifactId)
    setMethod(invocation.method)
    setPath(invocation.path)
    setBody(invocation.body)
    setBaseUrl(invocation.baseUrl)
    setResponse(null)
    setPane('request')
  }

  const updateContractParameter = (
    index: number,
    patch: Partial<RestApiContractParameterInput>,
  ) => {
    setContractParameters((current) => current.map((parameter, candidate) => (
      candidate === index ? { ...parameter, ...patch } : parameter
    )))
  }

  const openAddition = () => {
    setAuthorMode('ADD')
    setMutationTarget(null)
    setAuthorError(null)
    setPendingContract(null)
    setAuthoring(true)
  }

  const openMutation = (mode: 'UPDATE' | 'REMOVE') => {
    if (!selected || !selectedEditableConfig || selected.kind === 'CONTROLLER') return
    const segments = selected.path.split('/').filter(Boolean)
    setAuthorMode(mode)
    setContractKind(selected.kind)
    setConfigId(selectedEditableConfig.artifactId)
    setContractParameters(selected.parameters.map(({ name, javaType }) => ({ name, javaType })))
    if (selected.kind === 'SERVICE') {
      const separator = selected.displayName.lastIndexOf('.')
      const service = segments[segments.indexOf('services') + 1] ??
        (separator >= 0 ? selected.displayName.slice(0, separator) : selected.displayName)
      const method = segments[segments.indexOf('services') + 2] ??
        (separator >= 0 ? selected.displayName.slice(separator + 1) : selected.displayName)
      setServiceName(service)
      setMethodName(method)
      setMutationTarget({
        kind: 'SERVICE',
        serviceName: service,
        methodName: method,
        parameterTypes: selected.parameters.map((parameter) => parameter.javaType),
      })
    } else {
      const entity = segments[segments.indexOf('queries') + 1] ?? ''
      const name = segments[segments.indexOf('queries') + 2] ?? selected.displayName
      setQueryName(name)
      setEntityName(entity)
      setFetchPlan(selected.fetchPlanName ?? '_base')
      setJpql(selected.queryText ?? '')
      setMutationTarget({ kind: 'QUERY', name, entityName: entity })
    }
    setAuthorError(null)
    setPendingContract(null)
    setAuthoring(true)
  }

  const previewContract = async () => {
    if (!selectedConfig) {
      setAuthorError(`No indexed ${contractKind.toLowerCase()} XML configuration is available.`)
      return
    }
    const contract = contractKind === 'SERVICE'
      ? {
          kind: 'SERVICE' as const,
          serviceName,
          methodName,
          parameters: contractParameters,
        }
      : {
          kind: 'QUERY' as const,
          name: queryName,
          entityName,
          fetchPlan,
          jpql,
          parameters: contractParameters,
        }
    setPreviewing(true)
    setAuthorError(null)
    try {
      const change: RestApiContractAdditionRequest | RestApiContractMutationRequest =
        authorMode === 'ADD'
          ? {
              moduleId: selectedConfig.moduleId,
              configLocator: selectedConfig.sourceLocator,
              contract,
            }
          : {
              moduleId: selectedConfig.moduleId,
              configLocator: selectedConfig.sourceLocator,
              mode: authorMode,
              target: mutationTarget ?? (() => { throw new Error('The indexed contract target is missing.') })(),
              ...(authorMode === 'UPDATE' ? { replacement: contract } : {}),
            }
      const preview = authorMode === 'ADD'
        ? await bridge.previewRestApiContractAddition(change as RestApiContractAdditionRequest)
        : await bridge.previewRestApiContractMutation(change as RestApiContractMutationRequest)
      if (!preview.accepted || !preview.planDigest) {
        setAuthorError(preview.issues[0]?.message ?? 'The contract preview was rejected.')
        return
      }
      setPendingContract({ change, mode: authorMode, preview })
    } catch (cause) {
      setAuthorError(cause instanceof Error ? cause.message : 'Contract preview failed.')
    } finally {
      setPreviewing(false)
    }
  }

  const applyContract = async () => {
    if (!pendingContract?.preview.planDigest) return
    setApplying(true)
    setAuthorError(null)
    try {
      const result = pendingContract.mode === 'ADD'
        ? await bridge.applyRestApiContractAddition(
            pendingContract.change as RestApiContractAdditionRequest,
            pendingContract.preview.planDigest,
          )
        : await bridge.applyRestApiContractMutation(
            pendingContract.change as RestApiContractMutationRequest,
            pendingContract.preview.planDigest,
          )
      if (!result.success) {
        setAuthorError(result.issues[0]?.message ?? 'The reviewed contract could not be applied.')
        setPendingContract(null)
        return
      }
      setPendingContract(null)
      setAuthoring(false)
      setContractParameters([])
      await load(true)
    } catch (cause) {
      setAuthorError(cause instanceof Error ? cause.message : 'Contract apply failed.')
    } finally {
      setApplying(false)
    }
  }

  if (loading && !workspace) {
    return <div className="flex flex-1 items-center justify-center text-xs text-gray-500"><Loader2 className="mr-2 animate-spin" size={16} /> Mapping API contracts…</div>
  }

  return (
    <section className="relative flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-surface" aria-label="REST API designer">
      <header className="shrink-0 border-b border-surface-border bg-surface-light px-4 py-3">
        <div className="flex min-w-0 flex-wrap items-start gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <Server size={17} className="text-jmix-400" />
              <h2 className="text-base font-semibold text-gray-100">API & Service Workbench</h2>
            </div>
            <p className="mt-1 max-w-3xl text-[11px] leading-relaxed text-gray-500">
              Connected controllers, Generic REST services, predefined JPQL queries, API roles, row-security boundaries, OpenAPI, and local contract execution.
            </p>
          </div>
          <button
            type="button"
            onClick={openAddition}
            className="inline-flex items-center gap-1.5 rounded bg-jmix-500 px-2.5 py-1.5 text-[10px] font-semibold text-white hover:bg-jmix-400"
          >
            <Plus size={11} /> Add contract
          </button>
          <button
            type="button"
            onClick={() => void load(true)}
            disabled={loading}
            className="inline-flex items-center gap-1.5 rounded border border-surface-border bg-surface-lighter px-2.5 py-1.5 text-[10px] text-gray-300 hover:border-jmix-500/50 hover:text-jmix-300 disabled:opacity-50"
          >
            <RefreshCw size={11} className={loading ? 'animate-spin' : ''} /> Refresh
          </button>
        </div>
        {workspace && (
          <div className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6">
            <Metric label="Controllers" value={workspace.summary.controllerCount} />
            <Metric label="Services" value={workspace.summary.serviceCount} />
            <Metric label="Queries" value={workspace.summary.queryCount} />
            <Metric label="API roles" value={workspace.apiRoles.length} />
            <Metric label="Errors" value={workspace.summary.errorCount} danger={workspace.summary.errorCount > 0} />
            <Metric label="Warnings" value={workspace.summary.warningCount} warning={workspace.summary.warningCount > 0} />
          </div>
        )}
        {workspace && (
          <div className={`mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 rounded border px-3 py-2 text-[9px] ${
            workspace.security.restProtected && workspace.security.restEnabledRoleCount > 0
              ? 'border-emerald-500/25 bg-emerald-500/5 text-emerald-200'
              : 'border-red-500/30 bg-red-500/10 text-red-200'
          }`}>
            {workspace.security.restProtected ? <ShieldCheck size={12} /> : <ShieldX size={12} />}
            <span className="font-semibold">
              {workspace.security.restProtected ? 'REST URL protection indexed' : 'REST URL protection missing'}
            </span>
            <span className="text-current/60">{workspace.security.authenticatedPatterns || 'no authenticated patterns'}</span>
            <span>{workspace.security.restEnabledRoleCount} API-scoped rest.enabled role(s)</span>
          </div>
        )}
      </header>

      {error && <div className="m-3 rounded border border-red-500/30 bg-red-500/10 p-3 text-xs text-red-200">{error}</div>}

      {workspace && (
        <>
          <ResponsivePaneSwitcher
            value={pane}
            onChange={setPane}
            label="API workbench panels"
            options={[
              { id: 'operations', label: 'Operations', badge: matchingOperations.length },
              { id: 'contract', label: 'Contract', badge: selectedFindings.length },
              { id: 'request', label: 'Request runner', badge: saved.length },
            ]}
          />
          <div className="flex min-h-0 min-w-0 flex-1 overflow-hidden">
            <aside className={`${pane === 'operations' ? 'flex' : 'hidden'} min-h-0 min-w-0 w-full flex-col border-r border-surface-border bg-surface-light/25 lg:flex lg:w-[27%] lg:max-w-[23rem]`}>
              <div className="shrink-0 border-b border-surface-border p-2.5">
                <div className="flex min-w-0 items-center gap-2 rounded border border-surface-border bg-surface px-2">
                  <Search size={12} className="shrink-0 text-gray-600" />
                  <input
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="Search operation, path, entity"
                    className="min-w-0 flex-1 border-0 bg-transparent py-2 text-[10px] outline-none"
                  />
                </div>
                <div className="mt-2 flex flex-wrap gap-1">
                  {(['ALL', 'CONTROLLER', 'SERVICE', 'QUERY'] as KindFilter[]).map((candidate) => (
                    <button
                      type="button"
                      key={candidate}
                      onClick={() => setKind(candidate)}
                      className={`rounded px-2 py-1 text-[9px] ${kind === candidate ? 'bg-jmix-500 text-white' : 'bg-surface-lighter text-gray-500 hover:text-gray-200'}`}
                    >
                      {candidate === 'ALL' ? 'All' : candidate.toLowerCase()}
                    </button>
                  ))}
                </div>
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto p-2">
                {matchingOperations.length > visibleOperations.length && (
                  <div className="mb-2 rounded border border-amber-500/20 bg-amber-500/5 px-2 py-1.5 text-[9px] text-amber-200/80">
                    Showing the first {visibleOperations.length} of {matchingOperations.length} operations. Refine the search to narrow the catalog.
                  </div>
                )}
                {visibleOperations.map((operation) => (
                  <button
                    type="button"
                    key={operation.artifactId}
                    onClick={() => selectOperation(operation)}
                    className={`mb-1.5 w-full min-w-0 rounded-lg border p-2.5 text-left transition-colors ${
                      selectedId === operation.artifactId
                        ? 'border-jmix-500/60 bg-jmix-500/10'
                        : 'border-surface-border bg-surface-light hover:border-gray-600'
                    }`}
                  >
                    <div className="flex min-w-0 items-center gap-2">
                      <span className={`shrink-0 rounded border px-1.5 py-0.5 text-[8px] font-semibold ${kindStyle[operation.kind]}`}>{operation.kind}</span>
                      <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-gray-200">{operation.displayName}</span>
                      <ChevronRight size={11} className="shrink-0 text-gray-700" />
                    </div>
                    <div className="mt-1.5 truncate font-mono text-[9px] text-gray-600">{operation.methods.join('|')} {operation.path}</div>
                    <div className="mt-1 flex min-w-0 flex-wrap gap-1 text-[8px] text-gray-600">
                      <span>{operation.moduleId}</span>
                      {operation.entityNames.map((entity) => <span key={entity}>· {entity}</span>)}
                    </div>
                  </button>
                ))}
                {visibleOperations.length === 0 && <EmptyState text="No API operations match the current filters." />}
              </div>
            </aside>

            <main className={`${pane === 'contract' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col lg:flex`}>
              {selected ? (
                <div className="min-h-0 flex-1 overflow-y-auto p-3">
                  <div className="rounded-lg border border-surface-border bg-surface-light p-3">
                    <div className="flex min-w-0 flex-wrap items-center gap-2">
                      <span className={`rounded border px-2 py-1 text-[9px] font-semibold ${kindStyle[selected.kind]}`}>{selected.kind}</span>
                      <span className="min-w-0 flex-1 truncate font-mono text-xs text-gray-100">{selected.displayName}</span>
                      <button
                        type="button"
                        onClick={() => void bridge.navigateToSource(selected.sourceLocator)}
                        className="inline-flex items-center gap-1 rounded px-2 py-1 text-[9px] text-gray-500 hover:bg-surface-lighter hover:text-jmix-300"
                      >
                        <ExternalLink size={11} /> Source
                      </button>
                      {selectedEditableConfig && (
                        <>
                          <button
                            type="button"
                            onClick={() => openMutation('UPDATE')}
                            className="inline-flex items-center gap-1 rounded border border-surface-border px-2 py-1 text-[9px] text-gray-400 hover:border-jmix-500/40 hover:text-jmix-300"
                          >
                            <Code2 size={10} /> Edit
                          </button>
                          <button
                            type="button"
                            onClick={() => openMutation('REMOVE')}
                            className="inline-flex items-center gap-1 rounded border border-red-500/20 px-2 py-1 text-[9px] text-red-300/70 hover:bg-red-500/10 hover:text-red-200"
                          >
                            <Trash2 size={10} /> Remove
                          </button>
                        </>
                      )}
                    </div>
                    <div className="mt-3 flex min-w-0 items-center gap-2 rounded border border-surface-border bg-surface px-3 py-2 font-mono text-[10px]">
                      <span className="rounded bg-jmix-500/15 px-1.5 py-0.5 font-semibold text-jmix-300">{selected.methods.join(' | ')}</span>
                      <span className="min-w-0 flex-1 break-all text-gray-300">{selected.path}</span>
                    </div>
                  </div>

                  <div className="mt-3 grid min-w-0 gap-3 2xl:grid-cols-2">
                    <ContractSection icon={Code2} title="Input contract">
                      {selected.parameters.length ? selected.parameters.map((parameter) => (
                        <button
                          type="button"
                          key={parameter.name}
                          onClick={() => void bridge.navigateToSource(parameter.sourceLocator)}
                          className="flex w-full min-w-0 items-center gap-2 border-b border-surface-border/60 px-2 py-2 text-left last:border-0 hover:bg-surface-lighter"
                        >
                          <span className="min-w-0 flex-1 truncate font-mono text-[10px] text-gray-200">{parameter.name}</span>
                          <span className="max-w-[55%] truncate font-mono text-[9px] text-gray-600">{parameter.javaType}</span>
                          <span className="text-[8px] text-amber-300">{parameter.required ? 'required' : 'optional'}</span>
                        </button>
                      )) : <EmptyState text="No explicit parameters are indexed for this operation." compact />}
                    </ContractSection>
                    <ContractSection icon={Database} title="Data impact">
                      <ContractFact label="Entities" value={selected.entityNames.join(', ') || 'No entity dependency indexed'} />
                      <ContractFact label="Transaction" value={selected.transactionBoundary.replace(/_/g, ' ').toLowerCase()} />
                      <ContractFact
                        label="Row security"
                        value={selected.rowSecurity.replace(/_/g, ' ').toLowerCase()}
                        warning={selected.rowSecurity !== 'ENFORCED_READ'}
                      />
                    </ContractSection>
                    <ContractSection icon={KeyRound} title="Authorization">
                      <ContractFact label="rest.enabled API roles" value={`${workspace.apiRoles.length}`} />
                      <ContractFact label="Operation role references" value={`${selected.securedRoleIds.length}`} warning={selected.kind === 'CONTROLLER' && selected.securedRoleIds.length === 0} />
                      <ContractFact label="Authenticated URLs" value={workspace.security.authenticatedPatterns || 'Not indexed'} warning={!workspace.security.restProtected} />
                    </ContractSection>
                    <ContractSection icon={FileJson2} title="OpenAPI">
                      {[workspace.openApi.detailedJsonPath, workspace.openApi.genericJsonPath].map((openApiPath) => (
                        <button
                          type="button"
                          key={openApiPath}
                          onClick={() => {
                            setMethod('GET')
                            setPath(openApiPath)
                            setBody('')
                            setPane('request')
                          }}
                          className="flex w-full min-w-0 items-center gap-2 border-b border-surface-border/60 px-2 py-2 text-left font-mono text-[9px] text-sky-300 last:border-0 hover:bg-surface-lighter"
                        >
                          <FileJson2 size={11} /> <span className="truncate">{openApiPath}</span>
                        </button>
                      ))}
                    </ContractSection>
                  </div>

                  <div className="mt-3 space-y-2">
                    {selectedFindings.map((finding) => (
                      <button
                        type="button"
                        key={`${finding.code}:${finding.operationId ?? ''}`}
                        onClick={() => finding.sourceLocator && void bridge.navigateToSource(finding.sourceLocator)}
                        className={`w-full rounded-lg border p-3 text-left ${
                          finding.severity === 'ERROR' || finding.severity === 'BLOCKING'
                            ? 'border-red-500/30 bg-red-500/10 text-red-100'
                            : 'border-amber-500/30 bg-amber-500/10 text-amber-100'
                        }`}
                      >
                        <div className="flex items-start gap-2">
                          <AlertTriangle size={12} className="mt-0.5 shrink-0" />
                          <div className="min-w-0">
                            <div className="text-[10px] font-semibold">{finding.title}</div>
                            <p className="mt-1 text-[9px] leading-relaxed opacity-70">{finding.message}</p>
                            {finding.remediation && <p className="mt-1.5 text-[9px] opacity-50">Fix: {finding.remediation}</p>}
                          </div>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
              ) : <EmptyState text="Select an API operation to inspect its complete contract." />}
            </main>

            <aside className={`${pane === 'request' ? 'flex' : 'hidden'} min-h-0 min-w-0 w-full flex-col border-l border-surface-border bg-surface-light/20 lg:flex lg:w-[31%] lg:max-w-[27rem]`}>
              <div className="shrink-0 border-b border-surface-border px-3 py-2.5">
                <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-gray-400">
                  <Play size={12} className="text-jmix-400" /> Local contract runner
                </div>
                <p className="mt-1 text-[9px] leading-relaxed text-gray-600">Loopback HTTP(S) only. Bearer tokens remain in memory and are never saved.</p>
              </div>
              <div className="min-h-0 flex-1 overflow-y-auto p-3">
                <Field label="Base URL">
                  <input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} className="field-input font-mono" />
                </Field>
                <div className="mt-2 grid min-w-0 grid-cols-[5rem_minmax(0,1fr)] gap-2">
                  <Field label="Method">
                    <select value={method} onChange={(event) => setMethod(event.target.value)} className="field-input">
                      {['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((verb) => <option key={verb}>{verb}</option>)}
                    </select>
                  </Field>
                  <Field label="Path">
                    <input value={path} onChange={(event) => setPath(event.target.value)} className="field-input font-mono" />
                  </Field>
                </div>
                <div className="mt-2">
                  <Field label="Bearer token — not persisted">
                    <input type="password" value={token} onChange={(event) => setToken(event.target.value)} autoComplete="off" className="field-input font-mono" placeholder="Optional access token" />
                  </Field>
                </div>
                {method !== 'GET' && (
                  <div className="mt-2">
                    <Field label="JSON body">
                      <textarea value={body} onChange={(event) => setBody(event.target.value)} className="field-input min-h-40 resize-y font-mono leading-relaxed" spellCheck={false} />
                    </Field>
                  </div>
                )}
                <div className="mt-2 flex min-w-0 gap-2">
                  <button
                    type="button"
                    onClick={() => void invoke()}
                    disabled={sending}
                    className="inline-flex flex-1 items-center justify-center gap-1.5 rounded bg-jmix-500 px-3 py-2 text-[10px] font-semibold text-white hover:bg-jmix-400 disabled:opacity-50"
                  >
                    {sending ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />} Send request
                  </button>
                  <button
                    type="button"
                    onClick={() => setBody(formatBody(body))}
                    className="rounded border border-surface-border bg-surface-lighter px-2.5 text-[9px] text-gray-400 hover:text-gray-200"
                  >
                    Format
                  </button>
                </div>

                {response && (
                  <div className={`mt-3 rounded-lg border p-3 ${
                    response.accepted && (response.status ?? 500) < 400
                      ? 'border-emerald-500/30 bg-emerald-500/5'
                      : 'border-red-500/30 bg-red-500/5'
                  }`}>
                    <div className="flex min-w-0 flex-wrap items-center gap-2 text-[9px]">
                      {response.accepted ? <CheckCircle2 size={12} className="text-emerald-300" /> : <AlertTriangle size={12} className="text-red-300" />}
                      <span className="font-semibold text-gray-200">{response.status ? `HTTP ${response.status}` : response.errorCode}</span>
                      <span className="ml-auto inline-flex items-center gap-1 text-gray-600"><Clock3 size={10} /> {response.durationMillis} ms</span>
                    </div>
                    <div className="mt-1 text-[9px] text-gray-500">{response.message}</div>
                    {response.body && <pre className="mt-2 max-h-72 overflow-auto whitespace-pre-wrap rounded bg-gray-950/60 p-2 font-mono text-[9px] leading-relaxed text-gray-300">{formatBody(response.body)}</pre>}
                  </div>
                )}

                <div className="mt-4 border-t border-surface-border pt-3">
                  <div className="flex min-w-0 gap-2">
                    <input value={saveName} onChange={(event) => setSaveName(event.target.value)} placeholder="Scenario name" className="field-input min-w-0 flex-1" />
                    <button type="button" onClick={saveInvocation} disabled={!selected} className="inline-flex items-center gap-1 rounded border border-surface-border px-2 text-[9px] text-gray-400 hover:text-jmix-300 disabled:opacity-40"><Save size={10} /> Save</button>
                  </div>
                  <div className="mt-2 space-y-1">
                    {saved.map((invocation) => (
                      <div key={invocation.id} className="flex min-w-0 items-center gap-1 rounded border border-surface-border bg-surface px-2 py-1.5">
                        <button type="button" onClick={() => restoreInvocation(invocation)} className="min-w-0 flex-1 truncate text-left text-[9px] text-gray-300 hover:text-jmix-300">
                          <span className="font-mono text-gray-600">{invocation.method}</span> {invocation.name}
                        </button>
                        <button type="button" onClick={() => persistSaved(saved.filter((item) => item.id !== invocation.id))} className="shrink-0 p-1 text-gray-700 hover:text-red-300" aria-label={`Delete ${invocation.name}`}><Trash2 size={10} /></button>
                      </div>
                    ))}
                    {saved.length === 0 && <p className="py-2 text-center text-[9px] text-gray-700">No saved contract requests.</p>}
                  </div>
                </div>
              </div>
            </aside>
          </div>
        </>
      )}
      {authoring && workspace && (
        <div className="absolute inset-0 z-40 flex min-h-0 min-w-0 items-center justify-center bg-gray-950/80 p-2 backdrop-blur-sm sm:p-4">
          <div className="flex max-h-full min-h-0 w-full max-w-5xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl">
            <div className="flex shrink-0 items-start gap-3 border-b border-surface-border bg-surface-light px-4 py-3">
              <div className="min-w-0 flex-1">
                <h3 className="text-sm font-semibold text-gray-100">
                  {authorMode === 'ADD' ? 'Add' : authorMode === 'UPDATE' ? 'Edit' : 'Remove'} Jmix REST contract
                </h3>
                <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
                  {authorMode === 'REMOVE'
                    ? 'Removal targets the exact indexed identity and remains revision locked until apply.'
                    : 'Known contract fields are changed surgically. Unknown XML and manual formatting remain untouched; stale previews are rejected.'}
                </p>
              </div>
              <button
                type="button"
                onClick={() => {
                  setAuthoring(false)
                  setPendingContract(null)
                  setAuthorError(null)
                }}
                className="rounded p-1.5 text-gray-500 hover:bg-surface-lighter hover:text-gray-200"
                aria-label="Close contract editor"
              >
                <X size={15} />
              </button>
            </div>

            <div className="min-h-0 flex-1 overflow-y-auto p-3 sm:p-4">
              {!pendingContract && authorMode === 'REMOVE' ? (
                <div className="mx-auto max-w-2xl rounded-xl border border-red-500/25 bg-red-500/5 p-5">
                  <div className="flex items-start gap-3">
                    <AlertTriangle size={18} className="mt-0.5 shrink-0 text-red-300" />
                    <div className="min-w-0">
                      <h4 className="text-sm font-semibold text-red-100">Review contract removal</h4>
                      <p className="mt-2 text-[10px] leading-relaxed text-red-100/60">
                        The plugin will remove only the selected {contractKind.toLowerCase()} contract from
                        <span className="mx-1 break-all font-mono text-red-200">{selectedConfig?.sourceLocator.relativePath}</span>.
                        Other services, queries, comments, and extension elements are retained.
                      </p>
                      <div className="mt-4 rounded border border-red-500/20 bg-gray-950/30 p-3 font-mono text-[10px] text-gray-300">
                        {mutationTarget?.kind === 'SERVICE'
                          ? `${mutationTarget.serviceName}.${mutationTarget.methodName}(${mutationTarget.parameterTypes.join(', ')})`
                          : mutationTarget ? `${mutationTarget.entityName}:${mutationTarget.name}` : 'Indexed target unavailable'}
                      </div>
                    </div>
                  </div>
                </div>
              ) : !pendingContract ? (
                <div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(17rem,0.7fr)]">
                  <div className="min-w-0 space-y-3">
                    <div className="grid grid-cols-2 gap-2">
                      {(['SERVICE', 'QUERY'] as const).map((candidate) => (
                        <button
                          type="button"
                          key={candidate}
                          onClick={() => {
                            setContractKind(candidate)
                            setAuthorError(null)
                          }}
                          disabled={authorMode !== 'ADD'}
                          className={`rounded-lg border px-3 py-2.5 text-left ${
                            contractKind === candidate
                              ? 'border-jmix-500/60 bg-jmix-500/10 text-jmix-200'
                              : 'border-surface-border bg-surface-light text-gray-500 hover:text-gray-200'
                          }`}
                        >
                          <div className="text-[10px] font-semibold">{candidate === 'SERVICE' ? 'Service method' : 'JPQL query'}</div>
                          <div className="mt-1 text-[8px] opacity-60">{candidate === 'SERVICE' ? '/rest/services endpoint' : '/rest/queries read model'}</div>
                        </button>
                      ))}
                    </div>

                    <Field label="Target module and configuration">
                      <select
                        value={selectedConfig?.artifactId ?? ''}
                        onChange={(event) => setConfigId(event.target.value)}
                        disabled={authorMode !== 'ADD'}
                        className="field-input font-mono"
                      >
                        {compatibleConfigs.map((config) => (
                          <option key={config.artifactId} value={config.artifactId}>
                            {config.moduleId} · {config.sourceLocator.relativePath}{config.registered ? '' : ' · not registered'}
                          </option>
                        ))}
                      </select>
                    </Field>

                    {contractKind === 'SERVICE' ? (
                      <div className="grid min-w-0 gap-2 sm:grid-cols-2">
                        <Field label="Spring service name">
                          <input value={serviceName} onChange={(event) => setServiceName(event.target.value)} className="field-input font-mono" placeholder="loan_LoanService" />
                        </Field>
                        <Field label="Method name">
                          <input value={methodName} onChange={(event) => setMethodName(event.target.value)} className="field-input font-mono" placeholder="approve" />
                        </Field>
                      </div>
                    ) : (
                      <>
                        <div className="grid min-w-0 gap-2 sm:grid-cols-3">
                          <Field label="Query name">
                            <input value={queryName} onChange={(event) => setQueryName(event.target.value)} className="field-input font-mono" placeholder="loansByState" />
                          </Field>
                          <Field label="Jmix entity name">
                            <input value={entityName} onChange={(event) => setEntityName(event.target.value)} className="field-input font-mono" placeholder="loan_LoanApp" list="jvw-api-entities" />
                          </Field>
                          <Field label="Fetch plan">
                            <input value={fetchPlan} onChange={(event) => setFetchPlan(event.target.value)} className="field-input font-mono" placeholder="_base" />
                          </Field>
                        </div>
                        <Field label="Read-only JPQL">
                          <textarea
                            value={jpql}
                            onChange={(event) => setJpql(event.target.value)}
                            className="field-input min-h-32 resize-y font-mono leading-relaxed"
                            placeholder="select e from loan_LoanApp e where e.processState = :state"
                            spellCheck={false}
                          />
                        </Field>
                      </>
                    )}
                  </div>

                  <div className="min-w-0">
                    <div className="overflow-hidden rounded-lg border border-surface-border bg-surface-light">
                      <div className="flex items-center gap-2 border-b border-surface-border px-3 py-2">
                        <span className="min-w-0 flex-1 text-[9px] font-semibold uppercase tracking-widest text-gray-500">Typed parameters</span>
                        <button
                          type="button"
                          onClick={() => setContractParameters((current) => [...current, { name: '', javaType: '' }])}
                          className="inline-flex items-center gap-1 rounded border border-surface-border px-2 py-1 text-[8px] text-gray-400 hover:text-jmix-300"
                        >
                          <Plus size={9} /> Parameter
                        </button>
                      </div>
                      <div className="space-y-2 p-2">
                        {contractParameters.map((parameter, index) => (
                          <div key={index} className="grid min-w-0 grid-cols-[minmax(0,0.8fr)_minmax(0,1.2fr)_1.75rem] gap-1.5">
                            <input
                              value={parameter.name}
                              onChange={(event) => updateContractParameter(index, { name: event.target.value })}
                              className="field-input min-w-0 font-mono"
                              placeholder="name"
                            />
                            <input
                              value={parameter.javaType}
                              onChange={(event) => updateContractParameter(index, { javaType: event.target.value })}
                              className="field-input min-w-0 font-mono"
                              placeholder={contractKind === 'SERVICE' ? 'type (optional)' : 'java.lang.String'}
                            />
                            <button
                              type="button"
                              onClick={() => setContractParameters((current) => current.filter((_, candidate) => candidate !== index))}
                              className="rounded text-gray-700 hover:bg-red-500/10 hover:text-red-300"
                              aria-label={`Remove parameter ${parameter.name || index + 1}`}
                            >
                              <Trash2 size={11} className="mx-auto" />
                            </button>
                          </div>
                        ))}
                        {contractParameters.length === 0 && (
                          <p className="py-5 text-center text-[9px] text-gray-700">No parameters. Add only the public API inputs.</p>
                        )}
                      </div>
                    </div>
                    <div className="mt-3 rounded-lg border border-amber-500/20 bg-amber-500/5 p-3 text-[9px] leading-relaxed text-amber-100/70">
                      Service endpoints require explicit server-side authorization, validation, transaction, and row-security review. Query placeholders must match declared parameters exactly.
                    </div>
                  </div>
                </div>
              ) : (
                <div className="min-w-0">
                  <div className="flex min-w-0 flex-wrap items-center gap-2">
                    <CheckCircle2 size={14} className="text-emerald-300" />
                    <span className="min-w-0 flex-1 truncate text-xs font-semibold text-gray-100">{pendingContract.preview.label}</span>
                    <span className="rounded border border-emerald-500/25 bg-emerald-500/10 px-2 py-1 text-[8px] text-emerald-200">Revision locked</span>
                  </div>
                  {pendingContract.preview.files.map((file) => (
                    <div key={file.relativePath} className="mt-3 min-w-0 overflow-hidden rounded-lg border border-surface-border">
                      <div className="flex min-w-0 flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light px-3 py-2 font-mono text-[9px]">
                        <span className="rounded bg-amber-500/10 px-1.5 py-0.5 text-amber-200">{file.mode}</span>
                        <span className="min-w-0 flex-1 break-all text-gray-400">{file.relativePath}</span>
                        <span className="text-gray-700">{file.appliedEditCount} surgical edit</span>
                      </div>
                      <div className="grid min-w-0 lg:grid-cols-2">
                        <SourcePreview label="Current source" content={file.originalContent ?? ''} />
                        <SourcePreview label="Reviewed result" content={file.resultContent} changed />
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {authorError && (
                <div className="mt-3 rounded border border-red-500/30 bg-red-500/10 p-2.5 text-[9px] text-red-200">
                  {authorError}
                </div>
              )}
              <datalist id="jvw-api-entities">
                {[...new Set(workspace.operations.flatMap((operation) => operation.entityNames))].sort().map((entity) => <option key={entity} value={entity} />)}
              </datalist>
            </div>

            <div className="flex shrink-0 flex-wrap items-center justify-end gap-2 border-t border-surface-border bg-surface-light px-4 py-3">
              {pendingContract && (
                <button type="button" onClick={() => setPendingContract(null)} disabled={applying} className="rounded border border-surface-border px-3 py-2 text-[9px] text-gray-400 hover:text-gray-200">
                  Back to editor
                </button>
              )}
              <button
                type="button"
                onClick={() => pendingContract ? void applyContract() : void previewContract()}
                disabled={previewing || applying || !selectedConfig}
                className="inline-flex items-center gap-1.5 rounded bg-jmix-500 px-4 py-2 text-[9px] font-semibold text-white hover:bg-jmix-400 disabled:opacity-40"
              >
                {(previewing || applying) && <Loader2 size={11} className="animate-spin" />}
                {pendingContract
                  ? authorMode === 'REMOVE' ? 'Apply reviewed removal' : 'Apply reviewed contract'
                  : authorMode === 'REMOVE' ? 'Preview removal' : 'Preview source change'}
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

function Metric({ label, value, warning = false, danger = false }: { label: string; value: number; warning?: boolean; danger?: boolean }) {
  return (
    <div className={`rounded border px-2.5 py-2 ${danger ? 'border-red-500/30 bg-red-500/5' : warning ? 'border-amber-500/25 bg-amber-500/5' : 'border-surface-border bg-surface/60'}`}>
      <div className="text-[8px] font-semibold uppercase tracking-wider text-gray-600">{label}</div>
      <div className={`mt-0.5 text-sm font-semibold ${danger ? 'text-red-200' : warning ? 'text-amber-200' : 'text-gray-200'}`}>{value}</div>
    </div>
  )
}

function ContractSection({ icon: Icon, title, children }: { icon: typeof Workflow; title: string; children: ReactNode }) {
  return (
    <div className="min-w-0 overflow-hidden rounded-lg border border-surface-border bg-surface-light">
      <div className="flex items-center gap-1.5 border-b border-surface-border px-3 py-2 text-[9px] font-semibold uppercase tracking-widest text-gray-500">
        <Icon size={11} className="text-jmix-400" /> {title}
      </div>
      <div>{children}</div>
    </div>
  )
}

function ContractFact({ label, value, warning = false }: { label: string; value: string; warning?: boolean }) {
  return (
    <div className="flex min-w-0 items-start gap-3 border-b border-surface-border/60 px-3 py-2 last:border-0">
      <span className="w-24 shrink-0 text-[9px] text-gray-600">{label}</span>
      <span className={`min-w-0 flex-1 break-words text-[9px] ${warning ? 'text-amber-300' : 'text-gray-300'}`}>{value}</span>
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block min-w-0 text-[9px] text-gray-600">
      <span className="mb-1 block font-medium uppercase tracking-wider">{label}</span>
      {children}
    </label>
  )
}

function EmptyState({ text, compact = false }: { text: string; compact?: boolean }) {
  return <div className={`flex items-center justify-center p-4 text-center text-[9px] text-gray-700 ${compact ? 'min-h-20' : 'min-h-52'}`}>{text}</div>
}

function SourcePreview({ label, content, changed = false }: { label: string; content: string; changed?: boolean }) {
  return (
    <div className={`min-w-0 border-surface-border lg:border-r lg:last:border-r-0 ${changed ? 'bg-emerald-500/[0.03]' : 'bg-gray-950/30'}`}>
      <div className={`border-b border-surface-border px-3 py-1.5 text-[8px] font-semibold uppercase tracking-wider ${changed ? 'text-emerald-300' : 'text-gray-600'}`}>{label}</div>
      <pre className="max-h-[26rem] min-h-64 overflow-auto whitespace-pre p-3 font-mono text-[9px] leading-relaxed text-gray-400">{content}</pre>
    </div>
  )
}
