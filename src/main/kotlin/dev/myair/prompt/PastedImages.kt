package dev.myair.prompt

import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories

/** Where pasted images are saved, for `claude` to read when the session starts. */
val PASTED_IMAGES: Path = Path.of(System.getProperty("user.home"), ".my-air", "images")

/**
 * What a paste of [content] puts in the message: an image saved as a PNG in [dir], or copied files, each as an `@`
 * mention, which `claude` attaches at once (a bare path it reads only with a tool call). Null when it holds neither,
 * for the text paste to take.
 */
fun pastedMentions(content: Transferable, dir: Path): List<String>? = when {
    content.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ->
        (content.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).map { mention((it as File).toPath()) }
    content.isDataFlavorSupported(DataFlavor.imageFlavor) -> listOf(mention(savePng(content.getTransferData(DataFlavor.imageFlavor) as Image, dir)))
    else -> null
}

/** `@path`, quoted when the path has a space. */
fun mention(path: Path) = path.toString().let { if (it.any(Char::isWhitespace)) "@\"$it\"" else "@$it" }

private fun savePng(image: Image, dir: Path): Path {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    check(width > 0 && height > 0) { "pasted image has no size: ${width}x$height" }
    val png = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    png.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
    dir.createDirectories()
    return dir.resolve("${UUID.randomUUID()}.png").also { check(ImageIO.write(png, "png", it.toFile())) { "no PNG writer" } }
}
