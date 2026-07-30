import type {
  ApplicationGraphResponse,
  DmnDecisionModel,
  DmnDecisionWorkspaceResponse,
  DmnSimulationResult,
  FlowUiPropertyChangeRequest,
  FlowUiStructureChangeRequest,
  FlowUiDirectTextChangeRequest,
  FlowUiControllerInjectionRequest,
  FlowUiControllerHandlerRequest,
  FlowUiWorkspaceResponse,
  ExistingEntityAttributeAdditionRequest,
  DatabaseEntityTableInspectionRequest,
  DatabaseEntityTableInspectionResponse,
  DatabaseEntityTableBrowseRequest,
  DatabaseEntityTableBrowseResponse,
  DatabaseTableReference,
  DatabaseEntityImportRequest,
  DatabaseEntityImportPlanResponse,
  DatabaseEntityImportProfileWorkspaceResponse,
  DatabaseColumnSnapshot,
  EntityAttributePropagationChangeRequest,
  EntityAttributePropagationInspectionRequest,
  EntityAttributePropagationInspectionResponse,
  EntityAttributeRenameRequest,
  EntityAttributeRenameLaunchResponse,
  EntityAttributeSafeDeleteRequest,
  EntityAttributeSafeDeleteLaunchResponse,
  EntityAttributeTypeMigrationRequest,
  EntityAttributeTypeMigrationLaunchResponse,
  EntityAttributeTypeExpansionPreviewResponse,
  EntityAttributeTypeExpansionVerificationResponse,
  EntityAttributeTypeMappingCutoverRequest,
  GenerationResult,
  GraphSourceLocator,
  IntegrationConnectorModel,
  IntegrationConnectorWorkspaceResponse,
  JmixFlowUiHotDeployRequest,
  JmixRuntimeActionResponse,
  JmixRuntimeInspectionResponse,
  JmixRuntimeViewport,
  ProjectConfig,
  RuntimeSecurityEvidenceImportRequest,
  RuntimeSecurityEvidenceImportResponse,
  RuntimeSecurityEvidenceSnapshot,
  RestApiInvocationRequest,
  RestApiInvocationResponse,
  RestApiContractAdditionRequest,
  RestApiContractMutationRequest,
  RestApiWorkspaceResponse,
  ScenarioTestModel,
  ScenarioWorkspaceResponse,
  SchemaMigrationChangeRequest,
  SchemaWorkspaceResponse,
  SecurityWorkspaceSnapshot,
  SecurityRoleCreateRequest,
  SecurityRoleDestinationsResponse,
  SecurityRolePolicyChangeRequest,
  SecurityRolePolicyInspectionRequest,
  SecurityRolePolicyInspectionResponse,
  SecurityRolePolicyReplacementRequest,
  SecurityRolePolicyRemovalRequest,
  SourceNavigationResponse,
  WorkspaceChangeApplyResponse,
  WorkspaceHistoryMutationResponse,
  WorkspaceHistorySnapshot,
  WorkspaceChangePreviewResponse,
  WorkspaceChangeSet,
  WorkflowLoadResponse,
  WorkflowModel,
  VisualLogicClassModel,
  VisualLogicWorkspaceResponse,
  VisualRuleModel,
  VisualRuleWorkspaceResponse,
  WorkbenchLaunchContext,
} from '../types'
import {
  developmentApplicationGraph,
  developmentDmnDecisionWorkspace,
  developmentFlowUiWorkspace,
  developmentIntegrationConnectorWorkspace,
  developmentProjectConfig,
  developmentRestApiWorkspace,
  developmentScenarioWorkspace,
  developmentSchemaWorkspace,
  developmentSecurityWorkspace,
  developmentVisualLogicWorkspace,
  developmentVisualRuleWorkspace,
} from './devMocks'

type BridgeCallback = (action: string, requestId: string | null, result: any) => void

let developmentRuntimeSecurity: RuntimeSecurityEvidenceSnapshot = developmentSecurityWorkspace.runtime
let developmentHistory: WorkspaceHistorySnapshot = {
  canUndo: false,
  undoDepth: 0,
  canRedo: false,
  redoDepth: 0,
}

function developmentDatabaseColumn(
  name: string,
  typeName: string,
  attributeType: DatabaseColumnSnapshot['suggestion']['attributeType'],
  primaryKey: boolean,
  alreadyMapped: boolean,
  size?: number,
  suggestionOverrides: Partial<DatabaseColumnSnapshot['suggestion']> = {},
): DatabaseColumnSnapshot {
  return {
    name,
    jdbcType: 0,
    typeName,
    size,
    nullable: !primaryKey,
    autoIncrement: false,
    generated: false,
    ordinal: 1,
    primaryKey,
    alreadyMapped,
    suggestion: {
      attributeName: name.toLowerCase().replace(/_([a-z])/g, (_, letter) => letter.toUpperCase()),
      attributeType,
      javaType: suggestionOverrides.javaType ?? 'java.lang.String',
      primaryKey,
      mandatory: primaryKey,
      length: size,
      ...suggestionOverrides,
    },
  }
}

declare global {
  interface Window {
    javaBridge?: {
      send: (action: string, payload: any, requestId: string) => void
    }
    onBridgeResponse?: (action: string, requestId: string | null, result: any) => void
    onBridgeReady?: () => void
    jmixWorkbenchLaunchContext?: WorkbenchLaunchContext | null
    onWorkbenchLaunchContext?: (context: WorkbenchLaunchContext | null) => void
  }
}

class Bridge {
  private listeners: BridgeCallback[] = []
  private launchContextListeners: ((context: WorkbenchLaunchContext | null) => void)[] = []
  private launchContext: WorkbenchLaunchContext | null =
    window.jmixWorkbenchLaunchContext ?? this.developmentLaunchContext()
  private ready = false
  private requestSequence = 0
  private pendingQueue: { action: string; payload: any; requestId: string }[] = []

  constructor() {
    window.onBridgeResponse = (action: string, requestId: string | null, result: any) => {
      this.listeners.forEach(cb => cb(action, requestId, result))
    }

    window.onBridgeReady = () => {
      this.ready = true
      this.pendingQueue.forEach(({ action, payload, requestId }) => this.send(action, payload, requestId))
      this.pendingQueue = []
    }

    window.onWorkbenchLaunchContext = (context) => {
      this.launchContext = context
      this.launchContextListeners.forEach((listener) => listener(context))
    }

    // If bridge is already available (e.g., dev mode without JCEF)
    if (window.javaBridge) {
      this.ready = true
    }
  }

  private developmentLaunchContext(): WorkbenchLaunchContext | null {
    const document = developmentFlowUiWorkspace.document
    if (
      !import.meta.env.DEV ||
      window.location.pathname !== '/flowui-editor.html' ||
      !document
    ) {
      return null
    }
    return {
      surface: 'FLOW_UI_EDITOR',
      sourceLocator: {
        relativePath: document.relativePath,
        revisionFingerprint: document.revisionFingerprint,
      },
    }
  }

