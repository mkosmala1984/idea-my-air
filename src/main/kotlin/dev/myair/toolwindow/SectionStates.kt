package dev.myair.toolwindow

/**
 * The sections of the session context the user expanded or collapsed, by key: `+key` expanded, `-key` collapsed.
 * A section never toggled has no entry and keeps its default.
 */
fun sectionExpanded(states: List<String>, key: String): Boolean? = when {
    "+$key" in states -> true
    "-$key" in states -> false
    else -> null
}

fun withSection(states: List<String>, key: String, expanded: Boolean): List<String> =
    states.filter { it.substring(1) != key } + ((if (expanded) "+" else "-") + key)
