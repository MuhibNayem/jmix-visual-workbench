import { useState } from 'react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { CrudOptions, WorkspaceChangePreviewResponse } from '../../types'

const defaultOptions: CrudOptions = {
  generateEntity: true,
  generateMigration: true,
  generateDataRepository: false,
  generateFetchPlan: true,
  generateMenu: true,
  generateSecurityRole: true,
  generateMessages: true,
  listViewType: 'dataGrid',
  detailViewMode: 'form',
  includeFilter: true,
  includePagination: true,
  includeActions: true,
  menuIcon: 'vaadin:table',
  dbType: 'postgres',
}

export default function CrudWizard() {
  // Selective subscriptions avoid re-rendering on every store change (e.g.
  // each tab switch); this component only depends on the slices below.
  const entity = useStore((state) => state.entity)
  const crudEntityLocator = useStore((state) => state.crudEntityLocator)
  const addToast = useStore((state) => state.addToast)
  const isGenerating = useStore((state) => state.isGenerating)
  const setIsGenerating = useStore((state) => state.setIsGenerating)
  const existingEntityMode = crudEntityLocator !== null
  const [options, setOptions] = useState<CrudOptions>(() => ({
    ...defaultOptions,
    generateEntity: !existingEntityMode,
    existingEntitySource: crudEntityLocator ?? undefined,
    generateMigration: !existingEntityMode,
    generateSecurityRole: !existingEntityMode,
  }))
  const [step, setStep] = useState(0)
  const [result, setResult] = useState<{ files: string[]; errors: string[] } | null>(null)
  const [generationPreview, setGenerationPreview] = useState<WorkspaceChangePreviewResponse | null>(null)

  const steps = ['Entity', 'Options', 'Preview', 'Generate']

  const handleGenerate = async () => {
    if (!entity.className.trim()) {
      addToast('Define an entity first in the Entity Designer', 'error')
      return
    }
    setIsGenerating(true)
    setResult(null)
    setGenerationPreview(null)
    try {
      const preview = await bridge.previewCrudGeneration(entity, options)
      if (preview.accepted && preview.planDigest) {
        setGenerationPreview(preview)
        addToast(`CRUD preview ready: ${preview.files.length} atomic file changes`, 'info')
      } else {
        const errors = preview.issues.map(issue => issue.message)
        setResult({ files: [], errors })
        addToast('CRUD generation rejected', 'error')
      }
    } catch (e: any) {
      setResult({ files: [], errors: [e.message] })
      addToast(`Error: ${e.message}`, 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const handleApplyGeneration = async () => {
    if (!generationPreview?.planDigest) return
    setIsGenerating(true)
    try {
      const response = await bridge.applyCrudGeneration(entity, options, generationPreview.planDigest)
      if (response.success) {
        setResult({ files: response.filesChanged, errors: [] })
        setGenerationPreview(null)
        addToast(`CRUD generated atomically: ${response.filesChanged.length} files changed`, 'success')
      } else {
        const errors = response.issues.map(issue => issue.message)
        setResult({ files: [], errors })
        addToast('CRUD apply rejected; refresh the preview', 'error')
      }
    } catch (e: any) {
      setResult({ files: [], errors: [e.message] })
      addToast(`Error: ${e.message}`, 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const expectedFiles = getExpectedFiles(entity.className, options)

  return (
    <div className="flex h-full min-w-0 flex-col">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-border bg-surface-light px-3 py-2.5 sm:px-4">
        <div>
          <h2 className="text-sm font-semibold text-gray-200">
            {existingEntityMode ? 'Existing Entity View Wizard' : 'CRUD Scaffolding Wizard'}
          </h2>
          {existingEntityMode && (
            <p className="mt-0.5 text-[10px] text-emerald-300/80">
              Source-safe mode · entity and table creation are excluded
            </p>
          )}
        </div>
        <div className="flex min-w-[11rem] max-w-xs flex-1 items-center gap-2 sm:flex-none" aria-label={`Step ${step + 1} of ${steps.length}: ${steps[step]}`}>
          <div className="min-w-0 flex-1">
            <div className="mb-1 flex items-center justify-between gap-3 text-[10px]">
              <span className="truncate font-medium text-gray-300">{steps[step]}</span>
              <span className="shrink-0 text-gray-500">Step {step + 1} of {steps.length}</span>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-surface">
              <div
                className="h-full rounded-full bg-jmix-500 transition-[width] duration-200"
                style={{ width: `${((step + 1) / steps.length) * 100}%` }}
              />
            </div>
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 sm:p-6">
        {/* Step 0: Entity Summary */}
        {step === 0 && (
          <div className="max-w-2xl mx-auto">
            <h3 className="text-sm font-medium text-gray-200 mb-4">Entity: {entity.className || '(not defined)'}</h3>
            {entity.className ? (
              <div className="space-y-3">
                <InfoRow label="Package" value={entity.packageName} />
                <InfoRow label="Table" value={entity.tableName || entity.className.toUpperCase()} />
                <InfoRow label="Type" value={entity.entityType} />
                <InfoRow label="ID" value={`${entity.id.type} (${entity.id.generation})`} />
                <InfoRow label="Traits" value={entity.traits.join(', ') || 'none'} />
                <InfoRow label="Attributes" value={`${entity.attributes.length} attributes`} />
                <div className="mt-4 border border-surface-border rounded-lg overflow-hidden">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="bg-surface-light text-gray-400 text-left">
                        <th className="px-3 py-2">Name</th>
                        <th className="px-3 py-2">Type</th>
                        <th className="px-3 py-2">Mandatory</th>
                      </tr>
                    </thead>
                    <tbody>
                      {entity.attributes.map((attr, i) => (
                        <tr key={i} className="border-t border-surface-border">
                          <td className="px-3 py-1.5 text-gray-300">{attr.name}</td>
                          <td className="px-3 py-1.5 text-gray-400">{attr.type}</td>
                          <td className="px-3 py-1.5 text-gray-400">{attr.mandatory ? '✓' : '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="text-center py-16 text-gray-600">
                <p className="text-sm mb-2">No entity defined yet.</p>
                <p className="text-xs">Open the Entity Designer workspace to create an entity first.</p>
              </div>
            )}
            <div className="mt-6 flex justify-end">
              <button onClick={() => setStep(1)} className="px-4 py-2 text-xs rounded bg-jmix-500 text-white hover:bg-jmix-600">
                Next →
              </button>
            </div>
          </div>
        )}

        {/* Step 1: Options */}
        {step === 1 && (
          <div className="max-w-2xl mx-auto space-y-6">
            <h3 className="text-sm font-medium text-gray-200">Generation Options</h3>

            <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
              <div className="space-y-3">
                <h4 className="text-[10px] font-semibold text-gray-400 uppercase">Artifacts</h4>
                {!existingEntityMode && (
                  <Toggle label="Database Migration (Liquibase)" checked={options.generateMigration} onChange={v => setOptions({ ...options, generateMigration: v })} />
                )}
                <Toggle label="Data Repository" checked={options.generateDataRepository} onChange={v => setOptions({ ...options, generateDataRepository: v })} />
                <Toggle label="Fetch Plans" checked={options.generateFetchPlan} onChange={v => setOptions({ ...options, generateFetchPlan: v })} />
                <Toggle label="Menu entry" checked={options.generateMenu} onChange={v => setOptions({ ...options, generateMenu: v })} />
                <Toggle label="Localization messages" checked={options.generateMessages} onChange={v => setOptions({ ...options, generateMessages: v })} />
                <Toggle
                  label={existingEntityMode ? 'New full-access role (explicit opt-in)' : 'Full-access resource role'}
                  checked={options.generateSecurityRole}
                  onChange={v => setOptions({ ...options, generateSecurityRole: v })}
                />
                <Toggle label="Generic Filter" checked={options.includeFilter} onChange={v => setOptions({ ...options, includeFilter: v })} />
                <Toggle label="Pagination" checked={options.includePagination} onChange={v => setOptions({ ...options, includePagination: v })} />
                <Toggle label="CRUD Actions (Create/Edit/Remove)" checked={options.includeActions} onChange={v => setOptions({ ...options, includeActions: v })} />
              </div>

              <div className="space-y-3">
                <h4 className="text-[10px] font-semibold text-gray-400 uppercase">UI Style</h4>
                <div>
                  <label className="block text-[10px] text-gray-500 mb-1">List View Type</label>
                  <select value={options.listViewType} onChange={e => setOptions({ ...options, listViewType: e.target.value as any })} className="w-full">
                    <option value="dataGrid">DataGrid (Table)</option>
                    <option value="treeDataGrid">Tree DataGrid</option>
                    <option value="virtualList">Virtual List</option>
                  </select>
                </div>
                <div>
                  <label className="block text-[10px] text-gray-500 mb-1">Detail View Mode</label>
                  <select value={options.detailViewMode} onChange={e => setOptions({ ...options, detailViewMode: e.target.value as any })} className="w-full">
                    <option value="form">Form Layout</option>
                    <option value="tabbed">Tabbed</option>
                    <option value="sidePanel">Side Panel</option>
                  </select>
                </div>
                {!existingEntityMode && (
                  <div>
                    <label className="block text-[10px] text-gray-500 mb-1">Database Type</label>
                    <select value={options.dbType} onChange={e => setOptions({ ...options, dbType: e.target.value })} className="w-full">
                      <option value="postgres">PostgreSQL</option>
                      <option value="mysql">MySQL</option>
                      <option value="mssql">MS SQL Server</option>
                      <option value="oracle">Oracle</option>
                      <option value="hsqldb">HSQLDB</option>
                    </select>
                  </div>
                )}
                <div>
                  <label className="block text-[10px] text-gray-500 mb-1">Menu Icon</label>
                  <input value={options.menuIcon || ''} onChange={e => setOptions({ ...options, menuIcon: e.target.value })} className="w-full" placeholder="vaadin:table" />
                </div>
              </div>
            </div>

            <div className="flex justify-between mt-6">
              <button onClick={() => setStep(0)} className="px-4 py-2 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border">
                ← Back
              </button>
              <button onClick={() => setStep(2)} className="px-4 py-2 text-xs rounded bg-jmix-500 text-white hover:bg-jmix-600">
                Next →
              </button>
            </div>
          </div>
        )}

        {/* Step 2: Preview */}
        {step === 2 && (
          <div className="max-w-2xl mx-auto">
            <h3 className="text-sm font-medium text-gray-200 mb-4">Files to Generate</h3>
            <div className="space-y-1.5">
              {expectedFiles.map((file, i) => (
                <div key={i} className="flex items-center gap-2 text-xs text-gray-300 py-1.5 px-3 bg-surface-lighter rounded">
                  <span className="text-jmix-400">📄</span>
                  <span className="min-w-0 break-all font-mono text-[11px]">{file}</span>
                </div>
              ))}
            </div>
            <p className="mt-4 text-xs text-gray-500">
              Total: {expectedFiles.length} files will be generated for entity "{entity.className}".
            </p>
            <div className="flex justify-between mt-6">
              <button onClick={() => setStep(1)} className="px-4 py-2 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border">
                ← Back
              </button>
              <button onClick={() => setStep(3)} className="px-4 py-2 text-xs rounded bg-jmix-500 text-white hover:bg-jmix-600">
                Next →
              </button>
            </div>
          </div>
        )}

        {/* Step 3: Generate */}
        {step === 3 && (
          <div className="max-w-2xl mx-auto text-center">
            <h3 className="text-sm font-medium text-gray-200 mb-6">Ready to Generate</h3>

            {!result && !generationPreview ? (
              <button
                onClick={handleGenerate}
                disabled={isGenerating || !entity.className}
                className="px-8 py-3 text-sm rounded-lg bg-jmix-500 text-white font-medium hover:bg-jmix-600 disabled:opacity-50 transition-colors shadow-lg shadow-jmix-500/20"
              >
                {isGenerating ? (
                  <span className="flex items-center gap-2">
                    <span className="animate-spin">⏳</span> Planning...
                  </span>
                ) : (
                  existingEntityMode
                    ? '⚡ Preview views and UI support'
                    : '⚡ Preview Full CRUD Stack'
                )}
              </button>
            ) : generationPreview ? (
              <div className="space-y-4 text-left">
                <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4">
                  <h4 className="text-xs font-semibold text-amber-200">
                    Review {generationPreview.files.length} source-safe changes
                  </h4>
                  <p className="mt-1 text-[10px] leading-relaxed text-amber-100/60">
                    Existing menu and message files are merged at exact source locations. New Java, FlowUI, security,
                    fetch-plan, and Liquibase files are created only if their destinations remain unchanged.
                    {existingEntityMode && ' The indexed entity source and its database table are never regenerated.'}
                  </p>
                  <div className="mt-3 max-h-72 space-y-1 overflow-auto">
                    {generationPreview.files.map((file) => (
                      <div
                        key={file.relativePath}
                        className="flex min-w-0 items-center gap-2 rounded border border-amber-500/15 bg-black/15 px-2 py-1.5"
                      >
                        <span className="shrink-0 rounded bg-amber-500/10 px-1.5 py-0.5 text-[9px] text-amber-200">
                          {file.mode}
                        </span>
                        <span className="min-w-0 truncate font-mono text-[10px] text-amber-100/70">
                          {file.relativePath}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
                <div className="flex flex-wrap justify-end gap-2">
                  <button
                    onClick={() => setGenerationPreview(null)}
                    className="rounded bg-surface-lighter px-4 py-2 text-xs text-gray-300 hover:bg-surface-border"
                  >
                    Discard
                  </button>
                  <button
                    onClick={handleApplyGeneration}
                    disabled={isGenerating}
                    className="rounded bg-jmix-500 px-4 py-2 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
                  >
                    {isGenerating ? 'Applying…' : 'Apply atomic CRUD change'}
                  </button>
                </div>
              </div>
            ) : (
              <div className="text-left space-y-4">
                {result!.errors.length > 0 ? (
                  <div className="p-4 bg-red-900/20 border border-red-800 rounded-lg">
                    <h4 className="text-xs font-semibold text-red-300 mb-2">Errors</h4>
                    {result!.errors.map((e, i) => (
                      <p key={i} className="text-xs text-red-400">{e}</p>
                    ))}
                  </div>
                ) : (
                  <div className="p-4 bg-green-900/20 border border-green-800 rounded-lg">
                    <h4 className="text-xs font-semibold text-green-300 mb-2">
                      ✓ Generated {result!.files.length} files
                    </h4>
                    {result!.files.map((f, i) => (
                      <p key={i} className="text-xs text-green-400 font-mono py-0.5">{f}</p>
                    ))}
                  </div>
                )}
                <button
                  onClick={() => setResult(null)}
                  className="px-4 py-2 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border"
                >
                  Generate Again
                </button>
              </div>
            )}

            <div className="flex justify-start mt-6">
              <button onClick={() => setStep(2)} className="px-4 py-2 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border">
                ← Back
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex text-xs">
      <span className="w-24 text-gray-500 flex-shrink-0">{label}</span>
      <span className="text-gray-300 font-mono">{value}</span>
    </div>
  )
}

function Toggle({ label, checked, onChange }: { label: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
      <input type="checkbox" checked={checked} onChange={e => onChange(e.target.checked)} className="rounded border-surface-border" />
      {label}
    </label>
  )
}

function getExpectedFiles(entityName: string, options: CrudOptions): string[] {
  if (!entityName) return ['(define an entity first)']
  const files = [
    `view/${entityName}ListView.xml`,
    `view/${entityName}ListView.java`,
    `view/${entityName}DetailView.xml`,
    `view/${entityName}DetailView.java`,
  ]
  if (options.generateMenu) files.push('menu.xml (entry)')
  if (options.generateSecurityRole) files.push(`security/${entityName}Role.java`)
  if (options.generateMessages) files.push('messages.properties')
  if (options.generateEntity) files.unshift(`entity/${entityName}.java`)
  if (options.generateEntity && options.generateMigration) {
    files.splice(1, 0, `db/changelog/001-${entityName.toLowerCase()}.xml`)
  }
  if (options.generateDataRepository) files.push(`entity/${entityName}Repository.java`)
  if (options.generateFetchPlan) files.push(`entity/${entityName}-fetch-plans.xml`)
  return files
}
