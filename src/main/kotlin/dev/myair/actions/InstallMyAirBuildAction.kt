package dev.myair.actions

import com.intellij.ide.plugins.PluginInstaller
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAwareAction
import dev.myair.editor.projectBase
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Installs the My Air build of the open project ([myAirBuild]) over the running My Air and restarts the IDE without asking,
 * as Settings › Plugins › Install Plugin from Disk would after a restart: the platform swaps the files on startup.
 * Shown only in a project that has such a build.
 */
class InstallMyAirBuildAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { myAirBuild(projectBase(it)) } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val zip = checkNotNull(myAirBuild(projectBase(checkNotNull(e.project)))) { "the My Air build is gone" }
        val self = checkNotNull(PluginManagerCore.getPlugin(PluginId.getId(MY_AIR_ID))) { "$MY_AIR_ID is not loaded" }
        val installed = self.pluginPath
        // The startup script deletes the installed folder and unzips the build's root folder next to it: they must be one.
        val root = PluginInstaller.rootEntryName(zip)
        check(root == installed.fileName.toString()) { "$zip unpacks to $root, not to ${installed.fileName} of $installed" }
        PluginInstaller.installAfterRestart(self, zip, installed, false)
        ApplicationManagerEx.getApplicationEx().restart(true)
    }
}

private const val MY_AIR_ID = "dev.myair"

/** The `my-air-*.zip` that `./gradlew buildPlugin` leaves in [dir]'s `build/distributions`; null when there is none. */
fun myAirBuild(dir: Path): Path? {
    val dist = dir.resolve("build/distributions")
    if (!dist.isDirectory()) return null
    val zips = dist.listDirectoryEntries("my-air-*.zip")
    check(zips.size <= 1) { "several My Air builds in $dist: $zips" }
    return zips.singleOrNull()
}
