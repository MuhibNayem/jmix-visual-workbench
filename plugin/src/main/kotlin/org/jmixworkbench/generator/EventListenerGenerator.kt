package org.jmixworkbench.generator

import org.jmixworkbench.model.EntitySourceLanguage

/**
 * Generates current Jmix/Spring entity event listeners.
 *
 * This intentionally does not use the pre-Jmix `io.jmix.core.entity.*Listener`
 * interfaces. Transaction phase and after-commit data access are explicit so
 * generated listener skeletons do not imply unsafe persistence behavior.
 */
object EventListenerGenerator {

    enum class ListenerEvent {
        ENTITY_SAVING,
        ENTITY_LOADING,
        ENTITY_CHANGED_BEFORE_COMMIT,
        ENTITY_CHANGED_AFTER_COMMIT,
    }

    data class ListenerModel(
        val entityClassName: String,
        val entityQualifiedName: String,
        val listenerClassName: String,
        val packageName: String,
        val beanName: String,
        val sourceLanguage: EntitySourceLanguage,
        val events: List<ListenerEvent>,
        val afterCommitRequiresNewTransaction: Boolean = false,
    )

    fun generate(model: ListenerModel): String =
        when (model.sourceLanguage) {
            EntitySourceLanguage.JAVA -> generateJava(model)
            EntitySourceLanguage.KOTLIN -> generateKotlin(model)
        }

    private fun generateJava(model: ListenerModel): String = buildString {
        appendLine("package ${model.packageName};")
        appendLine()
        appendLine("import ${model.entityQualifiedName};")
        if (ListenerEvent.ENTITY_CHANGED_BEFORE_COMMIT in model.events ||
            ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT in model.events
        ) {
            appendLine("import io.jmix.core.event.EntityChangedEvent;")
        }
        if (ListenerEvent.ENTITY_LOADING in model.events) {
            appendLine("import io.jmix.core.event.EntityLoadingEvent;")
        }
        if (ListenerEvent.ENTITY_SAVING in model.events) {
            appendLine("import io.jmix.core.event.EntitySavingEvent;")
        }
        if (model.events.any { it != ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT }) {
            appendLine("import org.springframework.context.event.EventListener;")
        }
        appendLine("import org.springframework.stereotype.Component;")
        if (ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT in model.events) {
            if (model.afterCommitRequiresNewTransaction) {
                appendLine("import org.springframework.transaction.annotation.Propagation;")
                appendLine("import org.springframework.transaction.annotation.Transactional;")
            }
            appendLine("import org.springframework.transaction.event.TransactionPhase;")
            appendLine("import org.springframework.transaction.event.TransactionalEventListener;")
        }
        appendLine()
        appendLine("@Component(\"${escapeJava(model.beanName)}\")")
        appendLine("public class ${model.listenerClassName} {")
        model.events.forEachIndexed { index, event ->
            if (index > 0) appendLine()
            appendJavaHandler(model, event)
        }
        appendLine("}")
    }

    private fun StringBuilder.appendJavaHandler(
        model: ListenerModel,
        event: ListenerEvent,
    ) {
        when (event) {
            ListenerEvent.ENTITY_SAVING -> {
                appendLine("    @EventListener")
                appendLine(
                    "    public void on${model.entityClassName}Saving(" +
                        "EntitySavingEvent<${model.entityClassName}> event) {",
                )
                appendLine("        // Set reviewed defaults or transient values before the entity is stored.")
            }

            ListenerEvent.ENTITY_LOADING -> {
                appendLine("    @EventListener")
                appendLine(
                    "    public void on${model.entityClassName}Loading(" +
                        "EntityLoadingEvent<${model.entityClassName}> event) {",
                )
                appendLine("        // Initialize transient state only; related entities may not be available yet.")
            }

            ListenerEvent.ENTITY_CHANGED_BEFORE_COMMIT -> {
                appendLine("    @EventListener")
                appendLine(
                    "    public void on${model.entityClassName}ChangedBeforeCommit(" +
                        "EntityChangedEvent<${model.entityClassName}> event) {",
                )
                appendLine("        // Participate in the current transaction; keep side effects rollback-safe.")
            }

            ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT -> {
                appendLine("    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)")
                if (model.afterCommitRequiresNewTransaction) {
                    appendLine("    @Transactional(propagation = Propagation.REQUIRES_NEW)")
                }
                appendLine(
                    "    public void on${model.entityClassName}ChangedAfterCommit(" +
                        "EntityChangedEvent<${model.entityClassName}> event) {",
                )
                appendLine(
                    if (model.afterCommitRequiresNewTransaction) {
                        "        // Data access runs in an explicit new transaction after the original commit."
                    } else {
                        "        // Publish notifications or external side effects; no transaction is opened here."
                    },
                )
            }
        }
        appendLine("    }")
    }

