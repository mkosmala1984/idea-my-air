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

/** The positional argument that holds a board element's text: a note's text, a shape's label, a frame's title. */
private val TEXT_ARGUMENT = mapOf("Note" to 0, "Shape" to 1, "Frame" to 0)

/** A statement `name = Component(args)` taken apart: the component's name and the source span of each argument. */
private class Call(val statement: OpenUiStatement, val component: String?, val args: List<IntRange>) {
    val src get() = statement.text

    /** The statement with argument [index] replaced by [text]. */
    fun replace(index: Int, text: String): String {
        val span = args[index]
        val end = span.first + src.substring(span.first, span.last + 1).trimEnd().length
        return src.substring(0, span.first) + text + src.substring(end)
    }

    /** Argument [index]'s source, trimmed; null when there is no such argument. */
    fun arg(index: Int): String? = args.getOrNull(index)?.let { src.substring(it.first, it.last + 1).trim() }
}

private fun callOf(statement: OpenUiStatement): Call {
    val src = statement.text
    val tokens = tokenize(src)
    // name = Component( ... ): the call starts at the third token.
    val component = tokens.getOrNull(2)?.takeIf { it.t == T.IDENT && tokens.getOrNull(3)?.t == T.OPEN_PAREN }?.value
    // The spans of the call's arguments: from their first token to the comma or parenthesis that ends them.
    val spans = ArrayList<IntRange>()
    if (component != null) {
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
    }
    return Call(statement, component, spans)
}

private fun statementCall(openui: String, name: String): Call =
    callOf(requireNotNull(openUiStatements(openui).lastOrNull { it.name == name }) { "no statement '$name'" })

/**
 * [openui] with board element [element] placed where the user dragged it: its box becomes `{x, y, w?, h?, pinned: true}`
 * in grid units of its container's content area (an anchor gives way to the position), and statements only the old box
 * used are dropped, as a patch would. Throws when [element] is no statement, or not a Shape, Note or Frame with a box.
 */
fun moveBoardElement(openui: String, element: String, x: Long, y: Long, w: Long?, h: Long?): OpenUiMerge {
    val call = statementCall(openui, element)
    val index = requireNotNull(BOX_ARGUMENT[call.component]) { "'$element' is ${call.component ?: "no component"}, not a Shape, Note or Frame" }
    require(index < call.args.size) { "'$element' has no box argument" }
    val box = buildList {
        add("x: $x")
        add("y: $y")
        w?.let { add("w: $it") }
        h?.let { add("h: $it") }
        add("pinned: true")
    }.joinToString(", ", "{", "}")
    return mergeOpenUi(openui, call.replace(index, box))
}

/** A change the user made on a board with the mouse, which the plugin writes into the frame's document. */
sealed interface BoardEdit {
    /** A new note or shape ([kind] `note`, or a Shape kind such as `rect`) at [x], [y] at the top level of [board]. */
    data class Add(val board: String, val kind: String, val x: Long, val y: Long) : BoardEdit

    /** A new edge of [board] from [from] to [to]. */
    data class Connect(val board: String, val from: String, val to: String) : BoardEdit

    /** Elements and edges removed, with what only they held and the edges to anything removed. */
    data class Remove(val ids: List<String>) : BoardEdit

    /** [element]'s text: a note's text, a shape's label or a frame's title. */
    data class Text(val element: String, val text: String) : BoardEdit
}

/** The Shape kinds a board may add. */
val SHAPE_KINDS = setOf("rect", "rounded", "ellipse", "diamond", "cylinder", "group")

/** [text] as an OpenUI string literal. */
fun openUiString(text: String): String = buildString {
    append('"')
    for (c in text) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> {}
        '\t' -> append("\\t")
        else -> append(c)
    }
    append('"')
}

/** Applies [edit] to [openui]; throws naming what is missing or not a board element. */
fun editBoard(openui: String, edit: BoardEdit): OpenUiMerge = when (edit) {
    is BoardEdit.Add -> {
        val (prefix, expression) = if (edit.kind == "note") {
            "note" to "Note(\"New note\", {x: ${edit.x}, y: ${edit.y}, w: 8, pinned: true}, \"yellow\")"
        } else {
            require(edit.kind in SHAPE_KINDS) { "'${edit.kind}' is no note or Shape kind" }
            "shape" to "Shape(\"${edit.kind}\", \"New ${edit.kind}\", {x: ${edit.x}, y: ${edit.y}, w: 6, h: 3, pinned: true})"
        }
        addToBoard(openui, edit.board, prefix, expression)
    }
    is BoardEdit.Connect -> {
        val names = openUiStatements(openui).map { it.name }.toSet()
        require(edit.from in names && edit.to in names) { "an edge needs two elements that exist, not ${edit.from} and ${edit.to}" }
        addToBoard(openui, edit.board, "edge", "Edge(${openUiString(edit.from)}, ${openUiString(edit.to)})")
    }
    is BoardEdit.Remove -> removeFromBoard(openui, edit.ids)
    is BoardEdit.Text -> {
        val call = statementCall(openui, edit.element)
        val index = requireNotNull(TEXT_ARGUMENT[call.component]) { "'${edit.element}' is ${call.component ?: "no component"}, not a Shape, Note or Frame" }
        require(index < call.args.size) { "'${edit.element}' has no text argument" }
        mergeOpenUi(openui, call.replace(index, openUiString(edit.text)))
    }
}

