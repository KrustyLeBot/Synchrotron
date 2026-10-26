package app.synchrotron.sync

import java.io.File

/** [number] is bisync's conflict counter (`name.ext.conflictN`). */
data class ConflictFile(val file: File, val originalName: String, val number: Int)

/**
 * Bisync renames conflicting copies `name.ext.conflictN`, numbering them in order of appearance: with
 * no winner, the phone copy gets N and the Proton copy N + 1; with a winner (newer, larger…) only the
 * loser is renamed and the winner keeps the original name. So the number alone never tells the side.
 */
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

    /** Where a copy most likely comes from, given the other copies of the same file. */
    fun origin(copy: ConflictFile, group: List<ConflictFile>): String = when {
        group.any { it.number == copy.number + 1 } -> "version du téléphone"
        group.any { it.number == copy.number - 1 } -> "version de Proton"
        else -> "version écartée"
    }
}

object ConflictResolver {
    /** Puts [chosen] under the original name (null keeps the current file) and deletes every conflict copy. */
    fun keep(root: File, group: List<ConflictFile>, chosen: ConflictFile?) {
        if (chosen != null) chosen.file.copyTo(File(root, chosen.originalName), overwrite = true)
        group.forEach { it.file.delete() }
    }
}
