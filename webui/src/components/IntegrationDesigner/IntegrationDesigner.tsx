import { useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertTriangle, ArrowDownToLine, ArrowUpFromLine, Boxes, CheckCircle2, ChevronRight,
  CircuitBoard, Cloud, Code2, Database, FileKey2, Gauge, HardDrive, History,
  Inbox, KeyRound, Loader2, Mail, MessageSquareMore, Network, Plus, Radio,
  RefreshCw, RotateCcw, RotateCw, Save, Send, Server, ShieldCheck, SlidersHorizontal,
  Trash2, Undo2, Webhook, X,
} from 'lucide-react'
import { bridge } from '../../bridge'
import { useStore } from '../../store'
import type {
  GraphArtifact,
  IntegrationAuthenticationKind,
  IntegrationBackoffMode,
  IntegrationCapability,
  IntegrationConnectorKind,
  IntegrationConnectorModel,
  IntegrationDeliveryGuarantee,
  IntegrationHttpMethod,
  IntegrationOrganizationConnectorTemplateSnapshot,
  IntegrationRetryMode,
  IntegrationConnectorWorkspaceResponse,
  SchemaDataStoreSnapshot,
  WorkspaceChangePreviewResponse,
} from '../../types'

interface ConnectorDefinition {
  kind: IntegrationConnectorKind
  label: string
  description: string
  group: 'Web & API' | 'Messaging' | 'Files & transfer' | 'Jmix services' | 'Enterprise gateways'
  direction: 'Inbound' | 'Outbound'
  capability: IntegrationCapability
  icon: typeof Webhook
}

const definitions: ConnectorDefinition[] = [
  { kind: 'HTTP_CLIENT', label: 'HTTP client', description: 'Call a REST or HTTP service with bounded timeouts.', group: 'Web & API', direction: 'Outbound', capability: 'SPRING_WEB', icon: Cloud },
  { kind: 'WEBHOOK', label: 'Webhook', description: 'Deliver signed application events to an external endpoint.', group: 'Web & API', direction: 'Outbound', capability: 'SPRING_WEB', icon: Webhook },
  { kind: 'KAFKA_PUBLISHER', label: 'Kafka publisher', description: 'Publish ordered domain events with delivery controls.', group: 'Messaging', direction: 'Outbound', capability: 'SPRING_KAFKA', icon: Radio },
  { kind: 'KAFKA_CONSUMER', label: 'Kafka consumer', description: 'Consume records through an indexed application handler.', group: 'Messaging', direction: 'Inbound', capability: 'SPRING_KAFKA', icon: Inbox },
  { kind: 'RABBIT_PUBLISHER', label: 'RabbitMQ publisher', description: 'Publish messages to an exchange and routing key.', group: 'Messaging', direction: 'Outbound', capability: 'SPRING_AMQP', icon: Send },
  { kind: 'RABBIT_CONSUMER', label: 'RabbitMQ consumer', description: 'Consume queue messages through a selected handler.', group: 'Messaging', direction: 'Inbound', capability: 'SPRING_AMQP', icon: Inbox },
  { kind: 'SFTP_UPLOAD', label: 'SFTP upload', description: 'Upload binary files using a configured session template.', group: 'Files & transfer', direction: 'Outbound', capability: 'SPRING_INTEGRATION_SFTP', icon: ArrowUpFromLine },
  { kind: 'SFTP_DOWNLOAD', label: 'SFTP download', description: 'Read remote files through a managed SFTP session.', group: 'Files & transfer', direction: 'Inbound', capability: 'SPRING_INTEGRATION_SFTP', icon: ArrowDownToLine },
  { kind: 'JMIX_EMAIL', label: 'Jmix email', description: 'Send synchronous or queued email with Jmix Email.', group: 'Jmix services', direction: 'Outbound', capability: 'JMIX_EMAIL', icon: Mail },
  { kind: 'JMIX_FILE_STORAGE', label: 'Jmix file storage', description: 'Store and retrieve files through a named Jmix storage.', group: 'Jmix services', direction: 'Outbound', capability: 'JMIX_FILE_STORAGE', icon: HardDrive },
  { kind: 'OBJECT_STORAGE', label: 'Object storage', description: 'Use a Jmix FileStorage-backed object store.', group: 'Files & transfer', direction: 'Outbound', capability: 'JMIX_FILE_STORAGE', icon: Database },
  { kind: 'SMS_GATEWAY', label: 'SMS gateway', description: 'Send messages through an externalized HTTP gateway.', group: 'Enterprise gateways', direction: 'Outbound', capability: 'SPRING_WEB', icon: MessageSquareMore },
  { kind: 'PAYMENT_GATEWAY', label: 'Payment gateway', description: 'Call a payment provider with mandatory resilience controls.', group: 'Enterprise gateways', direction: 'Outbound', capability: 'SPRING_WEB', icon: ShieldCheck },
  { kind: 'IDENTITY_PROVIDER', label: 'Identity provider', description: 'Integrate an OAuth2 identity service through an approved client manager.', group: 'Enterprise gateways', direction: 'Outbound', capability: 'OAUTH2_CLIENT', icon: KeyRound },
]

const fieldClass = 'min-h-10 w-full min-w-0 rounded-md border border-surface-border bg-surface px-2.5 py-2 text-xs text-gray-100 outline-none transition focus:border-jmix-500'
const labelClass = 'mb-1 block text-[10px] font-semibold uppercase tracking-[0.12em] text-gray-500'
const panelClass = 'min-w-0 border-surface-border bg-surface-light/35'
const consumerKinds = new Set<IntegrationConnectorKind>(['KAFKA_CONSUMER', 'RABBIT_CONSUMER'])
const httpKinds = new Set<IntegrationConnectorKind>(['HTTP_CLIENT', 'WEBHOOK', 'SMS_GATEWAY', 'PAYMENT_GATEWAY', 'IDENTITY_PROVIDER'])
const brokerKinds = new Set<IntegrationConnectorKind>(['KAFKA_PUBLISHER', 'KAFKA_CONSUMER', 'RABBIT_PUBLISHER', 'RABBIT_CONSUMER'])
const publisherKinds = new Set<IntegrationConnectorKind>(['KAFKA_PUBLISHER', 'RABBIT_PUBLISHER'])
const connectorClassNames: Record<IntegrationConnectorKind, string> = {
  HTTP_CLIENT: 'HttpClientConnector',
  WEBHOOK: 'WebhookConnector',
  KAFKA_PUBLISHER: 'KafkaPublisherConnector',
  KAFKA_CONSUMER: 'KafkaConsumerConnector',
  RABBIT_PUBLISHER: 'RabbitPublisherConnector',
  RABBIT_CONSUMER: 'RabbitConsumerConnector',
  SFTP_UPLOAD: 'SftpUploadConnector',
  SFTP_DOWNLOAD: 'SftpDownloadConnector',
  JMIX_EMAIL: 'JmixEmailConnector',
  JMIX_FILE_STORAGE: 'JmixFileStorageConnector',
  OBJECT_STORAGE: 'ObjectStorageConnector',
  SMS_GATEWAY: 'SmsGatewayConnector',
  PAYMENT_GATEWAY: 'PaymentGatewayConnector',
  IDENTITY_PROVIDER: 'IdentityProviderConnector',
}

function classNameFor(kind: IntegrationConnectorKind) {
  return connectorClassNames[kind]
}

function beanNameFor(className: string) {
  return className ? className.charAt(0).toLowerCase() + className.slice(1) : ''
}

function outboxTableName(beanName: string) {
  const snake = beanName
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^A-Za-z0-9]+/g, '_')
    .toLowerCase()
    .replace(/^_+|_+$/g, '')
  const prefix = 'jvw_'
  const suffix = '_outbox'
  const core = (snake || 'integration').slice(0, 30 - prefix.length - suffix.length).replace(/_+$/g, '')
  return `${prefix}${core}${suffix}`
}

function inboxTableName(beanName: string) {
  return outboxTableName(beanName).replace(/_outbox$/, '_inbox')
}

function prefixFor(kind: IntegrationConnectorKind) {
  return `app.integration.${kind.toLowerCase().replace(/_/g, '-')}`
}

function addressSuffix(kind: IntegrationConnectorKind) {
  if (kind.includes('KAFKA')) return 'topic'
  if (kind.includes('RABBIT')) return 'destination'
  if (kind.includes('SFTP')) return 'remote-path'
  if (kind === 'JMIX_EMAIL') return 'recipient'
  if (kind.includes('STORAGE')) return 'storage-name'
  return 'url'
}

function addressLabel(kind: IntegrationConnectorKind) {
  if (kind.includes('KAFKA')) return 'Topic property'
  if (kind.includes('RABBIT')) return 'Destination property'
  if (kind.includes('SFTP')) return 'Remote path property'
  if (kind === 'JMIX_EMAIL') return 'Recipient property'
  if (kind.includes('STORAGE')) return 'Storage name property'
  return 'Endpoint URL property'
}

