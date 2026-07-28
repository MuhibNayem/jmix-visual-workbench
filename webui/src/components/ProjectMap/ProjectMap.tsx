import { useEffect, useMemo, useState } from 'react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'
import type {
  ApplicationGraphResponse,
  GraphArtifact,
  GraphDiagnostic,
  GraphRelationship,
} from '../../types'

type Group =
  | 'ALL'
  | 'DATA'
  | 'SCREENS'
  | 'SERVICES'
  | 'REST'
  | 'WORKFLOW'
  | 'SECURITY'
  | 'DATABASE'
  | 'CONFIG'

const GROUPS: { id: Group; label: string; kinds?: string[] }[] = [
  { id: 'ALL', label: 'All' },
  {
    id: 'DATA',
    label: 'Data',
    kinds: [
      'ENTITY', 'ENTITY_ATTRIBUTE', 'DTO', 'ENUM', 'REPOSITORY', 'DATA_CONTAINER', 'DATA_LOADER',
      'FETCH_PLAN', 'JPQL_QUERY', 'QUERY_PARAMETER',
    ],
  },
  {
    id: 'SCREENS',
    label: 'Screens',
    kinds: [
      'VIEW_DESCRIPTOR', 'VIEW_CONTROLLER', 'VIEW_ROUTE', 'VIEW_HANDLER', 'UI_COMPONENT', 'UI_ACTION',
      'MENU_SOURCE', 'MENU_ITEM',
    ],
  },
  {
    id: 'SERVICES',
    label: 'Services',
    kinds: ['SOURCE_TYPE', 'SERVICE', 'SERVICE_METHOD', 'VALIDATOR', 'EVENT_LISTENER', 'SCHEDULED_JOB'],
  },
  {
    id: 'REST',
    label: 'REST',
    kinds: [
      'REST_CONTROLLER', 'REST_ENDPOINT', 'REST_SERVICE_CONFIG', 'REST_SERVICE_METHOD',
      'REST_QUERY_CONFIG', 'REST_QUERY', 'CONTRACT_PARAMETER',
    ],
  },
  { id: 'WORKFLOW', label: 'Workflow', kinds: ['WORKFLOW_PROCESS', 'WORKFLOW_STATE'] },
  { id: 'SECURITY', label: 'Security', kinds: ['RESOURCE_ROLE', 'ROW_ROLE', 'SECURITY_POLICY'] },
  {
    id: 'DATABASE',
    label: 'Database',
    kinds: [
      'LIQUIBASE_ROOT', 'LIQUIBASE_INCLUDE', 'LIQUIBASE_CHANGESET', 'SCHEMA_OBJECT',
      'DATABASE_OPERATION', 'DATA_STORE',
    ],
  },
  {
    id: 'CONFIG',
    label: 'Config',
    kinds: ['CONFIGURATION_FILE', 'CONFIGURATION_PROPERTY', 'MESSAGE_BUNDLE', 'MESSAGE_KEY'],
  },
]

const MAX_VISIBLE_ARTIFACTS = 250

function readable(value: string): string {
  return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (character: string) => character.toUpperCase())
}

function severityClass(severity: GraphDiagnostic['severity']): string {
  switch (severity) {
    case 'BLOCKING':
    case 'ERROR':
      return 'border-red-500/40 bg-red-500/10 text-red-200'
    case 'WARNING':
      return 'border-amber-500/40 bg-amber-500/10 text-amber-100'
    default:
      return 'border-sky-500/30 bg-sky-500/10 text-sky-100'
  }
}

