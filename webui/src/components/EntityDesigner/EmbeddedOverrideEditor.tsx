import { Plus, Sparkles, Trash2 } from 'lucide-react'
import type {
  AttributeModel,
  EmbeddedAssociationOverride,
  EmbeddedAttributeOverride,
  SchemaEntitySnapshot,
} from '../../types'

interface EmbeddedOverrideEditorProps {
  attribute: AttributeModel
  entities: SchemaEntitySnapshot[]
  existingSource: boolean
  onChange: (patch: Partial<AttributeModel>) => void
}

export default function EmbeddedOverrideEditor({
  attribute,
  entities,
  existingSource,
  onChange,
}: EmbeddedOverrideEditorProps) {
  const scalarOverrides = attribute.embeddedAttributeOverrides ?? []
  const associationOverrides = attribute.embeddedAssociationOverrides ?? []
  const embeddables = entities.filter(candidate => candidate.entityType === 'embeddable')
  const selected = embeddables.find(candidate =>
    candidate.qualifiedName === attribute.embeddedClass ||
    candidate.className === attribute.embeddedClass,
  )
  const scalarMembers = selected?.attributes.filter(candidate =>
    candidate.persistent && !candidate.association && !candidate.embedded,
  ) ?? []
  const associationMembers = selected?.attributes.filter(candidate =>
    candidate.persistent && candidate.association,
  ) ?? []
  const columns = [
    ...scalarOverrides.map(mapping => mapping.columnName.trim().toUpperCase()),
    ...associationOverrides.flatMap(mapping =>
      mapping.joinColumns.map(column => column.name.trim().toUpperCase()),
    ),
  ].filter(Boolean)
  const duplicateColumns = new Set(
    columns.filter((column, index) => columns.indexOf(column) !== index),
  )
  const duplicatePaths = new Set(
    [...scalarOverrides, ...associationOverrides]
      .map(mapping => mapping.path.trim())
      .filter((path, index, all) => path && all.indexOf(path) !== index),
  )

  const replaceScalar = (index: number, patch: Partial<EmbeddedAttributeOverride>) => {
    onChange({
      embeddedAttributeOverrides: scalarOverrides.map((mapping, candidate) =>
        candidate === index ? { ...mapping, ...patch } : mapping,
      ),
    })
  }
  const replaceAssociation = (
    index: number,
    patch: Partial<EmbeddedAssociationOverride>,
  ) => {
    onChange({
      embeddedAssociationOverrides: associationOverrides.map((mapping, candidate) =>
        candidate === index ? { ...mapping, ...patch } : mapping,
      ),
    })
  }
  const replaceJoinColumn = (
    associationIndex: number,
    joinIndex: number,
    patch: Partial<EmbeddedAssociationOverride['joinColumns'][number]>,
  ) => {
    const mapping = associationOverrides[associationIndex]
    replaceAssociation(associationIndex, {
      joinColumns: mapping.joinColumns.map((joinColumn, candidate) =>
        candidate === joinIndex ? { ...joinColumn, ...patch } : joinColumn,
      ),
    })
  }
  const addJoinColumn = (associationIndex: number) => {
    const mapping = associationOverrides[associationIndex]
    replaceAssociation(associationIndex, {
      joinColumns: [
        ...mapping.joinColumns,
        { name: '', referencedColumnName: 'ID' },
      ],
    })
  }
  const removeJoinColumn = (associationIndex: number, joinIndex: number) => {
    const mapping = associationOverrides[associationIndex]
    if (mapping.joinColumns.length <= 1) return
    replaceAssociation(associationIndex, {
      joinColumns: mapping.joinColumns.filter((_, candidate) => candidate !== joinIndex),
    })
  }
  const generateDefaults = () => {
    const prefix = attribute.name
      .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
      .replace(/[^A-Za-z0-9_]/g, '_')
      .toUpperCase()
    onChange({
      embeddedAttributeOverrides: scalarMembers.map(member => ({
        path: member.name,
        columnName: `${prefix}_${member.columnName}`,
        attributeType: schemaAttributeType(member.javaType),
        nullable: member.nullable,
        unique: member.unique || undefined,
        length: member.length,
        precision: member.precision,
        scale: member.scale,
        columnDefinition: member.sqlType,
      })),
      embeddedAssociationOverrides: associationMembers.map(member => ({
        path: member.name,
        relatedEntity: member.associationDetails?.relatedEntity,
        relatedIdType: member.associationDetails?.relatedIdType,
        joinColumns: [{
          name: `${prefix}_${member.associationDetails?.joinColumnName ?? `${member.columnName}_ID`}`,
          referencedColumnName: member.associationDetails?.relatedIdColumnName ?? 'ID',
          nullable: member.nullable,
        }],
      })),
    })
  }

  return (
    <section className="col-span-full min-w-0 space-y-3 rounded-xl border border-violet-500/20 bg-violet-500/[0.035] p-3">
      <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h4 className="text-[10px] font-semibold uppercase tracking-[0.16em] text-violet-200">
            Embedded mapping
          </h4>
          <p className="mt-1 text-[9px] leading-relaxed text-gray-500">
            Override nested scalar and association members without flattening the value object.
            Dot-separated paths follow Jakarta Persistence semantics.
          </p>
        </div>
        <button
          type="button"
          disabled={existingSource || !selected}
          onClick={generateDefaults}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-violet-400/25 bg-violet-400/[0.08] px-2.5 py-1.5 text-[9px] text-violet-100 disabled:opacity-40"
        >
          <Sparkles size={12} />
          Generate explicit mappings
        </button>
      </div>

      <label className="block min-w-0">
        <span className="mb-1 block text-[9px] text-gray-500">Embeddable class</span>
        <input
          list={`embeddables-${attribute.name}`}
          value={attribute.embeddedClass ?? ''}
          disabled={existingSource}
          onChange={event => onChange({
            embeddedClass: event.target.value || undefined,
            embeddedAttributeOverrides: [],
            embeddedAssociationOverrides: [],
          })}
          placeholder="com.example.entity.PostalAddress"
          className="w-full min-w-0 font-mono"
        />
        <datalist id={`embeddables-${attribute.name}`}>
          {embeddables.map(candidate => (
            <option key={candidate.artifactId} value={candidate.qualifiedName}>
              {candidate.attributes.length} mapped members
            </option>
          ))}
        </datalist>
      </label>

      {existingSource && (
        <div className="rounded-lg border border-amber-500/20 bg-amber-500/[0.06] px-3 py-2 text-[9px] leading-relaxed text-amber-100/75">
          These mappings were reconstructed from the current Java/Kotlin document. Existing-source editing remains
          locked unless the exact physical column inventory can be paired with a reversible migration.
        </div>
      )}

      {(duplicateColumns.size > 0 || duplicatePaths.size > 0) && (
        <div role="alert" className="rounded-lg border border-red-500/25 bg-red-500/[0.07] px-3 py-2 text-[9px] text-red-100/80">
          {duplicatePaths.size > 0 && <>Duplicate member paths: {[...duplicatePaths].join(', ')}. </>}
          {duplicateColumns.size > 0 && <>Colliding columns: {[...duplicateColumns].join(', ')}.</>}
        </div>
      )}

      <OverrideGroup
        title="Scalar member overrides"
        detail={`${scalarOverrides.length} explicit column mapping${scalarOverrides.length === 1 ? '' : 's'}`}
        onAdd={existingSource ? undefined : () => onChange({
          embeddedAttributeOverrides: [
            ...scalarOverrides,
            { path: '', columnName: '' },
          ],
        })}
      >
        {scalarOverrides.map((mapping, index) => (
          <div key={`${mapping.path}-${index}`} className="min-w-0 rounded-lg border border-surface-border bg-black/10 p-2.5">
            <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
              <OverrideInput
                label="Member path"
                value={mapping.path}
                disabled={existingSource}
                list={`embedded-scalars-${attribute.name}`}
                onChange={value => replaceScalar(index, { path: value })}
              />
              <datalist id={`embedded-scalars-${attribute.name}`}>
                {scalarMembers.map(member => <option key={member.artifactId} value={member.name} />)}
              </datalist>
              <OverrideInput
                label="Column"
                value={mapping.columnName}
                disabled={existingSource}
                onChange={value => replaceScalar(index, { columnName: value })}
              />
              <OverrideInput
                label="Length"
                type="number"
                value={mapping.length?.toString() ?? ''}
                disabled={existingSource}
                onChange={value => replaceScalar(index, {
                  length: value ? Number.parseInt(value, 10) : undefined,
                })}
              />
              <OverrideInput
                label="SQL definition"
                value={mapping.columnDefinition ?? ''}
                disabled={existingSource}
                onChange={value => replaceScalar(index, {
                  columnDefinition: value || undefined,
                })}
              />
            </div>
            <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-2">
              <TriState
                label="Nullable"
                value={mapping.nullable}
                disabled={existingSource}
                onChange={value => replaceScalar(index, { nullable: value })}
              />
              <TriState
                label="Unique"
                value={mapping.unique}
                disabled={existingSource}
                onChange={value => replaceScalar(index, { unique: value })}
              />
              {!existingSource && (
                <button
                  type="button"
                  onClick={() => onChange({
                    embeddedAttributeOverrides: scalarOverrides.filter((_, candidate) => candidate !== index),
                  })}
                  className="ml-auto inline-flex items-center gap-1 text-[9px] text-red-300/75 hover:text-red-200"
                >
                  <Trash2 size={11} />
                  Remove
                </button>
              )}
            </div>
          </div>
        ))}
      </OverrideGroup>

      <OverrideGroup
        title="Association member overrides"
        detail={`${associationOverrides.length} explicit join mapping${associationOverrides.length === 1 ? '' : 's'}`}
        onAdd={existingSource ? undefined : () => onChange({
          embeddedAssociationOverrides: [
            ...associationOverrides,
            {
              path: '',
              joinColumns: [{ name: '', referencedColumnName: 'ID' }],
            },
          ],
        })}
      >
        {associationOverrides.map((mapping, index) => {
          const joinColumns = mapping.joinColumns.length > 0
            ? mapping.joinColumns
            : [{ name: '', referencedColumnName: 'ID' }]
          return (
            <div key={`${mapping.path}-${index}`} className="min-w-0 rounded-lg border border-surface-border bg-black/10 p-2.5">
              <div className="min-w-0">
                <OverrideInput
                  label="Member path"
                  value={mapping.path}
                  disabled={existingSource}
                  list={`embedded-associations-${attribute.name}`}
                  onChange={value => replaceAssociation(index, { path: value })}
                />
                <datalist id={`embedded-associations-${attribute.name}`}>
                  {associationMembers.map(member => <option key={member.artifactId} value={member.name} />)}
                </datalist>
              </div>
              <div className="mt-2 space-y-2">
                {joinColumns.map((join, joinIndex) => (
                  <div
                    key={`${join.name}-${join.referencedColumnName}-${joinIndex}`}
                    className="min-w-0 rounded-lg border border-violet-400/15 bg-violet-400/[0.025] p-2"
                  >
                    <div className="mb-1.5 flex min-w-0 items-center justify-between gap-2">
                      <span className="text-[8px] font-semibold uppercase tracking-[0.13em] text-violet-200/65">
                        Join column {joinIndex + 1}
                      </span>
                      {!existingSource && joinColumns.length > 1 && (
                        <button
                          type="button"
                          onClick={() => removeJoinColumn(index, joinIndex)}
                          className="inline-flex items-center gap-1 text-[8px] text-red-300/70 hover:text-red-200"
                          aria-label={`Remove join column ${joinIndex + 1}`}
                        >
                          <Trash2 size={10} />
                          Remove
                        </button>
                      )}
                    </div>
                    <div className="grid min-w-0 grid-cols-1 gap-2 sm:grid-cols-2">
                      <OverrideInput
                        label="Join column"
                        value={join.name}
                        disabled={existingSource}
                        onChange={value => replaceJoinColumn(index, joinIndex, { name: value })}
                      />
                      <OverrideInput
                        label="Referenced column"
                        value={join.referencedColumnName}
                        disabled={existingSource}
                        onChange={value => replaceJoinColumn(index, joinIndex, {
                          referencedColumnName: value,
                        })}
                      />
                    </div>
                    <div className="mt-2 flex min-w-0 flex-wrap items-center gap-3">
                      <TriState
                        label="Nullable"
                        value={join.nullable}
                        disabled={existingSource}
                        onChange={value => replaceJoinColumn(index, joinIndex, { nullable: value })}
                      />
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-2 flex min-w-0 flex-wrap items-center gap-3">
                {!existingSource && (
                  <button
                    type="button"
                    onClick={() => addJoinColumn(index)}
                    className="inline-flex items-center gap-1 rounded-md border border-violet-400/20 px-2 py-1 text-[8px] text-violet-200/75 hover:border-violet-300/35 hover:text-violet-100"
                  >
                    <Plus size={10} />
                    Add join column
                  </button>
                )}
                {!existingSource && (
                  <button
                    type="button"
                    onClick={() => onChange({
                      embeddedAssociationOverrides: associationOverrides.filter(
                        (_, candidate) => candidate !== index,
                      ),
                    })}
                    className="ml-auto inline-flex items-center gap-1 text-[9px] text-red-300/75 hover:text-red-200"
                  >
                    <Trash2 size={11} />
                    Remove
                  </button>
                )}
              </div>
            </div>
          )
        })}
      </OverrideGroup>
    </section>
  )
}

function OverrideGroup({
  title,
  detail,
  onAdd,
  children,
}: {
  title: string
  detail: string
  onAdd?: () => void
  children: React.ReactNode
}) {
  return (
    <div className="min-w-0">
      <div className="mb-2 flex min-w-0 flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <div className="text-[9px] font-medium text-gray-300">{title}</div>
          <div className="text-[8px] text-gray-600">{detail}</div>
        </div>
        {onAdd && (
          <button
            type="button"
            onClick={onAdd}
            className="inline-flex items-center gap-1 rounded border border-surface-border px-2 py-1 text-[8px] text-gray-400 hover:text-gray-200"
          >
            <Plus size={10} />
            Add override
          </button>
        )}
      </div>
      <div className="space-y-2">{children}</div>
    </div>
  )
}

function OverrideInput({
  label,
  value,
  disabled,
  onChange,
  list,
  type = 'text',
}: {
  label: string
  value: string
  disabled: boolean
  onChange: (value: string) => void
  list?: string
  type?: 'text' | 'number'
}) {
  return (
    <label className="block min-w-0">
      <span className="mb-1 block text-[8px] text-gray-600">{label}</span>
      <input
        type={type}
        list={list}
        value={value}
        disabled={disabled}
        onChange={event => onChange(event.target.value)}
        className="w-full min-w-0 font-mono text-[10px]"
      />
    </label>
  )
}

function TriState({
  label,
  value,
  disabled,
  onChange,
}: {
  label: string
  value?: boolean
  disabled: boolean
  onChange: (value?: boolean) => void
}) {
  return (
    <label className="flex items-center gap-1.5 text-[8px] text-gray-500">
      {label}
      <select
        value={value == null ? 'default' : value ? 'true' : 'false'}
        disabled={disabled}
        onChange={event => onChange(
          event.target.value === 'default' ? undefined : event.target.value === 'true',
        )}
        className="h-6 min-w-0 py-0 text-[8px]"
      >
        <option value="default">Embeddable default</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
      </select>
    </label>
  )
}

function schemaAttributeType(javaType: string): EmbeddedAttributeOverride['attributeType'] {
  const simple = javaType.replace(/\?$/, '').split('.').pop()?.replace(/<.*>/, '')
  const mapping: Record<string, EmbeddedAttributeOverride['attributeType']> = {
    String: 'string',
    Character: 'character',
    char: 'character',
    Integer: 'integer',
    Int: 'integer',
    int: 'integer',
    Long: 'long',
    long: 'long',
    Double: 'double',
    double: 'double',
    BigDecimal: 'bigDecimal',
    Boolean: 'boolean',
    boolean: 'boolean',
    LocalDate: 'localDate',
    LocalDateTime: 'localDateTime',
    LocalTime: 'localTime',
    OffsetTime: 'offsetTime',
    OffsetDateTime: 'offsetDateTime',
    UUID: 'uuid',
    URI: 'uri',
    FileRef: 'fileRef',
  }
  return simple ? mapping[simple] : undefined
}
