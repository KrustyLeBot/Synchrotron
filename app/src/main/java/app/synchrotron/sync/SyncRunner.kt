package app.synchrotron.sync

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import app.synchrotron.data.AppDb
import app.synchrotron.data.HistoryEntity
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncMode
import app.synchrotron.data.SyncStatus
import app.synchrotron.integration.Integration
import app.synchrotron.notify.Notifier
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.rclone.RcloneException
import app.synchrotron.rclone.SessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SyncOptions(
    /** Build the bisync baseline (first sync, or recovery). Never deletes anything. */
    val resync: Boolean = false,
    /** The user confirmed a sync that exceeds the delete safeguard. */
    val force: Boolean = false,
    /** The user confirmed that a deliberately emptied side must be mirrored: [WIPE_REMOTE] or [WIPE_LOCAL]. */
    val wipe: Int = 0,
    /** When the sync was asked for (wall clock), to measure how long it waited before starting. */
    val requestedAt: Long = System.currentTimeMillis(),
)

const val WIPE_REMOTE = 1 // the phone folder is empty: empty Proton too
const val WIPE_LOCAL = 2 // the Proton folder is empty: empty the phone folder too

class SyncRunner(private val context: Context) {
    private val engine = RcloneEngine.get(context)
    private val db = AppDb.get(context)

    suspend fun run(pairId: Long, options: SyncOptions = SyncOptions(), onProgress: suspend (app.synchrotron.rclone.Progress) -> Unit = {}) {
        val pair = db.pairs().get(pairId) ?: return SyncRegistry.emitFinished(pairId, SyncStatus.ERROR, 0)
        if (SyncRegistry.isRunning(pairId)) return
        val started = System.currentTimeMillis()

        fun fail(status: SyncStatus, message: String) = Outcome(status, message, 0, "")

        val outcome: Outcome = when {
            pair.mode == SyncMode.BIDIRECTIONAL && !pair.initialized && !options.resync ->
                return // the first sync needs the user's confirmation in the app
            !engine.hasSession -> fail(SyncStatus.ERROR, "Reconnecte-toi à Proton").also { Notifier.sessionExpired(context) }
            !Environment.isExternalStorageManager() && pair.localPath.startsWith("/storage") ->
                fail(SyncStatus.ERROR, "Accès à tous les fichiers non accordé")
            !File(pair.localPath).isDirectory -> fail(SyncStatus.ERROR, "Dossier local introuvable")
            else -> {
                SyncRegistry.start(pairId, coroutineContext[Job])
                db.pairs().setStatus(pairId, SyncStatus.RUNNING, "")
                try {
                    execute(pair, options, started) { SyncRegistry.update(pairId, it); onProgress(it) }
                } catch (e: CancellationException) {
                    db.pairs().setStatus(pairId, SyncStatus.ERROR, "Annulée")
                    SyncRegistry.finish(pairId)
                    throw e
                } catch (e: SessionExpiredException) {
                    Notifier.sessionExpired(context)
                    fail(SyncStatus.ERROR, "Session Proton expirée : reconnecte-toi")
                } catch (e: RcloneException) {
                    classify(e.message.orEmpty())
                } catch (e: Exception) {
                    fail(SyncStatus.ERROR, e.message ?: e.javaClass.simpleName)
                } finally {
                    SyncRegistry.finish(pairId)
                }
            }
        }

        val initialized = pair.initialized || (outcome.status == SyncStatus.OK || outcome.status == SyncStatus.CONFLICTS)
        db.pairs().setResult(pairId, outcome.status, outcome.message, System.currentTimeMillis(), initialized)
        if (!pair.initialized && initialized) WatchService.refresh(context) // sync-on-change starts with the first good sync
        db.history().insert(
            HistoryEntity(
                pairId = pairId, startedAt = started, durationMs = System.currentTimeMillis() - started,
                status = outcome.status, changes = outcome.changes, message = outcome.message, log = outcome.log,
            )
        )
        db.history().trim(pairId)

        when (outcome.status) {
            SyncStatus.ERROR, SyncStatus.BLOCKED -> Notifier.problem(context, pair, outcome.status, outcome.message)
            SyncStatus.CONFLICTS -> Notifier.conflicts(context, pair, outcome.message)
            else -> Notifier.clear(context, pair)
        }
        SyncRegistry.emitFinished(pairId, outcome.status, outcome.changes)
        Integration.broadcastFinished(context, pair, outcome.status, outcome.changes)
    }

