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

export type SecurityRoleKind = 'RESOURCE' | 'ROW_LEVEL'
export type SecurityPolicyEffect = 'GRANT' | 'RESTRICT' | 'DENY' | 'UNKNOWN'
export type SecuritySurfaceKind = 'MENU' | 'VIEW' | 'ENTITY' | 'ATTRIBUTE' | 'REST' | 'COMPONENT'

export interface SecurityRoleSnapshot {
  id: string
  className: string
  name: string
  code: string
  kind: SecurityRoleKind
  scopes: string[]
  moduleId: string
  policyIds: string[]
  inheritedRoleIds: string[]
  unresolvedBaseRoleCount: number
  sourceLocator: GraphSourceLocator
}

export interface SecurityPolicySnapshot {
  id: string
  roleId: string
  type: string
  effect: SecurityPolicyEffect
  actions: string[]
  resourceExpressions: string[]
  targetArtifactIds: string[]
  wildcard: boolean
  condition?: string
  sourceLocator: GraphSourceLocator
}

export interface SecuritySurfaceSnapshot {
  artifactId: string
  kind: SecuritySurfaceKind
  displayName: string
  semanticKey: string
  moduleId: string
  grantingRoleIds: string[]
  restrictingRoleIds: string[]
  sourceLocator: GraphSourceLocator
}

export interface SecurityMenuRouteSnapshot {
  menuArtifactId: string
  viewArtifactId?: string
  menuId: string
  viewId?: string
  sourceLocator: GraphSourceLocator
}

export interface SecurityFindingSnapshot {
  code: string
  severity: 'INFO' | 'WARNING' | 'ERROR' | 'BLOCKING'
  title: string
  message: string
  remediation?: string
  roleId?: string
  artifactId?: string
  sourceLocator?: GraphSourceLocator
}

export interface SecurityWorkspaceSnapshot {
  graphDigest: string
  roles: SecurityRoleSnapshot[]
  policies: SecurityPolicySnapshot[]
  surfaces: SecuritySurfaceSnapshot[]
  menuRoutes: SecurityMenuRouteSnapshot[]
  findings: SecurityFindingSnapshot[]
  summary: {
    resourceRoleCount: number
    rowRoleCount: number
    policyCount: number
    coveredSurfaceCount: number
    uncoveredMenuCount: number
    uncoveredViewCount: number
    errorCount: number
    warningCount: number
  }
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
  controllerModel?: FlowUiControllerWorkspaceSnapshot
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

export interface FlowUiControllerInjectionSnapshot {
  fieldName: string
  componentId: string
  type: string
  visibility?: string
  sourceLocator: GraphSourceLocator
}

export interface FlowUiControllerHandlerSnapshot {
  methodName: string
  kind: string
  target?: string
  subject?: string
  targetScope?: string
  returnType?: string
  parameterTypes: string[]
  sourceLocator: GraphSourceLocator
}

export interface FlowUiControllerWorkspaceSnapshot {
  relativePath: string
  revisionFingerprint: string
  className: string
  language: string
  psiSupported: boolean
  injections: FlowUiControllerInjectionSnapshot[]
  handlers: FlowUiControllerHandlerSnapshot[]
  message?: string
}

export interface FlowUiControllerInjectionRequest {
  controllerLocator: GraphSourceLocator
  componentId: string
  componentTag: string
  entityClass?: string
}

export type FlowUiControllerHandlerKind =
  | 'VIEW_INIT'
  | 'VIEW_BEFORE_SHOW'
  | 'VIEW_READY'
  | 'VIEW_ATTACH'
  | 'VIEW_BEFORE_CLOSE'
  | 'VIEW_AFTER_CLOSE'
  | 'VIEW_DETACH'
  | 'VIEW_QUERY_PARAMETERS_CHANGE'
  | 'BUTTON_CLICK'
  | 'COMPONENT_TYPED_VALUE_CHANGE'
  | 'COMPONENT_VALUE_CHANGE'
  | 'ACTION_PERFORMED'
  | 'COLLECTION_LOADER_PRE_LOAD'
  | 'COLLECTION_LOADER_POST_LOAD'
  | 'COLLECTION_LOADER_LOAD_DELEGATE'
  | 'COMPONENT_VALIDATOR'

export interface FlowUiControllerHandlerRequest {
  controllerLocator: GraphSourceLocator
  kind: FlowUiControllerHandlerKind
  componentId?: string
  componentTag?: string
  targetId?: string
  entityClass?: string
}

export interface JmixRuntimeInspectionResponse {
  accepted: boolean
  viewId?: string
  targets: JmixRuntimeTargetSnapshot[]
  issues: WorkspaceChangeIssue[]
}

export interface JmixRuntimeTargetSnapshot {
  id: string
  moduleId: string
  moduleRoot: string
  profile: string
  preferred: boolean
  baseUrl: string
  previewUrl: string
  routePath?: string
  routeRequiresParameters: boolean
  reachable: boolean
  httpStatus?: number
  responseTimeMillis: number
  configSources: string[]
  hotDeploySupported: boolean
  hotDeployMessage?: string
  confDirectory?: string
  tempDirectory?: string
  warnings: string[]
}

export type JmixRuntimeViewport = 'DESKTOP' | 'TABLET' | 'MOBILE'

export interface JmixRuntimeActionResponse {
  success: boolean
  message: string
}

export interface JmixFlowUiHotDeployRequest {
  descriptorLocator: GraphSourceLocator
  targetId: string
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
