package dev.myair.actions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BranchNamesTest {
    @Test
    fun `the ticket key is the first Jira key in the text`() {
        assertEquals("TR-1234", ticketKey("TR-1234"))
        assertEquals("TR-7000", ticketKey("Fix the flex search, see TR-7000 and PW-12"))
        assertEquals("AB2-5", ticketKey("(AB2-5)"))
        assertNull(ticketKey("fix the login timeout, utf-8 only"))
        assertNull(ticketKey("tr-1234"))
    }

    @Test
    fun `the key goes before the slug, which stands alone without one`() {
        assertEquals("TR-1234-fix-login-timeout", branchName("TR-1234", "fix-login-timeout\n"))
        assertEquals("fix-login-timeout", branchName(null, "  fix-login-timeout "))
    }

    @Test
    fun `an answer that is not one slug line fails with the answer`() {
        for (answer in listOf("", "Fix-Login", "fix login", "fix-login\nsecond line", "`fix-login`", "fix--login", "-fix")) {
            val error = assertThrows<IllegalStateException> { branchName("TR-1", answer) }
            assert(answer in error.message!!) { error.message!! }
        }
    }

    @Test
    fun `the prompt holds the task`() {
        assert("TR-7000 flex search" in namingPrompt("TR-7000 flex search"))
    }
}