function defaultModel(workspace: IntegrationConnectorWorkspaceResponse, kind: IntegrationConnectorKind = 'HTTP_CLIENT'): IntegrationConnectorModel {
  const destination = workspace.destinations.find((candidate) => candidate.id === workspace.defaultDestinationId)
    ?? workspace.destinations[0]
  const inboxStore = consumerKinds.has(kind)
    ? workspace.dataStores.find((candidate) => (
      candidate.moduleId === destination?.moduleId &&
      candidate.rootChangelogPath &&
      candidate.generatedDirectory
    ))
    : undefined
  const className = classNameFor(kind)
  const prefix = prefixFor(kind)
  const model: IntegrationConnectorModel = {
    name: definitions.find((definition) => definition.kind === kind)?.label ?? 'Integration connector',
    description: '',
    destinationId: destination?.id ?? '',
    packageName: destination?.defaultPackage ?? 'com.example.app.integration',
    className,
    beanName: beanNameFor(className),
    kind,
    configurationPrefix: prefix,
    addressProperty: `${prefix}.${addressSuffix(kind)}`,
    payloadJavaType: kind.includes('SFTP') || kind.includes('STORAGE') ? 'byte[]' : 'java.lang.String',
    responseJavaType: kind === 'SFTP_DOWNLOAD' ? 'byte[]' : httpKinds.has(kind) ? 'java.lang.String' : 'void',
    httpMethod: 'POST',
    contentType: 'application/json',
    headers: [],
    authentication: { kind: 'NONE', evictInvalidAuthorizedClient: true, scopes: [] },
    transportSecurity: {
      mutualTlsEnabled: false,
    },
    reliability: {
      deliveryGuarantee: 'AT_LEAST_ONCE',
      connectTimeoutMs: 5000,
      requestTimeoutMs: 30000,
      retry: {
        mode: consumerKinds.has(kind) ? 'BLOCKING' : 'NONE',
        attempts: consumerKinds.has(kind) ? 4 : 1,
        backoff: 'EXPONENTIAL',
        initialDelayMs: 500,
        multiplier: 2,
        maximumDelayMs: 30000,
        deadLetterDestinationProperty: consumerKinds.has(kind) ? `${prefix}.dead-letter-destination` : undefined,
      },
      circuitBreaker: {
        enabled: false,
        slidingWindowSize: 100,
        minimumCalls: 20,
        failureRateThreshold: 50,
        openStateMs: 30000,
      },
      bulkhead: { enabled: false, maximumConcurrentCalls: 25, maximumWaitMs: 0 },
      rateLimit: { enabled: false, callsPerPeriod: 100, periodMs: 1000, timeoutMs: 0 },
      idempotency: {
        enabled: consumerKinds.has(kind),
        headerName: consumerKinds.has(kind) ? 'jvw-outbox-id' : 'Idempotency-Key',
        keyParameterName: consumerKinds.has(kind) ? 'messageId' : 'idempotencyKey',
      },
      transactional: consumerKinds.has(kind),
      outboxEnabled: false,
      inboxEnabled: consumerKinds.has(kind),
      inbox: consumerKinds.has(kind) ? {
        storeId: inboxStore?.id ?? '',
        tableName: inboxTableName(beanNameFor(className)),
        jsonApi: destination?.jsonApi,
        messageIdHeader: 'jvw-outbox-id',
        maximumPayloadBytes: 1048576,
        maintenanceBatchSize: 1000,
        retentionDays: 90,
        replayPermission: 'jvw.integration.inbox.replay',
        maintenancePermission: 'jvw.integration.inbox.maintain',
      } : undefined,
      orderingRequired: false,
    },
    observability: {
      metricsEnabled: true,
      tracingEnabled: true,
      structuredLoggingEnabled: true,
      auditEnabled: false,
      runtimeApi: destination?.observabilityApi,
      redactHeaders: ['Authorization', 'Proxy-Authorization', 'X-Api-Key', 'Cookie', 'Set-Cookie'],
    },
    runtimeJsonApi: destination?.jsonApi,
    runtimeSpringBootApi: destination?.springBootApi,
    profiles: [],
    enabled: true,
  }
  if (kind === 'IDENTITY_PROVIDER') {
    model.authentication = {
      kind: 'OAUTH2_CLIENT_CREDENTIALS',
      authorizedClientManagerBeanName: workspace.oauth2Managers[0]?.beanName,
      authorizedClientServiceBeanName: workspace.oauth2Services[0]?.beanName,
      clientRegistrationIdProperty: `${prefix}.client-registration-id`,
      principalNameProperty: `${prefix}.principal-name`,
      evictInvalidAuthorizedClient: true,
      scopes: [],
    }
  }
  return model
}

function modelFromCatalog(
  workspace: IntegrationConnectorWorkspaceResponse,
  catalog: IntegrationOrganizationConnectorTemplateSnapshot,
  selectedDestinationId?: string,
): IntegrationConnectorModel {
  const template = catalog.template
  const model = defaultModel(workspace, template.kind)
  const destination = workspace.destinations.find((candidate) => candidate.id === selectedDestinationId)
    ?? workspace.destinations.find((candidate) => candidate.id === model.destinationId)
    ?? workspace.destinations[0]
  const prefix = `app.integration.${template.configurationPrefixSuffix}`
  const classStem = template.id
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('')
  model.name = template.name
  model.description = template.description
  model.destinationId = destination?.id ?? model.destinationId
  model.packageName = destination?.defaultPackage ?? model.packageName
  model.className = `${classStem || classNameFor(template.kind)}Connector`
  model.beanName = beanNameFor(model.className)
  model.configurationPrefix = prefix
  model.addressProperty = `${prefix}.${template.addressPropertySuffix}`
  model.headers = template.headers.map((header) => ({
    name: header.name,
    valueProperty: `${prefix}.${header.propertySuffix}`,
    sensitive: header.sensitive,
  }))
  if (template.policy.requiredAuthentication) {
    model.authentication = {
      kind: template.policy.requiredAuthentication,
      evictInvalidAuthorizedClient: true,
      scopes: [],
      authorizedClientManagerBeanName: template.policy.requiredAuthentication === 'OAUTH2_CLIENT_CREDENTIALS'
        ? workspace.oauth2Managers[0]?.beanName
        : undefined,
      authorizedClientServiceBeanName: template.policy.requiredAuthentication === 'OAUTH2_CLIENT_CREDENTIALS'
        ? workspace.oauth2Services[0]?.beanName
        : undefined,
      clientRegistrationIdProperty: template.policy.requiredAuthentication === 'OAUTH2_CLIENT_CREDENTIALS'
        ? `${prefix}.client-registration-id`
        : undefined,
      principalNameProperty: template.policy.requiredAuthentication === 'OAUTH2_CLIENT_CREDENTIALS'
        ? `${prefix}.principal-name`
        : undefined,
    }
  }
  model.transportSecurity = {
    mutualTlsEnabled: template.policy.requireMutualTls,
    sslBundleNameProperty: template.policy.requireMutualTls ? `${prefix}.ssl-bundle` : undefined,
  }
  model.reliability.connectTimeoutMs = Math.min(
    model.reliability.connectTimeoutMs,
    template.policy.maximumConnectTimeoutMs,
  )
  model.reliability.requestTimeoutMs = Math.min(
    model.reliability.requestTimeoutMs,
    template.policy.maximumRequestTimeoutMs,
  )
  if (template.policy.minimumRetryAttempts > 1) {
    model.reliability.retry.mode = 'BLOCKING'
    model.reliability.retry.attempts = Math.max(
      model.reliability.retry.attempts,
      template.policy.minimumRetryAttempts,
    )
  }
  model.reliability.transactional ||= template.policy.requireTransactional
  model.reliability.idempotency.enabled ||= template.policy.requireIdempotency
  model.reliability.outboxEnabled ||= template.policy.requireOutbox
  model.reliability.inboxEnabled ||= template.policy.requireInbox
  const store = workspace.dataStores.find((candidate) => (
    candidate.moduleId === destination?.moduleId &&
    candidate.rootChangelogPath &&
    candidate.generatedDirectory
  ))
  if (template.policy.requireOutbox) {
    model.reliability.outbox = {
      storeId: store?.id ?? '',
      tableName: outboxTableName(model.beanName),
      jsonApi: destination?.jsonApi,
      batchSize: 100,
      pollDelayMs: 1000,
      leaseDurationMs: Math.max(60000, model.reliability.requestTimeoutMs),
      maxAttempts: 12,
      initialBackoffMs: 1000,
      maximumBackoffMs: 900000,
      retentionDays: 30,
      replayPermission: 'jvw.integration.outbox.replay',
      maintenancePermission: 'jvw.integration.outbox.maintain',
    }
  }
  if (template.policy.requireInbox) {
    model.reliability.inbox = {
      storeId: store?.id ?? '',
      tableName: inboxTableName(model.beanName),
      jsonApi: destination?.jsonApi,
      messageIdHeader: 'jvw-outbox-id',
      maximumPayloadBytes: 1048576,
      maintenanceBatchSize: 1000,
      retentionDays: 90,
      replayPermission: 'jvw.integration.inbox.replay',
      maintenancePermission: 'jvw.integration.inbox.maintain',
    }
  }
  model.observability.metricsEnabled ||= template.policy.requireMetrics
  model.observability.tracingEnabled ||= template.policy.requireTracing
  model.observability.structuredLoggingEnabled ||= template.policy.requireStructuredLogging
  model.observability.auditEnabled ||= template.policy.requireAudit
  model.observability.runtimeApi = template.policy.requiredObservabilityApi
    ?? destination?.observabilityApi
    ?? model.observability.runtimeApi
  model.runtimeJsonApi = destination?.jsonApi
  model.runtimeSpringBootApi = destination?.springBootApi
  model.catalogBinding = {
    catalogId: catalog.catalogId,
    catalogVersion: catalog.catalogVersion,
    bundleSha256: catalog.bundleSha256,
    templateId: template.id,
    templateVersion: template.version,
  }
  return model
}

function requiredCapabilities(model: IntegrationConnectorModel): IntegrationCapability[] {
  const definition = definitions.find((candidate) => candidate.kind === model.kind)
  const result = new Set<IntegrationCapability>()
  if (definition) result.add(definition.capability)
  if (model.kind === 'IDENTITY_PROVIDER' || model.authentication.kind === 'OAUTH2_CLIENT_CREDENTIALS') {
    result.add('SPRING_WEB')
    result.add('OAUTH2_CLIENT')
  }
  if (model.transportSecurity.mutualTlsEnabled) result.add('SPRING_BOOT_SSL_BUNDLES')
  if (
    model.reliability.retry.mode === 'BLOCKING' ||
    model.reliability.circuitBreaker.enabled ||
    model.reliability.bulkhead.enabled ||
    model.reliability.rateLimit.enabled
  ) result.add('RESILIENCE4J')
  return [...result]
}

