import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  AlertTriangle, Code2, Database, Eye, FolderTree, KeyRound, Loader2,
  Menu as MenuIcon, Monitor, Plus, Shield, ShieldCheck, SlidersHorizontal,
  Trash2, X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  RoleModel,
  SecurityRoleCreateRequest,
  SecurityRoleDestinationSnapshot,
  WorkspaceChangePreviewResponse,
} from '../../types'
import SecurityWorkspace from './SecurityWorkspace'

// ─── Constants ───────────────────────────────────────────────────────────────

const CRUD_ACTIONS = ['create', 'read', 'update', 'delete'] as const
const RLP_TYPES = ['jpql', 'predicate'] as const
const RLP_ACTIONS = ['create', 'read', 'update', 'delete'] as const

const slugify = (s: string) =>
  s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
const classNameFrom = (s: string) => {
  const value = s.trim().split(/[^A-Za-z0-9_$]+/).filter(Boolean)
    .map((part) => `${part.charAt(0).toUpperCase()}${part.slice(1)}`)
    .join('')
  return value ? `${value.endsWith('Role') ? value : `${value}Role`}` : ''
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const btnPrimary =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const btnGhost =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300'
const btnIcon =
  'rounded p-1 text-gray-500 transition-colors hover:bg-surface-lighter hover:text-gray-200'

function PolicyCard({ icon: Icon, title, count, onAdd, addLabel, children }: {
  icon: LucideIcon
  title: string
  count: number
  onAdd: () => void
  addLabel: string
  children: ReactNode
}) {
  return (
    <section className="flex min-h-[180px] flex-col overflow-hidden rounded-lg border border-surface-border bg-surface-light">
      <header className="flex items-center justify-between border-b border-surface-border px-3 py-2">
        <h3 className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-widest text-gray-400">
          <Icon size={13} className="text-jmix-400" />
          {title}
          <span className="rounded-full bg-surface-lighter px-1.5 py-px text-[10px] font-medium normal-case tracking-normal text-gray-500">
            {count}
          </span>
        </h3>
        <button onClick={onAdd} className={btnGhost}>
          <Plus size={12} className="text-jmix-400" /> {addLabel}
        </button>
      </header>
      <div className="flex-1 space-y-2 overflow-y-auto p-3">{children}</div>
    </section>
  )
}

function EmptyHint({ children }: { children: ReactNode }) {
  return <p className="px-1 py-3 text-center text-[11px] text-gray-600">{children}</p>
}

// ─── Main component ──────────────────────────────────────────────────────────

function NewRoleDesigner() {
  // Selective subscriptions avoid re-rendering this large component on every
  // store change (e.g. each tab switch), which caused switching hangs.
  const addToast = useStore((state) => state.addToast)
  const isGenerating = useStore((state) => state.isGenerating)
  const setIsGenerating = useStore((state) => state.setIsGenerating)
  const setLastResult = useStore((state) => state.setLastResult)

  const [role, setRole] = useState<RoleModel>({
    className: '',
    packageName: '',
    name: '',
    code: '',
    description: '',
    scope: 'resource',
    securityScopes: ['UI'],
    entityPolicies: [],
    entityAttributePolicies: [],
    menuPolicies: [],
    viewPolicies: [],
    specificPolicies: [],
    rowLevelPolicies: [],
    baseRoleClasses: [],
    allowWildcardPolicies: false,
  })
  const [pending, setPending] = useState<{
    change: SecurityRoleCreateRequest
    preview: WorkspaceChangePreviewResponse
  } | null>(null)
  const [applying, setApplying] = useState(false)
  const [destinations, setDestinations] = useState<SecurityRoleDestinationSnapshot[]>([])
  const [selectedDestinationId, setSelectedDestinationId] = useState('')
  const [destinationsLoading, setDestinationsLoading] = useState(true)
  const [destinationError, setDestinationError] = useState<string | null>(null)

  // Auto-derive the role code from the name until the user edits it manually
  const codeDirty = useRef(false)
  const classNameDirty = useRef(false)

  const isRowLevel = role.scope === 'rowLevel'
  const selectedDestination = destinations.find((destination) => destination.id === selectedDestinationId)
  const hasWildcardPolicy = role.entityAttributePolicies.some((policy) => policy.attributes.includes('*')) ||
    role.menuPolicies.some((policy) => policy.menuId.trim() === '*') ||
    role.viewPolicies.some((policy) => policy.viewId.trim() === '*') ||
    role.specificPolicies.some((policy) => policy.permission.trim() === '*')

  useEffect(() => {
    let active = true
    setDestinationsLoading(true)
    bridge.getSecurityRoleDestinations()
      .then((response) => {
        if (!active) return
        setDestinations(response.destinations)
        setSelectedDestinationId((current) => (
          response.destinations.some((destination) => destination.id === current)
            ? current
            : response.defaultDestinationId ?? response.destinations[0]?.id ?? ''
        ))
        setDestinationError(response.issues[0]?.message ?? null)
      })
      .catch(() => {
        if (active) setDestinationError('Production Java source roots could not be loaded.')
      })
      .finally(() => {
        if (active) setDestinationsLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    setPending(null)
  }, [role, selectedDestinationId])

  // ── Entity policy CRUD chips ───────────────────────────────────────────────

  const toggleAction = (index: number, action: string) => {
    setRole((r) => ({
      ...r,
      entityPolicies: r.entityPolicies.map((p, i) => {
        if (i !== index) return p
        if (action === 'all') {
          const turningOn = !p.allActions
          return { ...p, allActions: turningOn, actions: turningOn ? [...CRUD_ACTIONS] : [] }
        }
        const has = p.actions.includes(action)
        const actions = has ? p.actions.filter((a) => a !== action) : [...p.actions, action]
        return { ...p, actions, allActions: actions.length === CRUD_ACTIONS.length }
      }),
    }))
  }

  const chipClass = (on: boolean, accent: 'blue' | 'amber' = 'blue') =>
    `rounded border px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide transition-all ${
      on
        ? accent === 'amber'
          ? 'border-amber-500 bg-amber-500/15 text-amber-300'
          : 'border-jmix-500 bg-jmix-500/20 text-jmix-300'
        : 'border-surface-border bg-surface-lighter text-gray-500 hover:border-gray-500 hover:text-gray-300'
    }`

  // ── Preview and apply ──────────────────────────────────────────────────────

  const handleGenerate = async () => {
    if (!role.className.trim() || !role.name.trim() || !role.code.trim()) {
      addToast('Java class, role name, and role code are required', 'error')
      return
    }
    if (role.entityPolicies.some((p) => !p.entityClass.trim())) {
      addToast('Every entity policy needs an entity class', 'error')
      return
    }

    setIsGenerating(true)
    try {
      const change = {
        role,
        destinationId: selectedDestinationId || undefined,
      }
      const preview = await bridge.previewSecurityRoleCreate(change)
      if (!preview.accepted || !preview.planDigest || preview.files.length === 0) {
        addToast(preview.issues[0]?.message ?? 'Role creation was rejected', 'error')
        return
      }
      setPending({ change, preview })
    } catch {
      addToast('Role preview failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const applyPending = async () => {
    if (!pending?.preview.planDigest) return
    setApplying(true)
    try {
      const result = await bridge.applySecurityRoleCreate(
        pending.change,
        pending.preview.planDigest,
      )
      setLastResult({
        success: result.success,
        filesWritten: result.filesChanged,
        errors: result.issues.map((issue) => `${issue.code}: ${issue.message}`),
      })
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'Role creation failed', 'error')
        return
      }
      addToast(`Role "${role.code}" created after approved preview`, 'success')
      setPending(null)
    } catch {
      addToast('Role creation failed — bridge unavailable', 'error')
    } finally {
      setApplying(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="relative flex h-full min-w-0 max-w-full flex-1 basis-0 flex-col overflow-hidden bg-surface [color-scheme:dark]">
      {/* Header */}
      <header className="min-w-0 shrink-0 space-y-2.5 border-b border-surface-border bg-surface-light/60 px-3 py-3 sm:px-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2">
            <Shield size={15} className="text-jmix-400" />
            <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">Role Designer</h2>
          </div>

          {/* Scope segmented control */}
          <div className="inline-flex overflow-hidden rounded border border-surface-border" role="radiogroup" aria-label="Role scope">
            {(['resource', 'rowLevel'] as const).map((s) => (
              <button
                key={s}
                role="radio"
                aria-checked={role.scope === s}
                onClick={() => setRole((r) => ({
                  ...r,
                  scope: s,
                  ...(s === 'resource'
                    ? { rowLevelPolicies: [] }
                    : {
                        entityPolicies: [],
                        entityAttributePolicies: [],
                        menuPolicies: [],
                        viewPolicies: [],
                        specificPolicies: [],
                      }),
                }))}
                className={`px-2.5 py-1 text-[11px] font-medium transition-colors ${
                  role.scope === s
                    ? 'bg-jmix-500 text-white'
                    : 'bg-surface-lighter text-gray-400 hover:text-gray-200'
                }`}
              >
                {s === 'resource' ? 'Resource' : 'Row-level'}
              </button>
            ))}
          </div>

          <div className="ml-auto">
            <button
              onClick={handleGenerate}
              disabled={isGenerating || destinationsLoading || !selectedDestinationId}
              className={btnPrimary}
            >
              {isGenerating ? <Loader2 size={13} className="animate-spin" /> : <Eye size={13} />}
              Review role source
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 min-[1400px]:grid-cols-4">
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Target Module *</span>
            <span className="relative block">
              <FolderTree size={12} className="pointer-events-none absolute left-2 top-1/2 z-10 -translate-y-1/2 text-gray-500" />
              <select
                value={selectedDestinationId}
                onChange={(event) => setSelectedDestinationId(event.target.value)}
                disabled={destinationsLoading || destinations.length === 0}
                className="w-full py-1 pl-7 text-xs"
                aria-label="Target module and Java source root"
              >
                {destinationsLoading && <option value="">Loading project modules…</option>}
                {!destinationsLoading && destinations.length === 0 && <option value="">No Java source root</option>}
                {destinations.map((destination) => (
                  <option key={destination.id} value={destination.id}>
                    {destination.moduleId} — {destination.sourceRoot}
                  </option>
                ))}
              </select>
            </span>
          </label>
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Java Interface *</span>
            <input
              value={role.className}
              onChange={(e) => {
                classNameDirty.current = true
                setRole((r) => ({ ...r, className: e.target.value.replace(/[^A-Za-z0-9_$]/g, '') }))
              }}
              className="w-full py-1 font-mono text-xs"
              placeholder="OrderManagerRole"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Display Name *</span>
            <input
              value={role.name}
              onChange={(e) => {
                const name = e.target.value
                setRole((r) => ({
                  ...r,
                  name,
                  code: codeDirty.current ? r.code : slugify(name),
                  className: classNameDirty.current ? r.className : classNameFrom(name),
                }))
              }}
              className="w-full py-1 text-xs"
              placeholder="Order Manager"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Role Code *</span>
            <input
              value={role.code}
              onChange={(e) => {
                codeDirty.current = true
                setRole((r) => ({ ...r, code: e.target.value.replace(/\s+/g, '-') }))
              }}
              className="w-full py-1 font-mono text-xs"
              placeholder="order-manager"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Package</span>
            <input
              value={role.packageName ?? ''}
              onChange={(e) => setRole((r) => ({ ...r, packageName: e.target.value }))}
              className="w-full py-1 font-mono text-xs"
              placeholder={selectedDestination?.defaultPackage ?? 'Module security package'}
            />
          </label>
          <label className="block sm:col-span-2">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Description</span>
            <input
              value={role.description ?? ''}
              onChange={(e) => setRole((r) => ({ ...r, description: e.target.value }))}
              className="w-full py-1 text-xs"
              placeholder="Full access to order entities and their screens"
            />
          </label>
          {role.scope === 'resource' && (
            <div>
              <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Applies Through</span>
              <div className="flex gap-1">
                {(['UI', 'API'] as const).map((securityScope) => {
                  const selected = role.securityScopes.includes(securityScope)
                  return (
                    <button
                      type="button"
                      key={securityScope}
                      aria-pressed={selected}
                      onClick={() => setRole((current) => ({
                        ...current,
                        securityScopes: selected
                          ? current.securityScopes.filter((value) => value !== securityScope)
                          : [...current.securityScopes, securityScope],
                      }))}
                      className={chipClass(selected)}
                    >
                      {securityScope}
                    </button>
                  )
                })}
              </div>
            </div>
          )}
          {destinationError && (
            <div className="flex items-start gap-2 rounded border border-amber-500/30 bg-amber-500/10 px-2.5 py-2 text-[10px] leading-relaxed text-amber-100 sm:col-span-2">
              <AlertTriangle size={12} className="mt-0.5 shrink-0 text-amber-300" />
              {destinationError}
            </div>
          )}
          {hasWildcardPolicy && role.scope === 'resource' && (
            <label className="flex items-start gap-2 rounded border border-amber-500/30 bg-amber-500/10 px-2.5 py-2 text-[10px] leading-relaxed text-amber-100 sm:col-span-2">
              <input
                type="checkbox"
                checked={role.allowWildcardPolicies}
                onChange={(event) => setRole((current) => ({
                  ...current,
                  allowWildcardPolicies: event.target.checked,
                }))}
                className="mt-0.5 h-3.5 w-3.5 shrink-0"
              />
              <span>
                I understand that <code>*</code> grants broad access and have reviewed its effect for this role.
              </span>
            </label>
          )}
        </div>
      </header>

      {/* Policy cards */}
      <main className="grid min-h-0 min-w-0 flex-1 grid-cols-1 gap-3 overflow-y-auto overflow-x-hidden p-3 sm:gap-4 sm:p-4 min-[1600px]:grid-cols-2">
        {!isRowLevel && (
          <>
            <PolicyCard
              icon={Database}
              title="Entity CRUD"
              count={role.entityPolicies.length}
              addLabel="Entity"
              onAdd={() => setRole((current) => ({
                ...current,
                entityPolicies: [...current.entityPolicies, {
                  entityClass: '',
                  actions: ['read'],
                  allActions: false,
                }],
              }))}
            >
              {role.entityPolicies.length === 0 && <EmptyHint>Grant only the CRUD operations this job needs.</EmptyHint>}
              {role.entityPolicies.map((policy, index) => (
                <div key={index} className="flex flex-wrap items-center gap-2 rounded border border-surface-border bg-surface p-2">
                  <Database size={13} className="shrink-0 text-gray-500" />
                  <input
                    value={policy.entityClass}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      entityPolicies: current.entityPolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? { ...candidate, entityClass: event.target.value } : candidate),
                    }))}
                    className="min-w-[12rem] flex-1 py-1 font-mono text-xs"
                    placeholder="com.example.entity.Order"
                    aria-label="Entity class"
                  />
                  <div className="flex flex-wrap items-center gap-1">
                    {CRUD_ACTIONS.map((action) => (
                      <button
                        type="button"
                        key={action}
                        onClick={() => toggleAction(index, action)}
                        className={chipClass(policy.actions.includes(action))}
                        title={action}
                        aria-pressed={policy.actions.includes(action)}
                      >
                        {action[0].toUpperCase()}
                      </button>
                    ))}
                    <button
                      type="button"
                      onClick={() => toggleAction(index, 'all')}
                      className={chipClass(policy.allActions, 'amber')}
                      aria-pressed={policy.allActions}
                    >
                      All
                    </button>
                  </div>
                  <button
                    type="button"
                    onClick={() => setRole((current) => ({
                      ...current,
                      entityPolicies: current.entityPolicies.filter((_, candidateIndex) => candidateIndex !== index),
                    }))}
                    className={btnIcon}
                    aria-label="Remove entity policy"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
            </PolicyCard>

            <PolicyCard
              icon={Eye}
              title="Entity Attributes"
              count={role.entityAttributePolicies.length}
              addLabel="Attributes"
              onAdd={() => setRole((current) => ({
                ...current,
                entityAttributePolicies: [...current.entityAttributePolicies, {
                  entityClass: '',
                  attributes: ['*'],
                  action: 'view',
                }],
              }))}
            >
              {role.entityAttributePolicies.length === 0 && <EmptyHint>Control which fields are visible or editable.</EmptyHint>}
              {role.entityAttributePolicies.map((policy, index) => (
                <div key={index} className="grid gap-2 rounded border border-surface-border bg-surface p-2 sm:grid-cols-[minmax(10rem,1fr)_minmax(8rem,1fr)_7rem_auto]">
                  <input
                    value={policy.entityClass}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      entityAttributePolicies: current.entityAttributePolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? { ...candidate, entityClass: event.target.value } : candidate),
                    }))}
                    className="py-1 font-mono text-xs"
                    placeholder="Entity class"
                    aria-label="Attribute policy entity class"
                  />
                  <input
                    value={policy.attributes.join(', ')}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      entityAttributePolicies: current.entityAttributePolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index
                          ? { ...candidate, attributes: event.target.value.split(',').map((value) => value.trim()).filter(Boolean) }
                          : candidate),
                    }))}
                    className="py-1 font-mono text-xs"
                    placeholder="name, status"
                    aria-label="Permitted attributes"
                  />
                  <select
                    value={policy.action}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      entityAttributePolicies: current.entityAttributePolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? { ...candidate, action: event.target.value as 'view' | 'modify' } : candidate),
                    }))}
                    className="py-1 text-xs"
                    aria-label="Attribute access"
                  >
                    <option value="view">View</option>
                    <option value="modify">Modify</option>
                  </select>
                  <button
                    type="button"
                    onClick={() => setRole((current) => ({
                      ...current,
                      entityAttributePolicies: current.entityAttributePolicies.filter((_, candidateIndex) => candidateIndex !== index),
                    }))}
                    className={btnIcon}
                    aria-label="Remove attribute policy"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
            </PolicyCard>

            <PolicyCard
              icon={Monitor}
              title="UI Access"
              count={role.menuPolicies.length + role.viewPolicies.length}
              addLabel="Journey"
              onAdd={() => setRole((current) => ({
                ...current,
                menuPolicies: [...current.menuPolicies, { menuId: '' }],
                viewPolicies: [...current.viewPolicies, { viewId: '' }],
              }))}
            >
              {role.menuPolicies.length === 0 && role.viewPolicies.length === 0 && (
                <EmptyHint>Add matching menu and view grants so navigation journeys work.</EmptyHint>
              )}
              {Array.from({ length: Math.max(role.menuPolicies.length, role.viewPolicies.length) }).map((_, index) => (
                <div key={index} className="grid gap-2 rounded border border-surface-border bg-surface p-2 sm:grid-cols-[1fr_1fr_auto]">
                  <label className="flex min-w-0 items-center gap-2">
                    <MenuIcon size={12} className="shrink-0 text-gray-500" />
                    <input
                      value={role.menuPolicies[index]?.menuId ?? ''}
                      onChange={(event) => setRole((current) => ({
                        ...current,
                        menuPolicies: current.menuPolicies.map((candidate, candidateIndex) =>
                          candidateIndex === index ? { ...candidate, menuId: event.target.value } : candidate),
                      }))}
                      className="min-w-0 flex-1 py-1 font-mono text-xs"
                      placeholder="Menu id"
                    />
                  </label>
                  <label className="flex min-w-0 items-center gap-2">
                    <Eye size={12} className="shrink-0 text-gray-500" />
                    <input
                      value={role.viewPolicies[index]?.viewId ?? ''}
                      onChange={(event) => setRole((current) => ({
                        ...current,
                        viewPolicies: current.viewPolicies.map((candidate, candidateIndex) =>
                          candidateIndex === index ? { ...candidate, viewId: event.target.value } : candidate),
                      }))}
                      className="min-w-0 flex-1 py-1 font-mono text-xs"
                      placeholder="View id"
                    />
                  </label>
                  <button
                    type="button"
                    onClick={() => setRole((current) => ({
                      ...current,
                      menuPolicies: current.menuPolicies.filter((_, candidateIndex) => candidateIndex !== index),
                      viewPolicies: current.viewPolicies.filter((_, candidateIndex) => candidateIndex !== index),
                    }))}
                    className={btnIcon}
                    aria-label="Remove UI journey policy"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
            </PolicyCard>

            <PolicyCard
              icon={KeyRound}
              title="Specific Permissions"
              count={role.specificPolicies.length}
              addLabel="Permission"
              onAdd={() => setRole((current) => ({
                ...current,
                specificPolicies: [...current.specificPolicies, { permission: '' }],
              }))}
            >
              {role.specificPolicies.length === 0 && <EmptyHint>Add named permissions such as rest.enabled only when required.</EmptyHint>}
              {role.specificPolicies.map((policy, index) => (
                <div key={index} className="flex items-center gap-2 rounded border border-surface-border bg-surface p-2">
                  <KeyRound size={12} className="text-gray-500" />
                  <input
                    value={policy.permission}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      specificPolicies: current.specificPolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? { ...candidate, permission: event.target.value } : candidate),
                    }))}
                    className="min-w-0 flex-1 py-1 font-mono text-xs"
                    placeholder="rest.enabled"
                  />
                  <button
                    type="button"
                    onClick={() => setRole((current) => ({
                      ...current,
                      specificPolicies: current.specificPolicies.filter((_, candidateIndex) => candidateIndex !== index),
                    }))}
                    className={btnIcon}
                    aria-label="Remove specific permission"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
            </PolicyCard>
          </>
        )}

        {isRowLevel && (
          <PolicyCard
            icon={SlidersHorizontal}
            title="Row-Level Policies"
            count={role.rowLevelPolicies.length}
            addLabel="Rule"
            onAdd={() => setRole((current) => ({
              ...current,
              rowLevelPolicies: [...current.rowLevelPolicies, {
                entityClass: '',
                type: 'jpql',
                action: 'read',
                actions: ['read'],
                whereClause: '',
                joinClause: '',
              }],
            }))}
          >
            {role.rowLevelPolicies.length === 0 && (
              <EmptyHint>Use a bounded JPQL policy for root loads; predicates require an explicit Java expression.</EmptyHint>
            )}
            {role.rowLevelPolicies.map((policy, index) => (
              <div key={index} className="space-y-2 rounded border border-surface-border bg-surface p-2">
                <div className="flex flex-wrap items-center gap-2">
                  <SlidersHorizontal size={13} className="shrink-0 text-gray-500" />
                  <input
                    value={policy.entityClass}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? { ...candidate, entityClass: event.target.value } : candidate),
                    }))}
                    className="min-w-[12rem] flex-1 py-1 font-mono text-xs"
                    placeholder="com.example.entity.Order"
                    aria-label="Row policy entity class"
                  />
                  <select
                    value={policy.type}
                    onChange={(event) => setRole((current) => ({
                      ...current,
                      rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                        candidateIndex === index ? {
                          ...candidate,
                          type: event.target.value as 'jpql' | 'predicate',
                        } : candidate),
                    }))}
                    className="w-28 py-1 text-xs"
                    aria-label="Row policy type"
                  >
                    {RLP_TYPES.map((type) => <option key={type} value={type}>{type.toUpperCase()}</option>)}
                  </select>
                  <button
                    type="button"
                    onClick={() => setRole((current) => ({
                      ...current,
                      rowLevelPolicies: current.rowLevelPolicies.filter((_, candidateIndex) => candidateIndex !== index),
                    }))}
                    className={btnIcon}
                    aria-label="Remove row-level policy"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
                {policy.type === 'jpql' ? (
                  <div className="grid gap-2 sm:grid-cols-2">
                    <input
                      value={policy.whereClause ?? ''}
                      onChange={(event) => setRole((current) => ({
                        ...current,
                        rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                          candidateIndex === index ? { ...candidate, whereClause: event.target.value } : candidate),
                      }))}
                      className="py-1 font-mono text-xs"
                      placeholder="{E}.createdBy = :current_user_username"
                      aria-label="JPQL where expression"
                    />
                    <input
                      value={policy.joinClause ?? ''}
                      onChange={(event) => setRole((current) => ({
                        ...current,
                        rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                          candidateIndex === index ? { ...candidate, joinClause: event.target.value } : candidate),
                      }))}
                      className="py-1 font-mono text-xs"
                      placeholder="Optional: join {E}.department d"
                      aria-label="JPQL join clause"
                    />
                  </div>
                ) : (
                  <div className="space-y-2">
                    <div className="flex flex-wrap gap-1">
                      {RLP_ACTIONS.map((action) => {
                        const selected = policy.actions.includes(action)
                        return (
                          <button
                            type="button"
                            key={action}
                            aria-pressed={selected}
                            onClick={() => setRole((current) => ({
                              ...current,
                              rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                                candidateIndex === index ? {
                                  ...candidate,
                                  actions: selected
                                    ? candidate.actions.filter((value) => value !== action)
                                    : [...candidate.actions, action],
                                } : candidate),
                            }))}
                            className={chipClass(selected)}
                          >
                            {action}
                          </button>
                        )
                      })}
                    </div>
                    <input
                      value={policy.predicateExpression ?? ''}
                      onChange={(event) => setRole((current) => ({
                        ...current,
                        rowLevelPolicies: current.rowLevelPolicies.map((candidate, candidateIndex) =>
                          candidateIndex === index ? { ...candidate, predicateExpression: event.target.value } : candidate),
                      }))}
                      className="w-full py-1 font-mono text-xs"
                      placeholder='entity.getStatus() == Status.ACTIVE'
                      aria-label="Predicate Java expression"
                    />
                  </div>
                )}
              </div>
            ))}
          </PolicyCard>
        )}

        <PolicyCard
          icon={ShieldCheck}
          title="Role Inheritance"
          count={role.baseRoleClasses.length}
          addLabel="Base role"
          onAdd={() => setRole((current) => ({
            ...current,
            baseRoleClasses: [...current.baseRoleClasses, ''],
          }))}
        >
          {role.baseRoleClasses.length === 0 && <EmptyHint>Compose this role from same-kind design-time role interfaces.</EmptyHint>}
          {role.baseRoleClasses.map((baseRole, index) => (
            <div key={index} className="flex items-center gap-2 rounded border border-surface-border bg-surface p-2">
              <ShieldCheck size={12} className="text-gray-500" />
              <input
                value={baseRole}
                onChange={(event) => setRole((current) => ({
                  ...current,
                  baseRoleClasses: current.baseRoleClasses.map((candidate, candidateIndex) =>
                    candidateIndex === index ? event.target.value : candidate),
                }))}
                className="min-w-0 flex-1 py-1 font-mono text-xs"
                placeholder="com.example.security.BasicEmployeeRole"
              />
              <button
                type="button"
                onClick={() => setRole((current) => ({
                  ...current,
                  baseRoleClasses: current.baseRoleClasses.filter((_, candidateIndex) => candidateIndex !== index),
                }))}
                className={btnIcon}
                aria-label="Remove base role"
              >
                <Trash2 size={12} />
              </button>
            </div>
          ))}
        </PolicyCard>
      </main>

      {/* Footer summary */}
      <footer className="flex min-w-0 shrink-0 flex-wrap items-center gap-x-4 gap-y-1 border-t border-surface-border bg-surface-light/60 px-3 py-1.5 text-[10px] text-gray-500 sm:px-4">
        <span className="flex items-center gap-1">
          <ShieldCheck size={11} className={isRowLevel ? 'text-amber-400' : 'text-jmix-400'} />
          {isRowLevel ? 'Row-level role' : 'Resource role'}
        </span>
        <span>{role.entityPolicies.length} entity policies</span>
        <span>{role.menuPolicies.length + role.viewPolicies.length} ui policies</span>
        {isRowLevel && <span>{role.rowLevelPolicies.length} row rules</span>}
      </footer>

      {pending && (
        <div
          className="absolute inset-0 z-50 flex min-h-0 min-w-0 items-stretch justify-center bg-black/65 p-2 backdrop-blur-sm sm:p-4"
          role="dialog"
          aria-modal="true"
          aria-labelledby="security-role-preview-title"
        >
          <section className="flex min-h-0 min-w-0 w-full max-w-5xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl shadow-black/60">
            <header className="flex min-w-0 shrink-0 items-start gap-3 border-b border-surface-border bg-surface-light px-3 py-3 sm:px-4">
              <Code2 size={17} className="mt-0.5 shrink-0 text-jmix-400" />
              <div className="min-w-0 flex-1">
                <h3 id="security-role-preview-title" className="text-sm font-semibold text-gray-100">
                  Review generated Jmix role
                </h3>
                <p className="mt-0.5 break-all font-mono text-[10px] text-gray-500">
                  {pending.preview.files[0]?.relativePath}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setPending(null)}
                className={btnIcon}
                aria-label="Close role source preview"
              >
                <X size={15} />
              </button>
            </header>

            <div className="min-h-0 min-w-0 flex-1 overflow-auto bg-[#161621]">
              <pre className="min-w-max p-3 font-mono text-[11px] leading-relaxed text-gray-200 sm:p-4">
                <code>{pending.preview.files[0]?.resultContent}</code>
              </pre>
            </div>

            {pending.preview.issues.length > 0 && (
              <div className="max-h-28 shrink-0 overflow-auto border-t border-amber-500/20 bg-amber-500/5 px-3 py-2 text-[10px] text-amber-100">
                {pending.preview.issues.map((issue) => (
                  <div key={`${issue.code}:${issue.relativePath ?? ''}`}>
                    <span className="font-mono text-amber-300">{issue.code}</span> — {issue.message}
                  </div>
                ))}
              </div>
            )}

            <footer className="flex min-w-0 shrink-0 flex-wrap items-center justify-between gap-2 border-t border-surface-border bg-surface-light px-3 py-2.5 sm:px-4">
              <p className="min-w-0 text-[10px] leading-relaxed text-gray-500">
                The file is created only after this exact revision-bound preview is approved.
              </p>
              <div className="flex shrink-0 items-center gap-2">
                <button type="button" onClick={() => setPending(null)} className={btnGhost} disabled={applying}>
                  Discard
                </button>
                <button type="button" onClick={() => void applyPending()} className={btnPrimary} disabled={applying}>
                  {applying ? <Loader2 size={13} className="animate-spin" /> : <ShieldCheck size={13} />}
                  Apply approved source
                </button>
              </div>
            </footer>
          </section>
        </div>
      )}
    </div>
  )
}