export default function ProjectMap() {
  const openFlowUiDesigner = useStore((state) => state.openFlowUiDesigner)
  const [graph, setGraph] = useState<ApplicationGraphResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [group, setGroup] = useState<Group>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [activePane, setActivePane] = useState<'artifacts' | 'impact'>('artifacts')

  const load = async (forceRefresh: boolean = false) => {
    setLoading(true)
    setError(null)
    try {
      const response = await bridge.getApplicationGraph(forceRefresh)
      if (response.error) {
        setError(response.error)
        return
      }
      setGraph(response)
      setSelectedId((current) => (
        current && response.artifacts.some((artifact) => artifact.id === current)
          ? current
          : response.artifacts[0]?.id ?? null
      ))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Application graph request failed.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const artifactsById = useMemo(
    () => new Map(graph?.artifacts.map((artifact) => [artifact.id, artifact]) ?? []),
    [graph],
  )
  const selected = selectedId ? artifactsById.get(selectedId) ?? null : null
  const selectedGroup = GROUPS.find((candidate) => candidate.id === group)
  const normalizedQuery = query.trim().toLowerCase()
  const filtered = useMemo(() => {
    if (!graph) return []
    return graph.artifacts.filter((artifact) => {
      const groupMatches = !selectedGroup?.kinds || selectedGroup.kinds.includes(artifact.kind)
      const queryMatches = !normalizedQuery || [
        artifact.displayName,
        artifact.semanticKey,
        artifact.kind,
        artifact.owner.moduleId,
        artifact.sourceLocator.relativePath,
      ].some((value) => value.toLowerCase().includes(normalizedQuery))
      return groupMatches && queryMatches
    })
  }, [graph, normalizedQuery, selectedGroup])
  const outgoing = graph?.relationships.filter((relationship) => relationship.sourceArtifactId === selectedId) ?? []
  const incoming = graph?.relationships.filter((relationship) => relationship.targetArtifactId === selectedId) ?? []
  const selectedDiagnostics = graph?.diagnostics.filter((diagnostic) => (
    diagnostic.sourceLocator?.relativePath === selected?.sourceLocator.relativePath
  )) ?? []

  return (
    <section className="flex min-h-0 flex-1 flex-col bg-surface" aria-label="Connected application map">
      <header className="border-b border-surface-border bg-surface-light px-5 py-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold text-gray-100">Connected Application Map</h2>
            <p className="mt-1 max-w-3xl text-xs text-gray-400">
              Screens, bindings, entities, services, REST, security, workflow, schedules, and database evolution
              from the current IntelliJ project model.
            </p>
          </div>
          <button
            type="button"
            onClick={() => void load(true)}
            disabled={loading}
            className="rounded border border-jmix-500/50 bg-jmix-500/10 px-3 py-1.5 text-xs font-medium text-jmix-300 hover:bg-jmix-500/20 disabled:opacity-50"
          >
            {loading ? 'Indexing…' : 'Refresh graph'}
          </button>
        </div>

        {graph && (
          <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-6">
            <Metric label="Artifacts" value={graph.summary.artifactCount} />
            <Metric label="Relationships" value={graph.summary.relationshipCount} />
            <Metric label="Diagnostics" value={graph.summary.diagnosticCount} />
            <Metric label="Unresolved" value={graph.summary.unresolvedRelationshipCount} warning />
            <Metric label="Files indexed" value={graph.scannedFiles} />
            <Metric label={graph.cacheHit ? 'Cache hit' : 'Index time'} value={graph.cacheHit ? 'Yes' : `${graph.durationMillis} ms`} />
          </div>
        )}
      </header>

      {error && (
        <div className="m-4 rounded border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-200" role="alert">
          {error}
        </div>
      )}

      {!error && loading && !graph && (
        <div className="flex flex-1 items-center justify-center text-sm text-gray-400">
          Building the module-aware application graph…
        </div>
      )}

      {!error && graph && (
        <>
          <div className="flex flex-wrap items-center gap-2 border-b border-surface-border px-4 py-3">
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search name, source, module, or kind"
              aria-label="Search application artifacts"
              className="min-w-[16rem] flex-1 rounded border border-surface-border bg-surface px-3 py-2 text-xs text-gray-200 outline-none focus:border-jmix-500"
            />
            <div className="flex flex-wrap gap-1" aria-label="Artifact groups">
              {GROUPS.map((candidate) => (
                <button
                  key={candidate.id}
                  type="button"
                  onClick={() => setGroup(candidate.id)}
                  aria-pressed={group === candidate.id}
                  className={`rounded px-2.5 py-1.5 text-[11px] ${
                    group === candidate.id
                      ? 'bg-jmix-500 text-white'
                      : 'bg-surface-lighter text-gray-400 hover:text-gray-200'
                  }`}
                >
                  {candidate.label}
                </button>
              ))}
            </div>
          </div>

          <ResponsivePaneSwitcher
            value={activePane}
            onChange={setActivePane}
            label="Application map panels"
            options={[
              { id: 'artifacts', label: 'Artifacts', badge: filtered.length },
              { id: 'impact', label: 'Impact', badge: outgoing.length + incoming.length },
            ]}
          />

          <div className="flex min-h-0 flex-1 overflow-hidden">
            <div className={`${activePane === 'artifacts' ? 'block' : 'hidden'} min-h-0 w-full overflow-auto ${
              selected ? 'min-[1200px]:w-[42%] min-[1200px]:shrink-0 min-[1200px]:border-r min-[1200px]:border-surface-border' : ''
            } min-[1200px]:block`}>
              <div className="sticky top-0 z-10 border-b border-surface-border bg-surface/95 px-4 py-2 text-[11px] text-gray-500 backdrop-blur">
                Showing {Math.min(filtered.length, MAX_VISIBLE_ARTIFACTS).toLocaleString()} of {filtered.length.toLocaleString()} matches
                {filtered.length > MAX_VISIBLE_ARTIFACTS && ' — refine the search to see more'}
              </div>
              {filtered.slice(0, MAX_VISIBLE_ARTIFACTS).map((artifact) => (
                <ArtifactRow
                  key={artifact.id}
                  artifact={artifact}
                  active={artifact.id === selectedId}
                  onSelect={() => {
                    setSelectedId(artifact.id)
                    setActivePane('impact')
                  }}
                />
              ))}
              {filtered.length === 0 && (
                <div className="flex min-h-[28rem] items-center justify-center p-8 text-center text-xs text-gray-500">
                  <div>
                    <div className="text-sm font-medium text-gray-400">No matching artifacts</div>
                    <p className="mt-1 max-w-md leading-relaxed">
                      Clear the search or refresh the graph after Jmix modules finish importing.
                    </p>
                  </div>
                </div>
              )}
            </div>

            {selected && (
              <div className={`${activePane === 'impact' ? 'block' : 'hidden'} min-h-0 min-w-0 flex-1 overflow-auto p-3 sm:p-5 min-[1200px]:block`}>
                <ArtifactInspector
                  artifact={selected}
                  outgoing={outgoing}
                  incoming={incoming}
                  diagnostics={selectedDiagnostics}
                  artifactsById={artifactsById}
                  onNavigate={setSelectedId}
                  onOpenDesigner={() => openFlowUiDesigner(selected.sourceLocator)}
                />
              </div>
            )}
          </div>

          <footer className="border-t border-surface-border px-4 py-2 text-[10px] text-gray-600">
            Snapshot {graph.snapshotDigest.slice(0, 12)} · {graph.candidateFiles.toLocaleString()} candidates ·{' '}
            {graph.reusedFiles.toLocaleString()} unchanged sources reused · {graph.changedFiles.toLocaleString()} sources reread ·{' '}
            {graph.excludedFiles.toLocaleString()} excluded by safety/size policy
          </footer>
        </>
      )}
    </section>
  )
}

function Metric({ label, value, warning = false }: { label: string; value: string | number; warning?: boolean }) {
  return (
    <div className="rounded border border-surface-border bg-surface px-3 py-2">
      <div className={`text-base font-semibold ${warning && Number(value) > 0 ? 'text-amber-300' : 'text-gray-100'}`}>
        {typeof value === 'number' ? value.toLocaleString() : value}
      </div>
      <div className="text-[10px] uppercase tracking-wide text-gray-500">{label}</div>
    </div>
  )
}

function ArtifactRow({ artifact, active, onSelect }: {
  artifact: GraphArtifact
  active: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`w-full border-b border-surface-border px-4 py-3 text-left ${
        active ? 'bg-jmix-500/15' : 'hover:bg-surface-light'
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-medium text-gray-200">{artifact.displayName}</div>
          <div className="mt-0.5 truncate text-[11px] text-gray-500">{artifact.sourceLocator.relativePath}</div>
        </div>
        <span className="shrink-0 rounded bg-surface-lighter px-2 py-0.5 text-[9px] text-gray-400">
          {readable(artifact.kind)}
        </span>
      </div>
      <div className="mt-1 text-[10px] text-gray-600">{artifact.owner.moduleId} · {artifact.owner.sourceSetId ?? 'unknown source set'}</div>
    </button>
  )
}

function ArtifactInspector({
  artifact,
  outgoing,
  incoming,
  diagnostics,
  artifactsById,
  onNavigate,
  onOpenDesigner,
}: {
  artifact: GraphArtifact
  outgoing: GraphRelationship[]
  incoming: GraphRelationship[]
  diagnostics: GraphDiagnostic[]
  artifactsById: Map<string, GraphArtifact>
  onNavigate: (id: string) => void
  onOpenDesigner: () => void
}) {
  const [navigationMessage, setNavigationMessage] = useState<string | null>(null)
  const openSource = async () => {
    const response = await bridge.navigateToSource(artifact.sourceLocator)
    setNavigationMessage(response.success ? null : `${response.errorCode ?? 'Navigation failed'}: ${response.message}`)
  }

  return (
    <div className="space-y-5">
      <div>
        <div className="text-[10px] uppercase tracking-wider text-jmix-400">{readable(artifact.kind)}</div>
        <h3 className="mt-1 break-words text-xl font-semibold text-gray-100">{artifact.displayName}</h3>
        <p className="mt-1 text-xs text-gray-400">{artifact.summary}</p>
        <div className="mt-3 flex flex-wrap gap-2">
          {artifact.kind === 'VIEW_DESCRIPTOR' && (
            <button
              type="button"
              onClick={onOpenDesigner}
              className="rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600"
            >
              Open in round-trip designer
            </button>
          )}
          <button
            type="button"
            onClick={() => void openSource()}
            className="rounded border border-jmix-500/50 bg-jmix-500/10 px-3 py-1.5 text-xs font-medium text-jmix-300 hover:bg-jmix-500/20"
          >
            Open source in IntelliJ
          </button>
        </div>
        {navigationMessage && (
          <div className="mt-2 rounded border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-100" role="alert">
            {navigationMessage}
          </div>
        )}
      </div>

      <dl className="grid gap-3 rounded border border-surface-border bg-surface-light p-4 text-xs sm:grid-cols-2">
        <Detail label="Module" value={artifact.owner.moduleId} />
        <Detail label="Build" value={artifact.owner.buildId} />
        <Detail label="Source set" value={artifact.owner.sourceSetId ?? 'Unknown'} />
        <Detail label="Origin" value={readable(artifact.origin)} />
        <div className="sm:col-span-2">
          <dt className="text-[10px] uppercase text-gray-600">Source</dt>
          <dd className="mt-1 break-all text-gray-300">
            {artifact.sourceLocator.relativePath}
            {artifact.sourceLocator.line ? `:${artifact.sourceLocator.line}` : ''}
          </dd>
        </div>
        <div className="sm:col-span-2">
          <dt className="text-[10px] uppercase text-gray-600">Semantic identity</dt>
          <dd className="mt-1 break-all font-mono text-[11px] text-gray-400">{artifact.semanticKey}</dd>
        </div>
      </dl>

      <RelationshipSection
        title={`Outgoing impact (${outgoing.length})`}
        relationships={outgoing}
        direction="outgoing"
        artifactsById={artifactsById}
        onNavigate={onNavigate}
      />
      <RelationshipSection
        title={`Incoming impact (${incoming.length})`}
        relationships={incoming}
        direction="incoming"
        artifactsById={artifactsById}
        onNavigate={onNavigate}
      />

      <section>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">
          Diagnostics ({diagnostics.length})
        </h4>
        <div className="space-y-2">
          {diagnostics.map((diagnostic) => (
            <div key={diagnostic.id} className={`rounded border p-3 text-xs ${severityClass(diagnostic.severity)}`}>
              <div className="font-medium">{diagnostic.reasonCode}</div>
              <div className="mt-1 opacity-90">{diagnostic.message}</div>
              {diagnostic.nextStep && <div className="mt-2 opacity-70">Next: {diagnostic.nextStep}</div>}
              {diagnostic.sourceLocator && (
                <button
                  type="button"
                  onClick={() => void bridge.navigateToSource(diagnostic.sourceLocator!)}
                  className="mt-2 underline decoration-dotted underline-offset-2 opacity-80 hover:opacity-100"
                >
                  Open diagnostic source
                </button>
              )}
            </div>
          ))}
          {diagnostics.length === 0 && <div className="text-xs text-gray-600">No source-linked diagnostics.</div>}
        </div>
      </section>
    </div>
  )
}

function RelationshipSection({
  title,
  relationships,
  direction,
  artifactsById,
  onNavigate,
}: {
  title: string
  relationships: GraphRelationship[]
  direction: 'incoming' | 'outgoing'
  artifactsById: Map<string, GraphArtifact>
  onNavigate: (id: string) => void
}) {
  return (
    <section>
      <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">{title}</h4>
      <div className="space-y-1">
        {relationships.map((relationship, index) => {
          const targetId = direction === 'outgoing' ? relationship.targetArtifactId : relationship.sourceArtifactId
          const target = targetId ? artifactsById.get(targetId) : null
          return (
            <button
              key={`${relationship.sourceArtifactId}-${relationship.targetArtifactId}-${relationship.type}-${index}`}
              type="button"
              disabled={!target}
              onClick={() => target && onNavigate(target.id)}
              className="flex w-full items-center justify-between gap-3 rounded border border-surface-border bg-surface-light px-3 py-2 text-left text-xs hover:border-jmix-500/50 disabled:cursor-default disabled:opacity-60"
            >
              <span className="min-w-0">
                <span className="text-[10px] text-jmix-400">{readable(relationship.type)}</span>
                <span className="ml-2 truncate text-gray-300">
                  {target?.displayName ?? 'Unresolved target'}
                </span>
              </span>
              {target && <span className="text-gray-600">→</span>}
            </button>
          )
        })}
        {relationships.length === 0 && <div className="text-xs text-gray-600">No relationships in this direction.</div>}
      </div>
    </section>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[10px] uppercase text-gray-600">{label}</dt>
      <dd className="mt-1 break-all text-gray-300">{value}</dd>
    </div>
  )
}
