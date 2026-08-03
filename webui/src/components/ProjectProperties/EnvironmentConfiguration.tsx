import { useEffect, useMemo, useState } from 'react'
import { bridge } from '../../bridge'
import type {
  EnvironmentChangeRequest,
  EnvironmentConnectionRequest,
  EnvironmentFileSnapshot,
  EnvironmentVariableSnapshot,
  JmixEnvironmentWorkspace,
  WorkspaceChangePreviewResponse,
} from '../../types'

const inputClass =
  'min-h-10 w-full min-w-0 rounded border border-surface-border bg-surface px-3 py-2 text-xs text-gray-100 outline-none placeholder:text-gray-600 focus:border-jmix-500 focus-visible:ring-2 focus-visible:ring-jmix-500/30'

function previewIssue(preview: WorkspaceChangePreviewResponse): string {
  return preview.issues.map(issue => `${issue.code}: ${issue.message}`).join(' · ')
}

function moduleLabel(modulePath: string): string {
  return modulePath || 'root application'
}

export default function EnvironmentConfiguration() {
  const [workspace, setWorkspace] = useState<JmixEnvironmentWorkspace | null>(null)
  const [selectedPath, setSelectedPath] = useState('')
  const [selectedVariable, setSelectedVariable] = useState('')
  const [variableName, setVariableName] = useState('')
  const [value, setValue] = useState('')
  const [secure, setSecure] = useState(false)
  const [pendingChange, setPendingChange] = useState<EnvironmentChangeRequest | null>(null)
  const [pendingConnection, setPendingConnection] =
    useState<EnvironmentConnectionRequest | null>(null)
  const [secretCapability, setSecretCapability] = useState<string | null>(null)
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [reviewing, setReviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = (preferredPath?: string, forceRefresh: boolean = false) => {
    setLoading(true)
    setError(null)
    return bridge.getEnvironmentWorkspace(forceRefresh)
      .then(next => {
        setWorkspace(next)
        setSelectedPath(current => {
          const preferred = preferredPath || current
          return next.files.some(file => file.relativePath === preferred)
            ? preferred
            : next.files[0]?.relativePath ?? ''
        })
      })
      .catch(failure => {
        setError(failure instanceof Error ? failure.message : String(failure))
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    void load()
  }, [])

  const selectedFile = useMemo(
    () => workspace?.files.find(file => file.relativePath === selectedPath) ??
      workspace?.files[0],
    [selectedPath, workspace],
  )
  const selectedSnapshot = useMemo(
    () => selectedFile?.variables.find(variable => variable.name === selectedVariable),
    [selectedFile, selectedVariable],
  )

  const clearReview = () => {
    setPreview(null)
    setPendingChange(null)
    setPendingConnection(null)
    setSecretCapability(null)
  }

  const chooseVariable = (variable?: EnvironmentVariableSnapshot) => {
    setSelectedVariable(variable?.name ?? '')
    setVariableName(variable?.name ?? '')
    setValue(variable && !variable.secret ? variable.displayValue : '')
    setSecure(variable?.secret ?? false)
    clearReview()
    setError(null)
    setNotice(null)
  }

  useEffect(() => {
    chooseVariable(selectedFile?.variables[0])
  }, [selectedFile?.relativePath, selectedFile?.locator?.revisionFingerprint])

  const reviewVariable = async (mode: EnvironmentChangeRequest['mode']) => {
    if (!workspace || !selectedFile) return
    const normalized = variableName.trim()
    if (!/^[A-Z_][A-Z0-9_]{0,199}$/.test(normalized)) {
      setError('Use an uppercase environment name such as PAYROLL_API_TOKEN.')
      return
    }
    if (mode === 'REMOVE' && !selectedSnapshot) {
      setError('Select an existing variable to remove.')
      return
    }
    if (mode === 'REMOVE' && selectedSnapshot?.references.length) {
      setError(
        `${normalized} is referenced by ${selectedSnapshot.references[0].propertyKey}. ` +
          'Change the application property before removing it.',
      )
      return
    }
    setReviewing(true)
    setError(null)
    setNotice(null)
    clearReview()
    try {
      if (mode === 'SET' && (secure || selectedSnapshot?.secret)) {
        const response = await bridge.prepareSecretEnvironmentChange({
          workspaceDigest: workspace.snapshotDigest,
          relativePath: selectedFile.relativePath,
          locator: selectedFile.locator,
          variableName: normalized,
        })
        setPreview(response.preview)
        if (!response.accepted || !response.capability || !response.preview.planDigest) {
          setError(previewIssue(response.preview) || 'The secure change was cancelled or rejected.')
          return
        }
        setSecretCapability(response.capability)
        setNotice('The secret remains inside IntelliJ. Only this redacted one-time approval reached the workspace.')
        return
      }
      const change: EnvironmentChangeRequest = {
        workspaceDigest: workspace.snapshotDigest,
        relativePath: selectedFile.relativePath,
        locator: selectedFile.locator,
        variableName: normalized,
        mode,
        value: mode === 'SET' ? value : undefined,
      }
      const response = await bridge.previewEnvironmentChange(change)
      setPreview(response)
      if (!response.accepted || !response.planDigest) {
        setError(previewIssue(response) || 'The environment change was rejected.')
        return
      }
      setPendingChange(change)
      setNotice(`${normalized} is ready for revision-bound approval.`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setReviewing(false)
    }
  }

  const applyVariable = async () => {
    if (!preview?.planDigest) return
    setApplying(true)
    setError(null)
    try {
      const response = secretCapability
        ? await bridge.applySecretEnvironmentChange(secretCapability, preview.planDigest)
        : pendingChange
          ? await bridge.applyEnvironmentChange(pendingChange, preview.planDigest)
          : null
      if (!response?.success) {
        setError(
          response?.issues.map(issue => `${issue.code}: ${issue.message}`).join(' · ') ||
          'The environment change was not applied.',
        )
        return
      }
      const path = response.filesChanged[0] ?? selectedFile?.relativePath
      clearReview()
      await load(path)
      setNotice('Environment configuration updated atomically. Visual undo restores the exact previous bytes.')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setApplying(false)
    }
  }

  const reviewConnection = async (
    profileLocator: EnvironmentConnectionRequest['profileLocator'],
    environmentFile: EnvironmentConnectionRequest['environmentFile'] = '.env',
  ) => {
    if (!workspace) return
    const change: EnvironmentConnectionRequest = {
      workspaceDigest: workspace.snapshotDigest,
      profileLocator,
      environmentFile,
    }
    setReviewing(true)
    setError(null)
    setNotice(null)
    clearReview()
    try {
      const response = await bridge.previewEnvironmentConnection(change)
      setPreview(response)
      if (!response.accepted || !response.planDigest) {
        setError(previewIssue(response) || 'The environment-file connection was rejected.')
        return
      }
      setPendingConnection(change)
      setNotice(`Connection to ${environmentFile} is ready for approval.`)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setReviewing(false)
    }
  }

  const applyConnection = async () => {
    if (!pendingConnection || !preview?.planDigest) return
    setApplying(true)
    setError(null)
    try {
      const response = await bridge.applyEnvironmentConnection(
        pendingConnection,
        preview.planDigest,
      )
      if (!response.success) {
        setError(
          response.issues.map(issue => `${issue.code}: ${issue.message}`).join(' · ') ||
          'The environment-file connection was not applied.',
        )
        return
      }
      clearReview()
      await load()
      setNotice(
        'The profile now imports the environment file. Set its first variable to create a missing file safely.',
      )
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setApplying(false)
    }
  }

  return (
    <section
      className="mt-4 min-w-0 rounded border border-surface-border bg-surface-light"
      aria-label="External environment configuration"
    >
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-surface-border p-3 sm:p-4">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-gray-100">External Environment</h3>
          <p className="mt-0.5 max-w-3xl text-[10px] leading-4 text-gray-400">
            Connect project-local environment files, manage variables without exposing secrets,
            and distinguish configured profiles from runtime-proven profiles.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load(selectedFile?.relativePath, true)}
          disabled={loading || reviewing || applying}
          className="min-h-10 rounded border border-surface-border bg-surface px-3 py-2 text-[11px] text-gray-300 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
        >
          {loading ? 'Mapping…' : 'Refresh environment'}
        </button>
      </header>

      {error && (
        <div className="mx-3 mt-3 rounded border border-red-800 bg-red-950/30 p-3 text-[11px] leading-4 text-red-200 sm:mx-4" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="mx-3 mt-3 rounded border border-emerald-800 bg-emerald-950/20 p-3 text-[11px] leading-4 text-emerald-200 sm:mx-4" role="status">
          {notice}
        </div>
      )}

      {workspace && (
        <>
          {workspace.issues.length > 0 && (
            <div className="mx-3 mt-3 rounded border border-amber-700/60 bg-amber-950/20 p-3 sm:mx-4">
              <h4 className="text-[11px] font-semibold text-amber-200">Environment findings</h4>
              <ul className="mt-1.5 space-y-1">
                {workspace.issues.map((issue, index) => (
                  <li key={`${issue.code}-${index}`} className="break-words text-[10px] leading-4 text-amber-100/90">
                    <code className="text-amber-300">{issue.code}</code> — {issue.message}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="grid min-w-0 grid-cols-1 gap-3 p-3 sm:p-4 xl:grid-cols-[minmax(12rem,0.7fr)_minmax(18rem,1.35fr)_minmax(16rem,1fr)]">
            <aside className="min-w-0 rounded border border-surface-border bg-surface p-3" aria-label="Imported environment files">
              <h4 className="text-xs font-semibold text-gray-200">Connected files</h4>
              <div className="mt-2 space-y-1">
                {workspace.files.map(file => (
                  <button
                    type="button"
                    key={file.relativePath}
                    onClick={() => {
                      setSelectedPath(file.relativePath)
                      setError(null)
                      setNotice(null)
                    }}
                    aria-current={selectedFile?.relativePath === file.relativePath ? 'true' : undefined}
                    className={`min-h-10 w-full min-w-0 rounded px-2 py-2 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 ${
                      selectedFile?.relativePath === file.relativePath
                        ? 'bg-jmix-500/20 text-jmix-300'
                        : 'text-gray-300 hover:bg-surface-lighter'
                    }`}
                  >
                    <span className="block truncate font-mono text-[10px]">{file.relativePath}</span>
                    <span className="mt-0.5 block text-[9px] text-gray-500">
                      {file.existing ? `${file.variables.length} variables` : 'Imported · file not created'}
                    </span>
                  </button>
                ))}
                {workspace.files.length === 0 && (
                  <p className="rounded border border-dashed border-surface-border p-2 text-[10px] leading-4 text-gray-500">
                    No application profile explicitly imports a project environment file.
                  </p>
                )}
              </div>

              {workspace.connectionCandidates.length > 0 && (
                <section className="mt-4 border-t border-surface-border pt-3" aria-label="Connect environment file">
                  <h5 className="text-[10px] font-semibold uppercase tracking-wide text-gray-500">
                    Available profiles
                  </h5>
                  <div className="mt-2 space-y-2">
                    {workspace.connectionCandidates.map(candidate => (
                      <article key={candidate.profileLocator.relativePath} className="rounded border border-surface-border bg-surface-light p-2">
                        <p className="truncate text-[10px] text-gray-300">
                          {moduleLabel(candidate.modulePath)} · {candidate.profile}
                        </p>
                        <button
                          type="button"
                          onClick={() => void reviewConnection(candidate.profileLocator)}
                          disabled={reviewing || applying}
                          className="mt-2 min-h-10 w-full rounded border border-jmix-700 px-2 py-2 text-[10px] text-jmix-300 hover:border-jmix-500 hover:text-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
                        >
                          Connect .env
                        </button>
                      </article>
                    ))}
                  </div>
                </section>
              )}
            </aside>

            <main className="min-w-0 rounded border border-surface-border bg-surface p-3" aria-label="Environment variables">
              {selectedFile
                ? (
                  <>
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div className="min-w-0">
                        <h4 className="break-all font-mono text-xs font-semibold text-gray-200">
                          {selectedFile.relativePath}
                        </h4>
                        <p className="mt-0.5 text-[9px] leading-4 text-gray-500">
                          Imported by {selectedFile.importedBy.length} profile declaration
                          {selectedFile.importedBy.length === 1 ? '' : 's'}.
                        </p>
                      </div>
                      {selectedFile.locator && (
                        <button
                          type="button"
                          onClick={() => void bridge.navigateEnvironmentSource(selectedFile.locator!)}
                          className="min-h-10 rounded border border-surface-border px-3 py-2 text-[10px] text-gray-300 hover:border-jmix-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                        >
                          Open source
                        </button>
                      )}
                    </div>

                    <div className="mt-3 grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
                      {selectedFile.variables.map(variable => (
                        <button
                          type="button"
                          key={variable.name}
                          onClick={() => chooseVariable(variable)}
                          aria-current={selectedVariable === variable.name ? 'true' : undefined}
                          className={`min-h-10 min-w-0 rounded border p-2 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 ${
                            selectedVariable === variable.name
                              ? 'border-jmix-600 bg-jmix-950/25'
                              : 'border-surface-border bg-surface-light hover:border-gray-600'
                          }`}
                        >
                          <span className="flex min-w-0 items-center justify-between gap-2">
                            <code className="truncate text-[10px] text-gray-200">{variable.name}</code>
                            {variable.secret && (
                              <span className="shrink-0 rounded bg-violet-500/15 px-1.5 py-0.5 text-[8px] text-violet-300">
                                secret
                              </span>
                            )}
                          </span>
                          <span className="mt-1 block truncate text-[9px] text-gray-500">
                            {variable.displayValue}
                          </span>
                        </button>
                      ))}
                    </div>

                    <div className="mt-3 rounded border border-surface-border bg-surface-light p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <h5 className="text-[11px] font-semibold text-gray-200">
                          {selectedSnapshot ? 'Edit variable' : 'Add variable'}
                        </h5>
                        <button
                          type="button"
                          onClick={() => chooseVariable()}
                          className="min-h-10 rounded border border-surface-border px-2 py-2 text-[10px] text-gray-300 hover:border-jmix-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                        >
                          New variable
                        </button>
                      </div>
                      <div className="mt-2 grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
                        <label className="min-w-0">
                          <span className="mb-1 block text-[9px] text-gray-500">Name</span>
                          <input
                            className={inputClass}
                            value={variableName}
                            onChange={event => {
                              setVariableName(event.target.value.toUpperCase())
                              clearReview()
                            }}
                            disabled={Boolean(selectedSnapshot)}
                            placeholder="PAYROLL_API_URL"
                            autoComplete="off"
                            spellCheck={false}
                          />
                        </label>
                        <label className="min-w-0">
                          <span className="mb-1 block text-[9px] text-gray-500">
                            {secure || selectedSnapshot?.secret ? 'Value stays in native IntelliJ dialog' : 'Value'}
                          </span>
                          <input
                            className={inputClass}
                            type={secure || selectedSnapshot?.secret ? 'password' : 'text'}
                            value={secure || selectedSnapshot?.secret ? '' : value}
                            onChange={event => {
                              setValue(event.target.value)
                              clearReview()
                            }}
                            disabled={secure || Boolean(selectedSnapshot?.secret)}
                            placeholder={secure || selectedSnapshot?.secret ? 'Use secure native dialog' : 'https://sandbox.example'}
                            autoComplete="off"
                            spellCheck={false}
                          />
                        </label>
                      </div>
                      <label className="mt-2 flex min-h-10 items-center gap-2 text-[10px] text-gray-300">
                        <input
                          type="checkbox"
                          checked={secure || Boolean(selectedSnapshot?.secret)}
                          disabled={Boolean(selectedSnapshot?.secret)}
                          onChange={event => {
                            setSecure(event.target.checked)
                            setValue('')
                            clearReview()
                          }}
                          className="h-4 w-4 accent-jmix-500"
                        />
                        Treat as secret and collect it only in native IntelliJ
                      </label>
                      {selectedSnapshot?.references.length ? (
                        <div className="mt-2 rounded border border-surface-border bg-surface p-2">
                          <p className="text-[9px] text-gray-500">Referenced by</p>
                          {selectedSnapshot.references.map(reference => (
                            <button
                              type="button"
                              key={`${reference.profileLocator.relativePath}-${reference.propertyKey}`}
                              onClick={() => void bridge.navigateEnvironmentSource(reference.profileLocator)}
                              className="mt-1 block min-h-10 w-full break-all rounded px-2 py-2 text-left font-mono text-[9px] text-jmix-300 hover:bg-surface-lighter focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                            >
                              {reference.propertyKey} · {reference.profileLocator.relativePath}
                            </button>
                          ))}
                        </div>
                      ) : null}
                      <div className="mt-3 flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => void reviewVariable('SET')}
                          disabled={reviewing || applying || !selectedFile.mutable}
                          className="min-h-10 rounded bg-jmix-600 px-3 py-2 text-[11px] font-medium text-white hover:bg-jmix-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
                        >
                          {secure || selectedSnapshot?.secret ? 'Open secure review' : 'Review change'}
                        </button>
                        {selectedSnapshot && (
                          <button
                            type="button"
                            onClick={() => void reviewVariable('REMOVE')}
                            disabled={
                              reviewing ||
                              applying ||
                              !selectedSnapshot.mutable ||
                              selectedSnapshot.references.length > 0
                            }
                            className="min-h-10 rounded border border-red-800 px-3 py-2 text-[11px] text-red-300 hover:border-red-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-red-500 disabled:opacity-50"
                          >
                            Review removal
                          </button>
                        )}
                      </div>
                    </div>
                  </>
                )
                : (
                  <div className="flex min-h-52 items-center justify-center text-center text-[11px] leading-5 text-gray-500">
                    Connect an application profile to begin managing its environment.
                  </div>
                )}
            </main>

            <aside className="min-w-0 space-y-3" aria-label="Profile activation evidence">
              <section className="rounded border border-surface-border bg-surface p-3">
                <h4 className="text-xs font-semibold text-gray-200">Activation evidence</h4>
                <p className="mt-0.5 text-[9px] leading-4 text-gray-500">
                  Configuration resolution is never presented as proof that a process is running it.
                </p>
                <div className="mt-2 space-y-2">
                  {workspace.activations.map(activation => (
                    <article key={activation.modulePath} className="rounded border border-surface-border bg-surface-light p-2.5">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-[10px] font-medium text-gray-300">
                          {moduleLabel(activation.modulePath)}
                        </span>
                        <span className="flex flex-wrap items-center gap-1">
                          <span className={`rounded px-1.5 py-0.5 text-[8px] ${
                            activation.source === 'UNRESOLVED'
                              ? 'bg-red-500/15 text-red-300'
                              : activation.source === 'IMPORTED_ENV'
                                ? 'bg-jmix-500/15 text-jmix-300'
                                : 'bg-surface text-gray-500'
                          }`}>
                            {activation.source.replace(/_/g, ' ').toLowerCase()}
                          </span>
                          {activation.declarationLocator && (
                            <button
                              type="button"
                              onClick={() => void bridge.navigateEnvironmentSource(activation.declarationLocator!)}
                              className="min-h-10 rounded border border-surface-border px-2 py-1 text-[8px] text-gray-400 hover:border-jmix-500 hover:text-gray-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                            >
                              Open
                            </button>
                          )}
                        </span>
                      </div>
                      <p className="mt-1 break-words text-[9px] leading-4 text-gray-500">
                        {activation.explanation}
                      </p>
                      <div className="mt-2 flex flex-wrap gap-1">
                        {activation.expandedProfiles.map(profile => (
                          <span key={profile} className="rounded bg-surface px-1.5 py-1 font-mono text-[9px] text-gray-300">
                            {profile}
                          </span>
                        ))}
                        {activation.expandedProfiles.length === 0 && (
                          <span className="text-[9px] text-gray-600">No active profile resolved</span>
                        )}
                      </div>
                      {activation.missingProfiles.length > 0 && (
                        <p className="mt-2 text-[9px] leading-4 text-red-300">
                          Missing profile files: {activation.missingProfiles.join(', ')}
                        </p>
                      )}
                    </article>
                  ))}
                </div>
              </section>

              <section className="rounded border border-surface-border bg-surface p-3">
                <h4 className="text-xs font-semibold text-gray-200">IntelliJ launch evidence</h4>
                <div className="mt-2 space-y-2">
                  {workspace.launchConfigurations.map(launch => (
                    <article key={launch.relativePath} className="rounded border border-surface-border bg-surface-light p-2.5">
                      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
                        <code className="min-w-0 break-all text-[9px] text-gray-300">{launch.relativePath}</code>
                        <button
                          type="button"
                          onClick={() => void bridge.navigateEnvironmentSource({
                            relativePath: launch.relativePath,
                            revisionFingerprint: launch.revisionFingerprint,
                          })}
                          className="min-h-10 shrink-0 rounded border border-surface-border px-2 py-1 text-[8px] text-gray-400 hover:border-jmix-500 hover:text-gray-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400"
                        >
                          Open
                        </button>
                      </div>
                      <p className="mt-1 text-[9px] leading-4 text-gray-500">{launch.explanation}</p>
                      <div className="mt-1.5 flex flex-wrap gap-1">
                        {launch.activeProfiles.map(profile => (
                          <span key={profile} className="rounded bg-violet-500/10 px-1.5 py-0.5 text-[8px] text-violet-300">
                            {profile}
                          </span>
                        ))}
                        {launch.environmentFiles.map(file => (
                          <span key={file} className="rounded bg-jmix-500/10 px-1.5 py-0.5 text-[8px] text-jmix-300">
                            {file}
                          </span>
                        ))}
                      </div>
                    </article>
                  ))}
                  {workspace.launchConfigurations.length === 0 && (
                    <p className="rounded border border-dashed border-surface-border p-2 text-[9px] leading-4 text-gray-500">
                      No reviewed IntelliJ launch configuration declares profiles or an environment file.
                    </p>
                  )}
                </div>
              </section>
            </aside>
          </div>

          {preview && (
            <section className="mx-3 mb-3 rounded border border-jmix-800/70 bg-jmix-950/20 p-3 sm:mx-4 sm:mb-4" aria-label="Environment change review">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <h4 className="text-xs font-semibold text-jmix-200">{preview.label}</h4>
                  <p className="mt-1 text-[9px] leading-4 text-gray-500">
                    Focused preview only. Unrelated environment values and all secrets are intentionally omitted.
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    onClick={() => void (pendingConnection ? applyConnection() : applyVariable())}
                    disabled={applying || !preview.accepted || !preview.planDigest}
                    className="min-h-10 rounded bg-emerald-700 px-3 py-2 text-[11px] font-medium text-white hover:bg-emerald-600 focus-visible:outline focus-visible:outline-2 focus-visible:outline-emerald-400 disabled:opacity-50"
                  >
                    {applying ? 'Applying…' : 'Approve and apply'}
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      clearReview()
                      setNotice(null)
                    }}
                    disabled={applying}
                    className="min-h-10 rounded border border-surface-border px-3 py-2 text-[11px] text-gray-300 hover:border-gray-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-jmix-400 disabled:opacity-50"
                  >
                    Discard
                  </button>
                </div>
              </div>
              <div className="mt-2 grid min-w-0 grid-cols-1 gap-2 lg:grid-cols-2">
                {preview.files.map(file => (
                  <article key={file.relativePath} className="min-w-0 rounded border border-surface-border bg-surface p-2.5">
                    <code className="block break-all text-[9px] text-gray-400">{file.relativePath}</code>
                    <pre className="mt-2 max-h-32 min-w-0 overflow-auto whitespace-pre-wrap break-all rounded bg-black/20 p-2 text-[9px] leading-4 text-gray-300">
                      {file.resultContent}
                    </pre>
                  </article>
                ))}
              </div>
            </section>
          )}
        </>
      )}
    </section>
  )
}
