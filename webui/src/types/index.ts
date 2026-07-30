// ─── Entity Types ────────────────────────────────────────────────────────────

export type EntityType = 'entity' | 'mappedSuperclass' | 'embeddable' | 'dto' | 'enum'
export type EntitySourceLanguage = 'java' | 'kotlin'
export type IdType = 'uuid' | 'long' | 'integer' | 'string' | 'embedded'
export type IdGeneration = 'jmixGenerated' | 'identity' | 'sequence' | 'assigned'
export type InheritanceStrategy = 'singleTable' | 'joined' | 'tablePerClass'
export type AttributeType =
  | 'string' | 'character' | 'integer' | 'long' | 'double' | 'bigDecimal' | 'boolean'
  | 'date' | 'localDate' | 'localDateTime' | 'localTime' | 'offsetTime' | 'offsetDateTime'
  | 'sqlDate' | 'sqlTime' | 'uuid' | 'uri' | 'byteArray' | 'fileRef'
  | 'enum' | 'association' | 'composition' | 'embedded' | 'custom'
export type AssociationType = 'manyToOne' | 'oneToMany' | 'manyToMany' | 'oneToOne'
export type AssociationCollectionType = 'list' | 'set'
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
  readOnly: boolean
  jmixProperty: boolean
  dependsOnProperties: string[]
  propertyDatatype?: string
  lob: boolean
  javaTypeName?: string
  sqlType?: string
  association?: AssociationConfig
  embeddedClass?: string
  enumClass?: string
  enumIdType: 'string' | 'integer'
  validations: ValidationModel[]
  annotations: CustomAnnotation[]
  inBaseFetchPlan: boolean
}

export interface AssociationConfig {
  associationType: AssociationType
  relatedEntity: string
  relatedTableName?: string
  relatedIdColumnName: string
  relatedIdType: IdType
  localIdAttributeName?: string
  mappedBy?: string
  joinColumnName?: string
  joinColumns?: AssociationJoinColumn[]
  joinTable?: {
    name: string
    joinColumnName: string
    inverseJoinColumnName: string
    schema?: string
    catalog?: string
    joinColumns?: AssociationJoinColumn[]
    inverseJoinColumns?: AssociationJoinColumn[]
  }
  cascade: CascadeType[]
  fetch: FetchType
  collectionType: AssociationCollectionType
  crossDataStore: boolean
  orphanRemoval: boolean
  onDelete?: string
}

export interface AssociationJoinColumn {
  name: string
  referencedColumnName: string
  nullable?: boolean
  insertable?: boolean
  updatable?: boolean
}

