import { useEffect, useMemo, useState } from 'react'
import { ChevronDown, ChevronUp, Copy, LockKeyhole, Trash2 } from 'lucide-react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type {
  AttributeModel,
  AttributeType,
  TraitType,
  LifecycleCallback,
  IdType,
  IdGeneration,
  AssociationConfig,
  AssociationType,
  CascadeType,
  FetchType,
  EntityModel,
  SchemaEntityAttributeSnapshot,
  SchemaEntitySnapshot,
  SchemaRepositorySnapshot,
  ValidationType,
  SchemaWorkspaceResponse,
  WorkspaceChangePreviewResponse,
  ApplicationGraphResponse,
  DatabaseEntityTableInspectionResponse,
  DatabaseEntityTableBrowseResponse,
  DatabaseTableReference,
  DatabaseColumnSnapshot,
  DatabaseEntityImportRequest,
  DatabaseEntityImportPlanResponse,
  DatabaseEntityImportProfileWorkspaceResponse,
  EntityAttributePropagationChangeRequest,
  EntityAttributePropagationInspectionRequest,
  EntityAttributePropagationInspectionResponse,
  EntityAttributeTypeSchemaImpact,
  EntityAttributeTypeMigrationRequest,
  EntityAttributeTypeExpansionPreviewResponse,
  EntityAttributeTypeExpansionVerificationResponse,
  EntityAttributeTypeMappingCutoverRequest,
  GraphSourceLocator,
  GraphArtifact,
} from '../../types'
import ResponsivePaneSwitcher from '../shared/ResponsivePaneSwitcher'
import {
  EntitySourceContractEvidence,
  InheritedAttributeEvidence,
} from './EntitySourceEvidence'
import { existingEntityModel } from './entityModelAdapter'
import EntityEventListenerPanel from './EntityEventListenerPanel'
import EntityInheritancePanel from './EntityInheritancePanel'
import EmbeddedOverrideEditor from './EmbeddedOverrideEditor'
import RepositoryDesignerPanel from './RepositoryDesignerPanel'

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

const TRAIT_ATTRIBUTE_NAMES: Record<TraitType, string[]> = {
  standardEntity: ['id', 'version', 'createdBy', 'createdDate', 'lastModifiedBy', 'lastModifiedDate'],
  uuid: ['uuid'],
  softDelete: ['deletedDate', 'deletedBy'],
  hasTenantId: ['sysTenantId'],
  hasVersion: ['version'],
  createdBy: ['createdBy'],
  createdDate: ['createdDate'],
  updatedBy: ['lastModifiedBy'],
  updatedDate: ['lastModifiedDate'],
  auditable: ['createdBy', 'createdDate', 'lastModifiedBy', 'lastModifiedDate'],
}

const VALIDATIONS: ValidationType[] = [
  'notNull', 'notEmpty', 'notBlank', 'size', 'min', 'max',
  'decimalMin', 'decimalMax', 'pattern', 'email', 'past', 'future',
  'positive', 'negative', 'digits', 'assertTrue',
]

const LIFECYCLE_CALLBACKS: { value: LifecycleCallback; label: string }[] = [
  { value: 'prePersist', label: 'Before persist' },
  { value: 'postPersist', label: 'After persist' },
  { value: 'preUpdate', label: 'Before update' },
  { value: 'postUpdate', label: 'After update' },
  { value: 'preRemove', label: 'Before remove' },
  { value: 'postRemove', label: 'After remove' },
  { value: 'postLoad', label: 'After load' },
]

interface DatabaseColumnDraft {
  selected: boolean
  attributeName: string
  attributeType: AttributeType
}

interface CoordinatedRenameSession {
  attributeName: string
  newName: string
  sourcePhysicalColumn: string
  requestedPhysicalColumn: string
  previewPlanDigest?: string
  stage: 'MAPPING_PREVIEW' | 'MAPPING_APPLIED' | 'NATIVE_PREVIEW'
}

interface EntityDesignerProps {
  editorSurface?: boolean
  sourceLocator?: GraphSourceLocator
}