    private class Outcome(val status: SyncStatus, val message: String, val changes: Int, val log: String)

    private suspend fun execute(
        pair: PairEntity, options: SyncOptions, started: Long,
        onProgress: suspend (app.synchrotron.rclone.Progress) -> Unit,
    ): Outcome {
        val waited = started - options.requestedAt
        var mark = SystemClock.elapsedRealtime()
        fun lap(): Long = SystemClock.elapsedRealtime().let { now -> (now - mark).also { mark = now } }
        var queued = 0L
        return engine.session(onLocked = { queued = lap() }) {
            val opened = lap()
            val remote = "${RcloneEngine.REMOTE}:${pair.remotePath}"
            // Opening the remote is the Proton connection: free once this process has done it.
            // The folder only needs creating before the first sync; a vanished one is handled below.
            if (!pair.initialized || options.resync) engine.mkdirRoot(remote) else engine.connect(remote)
            val connected = lap()
            if (options.wipe != 0) mirrorEmptiedSide(pair, remote, options.wipe)
            val group = "pair-${pair.id}"
            suspend fun transfer() = when (pair.mode) {
                SyncMode.BIDIRECTIONAL -> bisync(pair, remote, group, options, onProgress)
                SyncMode.UPLOAD_ONLY -> copy(pair.localPath, remote, pair, group, onProgress)
                SyncMode.DOWNLOAD_ONLY -> copy(remote, pair.localPath, pair, group, onProgress)
            }
            val result = try {
                transfer()
            } catch (e: RcloneException) {
                // The Proton folder was deleted or recreated elsewhere: forget its cached id and try once more.
                if (!e.message.orEmpty().contains("not found", ignoreCase = true)) throw e
                engine.dropRemotes()
                engine.mkdirRoot(remote)
                transfer()
            }
            val synced = lap()
            val timings = "Durées : démarrage ${secs(waited)} · file d'attente ${secs(queued)} · moteur ${secs(opened)} · " +
                "Proton ${secs(connected)} · synchro ${secs(synced)}"
            val conflicts = ConflictScanner.scan(File(pair.localPath)).size
            val changes = (result.transfers + result.deletes).toInt()
            val message = buildString {
                append(if (changes == 0) "Rien à synchroniser" else "$changes changement${if (changes > 1) "s" else ""}")
                if (conflicts > 0) append(" · $conflicts conflit${if (conflicts > 1) "s" else ""}")
            }
            Outcome(
                if (conflicts > 0) SyncStatus.CONFLICTS else SyncStatus.OK, message, changes,
                timings + "\n" + clean(result.output),
            )
        }
    }

    private fun secs(ms: Long) = "%.1f s".format(ms.coerceAtLeast(0) / 1000.0)

    private suspend fun bisync(
        pair: PairEntity, remote: String, group: String, options: SyncOptions,
        onProgress: suspend (app.synchrotron.rclone.Progress) -> Unit,
    ): app.synchrotron.rclone.JobResult {
        suspend fun attempt(resync: Boolean) = engine.runJob("sync/bisync", JSONObject().apply {
            put("path1", pair.localPath)
            put("path2", remote)
            put("workdir", File(engine.workDir, "pair-${pair.id}").apply { mkdirs() }.absolutePath)
            put("filtersFile", writeFilters(pair).absolutePath)
            put("resync", resync)
            if (resync) put("resyncMode", "newer")
            put("maxDelete", pair.maxDeletePercent)
            put("force", options.force)
            put("conflictResolve", pair.conflictPolicy.rclone)
            put("conflictLoser", "num")
            put("conflictSuffix", "conflict")
            put("createEmptySrcDirs", true)
            put("ignoreListingChecksum", true)
            put("resilient", true)
            put("recover", true)
            put("maxLock", "15m")
        }, group, onProgress)

        return try {
            attempt(options.resync)
        } catch (e: RcloneException) {
            // Changed filters, interrupted run or lost listings: a resync only merges, it never deletes.
            if (!options.resync && needsResync(clean(e.message.orEmpty()))) attempt(true) else throw e
        }
    }

    private suspend fun copy(
        src: String, dst: String, pair: PairEntity, group: String,
        onProgress: suspend (app.synchrotron.rclone.Progress) -> Unit,
    ) = engine.runJob("sync/copy", JSONObject().apply {
        put("srcFs", src)
        put("dstFs", dst)
        put("createEmptySrcDirs", true)
        put("_filter", filterJson(pair))
    }, group, onProgress)

