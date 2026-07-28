// ─── Entity Types ────────────────────────────────────────────────────────────

export type EntityType = 'entity' | 'mappedSuperclass' | 'embeddable' | 'dto' | 'enum'
export type IdType = 'uuid' | 'long' | 'integer' | 'string' | 'embedded'
export type IdGeneration = 'jmixGenerated' | 'identity' | 'sequence' | 'assigned'
export type InheritanceStrategy = 'singleTable' | 'joined' | 'tablePerClass'
export type AttributeType =
  | 'string' | 'integer' | 'long' | 'double' | 'bigDecimal' | 'boolean'
  | 'date' | 'localDate' | 'localDateTime' | 'localTime' | 'offsetDateTime'
  | 'uuid' | 'byteArray' | 'enum' | 'association' | 'composition' | 'embedded'
export type AssociationType = 'manyToOne' | 'oneToMany' | 'manyToMany' | 'oneToOne'
export type FetchType = 'lazy' | 'eager'
export type CascadeType = 'all' | 'persist' | 'merge' | 'remove' | 'refresh' | 'detach'
export type ValidationType =
  | 'notNull' | 'notEmpty' | 'notBlank' | 'size' | 'min' | 'max'
  | 'decimalMin' | 'decimalMax' | 'pattern' | 'email' | 'past' | 'future'
  | 'positive' | 'negative' | 'digits' | 'assertTrue'
export type TraitType =
  | 'uuid' | 'softDelete' | 'hasTenantId' | 'hasVersion'
  | 'createdBy' | 'createdDate' | 'updatedBy' | 'updatedDate'
  | 'auditable' | 'standardEntity'
export type LifecycleCallback =
  | 'prePersist' | 'postPersist' | 'preUpdate' | 'postUpdate'
  | 'preRemove' | 'postRemove' | 'postLoad'

export interface AttributeModel {
  name: string
  type: AttributeType
  columnName?: string
  mandatory: boolean
  unique: boolean
  length?: number
  precision?: number
  scale?: number
  comment?: string
  localizedCaption?: string
  defaultValue?: string
  transientFlag: boolean
  systemLevel: boolean
  association?: AssociationConfig
  embeddedClass?: string
  enumClass?: string
  validations: ValidationModel[]
  annotations: CustomAnnotation[]
  inBaseFetchPlan: boolean
}

export interface AssociationConfig {
  associationType: AssociationType
  relatedEntity: string
  mappedBy?: string
  joinColumnName?: string
  joinTable?: { name: string; joinColumnName: string; inverseJoinColumnName: string }
  cascade: CascadeType[]
  fetch: FetchType
  orphanRemoval: boolean
  onDelete?: string
}

export interface ValidationModel {
  type: ValidationType
  value?: string
  value2?: string
  message?: string
}

export interface CustomAnnotation {
  name: string
  importPath?: string
  parameters: Record<string, string>
}

export interface IndexModel {
  name: string
  columns: string[]
  unique: boolean
}

export interface EntityModel {
  className: string
  packageName: string
  tableName: string
  entityType: EntityType
  id: {
    type: IdType
    generation: IdGeneration
    columnName: string
    length?: number
    sequenceName?: string
  }
  inheritance?: {
    strategy: InheritanceStrategy
    discriminatorColumn?: string
    discriminatorType: string
    discriminatorValue?: string
  }
  traits: TraitType[]
  attributes: AttributeModel[]
  indexes: IndexModel[]
  uniqueConstraints: { name: string; columns: string[] }[]
  instanceNamePattern?: string
  comment?: string
  ddlGeneration: { enabled: boolean }
  softDelete?: { enabled: boolean }
  multitenancy?: { enabled: boolean }
  dataRepository?: { enabled: boolean; interfaceName?: string }
  lifecycleCallbacks: LifecycleCallback[]
  entityListeners: string[]
  enumConfig?: { idType: 'string' | 'integer'; values: { name: string; storedValue: string; caption?: string }[] }
  dtoConfig?: { readOnly: boolean }
  extendsClass?: string
  implementsInterfaces: string[]
  annotations: CustomAnnotation[]
}

// ─── View Types ──────────────────────────────────────────────────────────────