export default function RoleDesigner() {
  const [mode, setMode] = useState<'explore' | 'create'>('explore')
  return (
    <div className="flex h-full min-h-0 min-w-0 max-w-full flex-col overflow-hidden bg-surface">
      <div className="flex flex-wrap items-center gap-1 border-b border-surface-border bg-surface-light px-3 py-2">
        <button
          type="button"
          onClick={() => setMode('explore')}
          className={`rounded px-3 py-1.5 text-[11px] font-semibold ${
            mode === 'explore' ? 'bg-jmix-500 text-white' : 'text-gray-500 hover:bg-surface-lighter hover:text-gray-200'
          }`}
        >
          Effective Access
        </button>
        <button
          type="button"
          onClick={() => setMode('create')}
          className={`rounded px-3 py-1.5 text-[11px] font-semibold ${
            mode === 'create' ? 'bg-jmix-500 text-white' : 'text-gray-500 hover:bg-surface-lighter hover:text-gray-200'
          }`}
        >
          Create Role
        </button>
        <span className="ml-auto hidden text-[9px] text-gray-600 sm:inline">
          Source-aware security · revision-safe navigation
        </span>
      </div>
      <div className="flex min-h-0 min-w-0 flex-1 overflow-hidden">
        {mode === 'explore' ? <SecurityWorkspace /> : <NewRoleDesigner />}
      </div>
    </div>
  )
}