    /**
     * Bisync refuses to sync an emptied folder, even with --force. When the user explicitly confirmed it,
     * empty the other side ourselves, then rebuild the (empty) baseline with the resync that follows.
     */
    private suspend fun mirrorEmptiedSide(pair: PairEntity, remote: String, wipe: Int) {
        val localEmpty = File(pair.localPath).walkTopDown().none { it.isFile }
        if (wipe == WIPE_REMOTE) {
            check(localEmpty) { "Le dossier du téléphone n'est plus vide : suppression annulée" }
            // Empty the folder but keep the folder itself: Proton's folder cache dislikes deleting the root of a pair.
            engine.listEntries(remote, recurse = false).forEach { e ->
                if (e.isDir) engine.rpc("operations/purge", JSONObject().put("fs", remote).put("remote", e.path))
                else engine.rpc("operations/deletefile", JSONObject().put("fs", remote).put("remote", e.path))
            }
        } else {
            check(engine.listEntries(remote, recurse = true).none { !it.isDir }) {
                "Le dossier Proton n'est plus vide : suppression annulée"
            }
            File(pair.localPath).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun writeFilters(pair: PairEntity): File {
        val dir = File(context.filesDir, "filters").apply { mkdirs() }
        return File(dir, "pair-${pair.id}.txt").apply {
            writeText(patterns(pair).joinToString("\n") { "- $it" } + "\n")
        }
    }

    private fun classify(raw: String): Outcome {
        val msg = clean(raw)
        return when {
            msg.contains("Safety abort", true) || msg.contains("too many deletes", true) ->
                Outcome(SyncStatus.BLOCKED, "Trop de suppressions prévues : confirmation requise", 0, msg)
            msg.contains("Empty current Path1", true) -> Outcome(SyncStatus.BLOCKED, MSG_LOCAL_EMPTY, 0, msg)
            msg.contains("Empty current Path2", true) -> Outcome(SyncStatus.BLOCKED, MSG_REMOTE_EMPTY, 0, msg)
            else -> Outcome(SyncStatus.ERROR, reason(msg), 0, msg)
        }
    }

    companion object {
        private val ansi = Regex("\u001B\\[[;\\d]*m")
        // Temporaries of the suite's atomic writes and unfinished downloads are never synced.
        private val builtinExcludes = listOf(".trashed-*", ".pending-*", ".thumbnails/", "*.tmp", "*.part")

        const val MSG_LOCAL_EMPTY = "Le dossier du téléphone est entièrement vide."
        const val MSG_REMOTE_EMPTY = "Le dossier Proton est entièrement vide."

        fun clean(s: String) = s.replace(ansi, "").take(8000)

        /** Bisync lost its baseline (crash, changed filters, first run failed): a resync rebuilds it without deleting. */
        fun needsResync(msg: String) = Regex(
            "(?i)(must run --resync|cannot find prior|run --resync to recover|resync is required|prior .{0,20}listing|empty prior)"
        ).containsMatchIn(msg)

        /** First meaningful ERROR line of rclone's output, without timestamp and noise. */
        fun reason(msg: String): String {
            val lines = msg.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val errors = lines.filter { " ERROR " in it || it.startsWith("ERROR") || it.contains("critical error", true) }
                .map { it.substringAfter("ERROR :", it).trim() }
                .filter { !it.contains("retryable", true) && !it.startsWith("Tip", true) }
            return (errors.firstOrNull() ?: lines.lastOrNull { !it.contains("retryable", true) } ?: "Erreur inconnue").take(300)
        }

        /** Name of the gitignore-like file an app can put at the root of its folder; it is synced too. */
        const val IGNORE_FILE = ".syncignore"

        fun patterns(pair: PairEntity): List<String> =
            builtinExcludes + rules(pair.exclusions) + rules(runCatching { File(pair.localPath, IGNORE_FILE).readText() }.getOrDefault(""))

        /**
         * One rclone exclude pattern per line; blank lines and `#` comments are skipped. rclone's syntax
         * is gitignore's for the usual cases (`*.log`, `cache/`, `/top-level-only`, `**`); negations
         * (`!keep`) are not supported and skipped.
         */
        private fun rules(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }

        fun filterJson(pair: PairEntity): JSONObject =
            JSONObject().put("ExcludeRule", JSONArray(patterns(pair)))
    }
}
