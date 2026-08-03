import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, CheckCircle2, ChevronRight, Database, ExternalLink, Eye, KeyRound,
  FilePenLine, Layers3, Loader2, LockKeyhole, Menu as MenuIcon, RefreshCw, Search, Server, Shield,
  ShieldAlert, ShieldCheck, SlidersHorizontal, Trash2, Upload, UserRoundCheck,
} from 'lucide-react'
import { bridge } from '../../bridge'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'
import ExistingRolePolicyEditor from './ExistingRolePolicyEditor'
import type {
  SecurityFindingSnapshot,
  SecurityPolicySnapshot,
  SecurityRoleSnapshot,
  SecuritySurfaceKind,
  SecuritySurfaceSnapshot,
  SecurityWorkspaceSnapshot,
} from '../../types'

type AccessContext = 'UI' | 'API'
type SurfaceFilter = 'JOURNEY' | SecuritySurfaceKind
type EffectiveRole = {
  id: string
  className: string
  name: string
  code: string
  kind: 'RESOURCE' | 'ROW_LEVEL'
  scopes: string[]
  moduleId: string
  policyIds: string[]
  inheritedRoleIds: string[]
  unresolvedBaseRoleCount: number
  origin: 'SOURCE' | 'RUNTIME'
  sourceRole?: SecurityRoleSnapshot
  evidenceSourceId?: string
}

type EffectivePolicy = Omit<SecurityPolicySnapshot, 'sourceLocator'> & {
  sourceLocator?: SecurityPolicySnapshot['sourceLocator']
  origin: 'SOURCE' | 'RUNTIME'
  evidenceSourceId?: string
}

const surfaceFilters: { id: SurfaceFilter; label: string }[] = [
  { id: 'JOURNEY', label: 'Menu journeys' },
  { id: 'VIEW', label: 'Views' },
  { id: 'ENTITY', label: 'Entities' },
  { id: 'ATTRIBUTE', label: 'Attributes' },
  { id: 'REST', label: 'REST & services' },
  { id: 'COMPONENT', label: 'Components' },
]

const quietButton =
  'inline-flex items-center gap-1.5 rounded border border-surface-border bg-surface-lighter px-2.5 py-1.5 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300 disabled:opacity-50'

function severityStyle(severity: SecurityFindingSnapshot['severity']) {
  if (severity === 'ERROR' || severity === 'BLOCKING') {
    return 'border-red-500/40 bg-red-500/10 text-red-100'
  }
  if (severity === 'WARNING') {
    return 'border-amber-500/40 bg-amber-500/10 text-amber-100'
  }
  return 'border-sky-500/30 bg-sky-500/10 text-sky-100'
}

function statusStyle(granted: boolean, restricted: boolean) {
  if (!granted) return 'border-gray-700 bg-gray-900/30 text-gray-500'
  if (restricted) return 'border-amber-500/40 bg-amber-500/10 text-amber-200'
  return 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
}

function policyAppliesTo(policy: EffectivePolicy, surface: SecuritySurfaceSnapshot): boolean {
  if (policy.targetArtifactIds.includes(surface.artifactId)) return true
  if (!policy.wildcard) return false
  return (
    (policy.type === 'EntityPolicy' && surface.kind === 'ENTITY') ||
    (policy.type === 'EntityAttributePolicy' && surface.kind === 'ATTRIBUTE') ||
    (policy.type === 'ViewPolicy' && surface.kind === 'VIEW') ||
    (policy.type === 'MenuPolicy' && surface.kind === 'MENU') ||
    (policy.type === 'UiComponentPolicy' && surface.kind === 'COMPONENT')
  )
}

function roleAppliesToContext(role: EffectiveRole, context: AccessContext): boolean {
  return role.kind === 'ROW_LEVEL' || role.scopes.includes('ALL') || role.scopes.includes(context)
}

function readFileAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(reader.error ?? new Error(`Could not read ${file.name}.`))
    reader.onload = () => {
      const result = String(reader.result ?? '')
      const separator = result.indexOf(',')
      if (separator < 0) reject(new Error(`Could not encode ${file.name}.`))
      else resolve(result.slice(separator + 1))
    }
    reader.readAsDataURL(file)
  })
}

