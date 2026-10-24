package dev.myair

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty

/** Settings of this IDE, shared by all its projects. */
@Service
@State(name = "MyAirSettings", storages = [Storage("myAir.xml")])
class MyAirSettings : SimplePersistentStateComponent<MyAirSettings.Options>(Options()) {
    class Options : BaseState() {
        /** Where new worktrees go; a relative path resolves against the root of the repository's main checkout. */
        var worktreesDir by string(".claude/worktrees")
    }

    val worktreesDir: String get() = checkNotNull(state.worktreesDir) { "worktrees directory is not set" }

    companion object {
        fun getInstance(): MyAirSettings = service()
    }
}

/** Settings › Tools › My Air. */
class MyAirConfigurable : BoundConfigurable("My Air") {
    override fun createPanel() = panel {
        row("Worktrees directory:") {
            textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Worktrees Directory"))
                .bindText(MyAirSettings.getInstance().state::worktreesDir.toNonNullableProperty(""))
                .columns(COLUMNS_LARGE)
                .validationOnApply { if (it.text.isBlank()) error("Enter a directory") else null }
                .comment("New Claude Session in Worktree creates &lt;directory&gt;/&lt;id&gt;. A relative path resolves against the root of the repository's main checkout.")
        }
    }
}