/** [openui] with a statement `<prefix><n> = expression` (the first free n) added at the end of [board]'s items. */
private fun addToBoard(openui: String, board: String, prefix: String, expression: String): OpenUiMerge {
    val call = statementCall(openui, board)
    require(call.component == "Board") { "'$board' is ${call.component ?: "no component"}, not a Board" }
    val names = openUiStatements(openui).map { it.name }.toSet()
    val name = generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in names }
    val items = requireNotNull(call.arg(0)?.takeIf { it.startsWith("[") && it.endsWith("]") }) { "'$board' has no items list" }
    val inner = items.removePrefix("[").removeSuffix("]").trim()
    return mergeOpenUi(openui, call.replace(0, if (inner.isEmpty()) "[$name]" else "[$inner, $name]") + "\n$name = $expression")
}

/**
 * [openui] without [ids], what only they held (a container's children, a frame's content) and the edges to any of
 * those: each is deleted and taken out of every list that names it.
 */
private fun removeFromBoard(openui: String, ids: List<String>): OpenUiMerge {
    val statements = openUiStatements(openui)
    val byName = statements.associateBy { it.name }
    require(ids.isNotEmpty() && ids.all { it in byName }) { "nothing to remove among ${ids.joinToString()}" }
    // What the removed hold: everything their statements refer to, transitively.
    val gone = HashSet<String>()
    val queue = ArrayDeque(ids)
    while (queue.isNotEmpty()) {
        val name = queue.removeLast()
        if (gone.add(name)) byName[name]?.refs?.forEach { if (it in byName) queue.add(it) }
    }
    // A statement something else still uses stays (a table shared by two frames); only what the removed alone held goes.
    while (true) {
        val used = statements.filter { it.name !in gone }.flatMap { it.refs }.toSet()
        if (!gone.removeAll { it !in ids && it in used }) break
    }
    for (s in statements) {
        val call = callOf(s)
        if (call.component == "Edge" && listOf(call.arg(0), call.arg(1)).any { arg -> arg != null && gone.any { openUiString(it) == arg } }) gone += s.name
    }
    val patch = ArrayList<String>()
    for (s in statements) {
        when {
            s.name in gone -> patch += "${s.name} = null"
            s.refs.any { it in gone } -> patch += withoutListItems(s.text, gone)
        }
    }
    return mergeOpenUi(openui, patch.joinToString("\n"))
}

/** [src] with every list item that is just a name in [names] taken out; a list with one gone is written anew. */
private fun withoutListItems(src: String, names: Set<String>): String {
    var out = src
    // One list at a time, from the text as it is now, so lists inside lists stay right.
    while (true) {
        val tokens = tokenize(out)
        val hit = tokens.indices.firstOrNull { k ->
            val tok = tokens[k]
            tok.t == T.IDENT && tok.value in names && k >= 3 &&
                tokens.getOrNull(k - 1)?.t.let { it == T.OPEN_BRACKET || it == T.COMMA } &&
                tokens.getOrNull(k + 1)?.t.let { it == T.COMMA || it == T.CLOSE_BRACKET }
        } ?: return out
        // The list around it: back to its [ and on to its ], at depth 0 relative to it.
        var open = hit - 1
        var depth = 0
        while (open >= 0) {
            when (tokens[open].t) {
                T.CLOSE_PAREN, T.CLOSE_BRACKET, T.CLOSE_BRACE -> depth++
                T.OPEN_PAREN, T.OPEN_BRACE -> depth--
                T.OPEN_BRACKET -> if (depth == 0) break else depth--
                else -> {}
            }
            open--
        }
        val items = ArrayList<String>()
        var close = open + 1
        var itemStart = tokens[open].start + 1
        depth = 0
        while (close < tokens.size) {
            val t = tokens[close].t
            if (depth == 0 && (t == T.COMMA || t == T.CLOSE_BRACKET)) {
                items += out.substring(itemStart, tokens[close].start).trim()
                itemStart = tokens[close].start + 1
                if (t == T.CLOSE_BRACKET) break
            } else when (t) {
                T.OPEN_PAREN, T.OPEN_BRACKET, T.OPEN_BRACE -> depth++
                T.CLOSE_PAREN, T.CLOSE_BRACKET, T.CLOSE_BRACE -> depth--
                else -> {}
            }
            close++
        }
        val kept = items.filter { it.isNotEmpty() && it !in names }
        out = out.substring(0, tokens[open].start) + kept.joinToString(", ", "[", "]") + out.substring(tokens[close].start + 1)
    }
}
