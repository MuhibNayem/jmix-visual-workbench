import { useEffect, useMemo, useState } from 'react'
import { bridge } from '../../bridge'
import type {
  GraphSourceLocator,
  JmixProjectPropertiesWorkspace,
  ProjectApplicationPropertiesChangeRequest,
  ProjectApplicationProfileSnapshot,
  ProjectDataStorePropertySnapshot,
  WorkspaceChangePreviewResponse,
} from '../../types'

interface StoreDraft {
  name: string
  url: string
  username: string
  passwordEnvironment: string
  driverClassName: string
  liquibaseChangeLog: string
}

interface ProfileDraft {
  serverPort: string
  contextPath: string
  activeProfiles: string
  availableLocales: string
  additionalStores: string
  stores: StoreDraft[]
}

const inputClass =
  'min-h-10 w-full min-w-0 rounded border border-surface-border bg-surface px-3 py-2 text-xs text-gray-100 outline-none placeholder:text-gray-600 focus:border-jmix-500 focus-visible:ring-2 focus-visible:ring-jmix-500/30'

function sourceLabel(locator: GraphSourceLocator): string {
  return locator.relativePath || 'project root'
}

function profileLabel(profile: ProjectApplicationProfileSnapshot): string {
  const module = profile.modulePath || 'root'
  return `${module} · ${profile.profile}`
}

function propertyValue(
  profile: ProjectApplicationProfileSnapshot,
  key: string,
): string | undefined {
  return profile.properties.find(property => property.key === key)?.displayValue
}

function environmentName(
  profile: ProjectApplicationProfileSnapshot,
  key: string,
): string {
  const match = propertyValue(profile, key)?.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/)
  return match?.[1] ?? ''
}

function createDraft(profile: ProjectApplicationProfileSnapshot): ProfileDraft {
  const declaredStores = profile.stores
    .filter(store => store.name !== 'main' && store.declaredAdditional)
    .map(store => store.name)
  return {
    serverPort: profile.serverPort ?? '',
    contextPath: profile.contextPath ?? '',
    activeProfiles:
      propertyValue(profile, 'spring.profiles.active') ??
      profile.activeProfiles.join(','),
    availableLocales:
      propertyValue(profile, 'jmix.core.available-locales') ??
      profile.availableLocales.join(','),
    additionalStores:
      propertyValue(profile, 'jmix.core.additional-stores') ??
      declaredStores.join(','),
    stores: profile.stores.map(store => ({
      name: store.name,
      url: store.url ?? '',
      username: store.username ?? '',
      passwordEnvironment: environmentName(profile, `${store.name}.datasource.password`),
      driverClassName: store.driverClassName ?? '',
      liquibaseChangeLog: store.liquibaseChangeLog ?? '',
    })),
  }
}

function sameValue(
  profile: ProjectApplicationProfileSnapshot,
  key: string,
  value: string,
): boolean {
  return propertyValue(profile, key) === value
}

function buildChange(
  profile: ProjectApplicationProfileSnapshot,
  draft: ProfileDraft,
): ProjectApplicationPropertiesChangeRequest {
  const updates: ProjectApplicationPropertiesChangeRequest['updates'] = []
  const add = (key: string, value: string, observed?: string) => {
    if (observed === undefined && value === '') return
    if ((observed ?? propertyValue(profile, key)) !== value) {
      updates.push({ key, value })
    }
  }

  add('server.port', draft.serverPort, profile.serverPort)
  add('server.servlet.context-path', draft.contextPath, profile.contextPath)
  if (profile.profile === 'default') {
    add(
      'spring.profiles.active',
      draft.activeProfiles,
      propertyValue(profile, 'spring.profiles.active') ??
        profile.activeProfiles.join(','),
    )
  }
  add(
    'jmix.core.available-locales',
    draft.availableLocales,
    propertyValue(profile, 'jmix.core.available-locales') ??
      profile.availableLocales.join(','),
  )
  add(
    'jmix.core.additional-stores',
    draft.additionalStores,
    propertyValue(profile, 'jmix.core.additional-stores') ??
      profile.stores
        .filter(store => store.name !== 'main' && store.declaredAdditional)
        .map(store => store.name)
        .join(','),
  )

  draft.stores.forEach(store => {
    const original = profile.stores.find(candidate => candidate.name === store.name)
    add(`${store.name}.datasource.url`, store.url, original?.url)
    add(`${store.name}.datasource.username`, store.username, original?.username)
    add(
      `${store.name}.datasource.driver-class-name`,
      store.driverClassName,
      original?.driverClassName,
    )
    add(
      `${store.name}.liquibase.change-log`,
      store.liquibaseChangeLog,
      original?.liquibaseChangeLog,
    )
    if (store.passwordEnvironment) {
      const key = `${store.name}.datasource.password`
      const value = `\${${store.passwordEnvironment}}`
      if (!sameValue(profile, key, value)) updates.push({ key, value })
    }
  })

  return {
    profileLocator: profile.locator,
    updates,
  }
}

