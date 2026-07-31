import type { SchemaEntitySnapshot, TraitType } from '../../types'

const TRAIT_LABELS: Record<TraitType, string> = {
  standardEntity: 'Standard entity',
  uuid: 'UUID',
  softDelete: 'Soft delete',
  hasTenantId: 'Tenant scoped',
  hasVersion: 'Optimistic lock',
  createdBy: 'Created by',
  createdDate: 'Created date',
  updatedBy: 'Last modified by',
  updatedDate: 'Last modified date',
  auditable: 'Auditable',
}

export function EntitySourceContractEvidence({
  entity,
}: {
  entity: SchemaEntitySnapshot
}) {
  const hasContractEvidence = Boolean(
    entity.extendsClass ||
    entity.implementsInterfaces.length ||
    entity.traits.length ||
    entity.inheritedTraits.length ||
    entity.lifecycleCallbacks.length ||
    entity.entityListeners.length,
  )
  if (!hasContractEvidence) return null

  return (
    <section className="min-w-0 rounded-lg border border-sky-500/20 bg-sky-500/[0.05] p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-[10px] font-semibold uppercase tracking-wider text-sky-200">
          Source contract
        </h3>
        <span className="rounded bg-sky-500/10 px-1.5 py-0.5 text-[8px] text-sky-200/70">
          read from {entity.sourceLocator.relativePath.endsWith('.kt') ? 'Kotlin' : 'Java'}
        </span>
      </div>

      <div className="mt-2 space-y-2 text-[9px] leading-relaxed">
        {entity.extendsClass && (
          <EvidenceLine label="Extends" values={[entity.extendsClass]} />
        )}
        {entity.implementsInterfaces.length > 0 && (
          <EvidenceLine label="Implements" values={entity.implementsInterfaces} />
        )}
        {entity.traits.length > 0 && (
          <EvidenceLine
            label="Declared traits"
            values={entity.traits.map(trait => TRAIT_LABELS[trait])}
          />
        )}
        {entity.inheritedTraits.length > 0 && (
          <div className="min-w-0">
            <div className="text-gray-600">Inherited traits</div>
            <div className="mt-1 flex min-w-0 flex-wrap gap-1">
              {entity.inheritedTraits.map(evidence => (
                <span
                  key={`${evidence.declaredBy}:${evidence.trait}`}
                  title={`Inherited from ${evidence.declaredBy} at depth ${evidence.depth}`}
                  className="max-w-full truncate rounded border border-sky-500/20 bg-black/10 px-1.5 py-0.5 text-sky-100/75"
                >
                  {TRAIT_LABELS[evidence.trait]} · {evidence.declaredBy.split('.').pop()}
                </span>
              ))}
            </div>
          </div>
        )}
        {entity.lifecycleCallbacks.length > 0 && (
          <EvidenceLine
            label="Lifecycle callbacks"
            values={entity.lifecycleCallbacks.map(callback => callback.replace(/([A-Z])/g, ' $1'))}
          />
        )}
        {entity.entityListeners.length > 0 && (
          <EvidenceLine label="Entity listeners" values={entity.entityListeners} />
        )}
      </div>
    </section>
  )
}

export function InheritedAttributeEvidence({
  entity,
}: {
  entity: SchemaEntitySnapshot
}) {
  if (entity.inheritedAttributes.length === 0) return null
  return (
    <details className="mb-3 min-w-0 rounded-lg border border-sky-500/20 bg-sky-500/[0.04]">
      <summary className="flex cursor-pointer list-none flex-wrap items-center justify-between gap-2 px-3 py-2 text-[10px] text-sky-100 marker:hidden">
        <span className="font-medium">
          {entity.inheritedAttributes.length} inherited attribute
          {entity.inheritedAttributes.length === 1 ? '' : 's'}
        </span>
        <span className="text-[9px] text-gray-500">Read-only source evidence · expand</span>
      </summary>
      <div className="grid min-w-0 gap-2 border-t border-sky-500/15 p-3 sm:grid-cols-2 xl:grid-cols-3">
        {entity.inheritedAttributes.map(evidence => (
          <div
            key={`${evidence.declaredBy}:${evidence.attribute.name}`}
            className="min-w-0 rounded border border-surface-border bg-black/10 px-2.5 py-2"
          >
            <div className="flex min-w-0 items-center justify-between gap-2">
              <code className="truncate text-[10px] text-gray-200">
                {evidence.attribute.name}
              </code>
              <span className="shrink-0 rounded bg-surface-lighter px-1.5 py-0.5 text-[8px] text-gray-500">
                {evidence.attribute.javaType.split('.').pop()}
              </span>
            </div>
            <div
              className="mt-1 truncate text-[8px] text-sky-200/60"
              title={evidence.declaredBy}
            >
              depth {evidence.depth} · {evidence.declaredBy}
            </div>
          </div>
        ))}
      </div>
    </details>
  )
}

function EvidenceLine({
  label,
  values,
}: {
  label: string
  values: string[]
}) {
  return (
    <div className="min-w-0">
      <div className="text-gray-600">{label}</div>
      <div className="mt-1 flex min-w-0 flex-wrap gap-1">
        {values.map(value => (
          <span
            key={value}
            title={value}
            className="max-w-full truncate rounded border border-surface-border bg-black/10 px-1.5 py-0.5 text-gray-300"
          >
            {value}
          </span>
        ))}
      </div>
    </div>
  )
}
