package dev.myair.toolwindow

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.messages.Topic
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JComponent

/** Points added to the fonts of the session lists and the context pane, shared by every window of the IDE. */
object PaneFont {
    private const val KEY = "dev.myair.fontSize"
    val RANGE = -4..12
    private val TOPIC = Topic.create("My Air font size", Runnable::class.java)

    var points: Int
        get() = PropertiesComponent.getInstance().getInt(KEY, 0)
        set(value) {
            require(value in RANGE) { "Font size $value outside $RANGE" }
            PropertiesComponent.getInstance().setValue(KEY, value, 0)
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).run()
        }

    /** Runs [onChange] after every change of [points], until [parent] is disposed. */
    fun subscribe(parent: Disposable, onChange: () -> Unit) =
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(TOPIC, Runnable(onChange))

    /** [font] grown by [points]. */
    fun grow(font: Font): Font = font.deriveFont(font.size2D + JBUIScale.scale(points.toFloat()))

    /** Grows the font of [root] and of every component in it; for freshly built components, as it adds to what they have. */
    fun growAll(root: JComponent) {
        if (points == 0) return
        UIUtil.uiTraverser(root).filter(JComponent::class.java).forEach { it.font = grow(it.font) }
    }
}
