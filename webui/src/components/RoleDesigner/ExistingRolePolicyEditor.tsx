import { useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle, ArrowLeft, Code2, Eye, FilePenLine, Loader2, Pencil,
  Plus, ShieldCheck, Trash2, X,
} from 'lucide-react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  SecurityRolePolicyChangeRequest,
  SecurityRolePolicyEditorSnapshot,
  SecurityRolePolicyModel,
  SecurityRolePolicyRemovalRequest,
  SecurityRolePolicyReplacementRequest,
  SecurityRolePolicyType,
  SecurityRoleSnapshot,
  WorkspaceChangePreviewResponse,
} from '../../types'

type EditorMode = 'list' | 'add' | 'edit'
type PreviewOperation =
  | { kind: 'add'; change: SecurityRolePolicyChangeRequest; response: WorkspaceChangePreviewResponse }
  | { kind: 'replace'; change: SecurityRolePolicyReplacementRequest; response: WorkspaceChangePreviewResponse }
  | { kind: 'remove'; change: SecurityRolePolicyRemovalRequest; response: WorkspaceChangePreviewResponse }

const CRUD_ACTIONS = ['create', 'read', 'update', 'delete'] as const

const primaryButton =
  'inline-flex items-center justify-center gap-1.5 rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-jmix-600 disabled:cursor-not-allowed disabled:opacity-50'
const quietButton =
  'inline-flex items-center justify-center gap-1.5 rounded border border-surface-border bg-surface-lighter px-3 py-1.5 text-xs text-gray-300 transition-colors hover:border-jmix-500/60 hover:text-jmix-300 disabled:opacity-50'

const resourcePolicyTypes: { id: SecurityRolePolicyType; label: string }[] = [
  { id: 'entity', label: 'Entity CRUD' },
  { id: 'entityAttribute', label: 'Entity attributes' },
  { id: 'menu', label: 'Menu access' },
  { id: 'view', label: 'View access' },
  { id: 'specific', label: 'Specific permission' },
]

const rowPolicyTypes: { id: SecurityRolePolicyType; label: string }[] = [
  { id: 'jpqlRow', label: 'JPQL row filter' },
  { id: 'predicateRow', label: 'Java predicate' },
]

function initialPolicy(role: SecurityRoleSnapshot): SecurityRolePolicyModel {
  return {
    type: role.kind === 'RESOURCE' ? 'entity' : 'jpqlRow',
    entityClass: '',
    entityActions: ['read'],
    allEntityActions: false,
    attributes: [],
    attributeAction: 'view',
    resources: [],
    rowActions: ['read'],
    whereClause: '',
    joinClause: '',
    predicateExpression: '',
    allowWildcard: false,
  }
}

function splitValues(value: string): string[] {
  return value.split(',').map((entry) => entry.trim()).filter(Boolean)
}

function policyLabel(type: SecurityRolePolicyType): string {
  return [...resourcePolicyTypes, ...rowPolicyTypes].find((candidate) => candidate.id === type)?.label ?? type
}

function policySummary(snapshot: SecurityRolePolicyEditorSnapshot): string {
  const policy = snapshot.policy
  if (!policy) return snapshot.annotationText
  if (policy.entityClass) {
    const actions = policy.allEntityActions
      ? 'ALL'
      : [...policy.entityActions, ...policy.rowActions].map((value) => value.toUpperCase()).join(', ')
    return `${policy.entityClass}${actions ? ` · ${actions}` : ''}`
  }
  return policy.resources.join(', ') || snapshot.methodName
}

