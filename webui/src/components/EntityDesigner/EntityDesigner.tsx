import { useEffect, useMemo, useState } from 'react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  AttributeModel,
  AttributeType,
  TraitType,
  IdType,
  IdGeneration,
  AssociationType,
  CascadeType,
  FetchType,
  EntityModel,
  SchemaEntitySnapshot,
  ValidationType,
  SchemaWorkspaceResponse,
  WorkspaceChangePreviewResponse,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

const ATTRIBUTE_TYPES: AttributeType[] = [
  'string', 'integer', 'long', 'double', 'bigDecimal', 'boolean',
  'date', 'localDate', 'localDateTime', 'localTime', 'offsetDateTime',
  'uuid', 'byteArray', 'enum', 'association', 'composition', 'embedded',
]

const TRAITS: { value: TraitType; label: string }[] = [
  { value: 'standardEntity', label: 'Standard Entity (UUID + Version + Audit)' },
  { value: 'uuid', label: 'UUID' },
  { value: 'softDelete', label: 'Soft Delete' },
  { value: 'hasTenantId', label: 'Multitenancy' },
  { value: 'hasVersion', label: 'Version (Optimistic Lock)' },
  { value: 'auditable', label: 'Auditable (Created + Last Modified)' },
  { value: 'createdBy', label: 'Created By' },
  { value: 'createdDate', label: 'Created Date' },
  { value: 'updatedBy', label: 'Last Modified By' },
  { value: 'updatedDate', label: 'Last Modified Date' },
]

const VALIDATIONS: ValidationType[] = [
  'notNull', 'notEmpty', 'notBlank', 'size', 'min', 'max',
  'decimalMin', 'decimalMax', 'pattern', 'email', 'past', 'future',
  'positive', 'negative', 'digits', 'assertTrue',
]

