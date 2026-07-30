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
  ApplicationGraphResponse,
  DatabaseEntityTableInspectionResponse,
  DatabaseEntityTableBrowseResponse,
  DatabaseTableReference,
  DatabaseColumnSnapshot,
  EntityAttributePropagationChangeRequest,
  EntityAttributePropagationInspectionResponse,
  EntityAttributeTypeSchemaImpact,
  EntityAttributeTypeMigrationRequest,
  EntityAttributeTypeExpansionPreviewResponse,
  EntityAttributeTypeExpansionVerificationResponse,
  EntityAttributeTypeMappingCutoverRequest,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'

const ATTRIBUTE_TYPES: AttributeType[] = [
  'string', 'character', 'integer', 'long', 'double', 'bigDecimal', 'boolean',
  'date', 'localDate', 'localDateTime', 'localTime', 'offsetTime', 'offsetDateTime',
  'sqlDate', 'sqlTime', 'uuid', 'uri', 'byteArray', 'fileRef',
  'enum', 'association', 'composition', 'embedded', 'custom',
]

const TYPE_MIGRATION_TYPES: AttributeType[] = ATTRIBUTE_TYPES.filter(type =>
  !['enum', 'association', 'composition', 'embedded', 'custom'].includes(type),
)

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

interface DatabaseColumnDraft {
  selected: boolean
  attributeName: string
  attributeType: AttributeType
}

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
  const [applicationGraph, setApplicationGraph] = useState<ApplicationGraphResponse | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [renameBusy, setRenameBusy] = useState(false)
  const [renameLaunched, setRenameLaunched] = useState(false)
  const [safeDeleteBusy, setSafeDeleteBusy] = useState(false)
  const [typeMigrationTarget, setTypeMigrationTarget] = useState<AttributeType>('long')
  const [typeMigrationBusy, setTypeMigrationBusy] = useState(false)
  const [typeMigrationImpact, setTypeMigrationImpact] =
    useState<EntityAttributeTypeSchemaImpact | null>(null)
  const [typeExpansionPending, setTypeExpansionPending] = useState<{
    change: EntityAttributeTypeMigrationRequest
    response: EntityAttributeTypeExpansionPreviewResponse
  } | null>(null)
  const [typeExpansionBusy, setTypeExpansionBusy] = useState(false)
  const [typeExpansionApplied, setTypeExpansionApplied] = useState<string | null>(null)
  const [typeCutoverSession, setTypeCutoverSession] = useState<{
    attributeName: string
    targetType: AttributeType
    verification: EntityAttributeTypeExpansionVerificationResponse
    sourceMigrationOpened: boolean
  } | null>(null)
  const [typeMappingCutoverPreview, setTypeMappingCutoverPreview] =
    useState<WorkspaceChangePreviewResponse | null>(null)
  const [databaseInspection, setDatabaseInspection] =
    useState<DatabaseEntityTableInspectionResponse | null>(null)
  const [databaseColumnDrafts, setDatabaseColumnDrafts] =
    useState<Record<string, DatabaseColumnDraft>>({})
  const [databaseInspectBusy, setDatabaseInspectBusy] = useState(false)
  const [databaseSchemaName, setDatabaseSchemaName] = useState('')
  const [databaseCatalogName, setDatabaseCatalogName] = useState('')
  const [databaseBrowseSearch, setDatabaseBrowseSearch] = useState('')
  const [databaseIncludeViews, setDatabaseIncludeViews] = useState(true)
  const [databaseBrowse, setDatabaseBrowse] =
    useState<DatabaseEntityTableBrowseResponse | null>(null)
  const [databaseBrowseBusy, setDatabaseBrowseBusy] = useState(false)
  const [databaseInspectionMergeAllowed, setDatabaseInspectionMergeAllowed] = useState(true)
  const [propagationInspection, setPropagationInspection] =
    useState<EntityAttributePropagationInspectionResponse | null>(null)
  const [propagationSelection, setPropagationSelection] = useState<string[]>([])
  const [propagationPreview, setPropagationPreview] =
    useState<WorkspaceChangePreviewResponse | null>(null)
  const [propagationBusy, setPropagationBusy] = useState(false)

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
    bridge.getApplicationGraph().then((graph) => {
      if (active) setApplicationGraph(graph)
    }).catch((error) => {
      if (active) addToast(`Cannot load entity impact graph: ${error.message}`, 'error')
    })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    const selected = selectedAttr === null ? undefined : entity.attributes[selectedAttr]
    const activeCutover = selected && typeCutoverSession?.attributeName === selected.name
    if (
      selected &&
      !activeCutover &&
      TYPE_MIGRATION_TYPES.includes(selected.type) &&
      selected.type === typeMigrationTarget
    ) {
      setTypeMigrationTarget(
        TYPE_MIGRATION_TYPES.find(candidate => candidate !== selected.type) ?? selected.type,
      )
    }
    if (!typeCutoverSession) {
      setTypeMigrationImpact(null)
      setTypeExpansionPending(null)
      setTypeExpansionApplied(null)
      setTypeMappingCutoverPreview(null)
    }
  }, [selectedAttr, typeCutoverSession?.attributeName])

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
  const entityImpact = useMemo(() => {
    if (!applicationGraph) return []
    const qualifiedName = existingEntity?.qualifiedName || `${entity.packageName}.${entity.className}`
    const entityArtifacts = applicationGraph.artifacts.filter(artifact =>
      artifact.kind.toLowerCase().includes('entity') &&
      (
        artifact.semanticKey === qualifiedName ||
        artifact.displayName === qualifiedName ||
        artifact.displayName === entity.className
      ),
    )
    const ids = new Set(entityArtifacts.map(artifact => artifact.id))
    const relatedIds = new Set<string>()
    applicationGraph.relationships.forEach(relationship => {
      if (ids.has(relationship.sourceArtifactId) && relationship.targetArtifactId) {
        relatedIds.add(relationship.targetArtifactId)
      }
      if (relationship.targetArtifactId && ids.has(relationship.targetArtifactId)) {
        relatedIds.add(relationship.sourceArtifactId)
      }
    })
    return applicationGraph.artifacts
      .filter(artifact => relatedIds.has(artifact.id))
      .sort((left, right) =>
        left.kind.localeCompare(right.kind) || left.displayName.localeCompare(right.displayName))
  }, [applicationGraph, entity.className, entity.packageName, existingEntity])

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

  const handleNativeAttributeRename = async (attributeName: string) => {
    if (!existingEntity) return
    const newName = renameDraft.trim()
    if (!newName || newName === attributeName) {
      addToast('Enter a different property name', 'error')
      return
    }
    setRenameBusy(true)
    try {
      const response = await bridge.launchEntityAttributeRename({
        sourceLocator: existingEntity.sourceLocator,
        entityClassName: existingEntity.className,
        attributeName,
        newName,
      })
      addToast(response.message, response.success ? 'info' : 'error')
      if (response.success) setRenameLaunched(true)
    } catch (error: any) {
      addToast(`Native rename failed: ${error.message}`, 'error')
    } finally {
      setRenameBusy(false)
    }
  }

  const handleNativeAttributeSafeDelete = async (attributeName: string) => {
    if (!existingEntity) return
    setSafeDeleteBusy(true)
    try {
      const response = await bridge.launchEntityAttributeSafeDelete({
        sourceLocator: existingEntity.sourceLocator,
        entityClassName: existingEntity.className,
        attributeName,
      })
      addToast(response.message, response.success ? 'info' : 'error')
      if (response.success) setRenameLaunched(true)
    } catch (error: any) {
      addToast(`Native Safe Delete failed: ${error.message}`, 'error')
    } finally {
      setSafeDeleteBusy(false)
    }
  }

  const handleNativeAttributeTypeMigration = async (
    attributeName: string,
    currentType: AttributeType,
    verificationToken?: string,
  ) => {
    if (!existingEntity) return
    if (typeMigrationTarget === currentType && !verificationToken) {
      addToast('Choose a different target type', 'error')
      return
    }
    setTypeMigrationBusy(true)
    setTypeMigrationImpact(null)
    if (!verificationToken) {
      setTypeExpansionPending(null)
      setTypeExpansionApplied(null)
      setTypeCutoverSession(null)
      setTypeMappingCutoverPreview(null)
    }
    try {
      const response = await bridge.launchEntityAttributeTypeMigration({
        sourceLocator: existingEntity.sourceLocator,
        entityClassName: existingEntity.className,
        attributeName,
        targetType: typeMigrationTarget,
        verificationToken,
      })
      setTypeMigrationImpact(response.schemaImpact ?? null)
      addToast(
        response.message,
        response.success || response.code === 'JVW-ENTITY-TYPE-MIGRATION-SCHEMA-STAGE-REQUIRED'
          ? 'info'
          : 'error',
      )
      if (response.success) {
        setRenameLaunched(true)
        if (verificationToken) {
          setTypeCutoverSession(current => current
            ? { ...current, sourceMigrationOpened: true }
            : current)
        }
      }
    } catch (error: any) {
      addToast(`Native type migration failed: ${error.message}`, 'error')
    } finally {
      setTypeMigrationBusy(false)
    }
  }

  const previewTypeExpansion = async (attributeName: string) => {
    if (!existingEntity) return
    const change: EntityAttributeTypeMigrationRequest = {
      sourceLocator: existingEntity.sourceLocator,
      entityClassName: existingEntity.className,
      attributeName,
      targetType: typeMigrationTarget,
    }
    setTypeExpansionBusy(true)
    setTypeExpansionPending(null)
    try {
      const response = await bridge.previewEntityAttributeTypeExpansion(change)
      if (!response.accepted || !response.preview.planDigest) {
        addToast(response.message, 'error')
        return
      }
      setTypeExpansionPending({ change, response })
      addToast(response.message, 'info')
    } catch (error: any) {
      addToast(`Expansion preview failed: ${error.message}`, 'error')
    } finally {
      setTypeExpansionBusy(false)
    }
  }

  const applyTypeExpansion = async () => {
    const pending = typeExpansionPending
    const digest = pending?.response.preview.planDigest
    if (!pending || !digest) return
    setTypeExpansionBusy(true)
    try {
      const response = await bridge.applyEntityAttributeTypeExpansion(pending.change, digest)
      if (!response.success) {
        addToast(response.issues[0]?.message ?? 'Expansion apply was rejected', 'error')
        return
      }
      setTypeExpansionApplied(pending.response.shadowColumnName ?? 'shadow column')
      setTypeExpansionPending(null)
      addToast(
        'Expansion migration created. Deploy it and verify the backfill before source cutover.',
        'success',
      )
      const refreshed = await bridge.getSchemaWorkspace(true)
      setSchemaWorkspace(refreshed)
    } catch (error: any) {
      addToast(`Expansion apply failed: ${error.message}`, 'error')
    } finally {
      setTypeExpansionBusy(false)
    }
  }

  const verifyTypeExpansion = async (attributeName: string) => {
    if (!existingEntity) return
    setTypeExpansionBusy(true)
    setTypeMappingCutoverPreview(null)
    try {
      const response = await bridge.verifyEntityAttributeTypeExpansion({
        sourceLocator: existingEntity.sourceLocator,
        entityClassName: existingEntity.className,
        attributeName,
        targetType: typeMigrationTarget,
        verificationToken: typeCutoverSession?.verification.verificationToken,
      })
      if (!response.accepted || !response.verificationToken) {
        addToast(response.message, 'error')
        return
      }
      setTypeCutoverSession({
        attributeName,
        targetType: typeMigrationTarget,
        verification: response,
        sourceMigrationOpened: typeCutoverSession?.sourceMigrationOpened ?? false,
      })
      addToast(response.message, 'success')
    } catch (error: any) {
      addToast(`Live expansion verification failed: ${error.message}`, 'error')
    } finally {
      setTypeExpansionBusy(false)
    }
  }

  const mappingCutoverRequest = (
    attributeName: string,
  ): EntityAttributeTypeMappingCutoverRequest | null => {
    if (
      !existingEntity ||
      !typeCutoverSession?.verification.verificationToken ||
      typeCutoverSession.attributeName !== attributeName
    ) return null
    return {
      sourceLocator: existingEntity.sourceLocator,
      entityClassName: existingEntity.className,
      attributeName,
      targetType: typeCutoverSession.targetType,
      verificationToken: typeCutoverSession.verification.verificationToken,
    }
  }

  const previewTypeMappingCutover = async (attributeName: string) => {
    const request = mappingCutoverRequest(attributeName)
    if (!request) return
    setTypeExpansionBusy(true)
    setTypeMappingCutoverPreview(null)
    try {
      const response = await bridge.previewEntityAttributeTypeMappingCutover(request)
      if (!response.accepted || !response.planDigest) {
        addToast(response.issues[0]?.message ?? 'Mapping cutover was rejected', 'error')
        return
      }
      setTypeMappingCutoverPreview(response)
      addToast('Exact annotation-only mapping cutover is ready for review', 'info')
    } catch (error: any) {
      addToast(`Mapping cutover preview failed: ${error.message}`, 'error')
    } finally {
      setTypeExpansionBusy(false)
    }
  }

  const applyTypeMappingCutover = async (attributeName: string) => {
    const request = mappingCutoverRequest(attributeName)
    const digest = typeMappingCutoverPreview?.planDigest
    if (!request || !digest) return
    setTypeExpansionBusy(true)
    try {
      const response = await bridge.applyEntityAttributeTypeMappingCutover(request, digest)
      if (!response.success) {
        addToast(response.issues[0]?.message ?? 'Mapping cutover apply was rejected', 'error')
        return
      }
      setTypeMappingCutoverPreview(null)
      setTypeCutoverSession(null)
      setTypeMigrationImpact(null)
      addToast('Entity mapping switched atomically to the verified shadow column', 'success')
      const refreshed = await bridge.getSchemaWorkspace(true)
      setSchemaWorkspace(refreshed)
      const updated = refreshed.entities.find(
        candidate => candidate.qualifiedName === existingEntity?.qualifiedName,
      )
      if (updated) {
        const store = refreshed.stores.find(
          candidate => candidate.moduleId === updated.moduleId && candidate.name === updated.storeName,
        )
        setExistingEntity(updated)
        setEntity(existingEntityModel(updated, store?.id))
        setSelectedAttr(updated.attributes.findIndex(attribute => attribute.name === attributeName))
      }
    } catch (error: any) {
      addToast(`Mapping cutover apply failed: ${error.message}`, 'error')
    } finally {
      setTypeExpansionBusy(false)
    }
  }

  const inspectDatabaseTable = async (selection?: DatabaseTableReference) => {
    const storeId = entity.generationTarget?.storeId
    const configuredTableName = selection?.name || existingEntity?.tableName || entity.tableName
    if (!existingEntity || !storeId || !configuredTableName.trim()) {
      addToast('Select an existing mapped entity and data store first', 'error')
      return
    }
    const tableParts = configuredTableName.split('.').map(part => part.trim()).filter(Boolean)
    const tableName = tableParts.pop() ?? configuredTableName
    const schemaName = selection?.schema || databaseSchemaName.trim() ||
      (tableParts.length ? tableParts.join('.') : undefined)
    setDatabaseInspectBusy(true)
    setDatabaseInspection(null)
    setDatabaseColumnDrafts({})
    setDatabaseInspectionMergeAllowed(false)
    try {
      const response = await bridge.inspectDatabaseEntityTable({
        storeId,
        tableName,
        schemaName,
        catalogName: selection?.catalog || databaseCatalogName.trim() || undefined,
        expectedEntityQualifiedName: existingEntity.qualifiedName,
      })
      setDatabaseInspection(response)
      setDatabaseBrowse(null)
      if (!response.accepted || !response.table) {
        addToast(
          response.issues.map(issue => issue.message).join(', ') || 'Database inspection was rejected',
          'error',
        )
        return
      }
      const mergeAllowed = response.existingEntityQualifiedName === existingEntity.qualifiedName
      setDatabaseInspectionMergeAllowed(mergeAllowed)
      const drafts = Object.fromEntries(response.table.columns.map(column => [
        column.name,
        {
          selected: mergeAllowed && databaseColumnCanBeStaged(column),
          attributeName: column.suggestion.attributeName,
          attributeType: column.suggestion.attributeType,
        },
      ]))
      setDatabaseColumnDrafts(drafts)
      const missingCount = response.table.columns.filter(databaseColumnCanBeStaged).length
      addToast(
        mergeAllowed
          ? `Inspected ${qualifiedDatabaseTable(response)}: ${missingCount} safe unmapped column${missingCount === 1 ? '' : 's'}`
          : `Inspected ${qualifiedDatabaseTable(response)} in read-only comparison mode`,
        mergeAllowed ? 'success' : 'info',
      )
    } catch (error: any) {
      addToast(`Database inspection failed: ${error.message}`, 'error')
    } finally {
      setDatabaseInspectBusy(false)
    }
  }

  const browseDatabaseTables = async () => {
    const storeId = entity.generationTarget?.storeId
    if (!existingEntity || !storeId) {
      addToast('Select an existing mapped entity and data store first', 'error')
      return
    }
    setDatabaseBrowseBusy(true)
    try {
      const response = await bridge.browseDatabaseEntityTables({
        storeId,
        catalogName: databaseCatalogName.trim() || undefined,
        schemaName: databaseSchemaName.trim() || undefined,
        search: databaseBrowseSearch.trim(),
        includeViews: databaseIncludeViews,
        limit: 500,
      })
      setDatabaseBrowse(response)
      if (!response.accepted) {
        addToast(response.issues[0]?.message ?? 'Database browsing failed', 'error')
      } else if (response.truncated) {
        addToast(response.issues[0]?.message ?? 'Database results were truncated', 'info')
      }
    } catch (error: any) {
      addToast(`Database browsing failed: ${error.message}`, 'error')
    } finally {
      setDatabaseBrowseBusy(false)
    }
  }

  const stageSelectedDatabaseColumns = () => {
    if (
      !databaseInspectionMergeAllowed ||
      databaseInspection?.existingEntityQualifiedName !== existingEntity?.qualifiedName
    ) {
      addToast('Import is locked because this is not the selected entity’s exact mapped table', 'error')
      return
    }
    const columns = databaseInspection?.table?.columns ?? []
    const selected = columns.filter(column => databaseColumnDrafts[column.name]?.selected)
    if (!selected.length) {
      addToast('Select at least one unmapped database column', 'error')
      return
    }
    const existingNames = new Set(entity.attributes.map(attribute => attribute.name))
    const staged: AttributeModel[] = []
    const problems: string[] = []
    selected.forEach(column => {
      const draft = databaseColumnDrafts[column.name]
      const name = draft.attributeName.trim()
      if (!/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(name)) {
        problems.push(`${column.name}: enter a valid Java/Kotlin property name`)
        return
      }
      if (existingNames.has(name) || staged.some(attribute => attribute.name === name)) {
        problems.push(`${column.name}: property ${name} already exists`)
        return
      }
      if (draft.attributeType === 'custom') {
        problems.push(`${column.name}: choose an explicit supported type`)
        return
      }
      const attribute = databaseColumnToAttribute(
        column,
        draft,
        schemaWorkspace,
        existingEntity?.storeName ?? entity.dataStore,
      )
      if (!attribute) {
        problems.push(`${column.name}: referenced entity is not mapped in this data store`)
        return
      }
      attribute.unique = Boolean(databaseInspection?.table?.indexes.some(index =>
        index.unique &&
        index.columns.length === 1 &&
        index.columns[0].toUpperCase() === column.name.toUpperCase(),
      ))
      staged.push(attribute)
    })
    if (problems.length) {
      addToast(problems.join('; '), 'error')
      return
    }
    setEntity({ attributes: [...entity.attributes, ...staged] })
    setDatabaseColumnDrafts(current => Object.fromEntries(
      Object.entries(current).map(([columnName, draft]) => [
        columnName,
        draft.selected ? { ...draft, selected: false } : draft,
      ]),
    ))
    setSelectedAttr(entity.attributes.length)
    setGenerationPreview(null)
    addToast(
      `Staged ${staged.length} database-backed attribute${staged.length === 1 ? '' : 's'}; preview the atomic update before applying`,
      'success',
    )
  }

  const refreshAfterNativeRefactor = async () => {
    if (!existingEntity) return
    setSchemaLoading(true)
    try {
      const refreshed = await bridge.getSchemaWorkspace(true)
      setSchemaWorkspace(refreshed)
      const updated = refreshed.entities.find(
        candidate => candidate.qualifiedName === existingEntity.qualifiedName,
      )
      if (updated) {
        const store = refreshed.stores.find(
          candidate => candidate.moduleId === updated.moduleId && candidate.name === updated.storeName,
        )
        setExistingEntity(updated)
        setEntity(existingEntityModel(updated, store?.id))
        const cutoverAttributeIndex = typeCutoverSession
          ? updated.attributes.findIndex(attribute =>
              attribute.name === typeCutoverSession.attributeName)
          : -1
        setSelectedAttr(cutoverAttributeIndex >= 0 ? cutoverAttributeIndex : null)
        setRenameDraft('')
        setRenameLaunched(false)
        addToast(
          cutoverAttributeIndex >= 0
            ? 'Source migration indexed. Review the verified mapping cutover.'
            : 'Entity workspace refreshed after native refactor',
          'success',
        )
      }
    } catch (error: any) {
      addToast(`Cannot refresh entity workspace: ${error.message}`, 'error')
    } finally {
      setSchemaLoading(false)
    }
  }

  const handleApplyGeneration = async () => {
    if (!generationPreview?.planDigest) return
    const addedAttributeNames = existingEntity
      ? entity.attributes
          .filter(attribute => !existingAttributeNames.has(attribute.name))
          .map(attribute => attribute.name)
      : []
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
            if (addedAttributeNames.length) {
              await inspectAttributePropagation(updated, addedAttributeNames)
            }
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

  const propagationChange = (): EntityAttributePropagationChangeRequest | null => {
    if (!propagationInspection || !existingEntity) return null
    return {
      inspection: {
        entityQualifiedName: existingEntity.qualifiedName,
        entityName: existingEntity.entityName,
        className: existingEntity.className,
        attributeNames: propagationInspection.attributes,
      },
      targetIds: propagationSelection,
    }
  }

  const inspectAttributePropagation = async (
    snapshot: SchemaEntitySnapshot,
    attributeNames: string[],
  ) => {
    if (!attributeNames.length) {
      addToast('Select an existing attribute to inspect its connected surfaces', 'error')
      return
    }
    setPropagationBusy(true)
    setPropagationPreview(null)
    try {
      const response = await bridge.inspectEntityAttributePropagation({
        entityQualifiedName: snapshot.qualifiedName,
        entityName: snapshot.entityName,
        className: snapshot.className,
        attributeNames,
      })
      setPropagationInspection(response)
      setPropagationSelection(
        response.targets
          .filter(target => target.recommended && target.supported && !target.securityExpanding)
          .map(target => target.id),
      )
      if (response.accepted) {
        const editable = response.targets.filter(target => target.supported).length
        addToast(
          `Impact review found ${response.targets.length} connected targets; ${editable} can be updated safely`,
          'info',
        )
      } else {
        addToast(response.issues.map(issue => issue.message).join(', '), 'error')
      }
    } catch (error: any) {
      addToast(`Cannot inspect attribute propagation: ${error.message}`, 'error')
    } finally {
      setPropagationBusy(false)
    }
  }

  const handlePreviewPropagation = async () => {
    const change = propagationChange()
    if (!change) return
    setPropagationBusy(true)
    setPropagationPreview(null)
    try {
      const preview = await bridge.previewEntityAttributePropagation(change)
      setPropagationPreview(preview)
      if (!preview.accepted) {
        addToast(preview.issues.map(issue => issue.message).join(', '), 'error')
      }
    } catch (error: any) {
      addToast(`Cannot preview propagation: ${error.message}`, 'error')
    } finally {
      setPropagationBusy(false)
    }
  }

  const handleApplyPropagation = async () => {
    const change = propagationChange()
    if (!change || !propagationPreview?.planDigest) return
    setPropagationBusy(true)
    try {
      const result = await bridge.applyEntityAttributePropagation(
        change,
        propagationPreview.planDigest,
      )
      if (result.success) {
        addToast(
          `Propagated attributes atomically across ${result.filesChanged.length} files`,
          'success',
        )
        setPropagationInspection(null)
        setPropagationSelection([])
        setPropagationPreview(null)
        bridge.getApplicationGraph(true).then(setApplicationGraph).catch(() => undefined)
      } else {
        addToast(result.issues.map(issue => issue.message).join(', '), 'error')
      }
    } catch (error: any) {
      addToast(`Cannot apply propagation: ${error.message}`, 'error')
    } finally {
      setPropagationBusy(false)
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
    setDatabaseInspection(null)
    setDatabaseColumnDrafts({})
    setDatabaseSchemaName('')
    setPropagationInspection(null)
    setPropagationSelection([])
    setPropagationPreview(null)
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
    setDatabaseInspection(null)
    setDatabaseColumnDrafts({})
    setDatabaseSchemaName('')
    setPropagationInspection(null)
    setPropagationSelection([])
    setPropagationPreview(null)
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
                Safe round-trip mode preserves manual {entity.sourceLanguage === 'kotlin' ? 'Kotlin' : 'Java'}.
                Existing names, types, relationships, and removals are
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
                  {entity.sourceLanguage === 'kotlin' ? 'Kotlin' : 'Java'} → {selectedStore.moduleId}/src/main/{entity.sourceLanguage}
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
                This module has no managed Liquibase store. Source generation remains available; enable DDL only after
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
            <Field label="Source Language">
              <select
                value={entity.sourceLanguage}
                onChange={e => setEntity({ sourceLanguage: e.target.value as EntityModel['sourceLanguage'] })}
                className="w-full"
              >
                <option value="java">Java</option>
                <option value="kotlin">Kotlin</option>
              </select>
              <p className="mt-1 text-[9px] leading-relaxed text-gray-600">
                Generates directly into the module&apos;s matching source set.
              </p>
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
            {entity.entityType === 'entity' && (
              <Field label="Table Name">
                <input
                  value={entity.tableName}
                  onChange={e => setEntity({ tableName: e.target.value })}
                  placeholder={resolvedTableName(entity, effectiveProjectId)}
                  className="w-full"
                />
              </Field>
            )}
            <Field label="Entity Type">
              <select
                value={entity.entityType}
                onChange={e => {
                  const entityType = e.target.value as EntityModel['entityType']
                  setEntity({
                    entityType,
                    traits: ['entity', 'mappedSuperclass'].includes(entityType)
                      ? entity.traits.length ? entity.traits : ['standardEntity']
                      : [],
                    ...(entityType === 'enum' && !entity.enumConfig
                      ? { enumConfig: { idType: 'string' as const, values: [] } }
                      : {}),
                    ...(entityType === 'dto' && !entity.dtoConfig
                      ? { dtoConfig: { readOnly: false } }
                      : {}),
                  })
                }}
                className="w-full"
              >
                <option value="entity">Entity (JPA)</option>
                <option value="mappedSuperclass">Mapped Superclass</option>
                <option value="embeddable">Embeddable</option>
                <option value="dto">DTO</option>
                <option value="enum">Enumeration</option>
              </select>
            </Field>
            {entity.entityType !== 'enum' && <Field label="Instance Name Pattern">
              <input
                value={entity.instanceNamePattern || ''}
                onChange={e => setEntity({ instanceNamePattern: e.target.value || undefined })}
                placeholder="name"
                className="w-full"
              />
            </Field>}
            {entity.entityType !== 'enum' && <Field label="Instance Name Attribute">
              <select
                value={entity.instanceNameAttribute || ''}
                onChange={e => setEntity({ instanceNameAttribute: e.target.value || undefined })}
                className="w-full"
              >
                <option value="">Use pattern / none</option>
                {entity.attributes
                  .filter(attribute => attribute.name)
                  .map(attribute => (
                    <option key={attribute.name} value={attribute.name}>{attribute.name}</option>
                  ))}
              </select>
            </Field>}
            <Field label="Comment">
              <input
                value={entity.comment || ''}
                onChange={e => setEntity({ comment: e.target.value || undefined })}
                className="w-full"
              />
            </Field>
          </Section>

          {/* ID Configuration */}
          {!['embeddable', 'enum'].includes(entity.entityType) && (
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
            {entity.id.type === 'embedded' && (
              <Field label="Embedded ID Class">
                <input
                  value={entity.id.embeddedIdClass || ''}
                  onChange={e => setEntity({
                    id: { ...entity.id, embeddedIdClass: e.target.value || undefined },
                  })}
                  placeholder="com.example.entity.OrderId"
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
          )}

          {entity.entityType === 'enum' && (
            <Section title="Enumeration Values">
              <Field label="Stored ID Type">
                <select
                  value={entity.enumConfig?.idType ?? 'string'}
                  onChange={e => setEntity({
                    enumConfig: {
                      idType: e.target.value as 'string' | 'integer',
                      values: entity.enumConfig?.values ?? [],
                    },
                  })}
                  className="w-full"
                >
                  <option value="string">String</option>
                  <option value="integer">Integer</option>
                </select>
              </Field>
              <div className="space-y-2">
                {(entity.enumConfig?.values ?? []).map((value, index) => (
                  <div key={`${value.name}-${index}`} className="grid grid-cols-[1fr_1fr_auto] gap-1.5">
                    <input
                      value={value.name}
                      aria-label={`Enum constant ${index + 1}`}
                      placeholder="APPROVED"
                      onChange={e => setEntity({
                        enumConfig: {
                          idType: entity.enumConfig?.idType ?? 'string',
                          values: (entity.enumConfig?.values ?? []).map((item, itemIndex) =>
                            itemIndex === index ? { ...item, name: e.target.value } : item),
                        },
                      })}
                    />
                    <input
                      value={value.storedValue}
                      aria-label={`Stored enum ID ${index + 1}`}
                      placeholder={entity.enumConfig?.idType === 'integer' ? '10' : 'A'}
                      onChange={e => setEntity({
                        enumConfig: {
                          idType: entity.enumConfig?.idType ?? 'string',
                          values: (entity.enumConfig?.values ?? []).map((item, itemIndex) =>
                            itemIndex === index ? { ...item, storedValue: e.target.value } : item),
                        },
                      })}
                    />
                    <button
                      type="button"
                      aria-label={`Remove enum value ${index + 1}`}
                      onClick={() => setEntity({
                        enumConfig: {
                          idType: entity.enumConfig?.idType ?? 'string',
                          values: (entity.enumConfig?.values ?? []).filter((_, itemIndex) => itemIndex !== index),
                        },
                      })}
                      className="px-2 text-red-400 hover:text-red-300"
                    >
                      ✕
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  onClick={() => setEntity({
                    enumConfig: {
                      idType: entity.enumConfig?.idType ?? 'string',
                      values: [
                        ...(entity.enumConfig?.values ?? []),
                        {
                          name: `VALUE_${(entity.enumConfig?.values.length ?? 0) + 1}`,
                          storedValue: entity.enumConfig?.idType === 'integer'
                            ? String((entity.enumConfig?.values.length ?? 0) + 1)
                            : `V${(entity.enumConfig?.values.length ?? 0) + 1}`,
                        },
                      ],
                    },
                  })}
                  className="w-full rounded border border-dashed border-surface-border py-1.5 text-[10px] text-jmix-300 hover:bg-jmix-500/10"
                >
                  + Add enum value
                </button>
              </div>
            </Section>
          )}

          {entity.entityType === 'dto' && (
            <Section title="DTO Behavior">
              <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
                <input
                  type="checkbox"
                  checked={entity.dtoConfig?.readOnly ?? false}
                  onChange={e => setEntity({ dtoConfig: { readOnly: e.target.checked } })}
                />
                Read-only DTO (no generated setters)
              </label>
            </Section>
          )}

          {/* Traits */}
          {['entity', 'mappedSuperclass'].includes(entity.entityType) && (
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
          )}

          {/* Inheritance */}
          {['entity', 'mappedSuperclass'].includes(entity.entityType) && (
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
          )}

          {/* Options */}
          <Section title="Options">
            {entity.entityType !== 'enum' && <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.systemLevel}
                onChange={e => setEntity({ systemLevel: e.target.checked })}
                className="rounded border-surface-border"
              />
              System-level entity
            </label>}
            {entity.entityType !== 'enum' && <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.annotatedPropertiesOnly}
                onChange={e => setEntity({ annotatedPropertiesOnly: e.target.checked })}
                className="rounded border-surface-border"
              />
              Include annotated properties only
            </label>}
            {entity.entityType === 'entity' && <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.databaseView}
                onChange={e => setEntity({ databaseView: e.target.checked })}
                className="rounded border-surface-border"
              />
              Maps an existing database view
            </label>}
            {entity.entityType === 'entity' && <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
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
            </label>}
            {entity.entityType === 'entity' && entity.ddlGeneration.enabled && !entity.databaseView && (
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
            {entity.entityType === 'entity' && <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer mt-1.5">
              <input
                type="checkbox"
                checked={entity.dataRepository?.enabled || false}
                onChange={e => setEntity({ dataRepository: { enabled: e.target.checked } })}
                className="rounded border-surface-border"
              />
              Generate Data Repository
            </label>}
          </Section>
          </fieldset>
        </div>

        {/* Center: Attributes Table */}
        <div className={`${activePane === 'attributes' ? 'block' : 'hidden'} min-h-0 min-w-0 flex-1 overflow-y-auto p-3 sm:p-4 lg:block`}>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider">
              {entity.entityType === 'enum' ? 'Enumeration' : 'Attributes'}
            </h3>
            {entity.entityType !== 'enum' && (
              <div className="flex flex-wrap justify-end gap-2">
                {existingEntity && (
                  <>
                    <button
                      type="button"
                      onClick={browseDatabaseTables}
                      disabled={databaseBrowseBusy || databaseInspectBusy || !entity.generationTarget?.storeId}
                      className="rounded border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-200 transition-colors hover:bg-cyan-500/20 disabled:opacity-50"
                    >
                      {databaseBrowseBusy ? 'Browsing database…' : '⌕ Browse live database'}
                    </button>
                    <button
                      type="button"
                      onClick={() => inspectDatabaseTable()}
                      disabled={databaseInspectBusy || databaseBrowseBusy || !entity.generationTarget?.storeId}
                      className="rounded border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-200 transition-colors hover:bg-cyan-500/20 disabled:opacity-50"
                    >
                      {databaseInspectBusy ? 'Comparing database…' : '↻ Compare mapped table'}
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        const attribute = selectedAttr === null ? null : entity.attributes[selectedAttr]
                        inspectAttributePropagation(
                          existingEntity,
                          attribute && existingAttributeNames.has(attribute.name) ? [attribute.name] : [],
                        )
                      }}
                      disabled={
                        propagationBusy ||
                        selectedAttr === null ||
                        !existingAttributeNames.has(entity.attributes[selectedAttr]?.name ?? '')
                      }
                      className="rounded border border-violet-500/30 bg-violet-500/10 px-3 py-1 text-xs text-violet-200 transition-colors hover:bg-violet-500/20 disabled:opacity-50"
                    >
                      {propagationBusy ? 'Mapping impact…' : '⇢ Add selected attribute to views'}
                    </button>
                  </>
                )}
                <button
                  onClick={addAttribute}
                  className="rounded bg-jmix-500/20 px-3 py-1 text-xs text-jmix-400 transition-colors hover:bg-jmix-500/30"
                >
                  + Add Attribute
                </button>
              </div>
            )}
          </div>

          {(databaseBrowseBusy || databaseBrowse) && (
            <DatabaseBrowsePanel
              busy={databaseBrowseBusy}
              browse={databaseBrowse}
              catalogName={databaseCatalogName}
              schemaName={databaseSchemaName}
              search={databaseBrowseSearch}
              includeViews={databaseIncludeViews}
              mappedTableName={existingEntity?.tableName ?? entity.tableName}
              mappedTableSchema={existingEntity?.tableSchema}
              mappedTableCatalog={existingEntity?.tableCatalog}
              onCatalogChange={setDatabaseCatalogName}
              onSchemaChange={setDatabaseSchemaName}
              onSearchChange={setDatabaseBrowseSearch}
              onIncludeViewsChange={setDatabaseIncludeViews}
              onRefresh={browseDatabaseTables}
              onInspect={inspectDatabaseTable}
              onClose={() => setDatabaseBrowse(null)}
            />
          )}

          {(databaseInspectBusy || databaseInspection) && (
            <DatabaseInspectionPanel
              busy={databaseInspectBusy}
              inspection={databaseInspection}
              mergeAllowed={databaseInspectionMergeAllowed}
              drafts={databaseColumnDrafts}
              onDraftChange={(columnName, change) => setDatabaseColumnDrafts(current => ({
                ...current,
                [columnName]: {
                  ...(current[columnName] ?? {
                    selected: false,
                    attributeName: '',
                    attributeType: 'custom' as AttributeType,
                  }),
                  ...change,
                },
              }))}
              onClose={() => {
                setDatabaseInspection(null)
                setDatabaseColumnDrafts({})
              }}
              onStage={stageSelectedDatabaseColumns}
            />
          )}

          {(propagationBusy || propagationInspection) && (
            <div className="mb-4 min-w-0 rounded-xl border border-violet-500/30 bg-violet-500/5 p-3 sm:p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-xs font-semibold text-violet-100">
                    Connected attribute impact
                  </div>
                  <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                    Review each exact source target. View, fetch-plan, and default-locale changes are
                    source-preserving. Privilege-expanding security updates are never selected automatically.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setPropagationInspection(null)
                    setPropagationSelection([])
                    setPropagationPreview(null)
                  }}
                  className="rounded border border-surface-border px-2 py-1 text-[10px] text-gray-400 hover:text-gray-200"
                >
                  Close
                </button>
              </div>

              {propagationBusy && !propagationInspection ? (
                <div className="mt-3 text-[10px] text-violet-200">Indexing connected surfaces…</div>
              ) : propagationInspection && (
                <>
                  <div className="mt-3 flex flex-wrap gap-1.5">
                    {propagationInspection.attributes.map(attribute => (
                      <span
                        key={attribute}
                        className="rounded border border-violet-500/20 bg-violet-500/10 px-2 py-1 font-mono text-[9px] text-violet-100"
                      >
                        {attribute}
                      </span>
                    ))}
                  </div>
                  {propagationInspection.issues.length > 0 && (
                    <div className="mt-3 space-y-1.5">
                      {propagationInspection.issues.map((issue, index) => (
                        <div
                          key={`${issue.code}-${issue.relativePath ?? index}`}
                          className="rounded border border-amber-500/20 bg-amber-500/5 px-2.5 py-2 text-[9px] leading-relaxed text-amber-100/80"
                        >
                          {issue.message}
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="mt-3 grid min-w-0 gap-2 xl:grid-cols-2">
                    {propagationInspection.targets.map(target => {
                      const checked = propagationSelection.includes(target.id)
                      return (
                        <label
                          key={target.id}
                          className={`flex min-w-0 gap-3 rounded-lg border p-3 ${
                            target.securityExpanding
                              ? 'border-amber-500/25 bg-amber-500/5'
                              : checked
                                ? 'border-violet-500/35 bg-violet-500/10'
                                : 'border-surface-border bg-black/10'
                          } ${target.supported ? 'cursor-pointer' : 'cursor-not-allowed opacity-80'}`}
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={!target.supported || propagationBusy}
                            onChange={event => {
                              setPropagationPreview(null)
                              setPropagationSelection(current => event.target.checked
                                ? [...current, target.id]
                                : current.filter(id => id !== target.id))
                            }}
                            className="mt-0.5"
                          />
                          <span className="min-w-0 flex-1">
                            <span className="flex flex-wrap items-center gap-1.5">
                              <span className="text-[10px] font-medium text-gray-200">{target.label}</span>
                              <span className="rounded bg-surface-lighter px-1.5 py-0.5 text-[8px] text-gray-500">
                                {target.kind.replace(/_/g, ' ')}
                              </span>
                              {target.securityExpanding && (
                                <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-[8px] text-amber-200">
                                  privilege review
                                </span>
                              )}
                            </span>
                            <span className="mt-1 block text-[9px] leading-relaxed text-gray-500">
                              {target.detail}
                            </span>
                            <span
                              className="mt-1 block truncate font-mono text-[8px] text-gray-600"
                              title={target.relativePath}
                            >
                              {target.relativePath}
                            </span>
                          </span>
                        </label>
                      )
                    })}
                  </div>

                  {propagationInspection.targets.length === 0 && (
                    <div className="mt-3 rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-3 text-[10px] text-emerald-200">
                      No connected source requires this attribute.
                    </div>
                  )}

                  {propagationPreview && (
                    <div className="mt-3 rounded-lg border border-violet-500/25 bg-black/15 p-3">
                      <div className="text-[10px] font-medium text-violet-100">{propagationPreview.label}</div>
                      <div className="mt-2 flex flex-wrap gap-1.5">
                        {propagationPreview.files.map(file => (
                          <span
                            key={file.relativePath}
                            className="max-w-full truncate rounded border border-violet-500/20 px-2 py-1 font-mono text-[8px] text-violet-100/70"
                            title={file.relativePath}
                          >
                            {file.mode} · {file.relativePath}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}

                  <div className="mt-3 flex flex-wrap justify-end gap-2">
                    <button
                      type="button"
                      onClick={handlePreviewPropagation}
                      disabled={propagationBusy || propagationSelection.length === 0}
                      className="rounded border border-violet-500/30 px-3 py-1.5 text-[10px] text-violet-200 hover:bg-violet-500/10 disabled:opacity-50"
                    >
                      Preview selected targets
                    </button>
                    <button
                      type="button"
                      onClick={handleApplyPropagation}
                      disabled={propagationBusy || !propagationPreview?.planDigest}
                      className="rounded bg-violet-500 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-violet-600 disabled:opacity-50"
                    >
                      Apply atomic propagation
                    </button>
                  </div>
                </>
              )}
            </div>
          )}

          {entity.entityType === 'enum' ? (
            <div className="grid min-h-[24rem] place-items-center rounded-xl border border-surface-border bg-gradient-to-br from-surface-light/70 to-surface p-4 sm:p-6">
              <div className="w-full max-w-2xl">
                <div className="text-center">
                  <div className="text-xs font-semibold text-gray-200">Typed Jmix enumeration</div>
                  <p className="mt-2 text-[10px] leading-relaxed text-gray-500">
                    Each constant has a stable persisted {entity.enumConfig?.idType ?? 'string'} ID. Entity
                    attributes store that ID and expose the enum through null-safe EnumClass accessors.
                  </p>
                </div>
                <div className="mt-5 space-y-2">
                  {(entity.enumConfig?.values ?? []).map((value, index) => (
                    <div key={`${value.name}-${index}`} className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-lg border border-surface-border bg-surface p-3">
                      <span className="rounded bg-jmix-500/15 px-2 py-1 text-[9px] text-jmix-300">{index + 1}</span>
                      <span className="truncate font-mono text-[10px] text-gray-200">{value.name}</span>
                      <span className="font-mono text-[10px] text-gray-500">{value.storedValue}</span>
                    </div>
                  ))}
                  {(entity.enumConfig?.values.length ?? 0) === 0 && (
                    <div className="rounded-lg border border-dashed border-surface-border p-6 text-center text-[10px] text-gray-600">
                      Add the first enumeration value in Entity setup.
                    </div>
                  )}
                </div>
              </div>
            </div>
          ) : entity.attributes.length === 0 ? (
            <div className="grid min-h-[24rem] place-items-center rounded-xl border border-dashed border-surface-border bg-gradient-to-br from-surface-light/70 to-surface p-4 sm:p-6">
              <div className="w-full max-w-3xl">
                <div className="mx-auto max-w-xl text-center">
                  <div className="text-xs font-semibold text-gray-200">Design the entity contract</div>
                  <p className="mt-2 text-[10px] leading-relaxed text-gray-500">
                    Add persistent fields, Jmix enums, files, embedded values, or cross-module relationships.
                    Java metadata, Liquibase, localization, repositories, and connected usage impact stay aligned.
                  </p>
                  <button
                    type="button"
                    onClick={addAttribute}
                    className="mt-4 rounded bg-jmix-500 px-4 py-2 text-xs font-medium text-white hover:bg-jmix-600"
                  >
                    + Add first attribute
                  </button>
                </div>
                <div className="mt-6 grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
                  {[
                    ['Scalar data', 'Strings, numbers, money, dates, URI, files, LOBs, and custom datatypes'],
                    ['Domain values', 'Typed Jmix EnumClass storage with string or integer identifiers'],
                    ['Relationships', 'Associations, compositions, ownership, cascades, and cross-store references'],
                    ['Safe evolution', 'Atomic source + Liquibase preview with rollback and project-wide impact'],
                  ].map(([title, description]) => (
                    <div key={title} className="rounded-lg border border-surface-border bg-surface/80 p-3">
                      <div className="text-[10px] font-medium text-jmix-300">{title}</div>
                      <p className="mt-1 text-[9px] leading-relaxed text-gray-600">{description}</p>
                    </div>
                  ))}
                </div>
                <div className="mt-4 grid gap-2 rounded-lg border border-surface-border bg-black/10 p-3 text-[9px] text-gray-500 sm:grid-cols-3">
                  <div><span className="text-gray-300">Target</span><br />{selectedModuleId || 'Unresolved module'}</div>
                  <div><span className="text-gray-300">Store</span><br />{entity.dataStore || 'main'}</div>
                  <div><span className="text-gray-300">DDL policy</span><br />{entity.ddlGeneration.enabled ? entity.ddlGeneration.mode : 'disabled'}</div>
                </div>
              </div>
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
                        onClick={() => {
                          const next = selectedAttr === i ? null : i
                          setSelectedAttr(next)
                          setRenameDraft(next === null ? '' : attr.name)
                          setRenameLaunched(false)
                        }}
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
                          {['string', 'enum', 'uri', 'fileRef'].includes(attr.type) && (
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
                const sourceAssociation = source?.associationDetails
                const joinColumnRenameSafe = Boolean(
                  source?.persistent &&
                  sourceAssociation?.joinColumnName &&
                  !sourceAssociation.crossDataStore &&
                  !sourceAssociation.mappedBy &&
                  ['manyToOne', 'oneToOne'].includes(sourceAssociation.associationType) &&
                  existingEntity?.ddlMode !== 'DISABLED' &&
                  !existingEntity?.databaseView,
                )
                return (
                  <div className="mt-4 rounded-lg border border-surface-border bg-surface-light p-4">
                    <div className="text-xs text-gray-400">
                      <span className="font-mono text-gray-200">{selected.name}</span>
                      {' · '}
                      {mappingLocked
                        ? joinColumnRenameSafe
                          ? 'owning relationship · checked join-column evolution'
                          : 'relationship/transient mapping is source-protected'
                        : 'safe persistence metadata editor'}
                    </div>
                    {joinColumnRenameSafe && selected.association && (
                      <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                        <Field label="Physical join column">
                          <input
                            value={selected.association.joinColumnName ?? ''}
                            onChange={(event) => updateAttribute(selectedAttr, {
                              association: {
                                ...selected.association!,
                                joinColumnName: event.target.value || undefined,
                              },
                            })}
                            className="w-full min-w-0 font-mono"
                            aria-label={`Physical join column for ${selected.name}`}
                          />
                        </Field>
                        <div className="min-w-0 rounded border border-emerald-500/20 bg-emerald-500/5 p-2 text-[10px] leading-relaxed text-emerald-200/80">
                          Preview updates only the literal <code>@JoinColumn(name)</code> and creates a guarded
                          Liquibase rename with reverse rollback. Target, cardinality, ownership, cascade, fetch,
                          nullability, and constraints remain locked.
                        </div>
                      </div>
                    )}
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
                        {['string', 'enum', 'uri', 'fileRef'].includes(selected.type) && (
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
                      Mapping edits preserve property identity, Java/Kotlin type, manual annotations, accessors, and call sites.
                      {' '}Unsafe relationship changes remain blocked by the backend even if a request bypasses this UI.
                    </p>
                    {source && (
                      <div className="mt-4 rounded-lg border border-jmix-500/20 bg-jmix-500/5 p-3">
                        <div className="text-[10px] font-semibold uppercase tracking-wider text-jmix-300">
                          Native safe rename
                        </div>
                        <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                          Opens IntelliJ&apos;s usage preview so Java, Kotlin, FlowUI, fetch plans, JPQL, and security
                          references and JPA mappedBy strings participate in the IDE refactor. Persistent scalar and
                          owning relationship properties require explicit stable column or join-table mappings.
                        </p>
                        <div className="mt-3 flex flex-col gap-2 sm:flex-row">
                          <input
                            value={renameDraft}
                            onChange={event => setRenameDraft(event.target.value)}
                            className="min-w-0 flex-1"
                            aria-label={`New name for ${selected.name}`}
                          />
                          <button
                            type="button"
                            disabled={renameBusy || !renameDraft.trim() || renameDraft.trim() === selected.name}
                            onClick={() => handleNativeAttributeRename(selected.name)}
                            className="rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
                          >
                            {renameBusy ? 'Resolving…' : 'Open usage preview'}
                          </button>
                          {renameLaunched && (
                            <button
                              type="button"
                              disabled={schemaLoading}
                              onClick={refreshAfterNativeRefactor}
                              className="rounded border border-surface-border px-3 py-1.5 text-xs text-gray-300 hover:bg-surface-lighter"
                            >
                              Refresh after apply
                            </button>
                          )}
                        </div>
                        {!['enum', 'association', 'composition', 'embedded', 'custom'].includes(selected.type) && (
                          <div className="mt-4 border-t border-surface-border pt-3">
                            <div className="text-[10px] font-semibold uppercase tracking-wider text-sky-300">
                              Project-wide type migration
                            </div>
                            <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                              Runs IntelliJ&apos;s real Type Migration over Java and Kotlin light declarations, dependent
                              variables, parameters, return types, and call sites. Persistent columns are inspected
                              separately because a data conversion is not automatically reversible.
                            </p>
                            <div className="mt-3 flex min-w-0 flex-col gap-2 sm:flex-row">
                              <select
                                value={typeMigrationTarget}
                                onChange={event => {
                                  setTypeMigrationTarget(event.target.value as AttributeType)
                                  setTypeMigrationImpact(null)
                                }}
                                className="min-w-0 flex-1"
                                aria-label={`Target type for ${selected.name}`}
                              >
                                {TYPE_MIGRATION_TYPES.map(type => (
                                  <option key={type} value={type} disabled={type === selected.type}>
                                    {type === selected.type ? `${type} (current)` : type}
                                  </option>
                                ))}
                              </select>
                              <button
                                type="button"
                                disabled={typeMigrationBusy || typeMigrationTarget === selected.type}
                                onClick={() => handleNativeAttributeTypeMigration(selected.name, selected.type)}
                                className="shrink-0 rounded border border-sky-500/40 bg-sky-500/10 px-3 py-1.5 text-xs font-medium text-sky-200 hover:bg-sky-500/20 disabled:opacity-50"
                              >
                                {typeMigrationBusy ? 'Analyzing project…' : 'Open type preview'}
                              </button>
                            </div>
                            {typeMigrationImpact &&
                              (!typeCutoverSession || typeCutoverSession.attributeName === selected.name) && (
                              <div className={`mt-3 min-w-0 rounded border p-2.5 text-[10px] leading-relaxed ${
                                typeMigrationImpact.strategy === 'SOURCE_ONLY'
                                  ? 'border-emerald-500/25 bg-emerald-500/5 text-emerald-100/80'
                                  : 'border-amber-500/30 bg-amber-500/5 text-amber-100/80'
                              }`}>
                                <div className="font-semibold">
                                  {typeMigrationImpact.strategy.replace(/_/g, ' ')}
                                </div>
                                <div className="mt-1 break-words">{typeMigrationImpact.summary}</div>
                                {typeMigrationImpact.tableName && typeMigrationImpact.columnName && (
                                  <div className="mt-1 break-all font-mono text-[9px] opacity-75">
                                    {typeMigrationImpact.tableName}.{typeMigrationImpact.columnName}
                                    {typeMigrationImpact.currentSqlType && typeMigrationImpact.targetSqlType
                                      ? ` · ${typeMigrationImpact.currentSqlType} → ${typeMigrationImpact.targetSqlType}`
                                      : ''}
                                  </div>
                                )}
                                {typeMigrationImpact.dependencies.length > 0 && (
                                  <div className="mt-1 break-words text-[9px] opacity-75">
                                    Dependencies: {typeMigrationImpact.dependencies.join(', ')}
                                  </div>
                                )}
                                {typeMigrationImpact.strategy === 'EXPAND_CONTRACT_REQUIRED' && (
                                  <div className="mt-3 border-t border-amber-500/20 pt-2">
                                    <p className="text-[9px] leading-relaxed opacity-80">
                                      Expansion adds a deterministic nullable shadow column, copies every existing
                                      non-null value through the portable lossless matrix, restores mandatory
                                      nullability, and rolls back by dropping only the shadow. The original column
                                      remains untouched.
                                    </p>
                                    {!typeExpansionPending && !typeExpansionApplied && (
                                      <button
                                        type="button"
                                        disabled={typeExpansionBusy}
                                        onClick={() => previewTypeExpansion(selected.name)}
                                        className="mt-2 rounded border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-[10px] font-medium text-amber-100 hover:bg-amber-500/20 disabled:opacity-50"
                                      >
                                        {typeExpansionBusy ? 'Building safe plan…' : 'Preview expansion migration'}
                                      </button>
                                    )}
                                    {typeExpansionPending && (
                                      <div className="mt-2 rounded border border-surface-border bg-black/15 p-2">
                                        <div className="font-semibold text-gray-200">
                                          Shadow: {typeExpansionPending.response.shadowColumnName}
                                        </div>
                                        <div className="mt-1 flex max-w-full flex-wrap gap-1">
                                          {typeExpansionPending.response.preview.files.map(file => (
                                            <span
                                              key={file.relativePath}
                                              className="max-w-full truncate rounded border border-surface-border px-1.5 py-1 font-mono text-[8px] text-gray-400"
                                              title={file.relativePath}
                                            >
                                              {file.mode} · {file.relativePath}
                                            </span>
                                          ))}
                                        </div>
                                        <div className="mt-2 flex flex-wrap gap-2">
                                          <button
                                            type="button"
                                            disabled={typeExpansionBusy}
                                            onClick={() => setTypeExpansionPending(null)}
                                            className="rounded border border-surface-border px-2.5 py-1 text-[9px] text-gray-400 hover:text-gray-200"
                                          >
                                            Discard
                                          </button>
                                          <button
                                            type="button"
                                            disabled={typeExpansionBusy}
                                            onClick={applyTypeExpansion}
                                            className="rounded bg-amber-500 px-2.5 py-1 text-[9px] font-semibold text-black hover:bg-amber-400 disabled:opacity-50"
                                          >
                                            {typeExpansionBusy ? 'Applying…' : 'Create expansion changelog'}
                                          </button>
                                        </div>
                                      </div>
                                    )}
                                    {typeExpansionApplied && (
                                      <div className="mt-2 rounded border border-emerald-500/30 bg-emerald-500/5 p-2 text-emerald-100/80">
                                        Created {typeExpansionApplied}. Deploy and validate this migration before
                                        mapping cutover. The plugin will not open the source refactor early.
                                      </div>
                                    )}
                                    <div className="mt-3 rounded-lg border border-sky-500/25 bg-sky-500/5 p-2.5">
                                      <div className="flex flex-wrap items-center gap-1.5 text-[9px]">
                                        {[
                                          ['1', 'Deploy expansion', Boolean(typeExpansionApplied)],
                                          ['2', 'Verify live data', Boolean(typeCutoverSession)],
                                          ['3', 'Migrate source', Boolean(typeCutoverSession?.sourceMigrationOpened)],
                                          ['4', 'Switch mapping', false],
                                        ].map(([step, label, complete]) => (
                                          <span
                                            key={String(step)}
                                            className={`rounded-full border px-2 py-1 ${
                                              complete
                                                ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-100'
                                                : 'border-surface-border bg-black/15 text-gray-400'
                                            }`}
                                          >
                                            {step}. {label}
                                          </span>
                                        ))}
                                      </div>
                                      {!typeCutoverSession && (
                                        <div className="mt-2">
                                          <p className="text-[9px] leading-relaxed text-sky-100/70">
                                            This opens a read-only connection from the active Jmix data-store profile.
                                            It checks the deployed SQL type and compares shadow values with the
                                            authoritative original; no credentials or row values enter the designer.
                                          </p>
                                          <button
                                            type="button"
                                            disabled={typeExpansionBusy}
                                            onClick={() => verifyTypeExpansion(selected.name)}
                                            className="mt-2 rounded border border-sky-500/40 bg-sky-500/10 px-3 py-1.5 text-[10px] font-medium text-sky-100 hover:bg-sky-500/20 disabled:opacity-50"
                                          >
                                            {typeExpansionBusy ? 'Verifying deployed data…' : 'Verify deployed expansion'}
                                          </button>
                                        </div>
                                      )}
                                      {typeCutoverSession?.attributeName === selected.name && (
                                        <div className="mt-2 space-y-2">
                                          <div className="rounded border border-emerald-500/25 bg-emerald-500/5 p-2 text-[9px] leading-relaxed text-emerald-100/80">
                                            <div className="font-semibold">
                                              Live evidence {typeCutoverSession.verification.evidenceDigest?.slice(0, 12)}
                                            </div>
                                            <div className="mt-0.5 break-words">
                                              {typeCutoverSession.verification.database?.name}{' '}
                                              {typeCutoverSession.verification.database?.version} ·{' '}
                                              {typeCutoverSession.verification.shadowColumnName}{' '}
                                              {typeCutoverSession.verification.targetSqlType} · exact value parity
                                            </div>
                                          </div>
                                          <button
                                            type="button"
                                            disabled={typeExpansionBusy}
                                            onClick={() => verifyTypeExpansion(selected.name)}
                                            className="rounded border border-emerald-500/30 px-2.5 py-1 text-[9px] text-emerald-100 hover:bg-emerald-500/10 disabled:opacity-50"
                                          >
                                            {typeExpansionBusy ? 'Re-verifying…' : 'Re-verify live evidence'}
                                          </button>
                                          {!typeCutoverSession.sourceMigrationOpened ? (
                                            <button
                                              type="button"
                                              disabled={typeMigrationBusy}
                                              onClick={() => handleNativeAttributeTypeMigration(
                                                selected.name,
                                                selected.type,
                                                typeCutoverSession.verification.verificationToken,
                                              )}
                                              className="rounded bg-sky-500 px-3 py-1.5 text-[10px] font-semibold text-white hover:bg-sky-400 disabled:opacity-50"
                                            >
                                              {typeMigrationBusy
                                                ? 'Opening IntelliJ preview…'
                                                : 'Open verified source migration'}
                                            </button>
                                          ) : (
                                            <div>
                                              <p className="text-[9px] leading-relaxed text-gray-400">
                                                Apply IntelliJ&apos;s usage preview, then use “Refresh after apply”.
                                                The next preview changes only the exact @Column name literal and
                                                rechecks the live database first.
                                              </p>
                                              {!typeMappingCutoverPreview ? (
                                                <button
                                                  type="button"
                                                  disabled={typeExpansionBusy}
                                                  onClick={() => previewTypeMappingCutover(selected.name)}
                                                  className="mt-2 rounded border border-violet-500/40 bg-violet-500/10 px-3 py-1.5 text-[10px] font-medium text-violet-100 hover:bg-violet-500/20 disabled:opacity-50"
                                                >
                                                  {typeExpansionBusy
                                                    ? 'Rechecking live database…'
                                                    : 'Preview exact mapping cutover'}
                                                </button>
                                              ) : (
                                                <div className="mt-2 rounded border border-violet-500/25 bg-black/15 p-2">
                                                  <div className="font-semibold text-violet-100">
                                                    {typeMappingCutoverPreview.label}
                                                  </div>
                                                  {typeMappingCutoverPreview.files.map(file => (
                                                    <div
                                                      key={file.relativePath}
                                                      className="mt-1 break-all font-mono text-[8px] text-gray-400"
                                                    >
                                                      {file.mode} · {file.relativePath} · {file.appliedEditCount} exact edit
                                                    </div>
                                                  ))}
                                                  <div className="mt-2 flex flex-wrap gap-2">
                                                    <button
                                                      type="button"
                                                      disabled={typeExpansionBusy}
                                                      onClick={() => setTypeMappingCutoverPreview(null)}
                                                      className="rounded border border-surface-border px-2.5 py-1 text-[9px] text-gray-400"
                                                    >
                                                      Discard
                                                    </button>
                                                    <button
                                                      type="button"
                                                      disabled={typeExpansionBusy}
                                                      onClick={() => applyTypeMappingCutover(selected.name)}
                                                      className="rounded bg-violet-500 px-2.5 py-1 text-[9px] font-semibold text-white hover:bg-violet-400 disabled:opacity-50"
                                                    >
                                                      {typeExpansionBusy ? 'Applying…' : 'Switch verified mapping'}
                                                    </button>
                                                  </div>
                                                </div>
                                              )}
                                            </div>
                                          )}
                                        </div>
                                      )}
                                    </div>
                                  </div>
                                )}
                              </div>
                            )}
                          </div>
                        )}
                        <div className="mt-4 border-t border-surface-border pt-3">
                          <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                            <div className="min-w-0">
                              <div className="text-[10px] font-semibold uppercase tracking-wider text-amber-300">
                                Dependency-aware removal
                              </div>
                              <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                                Opens IntelliJ Safe Delete and shows source, FlowUI, fetch-plan, JPQL, security, and
                                relationship usages before anything is removed. The database column is deliberately
                                retained until a separate data-audited migration is approved.
                              </p>
                            </div>
                            <button
                              type="button"
                              disabled={safeDeleteBusy}
                              onClick={() => handleNativeAttributeSafeDelete(selected.name)}
                              className="shrink-0 rounded border border-amber-500/40 bg-amber-500/10 px-3 py-1.5 text-xs font-medium text-amber-200 hover:bg-amber-500/20 disabled:opacity-50"
                            >
                              {safeDeleteBusy ? 'Resolving dependencies…' : 'Open Safe Delete preview'}
                            </button>
                          </div>
                        </div>
                      </div>
                    )}
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

          {(existingEntity || entity.className) && (
            <section className="mt-5 rounded-lg border border-surface-border bg-surface-light/50 p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div>
                  <h3 className="text-xs font-semibold uppercase tracking-wider text-gray-300">
                    Project-wide impact
                  </h3>
                  <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                    Views, fetch plans, repositories, services, security, REST, workflow, menu, and migration
                    artifacts connected to this entity. Safe structural changes must account for every listed consumer.
                  </p>
                </div>
                <span className="rounded bg-surface-lighter px-2 py-1 text-[10px] text-jmix-300">
                  {entityImpact.length} connected
                </span>
              </div>
              {entityImpact.length ? (
                <div className="mt-3 grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
                  {entityImpact.map(artifact => (
                    <div
                      key={artifact.id}
                      className="min-w-0 rounded border border-surface-border bg-surface px-2.5 py-2"
                      title={artifact.sourceLocator.relativePath}
                    >
                      <div className="truncate text-[10px] font-medium text-gray-200">{artifact.displayName}</div>
                      <div className="mt-1 flex items-center justify-between gap-2 text-[9px] text-gray-600">
                        <span className="truncate">{artifact.kind}</span>
                        <span className="shrink-0">{artifact.owner.moduleId}</span>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="mt-3 rounded border border-dashed border-surface-border p-3 text-center text-[10px] text-gray-600">
                  No indexed consumers are connected yet. New consumers appear here after project indexing.
                </div>
              )}
            </section>
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
          <>
            <Field label="Jmix Enum Class">
              <input
                value={attr.enumClass || ''}
                onChange={e => onChange({ enumClass: e.target.value || undefined })}
                placeholder="com.example.entity.Status"
                className="w-full"
              />
            </Field>
            <Field label="Stored Enum ID Type">
              <select
                value={attr.enumIdType}
                onChange={e => onChange({ enumIdType: e.target.value as AttributeModel['enumIdType'] })}
                className="w-full"
              >
                <option value="string">String</option>
                <option value="integer">Integer</option>
              </select>
            </Field>
          </>
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

        {attr.type === 'custom' && (
          <>
            <Field label="Java Type">
              <input
                value={attr.javaTypeName || ''}
                onChange={e => onChange({ javaTypeName: e.target.value || undefined })}
                placeholder="com.example.money.Money"
                className="w-full"
              />
            </Field>
            <Field label="SQL Column Definition">
              <input
                value={attr.sqlType || ''}
                onChange={e => onChange({ sqlType: e.target.value || undefined })}
                placeholder="numeric(19, 4)"
                className="w-full"
              />
            </Field>
          </>
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
        <Field label="Property Datatype">
          <input
            value={attr.propertyDatatype || ''}
            onChange={e => onChange({ propertyDatatype: e.target.value || undefined })}
            placeholder="customDatatypeId"
            className="w-full"
          />
        </Field>
        <Field label="Depends On Properties">
          <input
            value={attr.dependsOnProperties.join(', ')}
            onChange={e => onChange({
              dependsOnProperties: e.target.value.split(',').map(value => value.trim()).filter(Boolean),
            })}
            placeholder="firstName, lastName"
            className="w-full"
          />
        </Field>

        <div className="col-span-2 flex flex-wrap gap-x-4 gap-y-2 mt-1">
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.transientFlag} onChange={e => onChange({ transientFlag: e.target.checked })} />
            Transient
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.readOnly} onChange={e => onChange({ readOnly: e.target.checked })} />
            Read-only (no setter)
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.jmixProperty} onChange={e => onChange({ jmixProperty: e.target.checked })} />
            Explicit Jmix property
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.systemLevel} onChange={e => onChange({ systemLevel: e.target.checked })} />
            System level
          </label>
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.lob} onChange={e => onChange({ lob: e.target.checked })} />
            Large object
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

function DatabaseBrowsePanel({
  busy,
  browse,
  catalogName,
  schemaName,
  search,
  includeViews,
  mappedTableName,
  mappedTableSchema,
  mappedTableCatalog,
  onCatalogChange,
  onSchemaChange,
  onSearchChange,
  onIncludeViewsChange,
  onRefresh,
  onInspect,
  onClose,
}: {
  busy: boolean
  browse: DatabaseEntityTableBrowseResponse | null
  catalogName: string
  schemaName: string
  search: string
  includeViews: boolean
  mappedTableName: string
  mappedTableSchema?: string
  mappedTableCatalog?: string
  onCatalogChange: (value: string) => void
  onSchemaChange: (value: string) => void
  onSearchChange: (value: string) => void
  onIncludeViewsChange: (value: boolean) => void
  onRefresh: () => void
  onInspect: (table: DatabaseTableReference) => void
  onClose: () => void
}) {
  if (busy && !browse) {
    return (
      <section className="mb-4 min-w-0 rounded-xl border border-cyan-500/20 bg-cyan-500/5 p-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="h-3 w-3 shrink-0 animate-pulse rounded-full bg-cyan-300" />
          <div className="min-w-0">
            <div className="text-xs font-medium text-cyan-100">Browsing database metadata</div>
            <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
              Catalogs, schemas, tables, and views are read through the project’s JDBC driver.
              Credentials and connection URLs stay inside IntelliJ.
            </p>
          </div>
        </div>
      </section>
    )
  }
  if (!browse) return null
  if (!browse.accepted) {
    return (
      <section className="mb-4 min-w-0 rounded-xl border border-red-500/25 bg-red-500/5 p-4">
        <div className="flex min-w-0 items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="text-xs font-medium text-red-200">Database browser could not connect</div>
            <ul className="mt-2 space-y-1 text-[10px] leading-relaxed text-red-100/70">
              {browse.issues.map(issue => (
                <li className="break-words" key={`${issue.code}-${issue.message}`}>{issue.message}</li>
              ))}
            </ul>
          </div>
          <button type="button" onClick={onClose} className="shrink-0 text-xs text-gray-500 hover:text-gray-200">✕</button>
        </div>
      </section>
    )
  }
  const schemaOptions = browse.schemas.filter(schema =>
    !catalogName ||
    !schema.catalog ||
    schema.catalog.toLowerCase() === catalogName.toLowerCase(),
  )
  const mappedName = mappedTableName.split('.').pop()?.toLowerCase()
  const tableCount = browse.tables.filter(table => table.type.toUpperCase() !== 'VIEW').length
  const viewCount = browse.tables.length - tableCount
  return (
    <section className="mb-4 min-w-0 overflow-hidden rounded-xl border border-cyan-500/25 bg-gradient-to-br from-cyan-500/[0.08] to-surface">
      <div className="relative min-w-0 border-b border-cyan-500/15 p-3 pr-10 sm:p-4 sm:pr-12">
        <div className="min-w-0">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-cyan-100">Live database browser</span>
            <span className="max-w-full truncate rounded bg-surface-lighter px-2 py-0.5 text-[9px] text-gray-400">
              {browse.database?.name} {browse.database?.version}
            </span>
            <span className="rounded bg-cyan-500/10 px-2 py-0.5 text-[9px] text-cyan-200/70">
              {tableCount} tables · {viewCount} views
            </span>
          </div>
          <p className="mt-2 max-w-4xl text-[10px] leading-relaxed text-gray-500">
            Inspect any table safely. Attribute import unlocks only when the backend proves that the selected
            catalog, schema, store, table, and entity mapping are the same target.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close database browser"
          className="absolute right-3 top-3 rounded p-1 text-xs text-gray-500 hover:bg-white/5 hover:text-gray-200 sm:right-4 sm:top-4"
        >
          ✕
        </button>
      </div>

      <div className="grid min-w-0 gap-2 border-b border-cyan-500/15 bg-black/10 p-3 sm:grid-cols-2 sm:p-4 2xl:grid-cols-[minmax(8rem,0.75fr)_minmax(8rem,0.75fr)_minmax(11rem,1.5fr)_auto_auto]">
        <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
          Catalog
          <select
            value={catalogName}
            onChange={event => {
              onCatalogChange(event.target.value)
              onSchemaChange('')
            }}
            className="mt-1 w-full min-w-0"
          >
            <option value="">Connection default</option>
            {browse.catalogs.map(catalog => <option key={catalog} value={catalog}>{catalog}</option>)}
          </select>
        </label>
        <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
          Schema
          <select
            value={schemaName}
            onChange={event => onSchemaChange(event.target.value)}
            className="mt-1 w-full min-w-0"
          >
            <option value="">All visible schemas</option>
            {schemaOptions.map(schema => (
              <option key={`${schema.catalog ?? ''}:${schema.name}`} value={schema.name}>
                {schema.name}
              </option>
            ))}
          </select>
        </label>
        <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
          Find table or view
          <input
            value={search}
            onChange={event => onSearchChange(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter') onRefresh()
            }}
            placeholder="LOAN, EMPLOYEE, LEDGER…"
            className="mt-1 w-full min-w-0"
          />
        </label>
        <label className="flex min-w-0 items-center gap-2 self-end rounded border border-surface-border px-3 py-2 text-[10px] text-gray-400">
          <input
            type="checkbox"
            checked={includeViews}
            onChange={event => onIncludeViewsChange(event.target.checked)}
          />
          Include views
        </label>
        <button
          type="button"
          onClick={onRefresh}
          disabled={busy}
          className="self-end rounded bg-cyan-600 px-3 py-2 text-[10px] font-medium text-white hover:bg-cyan-500 disabled:opacity-50 sm:col-span-2 2xl:col-span-1"
        >
          {busy ? 'Refreshing…' : 'Apply filters'}
        </button>
      </div>

      {browse.issues.length > 0 && (
        <div className="border-b border-amber-500/15 bg-amber-500/5 px-3 py-2 sm:px-4">
          {browse.issues.map(issue => (
            <div key={`${issue.code}-${issue.message}`} className="break-words text-[10px] text-amber-200/75">
              {issue.message}
            </div>
          ))}
        </div>
      )}

      {browse.tables.length ? (
        <div className="grid min-w-0 gap-2 p-3 sm:grid-cols-2 sm:p-4 2xl:grid-cols-3">
          {browse.tables.map(table => {
            const nameMatches = table.name.toLowerCase() === mappedName
            const explicitSchemaMatches = !mappedTableSchema ||
              table.schema?.toLowerCase() === mappedTableSchema.toLowerCase()
            const explicitCatalogMatches = !mappedTableCatalog ||
              table.catalog?.toLowerCase() === mappedTableCatalog.toLowerCase()
            const mappedCandidate = nameMatches && explicitSchemaMatches && explicitCatalogMatches
            const qualifiedName = [table.catalog, table.schema, table.name].filter(Boolean).join('.')
            return (
              <article
                key={`${table.catalog ?? ''}:${table.schema ?? ''}:${table.name}:${table.type}`}
                className={`min-w-0 rounded-lg border p-3 ${
                  mappedCandidate
                    ? 'border-cyan-400/35 bg-cyan-500/[0.08]'
                    : 'border-surface-border bg-surface/75'
                }`}
              >
                <div className="flex min-w-0 items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                      <span className="max-w-full break-all font-mono text-[10px] text-gray-200" title={qualifiedName}>
                        {table.name}
                      </span>
                      <DatabaseStatus
                        label={table.type.toUpperCase() === 'VIEW' ? 'view' : 'table'}
                        tone={table.type.toUpperCase() === 'VIEW' ? 'warning' : 'neutral'}
                      />
                      {mappedCandidate && <DatabaseStatus label="mapping candidate" tone="cyan" />}
                    </div>
                    <div className="mt-1 break-all font-mono text-[9px] text-gray-600">
                      {[table.catalog, table.schema].filter(Boolean).join('.') || 'connection default'}
                    </div>
                  </div>
                </div>
                {table.remarks && (
                  <p className="mt-2 line-clamp-2 break-words text-[9px] leading-relaxed text-gray-500">
                    {table.remarks}
                  </p>
                )}
                <button
                  type="button"
                  onClick={() => onInspect(table)}
                  disabled={busy}
                  className="mt-3 w-full rounded border border-cyan-500/25 bg-cyan-500/10 px-2 py-1.5 text-[10px] text-cyan-200 hover:bg-cyan-500/20 disabled:opacity-50"
                >
                  {mappedCandidate ? 'Compare and import safely' : 'Inspect metadata'}
                </button>
              </article>
            )
          })}
        </div>
      ) : (
        <div className="p-8 text-center">
          <div className="text-xs text-gray-400">No matching tables or views</div>
          <p className="mt-1 text-[10px] text-gray-600">Clear or narrow the catalog, schema, and search filters.</p>
        </div>
      )}

      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 border-t border-cyan-500/15 bg-black/10 px-3 py-2 text-[9px] text-gray-600 sm:px-4">
        <span className="min-w-0 break-all">
          Active catalog {browse.activeCatalog || 'driver default'} · URL fingerprint {browse.database?.urlFingerprint}
        </span>
        <span>{browse.schemas.length} schemas discovered</span>
      </div>
    </section>
  )
}

function DatabaseInspectionPanel({
  busy,
  inspection,
  mergeAllowed,
  drafts,
  onDraftChange,
  onClose,
  onStage,
}: {
  busy: boolean
  inspection: DatabaseEntityTableInspectionResponse | null
  mergeAllowed: boolean
  drafts: Record<string, DatabaseColumnDraft>
  onDraftChange: (columnName: string, change: Partial<DatabaseColumnDraft>) => void
  onClose: () => void
  onStage: () => void
}) {
  if (busy) {
    return (
      <section className="mb-4 rounded-xl border border-cyan-500/20 bg-cyan-500/5 p-4">
        <div className="flex items-center gap-3">
          <span className="h-3 w-3 animate-pulse rounded-full bg-cyan-300" />
          <div>
            <div className="text-xs font-medium text-cyan-100">Inspecting the mapped database table</div>
            <p className="mt-1 text-[10px] text-gray-500">
              The JDBC connection stays inside IntelliJ. Passwords and connection URLs never enter this UI.
            </p>
          </div>
        </div>
      </section>
    )
  }
  if (!inspection) return null
  if (!inspection.accepted || !inspection.table) {
    return (
      <section className="mb-4 rounded-xl border border-red-500/25 bg-red-500/5 p-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <div className="text-xs font-medium text-red-200">Database inspection could not continue</div>
            <ul className="mt-2 space-y-1 text-[10px] text-red-100/70">
              {inspection.issues.map(issue => <li key={`${issue.code}-${issue.message}`}>{issue.message}</li>)}
            </ul>
          </div>
          <button type="button" onClick={onClose} className="text-xs text-gray-500 hover:text-gray-200">✕</button>
        </div>
      </section>
    )
  }
  const table = inspection.table
  const selectedCount = table.columns.filter(column => drafts[column.name]?.selected).length
  const missingCount = table.columns.filter(column => !column.alreadyMapped && !column.primaryKey).length
  return (
    <section className="mb-4 overflow-hidden rounded-xl border border-cyan-500/25 bg-gradient-to-br from-cyan-500/[0.08] to-surface">
      <div className="relative min-w-0 border-b border-cyan-500/15 p-3 pr-10 sm:p-4 sm:pr-12">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-cyan-100">
              {mergeAllowed ? 'Database → existing entity' : 'Read-only database inspection'}
            </span>
            <span className="rounded bg-cyan-500/15 px-2 py-0.5 font-mono text-[9px] text-cyan-200">
              {qualifiedDatabaseTable(inspection)}
            </span>
            <span className="rounded bg-surface-lighter px-2 py-0.5 text-[9px] text-gray-400">
              {inspection.database?.name} {inspection.database?.version}
            </span>
          </div>
          <p className="mt-2 max-w-4xl text-[10px] leading-relaxed text-gray-500">
            Review the live schema snapshot and stage only missing columns. Nothing is written yet: the normal safe
            update preview still verifies the source fingerprint, preserves handwritten Java/Kotlin, and shows every
            source and Liquibase edit before atomic apply.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close database inspection"
          className="absolute right-3 top-3 rounded p-1 text-xs text-gray-500 hover:bg-white/5 hover:text-gray-200 sm:right-4 sm:top-4"
        >
          ✕
        </button>
      </div>

      {!mergeAllowed && (
        <div className="border-b border-amber-500/20 bg-amber-500/[0.07] px-3 py-2 sm:px-4">
          <div className="text-[10px] leading-relaxed text-amber-100/75">
            Import is locked. The backend did not resolve this catalog/schema/table as the selected entity’s
            exact mapping. You can inspect its types, keys, relationships, and indexes without changing source.
          </div>
        </div>
      )}

      <div className="grid gap-px bg-surface-border/60 sm:grid-cols-2 lg:grid-cols-4">
        {[
          ['Columns', table.columns.length],
          ['Missing', missingCount],
          ['Foreign keys', table.foreignKeys.length],
          ['Indexes', table.indexes.length],
        ].map(([label, value]) => (
          <div key={label} className="bg-surface/90 px-3 py-2">
            <div className="text-[9px] uppercase tracking-wider text-gray-600">{label}</div>
            <div className="mt-0.5 text-sm font-semibold text-gray-200">{value}</div>
          </div>
        ))}
      </div>

      {inspection.issues.length > 0 && (
        <div className="border-b border-amber-500/15 bg-amber-500/5 px-3 py-2 sm:px-4">
          {inspection.issues.map(issue => (
            <div key={`${issue.code}-${issue.message}`} className="text-[10px] text-amber-200/75">
              {issue.message}
            </div>
          ))}
        </div>
      )}

      <div className="grid gap-2 p-3 sm:grid-cols-2 sm:p-4 2xl:grid-cols-3">
        {table.columns.map(column => {
          const draft = drafts[column.name]
          const locked = column.alreadyMapped || column.primaryKey || column.generated
          const unsupported = Boolean(column.suggestion.unsupportedReason)
          const selectable = mergeAllowed && !locked
          const availableTypes = ATTRIBUTE_TYPES.filter(type =>
            !['composition', 'embedded', 'enum', 'fileRef'].includes(type) &&
            (type !== 'association' || Boolean(column.suggestion.relatedEntity)),
          )
          return (
            <article
              key={column.name}
              className={`min-w-0 rounded-lg border p-3 ${
                draft?.selected
                  ? 'border-cyan-400/35 bg-cyan-500/[0.08]'
                  : 'border-surface-border bg-surface/75'
              }`}
            >
              <div className="flex items-start gap-2">
                <input
                  type="checkbox"
                  aria-label={`Stage ${column.name}`}
                  checked={draft?.selected ?? false}
                  disabled={!selectable}
                  onChange={event => onDraftChange(column.name, { selected: event.target.checked })}
                  className="mt-0.5"
                />
                <div className="min-w-0 flex-1">
                  <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                    <span className="truncate font-mono text-[10px] text-gray-200" title={column.name}>
                      {column.name}
                    </span>
                    {column.alreadyMapped && <DatabaseStatus label="mapped" tone="neutral" />}
                    {column.primaryKey && <DatabaseStatus label="primary key" tone="neutral" />}
                    {column.generated && <DatabaseStatus label="generated" tone="warning" />}
                    {column.suggestion.relatedEntity && <DatabaseStatus label="relationship" tone="cyan" />}
                    {unsupported && <DatabaseStatus label="needs type" tone="warning" />}
                  </div>
                  <div className="mt-1 truncate font-mono text-[9px] text-gray-600">
                    {column.typeName}
                    {column.size ? `(${column.size}${column.scale != null ? `,${column.scale}` : ''})` : ''}
                    {column.nullable ? ' · nullable' : ' · required'}
                  </div>
                </div>
              </div>
              {mergeAllowed && !locked && draft && (
                <div className="mt-3 grid min-w-0 gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(7rem,0.8fr)]">
                  <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
                    Property
                    <input
                      value={draft.attributeName}
                      onChange={event => onDraftChange(column.name, { attributeName: event.target.value })}
                      className="mt-1 w-full min-w-0"
                    />
                  </label>
                  <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
                    Jmix type
                    <select
                      value={draft.attributeType}
                      onChange={event => onDraftChange(column.name, {
                        attributeType: event.target.value as AttributeType,
                      })}
                      className="mt-1 w-full min-w-0"
                    >
                      {availableTypes.map(type => <option key={type} value={type}>{type}</option>)}
                    </select>
                  </label>
                </div>
              )}
              {column.suggestion.relatedEntity && (
                <div className="mt-2 truncate text-[9px] text-cyan-200/60" title={column.suggestion.relatedEntity}>
                  → {column.suggestion.relatedEntity}
                </div>
              )}
              {unsupported && mergeAllowed && !locked && (
                <p className="mt-2 text-[9px] leading-relaxed text-amber-200/60">
                  {column.suggestion.unsupportedReason}
                </p>
              )}
            </article>
          )
        })}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-cyan-500/15 bg-black/10 px-3 py-3 sm:px-4">
        <div className="min-w-0 text-[9px] leading-relaxed text-gray-600">
          Snapshot {inspection.snapshotDigest?.slice(0, 12)} · URL fingerprint {inspection.database?.urlFingerprint}
          {table.dependencyTables.length
            ? ` · dependencies ${table.dependencyTables.join(', ')}`
            : ''}
        </div>
        <button
          type="button"
          disabled={!mergeAllowed || selectedCount === 0}
          onClick={onStage}
          className="rounded bg-cyan-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-cyan-500 disabled:opacity-50"
        >
          {mergeAllowed
            ? `Stage ${selectedCount || ''} selected ${selectedCount === 1 ? 'attribute' : 'attributes'}`
            : 'Import locked for this table'}
        </button>
      </div>
    </section>
  )
}

function DatabaseStatus({
  label,
  tone,
}: {
  label: string
  tone: 'neutral' | 'warning' | 'cyan'
}) {
  const color = tone === 'warning'
    ? 'bg-amber-500/10 text-amber-200/70'
    : tone === 'cyan'
      ? 'bg-cyan-500/10 text-cyan-200/70'
      : 'bg-surface-lighter text-gray-500'
  return <span className={`rounded px-1.5 py-0.5 text-[8px] ${color}`}>{label}</span>
}

function databaseColumnCanBeStaged(column: DatabaseColumnSnapshot): boolean {
  return !column.alreadyMapped &&
    !column.primaryKey &&
    !column.generated &&
    !column.suggestion.unsupportedReason
}

function qualifiedDatabaseTable(inspection: DatabaseEntityTableInspectionResponse): string {
  const table = inspection.table
  if (!table) return 'unresolved table'
  return [table.catalog, table.schema, table.name].filter(Boolean).join('.')
}

function databaseColumnToAttribute(
  column: DatabaseColumnSnapshot,
  draft: DatabaseColumnDraft,
  workspace: SchemaWorkspaceResponse | null,
  storeName: string,
): AttributeModel | null {
  const suggestion = column.suggestion
  const common: AttributeModel = {
    name: draft.attributeName.trim(),
    type: draft.attributeType,
    columnName: column.name,
    mandatory: !column.nullable,
    unique: false,
    length: draft.attributeType === 'string' || draft.attributeType === 'character'
      ? suggestion.length
      : undefined,
    precision: draft.attributeType === 'bigDecimal' ? suggestion.precision : undefined,
    scale: draft.attributeType === 'bigDecimal' ? suggestion.scale : undefined,
    comment: column.remarks,
    transientFlag: false,
    systemLevel: false,
    readOnly: false,
    jmixProperty: false,
    dependsOnProperties: [],
    lob: /(?:BLOB|CLOB|LONGVARBINARY|LONGVARCHAR)/i.test(column.typeName),
    enumIdType: 'string',
    validations: [],
    annotations: [],
    inBaseFetchPlan: true,
  }
  if (draft.attributeType !== 'association') return common
  const relatedName = suggestion.relatedEntity
  const related = workspace?.entities.find(candidate =>
    candidate.qualifiedName === relatedName && candidate.storeName === storeName,
  )
  if (!related || !relatedName) return null
  return {
    ...common,
    type: 'association',
    association: {
      associationType: 'manyToOne',
      relatedEntity: relatedName,
      relatedTableName: suggestion.foreignKeyTable,
      relatedIdColumnName: suggestion.referencedColumnName || related.idColumnName,
      relatedIdType: related.idType,
      joinColumnName: suggestion.joinColumnName || column.name,
      cascade: [],
      fetch: 'lazy',
      collectionType: 'list',
      crossDataStore: false,
      orphanRemoval: false,
    },
  }
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
    sourceLanguage: snapshot.sourceLocator.relativePath.endsWith('.kt') ? 'kotlin' : 'java',
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
        readOnly: false,
        jmixProperty: false,
        dependsOnProperties: [],
        lob: false,
        enumIdType: 'string',
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
    systemLevel: false,
    annotatedPropertiesOnly: false,
  }
}

function schemaAttributeType(javaType: string, association: boolean): AttributeType {
  if (association) return 'association'
  const simple = javaType.replace(/\??$/, '').split('.').pop()?.replace(/<.*>/, '') ?? javaType
  const mapping: Record<string, AttributeType> = {
    String: 'string',
    Character: 'character',
    char: 'character',
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
    OffsetTime: 'offsetTime',
    OffsetDateTime: 'offsetDateTime',
    URI: 'uri',
    FileRef: 'fileRef',
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
    character: 'Character',
    integer: 'Integer',
    long: 'Long',
    double: 'Double',
    bigDecimal: 'BigDecimal',
    boolean: 'Boolean',
    date: 'Date',
    localDate: 'LocalDate',
    localDateTime: 'LocalDateTime',
    localTime: 'LocalTime',
    offsetTime: 'OffsetTime',
    offsetDateTime: 'OffsetDateTime',
    sqlDate: 'java.sql.Date',
    sqlTime: 'java.sql.Time',
    uuid: 'UUID',
    uri: 'URI',
    byteArray: 'byte[]',
    fileRef: 'FileRef',
    enum: 'String',
    custom: 'Object',
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
  const idType = entity.id.type === 'uuid'
    ? 'UUID'
    : entity.id.type === 'long'
      ? 'Long'
      : entity.id.type === 'integer'
        ? 'Integer'
        : entity.id.type === 'embedded'
          ? entity.id.embeddedIdClass?.split('.').pop() || 'Object'
          : 'String'
  lines.push(`    protected ${idType} id;`)
  lines.push('')
  entity.attributes.forEach((attr: any) => {
    if (attr.mandatory) lines.push('    @NotNull')
    lines.push(`    @Column(name = "${(attr.columnName || attr.name.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase())}")`)
    const type = attr.type === 'enum'
      ? attr.enumClass?.split('.').pop() || 'Object'
      : attr.type === 'embedded'
        ? attr.embeddedClass?.split('.').pop() || 'Object'
        : attr.type === 'custom'
          ? attr.javaTypeName?.split('.').pop() || 'Object'
          : previewJavaType(attr.type)
    lines.push(`    protected ${type} ${attr.name};`)
    lines.push('')
  })
  lines.push('    // getters and setters...')
  lines.push('}')
  return lines.join('\n')
}