export default function EntityDesigner({
  editorSurface = false,
  sourceLocator,
}: EntityDesignerProps = {}) {
  const {
    entity,
    projectConfig,
    setEntity,
    addAttribute,
    duplicateAttribute,
    moveAttribute,
    updateAttribute,
    removeAttribute,
    resetEntity,
    addToast,
    isGenerating,
    setIsGenerating,
    openCrudDesigner,
    openFlowUiDesigner,
  } = useStore()
  const [selectedAttr, setSelectedAttr] = useState<number | null>(null)
  const [showPreview, setShowPreview] = useState(false)
  const [activePane, setActivePane] = useState<'config' | 'attributes' | 'preview'>('attributes')
  const [schemaWorkspace, setSchemaWorkspace] = useState<SchemaWorkspaceResponse | null>(null)
  const [schemaLoading, setSchemaLoading] = useState(true)
  const [nativeSourceIssue, setNativeSourceIssue] = useState<string | null>(null)
  const [showTraitAttributes, setShowTraitAttributes] = useState(false)
  const [generationPreview, setGenerationPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [existingEntity, setExistingEntity] = useState<SchemaEntitySnapshot | null>(null)
  const [entityRepositories, setEntityRepositories] = useState<SchemaRepositorySnapshot[]>([])
  const [selectedRepositoryArtifactId, setSelectedRepositoryArtifactId] = useState('')
  const [repositoryPreview, setRepositoryPreview] =
    useState<WorkspaceChangePreviewResponse | null>(null)
  const [repositoryBusy, setRepositoryBusy] = useState(false)
  const [applicationGraph, setApplicationGraph] = useState<ApplicationGraphResponse | null>(null)
  const [renameDraft, setRenameDraft] = useState('')
  const [renameBusy, setRenameBusy] = useState(false)
  const [renameLaunched, setRenameLaunched] = useState(false)
  const [coordinatedRename, setCoordinatedRename] =
    useState<CoordinatedRenameSession | null>(null)
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
  const [databaseImportSelection, setDatabaseImportSelection] =
    useState<DatabaseTableReference[]>([])
  const [databaseIdentifierOverrides, setDatabaseIdentifierOverrides] =
    useState<Record<string, string[]>>({})
  const [databaseImportRequest, setDatabaseImportRequest] =
    useState<DatabaseEntityImportRequest | null>(null)
  const [databaseImportPlan, setDatabaseImportPlan] =
    useState<DatabaseEntityImportPlanResponse | null>(null)
  const [databaseImportPreview, setDatabaseImportPreview] =
    useState<WorkspaceChangePreviewResponse | null>(null)
  const [databaseImportBusy, setDatabaseImportBusy] = useState(false)
  const [databaseProfileWorkspace, setDatabaseProfileWorkspace] =
    useState<DatabaseEntityImportProfileWorkspaceResponse | null>(null)
  const [databaseProfileEnabled, setDatabaseProfileEnabled] = useState(true)
  const [databaseProfileId, setDatabaseProfileId] = useState('')
  const [databaseProfileLabel, setDatabaseProfileLabel] = useState('')
  const [selectedDatabaseProfileId, setSelectedDatabaseProfileId] = useState('')
  const [databaseInspectionMergeAllowed, setDatabaseInspectionMergeAllowed] = useState(true)
  const [propagationInspection, setPropagationInspection] =
    useState<EntityAttributePropagationInspectionResponse | null>(null)
  const [propagationRequest, setPropagationRequest] =
    useState<EntityAttributePropagationInspectionRequest | null>(null)
  const [propagationSelection, setPropagationSelection] = useState<string[]>([])
  const [propagationPreview, setPropagationPreview] =
    useState<WorkspaceChangePreviewResponse | null>(null)
  const [propagationBusy, setPropagationBusy] = useState(false)

  useEffect(() => {
    let active = true
    setSchemaLoading(true)
    bridge.getSchemaWorkspace(Boolean(editorSurface && sourceLocator)).then((workspace) => {
      if (!active) return
      setSchemaWorkspace(workspace)
      if (editorSurface && sourceLocator) {
        const matching = workspace.entities.filter(candidate =>
          candidate.sourceLocator.relativePath === sourceLocator.relativePath,
        )
        if (matching.length !== 1) {
          setNativeSourceIssue(
            matching.length === 0
              ? 'The current Java/Kotlin document is not a parseable indexed Jmix entity. Fix source errors or use the source editor.'
              : 'The current source resolves to multiple entity models. Resolve module/source ownership before visual editing.',
          )
          return
        }
        const selected = matching[0]
        if (selected.sourceLocator.revisionFingerprint !== sourceLocator.revisionFingerprint) {
          setNativeSourceIssue(
            'The Entity Designer index has not reached the current unsaved document revision. Reselect Design after indexing completes.',
          )
          return
        }
        const store = workspace.stores.find(candidate =>
          candidate.moduleId === selected.moduleId && candidate.name === selected.storeName,
        )
        const repositories = workspace.repositories.filter(repository =>
          repository.entityQualifiedName === selected.qualifiedName)
        const selectedRepository = repositories[0]
        setNativeSourceIssue(null)
        setExistingEntity(selected)
        setEntityRepositories(repositories)
        setSelectedRepositoryArtifactId(selectedRepository?.artifactId ?? '')
        setRepositoryPreview(null)
        setGenerationPreview(null)
        setSelectedAttr(null)
        setShowTraitAttributes(false)
        setRenameDraft('')
        setRenameLaunched(false)
        setCoordinatedRename(null)
        setEntity(existingEntityModel(selected, store?.id, selectedRepository?.config ?? {
          enabled: false,
          applyConstraints: true,
          useNamedParameters: true,
          methods: [],
        }))
        setActivePane('attributes')
        return
      }
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
  }, [
    editorSurface,
    sourceLocator?.relativePath,
    sourceLocator?.revisionFingerprint,
  ])

  useEffect(() => {
    let active = true
    bridge.getDatabaseEntityImportProfiles().then((workspace) => {
      if (active) setDatabaseProfileWorkspace(workspace)
    }).catch(() => {
      if (active) setDatabaseProfileWorkspace({ profiles: [], issues: [] })
    })
    return () => {
      active = false
    }
  }, [])

  function synchronizeExistingEntity(
    snapshot: SchemaEntitySnapshot,
    workspace: SchemaWorkspaceResponse,
  ) {
    const store = workspace.stores.find(candidate =>
      candidate.moduleId === snapshot.moduleId && candidate.name === snapshot.storeName)
    const repositories = workspace.repositories.filter(repository =>
      repository.entityQualifiedName === snapshot.qualifiedName)
    const repository = repositories.find(candidate =>
      candidate.artifactId === selectedRepositoryArtifactId) ?? repositories[0]
    setExistingEntity(snapshot)
    setEntityRepositories(repositories)
    setSelectedRepositoryArtifactId(repository?.artifactId ?? '')
    setRepositoryPreview(null)
    setEntity(existingEntityModel(snapshot, store?.id, repository?.config ?? {
      enabled: false,
      applyConstraints: true,
      useNamedParameters: true,
      methods: [],
    }))
  }

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

  const selectAttribute = (index: number, attributeName: string) => {
    const next = selectedAttr === index ? null : index
    setSelectedAttr(next)
    setRenameDraft(
      next === null
        ? ''
        : coordinatedRename?.attributeName === attributeName
          ? coordinatedRename.newName
          : attributeName,
    )
    setRenameLaunched(
      next !== null &&
      coordinatedRename?.attributeName === attributeName &&
      coordinatedRename.stage === 'NATIVE_PREVIEW',
    )
  }

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
  const traitManagedAttributeNames = useMemo(
    () => new Set(
      (existingEntity?.traits ?? []).flatMap(trait => TRAIT_ATTRIBUTE_NAMES[trait]),
    ),
    [existingEntity],
  )
  const hiddenTraitAttributeCount = entity.attributes.filter(
    attribute => traitManagedAttributeNames.has(attribute.name),
  ).length
  const visibleAttributeEntries = entity.attributes
    .map((attribute, index) => ({ attribute, index }))
    .filter(({ attribute }) =>
      showTraitAttributes || !traitManagedAttributeNames.has(attribute.name))
  const duplicateDraftAttribute = (index: number, locked: boolean) => {
    const inserted = duplicateAttribute(index)
    if (inserted === null) return
    const selected = locked ? entity.attributes.length : inserted
    if (locked && inserted !== selected) {
      moveAttribute(inserted, selected)
    }
    setSelectedAttr(selected)
    setGenerationPreview(null)
    addToast(
      locked
        ? 'Copied as a new source-safe draft. Physical mappings were regenerated and constraint expansion was cleared.'
        : 'Attribute copied. Review its generated mapping before preview.',
      'info',
    )
  }

  const reorderDraftAttribute = (index: number, target: number) => {
    if (!moveAttribute(index, target)) return
    setSelectedAttr(target)
    setGenerationPreview(null)
  }
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
  const entityEventListeners = useMemo(
    () => entityImpact.filter(artifact => artifact.kind === 'EVENT_LISTENER'),
    [entityImpact],
  )
  const existingRepository = useMemo(
    () => entityRepositories.find(repository =>
      repository.artifactId === selectedRepositoryArtifactId) ?? null,
    [entityRepositories, selectedRepositoryArtifactId],
  )

  const existingViewContractHasDraftChanges = useMemo(() => {
    if (!existingEntity) return false
    const store = schemaWorkspace?.stores.find(candidate =>
      candidate.moduleId === existingEntity.moduleId &&
      candidate.name === existingEntity.storeName,
    )
    const baseline = existingEntityModel(existingEntity, store?.id)
    return baseline.className !== entity.className ||
      baseline.packageName !== entity.packageName ||
      baseline.entityName !== entity.entityName ||
      JSON.stringify(baseline.attributes ?? []) !== JSON.stringify(entity.attributes)
  }, [entity, existingEntity, schemaWorkspace])

  const openEntityViewWorkflow = async () => {
    if (!entity.className.trim()) {
      addToast('Define or select an entity before creating views.', 'error')
      return
    }
    if (!existingEntity) {
      openCrudDesigner()
      return
    }
    if (existingViewContractHasDraftChanges || generationPreview) {
      addToast(
        'Apply or discard the pending entity-source changes before creating bound views.',
        'error',
      )
      return
    }
    if (!editorSurface) {
      openCrudDesigner(existingEntity.sourceLocator)
      return
    }
    const response = await bridge.openWorkbenchSurface(
      'CRUD_DESIGNER',
      existingEntity.sourceLocator,
    )
    addToast(
      response.message,
      response.success ? 'success' : 'error',
    )
  }

  const openImpactSource = async (artifact: GraphArtifact) => {
    const response = await bridge.navigateToSource(artifact.sourceLocator)
    if (!response.success) addToast(response.message, 'error')
  }

  const refreshAndOpenCreatedListener = async (createdPath: string) => {
    const graph = await bridge.getApplicationGraph(true)
    setApplicationGraph(graph)
    const created = graph.artifacts.find(artifact =>
      artifact.kind === 'EVENT_LISTENER' &&
      artifact.sourceLocator.relativePath === createdPath,
    )
    if (created) {
      await openImpactSource(created)
    }
  }

  const designImpactView = async (artifact: GraphArtifact) => {
    if (!editorSurface) {
      openFlowUiDesigner(artifact.sourceLocator)
      return
    }
    const response = await bridge.openWorkbenchSurface(
      'FLOW_UI_EDITOR',
      artifact.sourceLocator,
    )
    addToast(
      response.message,
      response.success ? 'success' : 'error',
    )
  }

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
      if (response.success) {
        setRenameLaunched(true)
        setCoordinatedRename(current =>
          current && current.attributeName === attributeName
            ? { ...current, stage: 'NATIVE_PREVIEW' }
            : current,
        )
      }
    } catch (error: any) {
      addToast(`Native rename failed: ${error.message}`, 'error')
    } finally {
      setRenameBusy(false)
    }
  }

  const previewCoordinatedAttributeRename = async (
    attribute: AttributeModel,
    source: SchemaEntityAttributeSnapshot,
  ) => {
    if (!existingEntity) return
    const newName = renameDraft.trim()
    if (!newName || newName === attribute.name) {
      addToast('Enter a different property name', 'error')
      return
    }
    const requestedPhysicalColumn = (
      attribute.association?.joinColumnName ||
      attribute.columnName ||
      source.columnName
    ).trim()
    const sourcePhysicalColumn = source.columnName
    if (requestedPhysicalColumn === sourcePhysicalColumn) {
      await handleNativeAttributeRename(attribute.name)
      return
    }
    setRenameBusy(true)
    setGenerationPreview(null)
    try {
      const renameRequest = {
        sourceLocator: existingEntity.sourceLocator,
        entityClassName: existingEntity.className,
        attributeName: attribute.name,
        newName,
      }
      const nativePreflight = await bridge.inspectEntityAttributeRename(renameRequest)
      if (!nativePreflight.success) {
        addToast(nativePreflight.message, 'error')
        return
      }
      const preview = await bridge.previewExistingEntityAttributeAdditions({
        sourceLocator: existingEntity.sourceLocator,
        entity,
      })
      if (!preview.accepted || !preview.planDigest) {
        addToast(
          `Coordinated rename rejected: ${preview.issues.map(issue => issue.message).join(', ')}`,
          'error',
        )
        return
      }
      setGenerationPreview(preview)
      setCoordinatedRename({
        attributeName: attribute.name,
        newName,
        sourcePhysicalColumn,
        requestedPhysicalColumn,
        previewPlanDigest: preview.planDigest,
        stage: 'MAPPING_PREVIEW',
      })
      addToast(
        `Rename preflight passed. Review the physical ${sourcePhysicalColumn} → ` +
          `${requestedPhysicalColumn} change, then apply it before opening IntelliJ usage preview.`,
        'info',
      )
    } catch (error: any) {
      addToast(`Coordinated rename failed: ${error.message}`, 'error')
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
        synchronizeExistingEntity(updated, refreshed)
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
    if (!storeId) {
      addToast('Select a target module and data store first', 'error')
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

  const buildDatabaseImportRequest = (
    selection = databaseImportSelection,
    identifierOverrides = databaseIdentifierOverrides,
  ): DatabaseEntityImportRequest | null => {
    const moduleId = entity.generationTarget?.moduleId
    const storeId = entity.generationTarget?.storeId
    if (!moduleId || !storeId || !entity.packageName.trim() || !selection.length) return null
    return {
      moduleId,
      storeId,
      packageName: entity.packageName.trim(),
      sourceLanguage: entity.sourceLanguage,
      selectedTables: selection,
      includeDependencies: true,
      identifierOverrides,
      ...(databaseProfileEnabled && databaseProfileId && databaseProfileLabel
        ? {
            profileId: databaseProfileId,
            profileLabel: databaseProfileLabel,
          }
        : {}),
    }
  }

  const executeDatabaseEntityImportPlan = async (request: DatabaseEntityImportRequest) => {
    setDatabaseImportBusy(true)
    setDatabaseImportPlan(null)
    setDatabaseImportPreview(null)
    try {
      const response = await bridge.planDatabaseEntityImport(request)
      setDatabaseImportRequest(request)
      setDatabaseImportPlan(response)
      if (!response.accepted) {
        addToast(response.issues[0]?.message ?? 'Database entity planning was rejected', 'error')
      } else if (response.ready) {
        const generated = response.tables.filter(table => table.generated).length
        addToast(
          `Planned ${generated} database-backed entity type${generated === 1 ? '' : 's'} with dependency closure`,
          'success',
        )
      } else {
        addToast('The import plan needs the highlighted identifier or mapping decisions', 'info')
      }
    } catch (error: any) {
      addToast(`Database entity planning failed: ${error.message}`, 'error')
    } finally {
      setDatabaseImportBusy(false)
    }
  }

  const planDatabaseEntityImport = async (
    identifierOverrides = databaseIdentifierOverrides,
  ) => {
    const request = buildDatabaseImportRequest(databaseImportSelection, identifierOverrides)
    if (!request) {
      addToast('Select at least one table plus a target module, data store, and package', 'error')
      return
    }
    await executeDatabaseEntityImportPlan(request)
  }

  const loadDatabaseImportProfile = async () => {
    const document = databaseProfileWorkspace?.profiles.find(
      candidate => candidate.profile.id === selectedDatabaseProfileId,
    )
    if (!document) {
      addToast('Select a saved database mapping first', 'error')
      return
    }
    const request = document.profile.request
    const store = schemaWorkspace?.stores.find(candidate => candidate.id === request.storeId)
    if (!store || store.moduleId !== request.moduleId) {
      addToast('The saved mapping target module or data store is not indexed in this project', 'error')
      return
    }
    setExistingEntity(null)
    setEntity({
      packageName: request.packageName,
      sourceLanguage: request.sourceLanguage,
      dataStore: store.name,
      generationTarget: {
        moduleId: request.moduleId,
        storeId: request.storeId,
      },
    })
    setDatabaseImportSelection(request.selectedTables)
    setDatabaseIdentifierOverrides(request.identifierOverrides ?? {})
    setDatabaseProfileEnabled(true)
    setDatabaseProfileId(document.profile.id)
    setDatabaseProfileLabel(document.profile.label)
    setDatabaseImportPreview(null)
    await executeDatabaseEntityImportPlan(request)
  }

  const previewDatabaseEntityImport = async () => {
    const snapshotDigest = databaseImportPlan?.snapshotDigest
    if (!databaseImportRequest || !snapshotDigest || !databaseImportPlan.ready) return
    setDatabaseImportBusy(true)
    try {
      const response = await bridge.previewDatabaseEntityImport(
        databaseImportRequest,
        snapshotDigest,
      )
      setDatabaseImportPreview(response)
      if (!response.accepted) {
        addToast(response.issues[0]?.message ?? 'Atomic import preview was rejected', 'error')
      }
    } catch (error: any) {
      addToast(`Database entity preview failed: ${error.message}`, 'error')
    } finally {
      setDatabaseImportBusy(false)
    }
  }

  const applyDatabaseEntityImport = async () => {
    const snapshotDigest = databaseImportPlan?.snapshotDigest
    const planDigest = databaseImportPreview?.planDigest
    if (!databaseImportRequest || !snapshotDigest || !planDigest) return
    setDatabaseImportBusy(true)
    try {
      const response = await bridge.applyDatabaseEntityImport(
        databaseImportRequest,
        snapshotDigest,
        planDigest,
      )
      if (!response.success) {
        addToast(response.issues[0]?.message ?? 'Atomic database import was rejected', 'error')
        return
      }
      const generatedNames = new Set(
        databaseImportPlan?.tables
          .map(table => table.entityQualifiedName)
          .filter((value): value is string => Boolean(value)),
      )
      const refreshed = await bridge.getSchemaWorkspace(true)
      setSchemaWorkspace(refreshed)
      setDatabaseProfileWorkspace(await bridge.getDatabaseEntityImportProfiles())
      const generated = refreshed.entities.find(candidate => generatedNames.has(candidate.qualifiedName))
      if (generated) {
        synchronizeExistingEntity(generated, refreshed)
      }
      setDatabaseBrowse(null)
      setDatabaseImportSelection([])
      setDatabaseIdentifierOverrides({})
      setDatabaseImportRequest(null)
      setDatabaseImportPlan(null)
      setDatabaseImportPreview(null)
      addToast(
        `Imported ${response.filesChanged.length} source and message file${response.filesChanged.length === 1 ? '' : 's'} atomically`,
        'success',
      )
    } catch (error: any) {
      addToast(`Database entity import failed: ${error.message}`, 'error')
    } finally {
      setDatabaseImportBusy(false)
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
        synchronizeExistingEntity(updated, refreshed)
        const cutoverAttributeIndex = typeCutoverSession
          ? updated.attributes.findIndex(attribute =>
              attribute.name === typeCutoverSession.attributeName)
          : -1
        const coordinatedAttributeIndex = coordinatedRename?.stage === 'NATIVE_PREVIEW'
          ? updated.attributes.findIndex(attribute =>
              attribute.name === coordinatedRename.newName)
          : -1
        setSelectedAttr(
          cutoverAttributeIndex >= 0
            ? cutoverAttributeIndex
            : coordinatedAttributeIndex >= 0
              ? coordinatedAttributeIndex
              : null,
        )
        setRenameDraft('')
        setRenameLaunched(false)
        if (coordinatedAttributeIndex >= 0) {
          setCoordinatedRename(null)
        }
        addToast(
          cutoverAttributeIndex >= 0
            ? 'Source migration indexed. Review the verified mapping cutover.'
            : coordinatedAttributeIndex >= 0
              ? 'Coordinated logical and physical rename completed and re-indexed.'
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
    const coordinatedMappingApply =
      coordinatedRename?.stage === 'MAPPING_PREVIEW' &&
      coordinatedRename.previewPlanDigest === generationPreview.planDigest
        ? coordinatedRename
        : null
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
            synchronizeExistingEntity(updated, refreshed)
            if (coordinatedMappingApply) {
              const renamedAttributeIndex = updated.attributes.findIndex(
                attribute => attribute.name === coordinatedMappingApply.attributeName,
              )
              setSelectedAttr(renamedAttributeIndex >= 0 ? renamedAttributeIndex : null)
              setRenameDraft(coordinatedMappingApply.newName)
              setCoordinatedRename({
                ...coordinatedMappingApply,
                previewPlanDigest: undefined,
                stage: 'MAPPING_APPLIED',
              })
              addToast(
                'Physical mapping is applied and re-indexed. Open IntelliJ usage preview to finish the logical rename.',
                'info',
              )
            }
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
    if (!propagationInspection || !propagationRequest) return null
    return {
      inspection: propagationRequest,
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
      const provisional = attributeNames.some(
        name => !snapshot.attributes.some(attribute => attribute.name === name),
      )
      const requestedNames = new Set(attributeNames)
      const baselineAttributes = existingEntityModel(
        snapshot,
        entity.generationTarget?.storeId,
      ).attributes ?? []
      const atomicEntity = provisional
        ? {
            ...entity,
            attributes: [
              ...baselineAttributes,
              ...entity.attributes.filter(attribute =>
                requestedNames.has(attribute.name) &&
                !snapshot.attributes.some(existing => existing.name === attribute.name)),
            ],
          }
        : entity
      const request: EntityAttributePropagationInspectionRequest = {
        entityQualifiedName: snapshot.qualifiedName,
        entityName: snapshot.entityName,
        className: snapshot.className,
        attributeNames,
        ...(provisional ? {
          entityChange: {
            sourceLocator: snapshot.sourceLocator,
            entity: atomicEntity,
          },
        } : {}),
      }
      const response = await bridge.inspectEntityAttributePropagation(request)
      setPropagationRequest(request)
      setPropagationInspection(response)
      setPropagationSelection(
        response.targets
          .filter(target => target.recommended && target.supported && !target.securityExpanding)
          .map(target => target.id),
      )
      if (response.accepted) {
        const editable = response.targets.filter(target => target.supported).length
        addToast(
          provisional
            ? `Atomic review found ${response.targets.length} connected targets; the entity and selected surfaces will change together`
            : `Impact review found ${response.targets.length} connected targets; ${editable} can be updated safely`,
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
          change.inspection.entityChange
            ? `Added the entity attribute and connected surfaces atomically across ${result.filesChanged.length} files`
            : `Propagated attributes atomically across ${result.filesChanged.length} files`,
          'success',
        )
        setPropagationInspection(null)
        setPropagationRequest(null)
        setPropagationSelection([])
        setPropagationPreview(null)
        bridge.getApplicationGraph(true).then(setApplicationGraph).catch(() => undefined)
        if (change.inspection.entityChange) {
          const refreshed = await bridge.getSchemaWorkspace(true)
          setSchemaWorkspace(refreshed)
          const updated = refreshed.entities.find(
            candidate => candidate.qualifiedName === change.inspection.entityQualifiedName,
          )
          if (updated) {
            synchronizeExistingEntity(updated, refreshed)
            setSelectedAttr(null)
            setGenerationPreview(null)
          }
        }
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

  const selectRepository = (artifactId: string) => {
    setRepositoryPreview(null)
    if (artifactId === 'new') {
      setSelectedRepositoryArtifactId('')
      setEntity({
        dataRepository: {
          enabled: true,
          interfaceName: `${entity.className}Repository`,
          applyConstraints: true,
          useNamedParameters: true,
          methods: [],
        },
      })
      return
    }
    const repository = entityRepositories.find(candidate => candidate.artifactId === artifactId)
    if (!repository) return
    setSelectedRepositoryArtifactId(repository.artifactId)
    setEntity({ dataRepository: repository.config })
  }

  const previewRepositoryChange = async () => {
    if (!existingEntity || !entity.dataRepository) return
    setRepositoryBusy(true)
    setRepositoryPreview(null)
    try {
      const response = await bridge.previewDataRepositoryChange({
        entitySource: existingEntity.sourceLocator,
        repositorySource: existingRepository?.sourceLocator,
        config: entity.dataRepository,
      })
      setRepositoryPreview(response)
      if (!response.accepted) {
        addToast(
          response.issues.map(issue => issue.message).join(' ') || 'Repository preview was rejected.',
          'error',
        )
      }
    } catch (error: any) {
      addToast(`Cannot preview repository change: ${error.message}`, 'error')
    } finally {
      setRepositoryBusy(false)
    }
  }

  const applyRepositoryChange = async () => {
    if (
      !existingEntity ||
      !entity.dataRepository ||
      !repositoryPreview?.accepted ||
      !repositoryPreview.planDigest
    ) return
    const change = {
      entitySource: existingEntity.sourceLocator,
      repositorySource: existingRepository?.sourceLocator,
      config: entity.dataRepository,
    }
    setRepositoryBusy(true)
    try {
      const response = await bridge.applyDataRepositoryChange(
        change,
        repositoryPreview.planDigest,
      )
      if (!response.success) {
        addToast(
          response.issues.map(issue => issue.message).join(' ') || 'Repository apply was rejected.',
          'error',
        )
        return
      }
      addToast(
        `${existingRepository ? 'Updated' : 'Created'} repository atomically: ${response.filesChanged.length} file(s)`,
        'success',
      )
      const refreshed = await bridge.getSchemaWorkspace(true)
      setSchemaWorkspace(refreshed)
      const updatedEntity = refreshed.entities.find(candidate =>
        candidate.qualifiedName === existingEntity.qualifiedName)
      const repositories = refreshed.repositories.filter(repository =>
        repository.entityQualifiedName === existingEntity.qualifiedName)
      const requestedName = entity.dataRepository.interfaceName || `${entity.className}Repository`
      const updatedRepository = repositories.find(repository =>
        repository.qualifiedName === existingRepository?.qualifiedName ||
        repository.interfaceName === requestedName) ?? repositories[0]
      setEntityRepositories(repositories)
      setSelectedRepositoryArtifactId(updatedRepository?.artifactId ?? '')
      setRepositoryPreview(null)
      if (updatedEntity) {
        const store = refreshed.stores.find(candidate =>
          candidate.moduleId === updatedEntity.moduleId &&
          candidate.name === updatedEntity.storeName)
        setExistingEntity(updatedEntity)
        setEntity(existingEntityModel(
          updatedEntity,
          store?.id,
          updatedRepository?.config ?? entity.dataRepository,
        ))
      }
    } catch (error: any) {
      addToast(`Cannot apply repository change: ${error.message}`, 'error')
    } finally {
      setRepositoryBusy(false)
    }
  }

  const handleReset = () => {
    setExistingEntity(null)
    setEntityRepositories([])
    setSelectedRepositoryArtifactId('')
    setRepositoryPreview(null)
    setGenerationPreview(null)
    setSelectedAttr(null)
    setRenameDraft('')
    setRenameLaunched(false)
    setCoordinatedRename(null)
    setDatabaseInspection(null)
    setDatabaseColumnDrafts({})
    setDatabaseSchemaName('')
    setDatabaseBrowse(null)
    setDatabaseImportSelection([])
    setDatabaseIdentifierOverrides({})
    setDatabaseImportRequest(null)
    setDatabaseImportPlan(null)
    setDatabaseImportPreview(null)
    setDatabaseProfileEnabled(true)
    setDatabaseProfileId('')
    setDatabaseProfileLabel('')
    setSelectedDatabaseProfileId('')
    setPropagationInspection(null)
    setPropagationRequest(null)
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
    const repositories = schemaWorkspace?.repositories.filter(repository =>
      repository.entityQualifiedName === snapshot.qualifiedName) ?? []
    const selectedRepository = repositories[0]
    setExistingEntity(snapshot)
    setEntityRepositories(repositories)
    setSelectedRepositoryArtifactId(selectedRepository?.artifactId ?? '')
    setRepositoryPreview(null)
    setGenerationPreview(null)
    setSelectedAttr(null)
    setShowTraitAttributes(false)
    setRenameDraft('')
    setRenameLaunched(false)
    setCoordinatedRename(null)
    setDatabaseInspection(null)
    setDatabaseColumnDrafts({})
    setDatabaseSchemaName('')
    setDatabaseBrowse(null)
    setDatabaseImportSelection([])
    setDatabaseIdentifierOverrides({})
    setDatabaseImportRequest(null)
    setDatabaseImportPlan(null)
    setDatabaseImportPreview(null)
    setDatabaseProfileEnabled(true)
    setDatabaseProfileId('')
    setDatabaseProfileLabel('')
    setSelectedDatabaseProfileId('')
    setPropagationInspection(null)
    setPropagationRequest(null)
    setPropagationSelection([])
    setPropagationPreview(null)
    setEntity(existingEntityModel(snapshot, store?.id, selectedRepository?.config ?? {
      enabled: false,
      applyConstraints: true,
      useNamedParameters: true,
      methods: [],
    }))
  }

  return (
    <div className="entity-designer-shell flex h-full min-w-0 flex-col">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-border bg-surface-light px-3 py-2.5 sm:px-4">
        <h2 className="min-w-0 truncate text-sm font-semibold text-gray-200">
          {editorSurface && existingEntity
            ? `Entity Designer · ${existingEntity.className}`
            : 'Entity Designer'}
        </h2>
        <div className="flex flex-wrap justify-end gap-2">
          {(existingEntity || entity.className) && (
            <button
              type="button"
              onClick={() => void openEntityViewWorkflow()}
              className="rounded border border-emerald-500/35 bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-100 transition-colors hover:bg-emerald-500/20"
            >
              {existingEntity ? 'Create bound views' : 'Create CRUD views'}
            </button>
          )}
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
          {!editorSurface && (
            <button
              onClick={handleReset}
              className="px-3 py-1.5 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border transition-colors"
            >
              {existingEntity ? 'Create New' : 'Reset'}
            </button>
          )}
          <button
            onClick={handleGenerate}
            disabled={isGenerating || Boolean(nativeSourceIssue)}
            className="px-4 py-1.5 text-xs rounded bg-jmix-500 text-white font-medium hover:bg-jmix-600 disabled:opacity-50 transition-colors"
          >
            {isGenerating ? 'Planning...' : existingEntity ? '⚡ Preview Safe Update' : '⚡ Preview Generation'}
          </button>
        </div>
      </div>

      {nativeSourceIssue && (
        <div
          role="alert"
          className="border-b border-amber-500/30 bg-amber-500/5 px-3 py-2 text-[10px] leading-relaxed text-amber-100 sm:px-4"
        >
          {nativeSourceIssue}
        </div>
      )}

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
            onClick={() => {
              if (
                coordinatedRename?.stage === 'MAPPING_PREVIEW' &&
                coordinatedRename.previewPlanDigest === generationPreview.planDigest
              ) {
                setCoordinatedRename(null)
              }
              setGenerationPreview(null)
            }}
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
        className="entity-designer-pane-switcher"
      />

      <div className="flex min-h-0 flex-1 overflow-hidden">
        {/* Left: Entity Config */}
        <div className={`entity-designer-config ${activePane === 'config' ? 'block' : 'hidden'} min-h-0 w-full flex-shrink-0 space-y-4 overflow-y-auto p-4`}>
          <Section title="Project Ownership">
            <Field label="Entity Source">
              <select
                value={existingEntity?.artifactId ?? 'new'}
                disabled={editorSurface || schemaLoading}
                onChange={(event) => selectExistingEntity(event.target.value)}
                className="w-full"
              >
                <option value="new">Create a new entity</option>
                {schemaWorkspace?.entities.length ? (
                  <optgroup label="Edit an existing entity">
                    {schemaWorkspace.entities.map((candidate) => (
                      <option key={candidate.artifactId} value={candidate.artifactId}>
                        {candidate.moduleId} · {candidate.qualifiedName} · {candidate.entityType}
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
                protected; mapping metadata and new fields are revision-bound.
                {entity.entityType === 'entity'
                  ? ' Rollback-capable Liquibase changes are previewed and applied atomically.'
                  : ' This non-table type never generates table DDL.'}
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

          {existingEntity && <EntitySourceContractEvidence entity={existingEntity} />}

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
          {entity.entityType === 'entity' && (
            <EntityInheritancePanel
              entity={entity}
              entities={schemaWorkspace?.entities ?? []}
              existingSource={Boolean(existingEntity)}
              onChange={(inheritance, extendsClass) => setEntity({ inheritance, extendsClass })}
            />
          )}

          {entity.entityType !== 'enum' && (
            <Section title="Lifecycle & Listeners">
              <div className="grid grid-cols-2 gap-1.5">
                {LIFECYCLE_CALLBACKS.map(callback => {
                  const active = entity.lifecycleCallbacks.includes(callback.value)
                  return (
                    <label
                      key={callback.value}
                      className={`flex min-w-0 items-center gap-1.5 rounded border px-2 py-1.5 text-[9px] ${
                        active
                          ? 'border-jmix-500/35 bg-jmix-500/10 text-jmix-200'
                          : 'border-surface-border bg-black/10 text-gray-500'
                      }`}
                    >
                      <input
                        type="checkbox"
                        checked={active}
                        onChange={() => setEntity({
                          lifecycleCallbacks: active
                            ? entity.lifecycleCallbacks.filter(item => item !== callback.value)
                            : [...entity.lifecycleCallbacks, callback.value],
                        })}
                      />
                      <span className="truncate">{callback.label}</span>
                    </label>
                  )
                })}
              </div>
              <Field label="JPA @EntityListeners classes">
                <textarea
                  rows={3}
                  value={entity.entityListeners.join(', ')}
                  onChange={event => setEntity({
                    entityListeners: event.target.value
                      .split(/[\n,]/)
                      .map(value => value.trim())
                      .filter(Boolean),
                  })}
                  placeholder="com.example.listener.CustomerEntityListener"
                  className="w-full resize-y"
                />
              </Field>
              <p className="text-[9px] leading-relaxed text-gray-600">
                Generated callback methods are intentionally empty. Business side effects belong in reviewed
                services or listeners with explicit transaction behavior.
              </p>
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
            {!existingEntity && entity.entityType === 'entity' && (
              <RepositoryDesignerPanel
                entity={entity}
                onChange={dataRepository => setEntity({ dataRepository })}
              />
            )}
          </Section>
          </fieldset>
          {existingEntity?.entityType === 'entity' && (
            <div className="mt-4 space-y-2">
              <div className="flex flex-wrap items-end gap-2">
                <label className="min-w-0 flex-1 text-[10px] text-gray-500">
                  Repository source
                  <select
                    value={selectedRepositoryArtifactId || 'new'}
                    onChange={event => selectRepository(event.target.value)}
                    className="mt-1 w-full min-w-0"
                  >
                    {entityRepositories.map(repository => (
                      <option key={repository.artifactId} value={repository.artifactId}>
                        {repository.qualifiedName}
                      </option>
                    ))}
                    <option value="new">Create another repository…</option>
                  </select>
                </label>
                {existingRepository && (
                  <button
                    type="button"
                    onClick={() => void bridge.navigateToSource(existingRepository.sourceLocator)}
                    className="rounded border border-surface-border px-2.5 py-1.5 text-[10px] text-gray-300"
                  >
                    Open source
                  </button>
                )}
              </div>
              <RepositoryDesignerPanel
                entity={entity}
                sourceLocked={Boolean(existingRepository)}
                lockedMethodCount={existingRepository?.config.methods.length ?? 0}
                onChange={dataRepository => {
                  setRepositoryPreview(null)
                  setEntity({ dataRepository })
                }}
                footer={(
                  <div className="space-y-2 border-t border-surface-border pt-3">
                    {existingRepository?.methodEvidence.some(evidence => !evidence.editable) && (
                      <div className="rounded border border-amber-500/25 bg-amber-500/5 p-2 text-[9px] leading-relaxed text-amber-100">
                        {existingRepository.methodEvidence
                          .filter(evidence => !evidence.editable)
                          .map(evidence => evidence.issue || `${evidence.sourceSignature} is source-owned.`)
                          .join(' ')}
                      </div>
                    )}
                    {repositoryPreview && (
                      <div className={`rounded border p-2 text-[9px] ${
                        repositoryPreview.accepted
                          ? 'border-emerald-500/25 bg-emerald-500/5 text-emerald-100'
                          : 'border-red-500/25 bg-red-500/5 text-red-100'
                      }`}>
                        <strong className="block">{repositoryPreview.label}</strong>
                        {repositoryPreview.files.map(file => (
                          <span key={file.relativePath} className="mt-1 block truncate font-mono">
                            {file.mode} · {file.relativePath}
                          </span>
                        ))}
                        {repositoryPreview.issues.map(issue => (
                          <span key={`${issue.code}-${issue.message}`} className="mt-1 block">
                            {issue.message}
                          </span>
                        ))}
                      </div>
                    )}
                    <div className="flex flex-wrap justify-end gap-2">
                      <button
                        type="button"
                        onClick={() => void previewRepositoryChange()}
                        disabled={repositoryBusy || !entity.dataRepository?.enabled}
                        className="rounded border border-jmix-500/35 bg-jmix-500/10 px-3 py-1.5 text-[10px] text-jmix-100 disabled:opacity-40"
                      >
                        {repositoryBusy ? 'Checking…' : existingRepository
                          ? 'Preview additive methods'
                          : 'Preview repository creation'}
                      </button>
                      {repositoryPreview?.accepted && (
                        <button
                          type="button"
                          onClick={() => void applyRepositoryChange()}
                          disabled={repositoryBusy}
                          className="rounded bg-emerald-600 px-3 py-1.5 text-[10px] font-medium text-white disabled:opacity-40"
                        >
                          Apply atomically
                        </button>
                      )}
                    </div>
                  </div>
                )}
              />
            </div>
          )}
        </div>

        {/* Center: Attributes Table */}
        <div className={`entity-designer-attributes ${activePane === 'attributes' ? 'block' : 'hidden'} min-h-0 min-w-0 flex-1 overflow-y-auto p-3 sm:p-4`}>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider">
              {entity.entityType === 'enum' ? 'Enumeration' : 'Attributes'}
            </h3>
            {entity.entityType !== 'enum' && (
              <div className="flex flex-wrap justify-end gap-2">
                {hiddenTraitAttributeCount > 0 && (
                  <button
                    type="button"
                    aria-pressed={showTraitAttributes}
                    onClick={() => {
                      setShowTraitAttributes(current => {
                        const next = !current
                        if (!next && selectedAttr !== null) {
                          const selectedName = entity.attributes[selectedAttr]?.name
                          if (selectedName && traitManagedAttributeNames.has(selectedName)) {
                            setSelectedAttr(null)
                          }
                        }
                        return next
                      })
                    }}
                    className={`rounded border px-2.5 py-1 text-[10px] transition-colors ${
                      showTraitAttributes
                        ? 'border-sky-500/35 bg-sky-500/10 text-sky-100'
                        : 'border-surface-border bg-black/10 text-gray-500 hover:text-gray-300'
                    }`}
                  >
                    {showTraitAttributes ? 'Hide' : 'Show'} {hiddenTraitAttributeCount} trait field
                    {hiddenTraitAttributeCount === 1 ? '' : 's'}
                  </button>
                )}
                {!existingEntity && entity.entityType === 'entity' &&
                  Boolean(databaseProfileWorkspace?.profiles.length) && (
                    <div className="flex min-w-0 max-w-full items-center gap-1 rounded border border-violet-500/25 bg-violet-500/[0.07] p-1">
                      <select
                        aria-label="Saved database mappings"
                        value={selectedDatabaseProfileId}
                        onChange={event => setSelectedDatabaseProfileId(event.target.value)}
                        className="min-w-0 max-w-48 border-0 bg-transparent py-0.5 text-[10px] text-violet-100"
                      >
                        <option value="">Saved database mappings…</option>
                        {databaseProfileWorkspace?.profiles.map(document => (
                          <option key={document.profile.id} value={document.profile.id}>
                            {document.profile.label}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        onClick={loadDatabaseImportProfile}
                        disabled={!selectedDatabaseProfileId || databaseImportBusy}
                        className="shrink-0 rounded bg-violet-500/20 px-2 py-1 text-[10px] text-violet-100 hover:bg-violet-500/30 disabled:opacity-50"
                      >
                        Review live drift
                      </button>
                    </div>
                  )}
                {entity.entityType === 'entity' && (
                  <button
                    type="button"
                    onClick={browseDatabaseTables}
                    disabled={databaseBrowseBusy || databaseInspectBusy || !entity.generationTarget?.storeId}
                    className="rounded border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-200 transition-colors hover:bg-cyan-500/20 disabled:opacity-50"
                  >
                    {databaseBrowseBusy
                      ? 'Browsing database…'
                      : existingEntity
                        ? '⌕ Browse live database'
                        : '⌕ Import database model'}
                  </button>
                )}
                {existingEntity && (
                  <>
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
                          attribute ? [attribute.name] : [],
                        )
                      }}
                      disabled={
                        propagationBusy ||
                        selectedAttr === null
                      }
                      className="rounded border border-violet-500/30 bg-violet-500/10 px-3 py-1 text-xs text-violet-200 transition-colors hover:bg-violet-500/20 disabled:opacity-50"
                    >
                      {propagationBusy
                        ? 'Mapping impact…'
                        : selectedAttr === null
                          ? '⇢ Select attribute for connected impact'
                          : existingAttributeNames.has(entity.attributes[selectedAttr]?.name ?? '')
                          ? '⇢ Add selected attribute to views'
                          : '⇢ Add attribute + connected surfaces'}
                    </button>
                  </>
                )}
                <button
                  onClick={() => {
                    addAttribute()
                    setSelectedAttr(entity.attributes.length)
                    setPropagationInspection(null)
                    setPropagationRequest(null)
                    setPropagationSelection([])
                    setPropagationPreview(null)
                  }}
                  className="rounded bg-jmix-500/20 px-3 py-1 text-xs text-jmix-400 transition-colors hover:bg-jmix-500/30"
                >
                  + Add Attribute
                </button>
              </div>
            )}
          </div>

          {existingEntity && <InheritedAttributeEvidence entity={existingEntity} />}

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
              importMode={!existingEntity}
              selectedTables={databaseImportSelection}
              planning={databaseImportBusy}
              profileEnabled={databaseProfileEnabled}
              profileId={databaseProfileId}
              profileLabel={databaseProfileLabel}
              onProfileEnabledChange={value => {
                setDatabaseProfileEnabled(value)
                setDatabaseImportPlan(null)
                setDatabaseImportPreview(null)
              }}
              onProfileIdChange={value => {
                setDatabaseProfileId(value)
                setDatabaseImportPlan(null)
                setDatabaseImportPreview(null)
              }}
              onProfileLabelChange={value => {
                setDatabaseProfileLabel(value)
                setDatabaseImportPlan(null)
                setDatabaseImportPreview(null)
              }}
              onToggleTable={(table) => {
                const key = databaseTableKey(table)
                const alreadySelected = databaseImportSelection.some(candidate =>
                  databaseTableKey(candidate) === key)
                setDatabaseImportSelection(current => alreadySelected
                  ? current.filter(candidate => databaseTableKey(candidate) !== key)
                  : [...current, table])
                if (!alreadySelected && !databaseProfileId) {
                  setDatabaseProfileId(databaseProfileSlug(table.name))
                  setDatabaseProfileLabel(`${databaseProfileTitle(table.name)} database model`)
                }
                setDatabaseImportPlan(null)
                setDatabaseImportPreview(null)
              }}
              onPlan={() => planDatabaseEntityImport()}
              onClose={() => {
                setDatabaseBrowse(null)
                if (!existingEntity) {
                  setDatabaseImportSelection([])
                  setDatabaseImportPlan(null)
                  setDatabaseImportPreview(null)
                }
              }}
            />
          )}

          {(databaseImportBusy || databaseImportPlan) && !existingEntity && (
            <DatabaseEntityImportPanel
              busy={databaseImportBusy}
              plan={databaseImportPlan}
              preview={databaseImportPreview}
              identifierOverrides={databaseIdentifierOverrides}
              onIdentifierToggle={(table, column) => {
                const key = databaseTableKey(table)
                const current = databaseIdentifierOverrides[key] ?? []
                const next = current.includes(column)
                  ? current.filter(candidate => candidate !== column)
                  : [...current, column]
                setDatabaseIdentifierOverrides(overrides => ({ ...overrides, [key]: next }))
                setDatabaseImportPreview(null)
              }}
              onReplan={() => planDatabaseEntityImport(databaseIdentifierOverrides)}
              onPreview={previewDatabaseEntityImport}
              onApply={applyDatabaseEntityImport}
              onClose={() => {
                setDatabaseImportPlan(null)
                setDatabaseImportPreview(null)
              }}
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
                    Review each exact source target. New entity attributes can be committed with their
                    views, fetch plans, captions, and explicit role policies in one transaction.
                    Privilege-expanding security updates are never selected automatically.
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setPropagationInspection(null)
                    setPropagationRequest(null)
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
                      {propagationRequest?.entityChange
                        ? 'Preview atomic entity + surfaces'
                        : 'Preview selected targets'}
                    </button>
                    <button
                      type="button"
                      onClick={handleApplyPropagation}
                      disabled={propagationBusy || !propagationPreview?.planDigest}
                      className="rounded bg-violet-500 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-violet-600 disabled:opacity-50"
                    >
                      {propagationRequest?.entityChange
                        ? 'Apply entity + surfaces atomically'
                        : 'Apply atomic propagation'}
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
              <table className="w-full min-w-[44rem] text-xs">
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
                  {visibleAttributeEntries.map(({ attribute: attr, index: i }) => {
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
                        onClick={() => selectAttribute(i, attr.name)}
                        className={`border-t border-surface-border cursor-pointer transition-colors ${
                          selectedAttr === i ? 'bg-jmix-500/10' : 'hover:bg-surface-lighter'
                        } ${locked ? 'text-gray-500' : ''}`}
                      >
                        <td className="px-3 py-2">
                          {locked ? (
                            <button
                              type="button"
                              aria-pressed={selectedAttr === i}
                              aria-label={`Inspect ${attr.name}`}
                              onClick={event => {
                                event.stopPropagation()
                                selectAttribute(i, attr.name)
                              }}
                              className="w-28 truncate rounded px-1 py-0.5 text-left font-mono text-gray-400 hover:bg-jmix-500/10 hover:text-jmix-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-jmix-400"
                              title={`Inspect ${attr.name}`}
                            >
                              {attr.name}
                            </button>
                          ) : (
                            <input
                              value={attr.name}
                              onChange={e => updateAttribute(i, { name: e.target.value })}
                              onClick={e => e.stopPropagation()}
                              className="w-28 bg-transparent border-none p-0 text-gray-200"
                            />
                          )}
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
                          <div className="flex items-center gap-0.5">
                            <button
                              type="button"
                              aria-label={`Copy ${attr.name}`}
                              title="Copy as a new attribute draft"
                              onClick={event => {
                                event.stopPropagation()
                                duplicateDraftAttribute(i, locked)
                              }}
                              className="rounded p-1 text-gray-500 transition-colors hover:bg-jmix-500/10 hover:text-jmix-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-jmix-400"
                            >
                              <Copy size={13} aria-hidden="true" />
                            </button>
                            {locked ? (
                              <span
                                title={mappingLocked
                                  ? 'Relationship or transient source mapping is protected'
                                  : 'Existing declaration order and destructive shape edits are source-protected'}
                                aria-label={`${attr.name} source declaration is order locked`}
                                className="inline-flex items-center gap-1 rounded px-1 py-1 text-[9px] text-gray-600"
                              >
                                <LockKeyhole size={12} aria-hidden="true" />
                                <span>{mappingLocked ? 'Protected' : 'Source'}</span>
                              </span>
                            ) : (
                              <>
                                <button
                                  type="button"
                                  aria-label={`Move ${attr.name} up`}
                                  title="Move draft up"
                                  disabled={
                                    i === 0 ||
                                    existingAttributeNames.has(entity.attributes[i - 1]?.name ?? '')
                                  }
                                  onClick={event => {
                                    event.stopPropagation()
                                    reorderDraftAttribute(i, i - 1)
                                  }}
                                  className="rounded p-1 text-gray-500 transition-colors hover:bg-white/5 hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-20"
                                >
                                  <ChevronUp size={13} aria-hidden="true" />
                                </button>
                                <button
                                  type="button"
                                  aria-label={`Move ${attr.name} down`}
                                  title="Move draft down"
                                  disabled={
                                    i === entity.attributes.length - 1 ||
                                    existingAttributeNames.has(entity.attributes[i + 1]?.name ?? '')
                                  }
                                  onClick={event => {
                                    event.stopPropagation()
                                    reorderDraftAttribute(i, i + 1)
                                  }}
                                  className="rounded p-1 text-gray-500 transition-colors hover:bg-white/5 hover:text-gray-200 disabled:cursor-not-allowed disabled:opacity-20"
                                >
                                  <ChevronDown size={13} aria-hidden="true" />
                                </button>
                                <button
                                  type="button"
                                  aria-label={`Delete ${attr.name}`}
                                  title="Delete draft attribute"
                                  onClick={event => {
                                    event.stopPropagation()
                                    removeAttribute(i)
                                    setSelectedAttr(null)
                                    setGenerationPreview(null)
                                  }}
                                  className="rounded p-1 text-red-400 transition-colors hover:bg-red-500/10 hover:text-red-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400"
                                >
                                  <Trash2 size={13} aria-hidden="true" />
                                </button>
                              </>
                            )}
                          </div>
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
                const requestedPhysicalColumn = (
                  selected.association?.joinColumnName ||
                  selected.columnName ||
                  source?.columnName ||
                  ''
                ).trim()
                const physicalRenameRequested = Boolean(
                  source?.persistent &&
                  requestedPhysicalColumn &&
                  requestedPhysicalColumn !== source.columnName,
                )
                const activeCoordinatedRename =
                  coordinatedRename?.attributeName === selected.name
                    ? coordinatedRename
                    : null
                const scalarColumnRenameSafe = Boolean(
                  source?.persistent &&
                  !source.association &&
                  source.columnName &&
                  existingEntity?.ddlMode !== 'DISABLED' &&
                  !existingEntity?.databaseView,
                )
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
                    {selected.type === 'embedded' && (
                      <div className="mt-3">
                        <EmbeddedOverrideEditor
                          attribute={selected}
                          entities={schemaWorkspace?.entities ?? []}
                          existingSource
                          onChange={(patch) => updateAttribute(selectedAttr, patch)}
                        />
                      </div>
                    )}
                    {scalarColumnRenameSafe && (
                      <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                        <Field label="Physical column">
                          <input
                            value={selected.columnName ?? source?.columnName ?? ''}
                            onChange={(event) => {
                              if (activeCoordinatedRename?.stage === 'MAPPING_PREVIEW') {
                                setGenerationPreview(null)
                                setCoordinatedRename(null)
                              }
                              updateAttribute(selectedAttr, {
                                columnName: event.target.value || undefined,
                              })
                            }}
                            className="w-full min-w-0 font-mono"
                            aria-label={`Physical column for ${selected.name}`}
                          />
                        </Field>
                        <div className="min-w-0 rounded border border-emerald-500/20 bg-emerald-500/5 p-2 text-[10px] leading-relaxed text-emerald-200/80">
                          The explicit <code>@Column(name)</code> mapping and reversible Liquibase rename can be
                          staged before IntelliJ renames the logical property and every connected usage.
                        </div>
                      </div>
                    )}
                    {joinColumnRenameSafe && selected.association && (
                      <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                        <Field label="Physical join column">
                          <input
                            value={selected.association.joinColumnName ?? ''}
                            onChange={(event) => {
                              if (activeCoordinatedRename?.stage === 'MAPPING_PREVIEW') {
                                setGenerationPreview(null)
                                setCoordinatedRename(null)
                              }
                              updateAttribute(selectedAttr, {
                                association: {
                                  ...selected.association!,
                                  joinColumnName: event.target.value || undefined,
                                },
                              })
                            }}
                            className="w-full min-w-0 font-mono"
                            aria-label={`Physical join column for ${selected.name}`}
                          />
                        </Field>
                        <div className="min-w-0 rounded border border-emerald-500/20 bg-emerald-500/5 p-2 text-[10px] leading-relaxed text-emerald-200/80">
                          Preview updates only the literal <code>@JoinColumn(name)</code> and creates a guarded
                          Liquibase rename with reverse rollback. A proven optional/required transition can travel
                          in the same atomic plan; target and ownership remain locked unless a dedicated checked
                          relationship transformation below explicitly unlocks them.
                        </div>
                      </div>
                    )}
                    {sourceAssociation && selected.association && (
                      <ExistingRelationshipSemanticsEditor
                        attribute={selected}
                        sourceAssociation={sourceAssociation}
                        sourceNullable={source?.nullable ?? true}
                        sourceUnique={source?.unique ?? false}
                        sourceEntity={existingEntity!}
                        schemaWorkspace={schemaWorkspace}
                        onChange={(change) => updateAttribute(selectedAttr, change)}
                      />
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
                    {source && (
                      <ExistingAttributeSourceMetadataEditor
                        attribute={selected}
                        unmanagedAnnotations={source.unmanagedAnnotations ?? []}
                        onChange={(change) => updateAttribute(selectedAttr, change)}
                      />
                    )}
                    <p className="mt-3 text-[10px] leading-relaxed text-gray-600">
                      Managed metadata edits preserve property identity, Java/Kotlin type, unmanaged annotations,
                      accessors, and call sites. Unsafe relationship or mutability changes remain blocked by the backend
                      even if a request bypasses this UI.
                    </p>
                    {source && (
                      <div className="mt-4 rounded-lg border border-jmix-500/20 bg-jmix-500/5 p-3">
                        <div className="text-[10px] font-semibold uppercase tracking-wider text-jmix-300">
                          Coordinated safe rename
                        </div>
                        <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
                          Opens IntelliJ&apos;s usage preview so Java, Kotlin, FlowUI, fetch plans, JPQL, and security
                          references and JPA mappedBy strings participate in the IDE refactor. When the explicit
                          physical mapping also changes, the designer first preflights the native rename, applies a
                          reversible mapping migration, refreshes the source revision, and then opens usage preview.
                        </p>
                        {physicalRenameRequested && (
                          <div className="mt-2 flex min-w-0 flex-wrap items-center gap-1.5 text-[9px] text-emerald-200/80">
                            <span className="max-w-full break-all rounded border border-surface-border bg-black/15 px-2 py-1 font-mono">
                              {source.columnName}
                            </span>
                            <span aria-hidden="true">→</span>
                            <span className="max-w-full break-all rounded border border-emerald-500/25 bg-emerald-500/5 px-2 py-1 font-mono">
                              {requestedPhysicalColumn}
                            </span>
                          </div>
                        )}
                        {activeCoordinatedRename && (
                          <div className={`mt-2 rounded border px-2.5 py-2 text-[9px] leading-relaxed ${
                            activeCoordinatedRename.stage === 'MAPPING_APPLIED'
                              ? 'border-emerald-500/30 bg-emerald-500/5 text-emerald-100/80'
                              : activeCoordinatedRename.stage === 'NATIVE_PREVIEW'
                                ? 'border-sky-500/30 bg-sky-500/5 text-sky-100/80'
                                : 'border-amber-500/30 bg-amber-500/5 text-amber-100/80'
                          }`}>
                            {activeCoordinatedRename.stage === 'MAPPING_PREVIEW'
                              ? 'Native rename preflight passed. Review and apply the immutable physical mapping preview above.'
                              : activeCoordinatedRename.stage === 'MAPPING_APPLIED'
                                ? 'Physical mapping applied and re-indexed. The project remains runnable; open IntelliJ usage preview to finish the logical rename.'
                                : 'IntelliJ usage preview is open. Apply or cancel it in the IDE, then refresh this entity.'}
                          </div>
                        )}
                        <div className="mt-3 flex flex-col gap-2 sm:flex-row">
                          <input
                            value={renameDraft}
                            onChange={event => {
                              const newName = event.target.value
                              setRenameDraft(newName)
                              if (activeCoordinatedRename?.stage === 'MAPPING_PREVIEW') {
                                setGenerationPreview(null)
                                setCoordinatedRename(null)
                              } else if (activeCoordinatedRename) {
                                setCoordinatedRename({
                                  ...activeCoordinatedRename,
                                  newName,
                                })
                              }
                            }}
                            className="min-w-0 flex-1"
                            aria-label={`New name for ${selected.name}`}
                          />
                          <button
                            type="button"
                            disabled={
                              renameBusy ||
                              !renameDraft.trim() ||
                              renameDraft.trim() === selected.name ||
                              activeCoordinatedRename?.stage === 'MAPPING_PREVIEW' ||
                              activeCoordinatedRename?.stage === 'NATIVE_PREVIEW'
                            }
                            onClick={() => previewCoordinatedAttributeRename(selected, source)}
                            className="rounded bg-jmix-500 px-3 py-1.5 text-xs font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
                          >
                            {renameBusy
                              ? 'Resolving…'
                              : activeCoordinatedRename?.stage === 'MAPPING_PREVIEW'
                                ? 'Review mapping preview'
                                : activeCoordinatedRename?.stage === 'NATIVE_PREVIEW'
                                  ? 'Usage preview open'
                                  : physicalRenameRequested
                                    ? 'Preview coordinated rename'
                                    : 'Open usage preview'}
                          </button>
                          {(renameLaunched || activeCoordinatedRename?.stage === 'NATIVE_PREVIEW') && (
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
                existingSource={Boolean(existingEntity)}
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

          {existingEntity?.entityType === 'entity' && (
            <EntityEventListenerPanel
              entity={existingEntity}
              listeners={entityEventListeners}
              onOpenSource={openImpactSource}
              onApplied={refreshAndOpenCreatedListener}
              addToast={addToast}
            />
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
                <div className="flex flex-wrap items-center gap-2">
                  <button
                    type="button"
                    onClick={() => void openEntityViewWorkflow()}
                    className="rounded border border-emerald-500/30 bg-emerald-500/10 px-2.5 py-1 text-[10px] font-medium text-emerald-100 hover:bg-emerald-500/20"
                  >
                    Create list + detail views
                  </button>
                  <span className="rounded bg-surface-lighter px-2 py-1 text-[10px] text-jmix-300">
                    {entityImpact.length} connected
                  </span>
                </div>
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
                      <div className="mt-2 flex flex-wrap justify-end gap-1.5">
                        <button
                          type="button"
                          onClick={() => void openImpactSource(artifact)}
                          className="rounded border border-surface-border px-2 py-1 text-[9px] text-gray-400 hover:border-jmix-500/40 hover:text-gray-200"
                        >
                          Open source
                        </button>
                        {artifact.kind === 'VIEW_DESCRIPTOR' && (
                          <button
                            type="button"
                            onClick={() => void designImpactView(artifact)}
                            className="rounded border border-jmix-500/35 bg-jmix-500/10 px-2 py-1 text-[9px] text-jmix-200 hover:bg-jmix-500/20"
                          >
                            Design
                          </button>
                        )}
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
          <div className={`entity-designer-preview ${activePane === 'preview' ? 'block' : 'hidden'} min-h-0 w-full flex-shrink-0 overflow-y-auto p-4`}>
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
  existingSource,
  onChange,
}: {
  attr: AttributeModel
  entity: EntityModel
  projectId?: string
  schemaWorkspace: SchemaWorkspaceResponse | null
  existingSource: boolean
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
  const relatedEntitySnapshot = schemaWorkspace?.entities.find(
    candidate => candidate.qualifiedName === association.relatedEntity,
  )
  const pairedInverseSupported = Boolean(
    existingSource &&
    relatedEntitySnapshot?.entityType === 'entity' &&
    relatedEntitySnapshot.moduleId === entity.generationTarget?.moduleId &&
    relatedEntitySnapshot.storeName === entity.dataStore &&
    !association.crossDataStore &&
    (
      association.associationType === 'manyToOne' ||
      (association.associationType === 'oneToOne' && !association.mappedBy) ||
      (association.associationType === 'manyToMany' && !association.mappedBy && association.joinTable)
    ),
  )
  const suggestedInverseName = association.associationType === 'manyToOne'
    ? `${entity.className.charAt(0).toLowerCase()}${entity.className.slice(1)}s`
    : `${entity.className.charAt(0).toLowerCase()}${entity.className.slice(1)}`

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
                    generateInverse: associationType === 'manyToOne'
                      ? association.generateInverse
                      : false,
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
                    generateInverse: crossDataStore ? false : association.generateInverse,
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
                      generateInverse: e.target.value ? false : association.generateInverse,
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
            {pairedInverseSupported && (
              <div className="sm:col-span-2 rounded-lg border border-emerald-500/25 bg-emerald-500/[0.05] p-3">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <div className="text-[10px] font-semibold uppercase tracking-wider text-emerald-200">
                      Atomic inverse side
                    </div>
                    <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
                      Generate the matching property in {relatedEntitySnapshot?.className} and preview both
                      Java/Kotlin entities plus owning-side Liquibase as one revision-bound change.
                    </p>
                  </div>
                  <label className="flex shrink-0 cursor-pointer items-center gap-2 text-[10px] text-gray-300">
                    <input
                      type="checkbox"
                      checked={Boolean(association.generateInverse)}
                      onChange={event => updateAssociation({
                        generateInverse: event.target.checked,
                        inverseAttributeName: event.target.checked
                          ? association.inverseAttributeName || suggestedInverseName
                          : association.inverseAttributeName,
                      })}
                    />
                    generate inverse
                  </label>
                </div>
                {association.generateInverse && (
                  <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                    <Field label={`Property in ${relatedEntitySnapshot?.className}`}>
                      <input
                        value={association.inverseAttributeName || ''}
                        onChange={event => updateAssociation({
                          inverseAttributeName: event.target.value || undefined,
                        })}
                        placeholder={suggestedInverseName}
                        className="w-full min-w-0 font-mono"
                        aria-label={`Inverse attribute for ${attr.name}`}
                      />
                    </Field>
                    <div className="min-w-0 rounded border border-emerald-500/15 bg-black/10 p-2 text-[9px] leading-relaxed text-emerald-100/70">
                      The generated inverse uses <code>mappedBy=&quot;{attr.name}&quot;</code>. Name collisions,
                      stale target source, cross-store targets, and unsupported ownership shapes fail closed.
                    </div>
                  </div>
                )}
              </div>
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
          <EmbeddedOverrideEditor
            attribute={attr}
            entities={schemaWorkspace?.entities ?? []}
            existingSource={false}
            onChange={onChange}
          />
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

function ExistingAttributeSourceMetadataEditor({
  attribute,
  unmanagedAnnotations,
  onChange,
}: {
  attribute: AttributeModel
  unmanagedAnnotations: string[]
  onChange: (change: Partial<AttributeModel>) => void
}) {
  return (
    <div className="mt-4 rounded-lg border border-violet-500/20 bg-violet-500/[0.04] p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[10px] font-semibold uppercase tracking-wider text-violet-200">
            Source metadata
          </div>
          <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
            These annotations round-trip through exact Java/Kotlin source ranges. Unknown annotations remain untouched.
          </p>
        </div>
        {attribute.readOnly && (
          <span className="rounded border border-amber-500/25 bg-amber-500/10 px-2 py-1 text-[8px] text-amber-200">
            read-only accessor · native refactor required
          </span>
        )}
      </div>
      <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <Field label="Metadata comment">
          <input
            value={attribute.comment ?? ''}
            onChange={event => onChange({ comment: event.target.value || undefined })}
            className="w-full min-w-0"
            aria-label={`Metadata comment for ${attribute.name}`}
          />
        </Field>
        <Field label="Property datatype">
          <input
            value={attribute.propertyDatatype ?? ''}
            onChange={event => onChange({ propertyDatatype: event.target.value || undefined })}
            className="w-full min-w-0"
            placeholder="customDatatypeId"
            aria-label={`Property datatype for ${attribute.name}`}
          />
        </Field>
        <Field label="Depends on properties">
          <input
            value={attribute.dependsOnProperties.join(', ')}
            onChange={event => onChange({
              dependsOnProperties: event.target.value
                .split(',')
                .map(value => value.trim())
                .filter(Boolean),
            })}
            className="w-full min-w-0"
            placeholder="firstName, lastName"
            aria-label={`Dependencies for ${attribute.name}`}
          />
        </Field>
      </div>
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-2">
        {([
          ['systemLevel', 'System level'],
          ['lob', 'Large object'],
          ['jmixProperty', 'Explicit Jmix property'],
        ] as const).map(([property, label]) => (
          <label key={property} className="flex cursor-pointer items-center gap-1.5 text-[10px] text-gray-400">
            <input
              type="checkbox"
              checked={Boolean(attribute[property])}
              onChange={event => onChange({ [property]: event.target.checked })}
            />
            {label}
          </label>
        ))}
      </div>
      <div className="mt-3 border-t border-violet-500/15 pt-3">
        <div className="text-[9px] font-medium uppercase tracking-wider text-gray-500">
          Jakarta validation
        </div>
        <div className="mt-2 flex flex-wrap gap-1.5">
          {VALIDATIONS.map(validation => {
            const active = attribute.validations.some(candidate => candidate.type === validation)
            return (
              <button
                key={validation}
                type="button"
                onClick={() => onChange({
                  validations: active
                    ? attribute.validations.filter(candidate => candidate.type !== validation)
                    : [...attribute.validations, { type: validation }],
                })}
                className={`rounded border px-2 py-1 text-[9px] transition-colors ${
                  active
                    ? 'border-violet-500/40 bg-violet-500/20 text-violet-100'
                    : 'border-surface-border bg-black/10 text-gray-500 hover:text-gray-300'
                }`}
              >
                {validation}
              </button>
            )
          })}
        </div>
      </div>
      {unmanagedAnnotations.length > 0 && (
        <div className="mt-3 border-t border-violet-500/15 pt-3">
          <div className="text-[9px] font-medium uppercase tracking-wider text-gray-500">
            Preserved source-only annotations
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {unmanagedAnnotations.map(annotation => (
              <span
                key={annotation}
                className="rounded border border-surface-border bg-black/15 px-2 py-1 font-mono text-[8px] text-gray-400"
              >
                @{annotation}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

function ExistingRelationshipSemanticsEditor({
  attribute,
  sourceAssociation,
  sourceNullable,
  sourceUnique,
  sourceEntity,
  schemaWorkspace,
  onChange,
}: {
  attribute: AttributeModel
  sourceAssociation: NonNullable<SchemaEntityAttributeSnapshot['associationDetails']>
  sourceNullable: boolean
  sourceUnique: boolean
  sourceEntity: SchemaEntitySnapshot
  schemaWorkspace: SchemaWorkspaceResponse | null
  onChange: (change: Partial<AttributeModel>) => void
}) {
  const association = attribute.association!
  const semanticEditingDisabled = association.crossDataStore
  const compositionEligible = ['oneToMany', 'oneToOne'].includes(association.associationType)
  const orphanRemovalEligible = compositionEligible
  const owningToOneUpgradeEligible =
    sourceAssociation.associationType === 'manyToOne' &&
    !sourceAssociation.crossDataStore &&
    !sourceAssociation.mappedBy &&
    Boolean(sourceAssociation.joinColumnName)
  const sourceStore = schemaWorkspace?.stores.find(
    candidate => candidate.moduleId === sourceEntity.moduleId && candidate.name === sourceEntity.storeName,
  )
  const physicalStore = schemaWorkspace?.physicalSchemas.find(
    candidate => candidate.storeId === sourceStore?.id,
  )
  const physicalTable = physicalStore?.tables.find(
    candidate => candidate.name.toLowerCase() === sourceEntity.tableName.split('.').pop()?.toLowerCase(),
  )
  const uniqueBackingCount = sourceAssociation.joinColumnName
    ? (
      (physicalTable?.uniqueConstraints ?? []).filter(
        constraint => constraint.columns.length === 1 &&
          constraint.columns[0].toLowerCase() === sourceAssociation.joinColumnName?.toLowerCase(),
      ).length +
      (physicalTable?.indexes ?? []).filter(
        index => index.unique &&
          index.columns.length === 1 &&
          index.columns[0].toLowerCase() === sourceAssociation.joinColumnName?.toLowerCase(),
      ).length
    )
    : 0
  const owningToOneWideningCandidate =
    sourceAssociation.associationType === 'oneToOne' &&
    !sourceAssociation.crossDataStore &&
    !sourceAssociation.mappedBy &&
    Boolean(sourceAssociation.joinColumnName) &&
    sourceUnique
  const owningToOneWideningEligible =
    owningToOneWideningCandidate &&
    !sourceEntity.databaseView &&
    sourceEntity.ddlMode !== 'DISABLED' &&
    !sourceEntity.tableSchema &&
    !sourceEntity.tableCatalog &&
    physicalStore?.complete === true &&
    physicalTable?.columns.some(
      column => column.name.toLowerCase() === sourceAssociation.joinColumnName?.toLowerCase() &&
      column.unique &&
      !column.primaryKey,
    ) === true &&
    uniqueBackingCount === 1
  const relatedEntitySnapshot = schemaWorkspace?.entities.find(
    candidate => candidate.qualifiedName === sourceAssociation.relatedEntity,
  )
  const inverseCardinalityCandidates = relatedEntitySnapshot?.attributes.filter(candidate =>
    candidate.association &&
    candidate.associationDetails?.relatedEntity === sourceEntity.qualifiedName &&
    candidate.associationDetails?.mappedBy === attribute.name,
  ) ?? []
  const exactInverseCardinality = inverseCardinalityCandidates.length === 1
    ? inverseCardinalityCandidates[0]
    : undefined
  const checkedCardinalityEligible =
    (owningToOneUpgradeEligible || owningToOneWideningEligible) &&
    inverseCardinalityCandidates.length <= 1
  const relatedPhysicalTable = physicalStore?.tables.find(
    candidate => candidate.name.toLowerCase() ===
      relatedEntitySnapshot?.tableName.split('.').pop()?.toLowerCase(),
  )
  const relationshipPhysicalColumn = sourceAssociation.joinColumnName
    ? physicalTable?.columns.find(
      candidate => candidate.name.toLowerCase() === sourceAssociation.joinColumnName?.toLowerCase(),
    )
    : undefined
  const requestedJoinColumn = association.joinColumnName?.trim() ?? ''
  const joinColumnRenameRequested =
    requestedJoinColumn.toLowerCase() !== sourceAssociation.joinColumnName?.toLowerCase()
  const joinColumnRenameEligible = Boolean(
    requestedJoinColumn &&
    /^[A-Za-z_][A-Za-z0-9_]*$/.test(requestedJoinColumn) &&
    !sourceEntity.attributes.some(candidate =>
      candidate.name !== attribute.name &&
      candidate.persistent &&
      candidate.columnName.toLowerCase() === requestedJoinColumn.toLowerCase(),
    ) &&
    !physicalTable?.columns.some(candidate =>
      candidate.name.toLowerCase() !== sourceAssociation.joinColumnName?.toLowerCase() &&
      candidate.name.toLowerCase() === requestedJoinColumn.toLowerCase(),
    )
  )
  const mandatoryForeignKeyCount = sourceAssociation.joinColumnName
    ? (physicalTable?.foreignKeys ?? []).filter(foreignKey =>
      foreignKey.baseColumnNames.split(',').map(value => value.trim()).length === 1 &&
      foreignKey.baseColumnNames.trim().toLowerCase() === sourceAssociation.joinColumnName?.toLowerCase() &&
      foreignKey.referencedTableName.toLowerCase() === relatedPhysicalTable?.name.toLowerCase() &&
      foreignKey.referencedColumnNames.split(',').map(value => value.trim()).length === 1 &&
      foreignKey.referencedColumnNames.trim().toLowerCase() === sourceAssociation.relatedIdColumnName.toLowerCase(),
    ).length
    : 0
  const nullabilityChangeCandidate =
    ['manyToOne', 'oneToOne'].includes(sourceAssociation.associationType) &&
    !sourceAssociation.crossDataStore &&
    !sourceAssociation.mappedBy &&
    !sourceAssociation.joinTable &&
    Boolean(sourceAssociation.joinColumnName)
  const nullabilityChangeEligible = Boolean(
    nullabilityChangeCandidate &&
    association.associationType === sourceAssociation.associationType &&
    attribute.unique === sourceUnique &&
    !sourceEntity.databaseView &&
    sourceEntity.ddlMode !== 'DISABLED' &&
    !sourceEntity.tableSchema &&
    !sourceEntity.tableCatalog &&
    physicalStore?.complete === true &&
    relatedPhysicalTable &&
    relationshipPhysicalColumn?.nullable === sourceNullable &&
    relationshipPhysicalColumn.primaryKey === false &&
    relationshipPhysicalColumn.unique === sourceUnique &&
    (!joinColumnRenameRequested || joinColumnRenameEligible) &&
    mandatoryForeignKeyCount === 1
  )
  const ownershipTransferCandidate =
    sourceAssociation.associationType === 'oneToOne' &&
    !sourceAssociation.crossDataStore &&
    Boolean(sourceAssociation.mappedBy) &&
    !sourceAssociation.joinTable &&
    Boolean(relatedEntitySnapshot)
  const releasingAttribute = ownershipTransferCandidate
    ? relatedEntitySnapshot?.attributes.find(
      candidate => candidate.name === sourceAssociation.mappedBy,
    )
    : undefined
  const releasingAssociation = releasingAttribute?.associationDetails
  const releasingTable = physicalStore?.tables.find(
    candidate => candidate.name.toLowerCase() ===
      relatedEntitySnapshot?.tableName.split('.').pop()?.toLowerCase(),
  )
  const releasingColumn = releasingAssociation?.joinColumnName
    ? releasingTable?.columns.find(
      candidate => candidate.name.toLowerCase() ===
        releasingAssociation.joinColumnName?.toLowerCase(),
    )
    : undefined
  const releasingUniqueBackingCount = releasingAssociation?.joinColumnName
    ? (
      (releasingTable?.uniqueConstraints ?? []).filter(
        constraint => constraint.columns.length === 1 &&
          constraint.columns[0].toLowerCase() === releasingAssociation.joinColumnName?.toLowerCase(),
      ).length +
      (releasingTable?.indexes ?? []).filter(
        index => index.unique &&
          index.columns.length === 1 &&
          index.columns[0].toLowerCase() === releasingAssociation.joinColumnName?.toLowerCase(),
      ).length
    )
    : 0
  const releasingForeignKeyCount = releasingAssociation?.joinColumnName
    ? (releasingTable?.foreignKeys ?? []).filter(foreignKey =>
      foreignKey.baseColumnNames.split(',').map(value => value.trim().toLowerCase()).length === 1 &&
      foreignKey.baseColumnNames.trim().toLowerCase() === releasingAssociation.joinColumnName?.toLowerCase() &&
      foreignKey.referencedTableName.toLowerCase() === physicalTable?.name.toLowerCase() &&
      foreignKey.referencedColumnNames.trim().toLowerCase() === sourceEntity.idColumnName.toLowerCase(),
    ).length
    : 0
  const ownershipColumnCandidates = [
    `${toDatabaseName(attribute.name)}_ID`,
    `OWNED_${toDatabaseName(attribute.name)}_ID`,
    `REL_${toDatabaseName(attribute.name)}_ID`,
  ]
  const defaultOwnershipColumn = ownershipColumnCandidates.find(candidate =>
    !physicalTable?.columns.some(column => column.name.toLowerCase() === candidate.toLowerCase()),
  ) ?? ownershipColumnCandidates[0]
  const suggestedOwnershipColumn =
    association.ownershipJoinColumnName || defaultOwnershipColumn
  const receiverColumnAvailable = !physicalTable?.columns.some(
    column => column.name.toLowerCase() === suggestedOwnershipColumn.toLowerCase(),
  )
  const ownershipTransferEligible = Boolean(
    ownershipTransferCandidate &&
    relatedEntitySnapshot?.entityType === 'entity' &&
    relatedEntitySnapshot.moduleId === sourceEntity.moduleId &&
    relatedEntitySnapshot.storeName === sourceEntity.storeName &&
    !sourceEntity.databaseView &&
    sourceEntity.ddlMode !== 'DISABLED' &&
    !sourceEntity.tableSchema &&
    !sourceEntity.tableCatalog &&
    !relatedEntitySnapshot.databaseView &&
    relatedEntitySnapshot.ddlMode !== 'DISABLED' &&
    !relatedEntitySnapshot.tableSchema &&
    !relatedEntitySnapshot.tableCatalog &&
    physicalStore?.complete === true &&
    physicalTable &&
    releasingTable &&
    releasingAssociation?.associationType === 'oneToOne' &&
    releasingAssociation.relatedEntity === sourceEntity.qualifiedName &&
    !releasingAssociation.mappedBy &&
    !releasingAssociation.crossDataStore &&
    !releasingAssociation.joinTable &&
    Boolean(releasingAssociation.joinColumnName) &&
    !releasingAssociation.composition &&
    !releasingAssociation.orphanRemoval &&
    !releasingAssociation.onDelete &&
    releasingColumn?.unique === true &&
    releasingColumn.primaryKey === false &&
    releasingUniqueBackingCount === 1 &&
    releasingForeignKeyCount === 1
  )
  const inverseRepairSupported = Boolean(
    relatedEntitySnapshot?.entityType === 'entity' &&
    relatedEntitySnapshot.moduleId === sourceEntity.moduleId &&
    relatedEntitySnapshot.storeName === sourceEntity.storeName &&
    !sourceAssociation.crossDataStore &&
    !sourceAssociation.mappedBy &&
    (
      sourceAssociation.associationType === 'manyToOne' ||
      sourceAssociation.associationType === 'oneToOne' ||
      (sourceAssociation.associationType === 'manyToMany' && sourceAssociation.joinTable)
    ),
  )
  const suggestedInverseName = sourceAssociation.associationType === 'manyToOne'
    ? `${sourceEntity.className.charAt(0).toLowerCase()}${sourceEntity.className.slice(1)}s`
    : `${sourceEntity.className.charAt(0).toLowerCase()}${sourceEntity.className.slice(1)}`
  const updateAssociation = (change: Partial<AssociationConfig>) => {
    onChange({ association: { ...association, ...change } })
  }

  return (
    <div className="mt-4 rounded-lg border border-cyan-500/20 bg-cyan-500/[0.04] p-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[10px] font-semibold uppercase tracking-wider text-cyan-200">
            Relationship semantics
          </div>
          <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
            Exact source edits preserve target, cardinality, ownership, join structure, custom arguments, and manual code.
          </p>
        </div>
        <span className="max-w-full break-words rounded border border-surface-border bg-black/15 px-2 py-1 font-mono text-[8px] text-gray-400">
          {sourceAssociation.associationType} · {sourceAssociation.relatedEntity}
        </span>
      </div>
      {semanticEditingDisabled ? (
        <div className="mt-3 rounded border border-amber-500/20 bg-amber-500/5 p-2 text-[9px] leading-relaxed text-amber-200/80">
          Cross-store references use an ID bridge instead of a JPA relationship. JPA fetch, cascade,
          composition, orphan-removal, and delete-policy controls are intentionally unavailable.
        </div>
      ) : (
        <>
          <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {checkedCardinalityEligible && (
              <Field label="Checked cardinality">
                <select
                  value={association.associationType}
                  onChange={event => {
                    const associationType = event.target.value as AssociationType
                    onChange({
                      mandatory: !sourceNullable,
                      unique: associationType === 'oneToOne',
                      association: {
                        ...association,
                        associationType,
                      },
                    })
                  }}
                  className="w-full min-w-0"
                  aria-label={`Checked cardinality for ${attribute.name}`}
                >
                  <option value="manyToOne">Many to one</option>
                  <option value="oneToOne">One to one</option>
                </select>
              </Field>
            )}
            <Field label="Ownership semantics">
              <select
                value={attribute.type}
                disabled={!compositionEligible}
                onChange={event => onChange({
                  type: event.target.value as AttributeType,
                })}
                className="w-full min-w-0"
                aria-label={`Ownership semantics for ${attribute.name}`}
              >
                <option value="association">Association</option>
                <option value="composition">Composition</option>
              </select>
            </Field>
            <Field label="Fetch mode">
              <select
                value={association.fetch}
                onChange={event => updateAssociation({ fetch: event.target.value as FetchType })}
                className="w-full min-w-0"
                aria-label={`Fetch mode for ${attribute.name}`}
              >
                <option value="lazy">Lazy</option>
                <option value="eager">Eager</option>
              </select>
            </Field>
            <Field label="Delete policy">
              <select
                value={association.onDelete ?? ''}
                onChange={event => updateAssociation({ onDelete: event.target.value || undefined })}
                className="w-full min-w-0"
                aria-label={`Delete policy for ${attribute.name}`}
              >
                <option value="">No Jmix policy</option>
                <option value="DENY">Deny</option>
                <option value="CASCADE">Cascade</option>
                <option value="UNLINK">Unlink</option>
              </select>
            </Field>
          </div>
          {nullabilityChangeCandidate && (
            <div className={`mt-3 rounded-lg border p-3 ${
              nullabilityChangeEligible
                ? 'border-sky-500/30 bg-sky-500/[0.06]'
                : 'border-amber-500/25 bg-amber-500/[0.05]'
            }`}>
              <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className={`text-[10px] font-semibold uppercase tracking-wider ${
                    nullabilityChangeEligible ? 'text-sky-200' : 'text-amber-200'
                  }`}>
                    {sourceNullable ? 'Contract optional relationship' : 'Expand required relationship'}
                  </div>
                  <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
                    {sourceNullable ? (
                      <>
                        Make <code>{sourceEntity.className}.{attribute.name}</code> mandatory only after
                        complete Liquibase history proves the exact nullable join column and foreign key.
                        Preview halts when existing rows contain nulls and rollback restores nullability.
                        An explicit collision-free join-column rename can travel in the same atomic plan.
                      </>
                    ) : (
                      <>
                        Allow <code>{sourceEntity.className}.{attribute.name}</code> to become optional by dropping
                        only the proven foreign-key NOT NULL constraint. Rollback restores it and fails safely if
                        new null references were introduced. A checked join-column rename can be applied and
                        reversed in the same change set.
                      </>
                    )}
                  </p>
                </div>
                <label className={`flex shrink-0 items-center gap-2 text-[10px] ${
                  nullabilityChangeEligible
                    ? 'cursor-pointer text-gray-300'
                    : 'cursor-not-allowed text-gray-600'
                }`}>
                  <input
                    type="checkbox"
                    disabled={!nullabilityChangeEligible}
                    checked={attribute.mandatory}
                    onChange={event => onChange({ mandatory: event.target.checked })}
                    aria-label={`Mandatory relationship ${attribute.name}`}
                  />
                  require relationship
                </label>
              </div>
              {!nullabilityChangeEligible && (
                <p className="mt-2 text-[9px] leading-relaxed text-amber-200/75">
                  Locked until the original cardinality is retained and a complete, unqualified managed schema
                  proves the exact non-key column nullability plus one foreign key. Partial schemas, drift, missing
                  constraints, database views, invalid/colliding destination columns, and combined cardinality
                  changes fail closed.
                </p>
              )}
            </div>
          )}
          {owningToOneWideningCandidate && !owningToOneWideningEligible && (
            <div className="mt-3 rounded border border-amber-500/25 bg-amber-500/[0.05] p-2 text-[9px] leading-relaxed text-amber-200/80">
              One-to-many reuse is locked because the physical schema does not prove exactly one named, single-column
              unique constraint or index for <code>{sourceAssociation.joinColumnName}</code>. Refresh complete
              Liquibase coverage or resolve competing constraints before widening this relationship.
            </div>
          )}
          {ownershipTransferCandidate && (
            <div className={`mt-3 rounded-lg border p-3 ${
              ownershipTransferEligible
                ? 'border-fuchsia-500/30 bg-fuchsia-500/[0.06]'
                : 'border-amber-500/25 bg-amber-500/[0.05]'
            }`}>
              <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className={`text-[10px] font-semibold uppercase tracking-wider ${
                    ownershipTransferEligible ? 'text-fuchsia-200' : 'text-amber-200'
                  }`}>
                    Transfer one-to-one ownership
                  </div>
                  <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
                    Move the foreign key from {relatedEntitySnapshot?.className}.
                    {releasingAttribute?.name} to {sourceEntity.className}.{attribute.name}. Both handwritten
                    sources, the data backfill, constraints, and reverse rollback are previewed atomically.
                  </p>
                </div>
                <label className={`flex shrink-0 items-center gap-2 text-[10px] ${
                  ownershipTransferEligible
                    ? 'cursor-pointer text-gray-300'
                    : 'cursor-not-allowed text-gray-600'
                }`}>
                  <input
                    type="checkbox"
                    disabled={!ownershipTransferEligible}
                    checked={association.ownershipTransfer === 'request'}
                    onChange={event => updateAssociation({
                      ownershipTransfer: event.target.checked ? 'request' : undefined,
                      ownershipJoinColumnName: event.target.checked
                        ? suggestedOwnershipColumn
                        : undefined,
                      generateInverse: false,
                    })}
                  />
                  transfer ownership
                </label>
              </div>
              {ownershipTransferEligible && association.ownershipTransfer === 'request' ? (
                <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                  <Field label={`New column in ${physicalTable?.name}`}>
                    <input
                      value={association.ownershipJoinColumnName || ''}
                      onChange={event => updateAssociation({
                        ownershipJoinColumnName: event.target.value || undefined,
                      })}
                      className="w-full min-w-0 font-mono"
                      placeholder={`${toDatabaseName(attribute.name)}_ID`}
                      aria-label={`New owning join column for ${attribute.name}`}
                    />
                    {!receiverColumnAvailable && (
                      <p className="mt-1 text-[8px] leading-relaxed text-amber-300">
                        This column already exists in the physical table. Choose a new portable column name.
                      </p>
                    )}
                  </Field>
                  <div className="min-w-0 rounded border border-fuchsia-500/20 bg-black/10 p-2 text-[9px] leading-relaxed text-fuchsia-100/70">
                    Forward migration adds and backfills the new unique FK before removing
                    <code> {releasingAssociation?.joinColumnName}</code>. Rollback recreates the exact former
                    constraint or index and restores the data in the opposite direction.
                  </div>
                </div>
              ) : !ownershipTransferEligible ? (
                <p className="mt-2 text-[9px] leading-relaxed text-amber-200/75">
                  Locked until both entities are writable in the same module/store and complete Liquibase history
                  proves one named unique backing plus one exact foreign key on the current owner. Composition,
                  orphan removal, delete policies, qualified tables, collisions, and partial schema evidence fail closed.
                </p>
              ) : null}
            </div>
          )}
          {inverseRepairSupported && (
            <div className="mt-3 rounded-lg border border-emerald-500/25 bg-emerald-500/[0.05] p-3">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="text-[10px] font-semibold uppercase tracking-wider text-emerald-200">
                    Established inverse side
                  </div>
                  <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
                    Add or repair the matching property in {relatedEntitySnapshot?.className}. The indexed owning
                    mapping must remain exact; both handwritten sources are checked and previewed as one revision.
                  </p>
                </div>
                <label className="flex shrink-0 cursor-pointer items-center gap-2 text-[10px] text-gray-300">
                  <input
                    type="checkbox"
                    checked={Boolean(association.generateInverse)}
                    onChange={event => updateAssociation({
                      generateInverse: event.target.checked,
                      inverseAttributeName: event.target.checked
                        ? association.inverseAttributeName || suggestedInverseName
                        : association.inverseAttributeName,
                    })}
                  />
                  add or repair inverse
                </label>
              </div>
              {association.generateInverse && (
                <div className="mt-3 grid min-w-0 gap-3 sm:grid-cols-2">
                  <Field label={`Property in ${relatedEntitySnapshot?.className}`}>
                    <input
                      value={association.inverseAttributeName || ''}
                      onChange={event => updateAssociation({
                        inverseAttributeName: event.target.value || undefined,
                      })}
                      placeholder={suggestedInverseName}
                      className="w-full min-w-0 font-mono"
                      aria-label={`Established inverse attribute for ${attribute.name}`}
                    />
                  </Field>
                  <div className="min-w-0 rounded border border-emerald-500/15 bg-black/10 p-2 text-[9px] leading-relaxed text-emerald-100/70">
                    The inverse uses <code>mappedBy=&quot;{attribute.name}&quot;</code>. Existing matching mappings
                    are idempotent; collisions, stale sources, cross-store targets, and module cycles fail closed.
                  </div>
                </div>
              )}
            </div>
          )}
          <div className="mt-3 border-t border-cyan-500/15 pt-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-[9px] font-medium uppercase tracking-wider text-gray-500">
                Cascade operations
              </div>
              {orphanRemovalEligible && (
                <label className="flex cursor-pointer items-center gap-1.5 text-[9px] text-gray-400">
                  <input
                    type="checkbox"
                    checked={association.orphanRemoval}
                    onChange={event => updateAssociation({ orphanRemoval: event.target.checked })}
                  />
                  orphan removal
                </label>
              )}
            </div>
            <div className="mt-2 flex flex-wrap gap-1.5">
              {(['all', 'persist', 'merge', 'remove', 'refresh', 'detach'] as CascadeType[]).map(cascade => {
                const active = association.cascade.includes(cascade)
                return (
                  <button
                    key={cascade}
                    type="button"
                    onClick={() => updateAssociation({
                      cascade: active
                        ? association.cascade.filter(candidate => candidate !== cascade)
                        : [...association.cascade, cascade],
                    })}
                    className={`rounded border px-2 py-1 text-[9px] transition-colors ${
                      active
                        ? 'border-cyan-500/40 bg-cyan-500/20 text-cyan-100'
                        : 'border-surface-border bg-black/10 text-gray-500 hover:text-gray-300'
                    }`}
                  >
                    {cascade}
                  </button>
                )
              })}
            </div>
          </div>
          <p className="mt-3 text-[9px] leading-relaxed text-gray-600">
            Fetch, cascade, composition, orphan removal, and delete policy are source-only. Checked to-one
            cardinality also coordinates an exact existing inverse below; other structural changes remain blocked
            until target, usages, data migration, and rollback can be reviewed together.
          </p>
          {exactInverseCardinality && (
            <div className="mt-3 rounded border border-violet-500/20 bg-violet-500/[0.05] p-2 text-[9px] leading-relaxed text-violet-200/80">
              Bidirectional choreography will update{' '}
              <code>{relatedEntitySnapshot?.className}.{exactInverseCardinality.name}</code> in the same
              revision-bound plan, including its collection-or-scalar declaration. No independent inverse edit is
              required.
            </div>
          )}
          {inverseCardinalityCandidates.length > 1 && (
            <div className="mt-3 rounded border border-amber-500/25 bg-amber-500/[0.05] p-2 text-[9px] leading-relaxed text-amber-200/80">
              Cardinality is locked because multiple inverse properties declare{' '}
              <code>mappedBy=&quot;{attribute.name}&quot;</code>. Resolve the handwritten ambiguity first.
            </div>
          )}
          {owningToOneUpgradeEligible && association.associationType === 'oneToOne' && (
            <div className="mt-3 rounded border border-emerald-500/20 bg-emerald-500/5 p-2 text-[9px] leading-relaxed text-emerald-200/80">
              Checked narrowing adds a duplicate-data precondition and a deterministic unique constraint with reverse
              rollback. The owning property remains a to-one; an exact inverse collection becomes a to-one property
              in the same Java/Kotlin source transaction.
            </div>
          )}
          {owningToOneWideningEligible && association.associationType === 'manyToOne' && (
            <div className="mt-3 rounded border border-sky-500/20 bg-sky-500/5 p-2 text-[9px] leading-relaxed text-sky-200/80">
              Checked widening changes only the owning annotation and removes the exact named unique backing. Rollback
              restores that same constraint or index and will stop if new duplicate references make uniqueness
              invalid. An exact inverse to-one becomes its matching collection atomically.
            </div>
          )}
        </>
      )}
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
  importMode,
  selectedTables,
  planning,
  profileEnabled,
  profileId,
  profileLabel,
  onProfileEnabledChange,
  onProfileIdChange,
  onProfileLabelChange,
  onToggleTable,
  onPlan,
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
  importMode: boolean
  selectedTables: DatabaseTableReference[]
  planning: boolean
  profileEnabled: boolean
  profileId: string
  profileLabel: string
  onProfileEnabledChange: (value: boolean) => void
  onProfileIdChange: (value: string) => void
  onProfileLabelChange: (value: string) => void
  onToggleTable: (table: DatabaseTableReference) => void
  onPlan: () => void
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
            <span className="text-xs font-semibold text-cyan-100">
              {importMode ? 'Database-first entity model' : 'Live database browser'}
            </span>
            <span className="max-w-full truncate rounded bg-surface-lighter px-2 py-0.5 text-[9px] text-gray-400">
              {browse.database?.name} {browse.database?.version}
            </span>
            <span className="rounded bg-cyan-500/10 px-2 py-0.5 text-[9px] text-cyan-200/70">
              {tableCount} tables · {viewCount} views
            </span>
          </div>
          <p className="mt-2 max-w-4xl text-[10px] leading-relaxed text-gray-500">
            {importMode
              ? 'Select root tables or views. The backend follows foreign keys, reuses already mapped entities, recognizes strict join tables, and plans the complete source change atomically.'
              : 'Inspect any table safely. Attribute import unlocks only when the backend proves that the selected catalog, schema, store, table, and entity mapping are the same target.'}
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
            const selected = selectedTables.some(candidate =>
              databaseTableKey(candidate) === databaseTableKey(table))
            const nameMatches = table.name.toLowerCase() === mappedName
            const explicitSchemaMatches = !mappedTableSchema ||
              table.schema?.toLowerCase() === mappedTableSchema.toLowerCase()
            const explicitCatalogMatches = !mappedTableCatalog ||
              table.catalog?.toLowerCase() === mappedTableCatalog.toLowerCase()
            const mappedCandidate = !importMode &&
              nameMatches && explicitSchemaMatches && explicitCatalogMatches
            const qualifiedName = [table.catalog, table.schema, table.name].filter(Boolean).join('.')
            return (
              <article
                key={`${table.catalog ?? ''}:${table.schema ?? ''}:${table.name}:${table.type}`}
                className={`min-w-0 rounded-lg border p-3 ${
                  selected
                    ? 'border-jmix-400/50 bg-jmix-500/[0.1]'
                    : mappedCandidate
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
                      {selected && <DatabaseStatus label="selected" tone="cyan" />}
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
                  onClick={() => importMode ? onToggleTable(table) : onInspect(table)}
                  disabled={busy}
                  aria-pressed={importMode ? selected : undefined}
                  className={`mt-3 w-full rounded border px-2 py-1.5 text-[10px] disabled:opacity-50 ${
                    selected
                      ? 'border-jmix-400/40 bg-jmix-500/20 text-jmix-100 hover:bg-jmix-500/30'
                      : 'border-cyan-500/25 bg-cyan-500/10 text-cyan-200 hover:bg-cyan-500/20'
                  }`}
                >
                  {importMode
                    ? selected ? '✓ Included as import root' : '+ Include in entity model'
                    : mappedCandidate ? 'Compare and import safely' : 'Inspect metadata'}
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

      {importMode && (
        <div className="grid min-w-0 gap-2 border-t border-cyan-500/15 bg-violet-500/[0.035] px-3 py-3 sm:grid-cols-2 sm:px-4 2xl:grid-cols-[auto_minmax(10rem,0.8fr)_minmax(14rem,1.4fr)]">
          <label className="flex min-w-0 items-center gap-2 self-end rounded border border-violet-500/20 px-3 py-2 text-[10px] text-violet-100/80">
            <input
              type="checkbox"
              checked={profileEnabled}
              onChange={event => onProfileEnabledChange(event.target.checked)}
            />
            Track as repeatable mapping
          </label>
          <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600">
            Mapping ID
            <input
              value={profileId}
              disabled={!profileEnabled}
              onChange={event => onProfileIdChange(
                event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-').slice(0, 64),
              )}
              placeholder="loan-accounts"
              className="mt-1 w-full min-w-0 font-mono"
            />
          </label>
          <label className="min-w-0 text-[9px] uppercase tracking-wider text-gray-600 sm:col-span-2 2xl:col-span-1">
            Team-facing label
            <input
              value={profileLabel}
              disabled={!profileEnabled}
              onChange={event => onProfileLabelChange(event.target.value.slice(0, 120))}
              placeholder="Loan accounts database model"
              className="mt-1 w-full min-w-0"
            />
          </label>
        </div>
      )}

      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 border-t border-cyan-500/15 bg-black/10 px-3 py-2 text-[9px] text-gray-600 sm:px-4">
        <span className="min-w-0 break-all">
          Active catalog {browse.activeCatalog || 'driver default'} · URL fingerprint {browse.database?.urlFingerprint}
        </span>
        {importMode ? (
          <div className="flex flex-wrap items-center justify-end gap-2">
            <span>{selectedTables.length} import root{selectedTables.length === 1 ? '' : 's'}</span>
            <button
              type="button"
              onClick={onPlan}
              disabled={
                planning ||
                selectedTables.length === 0 ||
                (profileEnabled && (!/^[a-z][a-z0-9-]{2,63}$/.test(profileId) || !profileLabel.trim()))
              }
              className="rounded bg-jmix-500 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
            >
              {planning ? 'Resolving dependencies…' : 'Plan complete entity model →'}
            </button>
          </div>
        ) : (
          <span>{browse.schemas.length} schemas discovered</span>
        )}
      </div>
    </section>
  )
}

function DatabaseEntityImportPanel({
  busy,
  plan,
  preview,
  identifierOverrides,
  onIdentifierToggle,
  onReplan,
  onPreview,
  onApply,
  onClose,
}: {
  busy: boolean
  plan: DatabaseEntityImportPlanResponse | null
  preview: WorkspaceChangePreviewResponse | null
  identifierOverrides: Record<string, string[]>
  onIdentifierToggle: (table: DatabaseTableReference, column: string) => void
  onReplan: () => void
  onPreview: () => void
  onApply: () => void
  onClose: () => void
}) {
  if (busy && !plan) {
    return (
      <section className="mb-4 min-w-0 rounded-xl border border-jmix-500/25 bg-jmix-500/[0.06] p-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className="h-3 w-3 shrink-0 animate-pulse rounded-full bg-jmix-300" />
          <div className="min-w-0">
            <div className="text-xs font-medium text-jmix-100">Resolving the database entity graph</div>
            <p className="mt-1 text-[10px] leading-relaxed text-gray-500">
              Reading primary keys, ordered composite foreign keys, dependency closure, views, and pure join tables.
            </p>
          </div>
        </div>
      </section>
    )
  }
  if (!plan) return null
  const generatedEntities = plan.tables.filter(table => table.generated).length
  const existingEntities = plan.tables.filter(table => table.status === 'EXISTING_ENTITY').length
  const joinTables = plan.tables.filter(table => table.status === 'JOIN_TABLE').length
  const blocked = plan.tables.filter(table => table.status === 'BLOCKED').length
  return (
    <section className="mb-4 min-w-0 overflow-hidden rounded-xl border border-jmix-500/30 bg-gradient-to-br from-jmix-500/[0.09] to-surface">
      <div className="relative min-w-0 border-b border-jmix-500/20 p-3 pr-10 sm:p-4 sm:pr-12">
        <div className="min-w-0">
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <span className="text-xs font-semibold text-jmix-100">Reviewed database entity plan</span>
            <DatabaseStatus
              label={plan.ready ? 'ready for atomic preview' : `${blocked} blocked`}
              tone={plan.ready ? 'cyan' : 'warning'}
            />
            <span className="rounded bg-surface-lighter px-2 py-0.5 text-[9px] text-gray-400">
              {generatedEntities} generated · {existingEntities} reused · {joinTables} join tables
            </span>
          </div>
          <p className="mt-2 max-w-4xl text-[10px] leading-relaxed text-gray-500">
            Every generated type is DDL-disabled because these tables already exist. Preview and apply re-read the
            live schema and reject stale metadata, source collisions, or a changed destination.
          </p>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close database entity plan"
          className="absolute right-3 top-3 rounded p-1 text-xs text-gray-500 hover:bg-white/5 hover:text-gray-200 sm:right-4 sm:top-4"
        >
          ✕
        </button>
      </div>

      {plan.profileDrift && (
        <div className={`border-b px-3 py-3 sm:px-4 ${
          plan.profileDrift.matchesBaseline
            ? 'border-emerald-500/20 bg-emerald-500/[0.05]'
            : 'border-amber-500/20 bg-amber-500/[0.06]'
        }`}>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <span className={`text-[10px] font-medium ${
              plan.profileDrift.matchesBaseline ? 'text-emerald-100' : 'text-amber-100'
            }`}>
              {plan.profileDrift.matchesBaseline
                ? 'Saved mapping matches the live schema'
                : 'Live schema or mapping decisions drifted from the saved baseline'}
            </span>
            <span className="rounded bg-black/15 px-2 py-0.5 font-mono text-[8px] text-gray-500">
              {plan.profileDrift.profileId}
            </span>
          </div>
          {!plan.profileDrift.matchesBaseline && (
            <div className="mt-2 flex min-w-0 flex-wrap gap-1.5 text-[9px] text-amber-200/70">
              {plan.profileDrift.requestChanged && <span>mapping request changed</span>}
              {plan.profileDrift.addedTables.map(table => <span key={`added-${table}`}>+ {table}</span>)}
              {plan.profileDrift.removedTables.map(table => <span key={`removed-${table}`}>− {table}</span>)}
              {plan.profileDrift.changedTables.map(table => <span key={`changed-${table}`}>∆ {table}</span>)}
            </div>
          )}
        </div>
      )}

      {!plan.accepted && (
        <div className="border-b border-red-500/20 bg-red-500/[0.07] px-3 py-3 sm:px-4">
          {plan.issues.map(issue => (
            <div key={`${issue.code}-${issue.message}`} className="break-words text-[10px] text-red-200/80">
              {issue.message}
            </div>
          ))}
        </div>
      )}

      <div className="grid min-w-0 gap-2 p-3 sm:grid-cols-2 sm:p-4 2xl:grid-cols-3">
        {plan.tables.map(tablePlan => {
          const table = tablePlan.table
          const tableKey = databaseTableKey(table)
          const selectedIdentifiers = identifierOverrides[tableKey] ?? []
          const needsViewIdentifier = table.type.toUpperCase() === 'VIEW' &&
            tablePlan.issues.some(issue => issue.code === 'JVW-DB-IMPORT-IDENTIFIER-MISSING')
          return (
            <article
              key={tableKey}
              className={`min-w-0 rounded-lg border p-3 ${
                tablePlan.status === 'BLOCKED'
                  ? 'border-amber-500/30 bg-amber-500/[0.06]'
                  : 'border-surface-border bg-surface/75'
              }`}
            >
              <div className="flex min-w-0 flex-wrap items-center gap-1.5">
                <span className="max-w-full break-all font-mono text-[10px] text-gray-200">
                  {table.name}
                </span>
                <DatabaseStatus
                  label={databaseImportStatusLabel(tablePlan.status)}
                  tone={tablePlan.status === 'BLOCKED'
                    ? 'warning'
                    : tablePlan.status === 'EXISTING_ENTITY'
                      ? 'neutral'
                      : 'cyan'}
                />
                {tablePlan.selectedByUser && <DatabaseStatus label="root" tone="neutral" />}
              </div>
              <div className="mt-1 break-all font-mono text-[9px] text-gray-600">
                {[table.catalog, table.schema].filter(Boolean).join('.') || 'connection default'}
              </div>
              {tablePlan.entityQualifiedName && (
                <div className="mt-2 break-all font-mono text-[9px] text-jmix-200/75">
                  {tablePlan.entityQualifiedName}
                </div>
              )}
              {tablePlan.compositeIdClassName && (
                <div className="mt-1 break-all text-[9px] text-violet-300/75">
                  Composite identity · {tablePlan.compositeIdClassName}
                </div>
              )}
              {tablePlan.requiredBy.length > 0 && (
                <div className="mt-2 break-words text-[9px] text-gray-500">
                  Required by {tablePlan.requiredBy.join(', ')}
                </div>
              )}
              {tablePlan.issues.map(issue => (
                <div
                  key={`${issue.code}-${issue.message}`}
                  className="mt-2 break-words rounded border border-amber-500/15 bg-amber-500/5 p-2 text-[9px] leading-relaxed text-amber-200/75"
                >
                  {issue.message}
                </div>
              ))}
              {needsViewIdentifier && (
                <div className="mt-3">
                  <div className="text-[9px] font-medium uppercase tracking-wider text-amber-200/80">
                    Stable unique identifier columns
                  </div>
                  <div className="mt-2 flex flex-wrap gap-1">
                    {table.columns.map(column => {
                      const selected = selectedIdentifiers.includes(column.name)
                      return (
                        <button
                          key={column.name}
                          type="button"
                          aria-pressed={selected}
                          onClick={() => onIdentifierToggle(table, column.name)}
                          className={`rounded border px-2 py-1 font-mono text-[9px] ${
                            selected
                              ? 'border-amber-300/45 bg-amber-500/15 text-amber-100'
                              : 'border-surface-border text-gray-500 hover:text-gray-300'
                          }`}
                        >
                          {selected ? '✓ ' : ''}{column.name}
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}
            </article>
          )
        })}
      </div>

      {preview && (
        <div className={`border-t px-3 py-3 sm:px-4 ${
          preview.accepted
            ? 'border-emerald-500/20 bg-emerald-500/[0.05]'
            : 'border-red-500/20 bg-red-500/[0.05]'
        }`}>
          <div className="text-[10px] font-medium text-gray-200">{preview.label}</div>
          {preview.files.length > 0 && (
            <div className="mt-2 grid min-w-0 gap-1 sm:grid-cols-2 2xl:grid-cols-3">
              {preview.files.map(file => (
                <div
                  key={file.relativePath}
                  title={file.relativePath}
                  className="min-w-0 truncate rounded border border-emerald-500/15 bg-black/15 px-2 py-1.5 font-mono text-[9px] text-emerald-100/70"
                >
                  {file.mode} · {file.relativePath}
                </div>
              ))}
            </div>
          )}
          {preview.issues.map(issue => (
            <div key={`${issue.code}-${issue.message}`} className="mt-2 break-words text-[9px] text-red-200/80">
              {issue.message}
            </div>
          ))}
        </div>
      )}

      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2 border-t border-jmix-500/20 bg-black/10 px-3 py-2.5 sm:px-4">
        <span className="min-w-0 break-all font-mono text-[9px] text-gray-600">
          Live snapshot {plan.snapshotDigest?.slice(0, 16) ?? 'unavailable'}
        </span>
        <div className="flex flex-wrap justify-end gap-2">
          {!plan.ready && (
            <button
              type="button"
              onClick={onReplan}
              disabled={busy}
              className="rounded border border-amber-500/30 bg-amber-500/10 px-3 py-1.5 text-[10px] text-amber-100 hover:bg-amber-500/20 disabled:opacity-50"
            >
              {busy ? 'Re-reading schema…' : 'Replan reviewed decisions'}
            </button>
          )}
          {plan.ready && !preview?.accepted && (
            <button
              type="button"
              onClick={onPreview}
              disabled={busy}
              className="rounded bg-jmix-500 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-jmix-600 disabled:opacity-50"
            >
              {busy ? 'Building preview…' : 'Preview all generated files'}
            </button>
          )}
          {plan.ready && preview?.accepted && (
            <button
              type="button"
              onClick={onApply}
              disabled={busy || !preview.planDigest}
              className="rounded bg-emerald-600 px-3 py-1.5 text-[10px] font-medium text-white hover:bg-emerald-500 disabled:opacity-50"
            >
              {busy ? 'Applying atomically…' : 'Apply complete entity model'}
            </button>
          )}
        </div>
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

function databaseTableKey(table: DatabaseTableReference): string {
  return [table.catalog, table.schema, table.name].filter(Boolean).join('.')
}

function databaseProfileSlug(value: string): string {
  const slug = value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 64)
  if (/^[a-z][a-z0-9-]{2,63}$/.test(slug)) return slug
  return `db-${slug || 'model'}`.slice(0, 64)
}

function databaseProfileTitle(value: string): string {
  return value
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ') || 'Database'
}

function databaseImportStatusLabel(
  status: DatabaseEntityImportPlanResponse['tables'][number]['status'],
): string {
  switch (status) {
    case 'COMPOSITE_KEY': return 'composite identity'
    case 'JOIN_TABLE': return 'many-to-many join'
    case 'EXISTING_ENTITY': return 'existing entity'
    case 'VIEW': return 'database view'
    case 'BLOCKED': return 'decision required'
    default: return 'new entity'
  }
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
    embeddedAttributeOverrides: [],
    embeddedAssociationOverrides: [],
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
