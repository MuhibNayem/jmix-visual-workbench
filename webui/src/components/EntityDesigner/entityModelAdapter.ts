import type {
  AttributeType,
  EntityModel,
  SchemaEntitySnapshot,
} from '../../types'

/**
 * Rehydrates the editable visual model from trusted, indexed source evidence.
 *
 * Keep this adapter shared by the entity editor and cross-surface launch routing:
 * the native editor and the tool-window CRUD flow must describe the same entity.
 */
export function existingEntityModel(
  snapshot: SchemaEntitySnapshot,
  storeId?: string,
): Partial<EntityModel> {
  const packageName = snapshot.qualifiedName.split('.').slice(0, -1).join('.')
  return {
    className: snapshot.className,
    packageName,
    sourceLanguage: snapshot.sourceLocator.relativePath.endsWith('.kt') ? 'kotlin' : 'java',
    dataStore: snapshot.storeName,
    generationTarget: {
      moduleId: snapshot.moduleId,
      storeId,
    },
    entityName: snapshot.entityName,
    tableName: snapshot.tableName,
    tableSchema: snapshot.tableSchema,
    tableCatalog: snapshot.tableCatalog,
    entityType: snapshot.entityType,
    id: {
      type: snapshot.idType,
      generation: 'jmixGenerated',
      columnName: snapshot.idColumnName,
    },
    traits: [...snapshot.traits],
    attributes: snapshot.attributes.map((attribute) => {
      const discovered = attribute.associationDetails
      const attributeType = discovered?.composition
        ? 'composition' as const
        : schemaAttributeType(attribute.javaType, attribute.association)
      return {
        name: attribute.name,
        type: attributeType,
        columnName: attribute.columnName,
        mandatory: !attribute.nullable,
        unique: attribute.unique,
        length: attribute.length,
        precision: attribute.precision,
        scale: attribute.scale,
        transientFlag: !attribute.persistent,
        comment: attribute.comment,
        systemLevel: attribute.systemLevel ?? false,
        readOnly: attribute.readOnly ?? false,
        jmixProperty: attribute.jmixProperty ?? false,
        dependsOnProperties: attribute.dependsOnProperties ?? [],
        propertyDatatype: attribute.propertyDatatype,
        lob: attribute.lob ?? false,
        sqlType: attribute.sqlType,
        ...(attributeType === 'enum' ? {
          enumClass: attribute.javaType.replace(/\?$/, ''),
        } : {}),
        enumIdType: 'string' as const,
        validations: attribute.validations ?? [],
        annotations: [],
        inBaseFetchPlan: true,
        ...(attribute.association ? {
          association: {
            associationType: discovered?.associationType ?? 'manyToOne' as const,
            relatedEntity: discovered?.relatedEntity ?? attribute.javaType,
            relatedTableName: discovered?.relatedTableName,
            relatedIdColumnName: discovered?.relatedIdColumnName ?? 'ID',
            relatedIdType: discovered?.relatedIdType ?? 'uuid' as const,
            localIdAttributeName: discovered?.localIdAttributeName,
            mappedBy: discovered?.mappedBy,
            joinColumnName: discovered?.joinColumnName,
            joinTable: discovered?.joinTable,
            cascade: discovered?.cascade ?? [],
            fetch: discovered?.fetch ?? 'lazy' as const,
            collectionType: discovered?.collectionType ?? 'list' as const,
            crossDataStore: discovered?.crossDataStore ?? false,
            orphanRemoval: discovered?.orphanRemoval ?? false,
            onDelete: discovered?.onDelete,
          },
        } : {}),
      }
    }),
    indexes: [],
    uniqueConstraints: [],
    databaseView: snapshot.databaseView,
    ddlGeneration: {
      enabled: snapshot.entityType === 'entity' && snapshot.ddlMode !== 'DISABLED',
      mode: snapshot.ddlMode === 'CREATE_ONLY'
        ? 'createOnly'
        : snapshot.ddlMode === 'DISABLED'
          ? 'disabled'
          : 'createAndDrop',
      unmappedColumns: [],
      unmappedConstraints: [],
    },
    lifecycleCallbacks: [...snapshot.lifecycleCallbacks],
    entityListeners: [...snapshot.entityListeners],
    extendsClass: snapshot.extendsClass,
    implementsInterfaces: [...snapshot.implementsInterfaces],
    annotations: [],
    systemLevel: false,
    annotatedPropertiesOnly: false,
  }
}

function schemaAttributeType(javaType: string, association: boolean): AttributeType {
  if (association) return 'association'
  const simple = javaType.replace(/\??$/, '').split('.').pop()?.replace(/<.*>/, '') ?? javaType
  const mapping: Record<string, AttributeType> = {
    String: 'string',
    Character: 'character',
    char: 'character',
    Integer: 'integer',
    int: 'integer',
    Long: 'long',
    long: 'long',
    Double: 'double',
    double: 'double',
    BigDecimal: 'bigDecimal',
    Boolean: 'boolean',
    boolean: 'boolean',
    Date: 'date',
    LocalDate: 'localDate',
    LocalDateTime: 'localDateTime',
    LocalTime: 'localTime',
    OffsetTime: 'offsetTime',
    OffsetDateTime: 'offsetDateTime',
    URI: 'uri',
    FileRef: 'fileRef',
    UUID: 'uuid',
    'byte[]': 'byteArray',
  }
  return mapping[simple] ?? 'enum'
}
