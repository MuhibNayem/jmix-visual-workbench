import { useState } from 'react'
import { useStore } from '../../store'
import { bridge } from '../../bridge'
import type { AttributeType, TraitType, IdType, IdGeneration, AssociationType, FetchType, ValidationType } from '../../types'

const ATTRIBUTE_TYPES: AttributeType[] = [
  'string', 'integer', 'long', 'double', 'bigDecimal', 'boolean',
  'date', 'localDate', 'localDateTime', 'localTime', 'offsetDateTime',
  'uuid', 'byteArray', 'enum', 'association', 'composition', 'embedded',
]

const TRAITS: { value: TraitType; label: string }[] = [
  { value: 'standardEntity', label: 'Standard Entity (UUID + Version + Audit)' },
  { value: 'uuid', label: 'UUID' },
  { value: 'softDelete', label: 'Soft Delete' },
  { value: 'hasTenantId', label: 'Multitenancy' },
  { value: 'hasVersion', label: 'Version (Optimistic Lock)' },
  { value: 'auditable', label: 'Auditable (CreatedBy/Date, UpdatedBy/Date)' },
  { value: 'createdBy', label: 'Created By' },
  { value: 'createdDate', label: 'Created Date' },
  { value: 'updatedBy', label: 'Updated By' },
  { value: 'updatedDate', label: 'Updated Date' },
]

const VALIDATIONS: ValidationType[] = [
  'notNull', 'notEmpty', 'notBlank', 'size', 'min', 'max',
  'decimalMin', 'decimalMax', 'pattern', 'email', 'past', 'future',
  'positive', 'negative', 'digits', 'assertTrue',
]

