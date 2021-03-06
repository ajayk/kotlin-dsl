/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.tooling.builders

import org.gradle.kotlin.dsl.tooling.models.EditorPosition
import org.gradle.kotlin.dsl.tooling.models.EditorReport
import org.gradle.kotlin.dsl.tooling.models.EditorReportSeverity

import org.gradle.kotlin.dsl.resolver.EditorMessages

import org.gradle.internal.exceptions.LocationAwareException

import java.io.File
import java.io.Serializable


internal
fun buildEditorReportsFor(scriptFile: File?, exceptions: List<Exception>, locationAwareHints: Boolean): List<EditorReport> =
    if (scriptFile == null || exceptions.isEmpty()) emptyList()
    else inferEditorReportsFrom(scriptFile.canonicalFile, exceptions.asSequence(), locationAwareHints)


private
fun inferEditorReportsFrom(scriptFile: File, exceptions: Sequence<Exception>, locationAwareHints: Boolean): List<EditorReport> {

    val locatedExceptions =
        exceptions.findLocationAwareExceptions()

    val reports =
        mutableListOf<EditorReport>()

    reportExceptionsNotLocatedIn(scriptFile, locatedExceptions, reports)

    reportRuntimeExceptionsLocatedIn(scriptFile, locatedExceptions, locationAwareHints, reports)

    return reports
}


private
fun reportExceptionsNotLocatedIn(
    scriptFile: File,
    exceptions: Sequence<LocationAwareException>,
    reports: MutableList<EditorReport>
) {
    if (exceptions.anyNotLocatedIn(scriptFile.path)) {
        reports.add(wholeFileWarning(EditorMessages.buildConfigurationFailed))
    }
}


private
fun reportRuntimeExceptionsLocatedIn(
    scriptFile: File,
    exceptions: Sequence<LocationAwareException>,
    locationAwareHints: Boolean,
    reports: MutableList<EditorReport>
) {
    val actualLinesRange = if (locationAwareHints) scriptFile.readLinesRange() else LongRange.EMPTY
    exceptions.runtimeFailuresLocatedIn(scriptFile.path).forEach { failure ->
        if (locationAwareHints && failure.lineNumber in actualLinesRange) {
            reports.add(lineWarning(messageForLocationAwareEditorHint(failure), failure.lineNumber))
        } else {
            reports.add(wholeFileWarning(EditorMessages.buildConfigurationFailedInCurrentScript))
        }
    }
}


private
fun Sequence<Exception>.findLocationAwareExceptions(): Sequence<LocationAwareException> =
    mapNotNull(::firstLocationAwareCauseOrNull)


private
tailrec fun firstLocationAwareCauseOrNull(ex: Throwable): LocationAwareException? {
    if (ex is LocationAwareException) return ex
    val cause = ex.cause ?: return null
    return firstLocationAwareCauseOrNull(cause)
}


private
fun Sequence<LocationAwareException>.anyNotLocatedIn(scriptPath: String): Boolean =
    any { it.message?.contains(scriptPath) != true }


private
fun Sequence<LocationAwareException>.runtimeFailuresLocatedIn(scriptPath: String): Sequence<LocationAwareException> =
    mapNotNull { it.runtimeFailureLocatedIn(scriptPath) }


private
tailrec fun Throwable.runtimeFailureLocatedIn(scriptPath: String): LocationAwareException? {
    if (this is LocationAwareException && message?.contains(scriptPath) == true) {
        return if (isCausedByScriptCompilationException) null
        else this
    }
    val next = cause ?: return null
    return next.runtimeFailureLocatedIn(scriptPath)
}


/**
 * Check if this [LocationAwareException] is caused by a Gradle Kotlin DSL `ScriptCompilationException`.
 *
 * Compares class names because `ScriptCompilationException` from :provider isn't available here.
 */
private
val LocationAwareException.isCausedByScriptCompilationException
    get() = cause?.let { it::class.java.name == "org.gradle.kotlin.dsl.support.ScriptCompilationException" } == true


private
fun messageForLocationAwareEditorHint(failure: LocationAwareException): String {
    val cause = failure.cause!!
    return cause.message?.takeIf { it.isNotBlank() } ?: EditorMessages.defaultLocationAwareHintMessageFor(cause)
}


private
fun wholeFileWarning(message: String) =
    DefaultEditorReport(EditorReportSeverity.WARNING, message)


private
fun lineWarning(message: String, line: Int) =
    DefaultEditorReport(EditorReportSeverity.WARNING, message, DefaultEditorPosition(line))


private
data class DefaultEditorReport(
    override val severity: EditorReportSeverity,
    override val message: String,
    override val position: EditorPosition? = null
) : EditorReport, Serializable


internal
data class DefaultEditorPosition(
    override val line: Int,
    override val column: Int = 0
) : EditorPosition, Serializable


internal
fun File.readLinesRange() =
    countLines().let { count ->
        if (count == 0L) 0..0L
        else 1..count
    }


private
fun File.countLines(): Long {
    inputStream().buffered().use { input ->

        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val newLine = '\n'.toByte()

        var count = 0L
        var noNewLineBeforeEOF = false
        var readCount = input.read(buffer)

        while (readCount != -1) {
            for (idx in 0 until readCount) {
                if (buffer[idx] == newLine) count++
            }
            noNewLineBeforeEOF = buffer[readCount - 1] != newLine
            readCount = input.read(buffer)
        }
        if (noNewLineBeforeEOF) count++
        return count
    }
}
