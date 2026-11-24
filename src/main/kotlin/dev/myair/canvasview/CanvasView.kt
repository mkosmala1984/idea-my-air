package dev.myair.canvasview

import dev.myair.store.Canvas
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** The frame number of id `C<n>`; null for another id. */
private fun frameNumber(id: String): Int? = id.removePrefix("C").takeIf { id.startsWith("C") }?.toIntOrNull()

/**
 * The id of the frame a canvas tab shows once [canvas] is as it is now, after it showed [previousSelection] while the
 * newest frame was [previousNewest] (both null before the first): the newest when a frame newer than [previousNewest]
 * arrived, else the frame selected before while it is kept, else the newest; null without frames. Removing the newest
 * frame keeps the selection.
 */
fun selectedFrame(previousSelection: String?, previousNewest: String?, canvas: Canvas): String? {
    val newest = canvas.newest ?: return null
    val before = previousNewest?.let(::frameNumber)
    val now = frameNumber(newest.id)
    val arrived = previousNewest == null || if (before != null && now != null) now > before else newest.id != previousNewest
    if (arrived || previousSelection == null) return newest.id
    return previousSelection.takeIf { s -> canvas.frames.any { it.id == s } } ?: newest.id
}

/**
 * The file a canvas's `@OpenFile(path)` opens: [path] resolved against the first of [roots] when relative, normalized,
 * and only when it lies under one of [roots] (lexically: `..` is resolved, links are not followed); null when it lies
 * outside them all, is not a path, or there is no root.
 */
fun canvasFileTarget(path: String, roots: List<Path>): Path? {
    val bases = roots.map { it.toAbsolutePath().normalize() }
    val base = bases.firstOrNull() ?: return null
    val given = try {
        Path.of(path)
    } catch (e: InvalidPathException) {
        return null
    }
    val target = (if (given.isAbsolute) given else base.resolve(given)).normalize()
    return target.takeIf { t -> t.isAbsolute && bases.any(t::startsWith) }
}

/** What the canvas toolbar says about the frame shown: [summary] such as `2 errors, 1 warning`, and one line per problem for its tooltip. */
data class CanvasProblems(val summary: String, val lines: List<String>)

private fun counted(n: Int, noun: String) = if (n == 0) null else "$n $noun${if (n == 1) "" else "s"}"

private fun describe(e: CanvasError): String = buildString {
    append(if (e.warning) "Warning " else "Error ")
    append(e.code).append(": ").append(e.message)
    listOfNotNull(e.statementId?.let { "statement $it" }, e.component, e.path).takeIf { it.isNotEmpty() }?.let { append(" (").append(it.joinToString(", ")).append(')') }
    e.hint?.let { append(". ").append(it) }
}

/**
 * The problems of the frame shown: how it [parsed] (null before the page said), the renderer's errors [rendered] and
 * the page's script errors [script] since the frame was pushed; null when there are none. Errors and script errors count
 * as errors; warnings, unresolved references and orphaned statements as warnings; a document without a root is an error.
 */
fun canvasProblems(parsed: CanvasMessage.Parsed?, rendered: List<CanvasError>, script: List<String>): CanvasProblems? {
    val errors = (parsed?.errors.orEmpty() + rendered).distinct()
    val noRoot = parsed != null && !parsed.ok && parsed.errors.none { !it.warning }
    val lines = (if (noRoot) listOf("Error: the document has no root statement") else emptyList()) +
        errors.map(::describe) +
        script.map { "Script error: $it" } +
        parsed?.unresolved.orEmpty().map { "Warning: $it is used but never defined" } +
        parsed?.orphaned.orEmpty().map { "Warning: $it is not reached from root and does not show" }
    if (lines.isEmpty()) return null
    val errorCount = errors.count { !it.warning } + script.size + (if (noRoot) 1 else 0)
    val warningCount = errors.count { it.warning } + parsed?.unresolved.orEmpty().size + parsed?.orphaned.orEmpty().size
    return CanvasProblems(listOfNotNull(counted(errorCount, "error"), counted(warningCount, "warning")).joinToString(", "), lines)
}