export type ViewType = 'standard' | 'listView' | 'detailView' | 'blankView' | 'fragment' | 'loginView' | 'mainView'
export type ComponentType =
  | 'vbox' | 'hbox' | 'formLayout' | 'gridLayout' | 'split' | 'tabSheet' | 'accordion'
  | 'scroller' | 'details' | 'card' | 'sidePanelLayout' | 'flexLayout'
  | 'textField' | 'textArea' | 'integerField' | 'numberField' | 'bigDecimalField'
  | 'checkbox' | 'datePicker' | 'dateTimePicker' | 'timePicker' | 'comboBox'
  | 'entityComboBox' | 'entityPicker' | 'valuePicker' | 'multiSelectComboBox'
  | 'radioButtonGroup' | 'checkboxGroup' | 'passwordField' | 'emailField'
  | 'codeEditor' | 'richTextEditor' | 'fileUploadField' | 'switch' | 'select'
  | 'dataGrid' | 'treeDataGrid' | 'virtualList'
  | 'genericFilter' | 'propertyFilter' | 'simplePagination'
  | 'button' | 'label' | 'span' | 'h1' | 'h2' | 'h3' | 'image' | 'icon' | 'html'

export interface ComponentModel {
  id: string
  type: ComponentType
  properties: Record<string, any>
  children: ComponentModel[]
  dataBinding?: string
  propertyBinding?: string
  actions: ActionModel[]
  columns: ColumnModel[]
  cssClasses: string[]
  visible: boolean
  enabled: boolean
  width?: string
  height?: string
}

export interface ActionModel {
  id: string
  type: string
  caption?: string
  icon?: string
}

export interface ColumnModel {
  property: string
  caption?: string
  sortable: boolean
  resizable: boolean
  width?: string
}

export interface DataContainerModel {
  id: string
  type: 'instance' | 'collection' | 'keyValue'
  entityClass: string
  fetchPlan?: { name: string; properties: { name: string }[] }
  loader?: { id: string; query?: string; cacheable: boolean }
}

export interface ViewModel {
  viewName: string
  packageName: string
  viewType: ViewType
  entityClass?: string
  layout: ComponentModel
  dataContainers: DataContainerModel[]
  facets: { type: string; properties: Record<string, any> }[]
  actions: { id: string; type: string; caption?: string }[]
  menuEntry?: { id: string; caption: string; parentId?: string; icon?: string }
  messages: Record<string, string>
}

// ─── Role Types ──────────────────────────────────────────────────────────────

export interface RoleModel {
  name: string
  code: string
  description?: string
  scope: 'resource' | 'rowLevel'
  entityPolicies: { entityClass: string; actions: string[]; allActions: boolean }[]
  menuPolicies: { menuId: string }[]
  screenPolicies: { screenId: string }[]
  specificPolicies: { permission: string }[]
  rowLevelPolicies: { entityClass: string; type: string; action: string; whereClause?: string }[]
}

// ─── Migration Types ─────────────────────────────────────────────────────────

export interface MigrationModel {
  changelogId: string
  author: string
  changes: ChangeSetModel[]
}

export interface ChangeSetModel {
  id: string
  author: string
  comment?: string
  changes: any[]
}

// ─── Project Config ──────────────────────────────────────────────────────────

export interface ProjectConfig {
  projectRoot: string
  basePackage: string
  sourceRoot: string
  resourceRoot: string
  jmixVersion: string
  databaseType: string
}

// ─── Connected Application Graph ─────────────────────────────────────────────

export interface GraphSourceLocator {
  relativePath: string
  symbol?: string
  line?: number
  column?: number
  revisionFingerprint: string
}

export interface GraphDiagnostic {
  id: string
  severity: 'INFO' | 'WARNING' | 'ERROR' | 'BLOCKING'
  category: string
  reasonCode: string
  message: string
  nextStep?: string
  sourceLocator?: GraphSourceLocator
}

export interface GraphArtifact {
  id: string
  kind: string
  semanticKey: string
  owner: {
    buildId: string
    moduleId: string
    sourceSetId?: string
  }
  sourceLocator: GraphSourceLocator
  origin: string
  fingerprint: string
  displayName: string
  summary?: string
  diagnostics: GraphDiagnostic[]
}

export interface GraphRelationship {
  sourceArtifactId: string
  targetArtifactId?: string
  type: string
  sourceLocator: GraphSourceLocator
  diagnostic?: GraphDiagnostic
}

export interface ApplicationGraphResponse {
  artifacts: GraphArtifact[]
  relationships: GraphRelationship[]
  diagnostics: GraphDiagnostic[]
  summary: {
    artifactCount: number
    relationshipCount: number
    diagnosticCount: number
    unresolvedRelationshipCount: number
    countsByKind: Record<string, number>
  }
  scannedFiles: number
  candidateFiles: number
  excludedFiles: number
  excludedBytes: number
  reusedFiles: number
  changedFiles: number
  cacheHit: boolean
  durationMillis: number
  snapshotDigest: string
  error?: string
}

export interface SourceNavigationResponse {
  success: boolean
  errorCode?: string
  message: string
}

export type WorkspaceFileChangeMode = 'CREATE' | 'MODIFY'

export interface WorkspaceTextEdit {
  startOffset: number
  endOffset: number
  expectedText: string
  replacement: string
}