export interface ValidationModel {
  type: ValidationType
  value?: string
  value2?: string
  message?: string
  groups?: string[]
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
  sourceLanguage: EntitySourceLanguage
  dataStore: string
  generationTarget?: {
    moduleId?: string
    storeId?: string
  }
  entityName: string
  tableName: string
  tableSchema?: string
  tableCatalog?: string
  entityType: EntityType
  embeddableIdentity?: boolean
  id: {
    type: IdType
    generation: IdGeneration
    columnName: string
    length?: number
    sequenceName?: string
    embeddedIdClass?: string
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
  instanceNameAttribute?: string
  comment?: string
  systemLevel: boolean
  annotatedPropertiesOnly: boolean
  databaseView: boolean
  ddlGeneration: {
    enabled: boolean
    mode: 'createAndDrop' | 'createOnly' | 'disabled'
    unmappedColumns: string[]
    unmappedConstraints: string[]
  }
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
  className: string
  packageName?: string
  name: string
  code: string
  description?: string
  scope: 'resource' | 'rowLevel'
  securityScopes: ('UI' | 'API')[]
  entityPolicies: { entityClass: string; actions: string[]; allActions: boolean }[]
  entityAttributePolicies: {
    entityClass: string
    attributes: string[]
    action: 'view' | 'modify'
  }[]
  menuPolicies: { menuId: string }[]
  viewPolicies: { viewId: string }[]
  specificPolicies: { permission: string }[]
  rowLevelPolicies: {
    entityClass: string
    type: 'jpql' | 'predicate'
    action: string
    actions: string[]
    whereClause?: string
    joinClause?: string
    predicateExpression?: string
  }[]
  baseRoleClasses: string[]
  allowWildcardPolicies: boolean
}

export interface SecurityRoleCreateRequest {
  role: RoleModel
  destinationId?: string
}

export interface SecurityRoleDestinationSnapshot {
  id: string
  moduleId: string
  sourceRoot: string
  defaultPackage: string
  recommended: boolean
}

export interface SecurityRoleDestinationsResponse {
  destinations: SecurityRoleDestinationSnapshot[]
  defaultDestinationId?: string
  issues: WorkspaceChangeIssue[]
}

export type SecurityRolePolicyType =
  | 'entity'
  | 'entityAttribute'
  | 'menu'
  | 'view'
  | 'specific'
  | 'jpqlRow'
  | 'predicateRow'

export interface SecurityRolePolicyModel {
  type: SecurityRolePolicyType
  entityClass?: string
  entityActions: ('create' | 'read' | 'update' | 'delete')[]
  allEntityActions: boolean
  attributes: string[]
  attributeAction: 'view' | 'modify'
  resources: string[]
  rowActions: ('create' | 'read' | 'update' | 'delete')[]
  whereClause?: string
  joinClause?: string
  predicateExpression?: string
  allowWildcard: boolean
}

export interface SecurityRolePolicyChangeRequest {
  roleLocator: GraphSourceLocator
  roleClassName: string
  policy: SecurityRolePolicyModel
}

export interface SecurityRolePolicyInspectionRequest {
  roleLocator: GraphSourceLocator
  roleClassName: string
}

export interface SecurityRolePolicyEditorSnapshot {
  id: string
  locator: GraphSourceLocator
  type: SecurityRolePolicyType
  methodName: string
  annotationText: string
  policy?: SecurityRolePolicyModel
  editable: boolean
  editIssue?: string
}

export interface SecurityRolePolicyInspectionResponse {
  accepted: boolean
  policies: SecurityRolePolicyEditorSnapshot[]
  issues: WorkspaceChangeIssue[]
}

export interface SecurityRolePolicyReplacementRequest {
  roleLocator: GraphSourceLocator
  roleClassName: string
  policyLocator: GraphSourceLocator
  replacement: SecurityRolePolicyModel
}

export interface SecurityRolePolicyRemovalRequest {
  roleLocator: GraphSourceLocator
  roleClassName: string
  policyLocator: GraphSourceLocator
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
  rollback?: any[]
  context?: string
  dbms?: string
  runOnChange?: boolean
  runAlways?: boolean
  runInTransaction?: boolean
  labels?: string
  preConditions?: any[]
  preConditionOnFail?: 'HALT' | 'WARN' | 'CONTINUE' | 'MARK_RAN'
  preConditionOnError?: 'HALT' | 'WARN' | 'CONTINUE' | 'MARK_RAN'
}

export interface SchemaWorkspaceResponse {
  accepted: boolean
  snapshotDigest: string
  modules: SchemaModuleSnapshot[]
  stores: SchemaDataStoreSnapshot[]
  entities: SchemaEntitySnapshot[]
  changelogs: SchemaChangelogSnapshot[]
  physicalSchemas: SchemaPhysicalStoreSnapshot[]
  drifts: SchemaDriftSnapshot[]
  findings: SchemaFinding[]
  issues: WorkspaceChangeIssue[]
}

export interface SchemaModuleSnapshot {
  moduleId: string
  projectId?: string
  entityCount: number
  changelogCount: number
  storeCount: number
  findingCount: number
}

export interface SchemaDataStoreSnapshot {
  id: string
  name: string
  moduleId: string
  configuredPath: string
  configurationLocator?: GraphSourceLocator
  rootChangelogPath?: string
  rootLocator?: GraphSourceLocator
  includeMode: 'INCLUDE_ALL' | 'EXPLICIT' | 'DIRECT' | 'MISSING'
  includeTargets: SchemaIncludeTarget[]
  generatedDirectory?: string
}

export interface SchemaIncludeTarget {
  path: string
  includeAll: boolean
  relativeToChangelogFile: boolean
}

export interface SchemaEntitySnapshot {
  artifactId: string
  moduleId: string
  className: string
  qualifiedName: string
  entityName: string
  tableName: string
  tableSchema?: string
  tableCatalog?: string
  storeName: string
  idType: IdType
  idColumnName: string
  databaseView: boolean
  ddlMode: 'CREATE_AND_DROP' | 'CREATE_ONLY' | 'DISABLED'
  protectedUnmappedColumns?: string[]
  sourceLocator: GraphSourceLocator
  attributes: SchemaEntityAttributeSnapshot[]
  migrationCoverage: 'COVERED' | 'MISSING' | 'DISABLED'
  migrationArtifactIds: string[]
}

export interface SchemaEntityAttributeSnapshot {
  artifactId: string
  name: string
  javaType: string
  columnName: string
  nullable: boolean
  unique: boolean
  length?: number
  precision?: number
  scale?: number
  persistent: boolean
  association: boolean
  associationDetails?: SchemaAssociationSnapshot
  moneyCandidate: boolean
}

export interface SchemaAssociationSnapshot extends AssociationConfig {
  composition: boolean
}

export interface ExistingEntityAttributeAdditionRequest {
  sourceLocator: GraphSourceLocator
  entity: EntityModel
}

export interface EntityAttributeRenameRequest {
  sourceLocator: GraphSourceLocator
  entityClassName: string
  attributeName: string
  newName: string
}

export interface EntityAttributeRenameLaunchResponse {
  success: boolean
  code?: string
  message: string
}

export interface EntityAttributeSafeDeleteRequest {
  sourceLocator: GraphSourceLocator
  entityClassName: string
  attributeName: string
}

export interface EntityAttributeSafeDeleteLaunchResponse {
  success: boolean
  code?: string
  message: string
  retainedColumnName?: string
}

export interface EntityAttributeTypeMigrationRequest {
  sourceLocator: GraphSourceLocator
  entityClassName: string
  attributeName: string
  targetType: AttributeType
  verificationToken?: string
}

export type EntityAttributeTypeSchemaStrategy =
  | 'SOURCE_ONLY'
  | 'EXPAND_CONTRACT_REQUIRED'
  | 'EXTERNAL_SCHEMA_REQUIRED'
  | 'SCHEMA_EVIDENCE_INCOMPLETE'

export interface EntityAttributeTypeSchemaImpact {
  strategy: EntityAttributeTypeSchemaStrategy
  storeId?: string
  tableName?: string
  columnName?: string
  currentSqlType?: string
  targetSqlType?: string
  dependencies: string[]
  summary: string
}

export interface EntityAttributeTypeMigrationLaunchResponse {
  success: boolean
  code?: string
  message: string
  sourceLanguage?: string
  schemaImpact?: EntityAttributeTypeSchemaImpact
}

export interface EntityAttributeTypeExpansionPreviewResponse {
  accepted: boolean
  code?: string
  message: string
  shadowColumnName?: string
  targetSqlType?: string
  preview: WorkspaceChangePreviewResponse
}

export interface EntityAttributeTypeExpansionVerificationResponse {
  accepted: boolean
  code?: string
  message: string
  verificationToken?: string
  expiresAtEpochMillis?: number
  evidenceDigest?: string
  database?: DatabaseProductSnapshot
  shadowColumnName?: string
  targetSqlType?: string
  inconsistentBackfillRows?: number
}

export interface EntityAttributeTypeMappingCutoverRequest {
  sourceLocator: GraphSourceLocator
  entityClassName: string
  attributeName: string
  targetType: AttributeType
  verificationToken: string
}

export interface DatabaseEntityTableInspectionRequest {
  storeId: string
  tableName: string
  schemaName?: string
  catalogName?: string
  expectedEntityQualifiedName?: string
  connectTimeoutSeconds?: number
  networkTimeoutSeconds?: number
}

export interface DatabaseEntityTableBrowseRequest {
  storeId: string
  catalogName?: string
  schemaName?: string
  search?: string
  includeViews?: boolean
  limit?: number
  connectTimeoutSeconds?: number
  networkTimeoutSeconds?: number
}

export interface DatabaseEntityTableBrowseResponse {
  accepted: boolean
  storeId?: string
  database?: DatabaseProductSnapshot
  activeCatalog?: string
  catalogs: string[]
  schemas: DatabaseSchemaReference[]
  tables: DatabaseTableReference[]
  truncated: boolean
  issues: WorkspaceChangeIssue[]
}

export interface DatabaseSchemaReference {
  catalog?: string
  name: string
}

export interface DatabaseTableReference {
  catalog?: string
  schema?: string
  name: string
  type: string
  remarks?: string
}

export interface DatabaseEntityImportRequest {
  storeId: string
  moduleId: string
  packageName: string
  sourceLanguage: EntitySourceLanguage
  selectedTables: DatabaseTableReference[]
  includeDependencies: boolean
  identifierOverrides?: Record<string, string[]>
  classNameOverrides?: Record<string, string>
  profileId?: string
  profileLabel?: string
  connectTimeoutSeconds?: number
  networkTimeoutSeconds?: number
}

export type DatabaseEntityImportStatus =
  | 'READY'
  | 'VIEW'
  | 'COMPOSITE_KEY'
  | 'JOIN_TABLE'
  | 'EXISTING_ENTITY'
  | 'BLOCKED'

export interface DatabaseEntityImportTablePlan {
  table: DatabaseTableSnapshot
  selectedByUser: boolean
  requiredBy: string[]
  status: DatabaseEntityImportStatus
  entityClassName?: string
  entityQualifiedName?: string
  compositeIdClassName?: string
  generated: boolean
  issues: WorkspaceChangeIssue[]
}

export interface DatabaseEntityImportPlanResponse {
  accepted: boolean
  ready: boolean
  snapshotDigest?: string
  storeId?: string
  database?: DatabaseProductSnapshot
  tables: DatabaseEntityImportTablePlan[]
  entities: EntityModel[]
  issues: WorkspaceChangeIssue[]
  profileDrift?: DatabaseEntityImportProfileDrift
}

export interface DatabaseEntityImportProfile {
  schemaVersion: number
  id: string
  label: string
  request: DatabaseEntityImportRequest
  baselineSnapshotDigest: string
  database: DatabaseProductSnapshot
  tables: DatabaseEntityImportTablePlan[]
}

export interface DatabaseEntityImportProfileDocument {
  profile: DatabaseEntityImportProfile
  sourceLocator: GraphSourceLocator
}

export interface DatabaseEntityImportProfileWorkspaceResponse {
  profiles: DatabaseEntityImportProfileDocument[]
  issues: WorkspaceChangeIssue[]
}

export interface DatabaseEntityImportProfileDrift {
  profileId: string
  baselineSnapshotDigest: string
  liveSnapshotDigest?: string
  matchesBaseline: boolean
  requestChanged: boolean
  addedTables: string[]
  removedTables: string[]
  changedTables: string[]
}

export interface DatabaseEntityTableInspectionResponse {
  accepted: boolean
  snapshotDigest?: string
  storeId?: string
  database?: DatabaseProductSnapshot
  table?: DatabaseTableSnapshot
  existingEntityQualifiedName?: string
  issues: WorkspaceChangeIssue[]
}

export type EntityAttributePropagationTargetKind =
  | 'VIEW_FORM'
  | 'VIEW_GRID'
  | 'INLINE_FETCH_PLAN'
  | 'SHARED_FETCH_PLAN'
  | 'MESSAGE_BUNDLE'
  | 'RESOURCE_ROLE'

export interface EntityAttributePropagationInspectionRequest {
  entityQualifiedName: string
  entityName: string
  className: string
  attributeNames: string[]
  entityChange?: ExistingEntityAttributeAdditionRequest
}

export interface EntityAttributePropagationChangeRequest {
  inspection: EntityAttributePropagationInspectionRequest
  targetIds: string[]
}

export interface EntityAttributePropagationTargetSnapshot {
  id: string
  kind: EntityAttributePropagationTargetKind
  label: string
  relativePath: string
  detail: string
  missingAttributes: string[]
  recommended: boolean
  supported: boolean
  securityExpanding: boolean
}

export interface EntityAttributePropagationInspectionResponse {
  accepted: boolean
  entityQualifiedName: string
  attributes: string[]
  targets: EntityAttributePropagationTargetSnapshot[]
  issues: WorkspaceChangeIssue[]
}

export interface DatabaseProductSnapshot {
  name: string
  version: string
  driverName: string
  driverVersion: string
  urlFingerprint: string
}

export interface DatabaseTableSnapshot {
  catalog?: string
  schema?: string
  name: string
  type: string
  remarks?: string
  columns: DatabaseColumnSnapshot[]
  primaryKeyColumns: string[]
  foreignKeys: DatabaseForeignKeySnapshot[]
  indexes: DatabaseIndexSnapshot[]
  dependencyTables: string[]
}

export interface DatabaseColumnSnapshot {
  name: string
  jdbcType: number
  typeName: string
  size?: number
  scale?: number
  nullable: boolean
  defaultValue?: string
  remarks?: string
  autoIncrement: boolean
  generated: boolean
  ordinal: number
  primaryKey: boolean
  alreadyMapped: boolean
  suggestion: DatabaseColumnMappingSuggestion
}

export interface DatabaseColumnMappingSuggestion {
  attributeName: string
  attributeType: AttributeType
  javaType: string
  primaryKey: boolean
  mandatory: boolean
  length?: number
  precision?: number
  scale?: number
  customSqlType?: string
  unsupportedReason?: string
  relatedEntity?: string
  joinColumnName?: string
  foreignKeyTable?: string
  referencedColumnName?: string
}

export interface DatabaseForeignKeySnapshot {
  name?: string
  columnName: string
  referencedCatalog?: string
  referencedSchema?: string
  referencedTableName: string
  referencedColumnName: string
  updateRule: number
  deleteRule: number
  sequence: number
}

export interface DatabaseIndexSnapshot {
  name: string
  unique: boolean
  columns: string[]
}

export interface SchemaChangelogSnapshot {
  artifactId: string
  moduleId: string
  relativePath: string
  sourceLocator: GraphSourceLocator
  root: boolean
  changeSetCount: number
  includes: SchemaIncludeTarget[]
  tables: string[]
  containsRawSql: boolean
}

export interface SchemaPhysicalStoreSnapshot {
  storeId: string
  moduleId: string
  complete: boolean
  changelogPaths: string[]
  tables: SchemaPhysicalTableSnapshot[]
}

export interface SchemaPhysicalTableSnapshot {
  name: string
  columns: SchemaPhysicalColumnSnapshot[]
  foreignKeys: SchemaPhysicalForeignKeySnapshot[]
  indexes?: SchemaPhysicalIndexSnapshot[]
  sourcePaths: string[]
}

export interface SchemaPhysicalColumnSnapshot {
  name: string
  type: string
  nullable: boolean
  unique: boolean
  primaryKey: boolean
}

export interface SchemaPhysicalForeignKeySnapshot {
  constraintName: string
  baseColumnNames: string
  referencedTableName: string
  referencedColumnNames: string
  onDelete?: string
}

export interface SchemaPhysicalIndexSnapshot {
  name: string
  unique: boolean
  columns: string[]
}

export interface SchemaDriftSnapshot {
  id: string
  kind:
    | 'TABLE_MISSING'
    | 'COLUMN_MISSING'
    | 'TYPE_MISMATCH'
    | 'NULLABILITY_MISMATCH'
    | 'UNIQUE_CONSTRAINT_MISSING'
    | 'FOREIGN_KEY_MISSING'
    | 'UNMAPPED_COLUMN'
  severity: 'INFO' | 'WARNING' | 'ERROR'
  safety: 'SAFE' | 'DATA_CHECK_REQUIRED' | 'REVIEW'
  confidence: 'HIGH' | 'PARTIAL'
  moduleId: string
  storeId: string
  entityArtifactId: string
  entitySourceLocator: GraphSourceLocator
  tableName: string
  columnName?: string
  message: string
  suggestion?: SchemaDriftSuggestion
}

export interface SchemaDriftSuggestion {
  changeType:
    | 'createTable'
    | 'addColumn'
    | 'modifyColumn'
    | 'renameColumn'
    | 'addUniqueConstraint'
    | 'addNotNullConstraint'
    | 'dropNotNullConstraint'
    | 'addForeignKey'
  tableName: string
  columnName?: string
  columnType?: string
  nullable?: boolean
  columns: SchemaSuggestedColumn[]
  newDataType?: string
  newColumnName?: string
  constraintName?: string
  columnNames: string[]
  baseTableName?: string
  baseColumnNames?: string
  referencedTableName?: string
  referencedColumnNames?: string
  onDelete?: string
}

export interface SchemaSuggestedColumn {
  name: string
  type: string
  nullable: boolean
  unique: boolean
  primaryKey: boolean
}

export interface SchemaFinding {
  severity: 'INFO' | 'WARNING' | 'ERROR'
  code: string
  message: string
  moduleId: string
  sourceLocator?: GraphSourceLocator
  entityArtifactId?: string
}

export interface SchemaMigrationChangeRequest {
  storeId: string
  migration: MigrationModel
  fileName?: string
}

// ─── Project Config ──────────────────────────────────────────────────────────

export interface ProjectConfig {
  projectRoot: string
  basePackage: string
  sourceRoot: string
  resourceRoot: string
  jmixVersion: string
  projectId?: string
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

export interface WorkbenchLaunchContext {
  surface: 'TOOL_WINDOW' | 'FLOW_UI_EDITOR'
  sourceLocator?: GraphSourceLocator
}

// ─── Indexed Menu Workspace ─────────────────────────────────────────────────

export interface MenuNodeSnapshot {
  id: string
  kind: 'menu' | 'view' | 'bean' | 'separator'
  caption: string
  titleExpression?: string
  description?: string
  icon?: string
  classNames?: string
  opened: boolean
  viewId?: string
  shortcut?: string
  openedBy?: string
  bean?: string
  beanMethod?: string
  order: number
  syntheticId: boolean
  properties: Record<string, string>
  routeParameters: Record<string, string>
  urlQueryParameters: Record<string, string>
  preservedAttributes: Record<string, string>
  children: MenuNodeSnapshot[]
}

export interface MenuSourceSnapshot {
  moduleId: string
  relativePath: string
  rootElement: string
  namespace?: string
  sourceLocator: GraphSourceLocator
  nodes: MenuNodeSnapshot[]
  nodeCount: number
  maximumDepth: number
  warnings: string[]
}

export interface MenuWorkspaceResponse {
  sources: MenuSourceSnapshot[]
  warnings: string[]
  springBeans: MenuSpringBeanSnapshot[]
}

export interface MenuSpringBeanSnapshot {
  name: string
  declarationName: string
  sourcePath: string
  language: string
  ambiguous: boolean
  methods: MenuSpringBeanMethodSnapshot[]
}

export interface MenuSpringBeanMethodSnapshot {
  name: string
  signature: string
  callable: boolean
  issue?: string
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
  unreadableFiles: number
  parseErrorFiles: number
  parserUnavailableFiles: number
  reusedFiles: number
  changedFiles: number
  cacheHit: boolean
  durationMillis: number
  modules: {
    moduleId: string
    buildIds: string[]
    contentRootCount: number
    sourceRootCount: number
    fallbackContentRootCount: number
    discoveredSourceRootCount: number
    candidateFileCount: number
    indexedFileCount: number
    sourceSets: string[]
    moduleRoot: string
    sourceRoots: {
      moduleId: string
      relativePath: string
      sourceSetId: string
      buildId: string
      kind: 'JAVA' | 'KOTLIN' | 'GROOVY' | 'RESOURCES' | 'UNKNOWN'
      recovered: boolean
    }[]
  }[]
  indexHealth: {
    complete: boolean
    moduleCount: number
    contentRootCount: number
    sourceRootCount: number
    fallbackContentRootCount: number
    unreadableFileCount: number
    parseErrorFileCount: number
    parserUnavailableFileCount: number
    discoveredSourceRootCount: number
    recoveredModuleCount: number
    overlappingOwnershipFileCount: number
    ambiguousOwnershipFileCount: number
    unresolvedModuleDependencyCount: number
    limitReached: boolean
  }
  snapshotDigest: string
  error?: string
}

export type ScenarioStepKind =
  | 'SEED_ENTITY'
  | 'INVOKE_SERVICE'
  | 'ASSERT_PROPERTY'
  | 'ASSERT_VALUE'
  | 'ASSERT_ENTITY_COUNT'
  | 'ASSERT_SERVICE_FAILURE'

export type ScenarioValueType =
  | 'STRING'
  | 'INTEGER'
  | 'LONG'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'UUID'
  | 'LOCAL_DATE'
  | 'LOCAL_DATETIME'
  | 'OFFSET_DATETIME'
  | 'INSTANT'
  | 'ENUM'
  | 'NULL'
  | 'VARIABLE'

export type ScenarioAssertionOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'NULL'
  | 'NOT_NULL'
  | 'TRUE'
  | 'FALSE'
  | 'GREATER_THAN'
  | 'LESS_THAN'
  | 'CONTAINS'

export type ScenarioActorMode = 'SYSTEM' | 'USER'

export interface ScenarioValueModel {
  type: ScenarioValueType
  value?: string
  javaType?: string
}

export interface ScenarioFieldValueModel {
  property: string
  value: ScenarioValueModel
}

export interface ScenarioStepModel {
  id: string
  label: string
  kind: ScenarioStepKind
  actorMode: ScenarioActorMode
  username?: string
  variableName?: string
  entityClass?: string
  fields: ScenarioFieldValueModel[]
  beanName?: string
  methodName?: string
  arguments: ScenarioValueModel[]
  resultVariable?: string
  targetVariable?: string
  propertyPath?: string
  operator?: ScenarioAssertionOperator
  expected?: ScenarioValueModel
  jpql?: string
  expectedCount?: number
  expectedExceptionClass?: string
  messageContains?: string
}

export interface ScenarioTestModel {
  name: string
  description: string
  destinationId: string
  packageName: string
  className: string
  steps: ScenarioStepModel[]
  sourceLocator?: GraphSourceLocator
}

export interface ScenarioDestinationSnapshot {
  id: string
  moduleId: string
  testSourceRoot: string
  defaultPackage: string
  recommended: boolean
}

export interface ScenarioDocumentSnapshot {
  locator: GraphSourceLocator
  model: ScenarioTestModel
  editable: boolean
  issue?: string
}

export interface ScenarioWorkspaceResponse {
  graphDigest: string
  destinations: ScenarioDestinationSnapshot[]
  defaultDestinationId?: string
  contextArtifacts: GraphArtifact[]
  existingScenarios: ScenarioDocumentSnapshot[]
  issues: WorkspaceChangeIssue[]
  error?: string
}

// ─── Typed Visual Server Logic ──────────────────────────────────────────────

export type LogicNodeKind =
  | 'START' | 'RETURN' | 'CONSTANT'
  | 'CREATE_ENTITY' | 'LOAD_ENTITY_BY_ID' | 'LOAD_ENTITIES'
  | 'SET_PROPERTY' | 'SAVE_ENTITY' | 'REMOVE_ENTITY'
  | 'CALL_SERVICE' | 'CALL_SUBFLOW' | 'FOR_EACH' | 'TRY_CATCH'
  | 'CONDITION' | 'REQUIRE' | 'AUTHORIZE_ENTITY'
  | 'THROW' | 'LOG'

export type LogicValueSource = 'LITERAL' | 'PARAMETER' | 'VARIABLE' | 'NULL'
export type LogicValueType =
  | 'STRING' | 'INTEGER' | 'LONG' | 'DECIMAL' | 'BOOLEAN' | 'UUID'
  | 'LOCAL_DATE' | 'LOCAL_DATE_TIME' | 'OFFSET_DATE_TIME' | 'INSTANT'
  | 'ENUM' | 'ENTITY' | 'OBJECT'
export type LogicConditionOperator =
  | 'EQUALS' | 'NOT_EQUALS' | 'NULL' | 'NOT_NULL' | 'TRUE' | 'FALSE'
  | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL' | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL' | 'CONTAINS'
export type LogicTransitionBranch = 'ALWAYS' | 'TRUE' | 'FALSE' | 'ITEM' | 'DONE'
export type LogicMethodKind = 'ENTRY_POINT' | 'SUBFLOW'
export type LogicEntityOperation = 'CREATE' | 'READ' | 'UPDATE' | 'DELETE'
export type LogicLogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'
export type LogicTransactionPropagation =
  | 'REQUIRED' | 'REQUIRES_NEW' | 'SUPPORTS' | 'MANDATORY'
  | 'NOT_SUPPORTED' | 'NEVER' | 'NESTED'
export type LogicTransactionIsolation =
  | 'DEFAULT' | 'READ_UNCOMMITTED' | 'READ_COMMITTED'
  | 'REPEATABLE_READ' | 'SERIALIZABLE'

export interface LogicValueModel {
  source: LogicValueSource
  type: LogicValueType
  value?: string
  javaType?: string
}

export interface LogicNamedValueModel {
  name: string
  value: LogicValueModel
}

export interface LogicConditionModel {
  left: LogicValueModel
  operator: LogicConditionOperator
  right?: LogicValueModel
}

export interface LogicMethodParameterModel {
  name: string
  javaType: string
  nullable: boolean
}

export interface LogicTransitionModel {
  id: string
  sourceNodeId: string
  targetNodeId: string
  branch: LogicTransitionBranch
}

export interface LogicNodeModel {
  id: string
  label: string
  kind: LogicNodeKind
  x: number
  y: number
  resultVariable?: string
  resultJavaType?: string
  entityClass?: string
  targetVariable?: string
  propertyPath?: string
  value?: LogicValueModel
  fieldValues: LogicNamedValueModel[]
  jpql?: string
  queryParameters: LogicNamedValueModel[]
  maxResults?: number
  beanClass?: string
  beanFieldName?: string
  methodName?: string
  subflowMethod?: string
  catchMethod?: string
  finallyMethod?: string
  exceptionType?: string
  indexVariable?: string
  arguments: LogicValueModel[]
  condition?: LogicConditionModel
  entityOperation?: LogicEntityOperation
  message?: string
  logLevel: LogicLogLevel
}

export interface LogicTransactionModel {
  enabled: boolean
  readOnly: boolean
  propagation: LogicTransactionPropagation
  isolation: LogicTransactionIsolation
  timeoutSeconds?: number
}

export interface VisualLogicMethodModel {
  name: string
  description: string
  kind: LogicMethodKind
  returnJavaType: string
  parameters: LogicMethodParameterModel[]
  transaction: LogicTransactionModel
  maximumExecutions: number
  nodes: LogicNodeModel[]
  transitions: LogicTransitionModel[]
}

export interface VisualLogicClassModel {
  name: string
  description: string
  destinationId: string
  packageName: string
  className: string
  beanName: string
  methods: VisualLogicMethodModel[]
  sourceLocator?: GraphSourceLocator
}

export interface VisualLogicDestinationSnapshot {
  id: string
  moduleId: string
  sourceRoot: string
  defaultPackage: string
  recommended: boolean
}

export interface VisualLogicDocumentSnapshot {
  locator: GraphSourceLocator
  model: VisualLogicClassModel
  editable: boolean
  issue?: string
}

export interface VisualLogicWorkspaceResponse {
  graphDigest: string
  destinations: VisualLogicDestinationSnapshot[]
  defaultDestinationId?: string
  contextArtifacts: GraphArtifact[]
  existingDocuments: VisualLogicDocumentSnapshot[]
  issues: WorkspaceChangeIssue[]
  error?: string
}

// ─── Enterprise Integration Connectors ─────────────────────────────────────

export type IntegrationConnectorKind =
  | 'HTTP_CLIENT' | 'WEBHOOK'
  | 'KAFKA_PUBLISHER' | 'KAFKA_CONSUMER'
  | 'RABBIT_PUBLISHER' | 'RABBIT_CONSUMER'
  | 'SFTP_UPLOAD' | 'SFTP_DOWNLOAD'
  | 'JMIX_EMAIL' | 'JMIX_FILE_STORAGE' | 'OBJECT_STORAGE'
  | 'SMS_GATEWAY' | 'PAYMENT_GATEWAY' | 'IDENTITY_PROVIDER'

export type IntegrationCapability =
  | 'SPRING_WEB' | 'SPRING_KAFKA' | 'SPRING_AMQP'
  | 'SPRING_INTEGRATION_SFTP' | 'RESILIENCE4J'
  | 'JMIX_EMAIL' | 'JMIX_FILE_STORAGE' | 'OAUTH2_CLIENT'

export type IntegrationHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type IntegrationDeliveryGuarantee = 'AT_MOST_ONCE' | 'AT_LEAST_ONCE' | 'EXACTLY_ONCE'
export type IntegrationRetryMode = 'NONE' | 'BLOCKING' | 'NON_BLOCKING'
export type IntegrationBackoffMode = 'FIXED' | 'EXPONENTIAL'
export type IntegrationAuthenticationKind =
  | 'NONE' | 'BASIC' | 'BEARER' | 'API_KEY'
  | 'OAUTH2_CLIENT_CREDENTIALS' | 'SSH_KEY'

export interface IntegrationHeaderModel {
  name: string
  valueProperty: string
  sensitive: boolean
}

export interface IntegrationAuthenticationModel {
  kind: IntegrationAuthenticationKind
  headerName?: string
  usernameProperty?: string
  secretProperty?: string
  tokenUriProperty?: string
  clientIdProperty?: string
  authorizedClientManagerBeanName?: string
  clientRegistrationIdProperty?: string
  principalNameProperty?: string
  scopes: string[]
}

export interface IntegrationRetryPolicyModel {
  mode: IntegrationRetryMode
  attempts: number
  backoff: IntegrationBackoffMode
  initialDelayMs: number
  multiplier: number
  maximumDelayMs: number
  deadLetterDestinationProperty?: string
}

export interface IntegrationCircuitBreakerModel {
  enabled: boolean
  slidingWindowSize: number
  minimumCalls: number
  failureRateThreshold: number
  openStateMs: number
}

export interface IntegrationBulkheadModel {
  enabled: boolean
  maximumConcurrentCalls: number
  maximumWaitMs: number
}

export interface IntegrationRateLimitModel {
  enabled: boolean
  callsPerPeriod: number
  periodMs: number
  timeoutMs: number
}

export interface IntegrationIdempotencyModel {
  enabled: boolean
  headerName: string
  keyParameterName: string
}

export interface IntegrationReliabilityModel {
  deliveryGuarantee: IntegrationDeliveryGuarantee
  connectTimeoutMs: number
  requestTimeoutMs: number
  retry: IntegrationRetryPolicyModel
  circuitBreaker: IntegrationCircuitBreakerModel
  bulkhead: IntegrationBulkheadModel
  rateLimit: IntegrationRateLimitModel
  idempotency: IntegrationIdempotencyModel
  transactional: boolean
  outboxEnabled: boolean
  orderingRequired: boolean
}

export interface IntegrationObservabilityModel {
  metricsEnabled: boolean
  tracingEnabled: boolean
  structuredLoggingEnabled: boolean
  auditEnabled: boolean
  redactHeaders: string[]
}

export interface IntegrationConnectorModel {
  name: string
  description: string
  destinationId: string
  packageName: string
  className: string
  beanName: string
  kind: IntegrationConnectorKind
  configurationPrefix: string
  addressProperty: string
  payloadJavaType: string
  responseJavaType: string
  httpMethod: IntegrationHttpMethod
  contentType: string
  handlerBeanClass?: string
  handlerFieldName?: string
  handlerMethod?: string
  headers: IntegrationHeaderModel[]
  authentication: IntegrationAuthenticationModel
  reliability: IntegrationReliabilityModel
  observability: IntegrationObservabilityModel
  profiles: string[]
  enabled: boolean
  sourceLocator?: GraphSourceLocator
}

export interface IntegrationConnectorDestinationSnapshot {
  id: string
  moduleId: string
  sourceRoot: string
  resourceRoot: string
  defaultPackage: string
  capabilities: IntegrationCapability[]
  recommended: boolean
}

export interface IntegrationConnectorDocumentSnapshot {
  locator: GraphSourceLocator
  model: IntegrationConnectorModel
  editable: boolean
  issue?: string
}

export interface IntegrationConnectorWorkspaceResponse {
  graphDigest: string
  destinations: IntegrationConnectorDestinationSnapshot[]
  defaultDestinationId?: string
  contextArtifacts: GraphArtifact[]
  oauth2Managers: IntegrationOAuth2ManagerSnapshot[]
  existingDocuments: IntegrationConnectorDocumentSnapshot[]
  issues: WorkspaceChangeIssue[]
  error?: string
}

export interface IntegrationOAuth2ManagerSnapshot {
  beanName: string
  declaringType: string
  moduleId: string
  sourceLocator: GraphSourceLocator
}

export type VisualRuleKind = 'FORMULA' | 'PREDICATE' | 'VALIDATOR'

export type RuleDataType =
  | 'STRING'
  | 'INTEGER'
  | 'LONG'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'UUID'
  | 'LOCAL_DATE'
  | 'LOCAL_DATE_TIME'
  | 'OFFSET_DATE_TIME'
  | 'INSTANT'
  | 'ENUM'
  | 'ENTITY'
  | 'OBJECT'

export type RuleValueSource = 'LITERAL' | 'PARAMETER' | 'NULL'

export type RuleExpressionKind =
  | 'VALUE' | 'PROPERTY'
  | 'ADD' | 'SUBTRACT' | 'MULTIPLY' | 'DIVIDE' | 'NEGATE' | 'ABS' | 'ROUND' | 'MIN' | 'MAX'
  | 'EQUALS' | 'NOT_EQUALS' | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL' | 'LESS_THAN' | 'LESS_THAN_OR_EQUAL'
  | 'AND' | 'OR' | 'NOT' | 'IF' | 'COALESCE'
  | 'CONCAT' | 'UPPER' | 'LOWER' | 'TRIM' | 'LENGTH' | 'IS_NULL' | 'IS_NOT_NULL'
  | 'DATE_PLUS_DAYS' | 'DAYS_BETWEEN' | 'IN_LIST'

export interface RuleParameterModel {
  name: string
  javaType: string
  dataType: RuleDataType
  nullable: boolean
}

export interface RuleExpressionModel {
  id: string
  label: string
  kind: RuleExpressionKind
  dataType: RuleDataType
  javaType?: string
  valueSource?: RuleValueSource
  value?: string
  parameterName?: string
  propertyPath?: string
  children: RuleExpressionModel[]
}

export interface VisualRuleModel {
  name: string
  description: string
  kind: VisualRuleKind
  destinationId: string
  packageName: string
  className: string
  beanName: string
  methodName: string
  outputJavaType: string
  parameters: RuleParameterModel[]
  expression: RuleExpressionModel
  validationMessage?: string
  decimalScale: number
  roundingMode: string
  sourceLocator?: GraphSourceLocator
}

export interface VisualRuleDocumentSnapshot {
  locator: GraphSourceLocator
  model: VisualRuleModel
  editable: boolean
  issue?: string
}

export interface VisualRuleWorkspaceResponse {
  graphDigest: string
  destinations: VisualLogicDestinationSnapshot[]
  defaultDestinationId?: string
  contextArtifacts: GraphArtifact[]
  existingDocuments: VisualRuleDocumentSnapshot[]
  issues: WorkspaceChangeIssue[]
  error?: string
}

// ─── Jmix / Flowable DMN decision tables ───────────────────────────────────

export type DmnValueType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'DATE'
export type DmnHitPolicy =
  | 'UNIQUE' | 'FIRST' | 'ANY' | 'PRIORITY'
  | 'OUTPUT_ORDER' | 'RULE_ORDER' | 'COLLECT'
export type DmnCollectOperator = 'NONE' | 'SUM' | 'MIN' | 'MAX' | 'COUNT'
export type DmnConditionOperator =
  | 'ANY' | 'EQUALS' | 'NOT_EQUALS' | 'LESS_THAN' | 'LESS_THAN_OR_EQUAL'
  | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL' | 'BETWEEN'
export type DmnAuthoringStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED'

export interface DmnInputModel {
  id: string
  label: string
  variable: string
  type: DmnValueType
}

export interface DmnOutputModel {
  id: string
  label: string
  variable: string
  type: DmnValueType
  predefinedValues: string[]
}

export interface DmnConditionModel {
  operator: DmnConditionOperator
  value?: string
  secondValue?: string
}

export interface DmnDecisionRuleModel {
  id: string
  description: string
  enabled: boolean
  inputEntries: Record<string, DmnConditionModel>
  outputEntries: Record<string, string>
}

export interface DmnDecisionModel {
  name: string
  key: string
  namespace: string
  destinationId: string
  fileName: string
  hitPolicy: DmnHitPolicy
  collectOperator: DmnCollectOperator
  inputs: DmnInputModel[]
  outputs: DmnOutputModel[]
  rules: DmnDecisionRuleModel[]
  authoringVersion: number
  authoringStatus: DmnAuthoringStatus
  effectiveFrom?: string
  effectiveTo?: string
  description: string
  sourceLocator?: GraphSourceLocator
}

export interface DmnDiagnostic {
  code: string
  severity: 'INFO' | 'WARNING' | 'ERROR'
  message: string
  ruleIds: string[]
  columnId?: string
}

export interface DmnSimulationRequest {
  model: DmnDecisionModel
  inputs: Record<string, string>
}

export interface DmnSimulationResult {
  accepted: boolean
  matchedRuleIds: string[]
  results: Record<string, string>[]
  diagnostics: DmnDiagnostic[]
}

export interface DmnDestinationSnapshot {
  id: string
  moduleId: string
  resourceRoot: string
  dmnDirectory: string
  recommended: boolean
}

export interface DmnDecisionDocumentSnapshot {
  locator: GraphSourceLocator
  model?: DmnDecisionModel
  editable: boolean
  issue?: string
}

export interface DmnWorkflowReferenceSnapshot {
  processId: string
  nodeId: string
  nodeName: string
  decisionKey: string
  locator: GraphSourceLocator
  resolved: boolean
}

export interface DmnDecisionWorkspaceResponse {
  graphDigest: string
  destinations: DmnDestinationSnapshot[]
  defaultDestinationId?: string
  existingDocuments: DmnDecisionDocumentSnapshot[]
  workflowReferences: DmnWorkflowReferenceSnapshot[]
  issues: WorkspaceChangeIssue[]
  error?: string
}

export type RestApiOperationKind = 'CONTROLLER' | 'SERVICE' | 'QUERY'

export interface RestApiParameterSnapshot {
  name: string
  javaType: string
  location: string
  required: boolean
  sourceLocator: GraphSourceLocator
}

export interface RestApiOperationSnapshot {
  artifactId: string
  kind: RestApiOperationKind
  displayName: string
  methods: string[]
  path: string
  moduleId: string
  parameters: RestApiParameterSnapshot[]
  entityArtifactIds: string[]
  entityNames: string[]
  implementationArtifactIds: string[]
  securedRoleIds: string[]
  transactionBoundary: string
  rowSecurity: string
  queryText?: string
  fetchPlanName?: string
  sourceLocator: GraphSourceLocator
}

export interface RestApiWorkspaceResponse {
  graphDigest: string
  operations: RestApiOperationSnapshot[]
  configs: {
    artifactId: string
    kind: 'SERVICES' | 'QUERIES'
    moduleId: string
    registered: boolean
    operationCount: number
    sourceLocator: GraphSourceLocator
  }[]
  apiRoles: {
    id: string
    name: string
    code: string
    scopes: string[]
    sourceLocator: GraphSourceLocator
  }[]
  security: {
    restProtected: boolean
    authenticatedPatterns: string
    anonymousPatterns: string
    restEnabledRoleCount: number
  }
  openApi: {
    genericJsonPath: string
    detailedJsonPath: string
    genericYamlPath: string
    detailedYamlPath: string
  }
  findings: {
    code: string
    severity: 'INFO' | 'WARNING' | 'ERROR' | 'BLOCKING'
    title: string
    message: string
    remediation?: string
    operationId?: string
    sourceLocator?: GraphSourceLocator
  }[]
  summary: {
    controllerCount: number
    serviceCount: number
    queryCount: number
    errorCount: number
    warningCount: number
  }
  error?: string
}

export interface RestApiInvocationRequest {
  baseUrl: string
  path: string
  method: string
  headers: Record<string, string>
  body: string
  timeoutMillis: number
}

export interface RestApiInvocationResponse {
  accepted: boolean
  status?: number
  durationMillis: number
  headers: Record<string, string[]>
  body: string
  truncated: boolean
  errorCode?: string
  message: string
}

export interface RestApiContractParameterInput {
  name: string
  javaType: string
}

export type RestApiContractInput =
  | {
      kind: 'SERVICE'
      serviceName: string
      methodName: string
      parameters: RestApiContractParameterInput[]
    }
  | {
      kind: 'QUERY'
      name: string
      entityName: string
      fetchPlan: string
      jpql: string
      parameters: RestApiContractParameterInput[]
    }

export interface RestApiContractAdditionRequest {
  moduleId: string
  configLocator: GraphSourceLocator
  contract: RestApiContractInput
}

export type RestApiContractTargetInput =
  | {
      kind: 'SERVICE'
      serviceName: string
      methodName: string
      parameterTypes: string[]
    }
  | {
      kind: 'QUERY'
      name: string
      entityName: string
    }

export interface RestApiContractMutationRequest {
  moduleId: string
  configLocator: GraphSourceLocator
  mode: 'UPDATE' | 'REMOVE'
  target: RestApiContractTargetInput
  replacement?: RestApiContractInput
}

export type WorkflowNodeType =
  | 'START'
  | 'MESSAGE_START'
  | 'SIGNAL_START'
  | 'TIMER_START'
  | 'ERROR_START'
  | 'HUMAN_STATE'
  | 'AUTOMATED_STATE'
  | 'SCRIPT_STATE'
  | 'ENTITY_DATA_STATE'
  | 'EMAIL_STATE'
  | 'DECISION'
  | 'PARALLEL_GATEWAY'
  | 'INCLUSIVE_GATEWAY'
  | 'BUSINESS_RULE_STATE'
  | 'EMBEDDED_SUBPROCESS'
  | 'EVENT_SUBPROCESS'
  | 'TRANSACTION_SUBPROCESS'
  | 'CALL_ACTIVITY'
  | 'TIMER_EVENT'
  | 'MESSAGE_CATCH'
  | 'SIGNAL_CATCH'
  | 'SIGNAL_THROW'
  | 'COMPENSATION_THROW'
  | 'BOUNDARY_TIMER'
  | 'BOUNDARY_MESSAGE'
  | 'BOUNDARY_SIGNAL'
  | 'BOUNDARY_ERROR'
  | 'BOUNDARY_COMPENSATION'
  | 'BOUNDARY_CANCEL'
  | 'ERROR_END'
  | 'CANCEL_END'
  | 'TERMINATE_END'
  | 'TERMINAL'

export type WorkflowMultiInstanceMode = 'NONE' | 'SEQUENTIAL' | 'PARALLEL'
export type WorkflowTimerType = 'DURATION' | 'DATE' | 'CYCLE'
export type WorkflowAuditLevel = 'BASIC' | 'FULL' | 'REGULATED'
export type WorkflowSignalScope = 'GLOBAL' | 'PROCESS_INSTANCE'
export type WorkflowEntityDataOperation = 'LOAD' | 'MODIFY' | 'CREATE'
export type WorkflowLoadResultMode = 'SINGLE' | 'COLLECTION'
export type WorkflowListenerImplementationType = 'EXPRESSION' | 'DELEGATE_EXPRESSION' | 'CLASS'
export type WorkflowEmailContentType = 'HTML' | 'PLAIN_TEXT'

export interface WorkflowEmailAttachmentModel {
  id: string
  name?: string
  expression: string
}

export interface WorkflowLaneModel {
  id: string
  name: string
  actorRoleCodes: string[]
}

export interface WorkflowListenerModel {
  event: string
  implementationType: WorkflowListenerImplementationType
  implementation: string
}

export interface WorkflowVariableMapping {
  source?: string
  sourceExpression?: string
  target: string
}

export type WorkflowFormType = 'NO_FORM' | 'INPUT_DIALOG' | 'JMIX_VIEW' | 'CUSTOM'
export type WorkflowFormOpenMode = 'DIALOG' | 'NAVIGATE'

export interface WorkflowProcessVariable {
  name: string
  type: string
}

export interface WorkflowFormField {
  id: string
  caption: string
  type: string
  editable: boolean
  required: boolean
  properties: Record<string, string>
}

export interface WorkflowFormOutcome {
  id: string
  caption: string
  icon?: string
}

export interface WorkflowFormData {
  type: WorkflowFormType
  openMode: WorkflowFormOpenMode
  screenId?: string
  businessKey?: string
  businessKeySource?: string
  fields: WorkflowFormField[]
  outcomes: WorkflowFormOutcome[]
}

export interface WorkflowNodeModel {
  id: string
  name: string
  type: WorkflowNodeType
  stateValue?: string
  actorRoleCodes: string[]
  assigneeExpression?: string
  formKey?: string
  formData?: WorkflowFormData
  processVariables: WorkflowProcessVariable[]
  dueDate?: string
  priority?: string
  serviceBean?: string
  serviceMethod?: string
  script?: string
  resultVariable?: string
  entityDataOperation: WorkflowEntityDataOperation
  entityName?: string
  entityVariable?: string
  jpql?: string
  saveLoadResultAs: WorkflowLoadResultMode
  jpqlParametersJson?: string
  entityAttributesJson?: string
  emailTo?: string
  emailCc?: string
  emailBcc?: string
  emailFrom?: string
  emailSubject?: string
  emailContent?: string
  emailContentType: WorkflowEmailContentType
  emailSendAsync: boolean
  emailAttachments: WorkflowEmailAttachmentModel[]
  async: boolean
  exclusive: boolean
  triggerable: boolean
  retryCycle?: string
  idempotencyKeyExpression?: string
  multiInstanceMode: WorkflowMultiInstanceMode
  loopCardinality?: string
  collectionExpression?: string
  elementVariable?: string
  completionCondition?: string
  calledElement?: string
  inheritBusinessKey: boolean
  inheritVariables: boolean
  decisionTableKey?: string
  timerType: WorkflowTimerType
  timerExpression?: string
  attachedToNodeId?: string
  cancelActivity: boolean
  eventStartInterrupting: boolean
  eventReference?: string
  signalScope: WorkflowSignalScope
  compensationActivityRef?: string
  compensationHandlerNodeId?: string
  forCompensation: boolean
  parentSubprocessId?: string
  laneId?: string
  executionListeners: WorkflowListenerModel[]
  taskListeners: WorkflowListenerModel[]
  inputMappings: WorkflowVariableMapping[]
  outputMappings: WorkflowVariableMapping[]
  defaultTransitionId?: string
  minimumApprovals?: number
  segregationOfDutyNodeIds: string[]
  requiredDocuments: string[]
  validationRules: string[]
  sideEffects: string[]
  notifications: string[]
  requiredPermissions: string[]
  documentation?: string
  x: number
  y: number
  width: number
  height: number
}

export interface WorkflowTransitionModel {
  id: string
  sourceId: string
  targetId: string
  name?: string
  conditionExpression?: string
  outcomeId?: string
  requiredRoleCodes: string[]
  requiredDocuments: string[]
  validationRules: string[]
  sideEffects: string[]
  notifications: string[]
}

export interface WorkflowModel {
  id: string
  name: string
  moduleId: string
  entityQualifiedName?: string
  stateAttribute?: string
  candidateStarterGroups: string[]
  candidateStarterUsers: string[]
  businessKeyExpression?: string
  versionTag?: string
  tenantExpression?: string
  auditLevel: WorkflowAuditLevel
  lanes: WorkflowLaneModel[]
  executionListeners: WorkflowListenerModel[]
  sourceRelativePath?: string
  sourceFingerprint?: string
  documentation?: string
  nodes: WorkflowNodeModel[]
  transitions: WorkflowTransitionModel[]
}

export interface WorkflowLoadResponse {
  workflow?: WorkflowModel
  editable: boolean
  unsupportedElements: string[]
  warnings: string[]
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

export interface SecurityJourneySnapshot {
  menuArtifactId: string
  menuId: string
  menuPathArtifactIds: string[]
  menuPathIds: string[]
  viewArtifactId?: string
  viewId?: string
  entityArtifactIds: string[]
  attributeArtifactIds: string[]
  componentArtifactIds: string[]
  unresolvedDependencyCount: number
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
  journeys: SecurityJourneySnapshot[]
  findings: SecurityFindingSnapshot[]
  runtime: RuntimeSecurityEvidenceSnapshot
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

export type RuntimeSecurityEvidenceSeverity = 'INFO' | 'WARNING' | 'ERROR'

export interface RuntimeSecurityEvidenceIssue {
  code: string
  severity: RuntimeSecurityEvidenceSeverity
  message: string
  sourceId?: string
  roleId?: string
  username?: string
}

export type RuntimeSecurityEvidenceFormat =
  | 'JMIX_RESOURCE_ROLE_JSON'
  | 'JMIX_ROW_LEVEL_ROLE_JSON'
  | 'JMIX_ROLE_ASSIGNMENT_JSON'
  | 'JMIX_MIXED_ENTITY_JSON'
  | 'JMIX_WORKBENCH_EVIDENCE_V1'

export interface RuntimeSecurityEvidenceSourceSnapshot {
  id: string
  fileName: string
  environmentLabel?: string
  format: RuntimeSecurityEvidenceFormat
  sha256: string
  importedAt: string
  roleCount: number
  policyCount: number
  assignmentCount: number
}

export interface RuntimeSecurityRoleSnapshot {
  id: string
  name: string
  code: string
  description?: string
  kind: 'RESOURCE' | 'ROW_LEVEL'
  scopes: string[]
  policyIds: string[]
  inheritedRoleIds: string[]
  unresolvedChildRoleCodes: string[]
  tenantId?: string
  evidenceSourceId: string
}

export interface RuntimeSecurityPolicySnapshot {
  id: string
  roleId: string
  type: string
  effect: 'GRANT' | 'RESTRICT' | 'DENY' | 'UNKNOWN'
  actions: string[]
  resourceExpressions: string[]
  targetArtifactIds: string[]
  wildcard: boolean
  condition?: string
  policyGroup?: string
  evidenceSourceId: string
}

export type RuntimeRoleAssignmentResolution = 'RESOLVED' | 'MISSING_ROLE' | 'AMBIGUOUS_ROLE'

export interface RuntimeRoleAssignmentSnapshot {
  id: string
  username: string
  roleCode: string
  roleKind: 'RESOURCE' | 'ROW_LEVEL'
  tenantId?: string
  candidateRoleIds: string[]
  resolution: RuntimeRoleAssignmentResolution
  evidenceSourceId: string
}

export interface RuntimeSecurityEvidenceSnapshot {
  sources: RuntimeSecurityEvidenceSourceSnapshot[]
  roles: RuntimeSecurityRoleSnapshot[]
  policies: RuntimeSecurityPolicySnapshot[]
  assignments: RuntimeRoleAssignmentSnapshot[]
  principals: string[]
  issues: RuntimeSecurityEvidenceIssue[]
  summary: {
    sourceCount: number
    roleCount: number
    policyCount: number
    assignmentCount: number
    principalCount: number
    errorCount: number
    warningCount: number
  }
}

export interface RuntimeSecurityEvidenceImportRequest {
  fileName: string
  contentBase64: string
  environmentLabel?: string
}

export interface RuntimeSecurityEvidenceImportResponse {
  accepted: boolean
  sourceId?: string
  message: string
  issues: RuntimeSecurityEvidenceIssue[]
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

export interface WorkspaceHistorySnapshot {
  canUndo: boolean
  undoLabel?: string
  undoDepth: number
  canRedo: boolean
  redoLabel?: string
  redoDepth: number
}

export interface WorkspaceHistoryMutationResponse {
  success: boolean
  message: string
  changedFiles: string[]
  revisions: Record<string, string>
  history: WorkspaceHistorySnapshot
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

export type FlowUiStructureOperation =
  | 'INSERT_CHILD'
  | 'DELETE'
  | 'MOVE_UP'
  | 'MOVE_DOWN'
  | 'REPARENT'
  | 'COPY_SUBTREE'
  | 'WRAP'
  | 'CONVERT_LAYOUT'

export interface FlowUiStructureChangeRequest {
  sourceLocator: GraphSourceLocator
  operation: FlowUiStructureOperation
  elementKey?: string
  parentKey?: string
  tagName?: string
  attributes?: Record<string, string>
  childCapable?: boolean
  beforeElementKey?: string
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
  issues?: FlowUiControllerIssueSnapshot[]
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
  issues?: FlowUiControllerIssueSnapshot[]
}

export interface FlowUiControllerIssueSnapshot {
  code: string
  message: string
  severity: 'ERROR' | 'WARNING'
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