function localBlockers(
  model: IntegrationConnectorModel,
  available: IntegrationCapability[],
  stores: SchemaDataStoreSnapshot[],
  moduleId?: string,
) {
  const blockers: string[] = []
  if (!model.name.trim() || !model.packageName.trim() || !model.className.trim() || !model.beanName.trim()) {
    blockers.push('Name, package, class, and bean identity are required.')
  }
  if (!/^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$/.test(model.addressProperty)) {
    blockers.push('The endpoint, topic, queue, path, or storage must be an external property key.')
  }
  const missing = requiredCapabilities(model).filter((capability) => !available.includes(capability))
  if (missing.length) blockers.push(`Selected module is missing: ${missing.join(', ')}.`)
  if (consumerKinds.has(model.kind) && (!model.handlerBeanClass || !model.handlerMethod || !model.handlerFieldName)) {
    blockers.push('Inbound connectors require an indexed handler type, field name, and method.')
  }
  if (model.reliability.retry.mode !== 'NONE' && consumerKinds.has(model.kind) && !model.reliability.retry.deadLetterDestinationProperty) {
    blockers.push('Retried consumers require an externalized dead-letter destination property.')
  }
  if (
    httpKinds.has(model.kind) &&
    ['POST', 'PATCH', 'DELETE'].includes(model.httpMethod) &&
    model.reliability.retry.mode !== 'NONE' &&
    !model.reliability.idempotency.enabled
  ) blockers.push('Retrying this non-idempotent HTTP operation requires an idempotency key.')
  if (model.reliability.outboxEnabled) {
    const outbox = model.reliability.outbox
    const store = stores.find((candidate) => candidate.id === outbox?.storeId)
    if (!outbox || !store) blockers.push('Select an indexed Liquibase data store for the durable outbox.')
    else if (store.moduleId !== moduleId) blockers.push('Outbox data store must belong to the selected connector module.')
    else if (!store.rootChangelogPath || !store.generatedDirectory) blockers.push('Selected data store has no safe Liquibase migration destination.')
    if (!model.reliability.transactional) blockers.push('Durable enqueue requires a transaction.')
    if (model.reliability.deliveryGuarantee === 'EXACTLY_ONCE') blockers.push('Database-to-broker delivery is at-least-once; exactly-once would be a false guarantee.')
  }
  if (consumerKinds.has(model.kind)) {
    const inbox = model.reliability.inbox
    const store = stores.find((candidate) => candidate.id === inbox?.storeId)
    if (!model.reliability.inboxEnabled || !inbox || !store) {
      blockers.push('Enterprise consumers require a persistent inbox in an indexed Liquibase data store.')
    } else if (store.moduleId !== moduleId) {
      blockers.push('Inbox data store must belong to the selected connector module.')
    } else if (!store.rootChangelogPath || !store.generatedDirectory) {
      blockers.push('Selected inbox data store has no safe Liquibase migration destination.')
    }
    if (!model.reliability.transactional) blockers.push('Persistent inbox processing requires a transaction.')
    if (!model.reliability.idempotency.enabled) blockers.push('Persistent consumers require a stable message ID.')
  }
  if (model.kind === 'IDENTITY_PROVIDER' && model.authentication.kind !== 'OAUTH2_CLIENT_CREDENTIALS') {
    blockers.push('Identity-provider connectors require OAuth2 client credentials.')
  }
  if (model.authentication.kind === 'OAUTH2_CLIENT_CREDENTIALS') {
    if (!model.authentication.authorizedClientManagerBeanName) {
      blockers.push('Select an indexed OAuth2AuthorizedClientManager bean.')
    }
    if (!model.authentication.clientRegistrationIdProperty || !model.authentication.principalNameProperty) {
      blockers.push('OAuth2 registration ID and application principal must use external property keys.')
    }
    if (model.authentication.evictInvalidAuthorizedClient && !model.authentication.authorizedClientServiceBeanName) {
      blockers.push('Select an indexed OAuth2AuthorizedClientService for invalid-token eviction.')
    }
  }
  if (model.transportSecurity.mutualTlsEnabled) {
    if (!httpKinds.has(model.kind)) blockers.push('Mutual TLS is available only for HTTP-based connectors.')
    if (!model.transportSecurity.sslBundleNameProperty) {
      blockers.push('Mutual TLS requires an externalized Spring Boot SSL-bundle name property.')
    }
  }
  return blockers
}

function cloneModel(model: IntegrationConnectorModel): IntegrationConnectorModel {
  return JSON.parse(JSON.stringify(model)) as IntegrationConnectorModel
}

function Section({ title, icon: Icon, children }: { title: string; icon: typeof Gauge; children: React.ReactNode }) {
  return (
    <section className="border-b border-surface-border px-3 py-3">
      <h3 className="mb-2 flex items-center gap-2 text-[11px] font-semibold text-gray-200">
        <Icon size={14} className="text-jmix-400" /> {title}
      </h3>
      <div className="space-y-2.5">{children}</div>
    </section>
  )
}

function Toggle({
  checked, onChange, label, description, disabled = false,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: string
  description?: string
  disabled?: boolean
}) {
  return (
    <label className={`flex min-h-10 items-start gap-2 rounded-md border border-surface-border bg-surface/50 p-2 ${disabled ? 'opacity-50' : 'cursor-pointer'}`}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
        className="mt-0.5 accent-jmix-500"
      />
      <span className="min-w-0">
        <span className="block text-[11px] font-medium text-gray-200">{label}</span>
        {description && <span className="mt-0.5 block text-[10px] leading-4 text-gray-500">{description}</span>}
      </span>
    </label>
  )
}

