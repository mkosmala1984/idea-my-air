package dev.myair.canvasview

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import dev.myair.canvas.CanvasService
import dev.myair.canvas.editInFrame
import dev.myair.canvas.moveInFrame
import dev.myair.canvas.setState
import dev.myair.store.Canvas
import dev.myair.store.Frame
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JPanel

/** The frame id the page is given while the canvas has no frame. */
internal const val EMPTY_FRAME_ID = "none"

// Pushed while the canvas has no frame: the page itself starts with the spike's sample, meant for a plain browser.
private const val EMPTY_DOCUMENT =
    "root = Stack([TextContent(\"Nothing is shown on this canvas yet. Ask its manager in the terminal for a result.\", \"small\")])"

/**
 * The left side of a canvas tab: a toolbar (the frame's title, the problems the page reported, `n / m` with Previous and
 * Next Frame, Copy OpenUI Source) above the canvas page ([CanvasBrowser]), or, where JCEF is not supported, above the
 * frame's OpenUI source. [show] selects the newest frame when a new one arrives and keeps the user's choice otherwise
 * ([selectedFrame]); a frame is pushed to the page only when its id, text or the theme changed, as the page keeps its
 * form state itself and reports it back ([CanvasMessage.State], stored in the frame). Clicks run here: [send] submits a
 * message to the manager's terminal (false while it cannot), and files open only under [roots] ([canvasFileTarget]).
 */
