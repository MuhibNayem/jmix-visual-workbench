import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import {
  AlertTriangle, Boxes, Database, FileCode2, GitCommit, Hash, KeyRound, ListMinus,
  ListPlus, Loader2, Play, Plus, PlusSquare, RefreshCw, ShieldCheck, Table2,
  Terminal, Trash2, X,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  MigrationModel,
  SchemaDriftSnapshot,
  SchemaDriftSuggestion,
  SchemaMigrationChangeRequest,
  SchemaWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'

// ─── Model ───────────────────────────────────────────────────────────────────

type ChangeType =
  | 'createTable' | 'addColumn' | 'dropColumn' | 'addForeignKey'
  | 'createIndex' | 'modifyColumn' | 'renameColumn' | 'addUniqueConstraint'
  | 'addNotNullConstraint' | 'dropNotNullConstraint'
  | 'insertData' | 'rawSql'

interface ColumnDef {
  name: string
  type: string
  nullable: boolean
  unique: boolean
  primaryKey: boolean
}

type MigrationChange =
  | { changeType: 'createTable'; tableName: string; columns: ColumnDef[] }
  | { changeType: 'addColumn'; tableName: string; columnName: string; columnType: string; nullable: boolean }
  | { changeType: 'dropColumn'; tableName: string; columnName: string }
  | { changeType: 'addForeignKey'; tableName: string; column: string; referencedTable: string; referencedColumn: string; onDelete: string }
  | { changeType: 'createIndex'; tableName: string; indexName: string; columns: string[]; unique: boolean }
  | { changeType: 'modifyColumn'; tableName: string; columnName: string; newDataType: string }
  | { changeType: 'renameColumn'; tableName: string; oldColumnName: string; newColumnName: string; columnDataType?: string }
  | { changeType: 'addUniqueConstraint'; tableName: string; constraintName: string; columnNames: string[] }
  | { changeType: 'addNotNullConstraint'; tableName: string; columnName: string; columnDataType: string }
  | { changeType: 'dropNotNullConstraint'; tableName: string; columnName: string; columnDataType: string }
  | { changeType: 'insertData'; tableName: string; columns: string; values: string }
  | { changeType: 'rawSql'; sql: string }

interface ChangeSet {
  id: string
  comment: string
  changes: MigrationChange[]
  autoRollback: boolean
  preConditions?: any[]
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
  { type: 'modifyColumn', label: 'Change Type', icon: RefreshCw },
  { type: 'renameColumn', label: 'Quarantine Column', icon: ShieldCheck },
  { type: 'addUniqueConstraint', label: 'Unique', icon: ShieldCheck },
  { type: 'addNotNullConstraint', label: 'Require Value', icon: ShieldCheck },
  { type: 'dropNotNullConstraint', label: 'Allow Null', icon: ShieldCheck },
  { type: 'insertData', label: 'Insert Data', icon: PlusSquare },
  { type: 'rawSql', label: 'Raw SQL', icon: Terminal },
]

const iconForChange = (type: ChangeType): LucideIcon =>
  CHANGE_TYPES.find((t) => t.type === type)?.icon ?? Database

function automaticRollback(change: MigrationChange): Record<string, unknown> | null {
  switch (change.changeType) {
    case 'createTable':
      return { changeType: 'dropTable', tableName: change.tableName, cascadeConstraints: true }
    case 'addColumn':
      return { changeType: 'dropColumn', tableName: change.tableName, columnName: change.columnName }
    case 'addForeignKey':
      return {
        changeType: 'dropForeignKeyConstraint',
        constraintName: `FK_${change.tableName}_${change.column}`,
        baseTableName: change.tableName,
      }
    case 'createIndex':
      return { changeType: 'dropIndex', tableName: change.tableName, indexName: change.indexName }
    case 'addUniqueConstraint':
      return {
        changeType: 'dropUniqueConstraint',
        tableName: change.tableName,
        constraintName: change.constraintName,
      }
    case 'addNotNullConstraint':
      return {
        changeType: 'dropNotNullConstraint',
        tableName: change.tableName,
        columnName: change.columnName,
      }
    case 'dropNotNullConstraint':
      return {
        changeType: 'addNotNullConstraint',
        tableName: change.tableName,
        columnName: change.columnName,
        columnDataType: change.columnDataType,
      }
    case 'modifyColumn':
      return null
    case 'renameColumn':
      return {
        changeType: 'renameColumn',
        tableName: change.tableName,
        oldColumnName: change.newColumnName,
        newColumnName: change.oldColumnName,
        columnDataType: change.columnDataType,
      }
    case 'dropColumn':
    case 'insertData':
    case 'rawSql':
      return null
  }
}

function makeChange(type: ChangeType): MigrationChange {
  switch (type) {
    case 'createTable':
      return {
        changeType: 'createTable',
        tableName: '',
        columns: [{ name: 'ID', type: 'UUID', nullable: false, unique: true, primaryKey: true }],
      }
    case 'addColumn':
      return { changeType: 'addColumn', tableName: '', columnName: '', columnType: 'VARCHAR(255)', nullable: true }
    case 'dropColumn':
      return { changeType: 'dropColumn', tableName: '', columnName: '' }
    case 'addForeignKey':
      return { changeType: 'addForeignKey', tableName: '', column: '', referencedTable: '', referencedColumn: 'ID', onDelete: 'NO ACTION' }
    case 'createIndex':
      return { changeType: 'createIndex', tableName: '', indexName: '', columns: [], unique: false }
    case 'modifyColumn':
      return { changeType: 'modifyColumn', tableName: '', columnName: '', newDataType: 'VARCHAR(255)' }
    case 'renameColumn':
      return { changeType: 'renameColumn', tableName: '', oldColumnName: '', newColumnName: '' }
    case 'addUniqueConstraint':
      return { changeType: 'addUniqueConstraint', tableName: '', constraintName: '', columnNames: [] }
    case 'addNotNullConstraint':
      return { changeType: 'addNotNullConstraint', tableName: '', columnName: '', columnDataType: 'VARCHAR(255)' }
    case 'dropNotNullConstraint':
      return { changeType: 'dropNotNullConstraint', tableName: '', columnName: '', columnDataType: 'VARCHAR(255)' }
    case 'insertData':
      return { changeType: 'insertData', tableName: '', columns: '', values: '' }
    case 'rawSql':
      return { changeType: 'rawSql', sql: '' }
  }
}

function migrationChangeFromSuggestion(suggestion: SchemaDriftSuggestion): MigrationChange | null {
  switch (suggestion.changeType) {
    case 'createTable':
      return {
        changeType: 'createTable',
        tableName: suggestion.tableName,
        columns: suggestion.columns.map((column) => ({
          name: column.name,
          type: column.type,
          nullable: column.nullable,
          unique: column.unique,
          primaryKey: column.primaryKey,
        })),
      }
    case 'addColumn':
      return {
        changeType: 'addColumn',
        tableName: suggestion.tableName,
        columnName: suggestion.columnName ?? '',
        columnType: suggestion.columnType ?? 'VARCHAR(255)',
        nullable: suggestion.nullable ?? true,
      }
    case 'modifyColumn':
      return {
        changeType: 'modifyColumn',
        tableName: suggestion.tableName,
        columnName: suggestion.columnName ?? '',
        newDataType: suggestion.newDataType ?? suggestion.columnType ?? 'VARCHAR(255)',
      }
    case 'renameColumn':
      return {
        changeType: 'renameColumn',
        tableName: suggestion.tableName,
        oldColumnName: suggestion.columnName ?? '',
        newColumnName: suggestion.newColumnName ?? '',
        columnDataType: suggestion.columnType,
      }
    case 'addUniqueConstraint':
      return {
        changeType: 'addUniqueConstraint',
        tableName: suggestion.tableName,
        constraintName: suggestion.constraintName ?? `UQ_${suggestion.tableName}_${suggestion.columnName ?? 'COLUMN'}`,
        columnNames: suggestion.columnNames,
      }
    case 'addNotNullConstraint':
    case 'dropNotNullConstraint':
      return {
        changeType: suggestion.changeType,
        tableName: suggestion.tableName,
        columnName: suggestion.columnName ?? '',
        columnDataType: suggestion.columnType ?? 'VARCHAR(255)',
      }
    case 'addForeignKey':
      return {
        changeType: 'addForeignKey',
        tableName: suggestion.baseTableName ?? suggestion.tableName,
        column: suggestion.baseColumnNames ?? suggestion.columnName ?? '',
        referencedTable: suggestion.referencedTableName ?? '',
        referencedColumn: suggestion.referencedColumnNames ?? 'ID',
        onDelete: suggestion.onDelete ?? 'NO ACTION',
      }
    default:
      return null
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
  // Selective subscriptions avoid re-rendering this large component on every
  // store change (e.g. each tab switch), which caused switching hangs.
  const addToast = useStore((state) => state.addToast)
  const isGenerating = useStore((state) => state.isGenerating)
  const setIsGenerating = useStore((state) => state.setIsGenerating)
  const setLastResult = useStore((state) => state.setLastResult)

  const [changelogId, setChangelogId] = useState(() => {
    const now = new Date()
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}-schema`
  })
  const [author, setAuthor] = useState('jmix-workbench')
  const [changesets, setChangesets] = useState<ChangeSet[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [activePane, setActivePane] = useState<'schema' | 'changesets' | 'editor'>('editor')
  const [schema, setSchema] = useState<SchemaWorkspaceResponse | null>(null)
  const [schemaLoading, setSchemaLoading] = useState(true)
  const [selectedStoreId, setSelectedStoreId] = useState('')
  const [selectedEntityId, setSelectedEntityId] = useState<string | null>(null)
  const [pending, setPending] = useState<{
    change: SchemaMigrationChangeRequest
    preview: WorkspaceChangePreviewResponse
  } | null>(null)

  const uid = useRef(1)
  const selected = changesets.find((cs) => cs.id === selectedId) ?? null
  const totalChanges = changesets.reduce((sum, cs) => sum + cs.changes.length, 0)

  const loadSchema = async (forceRefresh = false) => {
    setSchemaLoading(true)
    try {
      const response = await bridge.getSchemaWorkspace(forceRefresh)
      setSchema(response)
      setSelectedStoreId((current) => (
        current && response.stores.some((store) => store.id === current)
          ? current
          : response.stores[0]?.id ?? ''
      ))
    } catch {
      addToast('Schema workspace could not be loaded from the current project.', 'error')
    } finally {
      setSchemaLoading(false)
    }
  }

  useEffect(() => {
    void loadSchema()
    // The project bridge is stable for the lifetime of this designer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── Changeset ops ──────────────────────────────────────────────────────────

  const addChangeset = () => {
    const id = `changeset-${uid.current++}`
    setChangesets((prev) => [...prev, { id, comment: '', changes: [], autoRollback: true }])
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

  const stageDrifts = (drifts: SchemaDriftSnapshot[]) => {
    const stageable = drifts
      .map((drift) => ({
        drift,
        change: drift.suggestion ? migrationChangeFromSuggestion(drift.suggestion) : null,
      }))
      .filter((candidate): candidate is { drift: SchemaDriftSnapshot, change: MigrationChange } =>
        candidate.change != null,
      )
    if (!stageable.length) {
      addToast('These findings require review and do not have an automatic migration.', 'info')
      return
    }
    const first = stageable[0].drift
    const id = `sync-${first.tableName.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${uid.current++}`
    const generated: ChangeSet = {
      id,
      comment: stageable.length === 1
        ? `Resolve ${first.kind.toLowerCase().replace(/_/g, ' ')}`
        : `Synchronize ${first.tableName} with ${stageable.length} entity mapping differences`,
      changes: stageable.map((candidate) => candidate.change),
      autoRollback: stageable.every((candidate) => automaticRollback(candidate.change) != null),
      preConditions: stageable.flatMap((candidate) => {
        if (candidate.change.changeType !== 'renameColumn') return []
        return [
          {
            type: 'COLUMN_EXISTS',
            params: {
              tableName: candidate.change.tableName,
              columnName: candidate.change.oldColumnName,
            },
          },
          {
            type: 'COLUMN_NOT_EXISTS',
            params: {
              tableName: candidate.change.tableName,
              columnName: candidate.change.newColumnName,
            },
          },
        ]
      }),
    }
    setChangesets((current) => [...current, generated])
    setSelectedId(id)
    setSelectedStoreId(first.storeId)
    setSelectedEntityId(first.entityArtifactId)
    setActivePane('editor')
    addToast(
      `${stageable.length} schema ${stageable.length === 1 ? 'change' : 'changes'} staged for review.`,
      'success',
    )
  }

  const stageDrift = (drift: SchemaDriftSnapshot) => stageDrifts([drift])

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
        preConditions: cs.preConditions,
        changes: cs.changes,
        rollback: cs.autoRollback
          ? cs.changes.slice().reverse().map(automaticRollback).filter(Boolean)
          : [],
      })),
    }

    if (!selectedStoreId) {
      addToast('Select a resolved Jmix data store before creating a migration.', 'error')
      return
    }
    const change: SchemaMigrationChangeRequest = {
      storeId: selectedStoreId,
      migration: payload,
      fileName: payload.changelogId,
    }
    setIsGenerating(true)
    try {
      const preview = await bridge.previewSchemaMigration(change)
      if (!preview.accepted || !preview.planDigest) {
        addToast(preview.issues[0]?.message ?? 'The migration preview was rejected.', 'error')
        return
      }
      setPending({ change, preview })
      addToast('Source-safe migration preview is ready. Review the destination before applying.', 'info')
    } catch {
      addToast('Migration preview failed — bridge unavailable', 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const applyPending = async () => {
    if (!pending?.preview.planDigest) return
    setIsGenerating(true)
    try {
      const result = await bridge.applySchemaMigration(pending.change, pending.preview.planDigest)
      setLastResult({
        success: result.success,
        filesWritten: result.filesChanged,
        errors: result.issues.map((issue) => `${issue.code}: ${issue.message}`),
      })
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'Migration apply failed.', 'error')
        return
      }
      addToast(`Migration applied atomically — ${result.filesChanged.length} file(s) changed.`, 'success')
      setPending(null)
      await loadSchema(true)
    } catch {
      addToast('Migration apply failed — bridge unavailable', 'error')
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
              <div className="grid grid-cols-[minmax(90px,1fr)_128px_42px_34px_34px_24px] items-center gap-1 px-0.5 text-[9px] font-semibold uppercase tracking-wider text-gray-600">
                <span>Column</span><span>Type</span><span>Null?</span><span>UQ</span><span>PK</span><span />
              </div>
              {change.columns.map((col, ci) => (
                <div key={ci} className="grid grid-cols-[minmax(90px,1fr)_128px_42px_34px_34px_24px] items-center gap-1">
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
                  <label className="flex cursor-pointer items-center justify-center" title="Unique">
                    <input
                      type="checkbox"
                      checked={col.unique}
                      onChange={(e) => patchColumn(csId, index, ci, { unique: e.target.checked })}
                      className="h-3.5 w-3.5 accent-emerald-500"
                    />
                  </label>
                  <label className="flex cursor-pointer items-center justify-center" title="Primary key">
                    <input
                      type="checkbox"
                      checked={col.primaryKey}
                      onChange={(e) => patchColumn(csId, index, ci, {
                        primaryKey: e.target.checked,
                        unique: e.target.checked || col.unique,
                        nullable: e.target.checked ? false : col.nullable,
                      })}
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
                    columns: [
                      ...change.columns,
                      { name: '', type: 'VARCHAR(255)', nullable: true, unique: false, primaryKey: false },
                    ],
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
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
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

      case 'modifyColumn':
        return (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="Column Name">
              <input
                value={change.columnName}
                onChange={(e) => patchChange(csId, index, { columnName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="New Data Type">
              <input
                value={change.newDataType}
                onChange={(e) => patchChange(csId, index, { newDataType: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                list="jmix-sql-types"
              />
            </Field>
          </div>
        )

      case 'renameColumn':
        return (
          <div className="space-y-2">
            <div className="rounded border border-amber-500/30 bg-amber-500/5 p-2 text-[10px] leading-relaxed text-amber-100/80">
              Quarantine preserves every value under a deterministic retired name. The generated migration checks
              that the current column exists, checks that the retired name is free, and includes a reverse rename.
              Final deletion remains a later retention-policy decision.
            </div>
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
              <Field label="Table Name">
                <input
                  value={change.tableName}
                  onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                  className={`${inputSm} font-mono uppercase`}
                />
              </Field>
              <Field label="Current Column">
                <input
                  value={change.oldColumnName}
                  onChange={(e) => patchChange(csId, index, { oldColumnName: e.target.value.toUpperCase() })}
                  className={`${inputSm} font-mono uppercase`}
                />
              </Field>
              <Field label="Quarantine Column">
                <input
                  value={change.newColumnName}
                  onChange={(e) => patchChange(csId, index, { newColumnName: e.target.value.toUpperCase() })}
                  className={`${inputSm} font-mono uppercase`}
                />
              </Field>
            </div>
          </div>
        )

      case 'addUniqueConstraint':
        return (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="Constraint Name">
              <input
                value={change.constraintName}
                onChange={(e) => patchChange(csId, index, { constraintName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="Columns">
              <input
                value={change.columnNames.join(', ')}
                onChange={(e) => patchChange(csId, index, {
                  columnNames: e.target.value.split(',').map((value) => value.trim().toUpperCase()).filter(Boolean),
                })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
          </div>
        )

      case 'addNotNullConstraint':
      case 'dropNotNullConstraint':
        return (
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <Field label="Table Name">
              <input
                value={change.tableName}
                onChange={(e) => patchChange(csId, index, { tableName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="Column Name">
              <input
                value={change.columnName}
                onChange={(e) => patchChange(csId, index, { columnName: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
              />
            </Field>
            <Field label="Column Data Type">
              <input
                value={change.columnDataType}
                onChange={(e) => patchChange(csId, index, { columnDataType: e.target.value.toUpperCase() })}
                className={`${inputSm} font-mono uppercase`}
                list="jmix-sql-types"
              />
            </Field>
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

        <label className="flex min-w-48 items-center gap-1.5 text-[10px] uppercase tracking-wider text-gray-500">
          Data store
          <select
            value={selectedStoreId}
            onChange={(e) => setSelectedStoreId(e.target.value)}
            disabled={schemaLoading || !schema?.stores.length}
            className="min-w-0 flex-1 py-1 text-xs normal-case tracking-normal"
          >
            {!schema?.stores.length && <option value="">No resolved store</option>}
            {schema?.stores.map((store) => (
              <option key={store.id} value={store.id}>
                {store.name} · {store.moduleId}
              </option>
            ))}
          </select>
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
            Preview Migration
          </button>
        </div>
      </header>

      <div className="flex items-center gap-2 border-b border-surface-border bg-surface-light/50 px-2 py-1.5 lg:hidden">
        <button type="button" onClick={() => setActivePane('schema')} className={btnGhost}>
          <Boxes size={11} /> Project schema
        </button>
        <button type="button" onClick={() => setActivePane('changesets')} className={btnGhost}>
          <GitCommit size={11} /> Changesets
        </button>
        <button type="button" onClick={() => setActivePane('editor')} className={btnGhost}>
          <Database size={11} /> Editor
        </button>
      </div>

      {pending && (
        <div className="border-b border-amber-500/30 bg-amber-500/5 px-3 py-2">
          <div className="flex flex-wrap items-center gap-3">
            <ShieldCheck size={14} className="text-amber-300" />
            <div className="min-w-0 flex-1">
              <div className="truncate text-[11px] font-medium text-amber-200">{pending.preview.label}</div>
              <div className="truncate font-mono text-[9px] text-amber-100/60">
                {pending.preview.files.map((file) => file.relativePath).join(' · ')}
              </div>
            </div>
            <button type="button" onClick={() => setPending(null)} className={btnGhost}>Discard</button>
            <button type="button" onClick={() => void applyPending()} disabled={isGenerating} className={btnPrimary}>
              {isGenerating ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
              Apply atomic change
            </button>
          </div>
        </div>
      )}

      {/* Workspace */}
      <div className="relative flex min-h-0 flex-1 overflow-hidden lg:grid lg:grid-cols-[230px_220px_minmax(360px,1fr)] xl:grid-cols-[260px_240px_minmax(420px,1fr)]">
        {activePane !== 'editor' && (
          <button
            type="button"
            className="absolute inset-0 z-20 bg-black/55 lg:hidden"
            onClick={() => setActivePane('editor')}
            aria-label="Close migration side panel"
          />
        )}

        {/* Left: project-wide entity/schema context */}
        <aside
          aria-label="Project schema context"
          className={`${activePane === 'schema' ? 'flex' : 'hidden'} absolute inset-y-0 left-0 z-30 min-h-0 w-[min(92%,22rem)] flex-col border-r border-surface-border bg-surface-light shadow-2xl lg:static lg:z-auto lg:flex lg:w-auto lg:shadow-none`}
        >
          <div className="flex items-center border-b border-surface-border">
            <div className="flex min-w-0 flex-1 items-center gap-1.5 px-3 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
              <Boxes size={12} className="text-jmix-400" /> Project schema
            </div>
            <button
              type="button"
              onClick={() => void loadSchema(true)}
              className={btnIcon}
              title="Refresh entity and Liquibase graph"
              aria-label="Refresh entity and Liquibase graph"
            >
              <RefreshCw size={12} className={schemaLoading ? 'animate-spin' : ''} />
            </button>
            <button
              type="button"
              onClick={() => setActivePane('editor')}
              className={`${btnIcon} mr-2 lg:hidden`}
              aria-label="Close project schema"
            >
              <X size={12} />
            </button>
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto">
            <div className="border-b border-surface-border p-2">
              <div className="grid grid-cols-2 gap-1">
                <div className="rounded border border-surface-border bg-surface p-1.5 text-center">
                  <div className="text-sm font-semibold text-gray-200">{schema?.modules.length ?? 0}</div>
                  <div className="text-[8px] uppercase tracking-wider text-gray-600">modules</div>
                </div>
                <div className="rounded border border-surface-border bg-surface p-1.5 text-center">
                  <div className="text-sm font-semibold text-gray-200">{schema?.entities.length ?? 0}</div>
                  <div className="text-[8px] uppercase tracking-wider text-gray-600">entities</div>
                </div>
                <div className="rounded border border-surface-border bg-surface p-1.5 text-center">
                  <div className="text-sm font-semibold text-gray-200">{schema?.changelogs.length ?? 0}</div>
                  <div className="text-[8px] uppercase tracking-wider text-gray-600">logs</div>
                </div>
                <div className="rounded border border-surface-border bg-surface p-1.5 text-center">
                  <div className={`text-sm font-semibold ${
                    schema?.drifts.some((drift) => drift.severity === 'ERROR')
                      ? 'text-red-300'
                      : schema?.drifts.length
                        ? 'text-amber-300'
                        : 'text-emerald-300'
                  }`}>
                    {schema?.drifts.length ?? 0}
                  </div>
                  <div className="text-[8px] uppercase tracking-wider text-gray-600">drifts</div>
                </div>
              </div>
            </div>

            <div className="border-b border-surface-border">
              <div className="px-3 pb-1 pt-2 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                Data stores
              </div>
              <div className="space-y-1 p-2 pt-1">
                {schema?.stores.map((store) => (
                  <button
                    type="button"
                    key={store.id}
                    onClick={() => setSelectedStoreId(store.id)}
                    className={`w-full rounded border p-2 text-left ${
                      selectedStoreId === store.id
                        ? 'border-jmix-500/60 bg-jmix-500/10'
                        : 'border-surface-border bg-surface hover:border-gray-500'
                    }`}
                  >
                    <div className="flex items-center gap-1.5">
                      <Database size={11} className="text-jmix-400" />
                      <span className="text-[10px] font-medium text-gray-300">{store.name}</span>
                      <span className="ml-auto text-[8px] text-gray-600">{store.includeMode.replace(/_/g, ' ')}</span>
                    </div>
                    <div className="mt-1 truncate font-mono text-[8px] text-gray-600">
                      {store.rootChangelogPath ?? 'root changelog unresolved'}
                    </div>
                  </button>
                ))}
                {!schemaLoading && !schema?.stores.length && (
                  <div className="rounded border border-red-500/30 bg-red-500/5 p-2 text-[9px] text-red-300">
                    No configured Liquibase root was resolved. Generation is blocked to avoid writing into the wrong module.
                  </div>
                )}
              </div>
            </div>

            <div className="border-b border-surface-border">
              <div className="px-3 pb-1 pt-2 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                Entities and source coverage
              </div>
              <div className="space-y-1 p-2 pt-1">
                {schema?.entities.map((entity) => (
                  <button
                    type="button"
                    key={entity.artifactId}
                    onClick={() => setSelectedEntityId(entity.artifactId)}
                    onDoubleClick={() => void bridge.navigateToSource(entity.sourceLocator)}
                    className={`w-full rounded border p-2 text-left ${
                      selectedEntityId === entity.artifactId
                        ? 'border-jmix-500/60 bg-jmix-500/10'
                        : 'border-surface-border bg-surface hover:border-gray-500'
                    }`}
                  >
                    <div className="flex items-center gap-1.5">
                      <Table2 size={11} className="text-gray-500" />
                      <span className="min-w-0 flex-1 truncate text-[10px] text-gray-300">{entity.className}</span>
                      <span className={`h-1.5 w-1.5 rounded-full ${
                        entity.migrationCoverage === 'COVERED'
                          ? 'bg-emerald-400'
                          : entity.migrationCoverage === 'DISABLED'
                            ? 'bg-gray-500'
                            : 'bg-amber-400'
                      }`} />
                    </div>
                    <div className="mt-1 flex items-center justify-between gap-2 text-[8px] text-gray-600">
                      <span className="truncate font-mono">{entity.tableName}</span>
                      <span>{entity.moduleId}</span>
                    </div>
                  </button>
                ))}
              </div>
            </div>

            <div className="border-b border-surface-border">
              <div className="flex items-center gap-1.5 px-3 pb-1 pt-2 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                <RefreshCw size={10} /> Entity ↔ Liquibase drift
                <span className="ml-auto">{schema?.drifts.length ?? 0}</span>
              </div>
              <div className="space-y-1.5 p-2 pt-1">
                {schema?.drifts.slice(0, 60).map((drift) => (
                  <div
                    key={drift.id}
                    className={`rounded border p-2 ${
                      drift.severity === 'ERROR'
                        ? 'border-red-500/30 bg-red-500/5'
                        : drift.severity === 'WARNING'
                          ? 'border-amber-500/30 bg-amber-500/5'
                          : 'border-surface-border bg-surface'
                    }`}
                  >
                    <button
                      type="button"
                      onClick={() => setSelectedEntityId(drift.entityArtifactId)}
                      className="w-full text-left"
                    >
                      <div className="flex items-center gap-1 text-[8px] font-semibold uppercase tracking-wider text-gray-500">
                        <span>{drift.kind.replace(/_/g, ' ')}</span>
                        <span className="ml-auto">{drift.confidence.toLowerCase()}</span>
                      </div>
                      <div className="mt-1 text-[9px] leading-relaxed text-gray-400">{drift.message}</div>
                    </button>
                    <div className="mt-1.5 flex items-center justify-between gap-2">
                      <span className={`text-[8px] uppercase tracking-wider ${
                        drift.safety === 'SAFE'
                          ? 'text-emerald-400'
                          : drift.safety === 'DATA_CHECK_REQUIRED'
                            ? 'text-amber-300'
                            : 'text-gray-500'
                      }`}>
                        {drift.safety.replace(/_/g, ' ')}
                      </span>
                      {drift.suggestion && (
                        <button
                          type="button"
                          onClick={() => stageDrift(drift)}
                          className="rounded border border-jmix-500/40 bg-jmix-500/10 px-2 py-1 text-[8px] font-medium text-jmix-300 hover:bg-jmix-500/20"
                        >
                          Stage migration
                        </button>
                      )}
                    </div>
                  </div>
                ))}
                {!schemaLoading && !schema?.drifts.length && (
                  <div className="rounded border border-emerald-500/25 bg-emerald-500/5 p-2 text-[9px] leading-relaxed text-emerald-300/80">
                    Entity mappings and the indexed Liquibase chain are synchronized.
                  </div>
                )}
              </div>
            </div>

            <div>
              <div className="flex items-center gap-1.5 px-3 pb-1 pt-2 text-[9px] font-semibold uppercase tracking-widest text-gray-600">
                <AlertTriangle size={10} /> Production findings
                <span className="ml-auto">{schema?.findings.length ?? 0}</span>
              </div>
              <div className="space-y-1 p-2 pt-1">
                {schema?.findings.map((finding, index) => (
                  <button
                    type="button"
                    key={`${finding.code}-${index}`}
                    onClick={() => finding.sourceLocator && void bridge.navigateToSource(finding.sourceLocator)}
                    className={`w-full rounded border p-2 text-left ${
                      finding.severity === 'ERROR'
                        ? 'border-red-500/30 bg-red-500/5'
                        : finding.severity === 'WARNING'
                          ? 'border-amber-500/30 bg-amber-500/5'
                          : 'border-surface-border bg-surface'
                    }`}
                  >
                    <div className="text-[8px] font-semibold uppercase tracking-wider text-gray-500">{finding.code}</div>
                    <div className="mt-1 text-[9px] leading-relaxed text-gray-400">{finding.message}</div>
                  </button>
                ))}
              </div>
            </div>
          </div>
        </aside>

        {/* Middle: changeset list */}
        <aside className={`${activePane === 'changesets' ? 'flex' : 'hidden'} absolute inset-y-0 left-0 z-30 min-h-0 w-[min(90%,20rem)] flex-col border-r border-surface-border bg-surface-light shadow-2xl lg:static lg:z-auto lg:flex lg:w-auto lg:shadow-none`}>
          <div className="flex items-center gap-1.5 border-b border-surface-border px-3 py-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">
            <GitCommit size={12} className="text-jmix-400" /> Changesets
            <button
              type="button"
              onClick={() => setActivePane('editor')}
              className={`${btnIcon} ml-auto lg:hidden`}
              aria-label="Close changesets"
            >
              <X size={12} />
            </button>
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
        <section className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden lg:col-start-3">
          {selectedEntityId && schema?.entities.find((entity) => entity.artifactId === selectedEntityId) && (() => {
            const entity = schema.entities.find((candidate) => candidate.artifactId === selectedEntityId)!
            const entityDrifts = schema.drifts.filter((drift) => drift.entityArtifactId === entity.artifactId)
            const stageableDrifts = entityDrifts.filter((drift) => drift.suggestion)
            return (
              <div className="border-b border-surface-border bg-surface-light/70 px-3 py-2">
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <FileCode2 size={12} className="text-jmix-400" />
                  <span className="text-[10px] font-medium text-gray-300">{entity.qualifiedName}</span>
                  <span className="rounded border border-surface-border bg-surface px-1.5 py-0.5 font-mono text-[9px] text-gray-500">
                    {entity.tableName}
                  </span>
                  <span className="text-[9px] text-gray-600">
                    {entity.attributes.length} fields · {entity.storeName} store · {entity.ddlMode.replace(/_/g, ' ')}
                  </span>
                  <span className={`rounded border px-1.5 py-0.5 text-[9px] ${
                    entityDrifts.length
                      ? 'border-amber-500/30 bg-amber-500/5 text-amber-300'
                      : 'border-emerald-500/25 bg-emerald-500/5 text-emerald-300'
                  }`}>
                    {entityDrifts.length ? `${entityDrifts.length} schema drift${entityDrifts.length === 1 ? '' : 's'}` : 'schema synchronized'}
                  </span>
                  {stageableDrifts.length > 0 && (
                    <button
                      type="button"
                      onClick={() => stageDrifts(stageableDrifts)}
                      className={btnPrimary}
                    >
                      <RefreshCw size={11} />
                      Stage suggested changes
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => void bridge.navigateToSource(entity.sourceLocator)}
                    className={btnGhost}
                  >
                    Open entity source
                  </button>
                </div>
              </div>
            )
          })()}
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
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-[220px_1fr_auto]">
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
                <label className="flex cursor-pointer items-end gap-1.5 pb-1.5 text-[10px] text-gray-400">
                  <input
                    type="checkbox"
                    checked={selected.autoRollback}
                    onChange={(e) => updateChangeset(selected.id, { autoRollback: e.target.checked })}
                    className="h-3.5 w-3.5 accent-emerald-500"
                  />
                  Generate rollback
                </label>
              </div>
              {selected.autoRollback && selected.changes.some((change) => automaticRollback(change) == null) && (
                <div className="rounded border border-amber-500/30 bg-amber-500/5 p-2 text-[9px] leading-relaxed text-amber-200/80">
                  Some destructive or data-dependent operations cannot be reversed automatically. Add a reviewed,
                  database-scoped replacement operation before production deployment.
                </div>
              )}

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
