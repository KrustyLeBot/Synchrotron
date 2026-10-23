package app.synchrotron.sync

import java.io.File

data class ConflictFile(val file: File, val originalName: String, val side: Int)

/** Bisync names the losing copies `name.ext.conflict1` (local) and `name.ext.conflict2` (Proton). */
object ConflictScanner {
    private val pattern = Regex("""^(.*)\.conflict(\d+)$""")

    fun scan(root: File, limit: Int = 500): List<ConflictFile> {
        val out = ArrayList<ConflictFile>()
        root.walkTopDown().onEnter { out.size < limit }.forEach { f ->
            if (f.isFile) pattern.matchEntire(f.name)?.let {
                out += ConflictFile(f, File(f.parentFile, it.groupValues[1]).relativeTo(root).path, it.groupValues[2].toInt())
            }
        }
        return out
    }
}

object ConflictResolver {
    /** Keeps one side (1 = phone, 2 = Proton) under the original name and deletes the other copies. */
    fun keep(root: File, group: List<ConflictFile>, keepSide: Int) {
        val chosen = group.firstOrNull { it.side == keepSide } ?: return
        val target = File(root, chosen.originalName)
        chosen.file.copyTo(target, overwrite = true)
        group.forEach { it.file.delete() }
    }
}