class CanvasPanel(
    private val project: Project,
    private val folderId: String,
    parent: Disposable,
    private val send: (String) -> Boolean,
    private val roots: () -> List<Path>,
) : JPanel(BorderLayout()) {
    private val title = JBLabel()
    private val counter = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val problems = JBLabel().apply { isVisible = false }
    private val source = JBTextArea().apply { isEditable = false; font = JBUI.Fonts.create("Monospaced", font.size) }

    private var canvas: Canvas? = null
    private var selection: String? = null
    private var newest: String? = null
    /** The id, text and theme last pushed to the page. */
    private var pushed: Triple<String, String, Boolean>? = null
    private val parsed = HashMap<String, CanvasMessage.Parsed>()
    private val rendered = HashMap<String, List<CanvasError>>()
    /** The page's script errors since the last push. */
    private val scriptErrors = ArrayList<String>()
    // After the state it reports into; it calls back only later, on the EDT.
    private val browser: CanvasBrowser? = if (JBCefApp.isSupported()) CanvasBrowser(parent, ::received) else null

    private val previous = frameAction("Previous Frame", AllIcons.Actions.Back, { index() > 0 }) { step(-1) }
    private val next = frameAction("Next Frame", AllIcons.Actions.Forward, { index().let { it >= 0 && it < frames().size - 1 } }) { step(1) }
    private val copy = frameAction("Copy OpenUI Source", AllIcons.Actions.Copy, { selected() != null }) {
        selected()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it.openui)) }
    }
    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar("MyAirCanvas", DefaultActionGroup(previous, next, Separator.create(), copy), true)

    init {
        browser?.let { CanvasRenderers.getInstance().register(it, folderId, parent) }
        toolbar.targetComponent = this
        val east = JPanel(HorizontalLayout(JBUI.scale(8))).apply {
            isOpaque = false
            add(problems)
            add(counter)
            add(toolbar.component)
        }
        val bar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(0, 8, 0, 0))
            add(title, BorderLayout.CENTER)
            add(east, BorderLayout.EAST)
        }
        add(bar, BorderLayout.NORTH)
        add(browser?.component ?: withoutBrowser(), BorderLayout.CENTER)
    }

    private fun withoutBrowser() = JPanel(BorderLayout()).apply {
        add(JBLabel("This IDE cannot show web pages (JCEF is not supported), so the canvas shows each frame's OpenUI source. The terminal works as usual.")
            .apply { border = JBUI.Borders.empty(8) }, BorderLayout.NORTH)
        add(JBScrollPane(source), BorderLayout.CENTER)
    }

    /** Shows [result], the canvas as read now; one that cannot be read says so in the toolbar and leaves the frame shown. */
    fun show(result: Result<Canvas>) {
        val now = result.getOrElse {
            title.text = "Cannot read the canvas: ${it.message ?: it}"
            title.toolTipText = title.text
            title.foreground = NamedColorUtil.getErrorForeground()
            return
        }
        canvas = now
        selection = selectedFrame(selection, newest, now)
        newest = now.newest?.id
        display()
    }

    private fun frames(): List<Frame> = canvas?.frames.orEmpty()

    private fun index() = frames().indexOfFirst { it.id == selection }

    private fun selected(): Frame? = frames().getOrNull(index())

    private fun step(by: Int) {
        selection = frames()[index() + by].id
        display()
    }

    private fun display() {
        val frame = selected()
        title.text = frame?.let { it.title ?: it.id } ?: "No frames yet"
        title.toolTipText = frame?.let { "Frame ${it.id}" + (it.author?.let { a -> ", shown by session $a" } ?: "") + ", ${it.created}" }
        title.foreground = UIUtil.getLabelForeground()
        counter.text = if (frame == null) "" else "${index() + 1} / ${frames().size}"
        if (browser != null) {
            val id = frame?.id ?: EMPTY_FRAME_ID
            val text = frame?.openui ?: EMPTY_DOCUMENT
            val key = Triple(id, text, CanvasBrowser.dark())
            if (key != pushed) {
                pushed = key
                scriptErrors.clear()
                browser.show(CanvasFrame(id, text, key.third, frame?.state))
            }
        } else if (source.text != frame?.openui.orEmpty()) {
            source.text = frame?.openui.orEmpty()
            source.caretPosition = 0
        }
        showProblems()
        toolbar.updateActionsAsync()
    }

    private fun showProblems() {
        val id = pushed?.first
        val found = id?.let { canvasProblems(parsed[it], rendered[it].orEmpty(), scriptErrors) }
        problems.isVisible = found != null
        if (found == null) return
        problems.text = found.summary
        problems.icon = if (found.summary.contains("error")) AllIcons.General.Error else AllIcons.General.Warning
        problems.toolTipText = "<html>" + found.lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) } + "</html>"
    }

    private fun received(message: CanvasMessage) {
        when (message) {
            is CanvasMessage.Ready, is CanvasMessage.Validated -> {}
            is CanvasMessage.Parsed -> parsed[message.frame] = message
            is CanvasMessage.Errors -> rendered[message.frame] = message.errors
            is CanvasMessage.ScriptError -> scriptErrors += message.message
            is CanvasMessage.State -> storeState(message)
            is CanvasMessage.Action -> act(message)
            is CanvasMessage.Move -> move(message)
            is CanvasMessage.Edit -> editBoard(message)
        }
        showProblems()
    }

    /** Keeps a frame's form state in `canvas.json` (the page debounces it), so a restored tab gets it back. */
    private fun storeState(message: CanvasMessage.State) {
        if (frames().none { it.id == message.frame }) return
        try {
            CanvasService.getInstance().update(folderId) { c -> if (c.frames.any { it.id == message.frame }) setState(c, message.frame, message.state) else c }
        } catch (e: Exception) {
            // A canvas.json broken since the last read: the next read shows it as a failure.
            thisLogger().warn("Cannot store the state of frame ${message.frame} of canvas $folderId", e)
        }
    }

    /** Writes where the user dragged a board element into its frame's document, so the agent and a reload see it. */
    private fun move(message: CanvasMessage.Move) {
        if (frames().none { it.id == message.frame }) return
        try {
            CanvasService.getInstance().update(folderId) { c ->
                if (c.frames.any { it.id == message.frame }) moveInFrame(c, message.frame, message.element, message.x, message.y, message.w, message.h) else c
            }
        } catch (e: IllegalArgumentException) {
            notify("The move of ${message.element} was not saved: ${e.message}")
        } catch (e: Exception) {
            thisLogger().warn("Cannot store the move of ${message.element} in frame ${message.frame} of canvas $folderId", e)
        }
    }

    /** Writes what the user changed on a board (added, connected, removed, retitled) into the frame's document. */
    private fun editBoard(message: CanvasMessage.Edit) {
        if (frames().none { it.id == message.frame }) return
        try {
            CanvasService.getInstance().update(folderId) { c ->
                if (c.frames.any { it.id == message.frame }) editInFrame(c, message.frame, message.edit) else c
            }
        } catch (e: IllegalArgumentException) {
            notify("The change on the board was not saved: ${e.message}")
        } catch (e: Exception) {
            thisLogger().warn("Cannot store a board change in frame ${message.frame} of canvas $folderId", e)
        }
    }

    private fun act(action: CanvasMessage.Action) {
        val author = frames().firstOrNull { it.id == action.frame }?.author
        when (val command = commandOf(action, folderId, author)) {
            is CanvasCommand.Send ->
                if (!send(command.text)) notify("The click was not sent: the canvas's manager is not running in this tab. Start it, then click again.")
            is CanvasCommand.Browse -> BrowserUtil.browse(command.url)
            is CanvasCommand.OpenFile -> openFile(command)
            is CanvasCommand.Copy -> CopyPasteManager.getInstance().setContents(StringSelection(command.text))
            is CanvasCommand.Unknown -> notify("The canvas asked for the action '${command.type}', which My Air does not run.")
        }
    }

    private fun openFile(command: CanvasCommand.OpenFile) {
        val roots = roots()
        if (roots.isEmpty()) return notify("The canvas has no directory to open files in, so it cannot open ${command.path}.")
        val target = canvasFileTarget(command.path, roots)
            ?: return notify("The canvas opens files only under ${roots.joinToString(" or ")}, not ${command.path}.")
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.takeUnless { it.isDirectory }
            ApplicationManager.getApplication().invokeLater({
                if (file == null) notify("The canvas asked to open $target, which is not a file.")
                else (command.line?.let { OpenFileDescriptor(project, file, it - 1, 0) } ?: OpenFileDescriptor(project, file)).navigate(true)
            }, project.disposed)
        }
    }

    private fun notify(text: String) =
        NotificationGroupManager.getInstance().getNotificationGroup("My Air").createNotification(text, NotificationType.WARNING).notify(project)

    private fun frameAction(text: String, icon: javax.swing.Icon, enabled: () -> Boolean, run: () -> Unit) = object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
