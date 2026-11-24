package dev.myair.canvas

import java.security.MessageDigest

// Statement-level patches of an OpenUI Lang document, as `mergeStatements` of @openuidev/lang-core does them
// (src/parser/merge.ts): a patch statement replaces the one of its name or is appended, `name = null` deletes it, and
// statements no longer reachable from `root` are dropped. Ported rather than run on a page so a patch works without
// an open canvas tab; the scanner follows lang-core's tokenizer and statement splitter where they decide boundaries
// (strings, comments, brackets, a line starting with `?` continuing a ternary) and references.

/** A statement of a document: its name and its whole source text (`name = ...`, comments on its lines included). */
data class OpenUiStatement(val name: String, val text: String, internal val refs: Set<String>, internal val isNull: Boolean)

/** A merge's outcome: the new document, the statements the patch set, and those gone (deleted or unreachable). */
data class OpenUiMerge(val text: String, val set: List<String>, val removed: List<String>)

/** The revision of a frame's document: a short hash an agent passes back to say which text its patch is based on. */
fun openUiRev(openui: String): String =
    MessageDigest.getInstance("SHA-256").digest(openui.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

/** Merges [patch] into [existing]; throws when the patch has no statement or the result has no `root`. */
fun mergeOpenUi(existing: String, patch: String): OpenUiMerge {
    val patchStatements = openUiStatements(stripFences(patch))
    require(patchStatements.isNotEmpty()) { "the patch has no statement; write lines of the form name = expression" }
    val before = openUiStatements(existing)
    val merged = LinkedHashMap<String, OpenUiStatement>()
    before.forEach { merged[it.name] = it }
    for (s in patchStatements) {
        if (s.isNull) merged.remove(s.name) else merged[s.name] = s
    }
    require("root" in merged) { "the patch leaves the document without root" }
    val reachable = HashSet<String>().apply { add("root") }
    val queue = ArrayDeque(listOf("root"))
    while (queue.isNotEmpty()) {
        for (ref in merged[queue.removeLast()]?.refs.orEmpty()) if (ref in merged && reachable.add(ref)) queue.add(ref)
    }
    val kept = merged.values.filter { it.name in reachable || it.name.startsWith("$") }
    val keptNames = kept.map { it.name }.toSet()
    return OpenUiMerge(
        kept.joinToString("\n") { it.text },
        patchStatements.filter { !it.isNull }.map { it.name }.distinct(),
        before.map { it.name }.distinct().filter { it !in keptNames },
    )
}

private fun stripFences(text: String) = text.lines().filterNot { it.trimStart().startsWith("```") }.joinToString("\n")

private enum class T { NEWLINE, OPEN_PAREN, CLOSE_PAREN, OPEN_BRACKET, CLOSE_BRACKET, OPEN_BRACE, CLOSE_BRACE, COMMA, COLON, ASSIGN, QUESTION, DOT, NULL, IDENT, STATE, OTHER }

private class Token(val t: T, val start: Int, val value: String? = null)

private fun isIdentStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_'
private fun isIdentPart(c: Char) = isIdentStart(c) || c in '0'..'9'

/** The tokens the statement splitter and the reference walk need; everything else is OTHER (or skipped, as comments). */
private fun tokenize(src: String): List<Token> {
    val out = ArrayList<Token>()
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == ' ' || c == '\t' || c == '\r' -> i++
            c == '\n' -> out.add(Token(T.NEWLINE, i)).also { i++ }
            c == '(' -> out.add(Token(T.OPEN_PAREN, i)).also { i++ }
            c == ')' -> out.add(Token(T.CLOSE_PAREN, i)).also { i++ }
            c == '[' -> out.add(Token(T.OPEN_BRACKET, i)).also { i++ }
            c == ']' -> out.add(Token(T.CLOSE_BRACKET, i)).also { i++ }
            c == '{' -> out.add(Token(T.OPEN_BRACE, i)).also { i++ }
            c == '}' -> out.add(Token(T.CLOSE_BRACE, i)).also { i++ }
            c == ',' -> out.add(Token(T.COMMA, i)).also { i++ }
            c == ':' -> out.add(Token(T.COLON, i)).also { i++ }
            c == '?' -> out.add(Token(T.QUESTION, i)).also { i++ }
            c == '.' -> out.add(Token(T.DOT, i)).also { i++ }
            c == '=' -> if (i + 1 < n && src[i + 1] == '=') out.add(Token(T.OTHER, i)).also { i += 2 } else out.add(Token(T.ASSIGN, i)).also { i++ }
            c == '!' || c == '>' || c == '<' -> out.add(Token(T.OTHER, i)).also { i += if (i + 1 < n && src[i + 1] == '=') 2 else 1 }
            c == '#' || (c == '/' && i + 1 < n && src[i + 1] == '/') -> while (i < n && src[i] != '\n') i++
            c == '"' -> {
                val start = i++
                while (i < n) {
                    if (src[i] == '\\') i += 2 else if (src[i++] == '"') break
                }
                out.add(Token(T.OTHER, start))
            }
            c == '\'' -> {
                val start = i++
                while (i < n) {
                    if (src[i] == '\\') i += 2 else if (src[i++] == '\'') break
                }
                out.add(Token(T.OTHER, start))
            }
            c == '$' && i + 1 < n && isIdentStart(src[i + 1]) -> {
                val start = i++
                while (i < n && isIdentPart(src[i])) i++
                out.add(Token(T.STATE, start, src.substring(start, i)))
            }
            c == '@' && i + 1 < n && isIdentStart(src[i + 1]) -> {
                val start = i++
                while (i < n && isIdentPart(src[i])) i++
                out.add(Token(T.OTHER, start))
            }
            isIdentStart(c) -> {
                val start = i
                while (i < n && isIdentPart(src[i])) i++
                when (val word = src.substring(start, i)) {
                    "null" -> out.add(Token(T.NULL, start))
                    "true", "false" -> out.add(Token(T.OTHER, start))
                    else -> out.add(Token(T.IDENT, start, word))
                }
            }
            else -> out.add(Token(T.OTHER, i)).also { i++ }
        }
    }
    return out
}