function issueMessage(preview: WorkspaceChangePreviewResponse): string {
  return preview.issues.map(issue => `${issue.code}: ${issue.message}`).join(' · ')
}

export default function ProjectProperties() {
  const [workspace, setWorkspace] = useState<JmixProjectPropertiesWorkspace | null>(null)
  const [selectedProfilePath, setSelectedProfilePath] = useState('')
  const [draft, setDraft] = useState<ProfileDraft | null>(null)
  const [pendingChange, setPendingChange] =
    useState<ProjectApplicationPropertiesChangeRequest | null>(null)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [reviewing, setReviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = (preferredProfilePath?: string) => {
    setLoading(true)
    setError(null)
    return bridge.getProjectPropertiesWorkspace()
      .then((next) => {
        setWorkspace(next)
        setSelectedProfilePath((current) => {
          const preferred = preferredProfilePath || current
          if (next.profiles.some(profile => profile.locator.relativePath === preferred)) {
            return preferred
          }
          return next.profiles[0]?.locator.relativePath ?? ''
        })
      })
      .catch((failure) => {
        setError(failure instanceof Error ? failure.message : String(failure))
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    void load()
  }, [])

  const selectedProfile = useMemo(
    () => workspace?.profiles.find(profile =>
      profile.locator.relativePath === selectedProfilePath,
    ) ?? workspace?.profiles[0],
    [selectedProfilePath, workspace],
  )

  useEffect(() => {
    setDraft(selectedProfile ? createDraft(selectedProfile) : null)
    setPendingChange(null)
    setPreview(null)
    setNotice(null)
  }, [selectedProfile?.locator.relativePath, selectedProfile?.locator.revisionFingerprint])

  const navigate = (locator: GraphSourceLocator) => {
    void bridge.navigateToSource(locator)
  }

  const mutateDraft = (mutation: (current: ProfileDraft) => ProfileDraft) => {
    setDraft(current => current ? mutation(current) : current)
    setPendingChange(null)
    setPreview(null)
    setNotice(null)
  }

  const setProfileField = (
    field: Exclude<keyof ProfileDraft, 'stores'>,
    value: string,
  ) => {
    mutateDraft(current => ({ ...current, [field]: value }))
  }

  const setStoreField = (
    storeName: string,
    field: Exclude<keyof StoreDraft, 'name'>,
    value: string,
  ) => {
    mutateDraft(current => ({
      ...current,
      stores: current.stores.map(store =>
        store.name === storeName ? { ...store, [field]: value } : store,
      ),
    }))
  }

  const resetDraft = () => {
    if (!selectedProfile) return
    setDraft(createDraft(selectedProfile))
    setPendingChange(null)
    setPreview(null)
    setError(null)
    setNotice('Draft reset to the indexed source revision.')
  }

  const reviewChanges = async () => {
    if (!selectedProfile || !draft) return
    const invalidEnvironment = draft.stores.find(store =>
      store.passwordEnvironment &&
      !/^[A-Za-z_][A-Za-z0-9_]*$/.test(store.passwordEnvironment),
    )
    if (invalidEnvironment) {
      setError(
        `${invalidEnvironment.name} password must use an environment variable name such as DB_PASSWORD.`,
      )
      return
    }
    const change = buildChange(selectedProfile, draft)
    if (change.updates.length === 0) {
      setError('Nothing changed in this profile.')
      return
    }
    setReviewing(true)
    setError(null)
    setNotice(null)
    try {
      const response = await bridge.previewProjectProfileChange(change)
      setPreview(response)
      if (!response.accepted || !response.planDigest) {
        setPendingChange(null)
        setError(issueMessage(response) || 'The profile change was rejected.')
        return
      }
      setPendingChange(change)
      setNotice(
        `${change.updates.length} source-preserving change${change.updates.length === 1 ? '' : 's'} ready for approval.`,
      )
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setReviewing(false)
    }
  }

  const applyChanges = async () => {
    if (!pendingChange || !preview?.planDigest) return
    setApplying(true)
    setError(null)
    setNotice(null)
    try {
      const response = await bridge.applyProjectProfileChange(
        pendingChange,
        preview.planDigest,
      )
      if (!response.success) {
        setError(
          response.issues.map(issue => `${issue.code}: ${issue.message}`).join(' · ') ||
          'The profile change was not applied.',
        )
        return
      }
      const path = pendingChange.profileLocator.relativePath
      setPreview(null)
      setPendingChange(null)
      await load(path)
      setNotice(
        `Updated ${response.filesChanged.length} file${response.filesChanged.length === 1 ? '' : 's'}. The complete operation is available in visual undo history.`,
      )
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setApplying(false)
    }
  }

  return (
    <section
      className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden bg-surface"
      aria-label="Jmix project properties"
    >
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-surface-border px-3 py-3 sm:px-4">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-gray-100">Project Configuration</h2>
          <p className="mt-0.5 max-w-3xl text-[11px] leading-4 text-gray-400">
            Edit revision-bound server, locale and data-store settings with credential-safe review,
            atomic apply and visual undo.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load(selectedProfilePath)}
          disabled={loading || applying || reviewing}
          className="min-h-10 rounded border border-surface-border bg-surface-light px-3 py-2 text-xs text-gray-200 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
        >
          {loading ? 'Refreshing…' : 'Refresh project'}
        </button>
      </header>

      <div className="sr-only" aria-live="polite">{notice}</div>
      {error && (
        <div className="mx-3 mt-3 rounded border border-red-800 bg-red-950/40 p-3 text-xs leading-5 text-red-200 sm:mx-4" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="mx-3 mt-3 rounded border border-emerald-800 bg-emerald-950/30 p-3 text-xs leading-5 text-emerald-200 sm:mx-4" role="status">
          {notice}
        </div>
      )}

      {!workspace && loading && (
        <div className="flex flex-1 items-center justify-center text-xs text-gray-400">
          Mapping project configuration…
        </div>
      )}

      {workspace && (
        <div className="min-h-0 min-w-0 flex-1 overflow-auto p-3 sm:p-4">
          <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2 2xl:grid-cols-4">
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
                  <li key={`${issue.code}-${issue.relativePath ?? ''}-${index}`} className="break-words text-[11px] leading-4 text-amber-100/90">
                    <span className="font-mono text-amber-300">{issue.code}</span>
                    {' — '}
                    {issue.message}
                  </li>
                ))}
              </ul>
            </section>
          )}

          <div className="mt-3 grid min-w-0 grid-cols-1 gap-3 xl:grid-cols-[minmax(13rem,0.65fr)_minmax(0,2.35fr)]">
            <aside className="min-w-0 rounded border border-surface-border bg-surface-light p-3" aria-label="Configuration sources">
              <h3 className="text-xs font-semibold text-gray-200">Profiles</h3>
              <div className="mt-2 grid grid-cols-1 gap-1 sm:grid-cols-2 xl:grid-cols-1">
                {workspace.profiles.map(profile => (
                  <button
                    key={profile.locator.relativePath}
                    type="button"
                    onClick={() => setSelectedProfilePath(profile.locator.relativePath)}
                    aria-current={selectedProfile?.locator.relativePath === profile.locator.relativePath ? 'true' : undefined}
                    className={`block min-h-10 w-full min-w-0 rounded px-2 py-2 text-left text-[11px] focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 ${
                      selectedProfile?.locator.relativePath === profile.locator.relativePath
                        ? 'bg-jmix-500/20 text-jmix-300'
                        : 'text-gray-300 hover:bg-surface-lighter'
                    }`}
                    title={profile.locator.relativePath}
                  >
                    <span className="block truncate">{profileLabel(profile)}</span>
                    <span className="mt-0.5 block truncate font-mono text-[9px] text-gray-500">
                      {profile.locator.relativePath}
                    </span>
                  </button>
                ))}
              </div>

              <details className="mt-4 rounded border border-surface-border bg-surface">
                <summary className="min-h-10 cursor-pointer px-2 py-2.5 text-xs font-semibold text-gray-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400">
                  Build and settings sources
                </summary>
                <div className="border-t border-surface-border p-1">
                  {[...workspace.buildFiles, ...workspace.settingsFiles].map(locator => (
                    <button
                      key={locator.relativePath}
                      type="button"
                      onClick={() => navigate(locator)}
                      className="block min-h-10 w-full min-w-0 rounded px-2 py-2 text-left font-mono text-[10px] text-gray-400 hover:bg-surface-lighter hover:text-jmix-300 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                      title={sourceLabel(locator)}
                    >
                      <span className="block truncate">{sourceLabel(locator)}</span>
                    </button>
                  ))}
                </div>
              </details>
            </aside>

            <main className="min-w-0 rounded border border-surface-border bg-surface-light p-3 sm:p-4" aria-label="Selected application profile">
              {selectedProfile && draft
                ? (
                  <>
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0 flex-1">
                        <h3 className="truncate text-sm font-semibold text-gray-100">
                          {profileLabel(selectedProfile)}
                        </h3>
                        <p className="mt-0.5 break-all font-mono text-[10px] text-gray-500">
                          {selectedProfile.locator.relativePath}
                        </p>
                      </div>
                      <button
                        type="button"
                        onClick={() => navigate(selectedProfile.locator)}
                        className="min-h-10 shrink-0 rounded border border-surface-border px-3 py-2 text-[11px] text-gray-300 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                      >
                        Open source
                      </button>
                    </div>

                    <fieldset className="mt-4 min-w-0">
                      <legend className="text-xs font-semibold text-gray-200">Runtime profile</legend>
                      <div className="mt-2 grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2 2xl:grid-cols-4">
                        <EditorField label="Server port" hint="1–65535 or ${PORT}">
                          <input
                            value={draft.serverPort}
                            onChange={event => setProfileField('serverPort', event.target.value)}
                            className={inputClass}
                            inputMode="numeric"
                            placeholder="8080"
                          />
                        </EditorField>
                        <EditorField label="Context path" hint="Absolute path or ${CONTEXT_PATH}">
                          <input
                            value={draft.contextPath}
                            onChange={event => setProfileField('contextPath', event.target.value)}
                            className={inputClass}
                            placeholder="/payroll"
                          />
                        </EditorField>
                        <EditorField label="Available locales" hint="en|English,bn|বাংলা">
                          <input
                            value={draft.availableLocales}
                            onChange={event => setProfileField('availableLocales', event.target.value)}
                            className={inputClass}
                            placeholder="en|English"
                          />
                        </EditorField>
                        <EditorField label="Additional stores" hint="Comma-separated identifiers">
                          <input
                            value={draft.additionalStores}
                            onChange={event => setProfileField('additionalStores', event.target.value)}
                            className={inputClass}
                            placeholder="loan,audit"
                          />
                        </EditorField>
                        {selectedProfile.profile === 'default' && (
                          <EditorField label="Active profiles" hint="dev,local">
                            <input
                              value={draft.activeProfiles}
                              onChange={event => setProfileField('activeProfiles', event.target.value)}
                              className={inputClass}
                              placeholder="dev"
                            />
                          </EditorField>
                        )}
                      </div>
                    </fieldset>

                    <section className="mt-5 min-w-0" aria-label="Data-store configuration">
                      <div className="flex flex-wrap items-end justify-between gap-2">
                        <div>
                          <h4 className="text-xs font-semibold text-gray-200">Data stores</h4>
                          <p className="mt-0.5 text-[10px] leading-4 text-gray-500">
                            Passwords are write-only and accept environment variable names only.
                          </p>
                        </div>
                        <span className="rounded bg-surface px-2 py-1 text-[10px] text-gray-500">
                          {draft.stores.length} indexed
                        </span>
                      </div>
                      <div className="mt-2 grid min-w-0 grid-cols-1 gap-3 2xl:grid-cols-2">
                        {draft.stores.map(store => (
                          <DataStoreEditor
                            key={store.name}
                            store={store}
                            snapshot={selectedProfile.stores.find(candidate => candidate.name === store.name)}
                            onChange={(field, value) => setStoreField(store.name, field, value)}
                          />
                        ))}
                      </div>
                    </section>

                    <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-surface-border pt-4">
                      <button
                        type="button"
                        onClick={() => void reviewChanges()}
                        disabled={reviewing || applying}
                        className="min-h-10 rounded bg-jmix-600 px-4 py-2 text-xs font-medium text-white hover:bg-jmix-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-jmix-400 disabled:opacity-50"
                      >
                        {reviewing ? 'Building review…' : 'Review source changes'}
                      </button>
                      <button
                        type="button"
                        onClick={resetDraft}
                        disabled={reviewing || applying}
                        className="min-h-10 rounded border border-surface-border px-3 py-2 text-xs text-gray-300 hover:border-gray-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
                      >
                        Reset draft
                      </button>
                      <p className="min-w-[12rem] flex-1 text-[10px] leading-4 text-gray-500">
                        Nothing writes until you approve the exact revision-bound preview.
                      </p>
                    </div>

                    {preview && (
                      <ProfileChangeReview
                        preview={preview}
                        applying={applying}
                        onApply={() => void applyChanges()}
                        onDiscard={() => {
                          setPreview(null)
                          setPendingChange(null)
                          setNotice(null)
                        }}
                      />
                    )}
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

function EditorField({
  label,
  hint,
  children,
}: {
  label: string
  hint: string
  children: React.ReactNode
}) {
  return (
    <label className="block min-w-0">
      <span className="text-[11px] font-medium text-gray-300">{label}</span>
      <span className="mt-0.5 block min-h-4 truncate text-[9px] text-gray-600" title={hint}>
        {hint}
      </span>
      <span className="mt-1 block">{children}</span>
    </label>
  )
}

function DataStoreEditor({
  store,
  snapshot,
  onChange,
}: {
  store: StoreDraft
  snapshot?: ProjectDataStorePropertySnapshot
  onChange: (field: Exclude<keyof StoreDraft, 'name'>, value: string) => void
}) {
  return (
    <fieldset className="min-w-0 rounded border border-surface-border bg-surface p-3">
      <legend className="max-w-full px-1">
        <span className="font-mono text-xs text-jmix-300">{store.name}</span>
      </legend>
      {!snapshot?.declaredAdditional && (
        <p className="mb-2 rounded bg-amber-950/40 px-2 py-1.5 text-[10px] leading-4 text-amber-200">
          Discovered from properties but not declared in jmix.core.additional-stores.
        </p>
      )}
      <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
        <EditorField label="JDBC URL" hint="jdbc:… or ${DATABASE_URL}">
          <input
            value={store.url}
            onChange={event => onChange('url', event.target.value)}
            className={inputClass}
            placeholder="jdbc:postgresql://localhost/app"
            spellCheck={false}
          />
        </EditorField>
        <EditorField label="Username" hint="Literal user or ${DATABASE_USER}">
          <input
            value={store.username}
            onChange={event => onChange('username', event.target.value)}
            className={inputClass}
            placeholder="app"
            autoComplete="off"
            spellCheck={false}
          />
        </EditorField>
        <EditorField label="Password environment variable" hint="Blank keeps the source unchanged">
          <input
            value={store.passwordEnvironment}
            onChange={event => onChange('passwordEnvironment', event.target.value)}
            className={inputClass}
            placeholder={snapshot?.passwordConfigured ? 'Configured · enter a name to replace' : 'DATABASE_PASSWORD'}
            autoComplete="new-password"
            spellCheck={false}
          />
        </EditorField>
        <EditorField label="Driver class" hint="Qualified JVM class or placeholder">
          <input
            value={store.driverClassName}
            onChange={event => onChange('driverClassName', event.target.value)}
            className={inputClass}
            placeholder="org.postgresql.Driver"
            spellCheck={false}
          />
        </EditorField>
        <div className="min-w-0 sm:col-span-2">
          <EditorField label="Liquibase changelog" hint="classpath-relative changelog path">
            <input
              value={store.liquibaseChangeLog}
              onChange={event => onChange('liquibaseChangeLog', event.target.value)}
              className={inputClass}
              placeholder="com/company/app/liquibase/changelog.xml"
              spellCheck={false}
            />
          </EditorField>
        </div>
      </div>
    </fieldset>
  )
}

function ProfileChangeReview({
  preview,
  applying,
  onApply,
  onDiscard,
}: {
  preview: WorkspaceChangePreviewResponse
  applying: boolean
  onApply: () => void
  onDiscard: () => void
}) {
  const file = preview.files[0]
  return (
    <section className="mt-5 min-w-0 rounded border border-jmix-700/70 bg-jmix-950/10 p-3 sm:p-4" aria-label="Profile source review">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h4 className="text-xs font-semibold text-gray-100">Credential-safe source review</h4>
          <p className="mt-1 break-all font-mono text-[9px] text-gray-500">
            {file?.relativePath ?? preview.label}
          </p>
        </div>
        <span className="rounded bg-emerald-950/50 px-2 py-1 text-[10px] text-emerald-300">
          Exact revision verified
        </span>
      </div>

      {file && (
        <div className="mt-3 grid min-w-0 grid-cols-1 gap-3 lg:grid-cols-2">
          <SourcePreview label="Current selected values" content={file.originalContent ?? ''} />
          <SourcePreview label="Reviewed selected values" content={file.resultContent} changed />
        </div>
      )}

      <div className="mt-4 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={onApply}
          disabled={!preview.accepted || !preview.planDigest || applying}
          className="min-h-10 rounded bg-emerald-700 px-4 py-2 text-xs font-medium text-white hover:bg-emerald-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-400 disabled:opacity-50"
        >
          {applying ? 'Applying atomically…' : 'Apply reviewed change'}
        </button>
        <button
          type="button"
          onClick={onDiscard}
          disabled={applying}
          className="min-h-10 rounded border border-surface-border px-3 py-2 text-xs text-gray-300 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
        >
          Discard review
        </button>
      </div>
    </section>
  )
}

function SourcePreview({
  label,
  content,
  changed = false,
}: {
  label: string
  content: string
  changed?: boolean
}) {
  return (
    <article className={`min-w-0 overflow-hidden rounded border ${
      changed ? 'border-emerald-800/70 bg-emerald-950/20' : 'border-surface-border bg-surface'
    }`}>
      <h5 className="border-b border-inherit px-3 py-2 text-[10px] font-semibold text-gray-300">
        {label}
      </h5>
      <pre className="max-h-64 min-w-0 overflow-auto whitespace-pre-wrap break-all p-3 font-mono text-[10px] leading-5 text-gray-300">
        {content}
      </pre>
    </article>
  )
}
