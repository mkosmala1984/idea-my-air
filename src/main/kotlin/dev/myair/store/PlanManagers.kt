package dev.myair.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText

/** The session that manages a plan folder, with the directories it was started in. */
data class PlanManager(val session: String, val cwd: Path, val project: Path)

/** The manager session of each plan folder, by folder id. */
data class PlanManagers(val byFolder: Map<String, PlanManager> = emptyMap()) {
    fun set(folderId: String, manager: PlanManager) = PlanManagers(byFolder + (folderId to manager))

    /** `/clear` and `/resume` give a session a new id: the entry managed by [from] now belongs to [to]. */
    fun moveSession(from: String, to: String) = PlanManagers(byFolder.mapValues { (_, m) -> if (m.session == from) m.copy(session = to) else m })

    fun prune(folderIds: Set<String>) = PlanManagers(byFolder.filterKeys { it in folderIds })

    fun folderOf(sessionId: String): String? = byFolder.entries.firstOrNull { it.value.session == sessionId }?.key

    fun toJson(): String = GsonBuilder().setPrettyPrinting().create().toJson(
        byFolder.mapValues { (_, m) -> mapOf("session" to m.session, "cwd" to m.cwd.toString(), "project" to m.project.toString()) },
    )

    companion object {
        private val KEYS = setOf("session", "cwd", "project")

        fun parse(text: String) = PlanManagers(
            JsonParser.parseString(text).asJsonObject.entrySet().associate { (folder, entry) ->
                val o = entry.asJsonObject
                check(o.keySet() == KEYS) { "unexpected keys in plan manager of $folder: ${o.keySet()}" }
                fun string(key: String): String {
                    check(o[key].isJsonPrimitive && o[key].asJsonPrimitive.isString) { "$key of plan manager $folder is not a string: ${o[key]}" }
                    return o[key].asString
                }
                folder to PlanManager(string("session"), Path.of(string("cwd")), Path.of(string("project")))
            },
        )
    }
}

/** `~/.my-air/plan-managers.json`, shared by every IDE. */
class PlanManagerStore(val file: Path) {
    init {
        file.parent.createDirectories()
    }

    fun read(): PlanManagers = if (file.exists()) PlanManagers.parse(file.readText()) else PlanManagers()

    @Synchronized
    fun update(op: (PlanManagers) -> PlanManagers): PlanManagers {
        val before = read()
        val after = op(before)
        if (after != before) writeAtomically(file, after.toJson())
        return after
    }
}
