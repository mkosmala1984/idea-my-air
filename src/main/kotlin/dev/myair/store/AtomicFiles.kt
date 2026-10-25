package dev.myair.store

import com.sun.jna.Memory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Writes to a temp name and moves it into place, so a reader never sees half a file. `Files.move` (MoveFileEx) refuses
 * to replace a file anyone holds open, as the taskbar app and other IDEs do while reading; a POSIX-semantics rename
 * replaces it anyway, leaving readers that share delete access on the old contents.
 */
fun writeAtomically(target: Path, text: String) {
    val temp = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.tmp")
    temp.writeText(text)
    try {
        // A virus scanner or indexer may hold a just-written file without sharing delete for a moment; Windows then
        // refuses the rename with access denied or a sharing violation, as it does to the IDE's own temp files here.
        for (attempt in 1..RENAME_ATTEMPTS) {
            val error = rename(temp, target) ?: return
            check(error in TRANSIENT_ERRORS && attempt < RENAME_ATTEMPTS) { "cannot move $temp to $target: error $error" }
            Thread.sleep(50)
        }
    } finally {
        temp.deleteIfExists()
    }
}

/** Null when [temp] took the place of [target], else the Windows error code. */
private fun rename(temp: Path, target: Path): Int? {
    val kernel32 = Kernel32.INSTANCE
    val handle = kernel32.CreateFile(temp.toString(), WinNT.DELETE, WinNT.FILE_SHARE_READ or WinNT.FILE_SHARE_WRITE or WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING, WinNT.FILE_ATTRIBUTE_NORMAL, null)
    if (handle == WinBase.INVALID_HANDLE_VALUE) return kernel32.GetLastError()
    try {
        // FILE_RENAME_INFO on x64: Flags at 0, RootDirectory at 8, FileNameLength at 16, FileName at 20.
        val name = target.toAbsolutePath().toString()
        val info = Memory(20L + 2 * (name.length + 1)).apply {
            clear()
            setInt(0, FILE_RENAME_FLAG_REPLACE_IF_EXISTS or FILE_RENAME_FLAG_POSIX_SEMANTICS)
            setInt(16, 2 * name.length)
            setWideString(20, name)
        }
        return if (kernel32.SetFileInformationByHandle(handle, FILE_RENAME_INFO_EX, info, WinDef.DWORD(info.size()))) null else kernel32.GetLastError()
    } finally {
        kernel32.CloseHandle(handle)
    }
}

private const val RENAME_ATTEMPTS = 5
private val TRANSIENT_ERRORS = setOf(WinError.ERROR_ACCESS_DENIED, WinError.ERROR_SHARING_VIOLATION)

private const val FILE_RENAME_INFO_EX = 22
private const val FILE_RENAME_FLAG_REPLACE_IF_EXISTS = 1
private const val FILE_RENAME_FLAG_POSIX_SEMANTICS = 2
