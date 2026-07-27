import { useState } from 'react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { CrudOptions } from '../../types'

const defaultOptions: CrudOptions = {
  generateMigration: true,
  generateDataRepository: false,
  generateFetchPlan: true,
  listViewType: 'dataGrid',
  detailViewMode: 'form',
  includeFilter: true,
  includePagination: true,
  includeActions: true,
  menuIcon: 'vaadin:table',
  dbType: 'postgres',
}

export default function CrudWizard() {
  const { entity, addToast, isGenerating, setIsGenerating } = useStore()
  const [options, setOptions] = useState<CrudOptions>(defaultOptions)
  const [step, setStep] = useState(0)
  const [result, setResult] = useState<{ files: string[]; errors: string[] } | null>(null)

  const steps = ['Entity', 'Options', 'Preview', 'Generate']

  const handleGenerate = async () => {
    if (!entity.className.trim()) {
      addToast('Define an entity first in the Entity Designer tab', 'error')
      return
    }
    setIsGenerating(true)
    setResult(null)
    try {
      const res = await bridge.generateCrud(entity, options)
      if (res.success) {
        setResult({ files: res.filesWritten, errors: [] })
        addToast(`CRUD generated: ${res.filesWritten.length} files created`, 'success')
      } else {
        setResult({ files: [], errors: res.errors })
        addToast(`CRUD generation failed`, 'error')
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
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-surface-border bg-surface-light">
        <h2 className="text-sm font-semibold text-gray-200">CRUD Scaffolding Wizard</h2>
        <div className="flex items-center gap-1">
          {steps.map((s, i) => (
            <button
              key={s}
              onClick={() => setStep(i)}
              className={`px-3 py-1 text-xs rounded transition-colors ${
                step === i
                  ? 'bg-jmix-500 text-white'
                  : 'text-gray-400 hover:text-gray-200'
              }`}
            >
              {i + 1}. {s}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6">
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
                <p className="text-xs">Go to the Entity Designer tab to create an entity first.</p>
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

            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-3">
                <h4 className="text-[10px] font-semibold text-gray-400 uppercase">Artifacts</h4>
                <Toggle label="Database Migration (Liquibase)" checked={options.generateMigration} onChange={v => setOptions({ ...options, generateMigration: v })} />
                <Toggle label="Data Repository" checked={options.generateDataRepository} onChange={v => setOptions({ ...options, generateDataRepository: v })} />
                <Toggle label="Fetch Plans" checked={options.generateFetchPlan} onChange={v => setOptions({ ...options, generateFetchPlan: v })} />
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
                  <span className="font-mono text-[11px]">{file}</span>
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

            {!result ? (
              <button
                onClick={handleGenerate}
                disabled={isGenerating || !entity.className}
                className="px-8 py-3 text-sm rounded-lg bg-jmix-500 text-white font-medium hover:bg-jmix-600 disabled:opacity-50 transition-colors shadow-lg shadow-jmix-500/20"
              >
                {isGenerating ? (
                  <span className="flex items-center gap-2">
                    <span className="animate-spin">⏳</span> Generating...
                  </span>
                ) : (
                  '⚡ Generate Full CRUD Stack'
                )}
              </button>
            ) : (
              <div className="text-left space-y-4">
                {result.errors.length > 0 ? (
                  <div className="p-4 bg-red-900/20 border border-red-800 rounded-lg">
                    <h4 className="text-xs font-semibold text-red-300 mb-2">Errors</h4>
                    {result.errors.map((e, i) => (
                      <p key={i} className="text-xs text-red-400">{e}</p>
                    ))}
                  </div>
                ) : (
                  <div className="p-4 bg-green-900/20 border border-green-800 rounded-lg">
                    <h4 className="text-xs font-semibold text-green-300 mb-2">
                      ✓ Generated {result.files.length} files
                    </h4>
                    {result.files.map((f, i) => (
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
    `entity/${entityName}.java`,
    `view/${entityName}ListView.xml`,
    `view/${entityName}ListView.java`,
    `view/${entityName}DetailView.xml`,
    `view/${entityName}DetailView.java`,
    `menu.xml (entry)`,
    `security/${entityName}Role.java`,
    `messages.properties`,
  ]
  if (options.generateMigration) files.splice(1, 0, `db/changelog/001-${entityName.toLowerCase()}.xml`)
  if (options.generateDataRepository) files.push(`entity/${entityName}Repository.java`)
  if (options.generateFetchPlan) files.push(`entity/${entityName}-fetch-plans.xml`)
  return files
}
