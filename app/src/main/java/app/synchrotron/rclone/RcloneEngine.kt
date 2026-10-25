package app.synchrotron.rclone

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RcloneException(val status: Int, message: String) : Exception(message)

/** The Proton session is gone or was never created: the user has to log in again. */
class SessionExpiredException(message: String) : Exception(message)

data class Progress(val bytes: Long = 0, val totalBytes: Long = 0, val transfers: Long = 0, val file: String = "")

data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

data class JobResult(val output: String, val transfers: Long, val deletes: Long)

/**
 * Single access point to the embedded rclone. Only one sync session runs at a time: the Proton
 * session lives in a temporary rclone.conf which exists on disk only while a session is open.
 *
 * Speed: rclone keeps each opened remote (the Proton client with its unlocked keys) in memory for the
 * life of the process (see rclone-bridge), so only the first sync after a process start pays the
 * Proton connection. The native library is loaded off the main thread ([warmUp]).
 */
class RcloneEngine private constructor(private val context: Context) {
    private val vault = ConfigVault(context)
    private val sessionLock = Mutex()
    private val confFile = File(context.noBackupFilesDir, "rclone.conf")

    private val _busy = MutableStateFlow(false)

    /** True while a session (login, sync, browsing) is open. The updater waits for this. */
    val busy: StateFlow<Boolean> = _busy

    /** How long loading the Go runtime and rclone took in this process (0 = not loaded yet). */
    @Volatile var nativeInitMs = 0L
        private set

    private val native = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val started = SystemClock.elapsedRealtime()
        confFile.delete() // leftover of a crash
        val home = File(context.filesDir, "rclone-home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "rclone-tmp").apply { mkdirs() }
        RcloneNative.initialize(home.absolutePath, tmp.absolutePath)
        nativeInitMs = SystemClock.elapsedRealtime() - started
    }

    val workDir: File get() = File(context.filesDir, "bisync").apply { mkdirs() }

    val hasSession: Boolean get() = vault.load()?.containsKey("client_access_token") == true
    val username: String? get() = vault.load()?.get("username")

    /** Loads rclone in the background so the first request does not wait for it. */
    fun warmUp() {
        if (!native.isInitialized()) Thread({ native.value }, "rclone-warmup").start()
    }

    /** Raw rc call. Throws [RcloneException] on a non-200 status. */
    suspend fun rpc(method: String, params: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        native.value
        val (status, out) = RcloneNative.rpc(method, params.toString())
        val json = runCatching { JSONObject(out) }.getOrDefault(JSONObject())
        if (status != 200) throw RcloneException(status, json.optString("error", out).ifBlank { "rclone error $status" })
        json
    }

    /** Forgets every opened remote; the next call reconnects to Proton. */
    suspend fun dropRemotes() {
        runCatching { rpc("fscache/clear") }
    }

    /**
     * Opens a session: decrypts the Proton credentials into a temporary rclone.conf, runs [block],
     * saves any refreshed tokens back to the vault and wipes the file.
     */
    suspend fun <T> session(onLocked: () -> Unit = {}, block: suspend () -> T): T = sessionLock.withLock {
        onLocked() // syncs run one at a time: lets callers measure how long they queued
        _busy.value = true
        try {
            val creds = vault.load() ?: throw SessionExpiredException("Pas de session Proton")
            openSession(creds)
            try {
                val result = block()
                harvest(creds)
                result
            } catch (e: Throwable) {
                runCatching { harvest(creds) }
                throw e
            } finally {
                closeSession()
            }
        } finally {
            _busy.value = false
        }
    }

    private suspend fun openSession(values: Map<String, String>) {
        writeConf(values)
        rpc("config/setpath", JSONObject().put("path", confFile.absolutePath))
    }

    private fun writeConf(values: Map<String, String>) {
        // Proton's metadata cache stays on (the default): without it every listed file costs one API call per
        // parent folder to rebuild its keys. It does not hide changes made elsewhere: folder contents are
        // always fetched from Proton, only folder keys and metadata are kept.
        val sb = StringBuilder("[$REMOTE]\ntype = protondrive\n")
        values.forEach { (k, v) -> if (k != "type") sb.append(k).append(" = ").append(v).append('\n') }
        confFile.writeText(sb.toString())
        confFile.setReadable(false, false); confFile.setReadable(true, true)
    }