export default function SecurityWorkspace() {
  const [workspace, setWorkspace] = useState<SecurityWorkspaceSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [context, setContext] = useState<AccessContext>('UI')
  const [surfaceFilter, setSurfaceFilter] = useState<SurfaceFilter>('JOURNEY')
  const [selectedRoleIds, setSelectedRoleIds] = useState<Set<string>>(new Set())
  const [selectedPrincipal, setSelectedPrincipal] = useState('')
  const [environmentLabel, setEnvironmentLabel] = useState('Local development')
  const [runtimeBusy, setRuntimeBusy] = useState(false)
  const [runtimeMessage, setRuntimeMessage] = useState<string | null>(null)
  const [activePane, setActivePane] = useState<'roles' | 'access' | 'findings'>('access')
  const [editingRole, setEditingRole] = useState<SecurityRoleSnapshot | null>(null)
  const runtimeFileInput = useRef<HTMLInputElement>(null)

  const load = async (forceRefresh: boolean = false) => {
    setLoading(true)
    setError(null)
    try {
      const response = await bridge.getSecurityWorkspace(forceRefresh)
      setWorkspace(response)
      setSelectedRoleIds((current) => {
        const available = new Set([
          ...response.roles.map((role) => role.id),
          ...response.runtime.roles.map((role) => role.id),
        ])
        const retained = new Set([...current].filter((id) => available.has(id)))
        if (retained.size > 0) return retained
        const preferred = response.roles.find((role) => role.kind === 'RESOURCE') ??
          response.runtime.roles.find((role) => role.kind === 'RESOURCE') ??
          response.roles[0] ??
          response.runtime.roles[0]
        return preferred ? new Set([preferred.id]) : new Set()
      })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Security workspace could not be built.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const allRoles = useMemo<EffectiveRole[]>(() => [
    ...(workspace?.roles.map((role) => ({
      ...role,
      origin: 'SOURCE' as const,
      sourceRole: role,
    })) ?? []),
    ...(workspace?.runtime.roles.map((role) => ({
      id: role.id,
      className: 'Runtime database role',
      name: role.name,
      code: role.code,
      kind: role.kind,
      scopes: role.scopes,
      moduleId: workspace.runtime.sources.find((source) => source.id === role.evidenceSourceId)?.environmentLabel ||
        'runtime',
      policyIds: role.policyIds,
      inheritedRoleIds: role.inheritedRoleIds,
      unresolvedBaseRoleCount: role.unresolvedChildRoleCodes.length,
      origin: 'RUNTIME' as const,
      evidenceSourceId: role.evidenceSourceId,
    })) ?? []),
  ], [workspace])
  const allPolicies = useMemo<EffectivePolicy[]>(() => [
    ...(workspace?.policies.map((policy) => ({ ...policy, origin: 'SOURCE' as const })) ?? []),
    ...(workspace?.runtime.policies.map((policy) => ({ ...policy, origin: 'RUNTIME' as const })) ?? []),
  ], [workspace])
  const rolesById = useMemo(
    () => new Map(allRoles.map((role) => [role.id, role])),
    [allRoles],
  )
  const expandedRoleIds = useMemo(() => {
    const result = new Set<string>()
    const visit = (id: string) => {
      if (result.has(id)) return
      const role = rolesById.get(id)
      if (!role || !roleAppliesToContext(role, context)) return
      result.add(id)
      role.inheritedRoleIds.forEach(visit)
    }
    selectedRoleIds.forEach(visit)
    return result
  }, [context, rolesById, selectedRoleIds])
  const activePolicies = useMemo(
    () => allPolicies.filter((policy) => expandedRoleIds.has(policy.roleId)),
    [allPolicies, expandedRoleIds],
  )
  const normalizedQuery = query.trim().toLowerCase()
  const roleMatches = (role: EffectiveRole) => !normalizedQuery || [
    role.name, role.code, role.className, role.moduleId, role.kind, role.origin,
  ].some((value) => value.toLowerCase().includes(normalizedQuery))

  const toggleRole = (role: EffectiveRole) => {
    setSelectedRoleIds((current) => {
      const next = new Set(current)
      if (next.has(role.id)) next.delete(role.id)
      else next.add(role.id)
      return next
    })
  }
  const surfaceGranted = (surface: SecuritySurfaceSnapshot) =>
    activePolicies.some((policy) => policy.effect === 'GRANT' && policyAppliesTo(policy, surface))
  const surfaceActionGranted = (surface: SecuritySurfaceSnapshot, action: string) =>
    activePolicies.some((policy) => (
      policy.effect === 'GRANT' &&
      policyAppliesTo(policy, surface) &&
      (policy.actions.length === 0 ||
        policy.actions.includes('ALL') ||
        policy.actions.includes(action) ||
        (action === 'VIEW' && policy.actions.includes('MODIFY')))
    ))
  const surfaceRestricted = (surface: SecuritySurfaceSnapshot) =>
    activePolicies.some((policy) => policy.effect === 'RESTRICT' && policyAppliesTo(policy, surface))
  const surfaceHasDenyEvidence = (surface: SecuritySurfaceSnapshot) =>
    activePolicies.some((policy) => policy.effect === 'DENY' && policyAppliesTo(policy, surface))
  const runtimeFindings = workspace?.runtime.issues.map<SecurityFindingSnapshot>((issue) => ({
    code: issue.code,
    severity: issue.severity,
    title: issue.code
      .replace('JVW-RUNTIME-SECURITY-', '')
      .toLowerCase()
      .replace(/-/g, ' ')
      .replace(/^\w/, (value: string) => value.toUpperCase()),
    message: issue.message,
    remediation: issue.severity === 'ERROR'
      ? 'Correct the runtime export or conflicting role definitions, then import fresh evidence.'
      : undefined,
    roleId: issue.roleId,
  })) ?? []
  const relevantFindings = [...(workspace?.findings ?? []), ...runtimeFindings].filter((finding) => (
    !finding.roleId || selectedRoleIds.has(finding.roleId) || expandedRoleIds.has(finding.roleId)
  ))
  const visibleSurfaces = workspace?.surfaces.filter((surface) => (
    surfaceFilter !== 'JOURNEY' &&
    surface.kind === surfaceFilter &&
    (!normalizedQuery || [
      surface.displayName, surface.semanticKey, surface.moduleId,
    ].some((value) => value.toLowerCase().includes(normalizedQuery)))
  )) ?? []
  const selectedNames = [...selectedRoleIds].map((id) => rolesById.get(id)?.name).filter(Boolean)
  const selectedDirectRole = selectedRoleIds.size === 1
    ? rolesById.get([...selectedRoleIds][0]) ?? null
    : null
  const selectedDirectSourceRole = selectedDirectRole?.sourceRole ?? null
  const workspaceSurfaces = workspace?.surfaces ?? []
  const journeys = workspace?.journeys ?? []
  const globallyCoveredSurfaceIds = new Set(
    workspaceSurfaces
      .filter((surface) => allPolicies.some((policy) => policyAppliesTo(policy, surface)))
      .map((surface) => surface.artifactId),
  )
  const uncoveredMenuCount = workspaceSurfaces.filter((surface) => (
    surface.kind === 'MENU' && !globallyCoveredSurfaceIds.has(surface.artifactId)
  )).length
  const uncoveredViewCount = workspaceSurfaces.filter((surface) => (
    surface.kind === 'VIEW' && !globallyCoveredSurfaceIds.has(surface.artifactId)
  )).length

  const selectPrincipal = (username: string) => {
    setSelectedPrincipal(username)
    if (!workspace || !username) return
    const assigned = workspace.runtime.assignments.filter((assignment) => (
      assignment.username === username && assignment.resolution === 'RESOLVED'
    ))
    setSelectedRoleIds(new Set(assigned.flatMap((assignment) => assignment.candidateRoleIds)))
  }

  const importRuntimeEvidence = async (files: FileList | null) => {
    if (!files?.length) return
    setRuntimeBusy(true)
    setRuntimeMessage(null)
    const messages: string[] = []
    let accepted = false
    try {
      for (const file of Array.from(files)) {
        if (file.size > 10 * 1024 * 1024) {
          messages.push(`${file.name}: exceeds the 10 MiB evidence limit.`)
          continue
        }
        const response = await bridge.importRuntimeSecurityEvidence({
          fileName: file.name,
          contentBase64: await readFileAsBase64(file),
          environmentLabel: environmentLabel.trim() || undefined,
        })
        messages.push(response.message)
        accepted ||= response.accepted
      }
      if (accepted) await load(false)
      setRuntimeMessage(messages.join(' '))
    } catch (cause) {
      setRuntimeMessage(cause instanceof Error ? cause.message : 'Runtime security evidence import failed.')
    } finally {
      setRuntimeBusy(false)
      if (runtimeFileInput.current) runtimeFileInput.current.value = ''
    }
  }

  const clearRuntimeEvidence = async () => {
    setRuntimeBusy(true)
    try {
      const response = await bridge.clearRuntimeSecurityEvidence()
      setRuntimeMessage(response.message)
      setSelectedPrincipal('')
      await load(false)
    } catch (cause) {
      setRuntimeMessage(cause instanceof Error ? cause.message : 'Runtime evidence could not be cleared.')
    } finally {
      setRuntimeBusy(false)
    }
  }

  if (loading && !workspace) {
    return (
      <div className="flex flex-1 items-center justify-center gap-2 text-sm text-gray-400">
        <Loader2 size={16} className="animate-spin text-jmix-400" />
        Building the effective security model…
      </div>
    )
  }

  if (error) {
    return (
      <div className="m-5 rounded-lg border border-red-500/40 bg-red-500/10 p-4 text-sm text-red-100">
        <div className="font-semibold">Security workspace unavailable</div>
        <p className="mt-1 text-xs text-red-200/80">{error}</p>
        <button type="button" onClick={() => void load(true)} className={`${quietButton} mt-3`}>Retry</button>
      </div>
    )
  }

  if (!workspace) return null

  return (
    <div className="relative flex min-h-0 min-w-0 max-w-full flex-1 flex-col overflow-x-hidden overflow-y-auto bg-surface lg:overflow-hidden">
      <header className="min-w-0 shrink-0 border-b border-surface-border bg-[radial-gradient(circle_at_top_left,rgba(36,129,204,0.13),transparent_42%)] px-3 py-3 sm:px-5 sm:py-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2">
              <ShieldCheck size={18} className="text-jmix-400" />
              <h2 className="text-base font-semibold text-gray-100">Effective Access Explorer</h2>
            </div>
            <p className="mt-1 max-w-3xl text-xs leading-relaxed text-gray-400">
              Select the roles a developer, employee, HR officer, payroll operator, or API client receives.
              The workspace expands inherited roles and shows what becomes visible, editable, restricted, or unsafe.
            </p>
          </div>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <div className="inline-flex overflow-hidden rounded border border-surface-border" aria-label="Access context">
              {(['UI', 'API'] as const).map((candidate) => (
                <button
                  type="button"
                  key={candidate}
                  onClick={() => setContext(candidate)}
                  className={`px-3 py-1.5 text-[11px] font-semibold ${
                    context === candidate ? 'bg-jmix-500 text-white' : 'bg-surface-lighter text-gray-400'
                  }`}
                >
                  {candidate}
                </button>
              ))}
            </div>
            <button
              type="button"
              onClick={() => selectedDirectSourceRole && setEditingRole(selectedDirectSourceRole)}
              disabled={!selectedDirectSourceRole}
              className={quietButton}
              title={selectedDirectSourceRole
                ? `Manage source policies for ${selectedDirectSourceRole.name}`
                : selectedDirectRole?.origin === 'RUNTIME'
                  ? 'Runtime database roles are evidence-only; edit them in the running Jmix application.'
                  : 'Select exactly one source-defined role to manage its policies'}
            >
              <FilePenLine size={12} /> Manage policies
            </button>
            <button type="button" onClick={() => void load(true)} disabled={loading} className={quietButton}>
              <RefreshCw size={12} className={loading ? 'animate-spin' : ''} /> Refresh source
            </button>
          </div>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-2 md:grid-cols-4 lg:grid-cols-8">
          <Metric label="Resource roles" value={allRoles.filter((role) => role.kind === 'RESOURCE').length} icon={Shield} />
          <Metric label="Row roles" value={allRoles.filter((role) => role.kind === 'ROW_LEVEL').length} icon={SlidersHorizontal} />
          <Metric label="Policies" value={allPolicies.length} icon={KeyRound} />
          <Metric label="Covered resources" value={globallyCoveredSurfaceIds.size} icon={CheckCircle2} />
          <Metric label="Menus uncovered" value={uncoveredMenuCount} icon={MenuIcon} warning />
          <Metric label="Views uncovered" value={uncoveredViewCount} icon={Eye} warning />
          <Metric label="Errors" value={workspace.summary.errorCount + workspace.runtime.summary.errorCount} icon={ShieldAlert} danger />
          <Metric label="Warnings" value={workspace.summary.warningCount + workspace.runtime.summary.warningCount} icon={AlertTriangle} warning />
        </div>

        <div className="mt-3 rounded border border-sky-500/20 bg-sky-500/5 p-2.5 text-[10px] text-sky-100/75">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <UserRoundCheck size={13} className="shrink-0 text-sky-300" />
            <div className="min-w-[12rem] flex-1 leading-relaxed">
              Source policies stay revision-bound. Imported runtime roles and assignments stay read-only and are
              identified by file digest, so the explorer never guesses live database state.
            </div>
            <input
              value={environmentLabel}
              onChange={(event) => setEnvironmentLabel(event.target.value)}
              placeholder="Environment label"
              aria-label="Runtime evidence environment"
              className="min-w-0 basis-40 rounded border border-sky-500/20 bg-black/15 px-2 py-1.5 text-[10px] text-sky-100 outline-none focus:border-sky-400/60"
            />
            <input
              ref={runtimeFileInput}
              type="file"
              accept=".json,.zip,application/json,application/zip"
              multiple
              className="hidden"
              onChange={(event) => void importRuntimeEvidence(event.target.files)}
            />
            <button
              type="button"
              className={quietButton}
              disabled={runtimeBusy}
              onClick={() => runtimeFileInput.current?.click()}
            >
              {runtimeBusy ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
              Import runtime
            </button>
            {workspace.runtime.sources.length > 0 && (
              <button
                type="button"
                className={`${quietButton} border-red-500/20 text-red-200 hover:border-red-400/50 hover:text-red-100`}
                disabled={runtimeBusy}
                onClick={() => void clearRuntimeEvidence()}
              >
                <Trash2 size={12} /> Clear
              </button>
            )}
          </div>
          {(runtimeMessage || workspace.runtime.sources.length > 0) && (
            <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 border-t border-sky-500/10 pt-2 text-[9px]">
              {runtimeMessage && <span className="basis-full break-words text-sky-100/80">{runtimeMessage}</span>}
              {workspace.runtime.sources.map((source) => (
                <span
                  key={source.id}
                  className="max-w-full truncate rounded bg-sky-500/10 px-1.5 py-1 font-mono"
                  title={`${source.fileName} · SHA-256 ${source.sha256}`}
                >
                  {source.environmentLabel || 'Runtime'} · {source.fileName} · {source.roleCount} roles · {source.assignmentCount} assignments
                </span>
              ))}
            </div>
          )}
        </div>
      </header>

      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-b border-surface-border px-4 py-2.5">
        <Search size={13} className="text-gray-600" />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search roles, modules, resources, or policies"
          className="min-w-[10rem] flex-1 border-0 bg-transparent px-0 py-1 text-xs outline-none"
        />
        {workspace.runtime.principals.length > 0 && (
          <select
            value={selectedPrincipal}
            onChange={(event) => selectPrincipal(event.target.value)}
            aria-label="Runtime principal"
            className="min-w-0 max-w-full basis-44 rounded border border-surface-border bg-surface-lighter px-2 py-1.5 text-[10px] text-gray-300"
          >
            <option value="">Choose runtime user…</option>
            {workspace.runtime.principals.map((principal) => (
              <option key={principal} value={principal}>{principal}</option>
            ))}
          </select>
        )}
        <span className="basis-full break-words pl-5 text-[10px] text-gray-600 sm:ml-auto sm:max-w-[40%] sm:basis-auto sm:truncate sm:pl-0">
          {selectedPrincipal ? `User: ${selectedPrincipal} · ` : ''}Context: {context} · {selectedNames.length ? selectedNames.join(' + ') : 'no roles selected'}
        </span>
      </div>

      <ResponsivePaneSwitcher
        value={activePane}
        onChange={setActivePane}
        label="Security workspace panels"
        options={[
          { id: 'roles', label: 'Assigned roles', icon: <UserRoundCheck size={12} />, badge: selectedRoleIds.size },
          { id: 'access', label: 'Effective access', icon: <ShieldCheck size={12} /> },
          { id: 'findings', label: 'Findings', icon: <ShieldAlert size={12} />, badge: relevantFindings.length },
        ]}
      />

      <div className="flex min-h-[32rem] shrink-0 overflow-hidden lg:min-h-0 lg:flex-1">
        <aside className={`${activePane === 'roles' ? 'block' : 'hidden'} min-h-0 w-full shrink-0 overflow-auto bg-surface-light/30 lg:block lg:w-64 lg:border-r lg:border-surface-border`}>
          <SectionHeading icon={UserRoundCheck} title="Role assignment" subtitle="Combine roles to simulate effective access" />
          {(['RESOURCE', 'ROW_LEVEL'] as const).map((kind) => (
            <div key={kind} className="border-b border-surface-border/70 p-2">
              <div className="px-1 pb-1.5 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                {kind === 'RESOURCE' ? 'Permissions — additive' : 'Row restrictions'}
              </div>
              <div className="space-y-1">
                {allRoles.filter((role) => role.kind === kind && roleMatches(role)).map((role) => {
                  const selected = selectedRoleIds.has(role.id)
                  const contextActive = roleAppliesToContext(role, context)
                  return (
                    <button
                      type="button"
                      key={role.id}
                      onClick={() => toggleRole(role)}
                      className={`w-full rounded border px-2.5 py-2 text-left transition-colors ${
                        selected
                          ? contextActive
                            ? 'border-jmix-500/60 bg-jmix-500/10'
                            : 'border-amber-500/40 bg-amber-500/10'
                          : 'border-transparent hover:border-surface-border hover:bg-surface-lighter'
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        {selected
                          ? <CheckCircle2 size={13} className={contextActive ? 'text-jmix-300' : 'text-amber-300'} />
                          : <span className="h-[13px] w-[13px] rounded-full border border-gray-700" />}
                        <span className="min-w-0 flex-1 truncate text-[11px] font-medium text-gray-200">{role.name}</span>
                        <span className={`rounded px-1 py-0.5 text-[8px] ${
                          role.origin === 'RUNTIME'
                            ? 'bg-violet-500/10 text-violet-300'
                            : 'bg-sky-500/10 text-sky-300'
                        }`}>
                          {role.origin === 'RUNTIME' ? 'DB' : 'CODE'}
                        </span>
                        <span className="text-[9px] text-gray-600">{role.scopes.join('+')}</span>
                      </div>
                      <div className="mt-1 truncate pl-5 font-mono text-[9px] text-gray-600">
                        {role.code} · {role.moduleId}
                      </div>
                      {(role.inheritedRoleIds.length > 0 || role.unresolvedBaseRoleCount > 0) && (
                        <div className="mt-1 pl-5 text-[9px] text-sky-300/60">
                          inherits {role.inheritedRoleIds.length} role(s)
                          {role.unresolvedBaseRoleCount > 0 && ` · ${role.unresolvedBaseRoleCount} unresolved`}
                        </div>
                      )}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
        </aside>

        <main className={`${activePane === 'access' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col lg:flex`}>
          <div className="flex flex-wrap gap-1 border-b border-surface-border p-2">
            {surfaceFilters.map((filter) => (
              <button
                type="button"
                key={filter.id}
                onClick={() => setSurfaceFilter(filter.id)}
                className={`rounded px-2.5 py-1.5 text-[10px] font-medium ${
                  surfaceFilter === filter.id
                    ? 'bg-jmix-500 text-white'
                    : 'bg-surface-lighter text-gray-500 hover:text-gray-200'
                }`}
              >
                {filter.label}
              </button>
            ))}
          </div>
          <div className="min-h-0 min-w-0 flex-1 overflow-y-auto overflow-x-hidden p-3">
            {selectedRoleIds.size === 0 ? (
              <EmptyState
                icon={UserRoundCheck}
                title="Select one or more roles"
                text="Start with the role assignment on the left. Resource roles add permissions; row-level roles restrict which entity instances are available."
              />
            ) : surfaceFilter === 'JOURNEY' ? (
              <div className="min-w-0 max-w-full space-y-2">
                <Guidance
                  title="How to read this"
                  text="A usable menu journey needs both a MenuPolicy and permission to open the connected view. Row policies then filter the data loaded inside that view."
                />
                {journeys.filter((journey) => (
                  !normalizedQuery || [
                    ...journey.menuPathIds,
                    journey.viewId ?? '',
                    ...journey.entityArtifactIds.map((id) =>
                      workspace.surfaces.find((surface) => surface.artifactId === id)?.displayName ?? ''),
                  ].some((value) => value.toLowerCase().includes(normalizedQuery))
                )).map((journey) => {
                  const menuPath = journey.menuPathArtifactIds
                    .map((id) => workspace.surfaces.find((surface) => surface.artifactId === id))
                    .filter((surface): surface is SecuritySurfaceSnapshot => Boolean(surface))
                  const view = workspace.surfaces.find((surface) => surface.artifactId === journey.viewArtifactId)
                  const entitySurfaces = journey.entityArtifactIds
                    .map((id) => workspace.surfaces.find((surface) => surface.artifactId === id))
                    .filter((surface): surface is SecuritySurfaceSnapshot => Boolean(surface))
                  const attributeSurfaces = journey.attributeArtifactIds
                    .map((id) => workspace.surfaces.find((surface) => surface.artifactId === id))
                    .filter((surface): surface is SecuritySurfaceSnapshot => Boolean(surface))
                  const componentSurfaces = journey.componentArtifactIds
                    .map((id) => workspace.surfaces.find((surface) => surface.artifactId === id))
                    .filter((surface): surface is SecuritySurfaceSnapshot => Boolean(surface))
                  const menuGranted = menuPath.length > 0 && menuPath.every(surfaceGranted)
                  const viewGranted = view ? surfaceGranted(view) : false
                  const readableEntities = entitySurfaces.filter((surface) => surfaceActionGranted(surface, 'READ'))
                  const dataGranted = entitySurfaces.length === 0 || readableEntities.length === entitySurfaces.length
                  const filteredEntities = entitySurfaces.filter(surfaceRestricted)
                  const visibleAttributes = attributeSurfaces.filter((surface) => surfaceActionGranted(surface, 'VIEW'))
                  const deniedComponents = componentSurfaces.filter((surface) =>
                    surfaceRestricted(surface) || surfaceHasDenyEvidence(surface))
                  const usable = menuGranted && viewGranted && dataGranted
                  return (
                    <div
                      key={`${journey.menuArtifactId}:${journey.viewArtifactId}`}
                      className="min-w-0 max-w-full overflow-hidden rounded-lg border border-surface-border bg-surface-light p-3"
                    >
                      <div className="flex min-w-0 flex-wrap items-center gap-2">
                        <AccessBadge
                          granted={menuGranted}
                          label={menuGranted
                            ? `${menuPath.length} menu level${menuPath.length === 1 ? '' : 's'} visible`
                            : 'Menu path denied'}
                        />
                        <ChevronRight size={13} className="text-gray-700" />
                        <AccessBadge granted={viewGranted} label={viewGranted ? 'View opens' : 'View denied'} />
                        <ChevronRight size={13} className="text-gray-700" />
                        <AccessBadge
                          granted={dataGranted}
                          restricted={filteredEntities.length > 0}
                          label={
                            !dataGranted
                              ? 'Entity READ denied'
                              : filteredEntities.length > 0
                                ? 'Data row-filtered'
                                : entitySurfaces.length > 0
                                  ? 'Data readable'
                                  : 'No entity binding'
                          }
                        />
                        <span className={`ml-auto max-w-full text-[10px] font-semibold ${usable ? 'text-emerald-300' : 'text-red-300'}`}>
                          {usable ? 'Journey available' : 'Journey broken'}
                        </span>
                      </div>
                      <div className="mt-2 flex min-w-0 items-center gap-2">
                        <MenuIcon size={13} className="shrink-0 text-jmix-400" />
                        <span
                          className="min-w-0 max-w-[55%] truncate font-mono text-[11px] text-gray-200"
                          title={journey.menuPathIds.join(' → ')}
                        >
                          {journey.menuPathIds.join(' → ') || journey.menuId}
                        </span>
                        <ChevronRight size={12} className="shrink-0 text-gray-700" />
                        <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-gray-300" title={journey.viewId ?? 'unresolved view'}>
                          {journey.viewId ?? 'unresolved view'}
                        </span>
                        <button
                          type="button"
                          onClick={() => void bridge.navigateToSource(journey.sourceLocator)}
                          className="shrink-0 rounded p-1 text-gray-600 hover:bg-surface-lighter hover:text-jmix-300"
                          title="Open menu source"
                        >
                          <ExternalLink size={12} />
                        </button>
                      </div>
                      <div className="mt-2 grid min-w-0 gap-2 border-t border-surface-border/70 pt-2 md:grid-cols-3">
                        <JourneyDependency
                          label="Entities"
                          value={entitySurfaces.length
                            ? `${readableEntities.length}/${entitySurfaces.length} readable`
                            : 'No binding indexed'}
                          tone={!dataGranted ? 'danger' : filteredEntities.length ? 'warning' : 'ok'}
                          detail={entitySurfaces.map((surface) =>
                            `${surface.displayName}${filteredEntities.includes(surface) ? ' (row-filtered)' : ''}`).join(', ')}
                        />
                        <JourneyDependency
                          label="Attributes"
                          value={attributeSurfaces.length
                            ? `${visibleAttributes.length}/${attributeSurfaces.length} visible`
                            : 'No bound fields'}
                          tone={attributeSurfaces.length > 0 && visibleAttributes.length < attributeSurfaces.length ? 'warning' : 'neutral'}
                          detail={attributeSurfaces.map((surface) => surface.displayName).join(', ')}
                        />
                        <JourneyDependency
                          label="Components"
                          value={componentSurfaces.length
                            ? `${deniedComponents.length} constrained / ${componentSurfaces.length}`
                            : 'No component policies'}
                          tone={deniedComponents.length > 0 ? 'warning' : 'neutral'}
                          detail={deniedComponents.map((surface) => surface.displayName).join(', ')}
                        />
                      </div>
                      {journey.unresolvedDependencyCount > 0 && (
                        <div className="mt-2 text-[9px] text-amber-300/80">
                          {journey.unresolvedDependencyCount} unresolved project reference(s) may affect this result.
                        </div>
                      )}
                    </div>
                  )
                })}
                {journeys.length === 0 && (
                  <EmptyState icon={MenuIcon} title="No menu routes indexed" text="Add or import a Jmix menu XML source to analyze complete UI journeys." />
                )}
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-2 2xl:grid-cols-2">
                {visibleSurfaces.map((surface) => {
                  const granted = surfaceGranted(surface)
                  const restricted = surfaceRestricted(surface)
                  const denyEvidence = surfaceHasDenyEvidence(surface)
                  const applicablePolicies = activePolicies.filter((policy) => policyAppliesTo(policy, surface))
                  return (
                    <button
                      type="button"
                      key={surface.artifactId}
                      onClick={() => void bridge.navigateToSource(surface.sourceLocator)}
                      className={`rounded-lg border p-3 text-left transition-colors hover:border-jmix-500/50 ${statusStyle(granted, restricted || denyEvidence)}`}
                    >
                      <div className="flex items-start gap-2">
                        <SurfaceIcon kind={surface.kind} />
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-[11px] font-semibold">{surface.displayName}</div>
                          <div className="mt-0.5 truncate font-mono text-[9px] opacity-60">{surface.semanticKey}</div>
                        </div>
                        <span className="rounded bg-black/15 px-1.5 py-0.5 text-[9px] uppercase">
                          {!granted
                            ? 'denied'
                            : restricted
                              ? 'granted + filtered'
                              : denyEvidence
                                ? 'grant + deny evidence'
                                : 'granted'}
                        </span>
                      </div>
                      <div className="mt-2 flex flex-wrap gap-1">
                        {applicablePolicies.flatMap((policy) => policy.actions).filter(Boolean).filter((action, index, all) => all.indexOf(action) === index).map((action) => (
                          <span key={action} className="rounded border border-current/20 px-1.5 py-0.5 text-[9px]">{action}</span>
                        ))}
                        {applicablePolicies.filter((policy) => policy.condition).map((policy) => (
                          <span key={policy.id} className="max-w-full truncate rounded border border-current/20 px-1.5 py-0.5 text-[9px]" title={policy.condition}>
                            {policy.condition}
                          </span>
                        ))}
                        {applicablePolicies.length === 0 && <span className="text-[9px] opacity-60">No matching effective policy</span>}
                      </div>
                      <div className="mt-2 text-[9px] opacity-50">{surface.moduleId}</div>
                    </button>
                  )
                })}
                {visibleSurfaces.length === 0 && (
                  <EmptyState icon={Layers3} title="No matching security surfaces" text="Change the filter or search, then refresh after the project import completes." />
                )}
              </div>
            )}
          </div>
        </main>

        <aside className={`${activePane === 'findings' ? 'block' : 'hidden'} min-h-0 w-full shrink-0 overflow-auto bg-surface-light/30 lg:block lg:w-72 lg:border-l lg:border-surface-border`}>
          <SectionHeading
            icon={ShieldAlert}
            title="Security review"
            subtitle={`${relevantFindings.length} finding(s) across source and imported runtime evidence`}
          />
          <div className="space-y-2 p-2">
            {relevantFindings.map((finding, index) => (
              <button
                type="button"
                key={`${finding.code}:${finding.artifactId ?? ''}:${index}`}
                onClick={() => finding.sourceLocator && void bridge.navigateToSource(finding.sourceLocator)}
                disabled={!finding.sourceLocator}
                className={`w-full rounded-lg border p-2.5 text-left ${severityStyle(finding.severity)}`}
              >
                <div className="flex items-start gap-2">
                  {finding.severity === 'ERROR' || finding.severity === 'BLOCKING'
                    ? <ShieldAlert size={13} className="mt-0.5 shrink-0" />
                    : <AlertTriangle size={13} className="mt-0.5 shrink-0" />}
                  <div className="min-w-0">
                    <div className="text-[10px] font-semibold">{finding.title}</div>
                    <p className="mt-1 text-[9px] leading-relaxed opacity-80">{finding.message}</p>
                    {finding.remediation && (
                      <p className="mt-1.5 border-t border-current/10 pt-1.5 text-[9px] leading-relaxed opacity-65">
                        Fix: {finding.remediation}
                      </p>
                    )}
                    <div className="mt-1.5 font-mono text-[8px] opacity-40">{finding.code}</div>
                  </div>
                </div>
              </button>
            ))}
            {relevantFindings.length === 0 && (
              <EmptyState
                icon={ShieldCheck}
                title="No indexed findings"
                text={workspace.runtime.sources.length
                  ? 'No source or imported runtime-evidence findings match this role selection.'
                  : 'Import current runtime roles and assignments before treating this source-only result as release evidence.'}
              />
            )}
          </div>
        </aside>
      </div>

      {editingRole && (
        <ExistingRolePolicyEditor
          role={editingRole}
          onClose={() => setEditingRole(null)}
          onApplied={() => {
            setEditingRole(null)
            void load(true)
          }}
        />
      )}
    </div>
  )
}

function Metric({ label, value, icon: Icon, warning = false, danger = false }: {
  label: string
  value: number
  icon: typeof Shield
  warning?: boolean
  danger?: boolean
}) {
  return (
    <div className={`rounded-lg border px-2.5 py-2 ${
      danger ? 'border-red-500/30 bg-red-500/5' : warning ? 'border-amber-500/25 bg-amber-500/5' : 'border-surface-border bg-surface-light/70'
    }`}>
      <div className="flex items-center gap-1.5">
        <Icon size={11} className={danger ? 'text-red-300' : warning ? 'text-amber-300' : 'text-jmix-400'} />
        <span className="truncate text-[9px] uppercase tracking-wider text-gray-600">{label}</span>
      </div>
      <div className={`mt-1 text-base font-semibold ${danger ? 'text-red-200' : warning ? 'text-amber-200' : 'text-gray-200'}`}>
        {value.toLocaleString()}
      </div>
    </div>
  )
}

function SectionHeading({ icon: Icon, title, subtitle }: {
  icon: typeof Shield
  title: string
  subtitle: string
}) {
  return (
    <div className="sticky top-0 z-10 border-b border-surface-border bg-surface-light/95 px-3 py-2.5 backdrop-blur">
      <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-widest text-gray-400">
        <Icon size={12} className="text-jmix-400" /> {title}
      </div>
      <div className="mt-0.5 text-[9px] leading-relaxed text-gray-600">{subtitle}</div>
    </div>
  )
}

function AccessBadge({ granted, restricted = false, label }: {
  granted: boolean
  restricted?: boolean
  label: string
}) {
  return (
    <span className={`inline-flex items-center gap-1 rounded border px-2 py-1 text-[9px] font-semibold ${
      !granted
        ? 'border-red-500/30 bg-red-500/10 text-red-200'
        : restricted
          ? 'border-amber-500/40 bg-amber-500/10 text-amber-200'
          : 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
    }`}>
      {!granted
        ? <LockKeyhole size={10} />
        : restricted
          ? <SlidersHorizontal size={10} />
          : <CheckCircle2 size={10} />} {label}
    </span>
  )
}

function JourneyDependency({ label, value, detail, tone }: {
  label: string
  value: string
  detail: string
  tone: 'ok' | 'warning' | 'danger' | 'neutral'
}) {
  const style = tone === 'danger'
    ? 'border-red-500/25 bg-red-500/5 text-red-200'
    : tone === 'warning'
      ? 'border-amber-500/25 bg-amber-500/5 text-amber-200'
      : tone === 'ok'
        ? 'border-emerald-500/20 bg-emerald-500/5 text-emerald-200'
        : 'border-surface-border bg-gray-900/20 text-gray-300'
  return (
    <div className={`min-w-0 rounded border px-2.5 py-2 ${style}`}>
      <div className="text-[8px] font-semibold uppercase tracking-widest opacity-60">{label}</div>
      <div className="mt-0.5 truncate text-[10px] font-medium" title={value}>{value}</div>
      {detail && <div className="mt-1 truncate text-[8px] opacity-50" title={detail}>{detail}</div>}
    </div>
  )
}

function SurfaceIcon({ kind }: { kind: SecuritySurfaceKind }) {
  const classes = 'mt-0.5 shrink-0'
  switch (kind) {
    case 'MENU': return <MenuIcon size={13} className={classes} />
    case 'VIEW': return <Eye size={13} className={classes} />
    case 'ENTITY':
    case 'ATTRIBUTE': return <Database size={13} className={classes} />
    case 'REST': return <Server size={13} className={classes} />
    default: return <Layers3 size={13} className={classes} />
  }
}

function Guidance({ title, text }: { title: string; text: string }) {
  return (
    <div className="rounded-lg border border-sky-500/20 bg-sky-500/5 p-3">
      <div className="flex items-center gap-1.5 text-[10px] font-semibold text-sky-200">
        <ShieldCheck size={12} /> {title}
      </div>
      <p className="mt-1 text-[9px] leading-relaxed text-sky-100/60">{text}</p>
    </div>
  )
}

function EmptyState({ icon: Icon, title, text }: {
  icon: typeof Shield
  title: string
  text: string
}) {
  return (
    <div className="col-span-full flex min-h-36 flex-col items-center justify-center rounded-lg border border-dashed border-surface-border p-6 text-center">
      <Icon size={20} className="text-gray-700" />
      <div className="mt-2 text-[11px] font-medium text-gray-400">{title}</div>
      <p className="mt-1 max-w-md text-[9px] leading-relaxed text-gray-600">{text}</p>
    </div>
  )
}
