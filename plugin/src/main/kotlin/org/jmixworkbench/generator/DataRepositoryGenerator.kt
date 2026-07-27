package org.jmixworkbench.generator

import org.jmixworkbench.model.*

/**
 * Generates Spring Data repository interfaces for Jmix entities.
 * Handles: standard CRUD, custom JPQL/native queries, derived query methods,
 * pagination, projections.
 */
object DataRepositoryGenerator {

    fun generate(entity: EntityModel): String {
        val repoConfig = entity.dataRepository ?: DataRepositoryConfig(enabled = true)
        val repoName = repoConfig.interfaceName ?: "${entity.className}Repository"

        val b = JavaClassBuilder(repoName)
        b.package_(entity.packageName)
        b.asInterface()

        b.import_(
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.jpa.repository.Query",
            "org.springframework.data.repository.query.Param",
            "org.springframework.stereotype.Repository",
            entity.fullName
        )

        val idType = when (entity.id.type) {
            IdType.UUID -> "UUID"
            IdType.LONG -> "Long"
            IdType.INTEGER -> "Integer"
            IdType.STRING -> "String"
            IdType.EMBEDDED -> "Object"
        }
        if (entity.id.type == IdType.UUID) {
            b.import_("java.util.UUID")
        }

        b.extends_("JpaRepository<${entity.className}, $idType>")

        b.annotation {
            name = "Repository"
            importPath = "org.springframework.stereotype.Repository"
        }

        // Custom methods
        repoConfig.methods.forEach { method ->
            b.method {
                name = method.name
                returnType = method.returnType
                isAbstract = true

                if (method.query != null) {
                    when (method.queryType) {
                        QueryType.JPQL -> annotation {
                            name = "Query"
                            importPath = "org.springframework.data.jpa.repository.Query"
                            value("\"${method.query}\"")
                        }
                        QueryType.NATIVE -> annotation {
                            name = "Query"
                            importPath = "org.springframework.data.jpa.repository.Query"
                            value("\"${method.query}\"")
                            param("nativeQuery", "true")
                        }
                        QueryType.DERIVED -> {}
                    }
                }

                method.parameters.forEachIndexed { i, p ->
                    param(p.type, p.name)
                    if (method.query != null) {
                        paramAnnotation(i) {
                            name = "Param"
                            importPath = "org.springframework.data.repository.query.Param"
                            value("\"${p.name}\"")
                        }
                    }
                }
            }
        }

        return b.build()
    }
}