    private fun generateKotlin(model: ListenerModel): String = buildString {
        appendLine("package ${model.packageName}")
        appendLine()
        appendLine("import ${model.entityQualifiedName}")
        if (ListenerEvent.ENTITY_CHANGED_BEFORE_COMMIT in model.events ||
            ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT in model.events
        ) {
            appendLine("import io.jmix.core.event.EntityChangedEvent")
        }
        if (ListenerEvent.ENTITY_LOADING in model.events) {
            appendLine("import io.jmix.core.event.EntityLoadingEvent")
        }
        if (ListenerEvent.ENTITY_SAVING in model.events) {
            appendLine("import io.jmix.core.event.EntitySavingEvent")
        }
        if (model.events.any { it != ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT }) {
            appendLine("import org.springframework.context.event.EventListener")
        }
        appendLine("import org.springframework.stereotype.Component")
        if (ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT in model.events) {
            if (model.afterCommitRequiresNewTransaction) {
                appendLine("import org.springframework.transaction.annotation.Propagation")
                appendLine("import org.springframework.transaction.annotation.Transactional")
            }
            appendLine("import org.springframework.transaction.event.TransactionPhase")
            appendLine("import org.springframework.transaction.event.TransactionalEventListener")
        }
        appendLine()
        appendLine("@Component(\"${escapeKotlin(model.beanName)}\")")
        appendLine("class ${model.listenerClassName} {")
        model.events.forEachIndexed { index, event ->
            if (index > 0) appendLine()
            appendKotlinHandler(model, event)
        }
        appendLine("}")
    }

    private fun StringBuilder.appendKotlinHandler(
        model: ListenerModel,
        event: ListenerEvent,
    ) {
        when (event) {
            ListenerEvent.ENTITY_SAVING -> {
                appendLine("    @EventListener")
                appendLine(
                    "    fun on${model.entityClassName}Saving(" +
                        "event: EntitySavingEvent<${model.entityClassName}>) {",
                )
                appendLine("        // Set reviewed defaults or transient values before the entity is stored.")
            }

            ListenerEvent.ENTITY_LOADING -> {
                appendLine("    @EventListener")
                appendLine(
                    "    fun on${model.entityClassName}Loading(" +
                        "event: EntityLoadingEvent<${model.entityClassName}>) {",
                )
                appendLine("        // Initialize transient state only; related entities may not be available yet.")
            }

            ListenerEvent.ENTITY_CHANGED_BEFORE_COMMIT -> {
                appendLine("    @EventListener")
                appendLine(
                    "    fun on${model.entityClassName}ChangedBeforeCommit(" +
                        "event: EntityChangedEvent<${model.entityClassName}>) {",
                )
                appendLine("        // Participate in the current transaction; keep side effects rollback-safe.")
            }

            ListenerEvent.ENTITY_CHANGED_AFTER_COMMIT -> {
                appendLine("    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)")
                if (model.afterCommitRequiresNewTransaction) {
                    appendLine("    @Transactional(propagation = Propagation.REQUIRES_NEW)")
                }
                appendLine(
                    "    fun on${model.entityClassName}ChangedAfterCommit(" +
                        "event: EntityChangedEvent<${model.entityClassName}>) {",
                )
                appendLine(
                    if (model.afterCommitRequiresNewTransaction) {
                        "        // Data access runs in an explicit new transaction after the original commit."
                    } else {
                        "        // Publish notifications or external side effects; no transaction is opened here."
                    },
                )
            }
        }
        appendLine("    }")
    }

    private fun escapeJava(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun escapeKotlin(value: String): String = escapeJava(value)
}
