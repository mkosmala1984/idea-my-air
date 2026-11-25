package dev.myair.canvasview

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CompletableDeferred
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The canvas page (`canvas/index.html`, built from `canvas-web/`) in a JCEF browser. [show] sends a frame and [validate]
 * a document to check, both queued until the page has loaded; what the page posts arrives at [onMessage] on the EDT. The page follows the IDE's theme. The page
 * makes no request of its own (its Content-Security-Policy is `default-src 'none'`), and a navigation the user starts
 * in it (a link) is refused: links open only through `@OpenUrl`.
 */
class CanvasBrowser(parent: Disposable, private val onMessage: (CanvasMessage) -> Unit) {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var loaded = false
    private var current: CanvasFrame? = null

    /** The [validate] calls waiting for their answer, by id; the scripts of those made before the page loaded. EDT only. */
    private val pending = HashMap<String, CompletableDeferred<CanvasMessage.Validated>>()
    private val queuedValidations = ArrayList<String>()

    /** Whether the page has loaded, so a [validate] is answered without waiting for the load (about a second). */
    val isLoaded: Boolean get() = loaded

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, browser)
        Disposer.register(browser, query)
        query.addHandler { json ->
            // The CEF thread; a message the plugin cannot read is logged, never thrown into CEF.
            val message = runCatching { CanvasMessage.parse(json) }.onFailure { thisLogger().warn(it) }.getOrNull()
            if (message != null) UIUtil.invokeLaterIfNeeded {
                if (browser.isDisposed) return@invokeLaterIfNeeded
                if (message is CanvasMessage.Validated) pending.remove(message.id)?.complete(message)
                onMessage(message)
            }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                cefBrowser.executeJavaScript(connectScript(query.inject("json")), cefBrowser.url, 0)
                UIUtil.invokeLaterIfNeeded {
                    loaded = true
                    current?.let(::send)
                    queuedValidations.forEach(::run)
                    queuedValidations.clear()
                }
            }
        }, browser.cefBrowser)
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean) = userGesture
        }, browser.cefBrowser)
        ApplicationManager.getApplication().messageBus.connect(browser).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            current?.let { show(it.copy(dark = dark())) }
        })
        Disposer.register(browser) {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
        browser.loadHTML(page, PAGE_URL)
    }

    /**
     * Has the page parse [openui] without showing it ([validateScript]); the answer completes the result on the EDT. It is
     * cancelled when the browser is disposed first. Call on the EDT, with an [id] not used before.
     */
    fun validate(id: String, openui: String): CompletableDeferred<CanvasMessage.Validated> {
        val answer = CompletableDeferred<CanvasMessage.Validated>()
        if (browser.isDisposed) return answer.also { it.cancel() }
        check(pending.put(id, answer) == null) { "validation $id is pending already" }
        val script = validateScript(id, openui)
        if (loaded) run(script) else queuedValidations += script
        return answer
    }

    /** Forgets the [validate] call [id], whose caller stopped waiting. */
    fun forget(id: String) {
        pending.remove(id)
    }

    private fun run(script: String) = browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)

    /** Shows [frame] now, or once the page has loaded. */
    fun show(frame: CanvasFrame) {
        current = frame
        if (loaded) send(frame)
    }

    private fun send(frame: CanvasFrame) {
        run(showScript(frame))
        // The off-screen browser can leave parts of the previous frame painted; the page forces a full repaint too.
        for (delay in REPAINT_DELAYS_MS) Timer(delay) { if (!browser.isDisposed) browser.component.repaint() }.apply { isRepeats = false; start() }
    }

    companion object {
        private const val PAGE_URL = "https://myair.canvas/"
        private val REPAINT_DELAYS_MS = intArrayOf(150, 600)

        /** The page, read once: 4 MB of inlined script and styles. */
        private val page: String by lazy {
            checkNotNull(CanvasBrowser::class.java.getResourceAsStream("/canvas/index.html")) { "canvas/index.html missing; run npm run build in canvas-web" }
                .use { String(it.readAllBytes(), Charsets.UTF_8) }
        }

        fun dark() = !JBColor.isBright()
    }
}
