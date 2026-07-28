import { useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  Database, GitCommit, Hash, KeyRound, ListMinus, ListPlus, Loader2, Play,
  Plus, PlusSquare, Table2, Terminal, Trash2,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { MigrationModel } from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

// ─── Model ───────────────────────────────────────────────────────────────────

type ChangeType =
  | 'createTable' | 'addColumn' | 'dropColumn' | 'addForeignKey'
  | 'createIndex' | 'insertData' | 'rawSql'

interface ColumnDef {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
}

type MigrationChange =
  | { changeType: 'createTable'; tableName: string; columns: ColumnDef[] }
  | { changeType: 'addColumn'; tableName: string; columnName: string; columnType: string; nullable: boolean }
  | { changeType: 'dropColumn'; tableName: string; columnName: string }
  | { changeType: 'addForeignKey'; tableName: string; column: string; referencedTable: string; referencedColumn: string; onDelete: string }
  | { changeType: 'createIndex'; tableName: string; indexName: string; columns: string[]; unique: boolean }
  | { changeType: 'insertData'; tableName: string; columns: string; values: string }
  | { changeType: 'rawSql'; sql: string }

interface ChangeSet {
  id: string
  comment: string
  changes: MigrationChange[]
}

const SQL_TYPES = [
  'UUID', 'VARCHAR(255)', 'TEXT', 'INT', 'BIGINT', 'DECIMAL(19,2)',
  'DOUBLE', 'BOOLEAN', 'DATE', 'TIMESTAMP',
]

const ON_DELETE = ['NO ACTION', 'CASCADE', 'SET NULL', 'RESTRICT']

const CHANGE_TYPES: { type: ChangeType; label: string; icon: LucideIcon }[] = [
  { type: 'createTable', label: 'Create Table', icon: Table2 },
  { type: 'addColumn', label: 'Add Column', icon: ListPlus },
  { type: 'dropColumn', label: 'Drop Column', icon: ListMinus },
  { type: 'addForeignKey', label: 'Foreign Key', icon: KeyRound },
  { type: 'createIndex', label: 'Index', icon: Hash },
  { type: 'insertData', label: 'Insert Data', icon: PlusSquare },
  { type: 'rawSql', label: 'Raw SQL', icon: Terminal },
]

const iconForChange = (type: ChangeType): LucideIcon =>
  CHANGE_TYPES.find((t) => t.type === type)?.icon ?? Database

function makeChange(type: ChangeType): MigrationChange {
  switch (type) {
    case 'createTable':
      return { changeType: 'createTable', tableName: '', columns: [{ name: 'ID', type: 'UUID', nullable: false, primaryKey: true }] }
    case 'addColumn':
      return { changeType: 'addColumn', tableName: '', columnName: '', columnType: 'VARCHAR(255)', nullable: true }
    case 'dropColumn':
      return { changeType: 'dropColumn', tableName: '', columnName: '' }
    case 'addForeignKey':
      return { changeType: 'addForeignKey', tableName: '', column: '', referencedTable: '', referencedColumn: 'ID', onDelete: 'NO ACTION' }
    case 'createIndex':
      return { changeType: 'createIndex', tableName: '', indexName: '', columns: [], unique: false }
    case 'insertData':
      return { changeType: 'insertData', tableName: '', columns: '', values: '' }
    case 'rawSql':
      return { changeType: 'rawSql', sql: '' }
  }
}

// ─── Styles ──────────────────────────────────────────────────────────────────

const btnPrimary =
  'inline-flex items-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const btnGhost =
  'inline-flex items-center gap-1 rounded border border-surface-border bg-surface-lighter px-2 py-1 text-[11px] text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300'
const btnIcon =
  'rounded p-1 text-gray-500 transition-colors hover:bg-surface-lighter hover:text-gray-200'
const inputSm = 'w-full py-1 text-xs'

function Field({ label, children, className = '' }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-1 block text-[10px] font-medium uppercase tracking-wider text-gray-500">{label}</span>
      {children}
    </label>
  )
}