/** The statements of [src] in order, split as lang-core's `split` does; lines that are no statement are skipped. */
fun openUiStatements(src: String): List<OpenUiStatement> {
    val tokens = tokenize(src)
    val out = ArrayList<OpenUiStatement>()
    var pos = 0
    fun skipLine() {
        while (pos < tokens.size && tokens[pos].t != T.NEWLINE) pos++
    }
    while (pos < tokens.size) {
        while (pos < tokens.size && tokens[pos].t == T.NEWLINE) pos++
        if (pos >= tokens.size) break
        val id = tokens[pos]
        if (id.t != T.IDENT && id.t != T.STATE) {
            skipLine()
            continue
        }
        pos++
        if (pos >= tokens.size || tokens[pos].t != T.ASSIGN) {
            skipLine()
            continue
        }
        pos++
        val expr = ArrayList<Token>()
        var depth = 0
        var ternary = 0
        while (pos < tokens.size) {
            val t = tokens[pos].t
            if (t == T.NEWLINE && depth <= 0 && ternary <= 0) {
                var peek = pos + 1
                while (peek < tokens.size && tokens[peek].t == T.NEWLINE) peek++
                // lang-core continues a statement on a line that starts with `?` (a ternary split over lines).
                if (peek < tokens.size && tokens[peek].t == T.QUESTION) {
                    pos++
                    continue
                }
                break
            }
            if (t == T.NEWLINE) {
                pos++
                continue
            }
            when {
                t == T.OPEN_PAREN || t == T.OPEN_BRACKET || t == T.OPEN_BRACE -> depth++
                (t == T.CLOSE_PAREN || t == T.CLOSE_BRACKET || t == T.CLOSE_BRACE) && depth > 0 -> depth--
                t == T.QUESTION && depth == 0 -> ternary++
                t == T.COLON && depth == 0 && ternary > 0 -> ternary--
            }
            expr.add(tokens[pos++])
        }
        if (expr.isEmpty()) continue
        val end = if (pos < tokens.size) tokens[pos].start else src.length
        out.add(OpenUiStatement(id.value!!, src.substring(id.start, end).trimEnd(), refsOf(expr), expr.size == 1 && expr[0].t == T.NULL))
    }
    return out
}

