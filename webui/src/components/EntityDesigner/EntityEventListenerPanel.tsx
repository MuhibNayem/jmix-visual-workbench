import { useEffect, useMemo, useState } from 'react'
import { bridge } from '../../bridge'
import type {
  EntityEventListenerEvent,
  EntityEventListenerRequest,
  EntitySourceLanguage,
  GraphArtifact,
  SchemaEntitySnapshot,
  WorkspaceChangePreviewResponse,
} from '../../types'

const EVENT_OPTIONS: {
  value: EntityEventListenerEvent
  title: string
  phase: string
  description: string
}[] = [
  {
    value: 'ENTITY_SAVING',
    title: 'Before store',
    phase: 'Current transaction',
    description: 'Set reviewed defaults or transient values before Jmix stores the entity.',
  },
  {
    value: 'ENTITY_LOADING',
    title: 'During load',
    phase: 'Loading pipeline',
    description: 'Initialize transient state. Relationships may not be available at this stage.',
  },
  {
    value: 'ENTITY_CHANGED_BEFORE_COMMIT',
    title: 'Before commit',
    phase: 'Current transaction',
    description: 'React to create, update, or delete while the original transaction can still roll back.',
  },
  {
    value: 'ENTITY_CHANGED_AFTER_COMMIT',
    title: 'After commit',
    phase: 'Committed state',
    description: 'Publish notifications or external side effects only after the database commit succeeds.',
  },
]

interface EntityEventListenerPanelProps {
  entity: SchemaEntitySnapshot
  listeners: GraphArtifact[]
  onOpenSource: (artifact: GraphArtifact) => Promise<void>
  onApplied: (createdPath: string) => Promise<void>
  addToast: (message: string, type: 'success' | 'error' | 'info') => void
}

