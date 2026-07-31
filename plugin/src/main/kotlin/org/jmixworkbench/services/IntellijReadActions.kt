package org.jmixworkbench.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager

/**
 * Reuses an existing read/write-intent context (including direct IntelliJ
 * actions and platform tests on EDT) and otherwise acquires a cancellable read
 * action on a background thread.
 *
 * Heavy production entry points are scheduled through JCEF non-blocking reads;
 * this helper prevents nested/non-cancellable background reads without
 * violating IntelliJ's rule that computeCancellable must not start on EDT.
 */
internal inline fun <T> cancellableRead(crossinline computation: () -> T): T {
    val application = ApplicationManager.getApplication()
    if (application.isReadAccessAllowed) {
        ProgressManager.checkCanceled()
        return computation()
    }
    return ReadAction.computeCancellable<T, RuntimeException> {
        ProgressManager.checkCanceled()
        computation()
    }
}