// ─── Main component ──────────────────────────────────────────────────────────

export default function MigrationPanel() {
  const { addToast, isGenerating, setIsGenerating, setLastResult } = useStore()

  const [changelogId, setChangelogId] = useState('')
  const [author, setAuthor] = useState('jmix-studio')
  const [changesets, setChangesets] = useState<ChangeSet[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [activePane, setActivePane] = useState<'changesets' | 'editor'>('changesets')

  const uid = useRef(1)
  const selected = changesets.find((cs) => cs.id === selectedId) ?? null
  const totalChanges = changesets.reduce((sum, cs) => sum + cs.changes.length, 0)

  // ── Changeset ops ──────────────────────────────────────────────────────────

  const addChangeset = () => {
    const id = `changeset-${uid.current++}`
    setChangesets((prev) => [...prev, { id, comment: '', changes: [] }])
    setSelectedId(id)
    setActivePane('editor')
  }

  const removeChangeset = (id: string) => {
    setChangesets((prev) => prev.filter((cs) => cs.id !== id))
    if (selectedId === id) setSelectedId(null)
  }

  const updateChangeset = (id: string, patch: Partial<ChangeSet>) => {
    setChangesets((prev) => prev.map((cs) => (cs.id === id ? { ...cs, ...patch } : cs)))
  }

  // ── Change ops ─────────────────────────────────────────────────────────────

  const addChange = (csId: string, type: ChangeType) => {
    setChangesets((prev) =>
      prev.map((cs) => (cs.id === csId ? { ...cs, changes: [...cs.changes, makeChange(type)] } : cs)),
    )
  }

  const updateChange = (csId: string, index: number, change: MigrationChange) => {
    setChangesets((prev) =>
      prev.map((cs) =>
        cs.id !== csId
          ? cs
          : { ...cs, changes: cs.changes.map((c, i) => (i === index ? change : c)) },
      ),
    )
  }

  const removeChange = (csId: string, index: number) => {
    setChangesets((prev) =>
      prev.map((cs) =>
        cs.id !== csId ? cs : { ...cs, changes: cs.changes.filter((_, i) => i !== index) },
      ),
    )
  }

  const patchChange = (csId: string, index: number, patch: Record<string, unknown>) => {
    setChangesets((prev) =>
      prev.map((cs) =>
        cs.id !== csId
          ? cs
          : {
              ...cs,
              changes: cs.changes.map((c, i) =>
                i === index ? ({ ...c, ...patch } as MigrationChange) : c),
            },
      ),
    )
  }

  const patchColumn = (csId: string, changeIdx: number, colIdx: number, patch: Partial<ColumnDef>) => {
    setChangesets((prev) =>
      prev.map((cs) =>
        cs.id !== csId
          ? cs
          : {
              ...cs,
              changes: cs.changes.map((c, i) => {
                if (i !== changeIdx || c.changeType !== 'createTable') return c
                return { ...c, columns: c.columns.map((col, j) => (j === colIdx ? { ...col, ...patch } : col)) }
              }),
            },
      ),
    )
  }

  // ── Generate ───────────────────────────────────────────────────────────────

  const handleGenerate = async () => {
    if (!changelogId.trim()) {
      addToast('Changelog id is required', 'error')
      return
    }
    if (changesets.length === 0) {
      addToast('Add at least one changeset', 'error')
      return
    }
    if (changesets.some((cs) => cs.changes.length === 0)) {
      addToast('Every changeset needs at least one change', 'error')
      return
    }

    const resolvedAuthor = author.trim() || 'jmix-studio'
    const payload: MigrationModel = {
      changelogId: changelogId.trim(),
      author: resolvedAuthor,
      changes: changesets.map((cs) => ({
        id: cs.id,
        author: resolvedAuthor,
        comment: cs.comment || undefined,
        changes: cs.changes,
      })),
    }

    setIsGenerating(true)
    try {
      const result = await bridge.generateMigration(payload)
      setLastResult(result)
      if (result.success) {
        addToast(`Migration "${payload.changelogId}" generated — ${result.filesWritten.length} file(s) written`, 'success')
      } else {
        addToast(result.errors?.[0] ?? 'Migration generation failed', 'error')
      }
    } catch {
      addToast('Migration generation failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  // ─── Per-type change forms ─────────────────────────────────────────────────

  const renderChangeForm = (change: MigrationChange, csId: string, index: number) => {
    switch (change.changeType) {
      case 'createTable':
        return (
          <div className="space-y-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase().replace(/\s+/g, '_') })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_ITEM"
              />
            </Field>
            <div className="space-y-1.5">
              <div className="grid grid-cols-[1fr_128px_52px_38px_24px] items-center gap-1.5 px-0.5 text-[9px] font-semibold uppercase tracking-wider text-gray-600">
                <span>Column</span><span>Type</span><span>Null?</span><span>PK</span><span />
              </div>
              {change.columns.map((col, ci) => (
                <div key={ci} className="grid grid-cols-[1fr_128px_52px_38px_24px] items-center gap-1.5">
                  <input
                    value={col.name}
                    onChange={(e) => patchColumn(csId, index, ci, { name: e.target.value.toUpperCase().replace(/\s+/g, '_') })}
                    className="py-1 font-mono text-[11px] uppercase"
                    placeholder="NAME"
                    aria-label="Column name"
                  />
                  <select
                    value={col.type}
                    onChange={(e) => patchColumn(csId, index, ci, { type: e.target.value })}
                    className="py-1 font-mono text-[11px]"
                    aria-label="Column type"
                  >
                    {SQL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                  </select>
                  <label className="flex cursor-pointer items-center justify-center" title="Nullable">
                    <input
                      type="checkbox"
                      checked={col.nullable}
                      onChange={(e) => patchColumn(csId, index, ci, { nullable: e.target.checked })}
                      className="h-3.5 w-3.5 accent-jmix-500"
                    />
                  </label>
                  <label className="flex cursor-pointer items-center justify-center" title="Primary key">
                    <input
                      type="checkbox"
                      checked={col.primaryKey}
                      onChange={(e) => patchColumn(csId, index, ci, { primaryKey: e.target.checked })}
                      className="h-3.5 w-3.5 accent-amber-500"
                    />
                  </label>
                  <button
                    onClick={() => {
                      const nextChange: MigrationChange = {
                        ...change,
                        columns: change.columns.filter((_, j) => j !== ci),
                      }
                      updateChange(csId, index, nextChange)
                    }}
                    className={btnIcon}
                    title="Remove column"
                    aria-label="Remove column"
                  >
                    <Trash2 size={11} />
                  </button>
                </div>
              ))}
              <button
                onClick={() =>
                  updateChange(csId, index, {
                    ...change,
                    columns: [...change.columns, { name: '', type: 'VARCHAR(255)', nullable: true, primaryKey: false }],
                  })
                }
                className={btnGhost}
              >
                <Plus size={11} /> Add Column
              </button>
            </div>
          </div>
        )

      case 'addColumn':
        return (
          <div className="grid grid-cols-2 gap-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_"
              />
            </Field>
            <Field label="Column Name">
              <input
                value={change.columnName}
                onChange={(e) => patchChange(csId, index, { columnName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="STATUS"
              />
            </Field>
            <Field label="Column Type">
              <select
                value={change.columnType}
                onChange={(e) => patchChange(csId, index, { columnType: e.target.value })}
                className={inputSm}
              >
                {SQL_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </Field>
            <label className="flex cursor-pointer items-end gap-1.5 pb-1.5 text-[11px] text-gray-400">
              <input
                type="checkbox"
                checked={change.nullable}
                onChange={(e) => patchChange(csId, index, { nullable: e.target.checked })}
                className="h-3.5 w-3.5 accent-jmix-500"
              />
              Nullable
            </label>
          </div>
        )

      case 'dropColumn':
        return (
          <div className="grid grid-cols-2 gap-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_"
              />
            </Field>
            <Field label="Column Name">
              <input
                value={change.columnName}
                onChange={(e) => patchChange(csId, index, { columnName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="LEGACY_FIELD"
              />
            </Field>
          </div>
        )

      case 'addForeignKey':
        return (
          <div className="grid grid-cols-2 gap-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_ITEM"
              />
            </Field>
            <Field label="Column">
              <input
                value={change.column}
                onChange={(e) => patchChange(csId, index, { column: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_ID"
              />
            </Field>
            <Field label="Referenced Table">
              <input
                value={change.referencedTable}
                onChange={(e) => patchChange(csId, index, { referencedTable: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_"
              />
            </Field>
            <div className="grid grid-cols-2 gap-2">
              <Field label="Ref. Column">
                <input
                  value={change.referencedColumn}
                  onChange={(e) => patchChange(csId, index, { referencedColumn: e.target.value.toUpperCase() })}
                  className={`${inputSm} font-mono uppercase`}
                  placeholder="ID"
                />
              </Field>
              <Field label="On Delete">
                <select
                  value={change.onDelete}
                  onChange={(e) => patchChange(csId, index, { onDelete: e.target.value })}
                  className={inputSm}
                >
                  {ON_DELETE.map((o) => <option key={o} value={o}>{o}</option>)}
                </select>
              </Field>
            </div>
          </div>
        )

      case 'createIndex':
        return (
          <div className="grid grid-cols-2 gap-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="ORDER_"
              />
            </Field>
            <Field label="Index Name">
              <input
                value={change.indexName}
                onChange={(e) => patchChange(csId, index, { indexName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="IDX_ORDER_STATUS"
              />
            </Field>
            <Field label="Columns (comma-separated)">
              <input
                value={change.columns.join(', ')}
                onChange={(e) => patchChange(csId, index, { columns: e.target.value.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean) })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="STATUS, CREATED_DATE"
              />
            </Field>
            <label className="flex cursor-pointer items-end gap-1.5 pb-1.5 text-[11px] text-gray-400">
              <input
                type="checkbox"
                checked={change.unique}
                onChange={(e) => patchChange(csId, index, { unique: e.target.checked })}
                className="h-3.5 w-3.5 accent-jmix-500"
              />
              Unique index
            </label>
          </div>
        )

      case 'insertData':
        return (
          <div className="space-y-2">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                placeholder="APP_CONFIG"
              />
            </Field>
            <Field label="Columns (comma-separated)">
              <input
                value={change.columns}
                onChange={(e) => patchChange(csId, index, { columns: e.target.value })}
                className={`${inputSm} font-mono`}
                placeholder="KEY, VALUE"
              />
            </Field>
            <Field label="Values (comma-separated)">
              <input
                value={change.values}
                onChange={(e) => patchChange(csId, index, { values: e.target.value })}
                className={`${inputSm} font-mono`}
                placeholder="theme, dark"
              />
            </Field>
          </div>
        )

      case 'rawSql':
        return (
          <Field label="SQL">
            <textarea
              value={change.sql}
              onChange={(e) => patchChange(csId, index, { sql: e.target.value })}
              rows={5}
              className="w-full resize-y py-1.5 font-mono text-[11px] leading-relaxed"
              placeholder={'UPDATE ORDER_ SET STATUS = \'CLOSED\'\nWHERE EXPIRY_DATE < CURRENT_DATE;'}
              spellCheck={false}
            />
          </Field>
        )
    }
  }

  // ── Render ─────────────────────────────────────────────────────────────────

  return (
    <div className="flex h-full min-w-0 flex-col bg-surface [color-scheme:dark]">
      {/* Top bar */}
      <header className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-surface-border bg-surface-light/60 px-3 py-2">
        <div className="flex items-center gap-2">
          <Database size={15} className="text-jmix-400" />
          <h2 className="text-xs font-bold uppercase tracking-widest text-gray-300">Migration Builder</h2>
        </div>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Changelog
          <input
            value={changelogId}
            onChange={(e) => setChangelogId(e.target.value)}
            className="w-40 py-1 font-mono text-xs tracking-normal sm:w-52"
            placeholder="2026-07-27-order-schema"
          />
        </label>

        <label className="flex items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Author
          <input
            value={author}
            onChange={(e) => setAuthor(e.target.value)}
            className="w-32 py-1 text-xs tracking-normal"
          />
        </label>

        <button onClick={addChangeset} className={btnGhost}>
          <Plus size={12} className="text-jmix-400" /> Add Changeset
        </button>

        <div className="ml-auto flex flex-wrap items-center justify-end gap-2">
          <span className="rounded-full border border-surface-border bg-surface-lighter px-2 py-0.5 text-[10px] text-gray-400">
            {changesets.length} changeset{changesets.length === 1 ? '' : 's'} · {totalChanges} change{totalChanges === 1 ? '' : 's'}
          </span>
          <button onClick={handleGenerate} disabled={isGenerating} className={btnPrimary}>
            {isGenerating ? <Loader2 size={13} className="animate-spin" /> : <Play size={13} />}
            Generate Migration
          </button>
        </div>
      </header>

      <ResponsivePaneSwitcher
        value={activePane}
        onChange={setActivePane}
        label="Migration builder panels"
        options={[
          { id: 'changesets', label: 'Changesets', icon: <GitCommit size={12} />, badge: changesets.length },
          { id: 'editor', label: 'Change editor', icon: <Database size={12} />, badge: totalChanges },
        ]}
      />

      {/* Workspace */}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Left: changeset list */}
        <aside className={`${activePane === 'changesets' ? 'flex' : 'hidden'} min-h-0 w-full shrink-0 flex-col bg-surface-light/40 min-[1600px]:flex min-[1600px]:w-64 min-[1600px]:border-r min-[1600px]:border-surface-border`}>
          <div className="flex items-center gap-1.5 border-b border-surface-border px-3 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
            <GitCommit size={12} className="text-jmix-400" /> Changesets
          </div>
          <div className="flex-1 space-y-1.5 overflow-y-auto p-2">
            {changesets.length === 0 && (
              <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
                <GitCommit size={20} className="text-gray-600" />
                <p className="text-[11px] leading-relaxed text-gray-500">
                  No changesets yet. Each changeset groups related schema changes into one atomic unit.
                </p>
                <button onClick={addChangeset} className={btnGhost}>
                  <Plus size={12} /> Add Changeset
                </button>
              </div>
            )}
            {changesets.map((cs, i) => {
              const isSelected = selectedId === cs.id
              return (
                <div
                  key={cs.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => { setSelectedId(cs.id); setActivePane('editor') }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      setSelectedId(cs.id)
                      setActivePane('editor')
                    }
                  }}
                  className={`group w-full cursor-pointer rounded-md border p-2.5 text-left transition-all ${
                    isSelected
                      ? 'border-jmix-500 bg-jmix-500/10 ring-1 ring-inset ring-jmix-500/40'
                      : 'border-surface-border bg-surface-light hover:border-gray-500'
                  }`}
                >
                  <div className="flex items-center gap-1.5">
                    <GitCommit size={13} className={isSelected ? 'text-jmix-400' : 'text-gray-500'} />
                    <span className="text-[10px] font-semibold text-gray-500">#{i + 1}</span>
                    <span className="truncate text-xs font-medium text-gray-200">{cs.id}</span>
                    <span className="ml-auto rounded-full bg-surface-lighter px-1.5 py-px text-[9px] text-gray-500">
                      {cs.changes.length}
                    </span>
                    <button
                      onClick={(e) => { e.stopPropagation(); removeChangeset(cs.id) }}
                      className="rounded p-0.5 text-gray-600 opacity-0 transition-all hover:bg-red-500/15 hover:text-red-400 group-hover:opacity-100"
                      title="Delete changeset"
                      aria-label="Delete changeset"
                    >
                      <Trash2 size={11} />
                    </button>
                  </div>
                  <p className="mt-1 truncate text-[10px] text-gray-500">{cs.comment || 'No comment'}</p>
                  {cs.changes.length > 0 && (
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      {cs.changes.map((c, j) => {
                        const Icon = iconForChange(c.changeType)
                        return (
                          <span
                            key={j}
                            className="inline-flex items-center gap-1 rounded bg-surface-lighter px-1 py-px text-[8px] uppercase tracking-wide text-gray-500"
                          >
                            <Icon size={8} />
                            {c.changeType}
                          </span>
                        )
                      })}
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </aside>

        {/* Right: changeset editor */}
        <section className={`${activePane === 'editor' ? 'flex' : 'hidden'} min-h-0 min-w-0 flex-1 flex-col overflow-hidden min-[1600px]:flex`}>
          {!selected ? (
            <div className="flex flex-1 flex-col items-center justify-center gap-2 p-6 text-center">
              <GitCommit size={26} className="text-gray-600" />
              <p className="max-w-xs text-[11px] leading-relaxed text-gray-500">
                Select a changeset to edit it, or add a new one. Changes are applied in changeset order.
              </p>
            </div>
          ) : (
            <div className="flex-1 space-y-4 overflow-y-auto p-4">
              {/* Changeset meta */}
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-[240px_1fr]">
                <Field label="Changeset Id">
                  <input
                    value={selected.id}
                    onChange={(e) => {
                      const nextId = e.target.value.replace(/\s+/g, '-')
                      setChangesets((prev) => prev.map((cs) => (cs.id === selected.id ? { ...cs, id: nextId } : cs)))
                      setSelectedId(nextId)
                    }}
                    className={`${inputSm} font-mono`}
                  />
                </Field>
                <Field label="Comment">
                  <input
                    value={selected.comment}
                    onChange={(e) => updateChangeset(selected.id, { comment: e.target.value })}
                    className={inputSm}
                    placeholder="Create ORDER_ and ORDER_ITEM tables"
                  />
                </Field>
              </div>

              {/* Changes */}
              <div>
                <p className="mb-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
                  Changes ({selected.changes.length})
                </p>
                <div className="space-y-3">
                  {selected.changes.map((change, i) => {
                    const Icon = iconForChange(change.changeType)
                    return (
                      <div key={i} className="overflow-hidden rounded-md border border-surface-border bg-surface-light">
                        <div className="flex items-center gap-2 border-b border-surface-border bg-surface px-2.5 py-1.5">
                          <Icon size={13} className="text-jmix-400" />
                          <select
                            value={change.changeType}
                            onChange={(e) => updateChange(selected.id, i, makeChange(e.target.value as ChangeType))}
                            className="bg-transparent py-0.5 text-[11px] font-semibold text-gray-200"
                            aria-label="Change type"
                          >
                            {CHANGE_TYPES.map((t) => <option key={t.type} value={t.type}>{t.label}</option>)}
                          </select>
                          <span className="ml-auto text-[9px] uppercase tracking-wider text-gray-600">#{i + 1}</span>
                          <button
                            onClick={() => removeChange(selected.id, i)}
                            className={btnIcon}
                            title="Remove change"
                            aria-label="Remove change"
                          >
                            <Trash2 size={12} />
                          </button>
                        </div>
                        <div className="p-2.5">{renderChangeForm(change, selected.id, i)}</div>
                      </div>
                    )
                  })}
                  {selected.changes.length === 0 && (
                    <p className="rounded-md border border-dashed border-surface-border px-3 py-4 text-center text-[11px] text-gray-600">
                      No changes in this changeset — add one below.
                    </p>
                  )}
                </div>

                {/* Add-change quick buttons */}
                <div className="mt-3 flex flex-wrap gap-1.5">
                  {CHANGE_TYPES.map((t) => (
                    <button key={t.type} onClick={() => addChange(selected.id, t.type)} className={btnGhost}>
                      <t.icon size={12} className="text-jmix-400" /> {t.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
