package dev.myair.canvasview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.ThreadingAssertions

/** A live canvas page as [chooseValidator] sees it: the [folder] it shows and whether it [loaded]. */
data class RendererSlot<B>(val browser: B, val folder: String, val loaded: Boolean)

/**
 * The page to check a frame of canvas [folderId] with: a loaded page of that canvas, else any loaded page, else any
 * page (it answers once loaded), else null. Every page has the same library, so any page validates any frame.
 */
fun <B> chooseValidator(slots: List<RendererSlot<B>>, folderId: String): B? =
    (slots.firstOrNull { it.loaded && it.folder == folderId } ?: slots.firstOrNull { it.loaded } ?: slots.firstOrNull())?.browser

/**
 * The live canvas pages of this IDE, each registered by the tab that made it until that tab is disposed; what
 * `my_air_canvas_show` checks a frame with ([validator]). EDT only.
 */
@Service(Service.Level.APP)
class CanvasRenderers {
    private val browsers = LinkedHashMap<CanvasBrowser, String>()

    /** Offers [browser], which shows canvas [folderId], until [parent] is disposed. */
    fun register(browser: CanvasBrowser, folderId: String, parent: Disposable) {
        ThreadingAssertions.assertEventDispatchThread()
        browsers[browser] = folderId
        Disposer.register(parent) { browsers.remove(browser) }
    }

    /** The page to check a frame of [folderId] with ([chooseValidator]); null when no canvas page is open in this IDE. */
    fun validator(folderId: String): CanvasBrowser? {
        ThreadingAssertions.assertEventDispatchThread()
        return chooseValidator(browsers.map { (b, folder) -> RendererSlot(b, folder, b.isLoaded) }, folderId)
    }

    companion object {
        fun getInstance(): CanvasRenderers = ApplicationManager.getApplication().getService(CanvasRenderers::class.java)
    }
}
