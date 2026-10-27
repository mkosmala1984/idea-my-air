package dev.myair.prompt

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PastedImagesTest {
    private fun transferable(flavor: DataFlavor, data: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(flavor)
        override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
        override fun getTransferData(f: DataFlavor) = data
    }

    @Test
    fun `saves a pasted image as a PNG of the same pixels and mentions it`(@TempDir dir: Path) {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(1, 1, Color.RED.rgb) }
        val mentions = pastedMentions(transferable(DataFlavor.imageFlavor, image), dir.resolve("images"))!!
        val saved = dir.resolve("images").listDirectoryEntries().single()
        assertEquals(listOf("@$saved"), mentions)
        val read = ImageIO.read(saved.toFile())
        assertEquals(3 to 2, read.width to read.height)
        assertEquals(Color.RED.rgb, read.getRGB(1, 1))
    }

    @Test
    fun `mentions copied files where they are, quoting a path with a space`(@TempDir dir: Path) {
        val files = listOf(File("C:/shots/a.png"), File("C:/my shots/b.png"))
        assertEquals(listOf("@${files[0].toPath()}", "@\"${files[1].toPath()}\""), pastedMentions(transferable(DataFlavor.javaFileListFlavor, files), dir))
    }

    @Test
    fun `prunes only the images older than the cutoff, and a missing folder is nothing to prune`(@TempDir dir: Path) {
        val now = Instant.parse("2026-10-27T12:00:00Z")
        val old = dir.resolve("old.png").apply { writeText("x"); setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(31)))) }
        val fresh = dir.resolve("fresh.png").apply { writeText("x"); setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(29)))) }
        val other = dir.resolve("old.txt").apply { writeText("x"); setLastModifiedTime(FileTime.from(now.minus(Duration.ofDays(31)))) }
        assertEquals(listOf(old), pruneImages(dir, now.minus(Duration.ofDays(30))))
        assertEquals(setOf(fresh, other), dir.listDirectoryEntries().toSet())
        assertEquals(emptyList(), pruneImages(dir.resolve("none"), now))
    }

    @Test
    fun `leaves text to the text paste`(@TempDir dir: Path) {
        assertNull(pastedMentions(StringSelection("hello"), dir))
    }
}
