package dev.myair.canvas

import com.google.gson.JsonParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class OpenUiMergeTest {
    /** The cases lang-core merged itself (`canvas-web/src/merge/cases.mjs`); read where they lie, as Gradle runs in the project dir. */
    private val cases = JsonParser.parseString(File("canvas-web/src/merge/merge-cases.json").readText()).asJsonArray.map { it.asJsonObject }

    @Test
    fun `every case merges as lang-core's mergeStatements does`() {
        assertEquals(11, cases.size)
        cases.forEach { c ->
            assertEquals(c["merged"].asString, mergeOpenUi(c["existing"].asString, c["patch"].asString).text, c["name"].asString)
        }
    }

    @Test
    fun `the merge names what the patch set and what is gone`() {
        val merge = mergeOpenUi(
            "root = Stack([b])\nb = Board([n, m])\nn = Note(\"n\", {x: 0, y: 0})\nm = Note(\"m\", {rightOf: \"n\"})",
            "b = Board([n, k])\nk = Note(\"k\", {below: \"n\"})\nn = Note(\"n\", {x: 2, y: 0})",
        )
        assertEquals(listOf("b", "k", "n"), merge.set)
        assertEquals(listOf("m"), merge.removed)
        assertEquals("root = Stack([b])\nb = Board([n, k])\nn = Note(\"n\", {x: 2, y: 0})\nk = Note(\"k\", {below: \"n\"})", merge.text)
    }

    @Test
    fun `a patch without statements or one that removes root is refused`() {
        assertEquals(
            "the patch has no statement; write lines of the form name = expression",
            assertFailsWith<IllegalArgumentException> { mergeOpenUi("root = Stack([])", "just text") }.message,
        )
        assertEquals("the patch leaves the document without root", assertFailsWith<IllegalArgumentException> { mergeOpenUi("root = Stack([])", "root = null") }.message)
    }

    @Test
    fun `the revision is a short hash of the text`() {
        assertEquals(12, openUiRev("root = Stack([])").length)
        assertEquals(openUiRev("a"), openUiRev("a"))
        assertNotEquals(openUiRev("a"), openUiRev("b"))
    }
}
