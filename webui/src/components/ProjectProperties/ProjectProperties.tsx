import { useEffect, useMemo, useState } from 'react'
import { bridge } from '../../bridge'
import type {
  GraphSourceLocator,
  JmixProjectPropertiesWorkspace,
  ProjectApplicationProfileSnapshot,
} from '../../types'

function sourceLabel(locator: GraphSourceLocator): string {
  return locator.relativePath || 'project root'
}

function profileLabel(profile: ProjectApplicationProfileSnapshot): string {
  const module = profile.modulePath || 'root'
  return `${module} · ${profile.profile}`
}

export default function ProjectProperties() {
  const [workspace, setWorkspace] = useState<JmixProjectPropertiesWorkspace | null>(null)
  const [selectedProfilePath, setSelectedProfilePath] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    setLoading(true)
    setError(null)
    bridge.getProjectPropertiesWorkspace()
      .then((next) => {
        setWorkspace(next)
        setSelectedProfilePath((current) => {
          if (next.profiles.some(profile => profile.locator.relativePath === current)) return current
          return next.profiles[0]?.locator.relativePath ?? ''
        })
      })
      .catch((failure) => {
        setError(failure instanceof Error ? failure.message : String(failure))
      })
      .finally(() => setLoading(false))
  }

  useEffect(load, [])

  const selectedProfile = useMemo(
    () => workspace?.profiles.find(profile =>
      profile.locator.relativePath === selectedProfilePath,
    ) ?? workspace?.profiles[0],
    [selectedProfilePath, workspace],
  )

  const navigate = (locator: GraphSourceLocator) => {
    void bridge.navigateToSource(locator)
  }

  return (
    <section
      className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden bg-surface"
      aria-label="Jmix project properties"
    >
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-surface-border px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-gray-100">Project Configuration</h2>
          <p className="mt-0.5 text-[11px] text-gray-400">
            Revision-bound Jmix, Java, add-on, profile, locale, server and data-store inventory
          </p>
        </div>
        <button
          type="button"
          onClick={load}
          disabled={loading}
          className="min-h-9 rounded border border-surface-border bg-surface-light px-3 py-1.5 text-xs text-gray-200 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
        >
          {loading ? 'Refreshing…' : 'Refresh'}
        </button>
      </header>

      {error && (
        <div className="m-4 rounded border border-red-800 bg-red-950/40 p-3 text-xs text-red-200" role="alert">
          {error}
        </div>
      )}

      {!workspace && loading && (
        <div className="flex flex-1 items-center justify-center text-xs text-gray-400">
          Mapping project configuration…
        </div>
      )}

      {workspace && (
        <div className="min-h-0 flex-1 overflow-auto p-3 sm:p-4">
          <div className="grid min-w-0 grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
            <SummaryCard
              label="Jmix"
              value={workspace.jmixVersion ?? 'Conflicting or unresolved'}
              detail={workspace.jmixVersionConfidence}
            />
            <SummaryCard
              label="Target Java"
              value={workspace.targetJava ? `Java ${workspace.targetJava}` : 'Conflicting or unresolved'}
              detail={workspace.targetJavaConfidence}
            />
            <SummaryCard
              label="Modules / build files"
              value={String(workspace.buildFiles.length)}
              detail={`${workspace.settingsFiles.length} settings file${workspace.settingsFiles.length === 1 ? '' : 's'}`}
            />
            <SummaryCard
              label="Application profiles"
              value={String(workspace.profiles.length)}
              detail={`${workspace.addOns.length} detected add-on${workspace.addOns.length === 1 ? '' : 's'}`}
            />
          </div>

          {workspace.issues.length > 0 && (
            <section className="mt-3 rounded border border-amber-700/60 bg-amber-950/20 p-3" aria-label="Configuration findings">
              <h3 className="text-xs font-semibold text-amber-200">Review required</h3>
              <ul className="mt-2 space-y-1.5">
                {workspace.issues.map((issue, index) => (
                  <li key={`${issue.code}-${issue.relativePath ?? ''}-${index}`} className="text-[11px] text-amber-100/90">
                    <span className="font-mono text-amber-300">{issue.code}</span>
                    {' — '}
                    {issue.message}
                  </li>
                ))}
              </ul>
            </section>
          )}

          <div className="mt-3 grid min-w-0 grid-cols-1 gap-3 xl:grid-cols-[minmax(16rem,0.8fr)_minmax(0,2.2fr)]">
            <aside className="min-w-0 rounded border border-surface-border bg-surface-light p-3" aria-label="Configuration sources">
              <h3 className="text-xs font-semibold text-gray-200">Sources</h3>
              <div className="mt-2 space-y-1">
                {[...workspace.buildFiles, ...workspace.settingsFiles].map(locator => (
                  <button
                    key={locator.relativePath}
                    type="button"
                    onClick={() => navigate(locator)}
                    className="block min-h-9 w-full truncate rounded px-2 py-1.5 text-left font-mono text-[11px] text-gray-300 hover:bg-surface-lighter hover:text-jmix-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                    title={sourceLabel(locator)}
                  >
                    {sourceLabel(locator)}
                  </button>
                ))}
              </div>

              <h3 className="mt-4 text-xs font-semibold text-gray-200">Profiles</h3>
              <div className="mt-2 space-y-1">
                {workspace.profiles.map(profile => (
                  <button
                    key={profile.locator.relativePath}
                    type="button"
                    onClick={() => setSelectedProfilePath(profile.locator.relativePath)}
                    aria-current={selectedProfile?.locator.relativePath === profile.locator.relativePath ? 'true' : undefined}
                    className={`block min-h-9 w-full truncate rounded px-2 py-1.5 text-left text-[11px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 ${
                      selectedProfile?.locator.relativePath === profile.locator.relativePath
                        ? 'bg-jmix-500/20 text-jmix-300'
                        : 'text-gray-300 hover:bg-surface-lighter'
                    }`}
                    title={profile.locator.relativePath}
                  >
                    {profileLabel(profile)}
                  </button>
                ))}
              </div>
            </aside>

            <main className="min-w-0 rounded border border-surface-border bg-surface-light p-3" aria-label="Selected application profile">
              {selectedProfile
                ? (
                  <>
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="min-w-0">
                        <h3 className="truncate text-xs font-semibold text-gray-100">
                          {profileLabel(selectedProfile)}
                        </h3>
                        <p className="mt-0.5 truncate font-mono text-[10px] text-gray-500">
                          {selectedProfile.locator.relativePath}
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={() => navigate(selectedProfile.locator)}
                        className="min-h-9 rounded border border-surface-border px-2.5 py-1 text-[11px] text-gray-300 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                      >
                        Open source
                      </button>
                    </div>

                    <dl className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-3">
                      <PropertyValue label="Server port" value={selectedProfile.serverPort ?? 'Default'} />
                      <PropertyValue label="Context path" value={selectedProfile.contextPath ?? '/'} />
                      <PropertyValue
                        label="Locales"
                        value={selectedProfile.availableLocales.join(', ') || 'Default locale'}
                      />
                    </dl>

                    <h4 className="mt-4 text-xs font-semibold text-gray-200">Data stores</h4>
                    <div className="mt-2 grid min-w-0 grid-cols-1 gap-2 lg:grid-cols-2">
                      {selectedProfile.stores.map(store => (
                        <article key={store.name} className="min-w-0 rounded border border-surface-border bg-surface p-3">
                          <div className="flex items-center justify-between gap-2">
                            <h5 className="font-mono text-xs text-jmix-300">{store.name}</h5>
                            {!store.declaredAdditional && (
                              <span className="rounded bg-amber-900/50 px-1.5 py-0.5 text-[9px] text-amber-200">
                                Not in additional-stores
                              </span>
                            )}
                          </div>
                          <dl className="mt-2 space-y-1 text-[11px]">
                            <PropertyRow label="URL" value={store.url ?? 'Not configured'} />
                            <PropertyRow label="User" value={store.username ?? 'Not configured'} />
                            <PropertyRow
                              label="Password"
                              value={!store.passwordConfigured
                                ? 'Not configured'
                                : store.passwordUsesPlaceholder ? 'Environment placeholder' : 'Configured · hidden'}
                            />
                            <PropertyRow label="Liquibase" value={store.liquibaseChangeLog ?? 'Disabled / unresolved'} />
                          </dl>
                        </article>
                      ))}
                    </div>
                  </>
                )
                : (
                  <div className="flex min-h-40 items-center justify-center text-xs text-gray-500">
                    No application.properties profile is indexed.
                  </div>
                )}
            </main>
          </div>
        </div>
      )}
    </section>
  )
}

function SummaryCard({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <article className="min-w-0 rounded border border-surface-border bg-surface-light p-3">
      <p className="text-[10px] uppercase tracking-wide text-gray-500">{label}</p>
      <p className="mt-1 truncate text-sm font-semibold text-gray-100" title={value}>{value}</p>
      <p className="mt-0.5 truncate text-[10px] text-gray-500" title={detail}>{detail}</p>
    </article>
  )
}

function PropertyValue({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 rounded border border-surface-border bg-surface px-3 py-2">
      <dt className="text-[10px] text-gray-500">{label}</dt>
      <dd className="mt-0.5 truncate text-xs text-gray-200" title={value}>{value}</dd>
    </div>
  )
}

function PropertyRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="grid min-w-0 grid-cols-[5rem_minmax(0,1fr)] gap-2">
      <dt className="text-gray-500">{label}</dt>
      <dd className="truncate text-gray-300" title={value}>{value}</dd>
    </div>
  )
}
