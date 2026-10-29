package app.synchrotron.sync

import app.synchrotron.data.PairEntity
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.rclone.RcloneException
import app.synchrotron.remote.Backend
import app.synchrotron.remote.Backends
import app.synchrotron.remote.ServerRemote

data class Preview(val toUpload: Int, val toDownload: Int, val different: Int, val identical: Int)

/** What the very first sync of a pair is going to do, computed by comparing both listings. */
suspend fun computePreview(context: android.content.Context, engine: RcloneEngine, pair: PairEntity): Preview =
    when (Backends.current(context)) {
        // The server gives each file's MD5: identical files are told apart exactly, without downloading.
        Backend.SERVER -> ServerRemote(context).preview(pair, SyncRunner.patterns(pair))
        Backend.PROTON -> protonPreview(engine, pair)
    }

private suspend fun protonPreview(engine: RcloneEngine, pair: PairEntity): Preview = engine.session {
    val filter = SyncRunner.filterJson(pair)
    val local = engine.listEntries(pair.localPath, recurse = true, filter = filter)
        .filter { !it.isDir }.associateBy { it.path }
    val remote = try {
        engine.listEntries("${RcloneEngine.REMOTE}:${pair.remotePath}", recurse = true, filter = filter)
            .filter { !it.isDir }.associateBy { it.path }
    } catch (e: RcloneException) {
        if (e.message.orEmpty().contains("not found", ignoreCase = true)) emptyMap() else throw e
    }
    var different = 0
    var identical = 0
    for ((path, l) in local) {
        val r = remote[path] ?: continue
        // Proton reports the clear-text size; a size mismatch is a reliable sign of different content.
        if (l.size == r.size) identical++ else different++
    }
    Preview(
        toUpload = local.keys.count { it !in remote },
        toDownload = remote.keys.count { it !in local },
        different = different,
        identical = identical,
    )
}
