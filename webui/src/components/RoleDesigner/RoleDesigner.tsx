import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Database, Loader2, Lock, Menu as MenuIcon, Monitor, Play, Plus, Shield,
  ShieldCheck, SlidersHorizontal, Trash2,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { RoleModel } from '../../types'
import SecurityWorkspace from './SecurityWorkspace'

// ─── Constants ───────────────────────────────────────────────────────────────

const CRUD_ACTIONS = ['create', 'read', 'update', 'delete'] as const
const RLP_TYPES = ['JPQL', 'predicate', 'script'] as const
const RLP_ACTIONS = ['create', 'read', 'update', 'delete'] as const

const slugify = (s: string) =>
  s.trim().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')

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
  const { addToast, isGenerating, setIsGenerating, setLastResult } = useStore()

  const [role, setRole] = useState<RoleModel>({
    name: '',
    code: '',
    description: '',
    scope: 'resource',
    entityPolicies: [],
    menuPolicies: [],
    screenPolicies: [],
    specificPolicies: [],
    rowLevelPolicies: [],
  })

  // Auto-derive the role code from the name until the user edits it manually
  const codeDirty = useRef(false)

  const isRowLevel = role.scope === 'rowLevel'

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

  // ── Generate ───────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    if (!role.name.trim() || !role.code.trim()) {
      addToast('Role name and code are required', 'error')
      return
    }
    if (role.entityPolicies.some((p) => !p.entityClass.trim())) {
      addToast('Every entity policy needs an entity class', 'error')
      return
    }

    setIsGenerating(true)
    try {
      const result = await bridge.generateRole(role)
      setLastResult(result)
      if (result.success) {
        addToast(`Role "${role.code}" generated — ${result.filesWritten.length} file(s) written`, 'success')
      } else {
        addToast(result.errors?.[0] ?? 'Role generation failed', 'error')
      }
    } catch {
      addToast('Role generation failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="flex h-full flex-col bg-surface [color-scheme:dark]">
      {/* Header */}
      <header className="space-y-2.5 border-b border-surface-border bg-surface-light/60 px-4 py-3">
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
                onClick={() => setRole((r) => ({ ...r, scope: s }))}
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
            <button onClick={handleGenerate} disabled={isGenerating} className={btnPrimary}>
              {isGenerating ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
              Generate Role
            </button>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-3 min-[800px]:grid-cols-[minmax(10rem,220px)_minmax(10rem,220px)_minmax(12rem,1fr)]">
          <label className="block">
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Role Name *</span>
            <input
              value={role.name}
              onChange={(e) => {
                const name = e.target.value
                setRole((r) => ({ ...r, name, code: codeDirty.current ? r.code : slugify(name) }))
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
            <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">Description</span>
            <input
              value={role.description ?? ''}
              onChange={(e) => setRole((r) => ({ ...r, description: e.target.value }))}
              className="w-full py-1 text-xs"
              placeholder="Full access to order entities and their screens"
            />
          </label>
        </div>
      </header>

      {/* Policy cards */}
      <main className="grid flex-1 grid-cols-1 gap-4 overflow-y-auto p-4 min-[1600px]:grid-cols-2">
        {/* Entity policies */}
        <PolicyCard
          icon={Database}
          title="Entity Policies"
          count={role.entityPolicies.length}
          addLabel="Entity"
          onAdd={() =>
            setRole((r) => ({
              ...r,
              entityPolicies: [...r.entityPolicies, { entityClass: '', actions: ['read'], allActions: false }],
            }))
          }
        >
          {role.entityPolicies.length === 0 && (
            <EmptyHint>Grant CRUD access per entity class.</EmptyHint>
          )}
          {role.entityPolicies.map((p, i) => (
            <div
              key={i}
              className="flex flex-wrap items-center gap-2 rounded border border-surface-border bg-surface p-2 transition-colors hover:border-gray-500"
            >
              <Database size={13} className="shrink-0 text-gray-500" />
              <input
                value={p.entityClass}
                onChange={(e) =>
                  setRole((r) => ({
                    ...r,
                    entityPolicies: r.entityPolicies.map((ep, idx) =>
                      idx === i ? { ...ep, entityClass: e.target.value } : ep),
                  }))
                }
                className="min-w-[160px] flex-1 py-1 font-mono text-xs"
                placeholder="com.example.entity.Order"
                aria-label="Entity class"
              />
              <div className="flex items-center gap-1">
                {CRUD_ACTIONS.map((a) => (
                  <button
                    key={a}
                    onClick={() => toggleAction(i, a)}
                    className={chipClass(p.actions.includes(a))}
                    title={a}
                    aria-pressed={p.actions.includes(a)}
                  >
                    {a[0].toUpperCase()}
                  </button>
                ))}
                <button
                  onClick={() => toggleAction(i, 'all')}
                  className={chipClass(p.allActions, 'amber')}
                  title="All actions"
                  aria-pressed={p.allActions}
                >
                  All
                </button>
              </div>
              <button
                onClick={() =>
                  setRole((r) => ({ ...r, entityPolicies: r.entityPolicies.filter((_, idx) => idx !== i) }))
                }
                className={btnIcon}
                title="Remove policy"
                aria-label="Remove entity policy"
              >
                <Trash2 size={12} />
              </button>
            </div>
          ))}
        </PolicyCard>

        {/* Menu policies */}
        <PolicyCard
          icon={MenuIcon}
          title="Menu Policies"
          count={role.menuPolicies.length}
          addLabel="Menu"
          onAdd={() => setRole((r) => ({ ...r, menuPolicies: [...r.menuPolicies, { menuId: '' }] }))}
        >
          {role.menuPolicies.length === 0 && <EmptyHint>Expose menu items to this role.</EmptyHint>}
          {role.menuPolicies.map((p, i) => (
            <div
              key={i}
              className="flex items-center gap-2 rounded border border-surface-border bg-surface p-2 transition-colors hover:border-gray-500"
            >
              <MenuIcon size={13} className="shrink-0 text-gray-500" />
              <input
                value={p.menuId}
                onChange={(e) =>
                  setRole((r) => ({
                    ...r,
                    menuPolicies: r.menuPolicies.map((mp, idx) =>
                      idx === i ? { ...mp, menuId: e.target.value } : mp),
                  }))
                }
                className="min-w-0 flex-1 py-1 font-mono text-xs"
                placeholder="customers"
                aria-label="Menu id"
              />
              <button
                onClick={() => setRole((r) => ({ ...r, menuPolicies: r.menuPolicies.filter((_, idx) => idx !== i) }))}
                className={btnIcon}
                title="Remove policy"
                aria-label="Remove menu policy"
              >
                <Trash2 size={12} />
              </button>
            </div>
          ))}
        </PolicyCard>

        {/* Screen policies */}
        <PolicyCard
          icon={Monitor}
          title="Screen Policies"
          count={role.screenPolicies.length}
          addLabel="Screen"
          onAdd={() => setRole((r) => ({ ...r, screenPolicies: [...r.screenPolicies, { screenId: '' }] }))}
        >
          {role.screenPolicies.length === 0 && <EmptyHint>Grant access to views by screen id.</EmptyHint>}
          {role.screenPolicies.map((p, i) => (
            <div
              key={i}
              className="flex items-center gap-2 rounded border border-surface-border bg-surface p-2 transition-colors hover:border-gray-500"
            >
              <Monitor size={13} className="shrink-0 text-gray-500" />
              <input
                value={p.screenId}
                onChange={(e) =>
                  setRole((r) => ({
                    ...r,
                    screenPolicies: r.screenPolicies.map((sp, idx) =>
                      idx === i ? { ...sp, screenId: e.target.value } : sp),
                  }))
                }
                className="min-w-0 flex-1 py-1 font-mono text-xs"
                placeholder="CustomerListView"
                aria-label="Screen id"
              />
              <button
                onClick={() => setRole((r) => ({ ...r, screenPolicies: r.screenPolicies.filter((_, idx) => idx !== i) }))}
                className={btnIcon}
                title="Remove policy"
                aria-label="Remove screen policy"
              >
                <Trash2 size={12} />
              </button>
            </div>
          ))}
        </PolicyCard>

        {/* Row-level policies */}
        <PolicyCard
          icon={SlidersHorizontal}
          title="Row-Level Policies"
          count={isRowLevel ? role.rowLevelPolicies.length : 0}
          addLabel="Rule"
          onAdd={() => {
            if (!isRowLevel) {
              addToast('Switch scope to Row-level to add row-level policies', 'info')
              return
            }
            setRole((r) => ({
              ...r,
              rowLevelPolicies: [
                ...r.rowLevelPolicies,
                { entityClass: '', type: 'JPQL', action: 'read', whereClause: '' },
              ],
            }))
          }}
        >
          {!isRowLevel && (
            <div className="flex items-center gap-2 rounded border border-dashed border-surface-border px-3 py-3 text-[11px] text-gray-500">
              <Lock size={12} className="shrink-0 text-gray-600" />
              Row-level policies are available when scope is set to <b className="text-gray-400">Row-level</b>.
            </div>
          )}
          {isRowLevel && role.rowLevelPolicies.length === 0 && (
            <EmptyHint>Restrict rows per entity with JPQL, predicate or script rules.</EmptyHint>
          )}
          {isRowLevel &&
            role.rowLevelPolicies.map((p, i) => (
              <div
                key={i}
                className="space-y-1.5 rounded border border-surface-border bg-surface p-2 transition-colors hover:border-gray-500"
              >
                <div className="flex items-center gap-2">
                  <SlidersHorizontal size={13} className="shrink-0 text-gray-500" />
                  <input
                    value={p.entityClass}
                    onChange={(e) =>
                      setRole((r) => ({
                        ...r,
                        rowLevelPolicies: r.rowLevelPolicies.map((rp, idx) =>
                          idx === i ? { ...rp, entityClass: e.target.value } : rp),
                      }))
                    }
                    className="min-w-0 flex-1 py-1 font-mono text-xs"
                    placeholder="com.example.entity.Order"
                    aria-label="Entity class"
                  />
                  <select
                    value={p.type}
                    onChange={(e) =>
                      setRole((r) => ({
                        ...r,
                        rowLevelPolicies: r.rowLevelPolicies.map((rp, idx) =>
                          idx === i ? { ...rp, type: e.target.value } : rp),
                      }))
                    }
                    className="w-[92px] shrink-0 py-1 text-[11px]"
                    aria-label="Policy type"
                  >
                    {RLP_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                  </select>
                  <select
                    value={p.action}
                    onChange={(e) =>
                      setRole((r) => ({
                        ...r,
                        rowLevelPolicies: r.rowLevelPolicies.map((rp, idx) =>
                          idx === i ? { ...rp, action: e.target.value } : rp),
                      }))
                    }
                    className="w-[80px] shrink-0 py-1 text-[11px]"
                    aria-label="Policy action"
                  >
                    {RLP_ACTIONS.map((a) => <option key={a} value={a}>{a}</option>)}
                  </select>
                  <button
                    onClick={() =>
                      setRole((r) => ({ ...r, rowLevelPolicies: r.rowLevelPolicies.filter((_, idx) => idx !== i) }))
                    }
                    className={btnIcon}
                    title="Remove policy"
                    aria-label="Remove row-level policy"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
                <input
                  value={p.whereClause ?? ''}
                  onChange={(e) =>
                    setRole((r) => ({
                      ...r,
                      rowLevelPolicies: r.rowLevelPolicies.map((rp, idx) =>
                        idx === i ? { ...rp, whereClause: e.target.value } : rp),
                    }))
                  }
                  className="w-full py-1 font-mono text-xs"
                  placeholder={p.type === 'JPQL' ? "where {E}.status = 'ACTIVE'" : p.type === 'script' ? 'entity.status == "ACTIVE"' : 'predicate expression'}
                  aria-label="Where clause"
                />
              </div>
            ))}
        </PolicyCard>
      </main>

      {/* Footer summary */}
      <footer className="flex items-center gap-4 border-t border-surface-border bg-surface-light/60 px-4 py-1.5 text-[10px] text-gray-500">
        <span className="flex items-center gap-1">
          <ShieldCheck size={11} className={isRowLevel ? 'text-amber-400' : 'text-jmix-400'} />
          {isRowLevel ? 'Row-level role' : 'Resource role'}
        </span>
        <span>{role.entityPolicies.length} entity policies</span>
        <span>{role.menuPolicies.length + role.screenPolicies.length} ui policies</span>
        {isRowLevel && <span>{role.rowLevelPolicies.length} row rules</span>}
      </footer>
    </div>
  )
}

export default function RoleDesigner() {
  const [mode, setMode] = useState<'explore' | 'create'>('explore')
  return (
    <div className="flex h-full min-h-0 flex-col bg-surface">
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
      <div className="flex min-h-0 flex-1">
        {mode === 'explore' ? <SecurityWorkspace /> : <NewRoleDesigner />}
      </div>
    </div>
  )
}
