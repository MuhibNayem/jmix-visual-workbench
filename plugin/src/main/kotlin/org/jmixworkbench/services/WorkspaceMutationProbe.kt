package org.jmixworkbench.services

/**
 * Internal-only mutation observation boundary used by failure-safety
 * certification. It is deliberately absent from the JCEF bridge and plugin
 * service API, so browser requests cannot activate or influence it.
 */
internal fun interface WorkspaceMutationProbe {
    fun observe(event: WorkspaceMutationEvent)

    companion object {
        val NONE = WorkspaceMutationProbe { }
    }
}

internal data class WorkspaceMutationEvent(
    val operation: WorkspaceMutationOperation,
    val phase: WorkspaceMutationPhase,
    val relativePath: String? = null,
    val fileIndex: Int? = null,
)

internal enum class WorkspaceMutationOperation {
    APPLY,
    UNDO,
    REDO,
    NATIVE_REPOSITORY_INJECTION,
}

internal enum class WorkspaceMutationPhase {
    AFTER_OUTER_PREFLIGHT,
    AFTER_LOCKED_PREFLIGHT,
    BEFORE_FILE_MUTATION,
    AFTER_FILE_MUTATION,
    BEFORE_ROLLBACK,
    AFTER_ROLLBACK,
}