export default function EntityDesigner() {
  const {
    entity,
    projectConfig,
    setEntity,
    addAttribute,
    updateAttribute,
    removeAttribute,
    resetEntity,
    addToast,
    isGenerating,
    setIsGenerating,
  } = useStore()
  const [selectedAttr, setSelectedAttr] = useState<number | null>(null)
  const [showPreview, setShowPreview] = useState(false)
  const [activePane, setActivePane] = useState<'config' | 'attributes' | 'preview'>('attributes')
  const [schemaWorkspace, setSchemaWorkspace] = useState<SchemaWorkspaceResponse | null>(null)
  const [schemaLoading, setSchemaLoading] = useState(true)
  const [generationPreview, setGenerationPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [existingEntity, setExistingEntity] = useState<SchemaEntitySnapshot | null>(null)

  useEffect(() => {
    let active = true
    bridge.getSchemaWorkspace().then((workspace) => {
      if (!active) return
      setSchemaWorkspace(workspace)
      if (!entity.generationTarget?.moduleId && workspace.modules.length) {
        const defaultModule = workspace.modules.find((module) => module.storeCount > 0) ?? workspace.modules[0]
        const defaultStore = workspace.stores.find(
          (store) => store.moduleId === defaultModule.moduleId && store.name === 'main',
        ) ?? workspace.stores.find((store) => store.moduleId === defaultModule.moduleId)
        setEntity({
          packageName: entity.packageName === 'com.example.app.entity'
            ? suggestedEntityPackage(workspace, defaultModule.moduleId) ?? entity.packageName
            : entity.packageName,
          dataStore: defaultStore?.name ?? 'main',
          generationTarget: {
            moduleId: defaultModule.moduleId,
            storeId: defaultStore?.id,
          },
        })
      }
    }).catch((error) => {
      if (active) addToast(`Cannot inspect project modules: ${error.message}`, 'error')
    }).finally(() => {
      if (active) setSchemaLoading(false)
    })
    return () => {
      active = false
    }
  }, [])

  const selectedModuleId = entity.generationTarget?.moduleId
    ?? schemaWorkspace?.stores.find((store) => store.id === entity.generationTarget?.storeId)?.moduleId
    ?? schemaWorkspace?.modules[0]?.moduleId
    ?? ''
  const moduleStores = useMemo(
    () => schemaWorkspace?.stores.filter((store) => store.moduleId === selectedModuleId) ?? [],
    [schemaWorkspace, selectedModuleId],
  )
  const selectedModule = schemaWorkspace?.modules.find(module => module.moduleId === selectedModuleId)
  const effectiveProjectId = selectedModule?.projectId ?? projectConfig?.projectId
  const selectedStore = schemaWorkspace?.stores.find(
    (store) => store.id === entity.generationTarget?.storeId,
  )
  const existingAttributeNames = useMemo(
    () => new Set(existingEntity?.attributes.map((attribute) => attribute.name) ?? []),
    [existingEntity],
  )

  const handleGenerate = async () => {
    if (!entity.className.trim()) {
      addToast('Entity class name is required', 'error')
      return
    }
    setIsGenerating(true)
    setGenerationPreview(null)
    try {
      const preview = existingEntity
        ? await bridge.previewExistingEntityAttributeAdditions({
            sourceLocator: existingEntity.sourceLocator,
            entity,
          })
        : await bridge.previewEntityGeneration(entity)
      if (preview.accepted && preview.planDigest) {
        setGenerationPreview(preview)
        addToast(`Preview ready: ${preview.files.length} source-safe changes`, 'info')
      } else {
        addToast(`Change rejected: ${preview.issues.map(issue => issue.message).join(', ')}`, 'error')
      }
    } catch (e: any) {
      addToast(`Error: ${e.message}`, 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const handleApplyGeneration = async () => {
    if (!generationPreview?.planDigest) return
    setIsGenerating(true)
    try {
      const result = existingEntity
        ? await bridge.applyExistingEntityAttributeAdditions(
            {
              sourceLocator: existingEntity.sourceLocator,
              entity,
            },
            generationPreview.planDigest,
          )
        : await bridge.applyEntityGeneration(entity, generationPreview.planDigest)
      if (result.success) {
        addToast(
          existingEntity
            ? `Updated "${entity.className}" atomically: ${result.filesChanged.length} files`
            : `Entity "${entity.className}" generated atomically: ${result.filesChanged.length} files`,
          'success',
        )
        setGenerationPreview(null)
        const refreshed = await bridge.getSchemaWorkspace(true)
        setSchemaWorkspace(refreshed)
        if (existingEntity) {
          const updated = refreshed.entities.find(
            (candidate) => candidate.qualifiedName === existingEntity.qualifiedName,
          )
          if (updated) {
            const store = refreshed.stores.find(
              (candidate) => candidate.moduleId === updated.moduleId && candidate.name === updated.storeName,
            )
            setExistingEntity(updated)
            setEntity(existingEntityModel(updated, store?.id))
          }
        }
      } else {
        addToast(`Apply rejected: ${result.issues.map(issue => issue.message).join(', ')}`, 'error')
      }
    } catch (e: any) {
      addToast(`Error: ${e.message}`, 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const toggleTrait = (trait: TraitType) => {
    const traits = entity.traits.includes(trait)
      ? entity.traits.filter(t => t !== trait)
      : [...entity.traits, trait]
    setEntity({ traits })
  }

  const toggleIndexColumn = (index: number, columnName: string) => {
    setEntity({
      indexes: entity.indexes.map((item, itemIndex) => itemIndex === index
        ? {
            ...item,
            columns: item.columns.includes(columnName)
              ? item.columns.filter((column) => column !== columnName)
              : [...item.columns, columnName],
          }
        : item),
    })
  }

  const toggleConstraintColumn = (index: number, columnName: string) => {
    setEntity({
      uniqueConstraints: entity.uniqueConstraints.map((item, itemIndex) => itemIndex === index
        ? {
            ...item,
            columns: item.columns.includes(columnName)
              ? item.columns.filter((column) => column !== columnName)
              : [...item.columns, columnName],
          }
        : item),
    })
  }

  const addIndexDefinition = () => {
    const table = resolvedTableName(entity, effectiveProjectId)
    setEntity({
      indexes: [
        ...entity.indexes,
        {
          name: `IDX_${table}_${entity.indexes.length + 1}`,
          columns: [],
          unique: false,
        },
      ],
    })
  }

  const addUniqueConstraint = () => {
    const table = resolvedTableName(entity, effectiveProjectId)
    setEntity({
      uniqueConstraints: [
        ...entity.uniqueConstraints,
        {
          name: `UQ_${table}_${entity.uniqueConstraints.length + 1}`,
          columns: [],
        },
      ],
    })
  }

  const handleReset = () => {
    setExistingEntity(null)
    setGenerationPreview(null)
    setSelectedAttr(null)
    resetEntity()
  }

  const selectExistingEntity = (artifactId: string) => {
    if (artifactId === 'new') {
      handleReset()
      return
    }
    const snapshot = schemaWorkspace?.entities.find((candidate) => candidate.artifactId === artifactId)
    if (!snapshot) return
    const store = schemaWorkspace?.stores.find(
      (candidate) => candidate.moduleId === snapshot.moduleId && candidate.name === snapshot.storeName,
    )
    setExistingEntity(snapshot)
    setGenerationPreview(null)
    setSelectedAttr(null)
    setEntity(existingEntityModel(snapshot, store?.id))
  }

  return (
    <div className="flex h-full min-w-0 flex-col">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-border bg-surface-light px-3 py-2.5 sm:px-4">
        <h2 className="text-sm font-semibold text-gray-200">Entity Designer</h2>
        <div className="flex flex-wrap justify-end gap-2">
          <button
            onClick={() => {
              setShowPreview((current) => {
                const next = !current
                setActivePane(next ? 'preview' : 'attributes')
                return next
              })
            }}
            className="px-3 py-1.5 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border transition-colors"
          >
            {showPreview ? 'Hide Preview' : 'Preview'}
          </button>
          <button
            onClick={handleReset}
            className="px-3 py-1.5 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border transition-colors"
          >
            {existingEntity ? 'Create New' : 'Reset'}
          </button>
          <button
            onClick={handleGenerate}
            disabled={isGenerating}
            className="px-4 py-1.5 text-xs rounded bg-jmix-500 text-white font-medium hover:bg-jmix-600 disabled:opacity-50 transition-colors"
          >
            {isGenerating ? 'Planning...' : existingEntity ? '⚡ Preview Safe Update' : '⚡ Preview Generation'}
          </button>
        </div>
      </div>

      {generationPreview && (
        <div className="flex flex-wrap items-center gap-3 border-b border-amber-500/30 bg-amber-500/5 px-3 py-2 sm:px-4">
          <div className="min-w-0 flex-1">
            <div className="text-xs font-medium text-amber-100">{generationPreview.label}</div>
            <div className="mt-1 flex max-w-full flex-wrap gap-1.5">
              {generationPreview.files.map((file) => (
                <span
                  key={file.relativePath}
                  className="max-w-full truncate rounded border border-amber-500/20 bg-black/15 px-2 py-1 font-mono text-[9px] text-amber-100/70"
                  title={file.relativePath}
                >
                  {file.mode} · {file.relativePath}
                </span>
              ))}
            </div>
          </div>
          <button
            type="button"
            onClick={() => setGenerationPreview(null)}
            className="rounded border border-surface-border px-3 py-1.5 text-xs text-gray-400 hover:text-gray-200"
          >
            Discard
          </button>
          <button
            type="button"
            onClick={handleApplyGeneration}
            disabled={isGenerating}
            className="rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
          >
            {isGenerating ? 'Applying…' : 'Apply atomic change'}
          </button>
        </div>
      )}

      <ResponsivePaneSwitcher
        value={activePane}
        onChange={(pane) => {
          setActivePane(pane)
          if (pane === 'preview') setShowPreview(true)
        }}
        label="Entity designer panels"
        options={[
          { id: 'config', label: 'Entity setup' },
          { id: 'attributes', label: 'Attributes', badge: entity.attributes.length },
          { id: 'preview', label: 'Code preview' },
        ]}
      />

      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Left: Entity Config */}
        <div className={`${activePane === 'config' ? 'block' : 'hidden'} min-h-0 w-full flex-shrink-0 space-y-4 overflow-y-auto p-4 lg:block lg:w-80 lg:border-r lg:border-surface-border`}>
          <Section title="Project Ownership">
            <Field label="Entity Source">
              <select
                value={existingEntity?.artifactId ?? 'new'}
                disabled={schemaLoading}
                onChange={(event) => selectExistingEntity(event.target.value)}
                className="w-full"
              >
                <option value="new">Create a new entity</option>
                {schemaWorkspace?.entities.length ? (
                  <optgroup label="Edit an existing entity">
                    {schemaWorkspace.entities.map((candidate) => (
                      <option key={candidate.artifactId} value={candidate.artifactId}>
                        {candidate.moduleId} · {candidate.qualifiedName}
                      </option>
                    ))}
                  </optgroup>
                ) : null}
              </select>
            </Field>
            {existingEntity && (
              <div className="rounded border border-jmix-500/30 bg-jmix-500/5 px-2.5 py-2 text-[10px] leading-relaxed text-jmix-100/80">
                Safe round-trip mode preserves manual Java. Existing names, Java types, relationships, and removals are
                protected; nullability, uniqueness, string length, decimal shape, new fields, and rollback-capable
                Liquibase changes are previewed and applied atomically.
              </div>
            )}
            <Field label="Target Module">
              <select
                value={selectedModuleId}
                disabled={Boolean(existingEntity) || schemaLoading || !schemaWorkspace?.modules.length}
                onChange={(event) => {
                  const moduleId = event.target.value
                  const defaultStore = schemaWorkspace?.stores.find(
                    (store) => store.moduleId === moduleId && store.name === 'main',
                  ) ?? schemaWorkspace?.stores.find((store) => store.moduleId === moduleId)
                  setEntity({
                    dataStore: defaultStore?.name ?? 'main',
                    generationTarget: {
                      moduleId,
                      storeId: defaultStore?.id,
                    },
                  })
                }}
                className="w-full"
              >
                {!schemaWorkspace?.modules.length && <option value="">No modules detected</option>}
                {schemaWorkspace?.modules.map((module) => (
                  <option key={module.moduleId} value={module.moduleId}>
                    {module.moduleId} · {module.entityCount} entities
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Data Store">
              <select
                value={entity.generationTarget?.storeId ?? ''}
                disabled={Boolean(existingEntity) || schemaLoading || moduleStores.length === 0}
                onChange={(event) => {
                  const store = moduleStores.find((candidate) => candidate.id === event.target.value)
                  setEntity({
                    dataStore: store?.name ?? 'main',
                    generationTarget: {
                      moduleId: selectedModuleId,
                      storeId: store?.id,
                    },
                  })
                }}
                className="w-full"
              >
                {moduleStores.length === 0 && <option value="">No managed data store</option>}
                {moduleStores.map((store) => (
                  <option key={store.id} value={store.id}>
                    {store.name} · {store.includeMode.replace(/_/g, ' ').toLowerCase()}
                  </option>
                ))}
              </select>
            </Field>
            {selectedStore ? (
              <div className="rounded border border-surface-border bg-surface px-2.5 py-2 text-[10px] leading-relaxed text-gray-500">
                <div className="font-medium text-gray-300">
                  Java → {selectedStore.moduleId}/src/main/java
                </div>
                <div className="mt-1 break-all">
                  Liquibase → {selectedStore.generatedDirectory ?? 'No writable include-chain destination'}
                </div>
                {selectedStore.name !== 'main' && (
                  <div className="mt-1 text-jmix-300">
                    Generates @Store(name = &quot;{selectedStore.name}&quot;)
                  </div>
                )}
              </div>
            ) : (
              <div className="rounded border border-amber-500/30 bg-amber-500/5 px-2.5 py-2 text-[10px] text-amber-200/80">
                This module has no managed Liquibase store. Java generation remains available; enable DDL only after
                configuring a data store.
              </div>
            )}
          </Section>

          <fieldset disabled={Boolean(existingEntity)} className="space-y-4 disabled:opacity-60">
          {/* Basic Info */}
          <Section title="Basic Information">
            <Field label="Class Name">
              <input
                value={entity.className}
                onChange={e => setEntity({ className: e.target.value })}
                placeholder="Customer"
                className="w-full"
              />
            </Field>
            <Field label="Package">
              <input
                value={entity.packageName}
                onChange={e => setEntity({ packageName: e.target.value })}
                className="w-full"
              />
            </Field>
            <Field label="Jmix Entity Name">
              <input
                value={entity.entityName}
                onChange={e => setEntity({ entityName: e.target.value })}
                placeholder={resolvedEntityName(entity, effectiveProjectId)}
                className="w-full"
              />
              {effectiveProjectId && !entity.entityName && (
                <p className="mt-1 text-[9px] leading-relaxed text-gray-600">
                  Uses module project prefix: {resolvedEntityName(entity, effectiveProjectId)}
                </p>
              )}
            </Field>
            <Field label="Table Name">
              <input
                value={entity.tableName}
                onChange={e => setEntity({ tableName: e.target.value })}
                placeholder={resolvedTableName(entity, effectiveProjectId)}
                className="w-full"
              />
            </Field>
            <Field label="Entity Type">
              <select
                value={entity.entityType}
                onChange={e => setEntity({ entityType: e.target.value as any })}
                className="w-full"
              >
                <option value="entity">Entity (JPA)</option>
                <option value="mappedSuperclass">Mapped Superclass</option>
                <option value="embeddable">Embeddable</option>
                <option value="dto">DTO</option>
                <option value="enum">Enumeration</option>
              </select>
            </Field>
            <Field label="Instance Name Pattern">
              <input
                value={entity.instanceNamePattern || ''}
                onChange={e => setEntity({ instanceNamePattern: e.target.value || undefined })}
                placeholder="name"
                className="w-full"
              />
            </Field>
            <Field label="Comment">
              <input
                value={entity.comment || ''}
                onChange={e => setEntity({ comment: e.target.value || undefined })}
                className="w-full"
              />
            </Field>
          </Section>

          {/* ID Configuration */}
          <Section title="Identifier">
            <Field label="ID Type">
              <select
                value={entity.id.type}
                onChange={e => setEntity({ id: { ...entity.id, type: e.target.value as IdType } })}
                className="w-full"
              >
                <option value="uuid">UUID</option>
                <option value="long">Long</option>
                <option value="integer">Integer</option>
                <option value="string">String</option>
                <option value="embedded">Embedded (Composite)</option>
              </select>
            </Field>
            <Field label="Generation Strategy">
              <select
                value={entity.id.generation}
                onChange={e => setEntity({ id: { ...entity.id, generation: e.target.value as IdGeneration } })}
                className="w-full"
              >
                <option value="jmixGenerated">Jmix Generated</option>
                <option value="identity">Identity Column</option>
                <option value="sequence">Sequence</option>
                <option value="assigned">Assigned by User</option>
              </select>
            </Field>
            <Field label="Column Name">
              <input
                value={entity.id.columnName}
                onChange={e => setEntity({ id: { ...entity.id, columnName: e.target.value } })}
                className="w-full"
              />
            </Field>
            {entity.id.type === 'string' && (
              <Field label="ID Length">
                <input
                  type="number"
                  value={entity.id.length || ''}
                  onChange={e => setEntity({ id: { ...entity.id, length: e.target.value ? parseInt(e.target.value) : undefined } })}
                  className="w-full"
                />
              </Field>
            )}
            {entity.id.generation === 'sequence' && (
              <Field label="Sequence Name">
                <input
                  value={entity.id.sequenceName || ''}
                  onChange={e => setEntity({ id: { ...entity.id, sequenceName: e.target.value || undefined } })}
                  className="w-full"
                />
              </Field>
            )}
          </Section>

          {/* Traits */}
          <Section title="Traits & Interfaces">
            <div className="space-y-1.5">
              {TRAITS.map(t => (
                <label key={t.value} className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={entity.traits.includes(t.value)}
                    onChange={() => toggleTrait(t.value)}
                    className="rounded border-surface-border"
                  />
                  {t.label}
                </label>
              ))}
            </div>
          </Section>

          {/* Inheritance */}
          <Section title="Inheritance">
            <Field label="Extends Class">
              <input
                value={entity.extendsClass || ''}
                onChange={e => setEntity({ extendsClass: e.target.value || undefined })}
                placeholder="com.example.BaseEntity"
                className="w-full"
              />
            </Field>
            {entity.extendsClass && (
              <Field label="Strategy">
                <select
                  value={entity.inheritance?.strategy || 'singleTable'}
                  onChange={e => setEntity({
                    inheritance: {
                      strategy: e.target.value as any,
                      discriminatorType: entity.inheritance?.discriminatorType || 'STRING',
                      discriminatorColumn: entity.inheritance?.discriminatorColumn,
                      discriminatorValue: entity.inheritance?.discriminatorValue,
                    }
                  })}
                  className="w-full"
                >
                  <option value="singleTable">Single Table</option>
                  <option value="joined">Joined</option>
                  <option value="tablePerClass">Table Per Class</option>
                </select>
              </Field>
            )}
          </Section>

          {/* Options */}
          <Section title="Options">
            <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.databaseView}
                onChange={e => setEntity({ databaseView: e.target.checked })}
                className="rounded border-surface-border"
              />
              Maps an existing database view
            </label>
            <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.ddlGeneration.enabled}
                disabled={entity.databaseView}
                onChange={e => setEntity({
                  ddlGeneration: {
                    ...entity.ddlGeneration,
                    enabled: e.target.checked,
                  },
                })}
                className="rounded border-surface-border"
              />
              DDL Generation
            </label>
            {entity.ddlGeneration.enabled && !entity.databaseView && (
              <>
                <Field label="DDL Safety Mode">
                  <select
                    value={entity.ddlGeneration.mode}
                    onChange={e => setEntity({
                      ddlGeneration: {
                        ...entity.ddlGeneration,
                        mode: e.target.value as EntityModel['ddlGeneration']['mode'],
                      },
                    })}
                    className="w-full"
                  >
                    <option value="createAndDrop">Create and update</option>
                    <option value="createOnly">Create only — never generate drops</option>
                    <option value="disabled">Disabled</option>
                  </select>
                </Field>
                <Field label="Protected Unmapped Columns">
                  <input
                    value={entity.ddlGeneration.unmappedColumns.join(', ')}
                    onChange={e => setEntity({
                      ddlGeneration: {
                        ...entity.ddlGeneration,
                        unmappedColumns: e.target.value.split(',').map(value => value.trim()).filter(Boolean),
                      },
                    })}
                    placeholder="LEGACY_CODE, EXTERNAL_TOTAL"
                    className="w-full"
                  />
                </Field>
                <Field label="Protected Constraints / Indexes">
                  <input
                    value={entity.ddlGeneration.unmappedConstraints.join(', ')}
                    onChange={e => setEntity({
                      ddlGeneration: {
                        ...entity.ddlGeneration,
                        unmappedConstraints: e.target.value.split(',').map(value => value.trim()).filter(Boolean),
                      },
                    })}
                    placeholder="IDX_LEGACY_CODE"
                    className="w-full"
                  />
                </Field>
              </>
            )}
            <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer mt-1.5">
              <input
                type="checkbox"
                checked={entity.dataRepository?.enabled || false}
                onChange={e => setEntity({ dataRepository: { enabled: e.target.checked } })}
                className="rounded border-surface-border"
              />
              Generate Data Repository
            </label>
          </Section>
          </fieldset>
        </div>

        {/* Center: Attributes Table */}
        <div className={`${activePane === 'attributes' ? 'block' : 'hidden'} min-h-0 min-w-0 flex-1 overflow-y-auto p-3 sm:p-4 lg:block`}>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider">Attributes</h3>
            <button
              onClick={addAttribute}
              className="px-3 py-1 text-xs rounded bg-jmix-500/20 text-jmix-400 hover:bg-jmix-500/30 transition-colors"
            >
              + Add Attribute
            </button>
          </div>

          {entity.attributes.length === 0 ? (
            <div className="text-center py-12 text-gray-600 text-xs">
              No attributes yet. Click "+ Add Attribute" to start.
            </div>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-surface-border">
              <table className="w-full min-w-[40rem] text-xs">
                <thead>
                  <tr className="bg-surface-light text-gray-400 text-left">
                    <th className="px-3 py-2 font-medium">Name</th>
                    <th className="px-3 py-2 font-medium">Type</th>
                    <th className="px-3 py-2 font-medium">Mandatory</th>
                    <th className="px-3 py-2 font-medium">Unique</th>
                    <th className="px-3 py-2 font-medium">Length</th>
                    <th className="px-3 py-2 font-medium">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {entity.attributes.map((attr, i) => {
                    const locked = existingAttributeNames.has(attr.name)
                    const sourceAttribute = existingEntity?.attributes.find(
                      (candidate) => candidate.name === attr.name,
                    )
                    const mappingLocked = Boolean(
                      sourceAttribute && (sourceAttribute.association || !sourceAttribute.persistent),
                    )
                    return (
                      <tr
                        key={`${attr.name}-${i}`}
                        onClick={() => setSelectedAttr(selectedAttr === i ? null : i)}
                        className={`border-t border-surface-border cursor-pointer transition-colors ${
                          selectedAttr === i ? 'bg-jmix-500/10' : 'hover:bg-surface-lighter'
                        } ${locked ? 'text-gray-500' : ''}`}
                      >
                        <td className="px-3 py-2">
                          <input
                            value={attr.name}
                            disabled={locked}
                            onChange={e => updateAttribute(i, { name: e.target.value })}
                            onClick={e => e.stopPropagation()}
                            className="w-28 bg-transparent border-none p-0 text-gray-200 disabled:text-gray-500"
                          />
                        </td>
                        <td className="px-3 py-2">
                          <select
                            value={attr.type}
                            disabled={locked}
                            onChange={e => {
                              const type = e.target.value as AttributeType
                              updateAttribute(i, {
                                type,
                                ...(type === 'association' || type === 'composition'
                                  ? { association: attr.association ?? defaultAssociation(type) }
                                  : {}),
                              })
                            }}
                            onClick={e => e.stopPropagation()}
                            className="bg-surface-lighter text-gray-300 text-xs disabled:text-gray-500"
                          >
                            {ATTRIBUTE_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                          </select>
                        </td>
                        <td className="px-3 py-2 text-center">
                          <input
                            type="checkbox"
                            checked={attr.mandatory}
                            disabled={mappingLocked}
                            onChange={e => updateAttribute(i, { mandatory: e.target.checked })}
                            onClick={e => e.stopPropagation()}
                          />
                        </td>
                        <td className="px-3 py-2 text-center">
                          <input
                            type="checkbox"
                            checked={attr.unique}
                            disabled={mappingLocked}
                            onChange={e => updateAttribute(i, { unique: e.target.checked })}
                            onClick={e => e.stopPropagation()}
                          />
                        </td>
                        <td className="px-3 py-2">
                          {attr.type === 'string' && (
                            <input
                              type="number"
                              value={attr.length || ''}
                              disabled={mappingLocked}
                              onChange={e => updateAttribute(i, { length: e.target.value ? parseInt(e.target.value) : undefined })}
                              onClick={e => e.stopPropagation()}
                              className="w-16 bg-transparent border-none p-0 text-gray-300 disabled:text-gray-500"
                              placeholder="255"
                            />
                          )}
                        </td>
                        <td className="px-3 py-2">
                          {locked ? (
                            <span
                              title={mappingLocked
                                ? 'Relationship or transient source mapping is protected'
                                : 'Name, Java type, and removal are protected; mapping metadata remains editable'}
                              className="text-[10px] text-gray-600"
                            >
                              {mappingLocked ? 'Protected' : 'Mapping only'}
                            </span>
                          ) : (
                            <button
                              onClick={e => { e.stopPropagation(); removeAttribute(i); setSelectedAttr(null) }}
                              className="text-red-400 hover:text-red-300 text-xs"
                            >
                              ✕
                            </button>
                          )}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}

          {/* Selected Attribute Detail */}
          {selectedAttr !== null && entity.attributes[selectedAttr] && (
            existingAttributeNames.has(entity.attributes[selectedAttr].name) ? (
              (() => {
                const selected = entity.attributes[selectedAttr]
                const source = existingEntity?.attributes.find((candidate) => candidate.name === selected.name)
                const mappingLocked = Boolean(source && (source.association || !source.persistent))
                return (
                  <div className="mt-4 rounded-lg border border-surface-border bg-surface-light p-4">
                    <div className="text-xs text-gray-400">
                      <span className="font-mono text-gray-200">{selected.name}</span>
                      {' · '}
                      {mappingLocked
                        ? 'relationship/transient mapping is source-protected'
                        : 'safe persistence metadata editor'}
                    </div>
                    {!mappingLocked && (
                      <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                        <label className="text-[10px] uppercase tracking-wider text-gray-500">
                          Mandatory
                          <input
                            type="checkbox"
                            checked={selected.mandatory}
                            onChange={(event) => updateAttribute(selectedAttr, { mandatory: event.target.checked })}
                            className="ml-2"
                          />
                        </label>
                        <label className="text-[10px] uppercase tracking-wider text-gray-500">
                          Unique
                          <input
                            type="checkbox"
                            checked={selected.unique}
                            onChange={(event) => updateAttribute(selectedAttr, { unique: event.target.checked })}
                            className="ml-2"
                          />
                        </label>
                        {(selected.type === 'string' || selected.type === 'enum') && (
                          <Field label="Length">
                            <input
                              type="number"
                              min={1}
                              value={selected.length ?? ''}
                              onChange={(event) => updateAttribute(selectedAttr, {
                                length: event.target.value ? Number(event.target.value) : undefined,
                              })}
                              className="w-full"
                            />
                          </Field>
                        )}
                        {selected.type === 'bigDecimal' && (
                          <>
                            <Field label="Precision">
                              <input
                                type="number"
                                min={1}
                                value={selected.precision ?? ''}
                                onChange={(event) => updateAttribute(selectedAttr, {
                                  precision: event.target.value ? Number(event.target.value) : undefined,
                                })}
                                className="w-full"
                              />
                            </Field>
                            <Field label="Scale">
                              <input
                                type="number"
                                min={0}
                                value={selected.scale ?? ''}
                                onChange={(event) => updateAttribute(selectedAttr, {
                                  scale: event.target.value ? Number(event.target.value) : undefined,
                                })}
                                className="w-full"
                              />
                            </Field>
                          </>
                        )}
                      </div>
                    )}
                    <p className="mt-3 text-[10px] leading-relaxed text-gray-600">
                      Field identity, Java type, manual annotations, accessors, and call sites remain untouched.
                    </p>
                  </div>
                )
              })()
            ) : (
              <AttributeDetail
                attr={entity.attributes[selectedAttr]}
                entity={entity}
                projectId={effectiveProjectId}
                schemaWorkspace={schemaWorkspace}
                onChange={(partial) => updateAttribute(selectedAttr, partial)}
              />
            )
          )}

          {!existingEntity && entity.entityType === 'entity' && (
            <div className="mt-5 grid grid-cols-1 gap-4 xl:grid-cols-2">
              <SchemaDefinitionPanel
                title="Indexes"
                description="Define database-backed search and uniqueness indexes. Select real column names; generation updates both @Table and Liquibase."
                items={entity.indexes}
                attributes={entity.attributes}
                onAdd={addIndexDefinition}
                onNameChange={(index, name) => setEntity({
                  indexes: entity.indexes.map((item, itemIndex) => itemIndex === index ? { ...item, name } : item),
                })}
                onToggleColumn={toggleIndexColumn}
                onToggleUnique={(index) => setEntity({
                  indexes: entity.indexes.map((item, itemIndex) => itemIndex === index
                    ? { ...item, unique: !item.unique }
                    : item),
                })}
                onRemove={(index) => setEntity({
                  indexes: entity.indexes.filter((_, itemIndex) => itemIndex !== index),
                })}
              />
              <SchemaDefinitionPanel
                title="Unique Constraints"
                description="Enforce multi-column business identifiers such as company + employee number at the database boundary."
                items={entity.uniqueConstraints.map((constraint) => ({ ...constraint, unique: true }))}
                attributes={entity.attributes}
                onAdd={addUniqueConstraint}
                onNameChange={(index, name) => setEntity({
                  uniqueConstraints: entity.uniqueConstraints.map((item, itemIndex) =>
                    itemIndex === index ? { ...item, name } : item),
                })}
                onToggleColumn={toggleConstraintColumn}
                onRemove={(index) => setEntity({
                  uniqueConstraints: entity.uniqueConstraints.filter((_, itemIndex) => itemIndex !== index),
                })}
              />
            </div>
          )}
        </div>

        {/* Right: Preview */}
        {showPreview && (
          <div className={`${activePane === 'preview' ? 'block' : 'hidden'} min-h-0 w-full flex-shrink-0 overflow-y-auto p-4 lg:block lg:w-96 lg:border-l lg:border-surface-border`}>
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider mb-3">
              {existingEntity ? 'Safe Round-trip Preview' : 'Generated Code Preview'}
            </h3>
            <pre className="text-[10px] text-gray-400 bg-surface-lighter rounded-lg p-3 overflow-x-auto whitespace-pre-wrap font-mono leading-relaxed">
              {existingEntity
                ? generateExistingUpdatePreview(entity, existingEntity)
                : generatePreview(entity, effectiveProjectId)}
            </pre>
          </div>
        )}
      </div>
    </div>
  )
}

function SchemaDefinitionPanel({
  title,
  description,
  items,
  attributes,
  onAdd,
  onNameChange,
  onToggleColumn,
  onToggleUnique,
  onRemove,
}: {
  title: string
  description: string
  items: { name: string; columns: string[]; unique: boolean }[]
  attributes: AttributeModel[]
  onAdd: () => void
  onNameChange: (index: number, name: string) => void
  onToggleColumn: (index: number, columnName: string) => void
  onToggleUnique?: (index: number) => void
  onRemove: (index: number) => void
}) {
  const columns = attributes
    .filter((attribute) => !attribute.transientFlag)
    .map((attribute) => attribute.columnName ||
      attribute.name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase())
  return (
    <section className="rounded-lg border border-surface-border bg-surface-light/50 p-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-300">{title}</h3>
          <p className="mt-1 text-[10px] leading-relaxed text-gray-500">{description}</p>
        </div>
        <button
          type="button"
          onClick={onAdd}
          className="shrink-0 rounded bg-jmix-500/20 px-2.5 py-1 text-[10px] text-jmix-300 hover:bg-jmix-500/30"
        >
          + Add
        </button>
      </div>
      <div className="mt-3 space-y-2">
        {items.length === 0 && (
          <div className="rounded border border-dashed border-surface-border p-3 text-center text-[10px] text-gray-600">
            No definitions yet.
          </div>
        )}
        {items.map((item, index) => (
          <div key={`${title}-${index}`} className="rounded border border-surface-border bg-surface p-2.5">
            <div className="flex items-center gap-2">
              <input
                value={item.name}
                onChange={(event) => onNameChange(index, event.target.value)}
                aria-label={`${title} name`}
                className="min-w-0 flex-1 font-mono text-[10px]"
              />
              {onToggleUnique && (
                <label className="flex shrink-0 items-center gap-1 text-[10px] text-gray-400">
                  <input
                    type="checkbox"
                    checked={item.unique}
                    onChange={() => onToggleUnique(index)}
                  />
                  Unique
                </label>
              )}
              <button
                type="button"
                onClick={() => onRemove(index)}
                aria-label={`Remove ${title.toLowerCase()} definition`}
                className="text-xs text-red-400 hover:text-red-300"
              >
                ✕
              </button>
            </div>
            <div className="mt-2 flex flex-wrap gap-1">
              {columns.map((column) => {
                const selected = item.columns.includes(column)
                return (
                  <button
                    key={column}
                    type="button"
                    aria-pressed={selected}
                    onClick={() => onToggleColumn(index, column)}
                    className={`rounded border px-2 py-1 font-mono text-[9px] transition-colors ${
                      selected
                        ? 'border-jmix-500/50 bg-jmix-500/15 text-jmix-200'
                        : 'border-surface-border text-gray-500 hover:text-gray-300'
                    }`}
                  >
                    {column}
                  </button>
                )
              })}
              {columns.length === 0 && (
                <span className="text-[10px] text-amber-300/70">Add persisted attributes before selecting columns.</span>
              )}
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

function AttributeDetail({
  attr,
  entity,
  projectId,
  schemaWorkspace,
  onChange,
}: {
  attr: AttributeModel
  entity: EntityModel
  projectId?: string
  schemaWorkspace: SchemaWorkspaceResponse | null
  onChange: (patch: Partial<AttributeModel>) => void
}) {
  const association = attr.association ?? defaultAssociation(attr.type)
  const toMany = association.associationType === 'oneToMany' ||
    association.associationType === 'manyToMany'
  const inverse = !association.crossDataStore &&
    (
      association.associationType === 'oneToMany' ||
      ((association.associationType === 'oneToOne' || association.associationType === 'manyToMany') &&
        Boolean(association.mappedBy))
    )
  const updateAssociation = (patch: Partial<NonNullable<AttributeModel['association']>>) => {
    onChange({ association: { ...association, ...patch } })
  }

  return (
    <div className="mt-4 border border-surface-border rounded-lg p-4 bg-surface-light">
      <h4 className="text-xs font-semibold text-jmix-400 mb-3">Attribute: {attr.name || '(unnamed)'}</h4>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {(attr.type === 'association' || attr.type === 'composition') && (
          <>
            <Field label="Association Type">
              <select
                value={association.associationType}
                onChange={e => {
                  const associationType = e.target.value as AssociationType
                  const next = {
                    associationType,
                    mappedBy: associationType === 'oneToMany' ? association.mappedBy : undefined,
                    joinTable: associationType === 'manyToMany' && !association.mappedBy
                      ? association.joinTable ?? suggestedJoinTable(entity, attr, association, projectId)
                      : association.joinTable,
                    collectionType: association.collectionType ?? 'list',
                  }
                  updateAssociation(next)
                }}
                className="w-full"
              >
                <option value="manyToOne" disabled={attr.type === 'composition'}>Many to One</option>
                <option value="oneToMany" disabled={association.crossDataStore}>One to Many</option>
                <option value="manyToMany" disabled={association.crossDataStore || attr.type === 'composition'}>Many to Many</option>
                <option value="oneToOne">One to One</option>
              </select>
            </Field>
            <Field label="Related Entity">
              <input
                list={`entity-targets-${attr.name}`}
                value={association.relatedEntity}
                onChange={e => {
                  const relatedEntity = e.target.value
                  const target = schemaWorkspace?.entities.find(
                    candidate => candidate.qualifiedName === relatedEntity,
                  )
                  const crossDataStore = Boolean(target && target.storeName !== entity.dataStore)
                  updateAssociation({
                    relatedEntity,
                    relatedTableName: target?.tableName,
                    relatedIdColumnName: target?.idColumnName ?? association.relatedIdColumnName ?? 'ID',
                    relatedIdType: target?.idType ?? association.relatedIdType ?? 'uuid',
                    crossDataStore,
                    associationType: crossDataStore && toMany ? 'manyToOne' : association.associationType,
                    mappedBy: crossDataStore ? undefined : association.mappedBy,
                    joinTable: crossDataStore ? undefined : association.joinTable,
                  })
                }}
                placeholder="com.example.entity.Order"
                className="w-full"
              />
              <datalist id={`entity-targets-${attr.name}`}>
                {schemaWorkspace?.entities
                  .filter(candidate => candidate.qualifiedName !== entity.packageName + '.' + entity.className)
                  .map(candidate => (
                    <option key={candidate.artifactId} value={candidate.qualifiedName}>
                      {candidate.moduleId} · {candidate.storeName} · {candidate.tableName}
                    </option>
                  ))}
              </datalist>
            </Field>
            <Field label="Fetch Type">
              <select
                value={association.fetch}
                onChange={e => updateAssociation({ fetch: e.target.value as FetchType })}
                className="w-full"
              >
                <option value="lazy">Lazy</option>
                <option value="eager">Eager</option>
              </select>
            </Field>
            {toMany && (
              <Field label="Collection Type">
                <select
                  value={association.collectionType}
                  onChange={e => updateAssociation({
                    collectionType: e.target.value as NonNullable<AttributeModel['association']>['collectionType'],
                  })}
                  className="w-full"
                >
                  <option value="list">List</option>
                  <option value="set">Set</option>
                </select>
              </Field>
            )}
            {!association.crossDataStore &&
              ['oneToMany', 'manyToMany', 'oneToOne'].includes(association.associationType) && (
                <Field label={association.associationType === 'oneToMany' ? 'Mapped By (required)' : 'Mapped By (blank = owning side)'}>
                  <input
                    value={association.mappedBy || ''}
                    onChange={e => updateAssociation({
                      mappedBy: e.target.value || undefined,
                      joinTable: association.associationType === 'manyToMany' && !e.target.value
                        ? association.joinTable ?? suggestedJoinTable(entity, attr, association, projectId)
                        : association.joinTable,
                    })}
                    placeholder={association.associationType === 'oneToMany' ? 'ownerAttribute' : 'inverse attribute'}
                    className="w-full"
                  />
                </Field>
              )}
            {(association.crossDataStore ||
              (['manyToOne', 'oneToOne'].includes(association.associationType) && !inverse)) && (
              <Field label={association.crossDataStore ? 'Local ID Column' : 'Join Column'}>
                <input
                  value={association.joinColumnName || ''}
                  onChange={e => updateAssociation({ joinColumnName: e.target.value || undefined })}
                  placeholder={`${toDatabaseName(attr.name)}_ID`}
                  className="w-full"
                />
              </Field>
            )}
            {association.crossDataStore && (
              <>
                <div className="sm:col-span-2 rounded border border-sky-500/30 bg-sky-500/5 p-2 text-[10px] leading-relaxed text-sky-200/80">
                  Cross-data-store mode generates a system-level ID column plus a transient Jmix property. No invalid
                  database foreign key is created; DataManager resolves the to-one reference.
                </div>
                <Field label="Local ID Attribute">
                  <input
                    value={association.localIdAttributeName || `${attr.name}Id`}
                    onChange={e => updateAssociation({ localIdAttributeName: e.target.value || undefined })}
                    className="w-full"
                  />
                </Field>
                <Field label="Target ID Type">
                  <select
                    value={association.relatedIdType}
                    onChange={e => updateAssociation({ relatedIdType: e.target.value as IdType })}
                    className="w-full"
                  >
                    <option value="uuid">UUID</option>
                    <option value="long">Long</option>
                    <option value="integer">Integer</option>
                    <option value="string">String</option>
                  </select>
                </Field>
              </>
            )}
            {association.associationType === 'manyToMany' && !association.mappedBy && !association.crossDataStore && (
              <>
                <Field label="Join Table">
                  <input
                    value={association.joinTable?.name || ''}
                    onChange={e => updateAssociation({
                      joinTable: {
                        ...(association.joinTable ?? suggestedJoinTable(entity, attr, association, projectId)),
                        name: e.target.value,
                      },
                    })}
                    className="w-full"
                  />
                </Field>
                <Field label="Owner / Target Columns">
                  <div className="grid grid-cols-2 gap-1.5">
                    <input
                      value={association.joinTable?.joinColumnName || ''}
                      onChange={e => updateAssociation({
                        joinTable: {
                          ...(association.joinTable ?? suggestedJoinTable(entity, attr, association, projectId)),
                          joinColumnName: e.target.value,
                        },
                      })}
                      aria-label="Join table owner column"
                    />
                    <input
                      value={association.joinTable?.inverseJoinColumnName || ''}
                      onChange={e => updateAssociation({
                        joinTable: {
                          ...(association.joinTable ?? suggestedJoinTable(entity, attr, association, projectId)),
                          inverseJoinColumnName: e.target.value,
                        },
                      })}
                      aria-label="Join table target column"
                    />
                  </div>
                </Field>
              </>
            )}
            <Field label="Database Delete Policy">
              <select
                value={association.onDelete || ''}
                onChange={e => updateAssociation({ onDelete: e.target.value || undefined })}
                disabled={association.crossDataStore || inverse}
                className="w-full"
              >
                <option value="">Database default</option>
                <option value="CASCADE">Cascade</option>
                <option value="SET NULL">Set null</option>
                <option value="RESTRICT">Restrict</option>
              </select>
            </Field>
            <div className="sm:col-span-2">
              <div className="text-[10px] text-gray-500">Cascade Operations</div>
              <div className="mt-1 flex flex-wrap gap-2">
                {(['all', 'persist', 'merge', 'remove', 'refresh', 'detach'] as CascadeType[]).map(cascade => (
                  <label key={cascade} className="flex items-center gap-1 text-[10px] text-gray-400">
                    <input
                      type="checkbox"
                      checked={association.cascade.includes(cascade)}
                      onChange={() => updateAssociation({
                        cascade: association.cascade.includes(cascade)
                          ? association.cascade.filter(candidate => candidate !== cascade)
                          : [...association.cascade, cascade],
                      })}
                    />
                    {cascade}
                  </label>
                ))}
                {['oneToMany', 'oneToOne'].includes(association.associationType) && (
                  <label className="flex items-center gap-1 text-[10px] text-gray-400">
                    <input
                      type="checkbox"
                      checked={association.orphanRemoval}
                      onChange={e => updateAssociation({ orphanRemoval: e.target.checked })}
                    />
                    orphan removal
                  </label>
                )}
              </div>
            </div>
          </>
        )}

        {attr.type === 'enum' && (
          <Field label="Enum Class">
            <input
              value={attr.enumClass || ''}
              onChange={e => onChange({ enumClass: e.target.value || undefined })}
              placeholder="com.example.entity.Status"
              className="w-full"
            />
          </Field>
        )}

        {attr.type === 'embedded' && (
          <Field label="Embedded Class">
            <input
              value={attr.embeddedClass || ''}
              onChange={e => onChange({ embeddedClass: e.target.value || undefined })}
              className="w-full"
            />
          </Field>
        )}

        {(attr.type === 'bigDecimal') && (
          <>
            <Field label="Precision">
              <input
                type="number"
                value={attr.precision || ''}
                onChange={e => onChange({ precision: e.target.value ? parseInt(e.target.value) : undefined })}
                className="w-full"
              />
            </Field>
            <Field label="Scale">
              <input
                type="number"
                value={attr.scale || ''}
                onChange={e => onChange({ scale: e.target.value ? parseInt(e.target.value) : undefined })}
                className="w-full"
              />
            </Field>
          </>
        )}

        <Field label="Column Name">
          <input
            value={attr.columnName || ''}
            onChange={e => onChange({ columnName: e.target.value || undefined })}
            placeholder="AUTO"
            className="w-full"
          />
        </Field>
        <Field label="Localized Caption">
          <input
            value={attr.localizedCaption || ''}
            onChange={e => onChange({ localizedCaption: e.target.value || undefined })}
            className="w-full"
          />
        </Field>
        <Field label="Default Value">
          <input
            value={attr.defaultValue || ''}
            onChange={e => onChange({ defaultValue: e.target.value || undefined })}
            className="w-full"
          />
        </Field>

        <div className="col-span-2 flex gap-4 mt-1">
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.transientFlag} onChange={e => onChange({ transientFlag: e.target.checked })} />
            Transient
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.inBaseFetchPlan} onChange={e => onChange({ inBaseFetchPlan: e.target.checked })} />
            In Base Fetch Plan
          </label>
        </div>
      </div>

      {/* Validations */}
      <div className="mt-3 pt-3 border-t border-surface-border">
        <h5 className="text-[10px] font-semibold text-gray-400 uppercase mb-2">Validations</h5>
        <div className="flex flex-wrap gap-1.5">
          {VALIDATIONS.map(v => {
            const active = attr.validations.some((val: any) => val.type === v)
            return (
              <button
                key={v}
                onClick={() => {
                  if (active) {
                    onChange({ validations: attr.validations.filter((val: any) => val.type !== v) })
                  } else {
                    onChange({ validations: [...attr.validations, { type: v }] })
                  }
                }}
                className={`px-2 py-0.5 text-[10px] rounded transition-colors ${
                  active
                    ? 'bg-jmix-500/30 text-jmix-300 border border-jmix-500/50'
                    : 'bg-surface-lighter text-gray-500 border border-surface-border hover:text-gray-300'
                }`}
              >
                {v}
              </button>
            )
          })}
        </div>
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h3 className="text-[10px] font-semibold text-gray-400 uppercase tracking-wider mb-2">{title}</h3>
      <div className="space-y-2">{children}</div>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-[10px] text-gray-500 mb-1">{label}</label>
      {children}
    </div>
  )
}

function suggestedEntityPackage(
  workspace: SchemaWorkspaceResponse,
  moduleId: string,
): string | undefined {
  return workspace.entities
    .find((candidate) => candidate.moduleId === moduleId)
    ?.qualifiedName
    .split('.')
    .slice(0, -1)
    .join('.')
}

function existingEntityModel(
  snapshot: SchemaEntitySnapshot,
  storeId?: string,
): Partial<EntityModel> {
  const packageName = snapshot.qualifiedName.split('.').slice(0, -1).join('.')
  return {
    className: snapshot.className,
    packageName,
    dataStore: snapshot.storeName,
    generationTarget: {
      moduleId: snapshot.moduleId,
      storeId,
    },
    entityName: snapshot.entityName,
    tableName: snapshot.tableName,
    entityType: 'entity',
    id: {
      type: snapshot.idType,
      generation: 'jmixGenerated',
      columnName: snapshot.idColumnName,
    },
    traits: [],
    attributes: snapshot.attributes.map((attribute) => {
      const discovered = attribute.associationDetails
      return {
        name: attribute.name,
        type: discovered?.composition
          ? 'composition' as const
          : schemaAttributeType(attribute.javaType, attribute.association),
        columnName: attribute.columnName,
        mandatory: !attribute.nullable,
        unique: attribute.unique,
        length: attribute.length,
        precision: attribute.precision,
        scale: attribute.scale,
        transientFlag: !attribute.persistent,
        systemLevel: false,
        validations: [],
        annotations: [],
        inBaseFetchPlan: true,
        ...(attribute.association ? {
          association: {
            associationType: discovered?.associationType ?? 'manyToOne' as const,
            relatedEntity: discovered?.relatedEntity ?? attribute.javaType,
            relatedTableName: discovered?.relatedTableName,
            relatedIdColumnName: discovered?.relatedIdColumnName ?? 'ID',
            relatedIdType: discovered?.relatedIdType ?? 'uuid' as const,
            localIdAttributeName: discovered?.localIdAttributeName,
            mappedBy: discovered?.mappedBy,
            joinColumnName: discovered?.joinColumnName,
            joinTable: discovered?.joinTable,
            cascade: discovered?.cascade ?? [],
            fetch: discovered?.fetch ?? 'lazy' as const,
            collectionType: discovered?.collectionType ?? 'list' as const,
            crossDataStore: discovered?.crossDataStore ?? false,
            orphanRemoval: discovered?.orphanRemoval ?? false,
            onDelete: discovered?.onDelete,
          },
        } : {}),
      }
    }),
    indexes: [],
    uniqueConstraints: [],
    databaseView: snapshot.databaseView,
    ddlGeneration: {
      enabled: snapshot.ddlMode !== 'DISABLED',
      mode: snapshot.ddlMode === 'CREATE_ONLY'
        ? 'createOnly'
        : snapshot.ddlMode === 'DISABLED'
          ? 'disabled'
          : 'createAndDrop',
      unmappedColumns: [],
      unmappedConstraints: [],
    },
    lifecycleCallbacks: [],
    entityListeners: [],
    implementsInterfaces: [],
    annotations: [],
  }
}

function schemaAttributeType(javaType: string, association: boolean): AttributeType {
  if (association) return 'association'
  const simple = javaType.replace(/\??$/, '').split('.').pop()?.replace(/<.*>/, '') ?? javaType
  const mapping: Record<string, AttributeType> = {
    String: 'string',
    Integer: 'integer',
    int: 'integer',
    Long: 'long',
    long: 'long',
    Double: 'double',
    double: 'double',
    BigDecimal: 'bigDecimal',
    Boolean: 'boolean',
    boolean: 'boolean',
    Date: 'date',
    LocalDate: 'localDate',
    LocalDateTime: 'localDateTime',
    LocalTime: 'localTime',
    OffsetDateTime: 'offsetDateTime',
    UUID: 'uuid',
    'byte[]': 'byteArray',
  }
  return mapping[simple] ?? 'enum'
}

function generateExistingUpdatePreview(entity: EntityModel, snapshot: SchemaEntitySnapshot): string {
  const currentByName = new Map(snapshot.attributes.map((attribute) => [attribute.name, attribute]))
  const additions = entity.attributes.filter((attribute) => !currentByName.has(attribute.name))
  const mappingChanges = entity.attributes.filter((attribute) => {
    const current = currentByName.get(attribute.name)
    if (!current || current.association || !current.persistent) return false
    return attribute.mandatory !== !current.nullable ||
      attribute.unique !== current.unique ||
      (attribute.length ?? 255) !== (current.length ?? 255) ||
      attribute.precision !== current.precision ||
      attribute.scale !== current.scale
  })
  if (!additions.length && !mappingChanges.length) {
    return [
      '// Existing Java source is preserved.',
      '// Edit safe mapping metadata or add an attribute to preview exact source and Liquibase changes.',
    ].join('\n')
  }
  const previews = additions.map((attribute) => {
    const columnName = attribute.columnName ||
      attribute.name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()
    const type = previewJavaType(attribute.type)
    const suffix = attribute.name.charAt(0).toUpperCase() + attribute.name.slice(1)
    return [
      `@Column(name = "${columnName}"${attribute.mandatory ? ', nullable = false' : ''})`,
      `protected ${type} ${attribute.name};`,
      '',
      `public ${type} get${suffix}() {`,
      `    return ${attribute.name};`,
      '}',
      '',
      `public void set${suffix}(${type} ${attribute.name}) {`,
      `    this.${attribute.name} = ${attribute.name};`,
      '}',
    ].join('\n')
  })
  mappingChanges.forEach((attribute) => {
    const current = currentByName.get(attribute.name)!
    const argumentsList = [
      `name = "${current.columnName}"`,
      attribute.mandatory ? 'nullable = false' : '',
      attribute.unique ? 'unique = true' : '',
      attribute.length ? `length = ${attribute.length}` : '',
      attribute.precision ? `precision = ${attribute.precision}` : '',
      attribute.scale !== undefined ? `scale = ${attribute.scale}` : '',
    ].filter(Boolean)
    previews.push([
      `// Managed annotation-only update for ${attribute.name}; manual code remains untouched.`,
      `@Column(${argumentsList.join(', ')})`,
      `// Liquibase: data-checked schema change with explicit rollback.`,
    ].join('\n'))
  })
  return previews.join('\n\n')
}

function previewJavaType(type: AttributeType): string {
  const mapping: Partial<Record<AttributeType, string>> = {
    string: 'String',
    integer: 'Integer',
    long: 'Long',
    double: 'Double',
    bigDecimal: 'BigDecimal',
    boolean: 'Boolean',
    date: 'Date',
    localDate: 'LocalDate',
    localDateTime: 'LocalDateTime',
    localTime: 'LocalTime',
    offsetDateTime: 'OffsetDateTime',
    uuid: 'UUID',
    byteArray: 'byte[]',
  }
  return mapping[type] ?? 'Object'
}

function defaultAssociation(type: AttributeType = 'association'): NonNullable<AttributeModel['association']> {
  return {
    associationType: type === 'composition' ? 'oneToOne' : 'manyToOne',
    relatedEntity: '',
    relatedIdColumnName: 'ID',
    relatedIdType: 'uuid',
    cascade: [],
    fetch: 'lazy',
    collectionType: 'list',
    crossDataStore: false,
    orphanRemoval: false,
  }
}

function toDatabaseName(value: string): string {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9_]/g, '_')
    .toUpperCase()
}

function suggestedJoinTable(
  entity: EntityModel,
  attribute: AttributeModel,
  association: NonNullable<AttributeModel['association']>,
  projectId?: string,
): NonNullable<NonNullable<AttributeModel['association']>['joinTable']> {
  const ownerTable = resolvedTableName(entity, projectId)
  const targetTable = association.relatedTableName ||
    toDatabaseName(association.relatedEntity.split('.').pop() || attribute.name || 'TARGET')
  return {
    name: `${ownerTable}_${targetTable}_LINK`,
    joinColumnName: `${toDatabaseName(entity.className || 'OWNER')}_ID`,
    inverseJoinColumnName: `${targetTable}_ID`,
  }
}

function resolvedEntityName(entity: EntityModel, projectId?: string): string {
  if (entity.entityName) return entity.entityName
  return projectId ? `${projectId}_${entity.className || 'Entity'}` : entity.className || 'Entity'
}

function resolvedTableName(entity: EntityModel, projectId?: string): string {
  const prefix = projectId ? `${projectId.toUpperCase()}_` : ''
  return entity.tableName ||
    `${prefix}${entity.className.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()}` ||
    'ENTITY'
}

function generatePreview(entity: EntityModel, projectId?: string): string {
  const lines: string[] = []
  lines.push(`package ${entity.packageName};`)
  lines.push('')
  lines.push('import jakarta.persistence.*;')
  lines.push('import io.jmix.core.metamodel.annotation.JmixEntity;')
  lines.push('import io.jmix.core.entity.annotation.JmixGeneratedValue;')
  if (entity.dataStore && entity.dataStore !== 'main') {
    lines.push('import io.jmix.core.metamodel.annotation.Store;')
  }
  lines.push('')
  if (entity.entityType === 'entity') {
    lines.push(`@Entity(name = "${resolvedEntityName(entity, projectId)}")`)
    lines.push(`@Table(name = "${resolvedTableName(entity, projectId)}")`)
  }
  if (entity.dataStore && entity.dataStore !== 'main') {
    lines.push(`@Store(name = "${entity.dataStore}")`)
  }
  lines.push('@JmixEntity')
  lines.push(`public class ${entity.className} {`)
  lines.push('')
  lines.push(`    @Id`)
  lines.push(`    @Column(name = "${entity.id.columnName}", nullable = false)`)
  if (entity.id.generation === 'jmixGenerated') {
    lines.push('    @JmixGeneratedValue')
  }
  const idType = entity.id.type === 'uuid' ? 'UUID' : entity.id.type === 'long' ? 'Long' : entity.id.type === 'integer' ? 'Integer' : 'String'
  lines.push(`    protected ${idType} id;`)
  lines.push('')
  entity.attributes.forEach((attr: any) => {
    if (attr.mandatory) lines.push('    @NotNull')
    lines.push(`    @Column(name = "${(attr.columnName || attr.name.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase())}")`)
    const type = attr.type === 'string' ? 'String' : attr.type === 'integer' ? 'Integer' : attr.type === 'long' ? 'Long' : attr.type === 'boolean' ? 'Boolean' : attr.type === 'bigDecimal' ? 'BigDecimal' : attr.type === 'localDate' ? 'LocalDate' : attr.type === 'localDateTime' ? 'LocalDateTime' : 'Object'
    lines.push(`    protected ${type} ${attr.name};`)
    lines.push('')
  })
  lines.push('    // getters and setters...')
  lines.push('}')
  return lines.join('\n')
}