    /** Reads the (possibly refreshed) session tokens back from rclone and persists them encrypted. */
    private suspend fun harvest(before: Map<String, String>?) {
        val cfg = rpc("config/get", JSONObject().put("name", REMOTE))
        var kept = KEPT_KEYS.mapNotNull { k -> cfg.optString(k).takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        if (kept["client_access_token"] == null && confFile.exists()) {
            // Fallback: rclone may have flushed refreshed tokens to the file only.
            val fromFile = confFile.readLines().mapNotNull { line ->
                line.split(" = ", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            kept = KEPT_KEYS.mapNotNull { k -> fromFile[k]?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
        }
        if (kept["client_access_token"] == null) {
            // rclone drops the tokens itself when Proton rejects them.
            dropRemotes()
            vault.clear()
            throw SessionExpiredException("Session Proton expirée")
        }
        if (before != null && kept["client_refresh_token"] != before["client_refresh_token"]) {
            // Proton rotated the refresh token: other open remotes still hold the old one and would get
            // the session revoked when they refresh. Drop them; they reconnect with the new tokens.
            dropRemotes()
        }
        vault.save(kept)
    }

    private fun closeSession() {
        confFile.delete()
    }

    /**
     * Logs in to Proton. Only the resulting session tokens are kept, never the password.
     * [twoFa] is the current TOTP code (empty when 2FA is off), [mailboxPassword] only for 2-password accounts.
     */
    suspend fun login(email: String, password: String, twoFa: String, mailboxPassword: String) = sessionLock.withLock {
        _busy.value = true
        try {
            val parameters = JSONObject().put("username", email).put("password", password)
            if (twoFa.isNotBlank()) parameters.put("2fa", twoFa.trim())
            if (mailboxPassword.isNotBlank()) parameters.put("mailbox_password", mailboxPassword)
            confFile.delete()
            rpc("config/setpath", JSONObject().put("path", confFile.absolutePath))
            try {
                rpc(
                    "config/create",
                    JSONObject().put("name", REMOTE).put("type", "protondrive")
                        .put("parameters", parameters)
                        .put("opt", JSONObject().put("obscure", true).put("nonInteractive", true))
                )
                // Creating the remote does not talk to Proton; opening it performs the real login (no listing needed).
                rpc("operations/fsinfo", JSONObject().put("fs", "$REMOTE:"))
                harvest(null)
            } finally {
                // Also forgets the remote opened with the password in memory.
                runCatching { rpc("config/delete", JSONObject().put("name", REMOTE)) }
                closeSession()
            }
        } finally {
            _busy.value = false
        }
    }

    fun logout() {
        vault.clear()
        confFile.delete()
    }

    /**
     * Lists sub-folders of [path] ("" = Drive root) on Proton. Needs an open [session]. Always goes through
     * the Drive root remote: one remote per browsed folder would mean one Proton connection per folder.
     */
    suspend fun listRemoteDirs(path: String): List<RemoteEntry> =
        listEntries("$REMOTE:", recurse = false, remote = path).filter { it.isDir }.sortedBy { it.name.lowercase() }

    suspend fun listEntries(fs: String, recurse: Boolean, filter: JSONObject? = null, remote: String = ""): List<RemoteEntry> {
        val params = JSONObject().put("fs", fs).put("remote", remote)
            .put("opt", JSONObject().put("recurse", recurse).put("noMimeType", true).put("noModTime", true))
        if (filter != null) params.put("_filter", filter)
        val arr: JSONArray = rpc("operations/list", params).optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteEntry(o.getString("Name"), o.getString("Path"), o.getBoolean("IsDir"), o.optLong("Size"))
        }
    }

    suspend fun mkdir(path: String) {
        rpc("operations/mkdir", JSONObject().put("fs", "$REMOTE:").put("remote", path))
    }

    /** Opens (or reuses) the remote for [fs] and creates its root folder if needed. */
    suspend fun mkdirRoot(fs: String) {
        rpc("operations/mkdir", JSONObject().put("fs", fs).put("remote", ""))
    }

    /** Opens (or reuses) the remote for [fs]: the Proton connection, measured separately from the sync. */
    suspend fun connect(fs: String) {
        rpc("operations/fsinfo", JSONObject().put("fs", fs))
    }

    /**
     * Runs a long rc call as a cancellable job, reporting progress. Polls quickly at first (most syncs
     * are short) then every 400 ms. Cancelling the coroutine stops the rclone job.
     */
    suspend fun runJob(
        method: String,
        params: JSONObject,
        group: String,
        onProgress: suspend (Progress) -> Unit = {},
    ): JobResult {
        params.put("_async", true).put("_group", group)
        val jobId = rpc(method, params).getLong("jobid")
        var polls = 0
        try {
            while (true) {
                delay(if (polls++ < 30) 60 else 400)
                val status = rpc("job/status", JSONObject().put("jobid", jobId))
                val finished = status.optBoolean("finished")
                // Progress is only worth asking for once the sync lasts a little.
                val stats = if (finished || polls > 5) runCatching { rpc("core/stats", JSONObject().put("group", group)) }.getOrNull() else null
                if (stats != null) onProgress(stats.toProgress())
                if (finished) {
                    val result = JobResult(
                        output = status.optJSONObject("output")?.optString("output").orEmpty(),
                        transfers = stats?.optLong("transfers") ?: 0,
                        deletes = stats?.optLong("deletes") ?: 0,
                    )
                    runCatching { rpc("core/stats-delete", JSONObject().put("group", group)) }
                    if (!status.optBoolean("success")) {
                        // The real reason is in the captured output; "error" alone is just "bisync aborted".
                        val detail = result.output.takeIf { it.isNotBlank() }
                        val summary = status.optString("error").ifBlank { "Échec de la synchronisation" }
                        throw RcloneException(500, if (detail != null) "$summary\n$detail" else summary)
                    }
                    return result
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { rpc("job/stop", JSONObject().put("jobid", jobId)) } }
            throw e
        }
    }

    private fun JSONObject.toProgress(): Progress {
        val transferring = optJSONArray("transferring")
        val current = if (transferring != null && transferring.length() > 0) transferring.getJSONObject(0).optString("name") else ""
        return Progress(optLong("bytes"), optLong("totalBytes"), optLong("transfers"), current)
    }

    companion object {
        const val REMOTE = "proton"
        private val KEPT_KEYS = listOf(
            "username", "client_uid", "client_access_token", "client_refresh_token", "client_salted_key_pass",
        )

        @Volatile private var instance: RcloneEngine? = null

        fun get(context: Context): RcloneEngine = instance ?: synchronized(this) {
            instance ?: RcloneEngine(context.applicationContext).also { instance = it }
        }
    }
}