export interface WorkspaceFileChange {
  relativePath: string
  mode: WorkspaceFileChangeMode
  baseRevisionFingerprint?: string
  edits: WorkspaceTextEdit[]
  createContent?: string
}

export interface WorkspaceChangeSet {
  id: string
  label: string
  files: WorkspaceFileChange[]
}

export interface WorkspaceChangeIssue {
  code: string
  message: string
  relativePath?: string
}

export interface WorkspaceChangeFilePreview {
  relativePath: string
  mode: WorkspaceFileChangeMode
  beforeFingerprint?: string
  afterFingerprint: string
  originalContent?: string
  resultContent: string
  appliedEditCount: number
}

export interface WorkspaceChangePreviewResponse {
  accepted: boolean
  changeSetId: string
  label: string
  planDigest?: string
  files: WorkspaceChangeFilePreview[]
  issues: WorkspaceChangeIssue[]
}

export interface WorkspaceChangeApplyResponse {
  success: boolean
  changeSetId: string
  planDigest?: string
  filesChanged: string[]
  issues: WorkspaceChangeIssue[]
}

// ─── Existing FlowUI round-trip workspace ───────────────────────────────────

export interface FlowUiAttributeSnapshot {
  name: string
  value: string
  rawValue: string
  sourceStart: number
  sourceEnd: number
  valueStart: number
  valueEnd: number
  quote: string
}

export interface FlowUiElementSnapshot {
  key: string
  tagName: string
  localTag: string
  id?: string
  parentKey?: string
  childKeys: string[]
  sourceStart: number
  startTagEnd: number
  endTagStart: number
  sourceEnd: number
  selfClosing: boolean
  attributes: FlowUiAttributeSnapshot[]
  directText?: string
  directTextStart?: number
  directTextEnd?: number
  directTextCdata: boolean
}

export interface FlowUiDescriptorSnapshot {
  relativePath: string
  revisionFingerprint: string
  viewId: string
  rootKey: string
  sourceText: string
  elements: FlowUiElementSnapshot[]
}

export interface FlowUiWorkspaceResponse {
  accepted: boolean
  document?: FlowUiDescriptorSnapshot
  contextArtifacts: GraphArtifact[]
  contextRelationships: GraphRelationship[]
  issues: WorkspaceChangeIssue[]
  dataModel?: FlowUiDataWorkspaceSnapshot
}

export interface FlowUiPropertyChangeRequest {
  sourceLocator: GraphSourceLocator
  elementKey: string
  propertyName: string
  value: string
}

export type FlowUiStructureOperation = 'INSERT_CHILD' | 'DELETE' | 'MOVE_UP' | 'MOVE_DOWN'

export interface FlowUiStructureChangeRequest {
  sourceLocator: GraphSourceLocator
  operation: FlowUiStructureOperation
  elementKey?: string
  parentKey?: string
  tagName?: string
  attributes?: Record<string, string>
}

export interface FlowUiDirectTextChangeRequest {
  sourceLocator: GraphSourceLocator
  elementKey: string
  value: string
}

export interface FlowUiDataContainerSnapshot {
  elementKey: string
  id: string
  kind: string
  entityClass?: string
  property?: string
  fetchPlan?: string
  fetchPlanElementKey?: string
  loaderElementKey?: string
  loaderId?: string
  queryElementKey?: string
  query?: string
}

export interface FlowUiComponentBindingSnapshot {
  elementKey: string
  componentId?: string
  componentTag: string
  containerId: string
  property?: string
}

export interface FlowUiEntityFieldSnapshot {
  artifactId: string
  entitySemanticKey: string
  name: string
  type?: string
  sourceLocator: GraphSourceLocator
}

export interface FlowUiQueryParameterSnapshot {
  queryElementKey: string
  name: string
}

export interface FlowUiDataWorkspaceSnapshot {
  containers: FlowUiDataContainerSnapshot[]
  bindings: FlowUiComponentBindingSnapshot[]
  entityFields: FlowUiEntityFieldSnapshot[]
  queryParameters: FlowUiQueryParameterSnapshot[]
}

// ─── CRUD Options ────────────────────────────────────────────────────────────

export interface CrudOptions {
  generateMigration: boolean
  generateDataRepository: boolean
  generateFetchPlan: boolean
  listViewType: 'dataGrid' | 'treeDataGrid' | 'virtualList'
  detailViewMode: 'form' | 'tabbed' | 'sidePanel'
  includeFilter: boolean
  includePagination: boolean
  includeActions: boolean
  menuParentId?: string
  menuIcon?: string
  roleCode?: string
  dbType: string
}

// ─── Generation Result ───────────────────────────────────────────────────────

export interface GenerationResult {
  success: boolean
  filesWritten: string[]
  errors: string[]
}