export default function EntityDesigner() {
  const { entity, setEntity, addAttribute, updateAttribute, removeAttribute, resetEntity, addToast, isGenerating, setIsGenerating } = useStore()
  const [selectedAttr, setSelectedAttr] = useState<number | null>(null)
  const [showPreview, setShowPreview] = useState(false)

  const handleGenerate = async () => {
    if (!entity.className.trim()) {
      addToast('Entity class name is required', 'error')
      return
    }
    setIsGenerating(true)
    try {
      const result = await bridge.generateEntity(entity)
      if (result.success) {
        addToast(`Entity "${entity.className}" generated: ${result.filesWritten.length} files`, 'success')
      } else {
        addToast(`Generation failed: ${result.errors.join(', ')}`, 'error')
      }
    } catch (e: any) {
      addToast(`Error: ${e.message}`, 'error')
    } finally {
      setIsGenerating(false)
    }
  }

  const toggleTrait = (trait: TraitType) => {
    const traits = entity.traits.includes(trait)
      ? entity.traits.filter(t => t !== trait)
      : [...entity.traits, trait]
    setEntity({ traits })
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-surface-border bg-surface-light">
        <h2 className="text-sm font-semibold text-gray-200">Entity Designer</h2>
        <div className="flex gap-2">
          <button
            onClick={() => setShowPreview(!showPreview)}
            className="px-3 py-1.5 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border transition-colors"
          >
            {showPreview ? 'Hide Preview' : 'Preview'}
          </button>
          <button
            onClick={resetEntity}
            className="px-3 py-1.5 text-xs rounded bg-surface-lighter text-gray-300 hover:bg-surface-border transition-colors"
          >
            Reset
          </button>
          <button
            onClick={handleGenerate}
            disabled={isGenerating}
            className="px-4 py-1.5 text-xs rounded bg-jmix-500 text-white font-medium hover:bg-jmix-600 disabled:opacity-50 transition-colors"
          >
            {isGenerating ? 'Generating...' : '⚡ Generate Entity'}
          </button>
        </div>
      </div>

      <div className="flex flex-1 overflow-hidden">
        {/* Left: Entity Config */}
        <div className="w-80 flex-shrink-0 overflow-y-auto border-r border-surface-border p-4 space-y-4">
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
            <Field label="Table Name">
              <input
                value={entity.tableName}
                onChange={e => setEntity({ tableName: e.target.value })}
                placeholder="AUTO (from class name)"
                className="w-full"
              />
            </Field>
            <Field label="Entity Type">
              <select
                value={entity.entityType}
                onChange={e => setEntity({ entityType: e.target.value as any })}
                className="w-full"
              >
                <option value="entity">Entity (JPA)</option>
                <option value="mappedSuperclass">Mapped Superclass</option>
                <option value="embeddable">Embeddable</option>
                <option value="dto">DTO</option>
                <option value="enum">Enumeration</option>
              </select>
            </Field>
            <Field label="Instance Name Pattern">
              <input
                value={entity.instanceNamePattern || ''}
                onChange={e => setEntity({ instanceNamePattern: e.target.value || undefined })}
                placeholder="name"
                className="w-full"
              />
            </Field>
            <Field label="Comment">
              <input
                value={entity.comment || ''}
                onChange={e => setEntity({ comment: e.target.value || undefined })}
                className="w-full"
              />
            </Field>
          </Section>

          {/* ID Configuration */}
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

          {/* Traits */}
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

          {/* Inheritance */}
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

          {/* Options */}
          <Section title="Options">
            <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer">
              <input
                type="checkbox"
                checked={entity.ddlGeneration.enabled}
                onChange={e => setEntity({ ddlGeneration: { enabled: e.target.checked } })}
                className="rounded border-surface-border"
              />
              DDL Generation
            </label>
            <label className="flex items-center gap-2 text-xs text-gray-300 cursor-pointer mt-1.5">
              <input
                type="checkbox"
                checked={entity.dataRepository?.enabled || false}
                onChange={e => setEntity({ dataRepository: { enabled: e.target.checked } })}
                className="rounded border-surface-border"
              />
              Generate Data Repository
            </label>
          </Section>
        </div>

        {/* Center: Attributes Table */}
        <div className="flex-1 overflow-y-auto p-4">
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider">Attributes</h3>
            <button
              onClick={addAttribute}
              className="px-3 py-1 text-xs rounded bg-jmix-500/20 text-jmix-400 hover:bg-jmix-500/30 transition-colors"
            >
              + Add Attribute
            </button>
          </div>

          {entity.attributes.length === 0 ? (
            <div className="text-center py-12 text-gray-600 text-xs">
              No attributes yet. Click "+ Add Attribute" to start.
            </div>
          ) : (
            <div className="border border-surface-border rounded-lg overflow-hidden">
              <table className="w-full text-xs">
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
                  {entity.attributes.map((attr, i) => (
                    <tr
                      key={i}
                      onClick={() => setSelectedAttr(selectedAttr === i ? null : i)}
                      className={`border-t border-surface-border cursor-pointer transition-colors ${
                        selectedAttr === i ? 'bg-jmix-500/10' : 'hover:bg-surface-lighter'
                      }`}
                    >
                      <td className="px-3 py-2">
                        <input
                          value={attr.name}
                          onChange={e => updateAttribute(i, { name: e.target.value })}
                          onClick={e => e.stopPropagation()}
                          className="w-28 bg-transparent border-none p-0 text-gray-200"
                        />
                      </td>
                      <td className="px-3 py-2">
                        <select
                          value={attr.type}
                          onChange={e => updateAttribute(i, { type: e.target.value as AttributeType })}
                          onClick={e => e.stopPropagation()}
                          className="bg-surface-lighter text-gray-300 text-xs"
                        >
                          {ATTRIBUTE_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                        </select>
                      </td>
                      <td className="px-3 py-2 text-center">
                        <input
                          type="checkbox"
                          checked={attr.mandatory}
                          onChange={e => updateAttribute(i, { mandatory: e.target.checked })}
                          onClick={e => e.stopPropagation()}
                        />
                      </td>
                      <td className="px-3 py-2 text-center">
                        <input
                          type="checkbox"
                          checked={attr.unique}
                          onChange={e => updateAttribute(i, { unique: e.target.checked })}
                          onClick={e => e.stopPropagation()}
                        />
                      </td>
                      <td className="px-3 py-2">
                        {attr.type === 'string' && (
                          <input
                            type="number"
                            value={attr.length || ''}
                            onChange={e => updateAttribute(i, { length: e.target.value ? parseInt(e.target.value) : undefined })}
                            onClick={e => e.stopPropagation()}
                            className="w-16 bg-transparent border-none p-0 text-gray-300"
                            placeholder="255"
                          />
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <button
                          onClick={e => { e.stopPropagation(); removeAttribute(i); setSelectedAttr(null) }}
                          className="text-red-400 hover:text-red-300 text-xs"
                        >
                          ✕
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {/* Selected Attribute Detail */}
          {selectedAttr !== null && entity.attributes[selectedAttr] && (
            <AttributeDetail
              attr={entity.attributes[selectedAttr]}
              onChange={(partial) => updateAttribute(selectedAttr, partial)}
            />
          )}
        </div>

        {/* Right: Preview */}
        {showPreview && (
          <div className="w-96 flex-shrink-0 border-l border-surface-border overflow-y-auto p-4">
            <h3 className="text-xs font-semibold text-gray-300 uppercase tracking-wider mb-3">Generated Code Preview</h3>
            <pre className="text-[10px] text-gray-400 bg-surface-lighter rounded-lg p-3 overflow-x-auto whitespace-pre-wrap font-mono leading-relaxed">
              {generatePreview(entity)}
            </pre>
          </div>
        )}
      </div>
    </div>
  )
}

function AttributeDetail({ attr, onChange }: { attr: any; onChange: (p: any) => void }) {
  return (
    <div className="mt-4 border border-surface-border rounded-lg p-4 bg-surface-light">
      <h4 className="text-xs font-semibold text-jmix-400 mb-3">Attribute: {attr.name || '(unnamed)'}</h4>
      <div className="grid grid-cols-2 gap-3">
        {(attr.type === 'association' || attr.type === 'composition') && (
          <>
            <Field label="Association Type">
              <select
                value={attr.association?.associationType || 'manyToOne'}
                onChange={e => onChange({
                  association: {
                    ...attr.association,
                    associationType: e.target.value as AssociationType,
                    relatedEntity: attr.association?.relatedEntity || '',
                    cascade: attr.association?.cascade || [],
                    fetch: attr.association?.fetch || 'lazy',
                    orphanRemoval: attr.association?.orphanRemoval || false,
                  }
                })}
                className="w-full"
              >
                <option value="manyToOne">Many to One</option>
                <option value="oneToMany">One to Many</option>
                <option value="manyToMany">Many to Many</option>
                <option value="oneToOne">One to One</option>
              </select>
            </Field>
            <Field label="Related Entity">
              <input
                value={attr.association?.relatedEntity || ''}
                onChange={e => onChange({
                  association: { ...attr.association, relatedEntity: e.target.value }
                })}
                placeholder="com.example.entity.Order"
                className="w-full"
              />
            </Field>
            <Field label="Fetch Type">
              <select
                value={attr.association?.fetch || 'lazy'}
                onChange={e => onChange({
                  association: { ...attr.association, fetch: e.target.value as FetchType }
                })}
                className="w-full"
              >
                <option value="lazy">Lazy</option>
                <option value="eager">Eager</option>
              </select>
            </Field>
            <Field label="Mapped By">
              <input
                value={attr.association?.mappedBy || ''}
                onChange={e => onChange({
                  association: { ...attr.association, mappedBy: e.target.value || undefined }
                })}
                className="w-full"
              />
            </Field>
          </>
        )}

        {attr.type === 'enum' && (
          <Field label="Enum Class">
            <input
              value={attr.enumClass || ''}
              onChange={e => onChange({ enumClass: e.target.value || undefined })}
              placeholder="com.example.entity.Status"
              className="w-full"
            />
          </Field>
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

        <div className="col-span-2 flex gap-4 mt-1">
          <label className="flex items-center gap-1.5 text-xs text-gray-400 cursor-pointer">
            <input type="checkbox" checked={attr.transientFlag} onChange={e => onChange({ transientFlag: e.target.checked })} />
            Transient
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

function generatePreview(entity: any): string {
  const lines: string[] = []
  lines.push(`package ${entity.packageName};`)
  lines.push('')
  lines.push('import jakarta.persistence.*;')
  lines.push('import io.jmix.core.entity.annotation.JmixEntity;')
  lines.push('')
  if (entity.entityType === 'entity') {
    lines.push(`@Entity(name = "${entity.className}")`)
    lines.push(`@Table(name = "${entity.tableName || entity.className.toUpperCase()}")`)
  }
  lines.push('@JmixEntity')
  lines.push(`public class ${entity.className} {`)
  lines.push('')
  lines.push(`    @Id`)
  lines.push(`    @Column(name = "${entity.id.columnName}", nullable = false)`)
  if (entity.id.generation === 'jmixGenerated') {
    lines.push('    @JmixGeneratedValue')
  }
  const idType = entity.id.type === 'uuid' ? 'UUID' : entity.id.type === 'long' ? 'Long' : entity.id.type === 'integer' ? 'Integer' : 'String'
  lines.push(`    protected ${idType} id;`)
  lines.push('')
  entity.attributes.forEach((attr: any) => {
    if (attr.mandatory) lines.push('    @NotNull')
    lines.push(`    @Column(name = "${(attr.columnName || attr.name.replace(/([a-z])([A-Z])/g, '$1_$2').toUpperCase())}")`)
    const type = attr.type === 'string' ? 'String' : attr.type === 'integer' ? 'Integer' : attr.type === 'long' ? 'Long' : attr.type === 'boolean' ? 'Boolean' : attr.type === 'bigDecimal' ? 'BigDecimal' : attr.type === 'localDate' ? 'LocalDate' : attr.type === 'localDateTime' ? 'LocalDateTime' : 'Object'
    lines.push(`    protected ${type} ${attr.name};`)
    lines.push('')
  })
  lines.push('    // getters and setters...')
  lines.push('}')
  return lines.join('\n')
}
