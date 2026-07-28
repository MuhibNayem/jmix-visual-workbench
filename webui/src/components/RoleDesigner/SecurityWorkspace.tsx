import { useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle, CheckCircle2, ChevronRight, Database, ExternalLink, Eye, KeyRound,
  Layers3, Loader2, LockKeyhole, Menu as MenuIcon, RefreshCw, Search, Server, Shield,
  ShieldAlert, ShieldCheck, SlidersHorizontal, UserRoundCheck,
} from 'lucide-react'
import { bridge } from '../../bridge'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'
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

function policyAppliesTo(policy: SecurityPolicySnapshot, surface: SecuritySurfaceSnapshot): boolean {
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

function roleAppliesToContext(role: SecurityRoleSnapshot, context: AccessContext): boolean {
  return role.kind === 'ROW_LEVEL' || role.scopes.includes('ALL') || role.scopes.includes(context)
}

export default function SecurityWorkspace() {
  const [workspace, setWorkspace] = useState<SecurityWorkspaceSnapshot | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [context, setContext] = useState<AccessContext>('UI')
  const [surfaceFilter, setSurfaceFilter] = useState<SurfaceFilter>('JOURNEY')
  const [selectedRoleIds, setSelectedRoleIds] = useState<Set<string>>(new Set())
  const [activePane, setActivePane] = useState<'roles' | 'access' | 'findings'>('access')

  const load = async (forceRefresh: boolean = false) => {
    setLoading(true)
    setError(null)
    try {
      const response = await bridge.getSecurityWorkspace(forceRefresh)
      setWorkspace(response)
      setSelectedRoleIds((current) => {
        const available = new Set(response.roles.map((role) => role.id))
        const retained = new Set([...current].filter((id) => available.has(id)))
        if (retained.size > 0) return retained
        const preferred = response.roles.find((role) => role.kind === 'RESOURCE') ?? response.roles[0]
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

  const rolesById = useMemo(
    () => new Map(workspace?.roles.map((role) => [role.id, role]) ?? []),
    [workspace],
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
  const effectiveSeedRoleIds = useMemo(
    () => new Set([...selectedRoleIds].filter((id) => {
      const role = rolesById.get(id)
      return role ? roleAppliesToContext(role, context) : false
    })),
    [context, rolesById, selectedRoleIds],
  )
  const activePolicies = useMemo(
    () => workspace?.policies.filter((policy) => expandedRoleIds.has(policy.roleId)) ?? [],
    [expandedRoleIds, workspace],
  )
  const normalizedQuery = query.trim().toLowerCase()
  const roleMatches = (role: SecurityRoleSnapshot) => !normalizedQuery || [
    role.name, role.code, role.className, role.moduleId, role.kind,
  ].some((value) => value.toLowerCase().includes(normalizedQuery))

  const toggleRole = (role: SecurityRoleSnapshot) => {
    setSelectedRoleIds((current) => {
      const next = new Set(current)
      if (next.has(role.id)) next.delete(role.id)
      else next.add(role.id)
      return next
    })
  }
  const surfaceGranted = (surface: SecuritySurfaceSnapshot) =>
    surface.grantingRoleIds.some((roleId) => effectiveSeedRoleIds.has(roleId))
  const surfaceRestricted = (surface: SecuritySurfaceSnapshot) =>
    surface.restrictingRoleIds.some((roleId) => effectiveSeedRoleIds.has(roleId))
  const relevantFindings = workspace?.findings.filter((finding) => (
    !finding.roleId || selectedRoleIds.has(finding.roleId) || expandedRoleIds.has(finding.roleId)
  )) ?? []
  const visibleSurfaces = workspace?.surfaces.filter((surface) => (
    surfaceFilter !== 'JOURNEY' &&
    surface.kind === surfaceFilter &&
    (!normalizedQuery || [
      surface.displayName, surface.semanticKey, surface.moduleId,
    ].some((value) => value.toLowerCase().includes(normalizedQuery)))
  )) ?? []
  const selectedNames = [...selectedRoleIds].map((id) => rolesById.get(id)?.name).filter(Boolean)

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
    <div className="flex min-h-0 flex-1 flex-col bg-surface">
      <header className="border-b border-surface-border bg-[radial-gradient(circle_at_top_left,rgba(36,129,204,0.13),transparent_42%)] px-5 py-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <ShieldCheck size={18} className="text-jmix-400" />
              <h2 className="text-base font-semibold text-gray-100">Effective Access Explorer</h2>
            </div>
            <p className="mt-1 max-w-3xl text-xs leading-relaxed text-gray-400">
              Select the roles a developer, employee, HR officer, payroll operator, or API client receives.
              The workspace expands inherited roles and shows what becomes visible, editable, restricted, or unsafe.
            </p>
          </div>
          <div className="flex items-center gap-2">
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
            <button type="button" onClick={() => void load(true)} disabled={loading} className={quietButton}>
              <RefreshCw size={12} className={loading ? 'animate-spin' : ''} /> Refresh source
            </button>
          </div>
        </div>

        <div className="mt-4 grid grid-cols-2 gap-2 md:grid-cols-4 xl:grid-cols-8">
          <Metric label="Resource roles" value={workspace.summary.resourceRoleCount} icon={Shield} />
          <Metric label="Row roles" value={workspace.summary.rowRoleCount} icon={SlidersHorizontal} />
          <Metric label="Policies" value={workspace.summary.policyCount} icon={KeyRound} />
          <Metric label="Covered resources" value={workspace.summary.coveredSurfaceCount} icon={CheckCircle2} />
          <Metric label="Menus uncovered" value={workspace.summary.uncoveredMenuCount} icon={MenuIcon} warning />
          <Metric label="Views uncovered" value={workspace.summary.uncoveredViewCount} icon={Eye} warning />
          <Metric label="Errors" value={workspace.summary.errorCount} icon={ShieldAlert} danger />
          <Metric label="Warnings" value={workspace.summary.warningCount} icon={AlertTriangle} warning />
        </div>

        <div className="mt-3 flex items-start gap-2 rounded border border-sky-500/20 bg-sky-500/5 px-3 py-2 text-[10px] leading-relaxed text-sky-100/75">
          <UserRoundCheck size={13} className="mt-0.5 shrink-0 text-sky-300" />
          This is the revision-bound design-time model from source. Runtime-created roles and live user assignments
          will be added to the runtime simulator; they are not guessed here.
        </div>
      </header>

      <div className="flex items-center gap-2 border-b border-surface-border px-4 py-2.5">
        <Search size={13} className="text-gray-600" />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Search roles, modules, resources, or policies"
          className="min-w-0 flex-1 border-0 bg-transparent px-0 py-1 text-xs outline-none"
        />
        <span className="max-w-[40%] truncate text-[10px] text-gray-600">
          Context: {context} · {selectedNames.length ? selectedNames.join(' + ') : 'no roles selected'}
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

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <aside className={`${activePane === 'roles' ? 'block' : 'hidden'} min-h-0 w-full shrink-0 overflow-auto bg-surface-light/30 min-[1200px]:block min-[1200px]:w-64 min-[1200px]:border-r min-[1200px]:border-surface-border`}>
          <SectionHeading icon={UserRoundCheck} title="Role assignment" subtitle="Combine roles to simulate effective access" />
          {(['RESOURCE', 'ROW_LEVEL'] as const).map((kind) => (
            <div key={kind} className="border-b border-surface-border/70 p-2">
              <div className="px-1 pb-1.5 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                {kind === 'RESOURCE' ? 'Permissions — additive' : 'Row restrictions'}
              </div>
              <div className="space-y-1">
                {workspace.roles.filter((role) => role.kind === kind && roleMatches(role)).map((role) => {
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

        <main className={`${activePane === 'access' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col min-[1200px]:flex`}>
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
          <div className="min-h-0 flex-1 overflow-auto p-3">
            {selectedRoleIds.size === 0 ? (
              <EmptyState
                icon={UserRoundCheck}
                title="Select one or more roles"
                text="Start with the role assignment on the left. Resource roles add permissions; row-level roles restrict which entity instances are available."
              />
            ) : surfaceFilter === 'JOURNEY' ? (
              <div className="space-y-2">
                <Guidance
                  title="How to read this"
                  text="A usable menu journey needs both a MenuPolicy and permission to open the connected view. Row policies then filter the data loaded inside that view."
                />
                {workspace.menuRoutes.filter((route) => (
                  !normalizedQuery || [route.menuId, route.viewId ?? ''].some((value) => value.toLowerCase().includes(normalizedQuery))
                )).map((route) => {
                  const menu = workspace.surfaces.find((surface) => surface.artifactId === route.menuArtifactId)
                  const view = workspace.surfaces.find((surface) => surface.artifactId === route.viewArtifactId)
                  const menuGranted = menu ? surfaceGranted(menu) : false
                  const viewGranted = view ? surfaceGranted(view) : false
                  const usable = menuGranted && viewGranted
                  return (
                    <div key={`${route.menuArtifactId}:${route.viewArtifactId}`} className="rounded-lg border border-surface-border bg-surface-light p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <AccessBadge granted={menuGranted} label={menuGranted ? 'Menu visible' : 'Menu denied'} />
                        <ChevronRight size={13} className="text-gray-700" />
                        <AccessBadge granted={viewGranted} label={viewGranted ? 'View opens' : 'View denied'} />
                        <span className={`ml-auto text-[10px] font-semibold ${usable ? 'text-emerald-300' : 'text-red-300'}`}>
                          {usable ? 'Journey available' : 'Journey broken'}
                        </span>
                      </div>
                      <div className="mt-2 flex items-center gap-2">
                        <MenuIcon size={13} className="text-jmix-400" />
                        <span className="font-mono text-[11px] text-gray-200">{route.menuId}</span>
                        <ChevronRight size={12} className="text-gray-700" />
                        <span className="font-mono text-[11px] text-gray-300">{route.viewId ?? 'unresolved view'}</span>
                        <button
                          type="button"
                          onClick={() => void bridge.navigateToSource(route.sourceLocator)}
                          className="ml-auto rounded p-1 text-gray-600 hover:bg-surface-lighter hover:text-jmix-300"
                          title="Open menu source"
                        >
                          <ExternalLink size={12} />
                        </button>
                      </div>
                    </div>
                  )
                })}
                {workspace.menuRoutes.length === 0 && (
                  <EmptyState icon={MenuIcon} title="No menu routes indexed" text="Add or import a Jmix menu XML source to analyze complete UI journeys." />
                )}
              </div>
            ) : (
              <div className="grid grid-cols-1 gap-2 2xl:grid-cols-2">
                {visibleSurfaces.map((surface) => {
                  const granted = surfaceGranted(surface)
                  const restricted = surfaceRestricted(surface)
                  const applicablePolicies = activePolicies.filter((policy) => policyAppliesTo(policy, surface))
                  return (
                    <button
                      type="button"
                      key={surface.artifactId}
                      onClick={() => void bridge.navigateToSource(surface.sourceLocator)}
                      className={`rounded-lg border p-3 text-left transition-colors hover:border-jmix-500/50 ${statusStyle(granted, restricted)}`}
                    >
                      <div className="flex items-start gap-2">
                        <SurfaceIcon kind={surface.kind} />
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-[11px] font-semibold">{surface.displayName}</div>
                          <div className="mt-0.5 truncate font-mono text-[9px] opacity-60">{surface.semanticKey}</div>
                        </div>
                        <span className="rounded bg-black/15 px-1.5 py-0.5 text-[9px] uppercase">
                          {!granted ? 'denied' : restricted ? 'granted + filtered' : 'granted'}
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
                        {applicablePolicies.length === 0 && <span className="text-[9px] opacity-60">No matching source policy</span>}
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

        <aside className={`${activePane === 'findings' ? 'block' : 'hidden'} min-h-0 w-full shrink-0 overflow-auto bg-surface-light/30 min-[1200px]:block min-[1200px]:w-72 min-[1200px]:border-l min-[1200px]:border-surface-border`}>
          <SectionHeading
            icon={ShieldAlert}
            title="Security review"
            subtitle={`${relevantFindings.length} finding(s) for this source model`}
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
              <EmptyState icon={ShieldCheck} title="No indexed findings" text="This does not prove the runtime is secure. Add runtime-role and authenticated-route verification before release." />
            )}
          </div>
        </aside>
      </div>
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

function AccessBadge({ granted, label }: { granted: boolean; label: string }) {
  return (
    <span className={`inline-flex items-center gap-1 rounded border px-2 py-1 text-[9px] font-semibold ${
      granted
        ? 'border-emerald-500/40 bg-emerald-500/10 text-emerald-200'
        : 'border-red-500/30 bg-red-500/10 text-red-200'
    }`}>
      {granted ? <CheckCircle2 size={10} /> : <LockKeyhole size={10} />} {label}
    </span>
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