export default function ExistingRolePolicyEditor({
  role,
  onClose,
  onApplied,
}: {
  role: SecurityRoleSnapshot
  onClose: () => void
  onApplied: () => void
}) {
  const addToast = useStore((state) => state.addToast)
  const setLastResult = useStore((state) => state.setLastResult)
  const [policy, setPolicy] = useState(() => initialPolicy(role))
  const [mode, setMode] = useState<EditorMode>('list')
  const [existingPolicies, setExistingPolicies] = useState<SecurityRolePolicyEditorSnapshot[]>([])
  const [selectedPolicy, setSelectedPolicy] = useState<SecurityRolePolicyEditorSnapshot | null>(null)
  const [inspectionError, setInspectionError] = useState<string | null>(null)
  const [preview, setPreview] = useState<PreviewOperation | null>(null)
  const [working, setWorking] = useState(false)

  useEffect(() => {
    let active = true
    setWorking(true)
    void bridge.inspectSecurityRolePolicies({
      roleLocator: role.sourceLocator,
      roleClassName: role.className,
    }).then((response) => {
      if (!active) return
      if (!response.accepted) {
        setInspectionError(response.issues[0]?.message ?? 'Existing policies could not be inspected.')
        return
      }
      setExistingPolicies(response.policies)
      setInspectionError(null)
    }).catch(() => {
      if (active) setInspectionError('Policy inspection failed — bridge unavailable.')
    }).finally(() => {
      if (active) setWorking(false)
    })
    return () => {
      active = false
    }
  }, [role.className, role.sourceLocator])

  const policyTypes = role.kind === 'RESOURCE' ? resourcePolicyTypes : rowPolicyTypes
  const needsEntity = policy.type === 'entity' ||
    policy.type === 'entityAttribute' ||
    policy.type === 'jpqlRow' ||
    policy.type === 'predicateRow'
  const needsResources = policy.type === 'menu' ||
    policy.type === 'view' ||
    policy.type === 'specific'
  const wildcardPresent = policy.attributes.includes('*') || policy.resources.includes('*')
  const sourceFile = preview?.response.files[0]

  const validation = useMemo(() => {
    if (needsEntity && !policy.entityClass?.trim()) return 'Choose or enter a fully qualified entity class.'
    if (needsEntity && !policy.entityClass?.includes('.')) return 'Use the fully qualified entity class name.'
    if (policy.type === 'entity' && !policy.allEntityActions && policy.entityActions.length === 0) {
      return 'Select at least one CRUD action.'
    }
    if (policy.type === 'entityAttribute' && policy.attributes.length === 0) {
      return 'Enter at least one entity attribute.'
    }
    if (needsResources && policy.resources.length === 0) return 'Enter at least one resource identifier.'
    if (policy.type === 'jpqlRow') {
      const where = policy.whereClause?.trim() ?? ''
      if (!where) return 'Enter a bounded JPQL where expression.'
      if (/^where\s/i.test(where)) return 'Enter the expression without the where keyword.'
      if (!where.includes('{E}') && !policy.joinClause?.includes('{E}')) {
        return 'Use the {E} entity placeholder in the where or join expression.'
      }
    }
    if (policy.type === 'predicateRow') {
      if (policy.rowActions.length === 0) return 'Select at least one protected CRUD action.'
      if (!policy.predicateExpression?.trim()) return 'Enter an explicit boolean Java expression.'
    }
    if (wildcardPresent && !policy.allowWildcard) return 'Acknowledge the wildcard grant before review.'
    return null
  }, [needsEntity, needsResources, policy, wildcardPresent])

  const changeType = (type: SecurityRolePolicyType) => {
    setPreview(null)
    setPolicy((current) => ({ ...current, type, allowWildcard: false }))
  }

  const startAdd = () => {
    setPreview(null)
    setSelectedPolicy(null)
    setPolicy(initialPolicy(role))
    setMode('add')
  }

  const startEdit = (snapshot: SecurityRolePolicyEditorSnapshot) => {
    if (!snapshot.editable || !snapshot.policy) {
      addToast(snapshot.editIssue ?? 'This policy is protected from visual replacement.', 'error')
      return
    }
    setPreview(null)
    setSelectedPolicy(snapshot)
    setPolicy({
      ...snapshot.policy,
      entityActions: [...snapshot.policy.entityActions],
      attributes: [...snapshot.policy.attributes],
      resources: [...snapshot.policy.resources],
      rowActions: [...snapshot.policy.rowActions],
    })
    setMode('edit')
  }

  const toggleEntityAction = (action: typeof CRUD_ACTIONS[number]) => {
    setPreview(null)
    setPolicy((current) => {
      const selected = current.entityActions.includes(action)
      const actions = selected
        ? current.entityActions.filter((value) => value !== action)
        : [...current.entityActions, action]
      return { ...current, entityActions: actions, allEntityActions: actions.length === CRUD_ACTIONS.length }
    })
  }

  const toggleRowAction = (action: typeof CRUD_ACTIONS[number]) => {
    setPreview(null)
    setPolicy((current) => {
      const selected = current.rowActions.includes(action)
      return {
        ...current,
        rowActions: selected
          ? current.rowActions.filter((value) => value !== action)
          : [...current.rowActions, action],
      }
    })
  }

  const review = async () => {
    if (validation) {
      addToast(validation, 'error')
      return
    }
    setWorking(true)
    try {
      const change = mode === 'edit' && selectedPolicy
        ? {
            roleLocator: role.sourceLocator,
            roleClassName: role.className,
            policyLocator: selectedPolicy.locator,
            replacement: policy,
          } satisfies SecurityRolePolicyReplacementRequest
        : {
            roleLocator: role.sourceLocator,
            roleClassName: role.className,
            policy,
          } satisfies SecurityRolePolicyChangeRequest
      const response = mode === 'edit'
        ? await bridge.previewSecurityRolePolicyReplacement(change as SecurityRolePolicyReplacementRequest)
        : await bridge.previewSecurityRolePolicyAddition(change as SecurityRolePolicyChangeRequest)
      if (!response.accepted || !response.planDigest || response.files.length !== 1) {
        addToast(response.issues[0]?.message ?? 'The existing role change was rejected.', 'error')
        return
      }
      setPreview(mode === 'edit'
        ? { kind: 'replace', change: change as SecurityRolePolicyReplacementRequest, response }
        : { kind: 'add', change: change as SecurityRolePolicyChangeRequest, response })
    } catch {
      addToast('Existing role preview failed — bridge unavailable.', 'error')
    } finally {
      setWorking(false)
    }
  }

  const reviewRemoval = async (snapshot: SecurityRolePolicyEditorSnapshot) => {
    const change: SecurityRolePolicyRemovalRequest = {
      roleLocator: role.sourceLocator,
      roleClassName: role.className,
      policyLocator: snapshot.locator,
    }
    setSelectedPolicy(snapshot)
    setWorking(true)
    try {
      const response = await bridge.previewSecurityRolePolicyRemoval(change)
      if (!response.accepted || !response.planDigest || response.files.length !== 1) {
        addToast(response.issues[0]?.message ?? 'The policy removal was rejected.', 'error')
        return
      }
      setPreview({ kind: 'remove', change, response })
    } catch {
      addToast('Policy removal preview failed — bridge unavailable.', 'error')
    } finally {
      setWorking(false)
    }
  }

  const apply = async () => {
    if (!preview?.response.planDigest) return
    setWorking(true)
    try {
      const result = preview.kind === 'add'
        ? await bridge.applySecurityRolePolicyAddition(preview.change, preview.response.planDigest)
        : preview.kind === 'replace'
          ? await bridge.applySecurityRolePolicyReplacement(preview.change, preview.response.planDigest)
          : await bridge.applySecurityRolePolicyRemoval(preview.change, preview.response.planDigest)
      setLastResult({
        success: result.success,
        filesWritten: result.filesChanged,
        errors: result.issues.map((issue) => `${issue.code}: ${issue.message}`),
      })
      if (!result.success) {
        addToast(result.issues[0]?.message ?? 'The role changed after preview; refresh and review again.', 'error')
        return
      }
      addToast(
        preview.kind === 'add'
          ? `Added policy to ${role.name} without replacing manual source.`
          : preview.kind === 'replace'
            ? `Updated ${selectedPolicy ? policyLabel(selectedPolicy.type) : 'policy'} with a revision-safe edit.`
            : `Removed ${selectedPolicy ? policyLabel(selectedPolicy.type) : 'policy'} after exact source review.`,
        'success',
      )
      onApplied()
    } catch {
      addToast('Existing role update failed — bridge unavailable.', 'error')
    } finally {
      setWorking(false)
    }
  }

  return (
    <div
      className="absolute inset-0 z-50 flex min-h-0 min-w-0 items-stretch justify-center bg-black/65 p-2 backdrop-blur-sm sm:p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="existing-role-policy-title"
    >
      <section className="flex min-h-0 min-w-0 w-full max-w-5xl flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl shadow-black/60">
        <header className="flex min-w-0 shrink-0 items-start gap-3 border-b border-surface-border bg-surface-light px-3 py-3 sm:px-4">
          {preview ? <Code2 size={17} className="mt-0.5 shrink-0 text-jmix-400" /> : <FilePenLine size={17} className="mt-0.5 shrink-0 text-jmix-400" />}
          <div className="min-w-0 flex-1">
            <h3 id="existing-role-policy-title" className="text-sm font-semibold text-gray-100">
              {preview
                ? preview.kind === 'remove' ? 'Review policy removal' : 'Review targeted role edit'
                : mode === 'list'
                  ? `Manage policies · ${role.name}`
                  : mode === 'edit'
                    ? `Edit ${selectedPolicy ? policyLabel(selectedPolicy.type) : 'policy'}`
                    : `Add policy to ${role.name}`}
            </h3>
            <p className="mt-0.5 break-all font-mono text-[10px] text-gray-500">
              {role.sourceLocator.relativePath} · {role.code}
            </p>
          </div>
          <button type="button" onClick={onClose} className="rounded p-1 text-gray-500 hover:bg-surface-lighter hover:text-gray-200" aria-label="Close policy editor">
            <X size={15} />
          </button>
        </header>

        {preview && sourceFile ? (
          <>
            <div className="min-h-0 min-w-0 flex-1 overflow-auto bg-[#161621]">
              <pre className="min-w-max p-3 font-mono text-[11px] leading-relaxed text-gray-200 sm:p-4">
                <code>{sourceFile.resultContent}</code>
              </pre>
            </div>
            <div className="shrink-0 border-t border-surface-border bg-surface-light/60 px-3 py-2 text-[10px] text-gray-500 sm:px-4">
              {sourceFile.appliedEditCount} targeted edit(s) · exact revision fingerprint required
              {preview.kind === 'remove'
                ? ' · only the selected policy declaration is removed'
                : ' · unrelated methods and comments remain untouched'}
            </div>
          </>
        ) : mode === 'list' ? (
          <div className="min-h-0 min-w-0 flex-1 overflow-auto p-3 sm:p-4">
            <div className="mx-auto flex w-full max-w-4xl flex-col gap-3">
              <div className="flex min-w-0 flex-wrap items-start justify-between gap-2 rounded-lg border border-sky-500/20 bg-sky-500/5 p-3">
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-sky-100">Round-trip source policies</div>
                  <p className="mt-1 max-w-2xl text-[10px] leading-relaxed text-sky-100/65">
                    Every operation is anchored to the indexed file revision and exact annotation ordinal.
                    Composite methods, custom predicates, imports and manual comments are protected.
                  </p>
                </div>
                <button type="button" onClick={startAdd} className={primaryButton} disabled={working}>
                  <Plus size={13} /> Add policy
                </button>
              </div>

              {working && existingPolicies.length === 0 && (
                <div className="flex min-h-32 items-center justify-center gap-2 rounded-lg border border-surface-border text-xs text-gray-400">
                  <Loader2 size={14} className="animate-spin text-jmix-400" /> Inspecting exact Java source…
                </div>
              )}

              {inspectionError && (
                <div className="flex items-start gap-2 rounded-lg border border-red-500/35 bg-red-500/10 p-3 text-xs text-red-100">
                  <AlertTriangle size={14} className="mt-0.5 shrink-0" />
                  <div>
                    <div className="font-semibold">Policy inspection unavailable</div>
                    <p className="mt-1 text-[10px] leading-relaxed text-red-100/70">{inspectionError}</p>
                  </div>
                </div>
              )}

              {!working && !inspectionError && existingPolicies.length === 0 && (
                <div className="flex min-h-40 flex-col items-center justify-center rounded-lg border border-dashed border-surface-border p-6 text-center">
                  <ShieldCheck size={20} className="text-gray-700" />
                  <div className="mt-2 text-xs font-medium text-gray-300">This role has no source policies</div>
                  <p className="mt-1 max-w-md text-[10px] leading-relaxed text-gray-500">
                    Add the first narrowly scoped policy. The plugin will show the exact Java file before writing.
                  </p>
                </div>
              )}

              {existingPolicies.map((snapshot) => (
                <article
                  key={snapshot.id}
                  className="min-w-0 rounded-lg border border-surface-border bg-surface-light/55 p-3"
                >
                  <div className="flex min-w-0 flex-wrap items-start gap-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="rounded bg-jmix-500/15 px-2 py-0.5 text-[10px] font-semibold text-jmix-200">
                          {policyLabel(snapshot.type)}
                        </span>
                        <span className="font-mono text-[9px] text-gray-600">{snapshot.methodName}()</span>
                        {!snapshot.editable && (
                          <span className="rounded border border-amber-500/30 bg-amber-500/10 px-1.5 py-0.5 text-[9px] text-amber-200">
                            source-managed
                          </span>
                        )}
                      </div>
                      <div className="mt-2 break-all text-[10px] text-gray-300">{policySummary(snapshot)}</div>
                      <div className="mt-1.5 overflow-hidden text-ellipsis whitespace-nowrap font-mono text-[9px] text-gray-600" title={snapshot.annotationText}>
                        {snapshot.annotationText}
                      </div>
                      {snapshot.editIssue && (
                        <p className="mt-2 text-[9px] leading-relaxed text-amber-200/70">{snapshot.editIssue}</p>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-1.5">
                      <button
                        type="button"
                        onClick={() => startEdit(snapshot)}
                        disabled={!snapshot.editable || working}
                        className={quietButton}
                        title={snapshot.editIssue ?? 'Edit with exact source preview'}
                      >
                        <Pencil size={12} /> Edit
                      </button>
                      <button
                        type="button"
                        onClick={() => void reviewRemoval(snapshot)}
                        disabled={working}
                        className="inline-flex items-center justify-center gap-1.5 rounded border border-red-500/30 bg-red-500/10 px-3 py-1.5 text-xs text-red-200 transition-colors hover:border-red-400 disabled:opacity-50"
                      >
                        <Trash2 size={12} /> Remove
                      </button>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          </div>
        ) : (
          <div className="min-h-0 min-w-0 flex-1 overflow-auto p-3 sm:p-4">
            <div className="grid min-w-0 gap-4 lg:grid-cols-[15rem_minmax(0,1fr)]">
              <aside className="min-w-0 space-y-1">
                <div className="mb-2 text-[10px] font-semibold uppercase tracking-widest text-gray-500">Policy type</div>
                {policyTypes.map((candidate) => (
                  <button
                    type="button"
                    key={candidate.id}
                    onClick={() => changeType(candidate.id)}
                    className={`w-full rounded border px-3 py-2 text-left text-xs transition-colors ${
                      policy.type === candidate.id
                        ? 'border-jmix-500/60 bg-jmix-500/15 text-jmix-200'
                        : 'border-transparent bg-surface-light text-gray-400 hover:border-surface-border hover:text-gray-200'
                    }`}
                  >
                    {candidate.label}
                  </button>
                ))}
              </aside>

              <main className="min-w-0 space-y-4 rounded-lg border border-surface-border bg-surface-light/40 p-3 sm:p-4">
                {needsEntity && (
                  <label className="block">
                    <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Entity class *</span>
                    <input
                      value={policy.entityClass ?? ''}
                      onChange={(event) => {
                        setPreview(null)
                        setPolicy((current) => ({ ...current, entityClass: event.target.value }))
                      }}
                      className="w-full font-mono text-xs"
                      placeholder="com.company.loan.entity.LoanApp"
                    />
                  </label>
                )}

                {policy.type === 'entity' && (
                  <div>
                    <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-gray-500">Allowed CRUD operations</div>
                    <div className="flex flex-wrap gap-1.5">
                      {CRUD_ACTIONS.map((action) => (
                        <button
                          type="button"
                          key={action}
                          onClick={() => toggleEntityAction(action)}
                          aria-pressed={policy.entityActions.includes(action)}
                          className={`rounded border px-2 py-1 text-[10px] font-semibold uppercase ${
                            policy.entityActions.includes(action)
                              ? 'border-jmix-500 bg-jmix-500/15 text-jmix-200'
                              : 'border-surface-border bg-surface text-gray-500'
                          }`}
                        >
                          {action}
                        </button>
                      ))}
                      <button
                        type="button"
                        onClick={() => setPolicy((current) => ({
                          ...current,
                          allEntityActions: !current.allEntityActions,
                          entityActions: !current.allEntityActions ? [...CRUD_ACTIONS] : [],
                        }))}
                        className={`rounded border px-2 py-1 text-[10px] font-semibold uppercase ${
                          policy.allEntityActions
                            ? 'border-amber-500 bg-amber-500/15 text-amber-200'
                            : 'border-surface-border bg-surface text-gray-500'
                        }`}
                      >
                        All
                      </button>
                    </div>
                  </div>
                )}

                {policy.type === 'entityAttribute' && (
                  <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_9rem]">
                    <label className="block">
                      <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Attributes *</span>
                      <input
                        value={policy.attributes.join(', ')}
                        onChange={(event) => setPolicy((current) => ({ ...current, attributes: splitValues(event.target.value) }))}
                        className="w-full font-mono text-xs"
                        placeholder="status, approvedAt"
                      />
                    </label>
                    <label className="block">
                      <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Access</span>
                      <select
                        value={policy.attributeAction}
                        onChange={(event) => setPolicy((current) => ({
                          ...current,
                          attributeAction: event.target.value as 'view' | 'modify',
                        }))}
                        className="w-full text-xs"
                      >
                        <option value="view">View</option>
                        <option value="modify">Modify</option>
                      </select>
                    </label>
                  </div>
                )}

                {needsResources && (
                  <label className="block">
                    <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">
                      {policy.type === 'menu' ? 'Menu IDs' : policy.type === 'view' ? 'View IDs' : 'Permission resources'} *
                    </span>
                    <input
                      value={policy.resources.join(', ')}
                      onChange={(event) => setPolicy((current) => ({ ...current, resources: splitValues(event.target.value) }))}
                      className="w-full font-mono text-xs"
                      placeholder={policy.type === 'menu' ? 'loanApplications' : policy.type === 'view' ? 'payroll_LoanApp.list' : 'rest.enabled'}
                    />
                  </label>
                )}

                {policy.type === 'jpqlRow' && (
                  <div className="space-y-3">
                    <label className="block">
                      <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Where expression *</span>
                      <input
                        value={policy.whereClause ?? ''}
                        onChange={(event) => setPolicy((current) => ({ ...current, whereClause: event.target.value }))}
                        className="w-full font-mono text-xs"
                        placeholder="{E}.employee.username = :current_user_username"
                      />
                    </label>
                    <label className="block">
                      <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Optional join</span>
                      <input
                        value={policy.joinClause ?? ''}
                        onChange={(event) => setPolicy((current) => ({ ...current, joinClause: event.target.value }))}
                        className="w-full font-mono text-xs"
                        placeholder="left join {E}.employee employee"
                      />
                    </label>
                  </div>
                )}

                {policy.type === 'predicateRow' && (
                  <div className="space-y-3">
                    <div>
                      <div className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-gray-500">Protected operations</div>
                      <div className="flex flex-wrap gap-1.5">
                        {CRUD_ACTIONS.map((action) => (
                          <button
                            type="button"
                            key={action}
                            onClick={() => toggleRowAction(action)}
                            aria-pressed={policy.rowActions.includes(action)}
                            className={`rounded border px-2 py-1 text-[10px] font-semibold uppercase ${
                              policy.rowActions.includes(action)
                                ? 'border-jmix-500 bg-jmix-500/15 text-jmix-200'
                                : 'border-surface-border bg-surface text-gray-500'
                            }`}
                          >
                            {action}
                          </button>
                        ))}
                      </div>
                    </div>
                    <label className="block">
                      <span className="mb-1 block text-[10px] font-semibold uppercase tracking-wider text-gray-500">Boolean Java expression *</span>
                      <input
                        value={policy.predicateExpression ?? ''}
                        onChange={(event) => setPolicy((current) => ({ ...current, predicateExpression: event.target.value }))}
                        className="w-full font-mono text-xs"
                        placeholder="entity.getClosedAt() == null"
                      />
                    </label>
                  </div>
                )}

                {wildcardPresent && (
                  <label className="flex items-start gap-2 rounded border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-[10px] leading-relaxed text-amber-100">
                    <input
                      type="checkbox"
                      checked={policy.allowWildcard}
                      onChange={(event) => setPolicy((current) => ({ ...current, allowWildcard: event.target.checked }))}
                      className="mt-0.5 h-3.5 w-3.5 shrink-0"
                    />
                    I understand that this wildcard grants broad access and have reviewed the affected application surfaces.
                  </label>
                )}

                {validation && (
                  <div className="flex items-start gap-2 rounded border border-amber-500/25 bg-amber-500/5 px-3 py-2 text-[10px] text-amber-100">
                    <AlertTriangle size={12} className="mt-0.5 shrink-0 text-amber-300" />
                    {validation}
                  </div>
                )}
              </main>
            </div>
          </div>
        )}

        <footer className="flex min-w-0 shrink-0 flex-wrap items-center justify-between gap-2 border-t border-surface-border bg-surface-light px-3 py-2.5 sm:px-4">
          <p className="min-w-0 text-[10px] leading-relaxed text-gray-500">
            {preview
              ? preview.kind === 'remove'
                ? 'Removal is atomic and rejected if the source changes after this preview.'
                : 'Only the reviewed imports and selected annotation or policy method will change.'
              : mode === 'list'
                ? `${existingPolicies.length} policy declaration(s) inspected from the current source revision.`
                : 'The current file fingerprint is checked again immediately before the write.'}
          </p>
          <div className="flex min-w-0 flex-wrap items-center justify-end gap-2">
            {preview && (
              <button type="button" onClick={() => setPreview(null)} className={quietButton} disabled={working}>
                <ArrowLeft size={13} /> Back
              </button>
            )}
            {!preview && mode !== 'list' && (
              <button
                type="button"
                onClick={() => {
                  setMode('list')
                  setSelectedPolicy(null)
                  setPolicy(initialPolicy(role))
                }}
                className={quietButton}
                disabled={working}
              >
                <ArrowLeft size={13} /> Policies
              </button>
            )}
            <button type="button" onClick={onClose} className={quietButton} disabled={working}>
              {mode === 'list' && !preview ? 'Close' : 'Cancel'}
            </button>
            {(preview || mode !== 'list') && (
              <button
                type="button"
                onClick={() => void (preview ? apply() : review())}
                className={preview?.kind === 'remove'
                  ? 'inline-flex items-center justify-center gap-1.5 rounded bg-red-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-red-500 disabled:cursor-not-allowed disabled:opacity-50'
                  : primaryButton}
                disabled={working || (!preview && Boolean(validation))}
              >
                {working
                  ? <Loader2 size={13} className="animate-spin" />
                  : preview?.kind === 'remove'
                    ? <Trash2 size={13} />
                    : preview
                      ? <ShieldCheck size={13} />
                      : <Eye size={13} />}
                {preview
                  ? preview.kind === 'remove' ? 'Apply removal' : 'Apply targeted edit'
                  : mode === 'edit' ? 'Review exact replacement' : 'Review exact Java change'}
              </button>
            )}
          </div>
        </footer>
      </section>
    </div>
  )
}
