package org.jmixworkbench.discovery.navigation

import org.jmixworkbench.discovery.model.SourceLocator

data class SourceNavigationValidation(
    val locator: SourceLocator?,
    val errorCode: String?,
    val message: String,
) {
    val accepted: Boolean
        get() = locator != null && errorCode == null
}

object SourceNavigationPolicy {
    private const val MAX_SOURCE_COORDINATE = 10_000_000

    fun validate(
        relativePath: String,
        line: Int?,
        column: Int?,
        revisionFingerprint: String,
    ): SourceNavigationValidation {
        if (line != null && line > MAX_SOURCE_COORDINATE) {
            return rejected("The requested source line is outside the supported range.")
        }
        if (column != null && column > MAX_SOURCE_COORDINATE) {
            return rejected("The requested source column is outside the supported range.")
        }

        val locator = runCatching {
            SourceLocator(
                relativePath = relativePath,
                line = line,
                column = column,
                revisionFingerprint = revisionFingerprint,
            )
        }.getOrElse {
            return rejected("The requested source location is not a valid project-relative location.")
        }
        return SourceNavigationValidation(
            locator = locator,
            errorCode = null,
            message = "Source location accepted.",
        )
    }

    fun revisionMatches(locator: SourceLocator, currentFingerprint: String): Boolean =
        currentFingerprint.isNotBlank() &&
            currentFingerprint == locator.revisionFingerprint

    private fun rejected(message: String): SourceNavigationValidation =
        SourceNavigationValidation(
            locator = null,
            errorCode = "JVW-NAVIGATION-PATH-REJECTED",
            message = message,
        )
}