export default function EntityEventListenerPanel({
  entity,
  listeners,
  onOpenSource,
  onApplied,
  addToast,
}: EntityEventListenerPanelProps) {
  const [editing, setEditing] = useState(false)
  const [className, setClassName] = useState(`${entity.className}EventListener`)
  const [packageName, setPackageName] = useState(defaultListenerPackage(entity.qualifiedName))
  const [sourceLanguage, setSourceLanguage] = useState<EntitySourceLanguage>(
    entity.sourceLocator.relativePath.endsWith('.kt') ? 'kotlin' : 'java',
  )
  const [events, setEvents] = useState<EntityEventListenerEvent[]>([
    'ENTITY_CHANGED_BEFORE_COMMIT',
  ])
  const [afterCommitRequiresNewTransaction, setAfterCommitRequiresNewTransaction] =
    useState(false)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setEditing(false)
    setClassName(`${entity.className}EventListener`)
    setPackageName(defaultListenerPackage(entity.qualifiedName))
    setSourceLanguage(entity.sourceLocator.relativePath.endsWith('.kt') ? 'kotlin' : 'java')
    setEvents(['ENTITY_CHANGED_BEFORE_COMMIT'])
    setAfterCommitRequiresNewTransaction(false)
    setPreview(null)
  }, [entity.className, entity.qualifiedName, entity.sourceLocator.relativePath])

  const listenerFiles = useMemo(() => {
    const grouped = new Map<string, GraphArtifact[]>()
    listeners.forEach(listener => {
      const path = listener.sourceLocator.relativePath
      grouped.set(path, [...(grouped.get(path) ?? []), listener])
    })
    return [...grouped.entries()]
      .map(([path, methods]) => ({
        path,
        methods: methods.sort((left, right) => left.displayName.localeCompare(right.displayName)),
      }))
      .sort((left, right) => left.path.localeCompare(right.path))
  }, [listeners])

  const request = (): EntityEventListenerRequest => ({
    entitySource: entity.sourceLocator,
    className: className.trim(),
    packageName: packageName.trim(),
    sourceLanguage,
    events,
    afterCommitRequiresNewTransaction,
  })

  const toggleEvent = (event: EntityEventListenerEvent) => {
    setEvents(current =>
      current.includes(event)
        ? current.filter(item => item !== event)
        : [...current, event],
    )
    if (event === 'ENTITY_CHANGED_AFTER_COMMIT' && events.includes(event)) {
      setAfterCommitRequiresNewTransaction(false)
    }
    setPreview(null)
  }

  const handlePreview = async () => {
    setBusy(true)
    setPreview(null)
    try {
      const result = await bridge.previewEntityEventListener(request())
      setPreview(result)
      if (!result.accepted) {
        addToast(
          result.issues[0]?.message ?? 'The listener could not be previewed.',
          'error',
        )
      }
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Listener preview failed.', 'error')
    } finally {
      setBusy(false)
    }
  }

  const handleApply = async () => {
    if (!preview?.accepted || !preview.planDigest) return
    setBusy(true)
    try {
      const createdPath = preview.files[0]?.relativePath
      const result = await bridge.applyEntityEventListener(request(), preview.planDigest)
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'The listener was not created.', 'error')
        return
      }
      addToast(`${className.trim()} was created and added to the entity impact graph.`, 'success')
      setEditing(false)
      setPreview(null)
      if (createdPath) {
        try {
          await onApplied(createdPath)
        } catch {
          addToast(
            'The listener was created, but the refreshed source location is not ready yet. Refresh the entity impact graph to open it.',
            'info',
          )
        }
      }
    } catch (error) {
      addToast(error instanceof Error ? error.message : 'Listener creation failed.', 'error')
    } finally {
      setBusy(false)
    }
  }

  const inputValid =
    /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(className.trim()) &&
    /^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*$/.test(packageName.trim()) &&
    events.length > 0

  return (
    <section className="mt-5 min-w-0 overflow-hidden rounded-xl border border-violet-500/25 bg-gradient-to-br from-violet-500/[0.07] to-surface">
      <div className="flex min-w-0 flex-col gap-3 border-b border-violet-500/15 p-3 sm:flex-row sm:items-start sm:justify-between sm:p-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-xs font-semibold text-violet-100">Jmix data event listeners</h3>
            <span className="rounded bg-violet-500/10 px-2 py-0.5 text-[9px] text-violet-200">
              {listenerFiles.length} source file{listenerFiles.length === 1 ? '' : 's'}
            </span>
          </div>
          <p className="mt-1 max-w-3xl text-[10px] leading-relaxed text-gray-500">
            Create Spring/Jmix entity handlers with visible transaction semantics. These are separate from JPA
            lifecycle callbacks and <code className="text-gray-400">@EntityListeners</code>.
          </p>
        </div>
        <button
          type="button"
          onClick={() => {
            setEditing(current => !current)
            setPreview(null)
          }}
          className="w-full shrink-0 rounded border border-violet-500/35 bg-violet-500/10 px-3 py-1.5 text-[10px] font-medium text-violet-100 hover:bg-violet-500/20 sm:w-auto"
        >
          {editing ? 'Close creator' : 'Create listener'}
        </button>
      </div>

      {listenerFiles.length > 0 && (
        <div className="grid min-w-0 gap-2 border-b border-violet-500/10 p-3 sm:grid-cols-2 sm:p-4 xl:grid-cols-3">
          {listenerFiles.map(listener => (
            <div
              key={listener.path}
              className="min-w-0 rounded-lg border border-surface-border bg-black/10 p-2.5"
            >
              <div className="truncate font-mono text-[9px] text-gray-300" title={listener.path}>
                {listener.path.substring(listener.path.lastIndexOf('/') + 1)}
              </div>
              <div className="mt-1 text-[9px] leading-relaxed text-gray-600">
                {listener.methods.map(method => method.displayName).join(' · ')}
              </div>
              <button
                type="button"
                onClick={() => void onOpenSource(listener.methods[0])}
                className="mt-2 w-full rounded border border-surface-border px-2 py-1 text-[9px] text-gray-400 hover:border-violet-500/35 hover:text-violet-100 sm:w-auto"
              >
                Open listener source
              </button>
            </div>
          ))}
        </div>
      )}

      {editing && (
        <div className="min-w-0 p-3 sm:p-4">
          <div className="grid min-w-0 gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(16rem,0.7fr)]">
            <div className="min-w-0 space-y-3">
              <div className="grid min-w-0 gap-2 sm:grid-cols-2">
                <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
                  Listener class
                  <input
                    value={className}
                    maxLength={120}
                    disabled={busy}
                    onChange={event => {
                      setClassName(event.target.value)
                      setPreview(null)
                    }}
                    className="mt-1 w-full min-w-0 font-mono"
                  />
                </label>
                <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
                  Source language
                  <select
                    value={sourceLanguage}
                    disabled={busy}
                    onChange={event => {
                      setSourceLanguage(event.target.value as EntitySourceLanguage)
                      setPreview(null)
                    }}
                    className="mt-1 w-full min-w-0"
                  >
                    <option value="java">Java</option>
                    <option value="kotlin">Kotlin</option>
                  </select>
                </label>
                <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600 sm:col-span-2">
                  Package
                  <input
                    value={packageName}
                    maxLength={300}
                    disabled={busy}
                    onChange={event => {
                      setPackageName(event.target.value)
                      setPreview(null)
                    }}
                    className="mt-1 w-full min-w-0 font-mono"
                  />
                </label>
              </div>

              <div className="grid min-w-0 gap-2 sm:grid-cols-2">
                {EVENT_OPTIONS.map(option => {
                  const active = events.includes(option.value)
                  return (
                    <label
                      key={option.value}
                      className={`min-w-0 cursor-pointer rounded-lg border p-2.5 ${
                        active
                          ? 'border-violet-500/35 bg-violet-500/10'
                          : 'border-surface-border bg-black/10'
                      }`}
                    >
                      <div className="flex min-w-0 items-start gap-2">
                        <input
                          type="checkbox"
                          checked={active}
                          disabled={busy}
                          onChange={() => toggleEvent(option.value)}
                          className="mt-0.5 shrink-0"
                        />
                        <span className="min-w-0">
                          <span className="block text-[10px] font-medium text-gray-200">
                            {option.title}
                          </span>
                          <span className="mt-0.5 block text-[8px] uppercase tracking-wider text-violet-300/70">
                            {option.phase}
                          </span>
                          <span className="mt-1 block text-[9px] leading-relaxed text-gray-600">
                            {option.description}
                          </span>
                        </span>
                      </div>
                    </label>
                  )
                })}
              </div>

              {events.includes('ENTITY_CHANGED_AFTER_COMMIT') && (
                <label className="flex min-w-0 items-start gap-2 rounded-lg border border-amber-500/25 bg-amber-500/[0.06] p-2.5">
                  <input
                    type="checkbox"
                    checked={afterCommitRequiresNewTransaction}
                    disabled={busy}
                    onChange={event => {
                      setAfterCommitRequiresNewTransaction(event.target.checked)
                      setPreview(null)
                    }}
                    className="mt-0.5 shrink-0"
                  />
                  <span className="min-w-0">
                    <span className="block text-[10px] font-medium text-amber-100">
                      Open a new transaction for after-commit data access
                    </span>
                    <span className="mt-1 block text-[9px] leading-relaxed text-gray-600">
                      Generates <code>REQUIRES_NEW</code>. Leave this off for notifications or external messages
                      that do not load or save Jmix data.
                    </span>
                  </span>
                </label>
              )}
            </div>

            <div className="min-w-0 rounded-lg border border-surface-border bg-black/15 p-3">
              <div className="text-[9px] font-medium uppercase tracking-wider text-gray-500">
                Atomic source preview
              </div>
              {preview?.accepted && preview.files[0] ? (
                <>
                  <div
                    className="mt-2 break-all font-mono text-[9px] text-violet-200"
                    title={preview.files[0].relativePath}
                  >
                    {preview.files[0].relativePath}
                  </div>
                  <pre className="mt-2 max-h-72 min-w-0 overflow-auto whitespace-pre-wrap break-words rounded bg-black/20 p-2 text-[9px] leading-relaxed text-gray-400">
                    {preview.files[0].resultContent}
                  </pre>
                </>
              ) : (
                <div className="mt-3 rounded border border-dashed border-surface-border p-4 text-center text-[9px] leading-relaxed text-gray-600">
                  Preview derives the entity name and module from the exact indexed source. Existing files are never
                  overwritten.
                </div>
              )}
              {preview && !preview.accepted && (
                <div className="mt-2 rounded border border-red-500/25 bg-red-500/[0.06] p-2 text-[9px] text-red-200">
                  {preview.issues[0]?.message ?? 'Listener creation was rejected.'}
                </div>
              )}
              <div className="mt-3 flex min-w-0 flex-col gap-2 sm:flex-row sm:justify-end">
                <button
                  type="button"
                  disabled={busy || !inputValid}
                  onClick={() => void handlePreview()}
                  className="rounded border border-surface-border px-3 py-1.5 text-[10px] text-gray-300 hover:border-violet-500/35 disabled:opacity-40"
                >
                  {busy ? 'Checking…' : 'Preview source'}
                </button>
                <button
                  type="button"
                  disabled={busy || !preview?.accepted || !preview.planDigest}
                  onClick={() => void handleApply()}
                  className="rounded bg-violet-600 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-violet-500 disabled:opacity-40"
                >
                  Create atomically
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}

function defaultListenerPackage(qualifiedEntityName: string): string {
  const separator = qualifiedEntityName.lastIndexOf('.')
  if (separator < 0) return 'listener'
  const entityPackage = qualifiedEntityName.slice(0, separator)
  return entityPackage.endsWith('.entity')
    ? `${entityPackage.substring(0, entityPackage.length - '.entity'.length)}.listener`
    : `${entityPackage}.listener`
}
