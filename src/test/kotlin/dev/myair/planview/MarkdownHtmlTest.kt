package dev.myair.planview

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MarkdownHtmlTest {
    private val colors = MarkdownColors(code = "#e6e8ec", rule = "#cccccc", quote = "#777777")

    @Test
    fun `inline code, code blocks, quotes and headings get inline styles`() {
        val html = markdownHtml("## Problem\n\nUse `git push`.\n\n```bash\ngit push -u origin x\n```\n\n> careful", colors)
        assertContains(html, "<h2 style=\"font-size:118%;margin-top:6px;margin-bottom:2px\">Problem</h2>")
        assertContains(html, "<code style=\"background-color:#e6e8ec\">git push</code>")
        assertContains(html, "<pre style=\"background-color:#e6e8ec;padding:6px 8px;margin-top:4px;margin-bottom:4px\">")
        assertContains(html, "<blockquote style=\"margin:4px 0 4px 0;padding-left:8px;border-left:3px solid #cccccc;color:#777777\">")
    }

    @Test
    fun `attributes the converter writes stay, and other tags are left alone`() {
        assertContains(
            styledHtml("<pre><code class=\"language-bash\">a</code></pre><hr><p>b</p>", colors),
            "<code class=\"language-bash\" style=\"background-color:#e6e8ec\">a</code></pre><hr><p>b</p>",
        )
        assertFalse("style" in styledHtml("<p><a href=\"u\">x</a></p>", colors))
    }

    @Test
    fun `a tag already styled throws`() {
        assertThrows<IllegalStateException> { styledHtml("<code style=\"color:red\">x</code>", colors) }
    }
}