  send(action: string, payload: any = {}, requestId: string = this.nextRequestId()) {
    if (!this.ready || !window.javaBridge) {
      // In dev mode, simulate response
      if (import.meta.env.DEV) {
        console.log(`[Bridge] ${action}`, payload)
        setTimeout(() => {
          const result = (() => {
            switch (action) {
              case 'getApplicationGraph':
                return developmentApplicationGraph
              case 'getScenarioWorkspace':
                return developmentScenarioWorkspace
              case 'getVisualLogicWorkspace':
                return developmentVisualLogicWorkspace
              case 'getIntegrationConnectorWorkspace':
                return developmentIntegrationConnectorWorkspace
              case 'getVisualRuleWorkspace':
                return developmentVisualRuleWorkspace
              case 'getDmnDecisionWorkspace':
                return developmentDmnDecisionWorkspace
              case 'previewVisualLogic': {
                const model = payload as VisualLogicClassModel
                const destination = developmentVisualLogicWorkspace.destinations.find(
                  (candidate) => candidate.id === model.destinationId,
                ) ?? developmentVisualLogicWorkspace.destinations[0]
                const relativePath = `${destination.sourceRoot}/${model.packageName.replace(/\./g, '/')}/${model.className}.java`
                return {
                  accepted: true,
                  changeSetId: 'visual-logic:development',
                  label: `${model.sourceLocator ? 'Update' : 'Create'} visual service ${model.name}`,
                  planDigest: 'development-visual-logic',
                  files: [{
                    relativePath,
                    mode: model.sourceLocator ? 'MODIFY' : 'CREATE',
                    beforeFingerprint: model.sourceLocator?.revisionFingerprint,
                    afterFingerprint: 'development-visual-logic-after',
                    resultContent: `package ${model.packageName};

import org.springframework.stereotype.Service;

@Service("${model.beanName}")
public class ${model.className} {
    // ${model.methods.length} typed visual method(s)
}
`,
                    appliedEditCount: model.sourceLocator ? 1 : 0,
                  }],
                  issues: [],
                } satisfies WorkspaceChangePreviewResponse
              }
              case 'applyVisualLogic':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate ${payload.model?.name ?? 'visual service'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'visual-logic:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [`${payload.model?.className ?? 'VisualService'}.java`],
                  issues: [],
                } satisfies WorkspaceChangeApplyResponse
              case 'previewIntegrationConnector': {
                const model = payload as IntegrationConnectorModel
                const destination = developmentIntegrationConnectorWorkspace.destinations.find(
                  (candidate) => candidate.id === model.destinationId,
                ) ?? developmentIntegrationConnectorWorkspace.destinations[0]
                const javaPath = `${destination.sourceRoot}/${model.packageName.replace(/\./g, '/')}/${model.className}.java`
                const policyPath = `${destination.resourceRoot}/META-INF/jvw/integration/${model.beanName}.properties`
                return {
                  accepted: true,
                  changeSetId: 'integration-connector:development',
                  label: `${model.sourceLocator ? 'Update' : 'Create'} integration connector ${model.name}`,
                  planDigest: 'development-integration-connector',
                  files: [
                    {
                      relativePath: javaPath,
                      mode: model.sourceLocator ? 'MODIFY' : 'CREATE',
                      beforeFingerprint: model.sourceLocator?.revisionFingerprint,
                      afterFingerprint: 'development-integration-java',
                      resultContent: `package ${model.packageName};

// JVW-INTEGRATION-MODEL: development
public final class ${model.className} {
    // ${model.kind} adapter with externalized configuration and reliability policies
}
`,
                      appliedEditCount: model.sourceLocator ? 1 : 0,
                    },
                    {
                      relativePath: policyPath,
                      mode: model.sourceLocator ? 'MODIFY' : 'CREATE',
                      afterFingerprint: 'development-integration-policy',
                      resultContent: `# Owned reliability policy for ${model.beanName}
# endpoint/topic/queue and secrets remain externalized
`,
                      appliedEditCount: model.sourceLocator ? 1 : 0,
                    },
                  ],
                  issues: [],
                } satisfies WorkspaceChangePreviewResponse
              }
              case 'applyIntegrationConnector':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate ${payload.model?.name ?? 'integration connector'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'integration-connector:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    `${payload.model?.className ?? 'IntegrationConnector'}.java`,
                    `${payload.model?.beanName ?? 'integrationConnector'}.properties`,
                  ],
                  issues: [],
                } satisfies WorkspaceChangeApplyResponse
              case 'previewVisualRule': {
                const model = payload as VisualRuleModel
                const destination = developmentVisualRuleWorkspace.destinations.find(
                  (candidate) => candidate.id === model.destinationId,
                ) ?? developmentVisualRuleWorkspace.destinations[0]
                const relativePath = `${destination.sourceRoot}/${model.packageName.replace(/\./g, '/')}/${model.className}.java`
                return {
                  accepted: true,
                  changeSetId: 'visual-rule:development',
                  label: `${model.sourceLocator ? 'Update' : 'Create'} visual rule ${model.name}`,
                  planDigest: 'development-visual-rule',
                  files: [{
                    relativePath,
                    mode: model.sourceLocator ? 'MODIFY' : 'CREATE',
                    beforeFingerprint: model.sourceLocator?.revisionFingerprint,
                    afterFingerprint: 'development-visual-rule-after',
                    resultContent: `package ${model.packageName};

import org.springframework.stereotype.Component;

@Component("${model.beanName}")
public class ${model.className} {
    // Pure typed ${model.kind.toLowerCase()} compiled from ${model.expression.kind}
}
`,
                    appliedEditCount: model.sourceLocator ? 1 : 0,
                  }],
                  issues: [],
                } satisfies WorkspaceChangePreviewResponse
              }
              case 'applyVisualRule':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate ${payload.model?.name ?? 'visual rule'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'visual-rule:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [`${payload.model?.className ?? 'VisualRule'}.java`],
                  issues: [],
                } satisfies WorkspaceChangeApplyResponse
              case 'previewDmnDecision': {
                const model = payload as DmnDecisionModel
                const destination = developmentDmnDecisionWorkspace.destinations.find(
                  (candidate) => candidate.id === model.destinationId,
                ) ?? developmentDmnDecisionWorkspace.destinations[0]
                const relativePath = `${destination.dmnDirectory}/${model.fileName}`
                return {
                  accepted: true,
                  changeSetId: 'dmn-decision:development',
                  label: `${model.sourceLocator ? 'Update' : 'Create'} DMN decision ${model.name}`,
                  planDigest: 'development-dmn-decision',
                  files: [{
                    relativePath,
                    mode: model.sourceLocator ? 'MODIFY' : 'CREATE',
                    beforeFingerprint: model.sourceLocator?.revisionFingerprint,
                    afterFingerprint: 'development-dmn-after',
                    resultContent: `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/DMN/20151101">
  <decision id="${model.key}" name="${model.name}">
    <decisionTable hitPolicy="${model.hitPolicy}">
      <!-- ${model.inputs.length} inputs, ${model.outputs.length} outputs, ${model.rules.length} rules -->
    </decisionTable>
  </decision>
</definitions>
`,
                    appliedEditCount: model.sourceLocator ? 1 : 0,
                  }],
                  issues: [],
                } satisfies WorkspaceChangePreviewResponse
              }
              case 'applyDmnDecision':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate ${payload.model?.name ?? 'DMN decision'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'dmn-decision:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [`${payload.model?.fileName ?? 'decision.dmn'}`],
                  issues: [],
                } satisfies WorkspaceChangeApplyResponse
              case 'simulateDmnDecision': {
                const model = payload.model as DmnDecisionModel
                const enabledRule = model.rules.find((rule) => rule.enabled)
                return {
                  accepted: Boolean(enabledRule),
                  matchedRuleIds: enabledRule ? [enabledRule.id] : [],
                  results: enabledRule ? [enabledRule.outputEntries] : [],
                  diagnostics: [],
                } satisfies DmnSimulationResult
              }
              case 'previewScenarioTest': {
                const scenario = payload as ScenarioTestModel
                const destination = developmentScenarioWorkspace.destinations.find(
                  (candidate) => candidate.id === scenario.destinationId,
                ) ?? developmentScenarioWorkspace.destinations[0]
                const relativePath = `${destination.testSourceRoot}/${scenario.packageName.replace(/\./g, '/')}/${scenario.className}.java`
                return {
                  accepted: true,
                  changeSetId: 'scenario-test:development',
                  label: `Create integration scenario ${scenario.name}`,
                  planDigest: 'development-scenario-test',
                  files: [{
                    relativePath,
                    mode: scenario.sourceLocator ? 'MODIFY' : 'CREATE',
                    beforeFingerprint: scenario.sourceLocator?.revisionFingerprint,
                    afterFingerprint: 'development-scenario-after',
                    resultContent: `package ${scenario.packageName};

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ${scenario.className} {
    @Test
    void ${scenario.name.replace(/[^A-Za-z0-9]+/g, '_').replace(/^_+|_+$/g, '').toLowerCase() || 'executes_scenario'}() {
        // ${scenario.steps.length} visual steps generated with DataManager and security context
    }
}
`,
                    appliedEditCount: scenario.sourceLocator ? 1 : 0,
                  }],
                  issues: [],
                }
              }
              case 'applyScenarioTest':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate scenario ${payload.scenario?.name ?? 'test'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'scenario-test:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [`${payload.scenario?.className ?? 'ScenarioTest'}.java`],
                  issues: [],
                }
              case 'getMenuWorkspace':
                return {
                  sources: [],
                  warnings: [],
                  springBeans: [
                    {
                      name: 'PayrollMenu',
                      declarationName: 'PayrollMenu',
                      sourcePath: 'src/main/java/com/company/payroll/menu/PayrollMenu.java',
                      language: 'JAVA',
                      ambiguous: false,
                      methods: [
                        {
                          name: 'closePeriod',
                          signature: 'closePeriod()',
                          callable: true,
                        },
                        {
                          name: 'openReport',
                          signature: 'openReport(Map<String, Object>)',
                          callable: true,
                        },
                      ],
                    },
                  ],
                }
              case 'getSchemaWorkspace':
                return developmentSchemaWorkspace
              case 'getDatabaseEntityImportProfiles':
                return {
                  profiles: [{
                    profile: {
                      schemaVersion: 1,
                      id: 'loan-accounts',
                      label: 'Loan accounts database model',
                      request: {
                        storeId: 'loan:main',
                        moduleId: 'loan',
                        packageName: 'com.company.loan.entity',
                        sourceLanguage: 'java',
                        selectedTables: [{
                          catalog: 'payroll',
                          schema: 'public',
                          name: 'LOAN_ACCT',
                          type: 'TABLE',
                          remarks: 'Loan accounts',
                        }],
                        includeDependencies: true,
                        identifierOverrides: {},
                        classNameOverrides: {},
                        profileId: 'loan-accounts',
                        profileLabel: 'Loan accounts database model',
                      },
                      baselineSnapshotDigest: 'development-database-entity-graph',
                      database: {
                        name: 'PostgreSQL',
                        version: '17',
                        driverName: 'PostgreSQL JDBC Driver',
                        driverVersion: '42.7',
                        urlFingerprint: 'development-db',
                      },
                      tables: [],
                    },
                    sourceLocator: {
                      relativePath: '.jmix-workbench/database-imports/loan-accounts.json',
                      revisionFingerprint: 'development-profile-revision',
                    },
                  }],
                  issues: [],
                } satisfies DatabaseEntityImportProfileWorkspaceResponse
              case 'getRestApiWorkspace':
                return developmentRestApiWorkspace
              case 'invokeRestApi':
                return {
                  accepted: false,
                  durationMillis: 3,
                  headers: {},
                  body: '',
                  truncated: false,
                  errorCode: 'JVW-REST-INVOKE-DEVELOPMENT-PREVIEW',
                  message: 'Start the Jmix application and use the packaged plugin to execute loopback API requests.',
                } satisfies RestApiInvocationResponse
              case 'previewRestApiContractAddition':
                return {
                  accepted: true,
                  changeSetId: 'rest-contract-add:development',
                  label: 'Expose Jmix REST contract',
                  planDigest: 'development-rest-contract',
                  files: [{
                    relativePath: payload.configLocator?.relativePath ?? 'src/main/resources/rest-services.xml',
                    mode: 'MODIFY',
                    beforeFingerprint: payload.configLocator?.revisionFingerprint,
                    afterFingerprint: 'development-rest-contract-after',
                    originalContent: '<services xmlns="http://jmix.io/schema/rest/services">\n</services>\n',
                    resultContent: '<services xmlns="http://jmix.io/schema/rest/services">\n    <!-- reviewed visual contract -->\n</services>\n',
                    appliedEditCount: 1,
                  }],
                  issues: [],
                }
              case 'applyRestApiContractAddition':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: 'Expose Jmix REST contract',
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'rest-contract-add:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change?.configLocator?.relativePath ?? 'src/main/resources/rest-services.xml'],
                  issues: [],
                }
              case 'previewRestApiContractMutation':
                return {
                  accepted: true,
                  changeSetId: 'rest-contract-change:development',
                  label: `${payload.mode === 'REMOVE' ? 'Remove' : 'Update'} Jmix REST contract`,
                  planDigest: 'development-rest-contract-change',
                  files: [{
                    relativePath: payload.configLocator?.relativePath ?? 'src/main/resources/rest-services.xml',
                    mode: 'MODIFY',
                    beforeFingerprint: payload.configLocator?.revisionFingerprint,
                    afterFingerprint: 'development-rest-contract-change-after',
                    originalContent: '<services xmlns="http://jmix.io/schema/rest/services">\n    <service name="existing"/>\n</services>\n',
                    resultContent: payload.mode === 'REMOVE'
                      ? '<services xmlns="http://jmix.io/schema/rest/services">\n</services>\n'
                      : '<services xmlns="http://jmix.io/schema/rest/services">\n    <!-- surgically updated contract -->\n</services>\n',
                    appliedEditCount: 2,
                  }],
                  issues: [],
                }
              case 'applyRestApiContractMutation':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `${payload.change?.mode === 'REMOVE' ? 'Remove' : 'Update'} Jmix REST contract`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'rest-contract-change:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change?.configLocator?.relativePath ?? 'src/main/resources/rest-services.xml'],
                  issues: [],
                }
              case 'previewSchemaMigration': {
                const change = payload as SchemaMigrationChangeRequest
                const store = developmentSchemaWorkspace.stores.find((candidate) => candidate.id === change.storeId)
                const relativePath = `${store?.generatedDirectory ?? 'src/main/resources/db/changelog'}/2026/07/${change.fileName ?? change.migration.changelogId}.xml`
                return {
                  accepted: true,
                  changeSetId: 'schema-migration:development',
                  label: `Create ${store?.name ?? 'main'} Liquibase migration ${change.migration.changelogId}`,
                  planDigest: 'development-schema-migration',
                  files: [{
                    relativePath,
                    mode: 'CREATE',
                    afterFingerprint: 'development-schema-after',
                    resultContent: '<databaseChangeLog><!-- source-safe migration preview --></databaseChangeLog>',
                    appliedEditCount: 0,
                  }],
                  issues: [],
                }
              }
              case 'applySchemaMigration':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: 'Create Liquibase migration',
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'schema-migration:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: ['loan/src/main/resources/com/company/loan/liquibase/changelog/2026/07/development.xml'],
                  issues: [],
                }
              case 'previewEntityGeneration': {
                const entity = payload as any
                const store = developmentSchemaWorkspace.stores.find(
                  (candidate) => candidate.id === entity.generationTarget?.storeId,
                ) ?? developmentSchemaWorkspace.stores[0]
                const packagePath = String(entity.packageName ?? 'com.example.app.entity').replace(/\./g, '/')
                const modulePrefix = store?.moduleId ? `${store.moduleId}/` : ''
                const tableStem = String(entity.tableName || entity.className)
                  .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
                  .toLowerCase()
                const files = [
                  `${modulePrefix}src/main/java/${packagePath}/${entity.className || 'NewEntity'}.java`,
                  `${modulePrefix}src/main/resources/${packagePath}/messages.properties`,
                ]
                if (
                  entity.databaseView !== true &&
                  entity.ddlGeneration?.enabled !== false &&
                  entity.ddlGeneration?.mode !== 'disabled' &&
                  store?.generatedDirectory
                ) {
                  files.push(
                    `${store.generatedDirectory}/2026/07/29-create-${tableStem}.xml`,
                  )
                }
                return {
                  accepted: true,
                  changeSetId: 'generation:development-entity',
                  label: `Create Jmix entity ${entity.className || 'NewEntity'}`,
                  planDigest: 'development-entity-generation',
                  files: files.map((relativePath) => ({
                    relativePath,
                    mode: relativePath.endsWith('messages.properties') ? 'MODIFY' : 'CREATE',
                    afterFingerprint: `development-${relativePath}`,
                    resultContent: relativePath.endsWith('.java')
                      ? `package ${entity.packageName};\n\npublic class ${entity.className} {}\n`
                      : '# source-safe generated preview\n',
                    appliedEditCount: relativePath.endsWith('messages.properties') ? 1 : 0,
                  })),
                  issues: [],
                }
              }
              case 'applyEntityGeneration':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Create Jmix entity ${payload.entity?.className ?? ''}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'generation:development-entity',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    `loan/src/main/java/${String(payload.entity?.packageName ?? '').replace(/\./g, '/')}/${payload.entity?.className}.java`,
                  ],
                  issues: [],
                }
              case 'previewExistingEntityAttributeAdditions': {
                const change = payload as any
                const entity = change.entity ?? {}
                const sourcePath = change.sourceLocator?.relativePath ??
                  'loan/src/main/java/com/company/loan/entity/LoanApp.java'
                const currentEntity = developmentSchemaWorkspace.entities
                  .find(candidate => candidate.qualifiedName === `${entity.packageName}.${entity.className}`)
                const additions = (entity.attributes ?? []).filter((attribute: any) =>
                  !currentEntity?.attributes.some(existing => existing.name === attribute.name),
                )
                const mappingChanges = (entity.attributes ?? []).filter((attribute: any) => {
                  const current = currentEntity?.attributes.find(existing => existing.name === attribute.name)
                  if (!current || current.association || !current.persistent) return false
                  return attribute.mandatory !== !current.nullable ||
                    attribute.unique !== current.unique ||
                    (attribute.length ?? 255) !== (current.length ?? 255) ||
                    attribute.precision !== current.precision ||
                    attribute.scale !== current.scale
                })
                if (additions.length === 0 && mappingChanges.length === 0) {
                  return {
                    accepted: false,
                    changeSetId: 'existing-entity-update:rejected',
                    label: 'Existing entity update rejected',
                    files: [],
                    issues: [{
                      code: 'JVW-ENTITY-UPDATE-NOOP',
                      message: 'Change a safe mapping or add an attribute before previewing the update.',
                    }],
                  }
                }
                const migrationPath =
                  `loan/src/main/resources/com/company/loan/liquibase/changelog/2026/07/29-update-${String(entity.tableName ?? entity.className).toLowerCase()}.xml`
                return {
                  accepted: true,
                  changeSetId: 'existing-entity-update:development',
                  label: `Update ${entity.className}: ${[
                    additions.length
                      ? `add ${additions.length} attribute${additions.length === 1 ? '' : 's'}`
                      : '',
                    mappingChanges.length
                      ? `change ${mappingChanges.length} mapping${mappingChanges.length === 1 ? '' : 's'}`
                      : '',
                  ].filter(Boolean).join(', ')}`,
                  planDigest: 'development-existing-entity-update',
                  files: [
                    {
                      relativePath: sourcePath,
                      mode: 'MODIFY',
                      beforeFingerprint: change.sourceLocator?.revisionFingerprint,
                      afterFingerprint: 'development-existing-entity-after',
                      resultContent: [
                        '// Existing source preserved',
                        additions.length
                          ? `// Added: ${additions.map((attribute: any) => attribute.name).join(', ')}`
                          : '',
                        mappingChanges.length
                          ? `// Mapping updates: ${mappingChanges.map((attribute: any) => attribute.name).join(', ')}`
                          : '',
                      ].filter(Boolean).join('\n'),
                      appliedEditCount: 2,
                    },
                    {
                      relativePath: migrationPath,
                      mode: 'CREATE',
                      afterFingerprint: 'development-existing-entity-migration',
                      resultContent: '<databaseChangeLog><!-- safe entity migration with rollback --></databaseChangeLog>',
                      appliedEditCount: 0,
                    },
                  ],
                  issues: [],
                }
              }
              case 'applyExistingEntityAttributeAdditions':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Update ${payload.change?.entity?.className ?? 'entity'}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'existing-entity-update:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    payload.change?.sourceLocator?.relativePath ??
                      'loan/src/main/java/com/company/loan/entity/LoanApp.java',
                    'loan/src/main/resources/com/company/loan/liquibase/changelog/2026/07/29-update-loan_app.xml',
                  ],
                  issues: [],
                }
              case 'launchEntityAttributeRename':
                return {
                  success: true,
                  message: `IntelliJ usage preview opened for ${payload.attributeName} → ${payload.newName}.`,
                }
              case 'launchEntityAttributeSafeDelete':
                return {
                  success: true,
                  message: `IntelliJ Safe Delete usage preview opened for ${payload.attributeName}. Database mapping is retained for separate migration review.`,
                  retainedColumnName: payload.attributeName
                    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
                    .toUpperCase(),
                }
              case 'launchEntityAttributeTypeMigration':
                return {
                  success: payload.targetType === 'uri' || Boolean(payload.verificationToken),
                  code: payload.targetType === 'uri' || payload.verificationToken
                    ? undefined
                    : 'JVW-ENTITY-TYPE-MIGRATION-SCHEMA-STAGE-REQUIRED',
                  message: payload.targetType === 'uri' || payload.verificationToken
                    ? `IntelliJ project-wide Type Migration preview opened for ${payload.attributeName} → ${payload.targetType}.`
                    : `Schema expansion is required before ${payload.attributeName} can migrate to ${payload.targetType}.`,
                  sourceLanguage: 'java',
                  schemaImpact: {
                    strategy: payload.targetType === 'uri'
                      ? 'SOURCE_ONLY'
                      : 'EXPAND_CONTRACT_REQUIRED',
                    storeId: 'loan:main',
                    tableName: 'LOAN_APP',
                    columnName: payload.attributeName
                      .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
                      .toUpperCase(),
                    currentSqlType: 'INT',
                    targetSqlType: payload.targetType === 'long'
                      ? 'BIGINT'
                      : payload.targetType === 'uri' ? 'INT' : 'DOUBLE',
                    dependencies: ['index IDX_LOAN_APP_STATUS'],
                    summary: payload.targetType === 'uri'
                      ? 'No physical type rewrite is required.'
                      : 'The mapped column requires a reviewed data conversion. This is not automatically reversible.',
                  },
                }
              case 'previewEntityAttributeTypeExpansion':
                return {
                  accepted: true,
                  message: `Expansion preview is ready for ${payload.attributeName}.`,
                  shadowColumnName: 'JVE_91A8B2C_LOAN_AMOUNT',
                  targetSqlType: payload.targetType === 'long' ? 'BIGINT' : 'DOUBLE',
                  preview: {
                    accepted: true,
                    changeSetId: 'entity-type-expansion:development',
                    label: `Expand ${payload.attributeName} safely`,
                    planDigest: 'development-entity-type-expansion',
                    files: [{
                      relativePath: 'loan/src/main/resources/com/company/loan/liquibase/changelog/2026/07/30-entity-type-expand.xml',
                      mode: 'CREATE',
                      afterFingerprint: 'development-expansion-after',
                      resultContent: '<databaseChangeLog><!-- shadow column + backfill + rollback --></databaseChangeLog>',
                      appliedEditCount: 0,
                    }],
                    issues: [],
                  },
                }
              case 'applyEntityAttributeTypeExpansion':
                return {
                  success: true,
                  changeSetId: 'entity-type-expansion:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    'loan/src/main/resources/com/company/loan/liquibase/changelog/2026/07/30-entity-type-expand.xml',
                  ],
                  issues: [],
                }
              case 'verifyEntityAttributeTypeExpansion':
                return {
                  accepted: true,
                  message: 'Live database verified: the shadow type and every deployed backfill row are complete.',
                  verificationToken: 'development-expansion-verification',
                  expiresAtEpochMillis: Date.now() + 20 * 60 * 1000,
                  evidenceDigest: 'development-live-expansion-evidence',
                  shadowColumnName: 'JVE_91A8B2C_LOAN_AMOUNT',
                  targetSqlType: payload.targetType === 'long' ? 'BIGINT' : 'DOUBLE',
                  inconsistentBackfillRows: 0,
                  database: {
                    name: 'PostgreSQL',
                    version: '17',
                    driverName: 'PostgreSQL JDBC Driver',
                    driverVersion: '42.7',
                    urlFingerprint: 'development-db',
                  },
                }
              case 'previewEntityAttributeTypeMappingCutover':
                return {
                  accepted: true,
                  changeSetId: 'entity-type-mapping-cutover:development',
                  label: `Switch ${payload.entityClassName}.${payload.attributeName} to verified shadow`,
                  planDigest: 'development-mapping-cutover',
                  files: [{
                    relativePath: payload.sourceLocator?.relativePath ??
                      'loan/src/main/java/com/company/loan/entity/LoanApp.java',
                    mode: 'MODIFY',
                    beforeFingerprint: payload.sourceLocator?.revisionFingerprint,
                    afterFingerprint: 'development-mapping-cutover-after',
                    originalContent: '@Column(name = "LOAN_AMOUNT")',
                    resultContent: '@Column(name = "JVE_91A8B2C_LOAN_AMOUNT")',
                    appliedEditCount: 1,
                  }],
                  issues: [],
                }
              case 'applyEntityAttributeTypeMappingCutover':
                return {
                  success: true,
                  changeSetId: 'entity-type-mapping-cutover:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change?.sourceLocator?.relativePath],
                  issues: [],
                }
              case 'inspectDatabaseEntityTable':
                return {
                  accepted: true,
                  snapshotDigest: 'development-loan-app-database-snapshot',
                  storeId: payload.storeId,
                  existingEntityQualifiedName:
                    payload.expectedEntityQualifiedName === 'com.company.loan.entity.LoanApp' &&
                    (payload.tableName || '').toUpperCase() === 'LOAN_LOAN_APP' &&
                    (payload.schemaName || 'public').toLowerCase() === 'public'
                      ? 'com.company.loan.entity.LoanApp'
                      : undefined,
                  database: {
                    name: 'PostgreSQL',
                    version: '17.2',
                    driverName: 'PostgreSQL JDBC Driver',
                    driverVersion: '42.7.5',
                    urlFingerprint: '0a17f0a17f0a17f0',
                  },
                  table: {
                    catalog: payload.catalogName || 'payroll',
                    schema: payload.schemaName || 'public',
                    name: payload.tableName || 'LOAN_LOAN_APP',
                    type: 'TABLE',
                    primaryKeyColumns: ['ID'],
                    dependencyTables: ['EMPLOYEE', 'LOAN_CATEGORY'],
                    foreignKeys: [
                      {
                        name: 'FK_LOAN_APP_EMPLOYEE',
                        columnName: 'EMPLOYEE_ID',
                        referencedTableName: 'EMPLOYEE',
                        referencedColumnName: 'ID',
                        updateRule: 3,
                        deleteRule: 3,
                        sequence: 1,
                      },
                    ],
                    indexes: [
                      { name: 'IDX_LOAN_APP_EMPLOYEE', unique: false, columns: ['EMPLOYEE_ID'] },
                      { name: 'UQ_LOAN_APP_NUMBER', unique: true, columns: ['APPLICATION_NO'] },
                    ],
                    columns: [
                      developmentDatabaseColumn('ID', 'UUID', 'uuid', true, true),
                      developmentDatabaseColumn('APPLICATION_NO', 'VARCHAR', 'string', false, true, 40),
                      developmentDatabaseColumn('EMPLOYEE_ID', 'UUID', 'association', false, false, undefined, {
                        attributeName: 'employee',
                        javaType: 'com.company.hr.entity.Employee',
                        relatedEntity: 'com.company.hr.entity.Employee',
                        joinColumnName: 'EMPLOYEE_ID',
                        referencedColumnName: 'ID',
                      }),
                      developmentDatabaseColumn('APPROVED_AMOUNT', 'NUMERIC', 'bigDecimal', false, false, 19, {
                        precision: 19,
                        scale: 2,
                      }),
                      developmentDatabaseColumn('LEGACY_RISK_SCORE', 'INTEGER', 'integer', false, false),
                    ],
                  },
                  issues: [],
                } satisfies DatabaseEntityTableInspectionResponse
              case 'inspectEntityAttributePropagation': {
                const attributeNames = payload.attributeNames ?? []
                return {
                  accepted: true,
                  entityQualifiedName: payload.entityQualifiedName,
                  attributes: attributeNames,
                  targets: [
                    {
                      id: 'development-detail-form',
                      kind: 'VIEW_FORM',
                      label: 'Form loanAppForm',
                      relativePath: 'loan/src/main/resources/com/company/loan/view/loanapp/loan-app-detail-view.xml',
                      detail: `Add ${attributeNames.length} bound fields for loanAppDc.`,
                      missingAttributes: attributeNames,
                      recommended: true,
                      supported: true,
                      securityExpanding: false,
                    },
                    {
                      id: 'development-list-grid',
                      kind: 'VIEW_GRID',
                      label: 'Grid loanAppsDataGrid',
                      relativePath: 'loan/src/main/resources/com/company/loan/view/loanapp/loan-app-list-view.xml',
                      detail: `Add ${attributeNames.length} bound columns for loanAppsDc.`,
                      missingAttributes: attributeNames,
                      recommended: true,
                      supported: true,
                      securityExpanding: false,
                    },
                    {
                      id: 'development-message-bundle',
                      kind: 'MESSAGE_BUNDLE',
                      label: 'Entity message bundle',
                      relativePath: 'loan/src/main/resources/com/company/loan/entity/messages.properties',
                      detail: `Add ${attributeNames.length} default-locale caption keys.`,
                      missingAttributes: attributeNames,
                      recommended: true,
                      supported: true,
                      securityExpanding: false,
                    },
                    {
                      id: 'development-security-impact',
                      kind: 'RESOURCE_ROLE',
                      label: 'Security role PayrollUserRole',
                      relativePath: 'loan/src/main/java/com/company/loan/security/PayrollUserRole.java',
                      detail: 'Explicitly extend the existing VIEW attribute policy. This expands privileges.',
                      missingAttributes: attributeNames,
                      recommended: false,
                      supported: true,
                      securityExpanding: true,
                    },
                  ],
                  issues: [],
                } satisfies EntityAttributePropagationInspectionResponse
              }
              case 'previewEntityAttributePropagation': {
                const selected = new Set<string>(payload.targetIds ?? [])
                const paths = [
                  payload.inspection?.entityChange?.sourceLocator?.relativePath ?? null,
                  selected.has('development-detail-form')
                    ? 'loan/src/main/resources/com/company/loan/view/loanapp/loan-app-detail-view.xml'
                    : null,
                  selected.has('development-list-grid')
                    ? 'loan/src/main/resources/com/company/loan/view/loanapp/loan-app-list-view.xml'
                    : null,
                  selected.has('development-message-bundle')
                    ? 'loan/src/main/resources/com/company/loan/entity/messages.properties'
                    : null,
                ].filter((path): path is string => Boolean(path))
                return {
                  accepted: paths.length > 0,
                  changeSetId: 'entity-attribute-propagation:development',
                  label: payload.inspection?.entityChange
                    ? `Add and propagate ${payload.inspection?.attributeNames?.length ?? 0} entity attributes`
                    : `Propagate ${payload.inspection?.attributeNames?.length ?? 0} entity attributes`,
                  planDigest: paths.length ? 'development-entity-attribute-propagation' : undefined,
                  files: paths.map(relativePath => ({
                    relativePath,
                    mode: 'MODIFY',
                    beforeFingerprint: 'development-before',
                    afterFingerprint: 'development-after',
                    resultContent: '<!-- source-preserving propagated attributes -->',
                    appliedEditCount: 1,
                  })),
                  issues: paths.length ? [] : [{
                    code: 'JVW-PROPAGATION-TARGETS-EMPTY',
                    message: 'Select at least one reviewed propagation target.',
                  }],
                }
              }
              case 'applyEntityAttributePropagation':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: 'Propagate entity attributes',
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'entity-attribute-propagation:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: ['loan/src/main/resources/com/company/loan/entity/messages.properties'],
                  issues: [],
                }
              case 'browseDatabaseEntityTables':
                return {
                  accepted: true,
                  storeId: payload.storeId,
                  database: {
                    name: 'PostgreSQL',
                    version: '17',
                    driverName: 'PostgreSQL JDBC Driver',
                    driverVersion: '42.7',
                    urlFingerprint: 'development-db',
                  },
                  activeCatalog: 'payroll',
                  catalogs: ['payroll'],
                  schemas: [
                    { catalog: 'payroll', name: 'public' },
                    { catalog: 'payroll', name: 'audit' },
                  ],
                  tables: [
                    {
                      catalog: 'payroll',
                      schema: 'public',
                      name: 'LOAN_LOAN_APP',
                      type: 'TABLE',
                      remarks: 'Loan applications',
                    },
                    {
                      catalog: 'payroll',
                      schema: 'public',
                      name: 'LOAN_ACCT',
                      type: 'TABLE',
                      remarks: 'Loan accounts',
                    },
                    {
                      catalog: 'payroll',
                      schema: 'audit',
                      name: 'V_LOAN_EXPOSURE',
                      type: 'VIEW',
                      remarks: 'Current loan exposure',
                    },
                  ].filter(table =>
                    (!payload.schemaName || table.schema === payload.schemaName) &&
                    (!payload.search || table.name.toLowerCase().includes(
                      String(payload.search).toLowerCase(),
                    )),
                  ),
                  truncated: false,
                  issues: [],
                }
              case 'planDatabaseEntityImport': {
                const selected = (payload.selectedTables ?? []) as DatabaseTableReference[]
                const root = selected[0] ?? {
                  catalog: 'payroll',
                  schema: 'public',
                  name: 'LOAN_ACCT',
                  type: 'TABLE',
                }
                const rootTable = {
                  ...root,
                  remarks: root.remarks ?? 'Database-first import root',
                  columns: [
                    developmentDatabaseColumn('BANK_CODE', 'VARCHAR', 'string', true, false, 12),
                    developmentDatabaseColumn('ACCOUNT_NO', 'VARCHAR', 'string', true, false, 32),
                    developmentDatabaseColumn('EMPLOYEE_ID', 'UUID', 'association', false, false),
                    developmentDatabaseColumn('BALANCE', 'NUMERIC', 'bigDecimal', false, false, 19, {
                      precision: 19,
                      scale: 2,
                    }),
                  ],
                  primaryKeyColumns: ['BANK_CODE', 'ACCOUNT_NO'],
                  foreignKeys: [{
                    name: 'FK_LOAN_ACCT_EMPLOYEE',
                    columnName: 'EMPLOYEE_ID',
                    referencedCatalog: 'payroll',
                    referencedSchema: 'public',
                    referencedTableName: 'EMPLOYEE',
                    referencedColumnName: 'ID',
                    updateRule: 3,
                    deleteRule: 3,
                    sequence: 1,
                  }],
                  indexes: [],
                  dependencyTables: ['EMPLOYEE'],
                }
                return {
                  accepted: true,
                  ready: true,
                  snapshotDigest: 'development-database-entity-graph',
                  storeId: payload.storeId,
                  database: {
                    name: 'PostgreSQL',
                    version: '17',
                    driverName: 'PostgreSQL JDBC Driver',
                    driverVersion: '42.7',
                    urlFingerprint: 'development-db',
                  },
                  tables: [
                    {
                      table: rootTable,
                      selectedByUser: true,
                      requiredBy: [],
                      status: 'COMPOSITE_KEY',
                      entityClassName: 'LoanAcct',
                      entityQualifiedName: `${payload.packageName}.LoanAcct`,
                      compositeIdClassName: `${payload.packageName}.LoanAcctId`,
                      generated: true,
                      issues: [],
                    },
                    {
                      table: {
                        catalog: 'payroll',
                        schema: 'public',
                        name: 'EMPLOYEE',
                        type: 'TABLE',
                        remarks: 'Already mapped HR entity',
                        columns: [developmentDatabaseColumn('ID', 'UUID', 'uuid', true, true)],
                        primaryKeyColumns: ['ID'],
                        foreignKeys: [],
                        indexes: [],
                        dependencyTables: [],
                      },
                      selectedByUser: false,
                      requiredBy: [[root.catalog, root.schema, root.name].filter(Boolean).join('.')],
                      status: 'EXISTING_ENTITY',
                      entityClassName: 'Employee',
                      entityQualifiedName: 'com.company.hr.entity.Employee',
                      generated: false,
                      issues: [],
                    },
                  ],
                  entities: [],
                  issues: [],
                  profileDrift: payload.profileId === 'loan-accounts'
                    ? {
                        profileId: 'loan-accounts',
                        baselineSnapshotDigest: 'development-database-entity-graph',
                        liveSnapshotDigest: 'development-database-entity-graph',
                        matchesBaseline: true,
                        requestChanged: false,
                        addedTables: [],
                        removedTables: [],
                        changedTables: [],
                      }
                    : undefined,
                } satisfies DatabaseEntityImportPlanResponse
              }
              case 'previewDatabaseEntityImport': {
                const packagePath = String(payload.request?.packageName ?? 'com.company.loan.entity')
                  .replace(/\./g, '/')
                const moduleId = payload.request?.moduleId ?? 'loan'
                const paths = [
                  `${moduleId}/src/main/java/${packagePath}/LoanAcctId.java`,
                  `${moduleId}/src/main/java/${packagePath}/LoanAcct.java`,
                  `${moduleId}/src/main/resources/${packagePath}/messages.properties`,
                ]
                if (payload.request?.profileId) {
                  paths.push(`.jmix-workbench/database-imports/${payload.request.profileId}.json`)
                }
                return {
                  accepted: true,
                  changeSetId: 'database-import:development',
                  label: 'Import 1 Jmix database entity with 1 composite ID class',
                  planDigest: 'development-database-entity-plan',
                  files: paths.map(relativePath => ({
                    relativePath,
                    mode: relativePath.endsWith('.properties') ? 'MODIFY' : 'CREATE',
                    afterFingerprint: `development-${relativePath}`,
                    resultContent: '// database-first source preview',
                    appliedEditCount: relativePath.endsWith('.properties') ? 1 : 0,
                  })),
                  issues: [],
                }
              }
              case 'applyDatabaseEntityImport':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: 'Import database entity model',
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'database-import:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    'loan/src/main/java/com/company/loan/entity/LoanAcctId.java',
                    'loan/src/main/java/com/company/loan/entity/LoanAcct.java',
                    'loan/src/main/resources/com/company/loan/entity/messages.properties',
                    ...(payload.request?.profileId
                      ? [`.jmix-workbench/database-imports/${payload.request.profileId}.json`]
                      : []),
                  ],
                  issues: [],
                }
              case 'previewCrudGeneration': {
                const entity = payload.entity as any
                const moduleId = entity.generationTarget?.moduleId ?? 'loan'
                const basePackage = String(entity.packageName ?? 'com.example.app.entity').replace(/\.entity$/, '')
                const sourcePackage = basePackage.replace(/\./g, '/')
                const entityName = entity.className || 'NewEntity'
                const store = developmentSchemaWorkspace.stores.find(
                  (candidate) => candidate.id === entity.generationTarget?.storeId,
                ) ?? developmentSchemaWorkspace.stores[0]
                const tableStem = String(entity.tableName || entityName)
                  .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
                  .toLowerCase()
                const paths = [
                  `${moduleId}/src/main/java/${sourcePackage}/entity/${entityName}.java`,
                  `${moduleId}/src/main/java/${sourcePackage}/view/${entityName}ListView.java`,
                  `${moduleId}/src/main/resources/${sourcePackage}/view/${entityName}ListView.xml`,
                  `${moduleId}/src/main/java/${sourcePackage}/view/${entityName}DetailView.java`,
                  `${moduleId}/src/main/resources/${sourcePackage}/view/${entityName}DetailView.xml`,
                  `${moduleId}/src/main/resources/${sourcePackage}/menu.xml`,
                  `${moduleId}/src/main/java/${sourcePackage}/security/${entityName}Role.java`,
                  `${moduleId}/src/main/resources/${sourcePackage}/messages.properties`,
                ]
                if (payload.options?.generateFetchPlan !== false) {
                  paths.push(`${moduleId}/src/main/resources/${sourcePackage}/entity/${entityName}-fetch-plans.xml`)
                }
                if (payload.options?.generateMigration !== false && store?.generatedDirectory) {
                  paths.push(`${store.generatedDirectory}/2026/07/29-create-${tableStem}.xml`)
                }
                if (payload.options?.generateDataRepository) {
                  paths.push(`${moduleId}/src/main/java/${sourcePackage}/entity/${entityName}Repository.java`)
                }
                return {
                  accepted: true,
                  changeSetId: 'generation:development-crud',
                  label: `Generate Jmix CRUD for ${entityName}`,
                  planDigest: 'development-crud-generation',
                  files: paths.map((relativePath) => ({
                    relativePath,
                    mode: relativePath.endsWith('menu.xml') || relativePath.endsWith('messages.properties')
                      ? 'MODIFY'
                      : 'CREATE',
                    afterFingerprint: `development-${relativePath}`,
                    resultContent: `<!-- source-safe CRUD preview for ${entityName} -->`,
                    appliedEditCount:
                      relativePath.endsWith('menu.xml') || relativePath.endsWith('messages.properties') ? 1 : 0,
                  })),
                  issues: [],
                }
              }
              case 'applyCrudGeneration':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Generate Jmix CRUD for ${payload.entity?.className ?? ''}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'generation:development-crud',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: ['loan/src/main/java/com/company/loan/entity/LoanApp.java'],
                  issues: [],
                }
              case 'previewWorkflowGeneration': {
                const workflow = payload as WorkflowModel
                const modulePrefix = workflow.moduleId && workflow.moduleId !== '.'
                  ? `${workflow.moduleId}/`
                  : ''
                return {
                  accepted: true,
                  changeSetId: 'generation:development-workflow',
                  label: `Create Jmix workflow ${workflow.name}`,
                  planDigest: 'development-workflow-generation',
                  files: [{
                    relativePath: `${modulePrefix}src/main/resources/processes/${workflow.id}.bpmn20.xml`,
                    mode: 'CREATE',
                    afterFingerprint: 'development-workflow-after',
                    resultContent: `<definitions><process id="${workflow.id}" name="${workflow.name}" isExecutable="true"><!-- connected source-safe workflow preview --></process></definitions>`,
                    appliedEditCount: 0,
                  }],
                  issues: [],
                }
              }
              case 'loadWorkflowModel':
                return {
                  editable: false,
                  unsupportedElements: ['development source is synthetic'],
                  warnings: ['Connect the plugin to an indexed IntelliJ project to load real BPMN source.'],
                }
              case 'applyWorkflowGeneration':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: `Create Jmix workflow ${payload.workflow?.name ?? ''}`,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  changeSetId: 'generation:development-workflow',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [
                    `${payload.workflow?.moduleId ?? 'loan'}/src/main/resources/processes/${payload.workflow?.id}.bpmn20.xml`,
                  ],
                  issues: [],
                }
              case 'getFlowUiWorkspace':
                return developmentFlowUiWorkspace
              case 'inspectJmixRuntime':
                return {
                  accepted: true,
                  viewId: developmentFlowUiWorkspace.document?.viewId,
                  targets: [{
                    id: 'development-runtime',
                    moduleId: 'loan',
                    moduleRoot: '/development/payroll-platform/loan',
                    profile: 'development',
                    preferred: true,
                    baseUrl: 'http://localhost:8080',
                    previewUrl: 'http://localhost:8080/loan-applications',
                    routePath: 'loan-applications',
                    routeRequiresParameters: false,
                    reachable: false,
                    responseTimeMillis: 1,
                    configSources: ['application.properties'],
                    hotDeploySupported: false,
                    hotDeployMessage: 'Start the Jmix application to enable runtime preview.',
                    warnings: [],
                  }],
                  issues: [],
                } satisfies JmixRuntimeInspectionResponse
              case 'getWorkspaceHistory':
                return developmentHistory
              case 'undoWorkspaceChange':
                developmentHistory = {
                  canUndo: false,
                  undoDepth: 0,
                  canRedo: true,
                  redoLabel: developmentHistory.undoLabel ?? 'last visual change',
                  redoDepth: 1,
                }
                return {
                  success: true,
                  message: 'Undid the last visual change.',
                  changedFiles: [],
                  revisions: {},
                  history: developmentHistory,
                  issues: [],
                } satisfies WorkspaceHistoryMutationResponse
              case 'redoWorkspaceChange':
                developmentHistory = {
                  canUndo: true,
                  undoLabel: developmentHistory.redoLabel ?? 'last visual change',
                  undoDepth: 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                return {
                  success: true,
                  message: 'Redid the last visual change.',
                  changedFiles: [],
                  revisions: {},
                  history: developmentHistory,
                  issues: [],
                } satisfies WorkspaceHistoryMutationResponse
              case 'getProjectConfig':
                return developmentProjectConfig
              case 'getSecurityWorkspace':
                return { ...developmentSecurityWorkspace, runtime: developmentRuntimeSecurity }
              case 'importRuntimeSecurityEvidence': {
                const sourceId = 'runtime-evidence:development'
                developmentRuntimeSecurity = {
                  sources: [{
                    id: sourceId,
                    fileName: payload.fileName || 'security-evidence.json',
                    environmentLabel: payload.environmentLabel || 'Development',
                    format: 'JMIX_MIXED_ENTITY_JSON',
                    sha256: 'development-runtime-evidence',
                    importedAt: new Date().toISOString(),
                    roleCount: 2,
                    policyCount: 3,
                    assignmentCount: 2,
                  }],
                  roles: [
                    {
                      id: `${sourceId}:role:runtime-payroll`,
                      name: 'Runtime payroll operator',
                      code: 'runtime-payroll-operator',
                      description: 'Database-backed payroll permissions.',
                      kind: 'RESOURCE',
                      scopes: ['UI', 'API'],
                      policyIds: [`${sourceId}:policy:payroll-entity`, `${sourceId}:policy:payroll-view`],
                      inheritedRoleIds: [],
                      unresolvedChildRoleCodes: [],
                      evidenceSourceId: sourceId,
                    },
                    {
                      id: `${sourceId}:role:own-loans`,
                      name: 'Runtime own loans',
                      code: 'runtime-own-loans',
                      kind: 'ROW_LEVEL',
                      scopes: ['ALL'],
                      policyIds: [`${sourceId}:policy:own-loans`],
                      inheritedRoleIds: [],
                      unresolvedChildRoleCodes: [],
                      evidenceSourceId: sourceId,
                    },
                  ],
                  policies: [
                    {
                      id: `${sourceId}:policy:payroll-entity`,
                      roleId: `${sourceId}:role:runtime-payroll`,
                      type: 'EntityPolicy',
                      effect: 'GRANT',
                      actions: ['READ', 'UPDATE'],
                      resourceExpressions: ['payroll_PayrollRun'],
                      targetArtifactIds: ['entity-payroll'],
                      wildcard: false,
                      evidenceSourceId: sourceId,
                    },
                    {
                      id: `${sourceId}:policy:payroll-view`,
                      roleId: `${sourceId}:role:runtime-payroll`,
                      type: 'ViewPolicy',
                      effect: 'GRANT',
                      actions: ['ACCESS'],
                      resourceExpressions: ['payroll_PayrollRun.list'],
                      targetArtifactIds: ['view-payroll'],
                      wildcard: false,
                      evidenceSourceId: sourceId,
                    },
                    {
                      id: `${sourceId}:policy:own-loans`,
                      roleId: `${sourceId}:role:own-loans`,
                      type: 'JpqlRowLevelPolicy',
                      effect: 'RESTRICT',
                      actions: ['READ'],
                      resourceExpressions: ['payroll_LoanApp'],
                      targetArtifactIds: ['entity-loan'],
                      wildcard: false,
                      condition: 'where: {E}.employee.user.id = :current_user_id',
                      evidenceSourceId: sourceId,
                    },
                  ],
                  assignments: [
                    {
                      id: `${sourceId}:assignment:alex-resource`,
                      username: 'alex',
                      roleCode: 'runtime-payroll-operator',
                      roleKind: 'RESOURCE',
                      candidateRoleIds: [`${sourceId}:role:runtime-payroll`],
                      resolution: 'RESOLVED',
                      evidenceSourceId: sourceId,
                    },
                    {
                      id: `${sourceId}:assignment:alex-row`,
                      username: 'alex',
                      roleCode: 'runtime-own-loans',
                      roleKind: 'ROW_LEVEL',
                      candidateRoleIds: [`${sourceId}:role:own-loans`],
                      resolution: 'RESOLVED',
                      evidenceSourceId: sourceId,
                    },
                  ],
                  principals: ['alex'],
                  issues: [],
                  summary: {
                    sourceCount: 1,
                    roleCount: 2,
                    policyCount: 3,
                    assignmentCount: 2,
                    principalCount: 1,
                    errorCount: 0,
                    warningCount: 0,
                  },
                }
                return {
                  accepted: true,
                  sourceId,
                  message: `Imported runtime security evidence from ${payload.fileName || 'security-evidence.json'}.`,
                  issues: [],
                } satisfies RuntimeSecurityEvidenceImportResponse
              }
              case 'clearRuntimeSecurityEvidence':
                developmentRuntimeSecurity = developmentSecurityWorkspace.runtime
                return {
                  accepted: true,
                  message: 'Cleared runtime security evidence.',
                  issues: [],
                } satisfies RuntimeSecurityEvidenceImportResponse
              case 'getSecurityRoleDestinations':
                return {
                  destinations: [
                    {
                      id: 'security-role-destination:loan',
                      moduleId: 'loan',
                      sourceRoot: 'loan/src/main/java',
                      defaultPackage: 'com.company.loan.security',
                      recommended: true,
                    },
                    {
                      id: 'security-role-destination:payroll',
                      moduleId: 'payroll',
                      sourceRoot: 'payroll/src/main/java',
                      defaultPackage: 'com.company.payroll.security',
                      recommended: false,
                    },
                  ],
                  defaultDestinationId: 'security-role-destination:loan',
                  issues: [],
                }
              case 'previewSecurityRoleCreate': {
                const role = payload.role
                const selectedRoot = payload.destinationId === 'security-role-destination:payroll'
                  ? 'payroll/src/main/java'
                  : 'loan/src/main/java'
                const defaultPackage = payload.destinationId === 'security-role-destination:payroll'
                  ? 'com.company.payroll.security'
                  : 'com.company.loan.security'
                const packageName = role.packageName || defaultPackage
                const relativePath = `${selectedRoot}/${packageName.replaceAll('.', '/')}/${role.className}.java`
                const annotation = role.scope === 'resource' ? 'ResourceRole' : 'RowLevelRole'
                const resultContent = `package ${packageName};\n\n` +
                  `import io.jmix.security.role.annotation.${annotation};\n\n` +
                  `@${annotation}(name = ${JSON.stringify(role.name)}, code = ${role.className}.CODE)\n` +
                  `public interface ${role.className} {\n` +
                  `    String CODE = ${JSON.stringify(role.code)};\n` +
                  `}\n`
                return {
                  accepted: true,
                  changeSetId: `security-role-create:development`,
                  label: `Create Jmix role ${role.className}`,
                  planDigest: `development-${role.className}`,
                  files: [{
                    relativePath,
                    mode: 'CREATE',
                    afterFingerprint: 'development-after',
                    resultContent,
                    appliedEditCount: 0,
                  }],
                  issues: [],
                }
              }
              case 'applySecurityRoleCreate':
                return {
                  success: true,
                  changeSetId: 'security-role-create:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: ['development/security-role.java'],
                  issues: [],
                }
              case 'previewSecurityRolePolicyAddition': {
                const change = payload as SecurityRolePolicyChangeRequest
                const roleName = change.roleClassName.split('.').slice(-1)[0] || 'ExistingRole'
                const policy = change.policy
                const annotation = (() => {
                  switch (policy.type) {
                    case 'entity':
                      return `@EntityPolicy(entityClass = ${policy.entityClass?.split('.').slice(-1)[0]}.class, actions = EntityPolicyAction.READ)`
                    case 'entityAttribute':
                      return `@EntityAttributePolicy(entityClass = ${policy.entityClass?.split('.').slice(-1)[0]}.class, attributes = {${policy.attributes.map((value) => JSON.stringify(value)).join(', ')}}, action = EntityAttributePolicyAction.${policy.attributeAction.toUpperCase()})`
                    case 'menu':
                      return `@MenuPolicy(menuIds = {${policy.resources.map((value) => JSON.stringify(value)).join(', ')}})`
                    case 'view':
                      return `@ViewPolicy(viewIds = {${policy.resources.map((value) => JSON.stringify(value)).join(', ')}})`
                    case 'specific':
                      return `@SpecificPolicy(resources = {${policy.resources.map((value) => JSON.stringify(value)).join(', ')}})`
                    case 'jpqlRow':
                      return `@JpqlRowLevelPolicy(entityClass = ${policy.entityClass?.split('.').slice(-1)[0]}.class, where = ${JSON.stringify(policy.whereClause || '')})`
                    case 'predicateRow':
                      return `@PredicateRowLevelPolicy(entityClass = ${policy.entityClass?.split('.').slice(-1)[0]}.class, actions = RowLevelPolicyAction.READ)`
                  }
                })()
                const originalContent = `public interface ${roleName} {\n    String CODE = \"existing-role\";\n}\n`
                const resultContent = `public interface ${roleName} {\n    String CODE = \"existing-role\";\n\n    ${annotation}\n    void generatedPolicy();\n}\n`
                return {
                  accepted: true,
                  changeSetId: 'security-role-policy:development',
                  label: `Add policy to ${roleName}`,
                  planDigest: `development-policy-${roleName}`,
                  files: [{
                    relativePath: change.roleLocator.relativePath,
                    mode: 'MODIFY',
                    beforeFingerprint: change.roleLocator.revisionFingerprint,
                    afterFingerprint: 'development-policy-after',
                    originalContent,
                    resultContent,
                    appliedEditCount: 2,
                  }],
                  issues: [],
                }
              }
              case 'applySecurityRolePolicyAddition':
                return {
                  success: true,
                  changeSetId: 'security-role-policy:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change.roleLocator.relativePath],
                  issues: [],
                }
              case 'inspectSecurityRolePolicies': {
                const request = payload as SecurityRolePolicyInspectionRequest
                const rowRole = request.roleClassName.endsWith('OwnLoansRole')
                const locator = {
                  ...request.roleLocator,
                  symbol: rowRole
                    ? `${request.roleClassName}#JpqlRowLevelPolicy-1`
                    : `${request.roleClassName}#EntityPolicy-1`,
                  line: 12,
                  column: 5,
                }
                return {
                  accepted: true,
                  policies: [{
                    id: locator.symbol,
                    locator,
                    type: rowRole ? 'jpqlRow' : 'entity',
                    methodName: rowRole ? 'loanApp' : 'payrollRun',
                    annotationText: rowRole
                      ? '@JpqlRowLevelPolicy(entityClass = LoanApp.class, where = "{E}.employee.user.id = :current_user_id")'
                      : '@EntityPolicy(entityClass = PayrollRun.class, actions = EntityPolicyAction.READ)',
                    policy: rowRole ? {
                      type: 'jpqlRow',
                      entityClass: 'com.company.loan.entity.LoanApp',
                      entityActions: [],
                      allEntityActions: false,
                      attributes: [],
                      attributeAction: 'view',
                      resources: [],
                      rowActions: [],
                      whereClause: '{E}.employee.user.id = :current_user_id',
                      joinClause: '',
                      predicateExpression: '',
                      allowWildcard: false,
                    } : {
                      type: 'entity',
                      entityClass: 'com.company.payroll.entity.PayrollRun',
                      entityActions: ['read'],
                      allEntityActions: false,
                      attributes: [],
                      attributeAction: 'view',
                      resources: [],
                      rowActions: [],
                      whereClause: '',
                      joinClause: '',
                      predicateExpression: '',
                      allowWildcard: false,
                    },
                    editable: true,
                  }],
                  issues: [],
                } satisfies SecurityRolePolicyInspectionResponse
              }
              case 'previewSecurityRolePolicyReplacement': {
                const change = payload as SecurityRolePolicyReplacementRequest
                const originalContent = `public interface ExistingRole {\n    ${change.policyLocator.symbol}\n}\n`
                const resultContent = `public interface ExistingRole {\n    // exact replacement: ${change.replacement.type}\n}\n`
                return {
                  accepted: true,
                  changeSetId: 'security-role-policy-replace:development',
                  label: 'Replace existing security policy',
                  planDigest: 'development-policy-replace',
                  files: [{
                    relativePath: change.roleLocator.relativePath,
                    mode: 'MODIFY',
                    beforeFingerprint: change.roleLocator.revisionFingerprint,
                    afterFingerprint: 'development-policy-replaced',
                    originalContent,
                    resultContent,
                    appliedEditCount: 1,
                  }],
                  issues: [],
                }
              }
              case 'applySecurityRolePolicyReplacement':
                return {
                  success: true,
                  changeSetId: 'security-role-policy-replace:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change.roleLocator.relativePath],
                  issues: [],
                }
              case 'previewSecurityRolePolicyRemoval': {
                const change = payload as SecurityRolePolicyRemovalRequest
                return {
                  accepted: true,
                  changeSetId: 'security-role-policy-remove:development',
                  label: 'Remove existing security policy',
                  planDigest: 'development-policy-remove',
                  files: [{
                    relativePath: change.roleLocator.relativePath,
                    mode: 'MODIFY',
                    beforeFingerprint: change.roleLocator.revisionFingerprint,
                    afterFingerprint: 'development-policy-removed',
                    originalContent: 'public interface ExistingRole { /* policy */ }\n',
                    resultContent: 'public interface ExistingRole { }\n',
                    appliedEditCount: 1,
                  }],
                  issues: [],
                }
              }
              case 'applySecurityRolePolicyRemoval':
                return {
                  success: true,
                  changeSetId: 'security-role-policy-remove:development',
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [payload.change.roleLocator.relativePath],
                  issues: [],
                }
              case 'previewFlowUiPropertyChange':
              case 'previewFlowUiStructureChange':
              case 'previewFlowUiDirectTextChange':
              case 'previewFlowUiControllerInjection':
              case 'previewFlowUiControllerHandler': {
                const sourceLocator = payload.sourceLocator ?? payload.controllerLocator
                const label = action === 'previewFlowUiStructureChange'
                  ? `${String(payload.operation ?? 'position').replace(/_/g, ' ').toLowerCase()} ${payload.tagName ?? ''}`.trim()
                  : action.replace(/^previewFlowUi/, '').replace(/([A-Z])/g, ' $1').trim()
                return {
                  accepted: true,
                  changeSetId: `flowui-development:${action}`,
                  label,
                  planDigest: `development-${action}`,
                  files: [{
                    relativePath: sourceLocator?.relativePath ??
                      developmentFlowUiWorkspace.document?.relativePath ??
                      'development-view.xml',
                    mode: 'MODIFY',
                    beforeFingerprint: sourceLocator?.revisionFingerprint ?? 'development-preview',
                    afterFingerprint: `development-after-${action}`,
                    originalContent: developmentFlowUiWorkspace.document?.sourceText ?? '',
                    resultContent: `${developmentFlowUiWorkspace.document?.sourceText ?? ''}\n<!-- ${label} -->`,
                    appliedEditCount: action === 'previewFlowUiStructureChange' &&
                      payload.operation === 'REPARENT' ? 2 : 1,
                  }],
                  issues: [],
                }
              }
              case 'applyFlowUiPropertyChange':
              case 'applyFlowUiStructureChange':
              case 'applyFlowUiDirectTextChange':
              case 'applyFlowUiControllerInjection':
              case 'applyFlowUiControllerHandler': {
                const label = action.replace(/^applyFlowUi/, '').replace(/([A-Z])/g, ' $1').trim()
                developmentHistory = {
                  canUndo: true,
                  undoLabel: label,
                  undoDepth: developmentHistory.undoDepth + 1,
                  canRedo: false,
                  redoDepth: 0,
                }
                const sourceLocator = payload.change?.sourceLocator ?? payload.change?.controllerLocator
                return {
                  success: true,
                  changeSetId: `flowui-development:${action}`,
                  planDigest: payload.expectedPlanDigest,
                  filesChanged: [sourceLocator?.relativePath ??
                    developmentFlowUiWorkspace.document?.relativePath ??
                    'development-view.xml'],
                  issues: [],
                }
              }
              default:
                return {
                  success: true,
                  filesWritten: [`generated/${action}.java`],
                  errors: [],
                }
            }
          })()
          this.listeners.forEach(cb => cb(action, requestId, result))
        }, 300)
        return
      }
      this.pendingQueue.push({ action, payload, requestId })
      return
    }
    window.javaBridge.send(action, payload, requestId)
  }

  onResponse(callback: BridgeCallback) {
    this.listeners.push(callback)
    return () => {
      this.listeners = this.listeners.filter(cb => cb !== callback)
    }
  }

  getLaunchContext(): WorkbenchLaunchContext | null {
    return this.launchContext
  }

  onLaunchContext(callback: (context: WorkbenchLaunchContext | null) => void) {
    this.launchContextListeners.push(callback)
    return () => {
      this.launchContextListeners = this.launchContextListeners.filter(
        (listener) => listener !== callback,
      )
    }
  }

  async request<T = any>(action: string, payload: any = {}): Promise<T> {
    const requestId = this.nextRequestId()
    return new Promise((resolve) => {
      const unsub = this.onResponse((respAction, responseRequestId, result) => {
        if (responseRequestId === requestId && respAction === action) {
          unsub()
          resolve(result)
        }
      })
      this.send(action, payload, requestId)
    })
  }

  private nextRequestId(): string {
    this.requestSequence += 1
    return `jvw-${Date.now().toString(36)}-${this.requestSequence.toString(36)}`
  }

  generateEntity(entity: any) {
    return this.request<GenerationResult>('generateEntity', entity)
  }

  previewEntityGeneration(entity: any) {
    return this.request<WorkspaceChangePreviewResponse>('previewEntityGeneration', entity)
  }

  applyEntityGeneration(entity: any, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyEntityGeneration', {
      entity,
      expectedPlanDigest,
    })
  }

  previewExistingEntityAttributeAdditions(change: ExistingEntityAttributeAdditionRequest) {
    return this.request<WorkspaceChangePreviewResponse>(
      'previewExistingEntityAttributeAdditions',
      change,
    )
  }

  applyExistingEntityAttributeAdditions(
    change: ExistingEntityAttributeAdditionRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>(
      'applyExistingEntityAttributeAdditions',
      { change, expectedPlanDigest },
    )
  }

  launchEntityAttributeRename(change: EntityAttributeRenameRequest) {
    return this.request<EntityAttributeRenameLaunchResponse>(
      'launchEntityAttributeRename',
      change,
    )
  }

  launchEntityAttributeSafeDelete(change: EntityAttributeSafeDeleteRequest) {
    return this.request<EntityAttributeSafeDeleteLaunchResponse>(
      'launchEntityAttributeSafeDelete',
      change,
    )
  }

  launchEntityAttributeTypeMigration(change: EntityAttributeTypeMigrationRequest) {
    return this.request<EntityAttributeTypeMigrationLaunchResponse>(
      'launchEntityAttributeTypeMigration',
      change,
    )
  }

  previewEntityAttributeTypeExpansion(change: EntityAttributeTypeMigrationRequest) {
    return this.request<EntityAttributeTypeExpansionPreviewResponse>(
      'previewEntityAttributeTypeExpansion',
      change,
    )
  }

  applyEntityAttributeTypeExpansion(
    change: EntityAttributeTypeMigrationRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>(
      'applyEntityAttributeTypeExpansion',
      { change, expectedPlanDigest },
    )
  }

  verifyEntityAttributeTypeExpansion(change: EntityAttributeTypeMigrationRequest) {
    return this.request<EntityAttributeTypeExpansionVerificationResponse>(
      'verifyEntityAttributeTypeExpansion',
      change,
    )
  }

  previewEntityAttributeTypeMappingCutover(change: EntityAttributeTypeMappingCutoverRequest) {
    return this.request<WorkspaceChangePreviewResponse>(
      'previewEntityAttributeTypeMappingCutover',
      change,
    )
  }

  applyEntityAttributeTypeMappingCutover(
    change: EntityAttributeTypeMappingCutoverRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>(
      'applyEntityAttributeTypeMappingCutover',
      { change, expectedPlanDigest },
    )
  }

  inspectDatabaseEntityTable(request: DatabaseEntityTableInspectionRequest) {
    return this.request<DatabaseEntityTableInspectionResponse>(
      'inspectDatabaseEntityTable',
      request,
    )
  }

  browseDatabaseEntityTables(request: DatabaseEntityTableBrowseRequest) {
    return this.request<DatabaseEntityTableBrowseResponse>(
      'browseDatabaseEntityTables',
      request,
    )
  }

  planDatabaseEntityImport(request: DatabaseEntityImportRequest) {
    return this.request<DatabaseEntityImportPlanResponse>(
      'planDatabaseEntityImport',
      request,
    )
  }

  previewDatabaseEntityImport(
    request: DatabaseEntityImportRequest,
    expectedSnapshotDigest: string,
  ) {
    return this.request<WorkspaceChangePreviewResponse>(
      'previewDatabaseEntityImport',
      { request, expectedSnapshotDigest },
    )
  }

  applyDatabaseEntityImport(
    request: DatabaseEntityImportRequest,
    expectedSnapshotDigest: string,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>(
      'applyDatabaseEntityImport',
      { request, expectedSnapshotDigest, expectedPlanDigest },
    )
  }

  inspectEntityAttributePropagation(request: EntityAttributePropagationInspectionRequest) {
    return this.request<EntityAttributePropagationInspectionResponse>(
      'inspectEntityAttributePropagation',
      request,
    )
  }

  previewEntityAttributePropagation(change: EntityAttributePropagationChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>(
      'previewEntityAttributePropagation',
      change,
    )
  }

  applyEntityAttributePropagation(
    change: EntityAttributePropagationChangeRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>(
      'applyEntityAttributePropagation',
      { change, expectedPlanDigest },
    )
  }

  generateCrud(entity: any, options: any) {
    return this.request<GenerationResult>('generateCrud', { entity, options })
  }

  previewCrudGeneration(entity: any, options: any) {
    return this.request<WorkspaceChangePreviewResponse>('previewCrudGeneration', { entity, options })
  }

  applyCrudGeneration(entity: any, options: any, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyCrudGeneration', {
      entity,
      options,
      expectedPlanDigest,
    })
  }

  generateView(view: any) {
    return this.request<GenerationResult>('generateView', view)
  }

  generateMigration(migration: any) {
    return this.request<GenerationResult>('generateMigration', migration)
  }

  getSchemaWorkspace(forceRefresh: boolean = false) {
    return this.request<SchemaWorkspaceResponse>('getSchemaWorkspace', { forceRefresh })
  }

  getDatabaseEntityImportProfiles() {
    return this.request<DatabaseEntityImportProfileWorkspaceResponse>(
      'getDatabaseEntityImportProfiles',
      {},
    )
  }

  getRestApiWorkspace(forceRefresh: boolean = false) {
    return this.request<RestApiWorkspaceResponse>('getRestApiWorkspace', { forceRefresh })
  }

  invokeRestApi(request: RestApiInvocationRequest) {
    return this.request<RestApiInvocationResponse>('invokeRestApi', request)
  }

  previewRestApiContractAddition(change: RestApiContractAdditionRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewRestApiContractAddition', change)
  }

  applyRestApiContractAddition(change: RestApiContractAdditionRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyRestApiContractAddition', {
      change,
      expectedPlanDigest,
    })
  }

  previewRestApiContractMutation(change: RestApiContractMutationRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewRestApiContractMutation', change)
  }

  applyRestApiContractMutation(change: RestApiContractMutationRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyRestApiContractMutation', {
      change,
      expectedPlanDigest,
    })
  }

  previewSchemaMigration(change: SchemaMigrationChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewSchemaMigration', change)
  }

  applySchemaMigration(change: SchemaMigrationChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applySchemaMigration', {
      change,
      expectedPlanDigest,
    })
  }

  generateRole(role: any) {
    return this.request<GenerationResult>('generateRole', role)
  }

  generateBpm(entityName: string) {
    return this.request<GenerationResult>('generateBpm', { entityName })
  }

  previewWorkflowGeneration(workflow: WorkflowModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewWorkflowGeneration', workflow)
  }

  loadWorkflowModel(relativePath: string, processId: string, moduleId: string) {
    return this.request<WorkflowLoadResponse>('loadWorkflowModel', {
      relativePath,
      processId,
      moduleId,
    })
  }

  applyWorkflowGeneration(workflow: WorkflowModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyWorkflowGeneration', {
      workflow,
      expectedPlanDigest,
    })
  }

  getProjectConfig() {
    return this.request<ProjectConfig>('getProjectConfig')
  }

  getApplicationGraph(forceRefresh: boolean = false) {
    return this.request<ApplicationGraphResponse>('getApplicationGraph', { forceRefresh })
  }

  getScenarioWorkspace(forceRefresh: boolean = false) {
    return this.request<ScenarioWorkspaceResponse>('getScenarioWorkspace', { forceRefresh })
  }

  getVisualLogicWorkspace(forceRefresh: boolean = false) {
    return this.request<VisualLogicWorkspaceResponse>('getVisualLogicWorkspace', { forceRefresh })
  }

  getIntegrationConnectorWorkspace(forceRefresh: boolean = false) {
    return this.request<IntegrationConnectorWorkspaceResponse>(
      'getIntegrationConnectorWorkspace',
      { forceRefresh },
    )
  }

  previewIntegrationConnector(model: IntegrationConnectorModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewIntegrationConnector', model)
  }

  applyIntegrationConnector(model: IntegrationConnectorModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyIntegrationConnector', {
      model,
      expectedPlanDigest,
    })
  }

  previewVisualLogic(model: VisualLogicClassModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewVisualLogic', model)
  }

  applyVisualLogic(model: VisualLogicClassModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyVisualLogic', {
      model,
      expectedPlanDigest,
    })
  }

  getVisualRuleWorkspace(forceRefresh: boolean = false) {
    return this.request<VisualRuleWorkspaceResponse>('getVisualRuleWorkspace', { forceRefresh })
  }

  previewVisualRule(model: VisualRuleModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewVisualRule', model)
  }

  applyVisualRule(model: VisualRuleModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyVisualRule', {
      model,
      expectedPlanDigest,
    })
  }

  getDmnDecisionWorkspace(forceRefresh: boolean = false) {
    return this.request<DmnDecisionWorkspaceResponse>('getDmnDecisionWorkspace', { forceRefresh })
  }

  previewDmnDecision(model: DmnDecisionModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewDmnDecision', model)
  }

  applyDmnDecision(model: DmnDecisionModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyDmnDecision', {
      model,
      expectedPlanDigest,
    })
  }

  simulateDmnDecision(model: DmnDecisionModel, inputs: Record<string, string>) {
    return this.request<DmnSimulationResult>('simulateDmnDecision', { model, inputs })
  }

  previewScenarioTest(scenario: ScenarioTestModel) {
    return this.request<WorkspaceChangePreviewResponse>('previewScenarioTest', scenario)
  }

  applyScenarioTest(scenario: ScenarioTestModel, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyScenarioTest', {
      scenario,
      expectedPlanDigest,
    })
  }

  getSecurityWorkspace(forceRefresh: boolean = false) {
    return this.request<SecurityWorkspaceSnapshot>('getSecurityWorkspace', { forceRefresh })
  }

  importRuntimeSecurityEvidence(change: RuntimeSecurityEvidenceImportRequest) {
    return this.request<RuntimeSecurityEvidenceImportResponse>('importRuntimeSecurityEvidence', change)
  }

  clearRuntimeSecurityEvidence(sourceId?: string) {
    return this.request<RuntimeSecurityEvidenceImportResponse>('clearRuntimeSecurityEvidence', { sourceId })
  }

  getSecurityRoleDestinations() {
    return this.request<SecurityRoleDestinationsResponse>('getSecurityRoleDestinations')
  }

  previewSecurityRoleCreate(change: SecurityRoleCreateRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewSecurityRoleCreate', change)
  }

  applySecurityRoleCreate(change: SecurityRoleCreateRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applySecurityRoleCreate', {
      change,
      expectedPlanDigest,
    })
  }

  previewSecurityRolePolicyAddition(change: SecurityRolePolicyChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewSecurityRolePolicyAddition', change)
  }

  applySecurityRolePolicyAddition(change: SecurityRolePolicyChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applySecurityRolePolicyAddition', {
      change,
      expectedPlanDigest,
    })
  }

  inspectSecurityRolePolicies(change: SecurityRolePolicyInspectionRequest) {
    return this.request<SecurityRolePolicyInspectionResponse>('inspectSecurityRolePolicies', change)
  }

  previewSecurityRolePolicyReplacement(change: SecurityRolePolicyReplacementRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewSecurityRolePolicyReplacement', change)
  }

  applySecurityRolePolicyReplacement(
    change: SecurityRolePolicyReplacementRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>('applySecurityRolePolicyReplacement', {
      change,
      expectedPlanDigest,
    })
  }

  previewSecurityRolePolicyRemoval(change: SecurityRolePolicyRemovalRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewSecurityRolePolicyRemoval', change)
  }

  applySecurityRolePolicyRemoval(
    change: SecurityRolePolicyRemovalRequest,
    expectedPlanDigest: string,
  ) {
    return this.request<WorkspaceChangeApplyResponse>('applySecurityRolePolicyRemoval', {
      change,
      expectedPlanDigest,
    })
  }

  navigateToSource(locator: GraphSourceLocator) {
    return this.request<SourceNavigationResponse>('navigateToSource', locator)
  }

  getFlowUiWorkspace(sourceLocator: GraphSourceLocator) {
    return this.request<FlowUiWorkspaceResponse>('getFlowUiWorkspace', { sourceLocator })
  }

  getWorkspaceHistory() {
    return this.request<WorkspaceHistorySnapshot>('getWorkspaceHistory')
  }

  undoWorkspaceChange() {
    return this.request<WorkspaceHistoryMutationResponse>('undoWorkspaceChange')
  }

  redoWorkspaceChange() {
    return this.request<WorkspaceHistoryMutationResponse>('redoWorkspaceChange')
  }

  previewFlowUiPropertyChange(change: FlowUiPropertyChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiPropertyChange', change)
  }

  applyFlowUiPropertyChange(change: FlowUiPropertyChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiPropertyChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiStructureChange(change: FlowUiStructureChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiStructureChange', change)
  }

  applyFlowUiStructureChange(change: FlowUiStructureChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiStructureChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiDirectTextChange(change: FlowUiDirectTextChangeRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiDirectTextChange', change)
  }

  applyFlowUiDirectTextChange(change: FlowUiDirectTextChangeRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiDirectTextChange', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiControllerInjection(change: FlowUiControllerInjectionRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiControllerInjection', change)
  }

  applyFlowUiControllerInjection(change: FlowUiControllerInjectionRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiControllerInjection', {
      change,
      expectedPlanDigest,
    })
  }

  previewFlowUiControllerHandler(change: FlowUiControllerHandlerRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiControllerHandler', change)
  }

  applyFlowUiControllerHandler(change: FlowUiControllerHandlerRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiControllerHandler', {
      change,
      expectedPlanDigest,
    })
  }

  inspectJmixRuntime(descriptorLocator: GraphSourceLocator) {
    return this.request<JmixRuntimeInspectionResponse>('inspectJmixRuntime', { descriptorLocator })
  }

  openJmixRuntimePreview(url: string, title: string, viewport: JmixRuntimeViewport = 'DESKTOP') {
    return this.request<JmixRuntimeActionResponse>('openJmixRuntimePreview', { url, title, viewport })
  }

  previewFlowUiHotDeploy(change: JmixFlowUiHotDeployRequest) {
    return this.request<WorkspaceChangePreviewResponse>('previewFlowUiHotDeploy', change)
  }

  applyFlowUiHotDeploy(change: JmixFlowUiHotDeployRequest, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyFlowUiHotDeploy', {
      change,
      expectedPlanDigest,
    })
  }

  previewWorkspaceChange(changeSet: WorkspaceChangeSet) {
    return this.request<WorkspaceChangePreviewResponse>('previewWorkspaceChange', changeSet)
  }

  applyWorkspaceChange(changeSet: WorkspaceChangeSet, expectedPlanDigest: string) {
    return this.request<WorkspaceChangeApplyResponse>('applyWorkspaceChange', {
      changeSet,
      expectedPlanDigest,
    })
  }
}

export const bridge = new Bridge()