export default function IntegrationDesigner() {
  const addToast = useStore((state) => state.addToast)
  const [workspace, setWorkspace] = useState<IntegrationConnectorWorkspaceResponse | null>(null)
  const [model, setModel] = useState<IntegrationConnectorModel | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [preview, setPreview] = useState<WorkspaceChangePreviewResponse | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [applying, setApplying] = useState(false)
  const [approvingCatalog, setApprovingCatalog] = useState(false)
  const [activePreviewFile, setActivePreviewFile] = useState(0)
  const historyRef = useRef<IntegrationConnectorModel[]>([])
  const futureRef = useRef<IntegrationConnectorModel[]>([])
  const [, rerenderHistory] = useState(0)

  const load = async (forceRefresh = false) => {
    setLoading(true)
    setError(null)
    try {
      const next = await bridge.getIntegrationConnectorWorkspace(forceRefresh)
      if (next.error) throw new Error(next.error)
      setWorkspace(next)
      setModel((current) => current ?? (
        next.existingDocuments.find((document) => document.editable)?.model ?? defaultModel(next)
      ))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Integration workspace request failed.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void load() }, [])

  const commit = (mutate: (draft: IntegrationConnectorModel) => void) => {
    setModel((current) => {
      if (!current) return current
      const next = cloneModel(current)
      mutate(next)
      if (JSON.stringify(next) === JSON.stringify(current)) return current
      historyRef.current.push(cloneModel(current))
      if (historyRef.current.length > 100) historyRef.current.shift()
      futureRef.current = []
      rerenderHistory((value) => value + 1)
      return next
    })
  }

  const replaceModel = (next: IntegrationConnectorModel) => {
    if (model) historyRef.current.push(cloneModel(model))
    futureRef.current = []
    setModel(cloneModel(next))
    setPreview(null)
    rerenderHistory((value) => value + 1)
  }

  const undo = () => {
    const previous = historyRef.current.pop()
    if (!previous || !model) return
    futureRef.current.push(cloneModel(model))
    setModel(previous)
    setPreview(null)
    rerenderHistory((value) => value + 1)
  }

  const redo = () => {
    const next = futureRef.current.pop()
    if (!next || !model) return
    historyRef.current.push(cloneModel(model))
    setModel(next)
    setPreview(null)
    rerenderHistory((value) => value + 1)
  }

  const destination = workspace?.destinations.find((candidate) => candidate.id === model?.destinationId)
    ?? workspace?.destinations[0]
  const definition = definitions.find((candidate) => candidate.kind === model?.kind)
  const handlers = workspace?.contextArtifacts.filter((artifact) => (
    ['SERVICE', 'BUSINESS_RULE', 'REPOSITORY', 'VALIDATOR', 'SOURCE_TYPE'].includes(artifact.kind)
  )) ?? []
  const selectedCatalogTemplate = workspace?.organizationConnectorTemplates.find((candidate) => (
    candidate.catalogId === model?.catalogBinding?.catalogId &&
    candidate.catalogVersion === model?.catalogBinding?.catalogVersion &&
    candidate.bundleSha256 === model?.catalogBinding?.bundleSha256 &&
    candidate.template.id === model?.catalogBinding?.templateId &&
    candidate.template.version === model?.catalogBinding?.templateVersion
  ))
  const blockers = model ? localBlockers(
    model,
    destination?.capabilities ?? [],
    workspace?.dataStores ?? [],
    destination?.moduleId,
  ) : []
  if (model?.catalogBinding && !selectedCatalogTemplate) {
    blockers.push('The bound organization connector template is no longer available. Refresh the signed catalog.')
  }
  if (
    selectedCatalogTemplate &&
    selectedCatalogTemplate.template.policy.risk !== 'STANDARD' &&
    !model?.catalogBinding?.approvalCapability
  ) {
    blockers.push('This organization connector requires explicit native IntelliJ approval.')
  }
  const groups = [...new Set(definitions.map((candidate) => candidate.group))]
  const normalizedQuery = query.trim().toLowerCase()
  const visibleDefinitions = definitions.filter((candidate) => (
    !normalizedQuery ||
    `${candidate.label} ${candidate.description} ${candidate.group}`.toLowerCase().includes(normalizedQuery)
  ))
  const selectedHandler = handlers.find((handler) => handler.semanticKey === model?.handlerBeanClass)

  const chooseKind = (kind: IntegrationConnectorKind) => {
    if (!workspace) return
    const next = defaultModel(workspace, kind)
    if (destination) {
      next.destinationId = destination.id
      next.packageName = destination.defaultPackage
    }
    replaceModel(next)
  }

  const chooseCatalogTemplate = (catalog: IntegrationOrganizationConnectorTemplateSnapshot) => {
    if (!workspace) return
    replaceModel(modelFromCatalog(workspace, catalog, destination?.id))
  }

  const approveCatalogTemplate = async () => {
    if (!model?.catalogBinding) return
    setApprovingCatalog(true)
    setError(null)
    try {
      const result = await bridge.approveIntegrationConnectorCatalogTemplate(
        model.catalogBinding,
        model.destinationId,
      )
      if (!result.approved || !result.approval) {
        setError(result.message ?? 'Organization connector approval was not granted.')
        return
      }
      commit((draft) => {
        if (draft.catalogBinding) {
          draft.catalogBinding.approvalCapability = result.approval?.capability
        }
      })
      addToast(
        `Approved ${selectedCatalogTemplate?.template.name ?? 'organization connector'} until ${new Date(result.approval.expiresAt).toLocaleTimeString()}.`,
        'success',
      )
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Organization connector approval failed.')
    } finally {
      setApprovingCatalog(false)
    }
  }

  const previewChanges = async () => {
    if (!model || blockers.length) return
    setPreviewing(true)
    setError(null)
    try {
      const result = await bridge.previewIntegrationConnector(model)
      setPreview(result)
      setActivePreviewFile(0)
      if (!result.accepted) setError(result.issues.map((issue) => issue.message).join(' '))
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Connector preview failed.')
    } finally {
      setPreviewing(false)
    }
  }

  const applyChanges = async () => {
    if (!model || !preview?.accepted || !preview.planDigest) return
    setApplying(true)
    try {
      const result = await bridge.applyIntegrationConnector(model, preview.planDigest)
      if (!result.success) {
        setError(result.issues.map((issue) => issue.message).join(' ') || 'Connector apply failed.')
        return
      }
      addToast(`Applied ${result.filesChanged.length} source-safe connector files.`, 'success')
      setPreview(null)
      historyRef.current = []
      futureRef.current = []
      rerenderHistory((value) => value + 1)
      await load(true)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Connector apply failed.')
    } finally {
      setApplying(false)
    }
  }

  if (loading && !workspace) {
    return <div className="flex h-full items-center justify-center gap-2 text-sm text-gray-400"><Loader2 className="animate-spin" size={18} /> Indexing integration capabilities…</div>
  }

  if (!workspace || !model) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-3 p-6 text-center">
        <AlertTriangle className="text-amber-400" />
        <p className="max-w-lg text-sm text-gray-300">{error ?? 'No production Java/resource destination is available.'}</p>
        <button className="btn-primary" onClick={() => void load(true)}>Retry indexing</button>
      </div>
    )
  }

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col overflow-hidden">
      <header className="flex min-w-0 shrink-0 flex-wrap items-center gap-2 border-b border-surface-border bg-surface-light px-3 py-2">
        <div className="mr-auto min-w-0">
          <h2 className="truncate text-sm font-semibold text-gray-100">Enterprise Integration Designer</h2>
          <p className="truncate text-[10px] text-gray-500">Externalized configuration · fail-closed reliability · exact source ownership</p>
        </div>
        <button className="btn-secondary flex items-center gap-1.5" disabled={!historyRef.current.length} onClick={undo} title="Undo visual edit">
          <Undo2 size={13} /> <span className="hidden sm:inline">Undo</span>
        </button>
        <button className="btn-secondary flex items-center gap-1.5" disabled={!futureRef.current.length} onClick={redo} title="Redo visual edit">
          <RotateCw size={13} /> <span className="hidden sm:inline">Redo</span>
        </button>
        <button className="btn-secondary flex items-center gap-1.5" onClick={() => void load(true)}>
          <RefreshCw size={13} className={loading ? 'animate-spin' : ''} /> Refresh
        </button>
        <button className="btn-primary flex items-center gap-1.5" disabled={previewing || blockers.length > 0} onClick={() => void previewChanges()}>
          {previewing ? <Loader2 size={13} className="animate-spin" /> : <Code2 size={13} />} Preview source
        </button>
      </header>

      {error && (
        <div className="flex shrink-0 items-start gap-2 border-b border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-200">
          <AlertTriangle size={14} className="mt-0.5 shrink-0" />
          <span className="min-w-0 flex-1">{error}</span>
          <button aria-label="Dismiss error" onClick={() => setError(null)}><X size={14} /></button>
        </div>
      )}

      <div className="integration-designer-grid min-h-0 min-w-0 flex-1">
        <aside className={`${panelClass} integration-catalog min-h-0 border-r`}>
          <div className="sticky top-0 z-10 border-b border-surface-border bg-surface-light p-3">
            <div className="mb-2 flex items-center justify-between">
              <span className="text-[11px] font-semibold text-gray-200">Connector catalog</span>
              <span className="rounded-full bg-jmix-500/15 px-2 py-0.5 text-[9px] text-jmix-300">{definitions.length} adapters</span>
            </div>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Search protocols…"
              aria-label="Search connector catalog"
              className={fieldClass}
            />
          </div>
          <div className="p-2">
            {workspace.organizationConnectorTemplates.length > 0 && (
              <section className="mb-3" aria-label="Organization connector catalog">
                <h3 className="px-1 pb-1.5 text-[9px] font-semibold uppercase tracking-widest text-emerald-500">
                  Signed organization catalog
                </h3>
                <div className="space-y-1">
                  {workspace.organizationConnectorTemplates
                    .filter((catalog) => (
                      !normalizedQuery ||
                      `${catalog.catalogDisplayName} ${catalog.template.name} ${catalog.template.provider} ${catalog.template.description}`
                        .toLowerCase()
                        .includes(normalizedQuery)
                    ))
                    .map((catalog) => {
                      const compatible = Boolean(
                        destination &&
                        catalog.template.springBootApis.includes(destination.springBootApi) &&
                        catalog.template.requiredCapabilities.every((capability) => (
                          destination.capabilities.includes(capability)
                        )),
                      )
                      const selected = selectedCatalogTemplate === catalog
                      return (
                        <button
                          key={`${catalog.catalogId}:${catalog.catalogVersion}:${catalog.template.id}:${catalog.template.version}:${catalog.bundleSha256}`}
                          onClick={() => compatible && chooseCatalogTemplate(catalog)}
                          disabled={!compatible}
                          className={`w-full rounded-md border p-2 text-left transition ${
                            selected
                              ? 'border-emerald-400/60 bg-emerald-500/10'
                              : compatible
                                ? 'border-emerald-500/20 bg-emerald-500/[0.04] hover:border-emerald-400/45 hover:bg-emerald-500/[0.08]'
                                : 'cursor-not-allowed border-surface-border bg-surface/30 opacity-55'
                          }`}
                        >
                          <span className="flex min-w-0 items-center justify-between gap-2">
                            <span className="truncate text-[11px] font-semibold text-gray-100">
                              {catalog.template.name}
                            </span>
                            <span className={`shrink-0 rounded px-1.5 py-0.5 text-[8px] font-semibold ${
                              catalog.template.policy.risk === 'STANDARD'
                                ? 'bg-emerald-500/10 text-emerald-300'
                                : catalog.template.policy.risk === 'SENSITIVE'
                                  ? 'bg-amber-500/10 text-amber-300'
                                  : 'bg-red-500/10 text-red-300'
                            }`}>
                              {catalog.template.policy.risk}
                            </span>
                          </span>
                          <span className="mt-0.5 block truncate text-[9px] text-emerald-300/80">
                            {catalog.template.provider} · {catalog.catalogDisplayName}
                          </span>
                          <span className="mt-1 block text-[9px] leading-3.5 text-gray-500">
                            {compatible ? catalog.template.description : 'Not compatible with the selected module capabilities.'}
                          </span>
                          <span className="mt-1 block truncate font-mono text-[8px] text-gray-600">
                            {catalog.catalogId}:{catalog.catalogVersion} · {catalog.template.version}
                          </span>
                        </button>
                      )
                    })}
                </div>
              </section>
            )}
            {workspace.existingDocuments.length > 0 && (
              <section className="mb-3">
                <h3 className="px-1 pb-1.5 text-[9px] font-semibold uppercase tracking-widest text-gray-600">Existing connectors</h3>
                {workspace.existingDocuments.map((document) => (
                  <button
                    key={document.locator.relativePath}
                    className={`mb-1 flex w-full items-start gap-2 rounded-md border p-2 text-left ${
                      model.sourceLocator?.relativePath === document.locator.relativePath
                        ? 'border-jmix-500/60 bg-jmix-500/10'
                        : 'border-surface-border bg-surface/40 hover:bg-surface-lighter'
                    }`}
                    onClick={() => replaceModel(document.model)}
                  >
                    {document.editable ? <CheckCircle2 size={13} className="mt-0.5 shrink-0 text-emerald-400" /> : <FileKey2 size={13} className="mt-0.5 shrink-0 text-amber-400" />}
                    <span className="min-w-0">
                      <span className="block truncate text-[11px] text-gray-200">{document.model.name}</span>
                      <span className="block truncate text-[9px] text-gray-500">{document.editable ? 'Owned and editable' : 'Manual changes · read only'}</span>
                    </span>
                  </button>
                ))}
              </section>
            )}
            {groups.map((group) => {
              const items = visibleDefinitions.filter((candidate) => candidate.group === group)
              if (!items.length) return null
              return (
                <section key={group} className="mb-3">
                  <h3 className="px-1 pb-1.5 text-[9px] font-semibold uppercase tracking-widest text-gray-600">{group}</h3>
                  <div className="space-y-1">
                    {items.map((item) => {
                      const Icon = item.icon
                      const available = destination?.capabilities.includes(item.capability)
                      return (
                        <button
                          key={item.kind}
                          onClick={() => chooseKind(item.kind)}
                          className={`group flex w-full items-start gap-2 rounded-md border p-2 text-left transition ${
                            model.kind === item.kind && !model.sourceLocator
                              ? 'border-jmix-500/60 bg-jmix-500/10'
                              : 'border-transparent hover:border-surface-border hover:bg-surface-lighter'
                          }`}
                        >
                          <span className={`rounded p-1 ${available ? 'bg-emerald-500/10 text-emerald-300' : 'bg-gray-500/10 text-gray-500'}`}><Icon size={14} /></span>
                          <span className="min-w-0 flex-1">
                            <span className="flex items-center justify-between gap-1">
                              <span className="text-[11px] font-medium text-gray-200">{item.label}</span>
                              <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${available ? 'bg-emerald-400' : 'bg-gray-600'}`} title={available ? 'Dependency detected' : 'Dependency not detected'} />
                            </span>
                            <span className="mt-0.5 block text-[9px] leading-3.5 text-gray-500">{item.description}</span>
                          </span>
                        </button>
                      )
                    })}
                  </div>
                </section>
              )
            })}
          </div>
        </aside>

        <main className={`${panelClass} integration-canvas min-h-0 border-r`}>
          <div className="border-b border-surface-border p-3">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <span className="rounded-md bg-jmix-500/15 p-2 text-jmix-300">{definition && <definition.icon size={18} />}</span>
              <div className="min-w-0 flex-1">
                <input
                  value={model.name}
                  onChange={(event) => commit((draft) => { draft.name = event.target.value })}
                  className="min-h-10 w-full min-w-0 border-0 bg-transparent text-base font-semibold text-gray-100 outline-none"
                  aria-label="Connector name"
                />
                <p className="text-[10px] text-gray-500">{definition?.direction} · {definition?.description}</p>
              </div>
              <span className={`rounded-full px-2 py-1 text-[9px] font-semibold ${blockers.length ? 'bg-amber-500/10 text-amber-300' : 'bg-emerald-500/10 text-emerald-300'}`}>
                {blockers.length ? `${blockers.length} blocker${blockers.length === 1 ? '' : 's'}` : 'Ready to preview'}
              </span>
            </div>

            <div className="integration-flow" aria-label="Connector architecture">
              <div className="integration-flow-node"><Boxes size={15} /><span>Jmix service</span></div>
              <ChevronRight size={14} className="integration-flow-arrow" />
              <div className="integration-flow-node integration-flow-node-active"><CircuitBoard size={15} /><span>{definition?.label}</span></div>
              <ChevronRight size={14} className="integration-flow-arrow" />
              <div className="integration-flow-node"><Network size={15} /><span>{definition?.direction === 'Inbound' ? 'Handler' : 'External system'}</span></div>
            </div>
            {selectedCatalogTemplate && (
              <div className={`mt-3 rounded-lg border p-2.5 ${
                selectedCatalogTemplate.template.policy.risk === 'STANDARD'
                  ? 'border-emerald-500/25 bg-emerald-500/5'
                  : model.catalogBinding?.approvalCapability
                    ? 'border-emerald-500/25 bg-emerald-500/5'
                    : 'border-amber-500/30 bg-amber-500/[0.07]'
              }`}>
                <div className="flex min-w-0 flex-wrap items-center gap-2">
                  <ShieldCheck size={14} className="shrink-0 text-emerald-300" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[10px] font-semibold text-gray-100">
                      Signed policy · {selectedCatalogTemplate.catalogDisplayName}
                    </p>
                    <p className="truncate font-mono text-[8px] text-gray-500">
                      {selectedCatalogTemplate.template.policy.approvalPolicyId ?? 'standard-policy'} · {selectedCatalogTemplate.bundleSha256.slice(0, 12)}
                    </p>
                  </div>
                  {selectedCatalogTemplate.template.policy.risk !== 'STANDARD' && (
                    model.catalogBinding?.approvalCapability ? (
                      <span className="rounded bg-emerald-500/10 px-2 py-1 text-[9px] font-semibold text-emerald-300">
                        Native approval active
                      </span>
                    ) : (
                      <button
                        className="btn-secondary flex items-center gap-1.5 border-amber-500/30 text-amber-200"
                        disabled={approvingCatalog}
                        onClick={() => void approveCatalogTemplate()}
                      >
                        {approvingCatalog ? <Loader2 size={12} className="animate-spin" /> : <ShieldCheck size={12} />}
                        Approve in IntelliJ
                      </button>
                    )
                  )}
                </div>
              </div>
            )}
          </div>

          <div className="integration-canvas-scroll p-3">
            <section className="mb-3 rounded-lg border border-surface-border bg-surface/45 p-3">
              <h3 className="mb-3 flex items-center gap-2 text-[11px] font-semibold text-gray-200"><SlidersHorizontal size={14} className="text-jmix-400" /> Adapter contract</h3>
              <div className="grid min-w-0 gap-3 sm:grid-cols-2">
                <label>
                  <span className={labelClass}>Target module</span>
                  <select
                    value={model.destinationId}
                    onChange={(event) => {
                      const nextDestination = workspace.destinations.find((candidate) => candidate.id === event.target.value)
                      commit((draft) => {
                        draft.destinationId = event.target.value
                        if (draft.catalogBinding) {
                          draft.catalogBinding.approvalCapability = undefined
                        }
                        if (!draft.sourceLocator && nextDestination) {
                          draft.packageName = nextDestination.defaultPackage
                          if (draft.reliability.outboxEnabled) {
                            const store = workspace.dataStores.find((candidate) => (
                              candidate.moduleId === nextDestination.moduleId &&
                              candidate.rootChangelogPath &&
                              candidate.generatedDirectory
                            ))
                          if (draft.reliability.outbox) {
                              draft.reliability.outbox.storeId = store?.id ?? ''
                              draft.reliability.outbox.jsonApi = nextDestination.jsonApi
                            }
                          }
                          if (draft.reliability.inboxEnabled) {
                            const store = workspace.dataStores.find((candidate) => (
                              candidate.moduleId === nextDestination.moduleId &&
                              candidate.rootChangelogPath &&
                              candidate.generatedDirectory
                            ))
                            if (draft.reliability.inbox) {
                              draft.reliability.inbox.storeId = store?.id ?? ''
                              draft.reliability.inbox.jsonApi = nextDestination.jsonApi
                            }
                          }
                          draft.observability.runtimeApi = nextDestination.observabilityApi
                        }
                      })
                    }}
                    disabled={Boolean(model.sourceLocator)}
                    className={fieldClass}
                  >
                    {workspace.destinations.map((candidate) => (
                      <option key={candidate.id} value={candidate.id}>{candidate.moduleId} · {candidate.capabilities.length} capabilities</option>
                    ))}
                  </select>
                </label>
                <label>
                  <span className={labelClass}>{addressLabel(model.kind)}</span>
                  <input value={model.addressProperty} onChange={(event) => commit((draft) => { draft.addressProperty = event.target.value })} className={fieldClass} />
                </label>
                <label>
                  <span className={labelClass}>Payload Java type</span>
                  <input value={model.payloadJavaType} onChange={(event) => commit((draft) => { draft.payloadJavaType = event.target.value })} className={fieldClass} />
                </label>
                <label>
                  <span className={labelClass}>Response Java type</span>
                  <input value={model.responseJavaType} onChange={(event) => commit((draft) => { draft.responseJavaType = event.target.value })} className={fieldClass} />
                </label>
                {httpKinds.has(model.kind) && (
                  <>
                    <label>
                      <span className={labelClass}>HTTP method</span>
                      <select value={model.httpMethod} onChange={(event) => commit((draft) => { draft.httpMethod = event.target.value as IntegrationHttpMethod })} className={fieldClass}>
                        {(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as IntegrationHttpMethod[]).map((method) => <option key={method}>{method}</option>)}
                      </select>
                    </label>
                    <label>
                      <span className={labelClass}>Content type</span>
                      <input value={model.contentType} onChange={(event) => commit((draft) => { draft.contentType = event.target.value })} className={fieldClass} />
                    </label>
                  </>
                )}
              </div>
            </section>

            {consumerKinds.has(model.kind) && (
              <section className="mb-3 rounded-lg border border-sky-500/25 bg-sky-500/5 p-3">
                <h3 className="mb-3 flex items-center gap-2 text-[11px] font-semibold text-sky-200"><Inbox size={14} /> Indexed inbound handler</h3>
                <div className="grid min-w-0 gap-3 sm:grid-cols-2">
                  <label className="sm:col-span-2">
                    <span className={labelClass}>Application type</span>
                    <select
                      value={model.handlerBeanClass ?? ''}
                      onChange={(event) => {
                        const artifact = handlers.find((candidate) => candidate.semanticKey === event.target.value)
                        commit((draft) => {
                          draft.handlerBeanClass = event.target.value || undefined
                          draft.handlerFieldName = artifact ? beanNameFor(artifact.displayName) : undefined
                        })
                      }}
                      className={fieldClass}
                    >
                      <option value="">Select an indexed service or rule…</option>
                      {handlers.map((handler) => <option key={handler.id} value={handler.semanticKey}>{handler.displayName} · {handler.owner.moduleId}</option>)}
                    </select>
                  </label>
                  <label>
                    <span className={labelClass}>Injected field</span>
                    <input value={model.handlerFieldName ?? ''} onChange={(event) => commit((draft) => { draft.handlerFieldName = event.target.value || undefined })} className={fieldClass} />
                  </label>
                  <label>
                    <span className={labelClass}>Handler method</span>
                    <input value={model.handlerMethod ?? ''} onChange={(event) => commit((draft) => { draft.handlerMethod = event.target.value || undefined })} placeholder="handleMessage" className={fieldClass} />
                  </label>
                </div>
                {selectedHandler && <p className="mt-2 text-[10px] text-gray-500">Impact target: {selectedHandler.semanticKey}</p>}
              </section>
            )}

            <section className="mb-3 rounded-lg border border-surface-border bg-surface/45 p-3">
              <div className="mb-3 flex items-center justify-between">
                <h3 className="flex items-center gap-2 text-[11px] font-semibold text-gray-200"><KeyRound size={14} className="text-jmix-400" /> Authentication and headers</h3>
                <button
                  className="btn-secondary flex items-center gap-1 text-[10px]"
                  onClick={() => commit((draft) => {
                    draft.headers.push({ name: `X-Header-${draft.headers.length + 1}`, valueProperty: `${draft.configurationPrefix}.header-${draft.headers.length + 1}`, sensitive: false })
                  })}
                ><Plus size={12} /> Header</button>
              </div>
              <label className="mb-3 block">
                <span className={labelClass}>Authentication</span>
                <select
                  value={model.authentication.kind}
                  onChange={(event) => commit((draft) => {
                    const kind = event.target.value as IntegrationAuthenticationKind
                    draft.authentication = {
                      kind,
                      scopes: [],
                      evictInvalidAuthorizedClient: true,
                      headerName: kind === 'API_KEY' ? 'X-Api-Key' : undefined,
                      authorizedClientManagerBeanName: kind === 'OAUTH2_CLIENT_CREDENTIALS'
                        ? workspace.oauth2Managers[0]?.beanName
                        : undefined,
                      authorizedClientServiceBeanName: kind === 'OAUTH2_CLIENT_CREDENTIALS'
                        ? workspace.oauth2Services[0]?.beanName
                        : undefined,
                      clientRegistrationIdProperty: kind === 'OAUTH2_CLIENT_CREDENTIALS'
                        ? `${draft.configurationPrefix}.client-registration-id`
                        : undefined,
                      principalNameProperty: kind === 'OAUTH2_CLIENT_CREDENTIALS'
                        ? `${draft.configurationPrefix}.principal-name`
                        : undefined,
                    }
                  })}
                  className={fieldClass}
                >
                  {(['NONE', 'BASIC', 'BEARER', 'API_KEY', 'OAUTH2_CLIENT_CREDENTIALS', 'SSH_KEY'] as IntegrationAuthenticationKind[]).map((kind) => <option key={kind} value={kind}>{kind.replace(/_/g, ' ')}</option>)}
                </select>
              </label>
              {model.authentication.kind !== 'NONE' && (
                <div className="mb-3 grid gap-2 sm:grid-cols-2">
                  {model.authentication.kind === 'BASIC' && <input aria-label="Username property" placeholder="Username property key" value={model.authentication.usernameProperty ?? ''} onChange={(event) => commit((draft) => { draft.authentication.usernameProperty = event.target.value || undefined })} className={fieldClass} />}
                  {model.authentication.kind === 'API_KEY' && <input aria-label="API key header" placeholder="Header name" value={model.authentication.headerName ?? ''} onChange={(event) => commit((draft) => { draft.authentication.headerName = event.target.value || undefined })} className={fieldClass} />}
                  {model.authentication.kind === 'OAUTH2_CLIENT_CREDENTIALS' && (
                    <>
                      <select aria-label="OAuth2 authorized client manager" value={model.authentication.authorizedClientManagerBeanName ?? ''} onChange={(event) => commit((draft) => { draft.authentication.authorizedClientManagerBeanName = event.target.value || undefined })} className={fieldClass}>
                        <option value="">Select indexed client manager…</option>
                        {workspace.oauth2Managers.map((manager) => <option key={`${manager.moduleId}:${manager.beanName}`} value={manager.beanName}>{manager.beanName} · {manager.moduleId}</option>)}
                      </select>
                      <select aria-label="OAuth2 authorized client service" value={model.authentication.authorizedClientServiceBeanName ?? ''} onChange={(event) => commit((draft) => { draft.authentication.authorizedClientServiceBeanName = event.target.value || undefined })} className={fieldClass}>
                        <option value="">Select indexed client service…</option>
                        {workspace.oauth2Services.map((service) => <option key={`${service.moduleId}:${service.beanName}`} value={service.beanName}>{service.beanName} · {service.moduleId}</option>)}
                      </select>
                      <input aria-label="Client registration ID property" placeholder="Client registration ID property" value={model.authentication.clientRegistrationIdProperty ?? ''} onChange={(event) => commit((draft) => { draft.authentication.clientRegistrationIdProperty = event.target.value || undefined })} className={fieldClass} />
                      <input aria-label="OAuth2 principal property" placeholder="Application principal property" value={model.authentication.principalNameProperty ?? ''} onChange={(event) => commit((draft) => { draft.authentication.principalNameProperty = event.target.value || undefined })} className={fieldClass} />
                      <div className="sm:col-span-2">
                        <Toggle
                          checked={model.authentication.evictInvalidAuthorizedClient}
                          onChange={(checked) => commit((draft) => { draft.authentication.evictInvalidAuthorizedClient = checked })}
                          label="Evict invalid access tokens"
                          description="Spring Security removes rejected authorized clients so the next attempt obtains a fresh token."
                        />
                      </div>
                    </>
                  )}
                  {model.authentication.kind !== 'OAUTH2_CLIENT_CREDENTIALS' && <input aria-label="Secret property" placeholder="Secret/token/key property key" value={model.authentication.secretProperty ?? ''} onChange={(event) => commit((draft) => { draft.authentication.secretProperty = event.target.value || undefined })} className={fieldClass} />}
                </div>
              )}
              <div className="space-y-2">
                {model.headers.map((header, index) => (
                  <div key={`${header.name}-${index}`} className="grid min-w-0 gap-2 rounded border border-surface-border p-2 sm:grid-cols-[1fr_1.4fr_auto_auto]">
                    <input aria-label={`Header ${index + 1} name`} value={header.name} onChange={(event) => commit((draft) => { draft.headers[index].name = event.target.value })} className={fieldClass} />
                    <input aria-label={`Header ${index + 1} property`} value={header.valueProperty} onChange={(event) => commit((draft) => { draft.headers[index].valueProperty = event.target.value })} className={fieldClass} />
                    <label className="flex items-center gap-1 text-[10px] text-gray-400"><input type="checkbox" checked={header.sensitive} onChange={(event) => commit((draft) => { draft.headers[index].sensitive = event.target.checked })} /> Secret</label>
                    <button aria-label={`Remove header ${header.name}`} className="text-gray-500 hover:text-red-300" onClick={() => commit((draft) => { draft.headers.splice(index, 1) })}><Trash2 size={13} /></button>
                  </div>
                ))}
                {!model.headers.length && <p className="rounded border border-dashed border-surface-border p-3 text-center text-[10px] text-gray-600">No static headers. Secrets and endpoints are never stored as literal values.</p>}
              </div>
              {httpKinds.has(model.kind) && (
                <div className="mt-3 border-t border-surface-border pt-3">
                  <Toggle
                    checked={model.transportSecurity.mutualTlsEnabled}
                    onChange={(checked) => commit((draft) => {
                      draft.transportSecurity.mutualTlsEnabled = checked
                      draft.transportSecurity.sslBundleNameProperty = checked
                        ? draft.transportSecurity.sslBundleNameProperty ?? `${draft.configurationPrefix}.ssl-bundle`
                        : undefined
                    })}
                    label="Mutual TLS"
                    description="Use a named Spring Boot SSL bundle. Private keys, trust material and passwords remain external."
                  />
                  {model.transportSecurity.mutualTlsEnabled && (
                    <div className="mt-2 grid gap-2 sm:grid-cols-2">
                      <input
                        aria-label="SSL bundle name property"
                        placeholder="SSL bundle name property"
                        value={model.transportSecurity.sslBundleNameProperty ?? ''}
                        onChange={(event) => commit((draft) => { draft.transportSecurity.sslBundleNameProperty = event.target.value || undefined })}
                        className={fieldClass}
                      />
                      <div className="rounded border border-emerald-500/20 bg-emerald-500/5 px-2 py-1.5 text-[10px] leading-4 text-emerald-200">
                        HTTPS and hostname verification are mandatory. Certificate material is never copied into generated source.
                      </div>
                    </div>
                  )}
                </div>
              )}
            </section>

            <section className="rounded-lg border border-surface-border bg-surface/45 p-3">
              <h3 className="mb-3 flex items-center gap-2 text-[11px] font-semibold text-gray-200"><Code2 size={14} className="text-jmix-400" /> Source identity</h3>
              <div className="grid min-w-0 gap-3 sm:grid-cols-2">
                <label className="sm:col-span-2"><span className={labelClass}>Package</span><input disabled={Boolean(model.sourceLocator)} value={model.packageName} onChange={(event) => commit((draft) => { draft.packageName = event.target.value })} className={fieldClass} /></label>
                <label><span className={labelClass}>Java class</span><input disabled={Boolean(model.sourceLocator)} value={model.className} onChange={(event) => commit((draft) => { draft.className = event.target.value })} className={fieldClass} /></label>
                <label><span className={labelClass}>Spring bean</span><input disabled={Boolean(model.sourceLocator)} value={model.beanName} onChange={(event) => commit((draft) => { draft.beanName = event.target.value })} className={fieldClass} /></label>
                <label className="sm:col-span-2"><span className={labelClass}>Configuration prefix</span><input value={model.configurationPrefix} onChange={(event) => commit((draft) => { draft.configurationPrefix = event.target.value })} className={fieldClass} /></label>
                <label className="sm:col-span-2"><span className={labelClass}>Description</span><textarea rows={2} value={model.description} onChange={(event) => commit((draft) => { draft.description = event.target.value })} className={fieldClass} /></label>
              </div>
            </section>
          </div>
        </main>

        <aside className={`${panelClass} integration-inspector min-h-0`}>
          <div className="border-b border-surface-border bg-surface-light px-3 py-2">
            <div className="flex items-center justify-between">
              <span className="text-[11px] font-semibold text-gray-200">Reliability inspector</span>
              <Gauge size={14} className="text-jmix-400" />
            </div>
            <p className="mt-0.5 text-[9px] text-gray-500">Policies compile into source plus externalized configuration.</p>
          </div>
          <Section title="Delivery contract" icon={ShieldCheck}>
            <label>
              <span className={labelClass}>Delivery guarantee</span>
              <select value={model.reliability.deliveryGuarantee} onChange={(event) => commit((draft) => { draft.reliability.deliveryGuarantee = event.target.value as IntegrationDeliveryGuarantee })} className={fieldClass}>
                {(['AT_MOST_ONCE', 'AT_LEAST_ONCE', 'EXACTLY_ONCE'] as IntegrationDeliveryGuarantee[]).map((value) => <option key={value} value={value}>{value.replace(/_/g, ' ')}</option>)}
              </select>
            </label>
            <div className="grid grid-cols-2 gap-2">
              <label><span className={labelClass}>Connect ms</span><input type="number" value={model.reliability.connectTimeoutMs} onChange={(event) => commit((draft) => { draft.reliability.connectTimeoutMs = Number(event.target.value) })} className={fieldClass} /></label>
              <label><span className={labelClass}>Request ms</span><input type="number" value={model.reliability.requestTimeoutMs} onChange={(event) => commit((draft) => { draft.reliability.requestTimeoutMs = Number(event.target.value) })} className={fieldClass} /></label>
            </div>
            <Toggle checked={model.reliability.transactional} onChange={(checked) => commit((draft) => { draft.reliability.transactional = checked })} label="Transactional boundary" description="Use only where the provider and generated adapter can honor it." />
            <Toggle checked={model.reliability.orderingRequired} onChange={(checked) => commit((draft) => { draft.reliability.orderingRequired = checked })} label="Strict ordering required" />
            <Toggle
              checked={model.reliability.outboxEnabled}
              onChange={(checked) => commit((draft) => {
                draft.reliability.outboxEnabled = checked
                if (checked) {
                  const store = workspace.dataStores.find((candidate) => (
                    candidate.moduleId === destination?.moduleId &&
                    candidate.rootChangelogPath &&
                    candidate.generatedDirectory
                  ))
                  draft.reliability.transactional = true
                  draft.reliability.deliveryGuarantee = 'AT_LEAST_ONCE'
                  draft.reliability.outbox = {
                    storeId: store?.id ?? '',
                    tableName: outboxTableName(draft.beanName),
                    jsonApi: destination?.jsonApi,
                    batchSize: 100,
                    pollDelayMs: 1000,
                    leaseDurationMs: Math.max(60000, draft.reliability.requestTimeoutMs),
                    maxAttempts: 12,
                    initialBackoffMs: 1000,
                    maximumBackoffMs: 900000,
                    retentionDays: 30,
                    replayPermission: 'jvw.integration.outbox.replay',
                    maintenancePermission: 'jvw.integration.outbox.maintain',
                  }
                } else {
                  draft.reliability.outbox = undefined
                }
              })}
              label="Transactional outbox"
              description={publisherKinds.has(model.kind) ? 'Persist, lease, confirm, retry, replay, and reconcile broker events.' : 'Available only for broker publishers.'}
              disabled={!publisherKinds.has(model.kind) || Boolean(model.sourceLocator)}
            />
            {model.reliability.outboxEnabled && model.reliability.outbox && (
              <div className="space-y-2 rounded-md border border-jmix-500/25 bg-jmix-500/5 p-2.5">
                <p className="text-[9px] leading-4 text-jmix-200">
                  Durable at-least-once delivery. Every broker message carries a stable <code>jvw-outbox-id</code> for downstream deduplication.
                </p>
                <label>
                  <span className={labelClass}>Data store</span>
                  <select
                    value={model.reliability.outbox.storeId}
                    disabled={Boolean(model.sourceLocator)}
                    onChange={(event) => commit((draft) => {
                      if (draft.reliability.outbox) draft.reliability.outbox.storeId = event.target.value
                    })}
                    className={fieldClass}
                  >
                    <option value="">Select migratable store…</option>
                    {workspace.dataStores
                      .filter((store) => store.moduleId === destination?.moduleId && store.rootChangelogPath && store.generatedDirectory)
                      .map((store) => <option key={store.id} value={store.id}>{store.name} · {store.includeMode}</option>)}
                  </select>
                </label>
                <label><span className={labelClass}>Table</span><input disabled={Boolean(model.sourceLocator)} value={model.reliability.outbox.tableName} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.tableName = event.target.value })} className={fieldClass} /></label>
                <div className="grid grid-cols-2 gap-2">
                  <label><span className={labelClass}>Batch</span><input type="number" min={1} max={10000} value={model.reliability.outbox.batchSize} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.batchSize = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Poll ms</span><input type="number" min={100} value={model.reliability.outbox.pollDelayMs} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.pollDelayMs = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Lease ms</span><input type="number" min={1000} value={model.reliability.outbox.leaseDurationMs} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.leaseDurationMs = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Attempts</span><input type="number" min={1} max={30} value={model.reliability.outbox.maxAttempts} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.maxAttempts = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Initial backoff</span><input type="number" min={100} value={model.reliability.outbox.initialBackoffMs} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.initialBackoffMs = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Max backoff</span><input type="number" min={100} value={model.reliability.outbox.maximumBackoffMs} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.maximumBackoffMs = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Retention days</span><input type="number" min={1} max={3650} value={model.reliability.outbox.retentionDays} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.retentionDays = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Replay permission</span><input value={model.reliability.outbox.replayPermission} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.replayPermission = event.target.value })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Maintenance permission</span><input value={model.reliability.outbox.maintenancePermission} onChange={(event) => commit((draft) => { if (draft.reliability.outbox) draft.reliability.outbox.maintenancePermission = event.target.value })} className={fieldClass} /></label>
                </div>
              </div>
            )}
            {consumerKinds.has(model.kind) && (
              <>
                <Toggle
                  checked={model.reliability.inboxEnabled}
                  onChange={(checked) => commit((draft) => {
                    draft.reliability.inboxEnabled = checked
                    if (checked) {
                      const store = workspace.dataStores.find((candidate) => (
                        candidate.moduleId === destination?.moduleId &&
                        candidate.rootChangelogPath &&
                        candidate.generatedDirectory
                      ))
                      draft.reliability.transactional = true
                      draft.reliability.deliveryGuarantee = 'AT_LEAST_ONCE'
                      draft.reliability.idempotency = {
                        enabled: true,
                        headerName: 'jvw-outbox-id',
                        keyParameterName: 'messageId',
                      }
                      draft.reliability.inbox = {
                        storeId: store?.id ?? '',
                        tableName: inboxTableName(draft.beanName),
                        jsonApi: destination?.jsonApi,
                        messageIdHeader: 'jvw-outbox-id',
                        maximumPayloadBytes: 1048576,
                        maintenanceBatchSize: 1000,
                        retentionDays: 90,
                        replayPermission: 'jvw.integration.inbox.replay',
                        maintenancePermission: 'jvw.integration.inbox.maintain',
                      }
                    } else {
                      draft.reliability.inbox = undefined
                    }
                  })}
                  label="Persistent idempotent inbox"
                  description="Deduplicate stable event IDs, transact handler changes, retain terminal payloads, and authorize replay."
                  disabled={Boolean(model.sourceLocator)}
                />
                {model.reliability.inboxEnabled && model.reliability.inbox && (
                  <div className="space-y-2 rounded-md border border-sky-500/25 bg-sky-500/5 p-2.5">
                    <p className="text-[9px] leading-4 text-sky-200">
                      The message ID and handler changes commit together. Successful duplicates are acknowledged without invoking the handler again.
                    </p>
                    <label>
                      <span className={labelClass}>Data store</span>
                      <select
                        value={model.reliability.inbox.storeId}
                        disabled={Boolean(model.sourceLocator)}
                        onChange={(event) => commit((draft) => {
                          if (draft.reliability.inbox) draft.reliability.inbox.storeId = event.target.value
                        })}
                        className={fieldClass}
                      >
                        <option value="">Select migratable store…</option>
                        {workspace.dataStores
                          .filter((store) => store.moduleId === destination?.moduleId && store.rootChangelogPath && store.generatedDirectory)
                          .map((store) => <option key={store.id} value={store.id}>{store.name} · {store.includeMode}</option>)}
                      </select>
                    </label>
                    <label><span className={labelClass}>Inbox table</span><input disabled={Boolean(model.sourceLocator)} value={model.reliability.inbox.tableName} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.tableName = event.target.value })} className={fieldClass} /></label>
                    <div className="grid grid-cols-2 gap-2">
                      <label><span className={labelClass}>Message ID header</span><input value={model.reliability.inbox.messageIdHeader} onChange={(event) => commit((draft) => {
                        if (draft.reliability.inbox) draft.reliability.inbox.messageIdHeader = event.target.value
                        draft.reliability.idempotency.headerName = event.target.value
                      })} className={fieldClass} /></label>
                      <label><span className={labelClass}>Maximum payload bytes</span><input type="number" min={1024} max={10485760} value={model.reliability.inbox.maximumPayloadBytes} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.maximumPayloadBytes = Number(event.target.value) })} className={fieldClass} /></label>
                      <label><span className={labelClass}>Maintenance batch</span><input type="number" min={1} max={10000} value={model.reliability.inbox.maintenanceBatchSize} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.maintenanceBatchSize = Number(event.target.value) })} className={fieldClass} /></label>
                      <label><span className={labelClass}>Retention days</span><input type="number" min={1} max={3650} value={model.reliability.inbox.retentionDays} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.retentionDays = Number(event.target.value) })} className={fieldClass} /></label>
                      <label><span className={labelClass}>Replay permission</span><input value={model.reliability.inbox.replayPermission} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.replayPermission = event.target.value })} className={fieldClass} /></label>
                      <label className="col-span-2"><span className={labelClass}>Maintenance permission</span><input value={model.reliability.inbox.maintenancePermission} onChange={(event) => commit((draft) => { if (draft.reliability.inbox) draft.reliability.inbox.maintenancePermission = event.target.value })} className={fieldClass} /></label>
                    </div>
                  </div>
                )}
              </>
            )}
          </Section>

          <Section title="Retry and dead letter" icon={History}>
            <label><span className={labelClass}>Retry mode</span><select value={model.reliability.retry.mode} onChange={(event) => commit((draft) => {
              const mode = event.target.value as IntegrationRetryMode
              draft.reliability.retry.mode = mode
              draft.reliability.retry.attempts = mode === 'NONE' ? 1 : Math.max(3, draft.reliability.retry.attempts)
              if (mode !== 'NONE' && consumerKinds.has(draft.kind) && !draft.reliability.retry.deadLetterDestinationProperty) {
                draft.reliability.retry.deadLetterDestinationProperty = `${draft.configurationPrefix}.dead-letter-destination`
              }
            })} className={fieldClass}>
              {(['NONE', 'BLOCKING', 'NON_BLOCKING'] as IntegrationRetryMode[]).map((value) => (
                <option key={value} value={value} disabled={model.kind === 'RABBIT_CONSUMER' && value === 'NON_BLOCKING'}>
                  {value.replace(/_/g, ' ')}
                </option>
              ))}
            </select></label>
            {model.reliability.retry.mode !== 'NONE' && (
              <>
                <div className="grid grid-cols-2 gap-2">
                  <label><span className={labelClass}>Attempts</span><input type="number" min={2} max={20} value={model.reliability.retry.attempts} onChange={(event) => commit((draft) => { draft.reliability.retry.attempts = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Backoff</span><select value={model.reliability.retry.backoff} onChange={(event) => commit((draft) => { draft.reliability.retry.backoff = event.target.value as IntegrationBackoffMode })} className={fieldClass}><option>FIXED</option><option>EXPONENTIAL</option></select></label>
                  <label><span className={labelClass}>Initial ms</span><input type="number" value={model.reliability.retry.initialDelayMs} onChange={(event) => commit((draft) => { draft.reliability.retry.initialDelayMs = Number(event.target.value) })} className={fieldClass} /></label>
                  <label><span className={labelClass}>Maximum ms</span><input type="number" value={model.reliability.retry.maximumDelayMs} onChange={(event) => commit((draft) => { draft.reliability.retry.maximumDelayMs = Number(event.target.value) })} className={fieldClass} /></label>
                </div>
                {consumerKinds.has(model.kind) && <label><span className={labelClass}>Dead-letter property</span><input value={model.reliability.retry.deadLetterDestinationProperty ?? ''} onChange={(event) => commit((draft) => { draft.reliability.retry.deadLetterDestinationProperty = event.target.value || undefined })} className={fieldClass} /></label>}
              </>
            )}
          </Section>

          <Section title="Failure isolation" icon={CircuitBoard}>
            <Toggle checked={model.reliability.circuitBreaker.enabled} onChange={(checked) => commit((draft) => { draft.reliability.circuitBreaker.enabled = checked })} label="Circuit breaker" description="Open after a bounded failure threshold." />
            {model.reliability.circuitBreaker.enabled && <div className="grid grid-cols-2 gap-2">
              <label><span className={labelClass}>Window</span><input type="number" value={model.reliability.circuitBreaker.slidingWindowSize} onChange={(event) => commit((draft) => { draft.reliability.circuitBreaker.slidingWindowSize = Number(event.target.value) })} className={fieldClass} /></label>
              <label><span className={labelClass}>Failure %</span><input type="number" value={model.reliability.circuitBreaker.failureRateThreshold} onChange={(event) => commit((draft) => { draft.reliability.circuitBreaker.failureRateThreshold = Number(event.target.value) })} className={fieldClass} /></label>
            </div>}
            <Toggle checked={model.reliability.bulkhead.enabled} onChange={(checked) => commit((draft) => { draft.reliability.bulkhead.enabled = checked })} label="Bulkhead" description="Bound concurrent external calls." />
            <Toggle checked={model.reliability.rateLimit.enabled} onChange={(checked) => commit((draft) => { draft.reliability.rateLimit.enabled = checked })} label="Rate limit" description="Protect provider quotas and internal capacity." />
            <Toggle
              checked={model.reliability.idempotency.enabled}
              onChange={(checked) => commit((draft) => { draft.reliability.idempotency.enabled = checked })}
              label="Idempotency key"
              description={consumerKinds.has(model.kind) ? 'Required by the persistent inbox and locked to the configured message ID header.' : 'Required before retrying non-idempotent HTTP operations.'}
              disabled={consumerKinds.has(model.kind) && model.reliability.inboxEnabled}
            />
          </Section>

          <Section title="Observability" icon={Gauge}>
            <div className="rounded-md border border-surface-border bg-surface/45 p-2 text-[9px] leading-4 text-gray-400">
              Runtime: <span className="font-semibold text-gray-200">
                {model.observability.runtimeApi === 'MICROMETER_OBSERVATION' ? 'Micrometer metrics + Observation tracing' : 'Spring application events'}
              </span>
              {model.reliability.outboxEnabled && ' · replay and purge always emit payload-free audit events'}
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Toggle checked={model.observability.metricsEnabled} onChange={(checked) => commit((draft) => { draft.observability.metricsEnabled = checked })} label="Metrics" />
              <Toggle checked={model.observability.tracingEnabled} onChange={(checked) => commit((draft) => { draft.observability.tracingEnabled = checked })} label="Tracing" />
              <Toggle checked={model.observability.structuredLoggingEnabled} onChange={(checked) => commit((draft) => { draft.observability.structuredLoggingEnabled = checked })} label="Structured logs" />
              <Toggle checked={model.observability.auditEnabled} onChange={(checked) => commit((draft) => { draft.observability.auditEnabled = checked })} label="Audit events" />
            </div>
          </Section>

          <section className="p-3">
            <h3 className="mb-2 text-[10px] font-semibold uppercase tracking-wider text-gray-500">Compatibility gate</h3>
            <div className="mb-2 flex flex-wrap gap-1">
              {requiredCapabilities(model).map((capability) => {
                const present = destination?.capabilities.includes(capability)
                return <span key={capability} className={`rounded px-1.5 py-1 text-[8px] ${present ? 'bg-emerald-500/10 text-emerald-300' : 'bg-red-500/10 text-red-300'}`}>{capability}</span>
              })}
            </div>
            {blockers.length ? (
              <div className="space-y-1.5">
                {blockers.map((blocker) => <div key={blocker} className="flex items-start gap-1.5 rounded border border-amber-500/20 bg-amber-500/5 p-2 text-[10px] leading-4 text-amber-200"><AlertTriangle size={12} className="mt-0.5 shrink-0" /> {blocker}</div>)}
              </div>
            ) : (
              <div className="flex items-start gap-1.5 rounded border border-emerald-500/20 bg-emerald-500/5 p-2 text-[10px] text-emerald-200"><CheckCircle2 size={12} className="mt-0.5 shrink-0" /> Local validation passed. Source preview performs the authoritative backend checks.</div>
            )}
          </section>
        </aside>
      </div>

      {preview && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-3 sm:p-6" role="dialog" aria-modal="true" aria-label="Connector source preview">
          <div className="flex max-h-full w-full max-w-6xl min-w-0 flex-col overflow-hidden rounded-xl border border-surface-border bg-surface shadow-2xl">
            <header className="flex min-w-0 items-center gap-2 border-b border-surface-border bg-surface-light p-3">
              <Code2 size={16} className="shrink-0 text-jmix-400" />
              <div className="min-w-0 flex-1"><h3 className="truncate text-sm font-semibold text-gray-100">{preview.label}</h3><p className="text-[10px] text-gray-500">{preview.files.length} files · immutable digest-bound plan</p></div>
              <button aria-label="Close preview" onClick={() => setPreview(null)} className="rounded p-1 text-gray-400 hover:bg-surface-lighter hover:text-white"><X size={17} /></button>
            </header>
            {preview.issues.length > 0 && <div className="border-b border-amber-500/25 bg-amber-500/10 p-3 text-xs text-amber-200">{preview.issues.map((issue) => <p key={issue.code}>{issue.code}: {issue.message}</p>)}</div>}
            <div className="flex min-h-0 min-w-0 flex-1 flex-col md:flex-row">
              <nav className="shrink-0 border-b border-surface-border bg-surface-light/50 p-2 md:w-72 md:border-b-0 md:border-r">
                {preview.files.map((file, index) => (
                  <button key={file.relativePath} onClick={() => setActivePreviewFile(index)} className={`mb-1 w-full rounded p-2 text-left ${activePreviewFile === index ? 'bg-jmix-500/15 text-jmix-200' : 'text-gray-400 hover:bg-surface-lighter'}`}>
                    <span className="block truncate text-[11px]">{file.relativePath.split('/').pop()}</span>
                    <span className="block truncate text-[9px] text-gray-600">{file.mode} · {file.relativePath}</span>
                  </button>
                ))}
              </nav>
              <pre className="min-h-[260px] min-w-0 flex-1 overflow-auto whitespace-pre p-4 text-[11px] leading-5 text-gray-300">{preview.files[activePreviewFile]?.resultContent}</pre>
            </div>
            <footer className="flex flex-wrap items-center justify-end gap-2 border-t border-surface-border bg-surface-light p-3">
              <button className="btn-secondary" onClick={() => setPreview(null)}>Cancel</button>
              <button className="btn-primary flex items-center gap-1.5" disabled={!preview.accepted || applying || !preview.planDigest} onClick={() => void applyChanges()}>
                {applying ? <Loader2 size={13} className="animate-spin" /> : <Save size={13} />} Apply atomically
              </button>
            </footer>
          </div>
        </div>
      )}
    </div>
  )
}
