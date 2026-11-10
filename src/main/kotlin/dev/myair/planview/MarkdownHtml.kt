package dev.myair.planview

import com.intellij.markdown.utils.convertMarkdownToHtml
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color

/** The colours [styledHtml] paints with, as `#rrggbb`. */
data class MarkdownColors(val code: String, val rule: String, val quote: String) {
    companion object {
        private val CODE = JBColor(0xE6E8EC, 0x393B40)

        /** Read from the current theme, so a panel built after a theme change takes the new one. */
        fun current() = MarkdownColors(hex(CODE), hex(JBColor.border()), hex(JBUI.CurrentTheme.ContextHelp.FOREGROUND))

        private fun hex(color: Color) = "#" + ColorUtil.toHex(color)
    }
}

private val STYLED = Regex("<(code|pre|blockquote|h[1-6])(\\s[^>]*)?>")

/**
 * [markdown] as HTML for a DSL text cell, with the code, code blocks, quotes and headings styled inline: the cell reads a
 * `<style>` only from the head it writes itself, and the default sheet sizes headings for a page, not a pane. Headings
 * grow relative to the text, so they follow the pane's font size.
 */
fun markdownHtml(markdown: String, colors: MarkdownColors): String = styledHtml(convertMarkdownToHtml(markdown), colors)

internal fun styledHtml(html: String, colors: MarkdownColors): String = STYLED.replace(html) { match ->
    val (tag, attributes) = match.destructured
    check("style=" !in attributes) { "<$tag> already styled: ${match.value}" }
    "<$tag$attributes style=\"${style(tag, colors)}\">"
}

private fun style(tag: String, colors: MarkdownColors) = when (tag) {
    // A monospaced letter is wider and looks larger than the text's at the same size.
    "code" -> "background-color:${colors.code};font-size:90%"
    "pre" -> "background-color:${colors.code};padding:6px 8px;margin-top:4px;margin-bottom:4px"
    "blockquote" -> "margin:4px 0 4px 0;padding-left:8px;border-left:3px solid ${colors.rule};color:${colors.quote}"
    else -> "font-size:${HEADING_SIZES.getValue(tag)}%;margin-top:6px;margin-bottom:2px"
}

private val HEADING_SIZES = mapOf("h1" to 130, "h2" to 118, "h3" to 108, "h4" to 100, "h5" to 100, "h6" to 100)
