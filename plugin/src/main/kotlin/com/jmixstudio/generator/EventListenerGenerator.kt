package com.jmixstudio.generator

import com.jmixstudio.model.EntityModel

/**
 * Generates Jmix entity event listener classes.
 * Handles: BeforeInsert, BeforeUpdate, BeforeDelete, AfterInsert,
 * AfterUpdate, AfterDelete, BeforeAttach, AfterAttach, BeforeDetach.
 */
object EventListenerGenerator {

    enum class ListenerEvent(val interfaceName: String, val methodName: String) {
        BEFORE_INSERT("BeforeInsertEntityListener", "onBeforeInsert"),
        BEFORE_UPDATE("BeforeUpdateEntityListener", "onBeforeUpdate"),
        BEFORE_DELETE("BeforeDeleteEntityListener", "onBeforeDelete"),
        AFTER_INSERT("AfterInsertEntityListener", "onAfterInsert"),
        AFTER_UPDATE("AfterUpdateEntityListener", "onAfterUpdate"),
        AFTER_DELETE("AfterDeleteEntityListener", "onAfterDelete"),
        BEFORE_ATTACH("BeforeAttachEntityListener", "onBeforeAttach"),
        AFTER_ATTACH("AfterAttachEntityListener", "onAfterAttach"),
        BEFORE_DETACH("BeforeDetachEntityListener", "onBeforeDetach")
    }

    fun generate(
        entity: EntityModel,
        listenerName: String,
        events: List<ListenerEvent>,
        packageName: String
    ): String {
        val b = JavaClassBuilder(listenerName)
        b.package_(packageName)

        b.import_(
            "io.jmix.core.entity.EntityListener",
            "org.springframework.stereotype.Component",
            entity.fullName
        )

        events.forEach { event ->
            b.import_("io.jmix.core.entity.${event.interfaceName}")
        }

        b.annotation {
            name = "Component"
            importPath = "org.springframework.stereotype.Component"
            value("\"${listenerName.replaceFirstChar { it.lowercase() }}\"")
        }

        // Implement listener interfaces
        events.forEach { event ->
            b.implements_("${event.interfaceName}<${entity.className}>")
        }

        // Implement methods
        events.forEach { event ->
            b.method {
                name = event.methodName
                returnType = "void"
                isOverride = true
                param(entity.className, "entity")

                when (event) {
                    ListenerEvent.BEFORE_INSERT -> {
                        line("// Called before a new entity is inserted into the database")
                        line("// Use for validation, default values, audit fields")
                    }
                    ListenerEvent.BEFORE_UPDATE -> {
                        line("// Called before an entity is updated in the database")
                        line("// Use for validation, audit fields")
                    }
                    ListenerEvent.BEFORE_DELETE -> {
                        line("// Called before an entity is deleted from the database")
                        line("// Use for cascade operations, cleanup")
                    }
                    ListenerEvent.AFTER_INSERT -> {
                        line("// Called after a new entity is inserted into the database")
                        line("// Use for notifications, indexing")
                    }
                    ListenerEvent.AFTER_UPDATE -> {
                        line("// Called after an entity is updated in the database")
                        line("// Use for notifications, cache invalidation")
                    }
                    ListenerEvent.AFTER_DELETE -> {
                        line("// Called after an entity is deleted from the database")
                        line("// Use for cleanup, notifications")
                    }
                    ListenerEvent.BEFORE_ATTACH -> {
                        line("// Called before a detached entity is attached to a persistence context")
                    }
                    ListenerEvent.AFTER_ATTACH -> {
                        line("// Called after a detached entity is attached to a persistence context")
                    }
                    ListenerEvent.BEFORE_DETACH -> {
                        line("// Called before an entity is detached from a persistence context")
                    }
                }
            }
        }

        return b.build()
    }

    /**
     * Generates a lifecycle callback holder (JPA @PrePersist etc. inside the entity).
     */
    fun generateLifecycleCallbacks(entity: EntityModel): String {
        val sb = StringBuilder()
        entity.lifecycleCallbacks.forEach { callback ->
            sb.appendLine()
            sb.appendLine("    ${callback.annotation}")
            sb.appendLine("    private void on${callback.name.lowercase().replaceFirstChar { it.uppercase() }}() {")
            sb.appendLine("        // TODO: implement ${callback.name}")
            sb.appendLine("    }")
        }
        return sb.toString()
    }
}
