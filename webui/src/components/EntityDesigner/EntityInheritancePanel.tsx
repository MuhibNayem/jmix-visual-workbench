import type {
  EntityModel,
  InheritanceRole,
  InheritanceStrategy,
  SchemaEntitySnapshot,
} from '../../types'

interface EntityInheritancePanelProps {
  entity: EntityModel
  entities: SchemaEntitySnapshot[]
  existingSource: boolean
  onChange: (inheritance: EntityModel['inheritance'], extendsClass?: string) => void
}

const strategies: Array<{ value: InheritanceStrategy; label: string; detail: string }> = [
  { value: 'singleTable', label: 'Single table', detail: 'One hierarchy table and a discriminator.' },
  { value: 'joined', label: 'Joined', detail: 'One table per class joined through the identifier.' },
  { value: 'tablePerClass', label: 'Table per class', detail: 'Concrete tables repeat inherited mappings.' },
]

export default function EntityInheritancePanel({
  entity,
  entities,
  existingSource,
  onChange,
}: EntityInheritancePanelProps) {
  const inheritance = entity.inheritance
  const role: InheritanceRole = inheritance?.role ?? (entity.extendsClass ? 'subtype' : 'root')
  const strategy = inheritance?.strategy ?? 'singleTable'
  const parent = entities.find(candidate =>
    candidate.qualifiedName === entity.extendsClass ||
    candidate.className === entity.extendsClass,
  )
  const rootCandidates = entities.filter(candidate =>
    candidate.entityType === 'entity' &&
    candidate.qualifiedName !== `${entity.packageName}.${entity.className}`,
  )

  const update = (patch: Partial<NonNullable<EntityModel['inheritance']>>) => {
    if (!inheritance) return
    const next = { ...inheritance, ...patch }
    if (next.strategy === 'tablePerClass') {
      next.discriminatorColumn = undefined
      next.discriminatorLength = undefined
      next.discriminatorValue = undefined
    }
    if (next.role === 'root') {
      next.primaryKeyJoinColumnName = undefined
      next.primaryKeyJoinReferencedColumnName = undefined
      next.parentTableName = undefined
      next.parentIdColumnName = undefined
    } else {
      next.discriminatorColumn = undefined
      next.discriminatorLength = undefined
    }
    onChange(next, next.role === 'root' ? undefined : entity.extendsClass)
  }

  const selectParent = (qualifiedName: string) => {
    const selected = entities.find(candidate => candidate.qualifiedName === qualifiedName)
    onChange(
      {
        ...(inheritance ?? {
          role: 'subtype',
          strategy: selected?.inheritance?.strategy ?? 'singleTable',
          discriminatorType: selected?.inheritance?.discriminatorType ?? 'STRING',
        }),
        role: 'subtype',
        strategy: selected?.inheritance?.strategy ?? strategy,
        discriminatorColumn: undefined,
        discriminatorLength: undefined,
        parentTableName: selected?.inheritance?.strategy === 'singleTable'
          ? selected.inheritance.parentTableName ?? selected.tableName
          : selected?.tableName,
        parentIdColumnName: selected?.idColumnName,
      },
      qualifiedName || undefined,
    )
  }

  return (
    <section className="min-w-0 rounded-xl border border-sky-500/20 bg-sky-500/[0.035] p-3">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-[10px] font-semibold uppercase tracking-[0.16em] text-sky-200">
            Entity hierarchy
          </h3>
          <p className="mt-1 max-w-2xl text-[9px] leading-relaxed text-gray-500">
            Root-only and subtype-only Jakarta annotations are generated separately. The designer never places
            <code className="mx-1 text-sky-200">@Inheritance</code>
            on a subtype.
          </p>
        </div>
        <label className="flex shrink-0 cursor-pointer items-center gap-2 rounded-lg border border-surface-border px-2.5 py-1.5 text-[10px] text-gray-300">
          <input
            type="checkbox"
            checked={Boolean(inheritance)}
            disabled={existingSource}
            onChange={event => {
              onChange(
                event.target.checked
                  ? {
                      role: entity.extendsClass ? 'subtype' : 'root',
                      strategy: 'singleTable',
                      discriminatorType: 'STRING',
                    }
                  : undefined,
                entity.extendsClass,
              )
            }}
          />
          Inheritance mapping
        </label>
      </div>

      {existingSource && (
        <div className="mt-3 rounded-lg border border-amber-500/20 bg-amber-500/[0.06] px-3 py-2 text-[9px] leading-relaxed text-amber-100/75">
          Existing hierarchy metadata is source-derived and revision-bound. Shape-changing edits stay locked until
          the schema migration planner can prove the complete parent/child table inventory.
        </div>
      )}

      {inheritance && (
        <div className="mt-3 space-y-3">
          <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
            {(['root', 'subtype'] as InheritanceRole[]).map(candidate => (
              <button
                key={candidate}
                type="button"
                disabled={existingSource}
                aria-pressed={role === candidate}
                onClick={() => update({ role: candidate })}
                className={`min-w-0 rounded-lg border px-3 py-2 text-left transition-colors ${
                  role === candidate
                    ? 'border-sky-400/45 bg-sky-400/10 text-sky-100'
                    : 'border-surface-border bg-black/10 text-gray-500 hover:text-gray-300'
                }`}
              >
                <span className="block text-[10px] font-semibold">
                  {candidate === 'root' ? 'Hierarchy root' : 'Concrete subtype'}
                </span>
                <span className="mt-0.5 block text-[8px] leading-relaxed opacity-70">
                  {candidate === 'root'
                    ? 'Owns strategy and discriminator-column configuration.'
                    : 'Inherits strategy; owns discriminator value and optional joined key mapping.'}
                </span>
              </button>
            ))}
          </div>

          {role === 'subtype' && (
            <label className="block min-w-0">
              <span className="mb-1 block text-[9px] text-gray-500">Parent entity</span>
              <input
                list="jmix-inheritance-parents"
                value={entity.extendsClass ?? ''}
                disabled={existingSource}
                onChange={event => selectParent(event.target.value)}
                placeholder="com.example.entity.BasePayment"
                className="w-full min-w-0 font-mono"
              />
              <datalist id="jmix-inheritance-parents">
                {rootCandidates.map(candidate => (
                  <option key={candidate.artifactId} value={candidate.qualifiedName}>
                    {candidate.tableName || candidate.entityType}
                  </option>
                ))}
              </datalist>
              {parent && (
                <span className="mt-1 block break-words text-[8px] text-emerald-200/65">
                  Indexed parent: {parent.tableName || parent.entityType} · {parent.idColumnName}
                </span>
              )}
            </label>
          )}

          <div className="grid min-w-0 grid-cols-1 gap-2 lg:grid-cols-3">
            {strategies.map(candidate => (
              <button
                key={candidate.value}
                type="button"
                disabled={existingSource || (role === 'subtype' && Boolean(parent?.inheritance))}
                aria-pressed={strategy === candidate.value}
                onClick={() => update({ strategy: candidate.value })}
                className={`min-w-0 rounded-lg border p-2.5 text-left ${
                  strategy === candidate.value
                    ? 'border-sky-400/40 bg-sky-400/[0.08] text-sky-100'
                    : 'border-surface-border bg-black/10 text-gray-500'
                }`}
              >
                <span className="block text-[9px] font-medium">{candidate.label}</span>
                <span className="mt-1 block text-[8px] leading-relaxed opacity-70">{candidate.detail}</span>
              </button>
            ))}
          </div>

          {strategy !== 'tablePerClass' && (
            <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {role === 'root' && (
                <TextField
                  label="Discriminator column"
                  value={inheritance.discriminatorColumn ?? ''}
                  placeholder="DTYPE"
                  disabled={existingSource}
                  onChange={value => update({ discriminatorColumn: value || undefined })}
                />
              )}
              <label className="block min-w-0">
                <span className="mb-1 block text-[9px] text-gray-500">Discriminator type</span>
                <select
                  value={inheritance.discriminatorType}
                  disabled={existingSource || role === 'subtype'}
                  onChange={event => update({ discriminatorType: event.target.value })}
                  className="w-full min-w-0"
                >
                  <option value="STRING">String</option>
                  <option value="CHAR">Character</option>
                  <option value="INTEGER">Integer</option>
                </select>
              </label>
              {role === 'root' && inheritance.discriminatorType === 'STRING' && (
                <TextField
                  label="Discriminator length"
                  value={inheritance.discriminatorLength?.toString() ?? ''}
                  placeholder="31"
                  disabled={existingSource}
                  type="number"
                  onChange={value => update({
                    discriminatorLength: value ? Number.parseInt(value, 10) : undefined,
                  })}
                />
              )}
              <TextField
                label="Value for this entity"
                value={inheritance.discriminatorValue ?? ''}
                placeholder={entity.className}
                disabled={existingSource}
                onChange={value => update({ discriminatorValue: value || undefined })}
              />
            </div>
          )}

          {role === 'subtype' && strategy === 'joined' && (
            <div className="grid min-w-0 grid-cols-1 gap-3 sm:grid-cols-2">
              <TextField
                label="Primary-key join column"
                value={inheritance.primaryKeyJoinColumnName ?? ''}
                placeholder={`${entity.className.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toUpperCase()}_ID`}
                disabled={existingSource}
                onChange={value => update({ primaryKeyJoinColumnName: value || undefined })}
              />
              <TextField
                label="Parent identifier column"
                value={inheritance.primaryKeyJoinReferencedColumnName ?? inheritance.parentIdColumnName ?? ''}
                placeholder="ID"
                disabled={existingSource}
                onChange={value => update({
                  primaryKeyJoinReferencedColumnName: value || undefined,
                })}
              />
            </div>
          )}
        </div>
      )}
    </section>
  )
}

function TextField({
  label,
  value,
  placeholder,
  disabled,
  type = 'text',
  onChange,
}: {
  label: string
  value: string
  placeholder?: string
  disabled: boolean
  type?: 'text' | 'number'
  onChange: (value: string) => void
}) {
  return (
    <label className="block min-w-0">
      <span className="mb-1 block text-[9px] text-gray-500">{label}</span>
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        onChange={event => onChange(event.target.value)}
        className="w-full min-w-0 font-mono"
      />
    </label>
  )
}