/**
 * The names an expression refers to: identifiers that are not a call's name (followed by `(`), not a member after `.`,
 * and not an object key (right after `{` or a `,` directly inside braces, and followed by `:`).
 */
private fun refsOf(expr: List<Token>): Set<String> {
    val refs = HashSet<String>()
    val stack = ArrayDeque<T>()
    for ((k, tok) in expr.withIndex()) {
        when (tok.t) {
            T.OPEN_PAREN, T.OPEN_BRACKET, T.OPEN_BRACE -> stack.addLast(tok.t)
            T.CLOSE_PAREN, T.CLOSE_BRACKET, T.CLOSE_BRACE -> stack.removeLastOrNull()
            T.IDENT -> {
                val prev = expr.getOrNull(k - 1)?.t
                val next = expr.getOrNull(k + 1)?.t
                val key = stack.lastOrNull() == T.OPEN_BRACE && (prev == T.OPEN_BRACE || prev == T.COMMA) && next == T.COLON
                if (next != T.OPEN_PAREN && prev != T.DOT && !key) refs.add(tok.value!!)
            }
            else -> {}
        }
    }
    return refs
}

/** The positional argument that holds a board element's box, by component. */
private val BOX_ARGUMENT = mapOf("Shape" to 2, "Note" to 1, "Frame" to 1)

/**
 * [openui] with board element [element] placed where the user dragged it: its box becomes `{x, y, w?, h?, pinned: true}`
 * in grid units of its container's content area (an anchor gives way to the position), and statements only the old box
 * used are dropped, as a patch would. Throws when [element] is no statement, or not a Shape, Note or Frame with a box.
 */
fun moveBoardElement(openui: String, element: String, x: Long, y: Long, w: Long?, h: Long?): OpenUiMerge {
    val statement = requireNotNull(openUiStatements(openui).lastOrNull { it.name == element }) { "no statement '$element'" }
    val src = statement.text
    val tokens = tokenize(src)
    // name = Component( ... ): the call starts at the third token.
    val call = tokens.getOrNull(2)?.takeIf { it.t == T.IDENT && tokens.getOrNull(3)?.t == T.OPEN_PAREN }?.value
    val index = requireNotNull(BOX_ARGUMENT[call]) { "'$element' is ${call ?: "no component"}, not a Shape, Note or Frame" }
    // The spans of the call's arguments: from their first token to the comma or parenthesis that ends them.
    val spans = ArrayList<IntRange>()
    var depth = 0
    var argStart = -1
    for (k in 4 until tokens.size) {
        val tok = tokens[k]
        when (tok.t) {
            T.OPEN_PAREN, T.OPEN_BRACKET, T.OPEN_BRACE -> {
                if (depth == 0 && argStart < 0) argStart = tok.start
                depth++
            }
            T.CLOSE_PAREN, T.CLOSE_BRACKET, T.CLOSE_BRACE -> {
                if (depth == 0) {
                    if (argStart >= 0) spans.add(argStart until tok.start)
                    break
                }
                depth--
            }
            T.COMMA -> if (depth == 0) {
                if (argStart >= 0) spans.add(argStart until tok.start)
                argStart = -1
            }
            T.NEWLINE -> {}
            else -> if (depth == 0 && argStart < 0) argStart = tok.start
        }
    }
    val span = requireNotNull(spans.getOrNull(index)) { "'$element' has no box argument" }
    val box = buildList {
        add("x: $x")
        add("y: $y")
        w?.let { add("w: $it") }
        h?.let { add("h: $it") }
        add("pinned: true")
    }.joinToString(", ", "{", "}")
    val end = span.first + src.substring(span.first, span.last + 1).trimEnd().length
    return mergeOpenUi(openui, src.substring(0, span.first) + box + src.substring(end))
}
