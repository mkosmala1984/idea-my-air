package dev.myair.actions

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.assertEquals

class InstallMyAirBuildTest {
    @TempDir
    lateinit var dir: Path

    private val dist get() = dir.resolve("build/distributions")

    @Test
    fun `a project without a build or with another plugin's zip has no My Air build`() {
        assertEquals(null, myAirBuild(dir))
        dist.createDirectories().resolve("other-1.0.zip").createFile()
        assertEquals(null, myAirBuild(dir))
    }

    @Test
    fun `the one my-air zip is the build`() {
        val zip = dist.createDirectories().resolve("my-air-0.1.0.zip").createFile()
        assertEquals(zip, myAirBuild(dir))
    }

    @Test
    fun `two my-air zips throw rather than one being picked`() {
        dist.createDirectories().resolve("my-air-0.1.0.zip").createFile()
        dist.resolve("my-air-0.2.0.zip").createFile()
        assertThrows<IllegalStateException> { myAirBuild(dir) }
    }
}
