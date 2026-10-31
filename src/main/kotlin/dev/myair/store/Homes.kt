package dev.myair.store

import java.nio.file.Path

/** The directories under the user's home this plugin reads: `claude`'s own, and its own ([TabStore] and the rest). */
object Homes {
    val claude: Path = Path.of(System.getProperty("user.home"), ".claude")
    val myAir: Path = Path.of(System.getProperty("user.home"), ".my-air")
}
