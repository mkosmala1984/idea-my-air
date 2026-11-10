package dev.myair.toolwindow

import dev.myair.actions.FileChange
import dev.myair.actions.FileChange.Kind
import dev.myair.actions.FileChange.Stage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class ChangeTreeTest {
    private fun change(path: String, added: Int = 1, removed: Int = 0, stage: Stage = Stage.PUSHED) = FileChange(path, Kind.MODIFIED, added, removed, stage)

    /** The tree as indented lines: a folder with its counts, a file with its index. */
    private fun lines(node: ChangeNode, depth: Int = 0): List<String> = when (node) {
        is ChangeDir -> listOf("  ".repeat(depth) + "${node.name}/ ${node.files} +${node.added} -${node.removed}") +
            node.children.flatMap { lines(it, depth + 1) }
        is ChangeFile -> listOf("  ".repeat(depth) + "${node.name} #${node.index}")
    }

    @Test
    fun `folders come before files, each by name, and a chain of single folders is merged`() {
        val changes = listOf(
            change("README.md"),
            change("rtm-cloud/src/main/java/com/deviniti/rtm/A.java", 5, 2),
            change("rtm-cloud/src/main/java/com/deviniti/rtm/b/B.java", 3),
            change("rtm-cloud/src/test/T.java", 1, 1),
            change("forge/x.ts"),
        )
        assertEquals(
            listOf(
                "/ 5 +11 -3",
                "  forge/ 1 +1 -0",
                "    x.ts #4",
                "  rtm-cloud/src/ 3 +9 -3",
                "    main/java/com/deviniti/rtm/ 2 +8 -2",
                "      b/ 1 +3 -0",
                "        B.java #2",
                "      A.java #1",
                "    test/ 1 +1 -1",
                "      T.java #3",
                "  README.md #0",
            ),
            lines(changeTree(changes, ChangesFilter.ALL)),
        )
    }

    @Test
    fun `a folder holding a file and a folder is not merged`() {
        assertEquals(
            listOf("/ 2 +2 -0", "  a/ 2 +2 -0", "    b/ 1 +1 -0", "      c.txt #1", "    a.txt #0"),
            lines(changeTree(listOf(change("a/a.txt"), change("a/b/c.txt")), ChangesFilter.ALL)),
        )
    }

    @Test
    fun `a filter keeps the files of its stages, each with its index in the whole list`() {
        val changes = listOf(change("a/pushed.txt"), change("a/local.txt", stage = Stage.COMMITTED), change("b/edited.txt", stage = Stage.UNCOMMITTED))
        assertEquals(listOf("/ 2 +2 -0", "  a/ 1 +1 -0", "    local.txt #1", "  b/ 1 +1 -0", "    edited.txt #2"), lines(changeTree(changes, ChangesFilter.NOT_PUSHED)))
        assertEquals(listOf("/ 1 +1 -0", "  b/ 1 +1 -0", "    edited.txt #2"), lines(changeTree(changes, ChangesFilter.UNCOMMITTED)))
        assertEquals(3, changeTree(changes, ChangesFilter.ALL).files)
    }

    @Test
    fun `a test folder starts closed, also inside a merged chain`() {
        assertEquals(false, openByDefault(ChangeDir("test", emptyList())))
        assertEquals(false, openByDefault(ChangeDir("src/test/java", emptyList())))
        assertEquals(false, openByDefault(ChangeDir("__tests__", emptyList())))
        assertEquals(true, openByDefault(ChangeDir("main/java/com/deviniti/rtm", emptyList())))
        assertEquals(true, openByDefault(ChangeDir("testdata", emptyList())))
    }

    @Test
    fun `no changes give an empty root`() = assertEquals(ChangeDir("", emptyList()), changeTree(emptyList(), ChangesFilter.ALL))

    @Test
    fun `a path with an empty segment throws`() {
        assertThrows<IllegalStateException> { changeTree(listOf(change("a//b.txt")), ChangesFilter.ALL) }
    }
}
