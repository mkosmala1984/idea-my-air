package dev.myair.service

import dev.myair.claude.ClaudeHomeWatcher
import dev.myair.claude.ClaudeSession
import dev.myair.claude.ClaudeSessionScanner
import dev.myair.claude.LiveSession
import dev.myair.claude.LiveSessionRegistry
import dev.myair.store.Profile
import java.io.Closeable
import java.nio.file.Path
import kotlin.io.path.isDirectory

/** The sessions of every profile, each stamped with its profile, newest first; one id in two profiles throws. */
fun mergeSessions(byProfile: Map<Profile, List<ClaudeSession>>): List<ClaudeSession> {
    requireUniqueIds(byProfile.mapValues { (_, sessions) -> sessions.map { it.id } })
    return byProfile.flatMap { (profile, sessions) -> sessions.map { it.copy(profile = profile.name) } }.sortedByDescending { it.lastMessage }
}

/** The live entries of every profile, each stamped with its profile; one id in two profiles throws. */
fun mergeLive(byProfile: Map<Profile, Map<String, LiveSession>>): Map<String, LiveSession> {
    requireUniqueIds(byProfile.mapValues { it.value.keys })
    return byProfile.flatMap { (profile, live) -> live.map { (id, entry) -> id to entry.copy(profile = profile.name) } }.toMap()
}

private fun requireUniqueIds(ids: Map<Profile, Collection<String>>) {
    val dirOf = HashMap<String, Path>()
    for ((profile, list) in ids) for (id in list) {
        dirOf.put(id, profile.dir)?.let { error("session $id is in $it and ${profile.dir}") }
    }
}

/** Throws naming `<dir>/projects` or `<dir>/sessions` when [profile]'s directory lacks it. */
fun requireProfileDirs(profile: Profile) {
    for (name in listOf("projects", "sessions")) {
        val dir = profile.dir.resolve(name)
        check(dir.isDirectory()) { "profile ${profile.name} has no directory $dir" }
    }
}

/**
 * A scanner and a registry per profile of [profiles], and one watcher over [watched] and every profile's `sessions/` and
 * `projects/`, calling [onChange]. [close] stops the watcher and saves the scanners.
 */
class ProfileReaders(
    val profiles: List<Profile>, cacheDir: Path, jar: Path, watched: List<Path>, onChange: () -> Unit,
) : Closeable {
    val scanners = profiles.associateWith { ClaudeSessionScanner(it.dir.resolve("projects"), ClaudeSessionScanner.Saved.forJar(cacheDir, jar, it)) }
    val registries = profiles.associateWith { LiveSessionRegistry(it.dir.resolve("sessions")) }

    // Last, so a failure above leaves no watcher thread behind.
    private val watcher = ClaudeHomeWatcher(profiles.map { it.dir.resolve("sessions") } + watched, profiles.map { it.dir.resolve("projects") }, onChange = onChange)

    override fun close() {
        watcher.close()
        scanners.values.forEach { it.save() }
    }
}
