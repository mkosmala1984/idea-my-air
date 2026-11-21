package dev.myair.store

import com.google.gson.JsonParser
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText

/** One reviewer of a profile: [prompt] is its first message; a null [model] or [effort] leaves its flag out; a null [profile] is `default`. */
data class Reviewer(val name: String, val prompt: String, val model: String? = null, val effort: String? = null, val profile: String? = null) {
    init {
        require(name.isNotBlank() && prompt.isNotBlank()) { "reviewer $name has a blank name or prompt" }
        require(effort == null || effort in EFFORTS) { "effort of reviewer $name is $effort, not one of $EFFORTS" }
    }
}

data class ReviewProfile(val name: String, val reviewers: List<Reviewer>)

fun parseReviewProfiles(text: String, source: Path = Path.of("review-profiles.json")): List<ReviewProfile> {
    val root = Keys(JsonParser.parseString(text), "profiles file", source, setOf("profiles"))
    return root.array("profiles").map { p ->
        val profile = Keys(p, "profile", source, setOf("name", "reviewers"))
        ReviewProfile(
            profile.string("name"),
            profile.array("reviewers").map {
                Keys(it, "reviewer", source, setOf("name", "prompt"), setOf("model", "effort", "profile")).run {
                    Reviewer(string("name"), string("prompt"), if (has("model")) string("model") else null, if (has("effort")) string("effort") else null, if (has("profile")) string("profile") else null)
                }
            }.also { rs -> check(rs.map { it.name }.toSet().size == rs.size) { "$source: reviewer names repeat in ${profile.string("name")}" } },
        )
    }.also { ps -> check(ps.map { it.name }.toSet().size == ps.size) { "$source: profile names repeat: ${ps.map { it.name }}" } }
}

/** `~/.my-air/review-profiles.json`, parsed again only when the file's time or size changes. */
class ReviewProfileStore(val file: Path) {
    private var cached: Pair<Pair<FileTime, Long>, Result<List<ReviewProfile>>>? = null

    /** A failure carries what is wrong with the file; a missing file means no profiles. */
    @Synchronized
    fun read(): Result<List<ReviewProfile>> {
        if (!file.exists()) return Result.success(emptyList())
        val stamp = file.getLastModifiedTime() to file.fileSize()
        cached?.takeIf { it.first == stamp }?.let { return it.second }
        return runCatching { parseReviewProfiles(file.readText(), file) }.also { cached = stamp to it }
    }

    companion object {
        val INSTANCE = ReviewProfileStore(Homes.myAir.resolve("review-profiles.json"))
    }
}
