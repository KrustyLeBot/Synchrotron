package app.synchrotron.remote

import android.content.Context
import app.synchrotron.data.ConflictPolicy
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncMode
import app.synchrotron.rclone.JobResult
import app.synchrotron.rclone.Progress
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.rclone.RemoteEntry
import app.synchrotron.sync.Preview
import org.json.JSONArray
import org.json.JSONObject

/** The server is configured but refuses us, or cannot be reached: see [kind]. */
class ServerException(val kind: String, message: String) : Exception(message)

/**
 * The Synchrotron server backend. The WebDAV client and the sync logic live in the embedded Go
 * library (rclone-bridge/davsync), reached through rclone's rc calls like the Proton backend.
 */
class ServerRemote(private val context: Context) {
    private val engine = RcloneEngine.get(context)

    private fun config(): ServerConfig = Backends.server(context) ?: throw ServerException("setup", "Serveur Synchrotron non configuré")

    private suspend fun call(method: String, params: JSONObject, cfg: ServerConfig = config()): JSONObject = try {
        engine.rpc(method, params.put("server", cfg.toJson()))
    } catch (e: app.synchrotron.rclone.RcloneException) {
        throw translate(e.message.orEmpty())
    }

    /** PROPFIND Depth 0 on the root: answers the host actually used (the LAN one when it answered). */
    suspend fun test(cfg: ServerConfig): String = engine.exclusive {
        call("synchrotron/davtest", JSONObject(), cfg).optString("host")
    }

    suspend fun listDirs(path: String): List<RemoteEntry> = engine.exclusive {
        val arr: JSONArray = call("synchrotron/davlist", JSONObject().put("path", path)).optJSONArray("list") ?: JSONArray()
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteEntry(o.getString("Name"), o.getString("Path"), true, 0)
        }
    }

    suspend fun mkdir(path: String) {
        engine.exclusive { call("synchrotron/davmkdir", JSONObject().put("path", path)) }
    }

    private fun syncParams(pair: PairEntity, patterns: List<String>, force: Boolean, allowEmpty: Boolean) = JSONObject()
        .put("local", pair.localPath)
        .put("remote", pair.remotePath)
        .put("stateFile", Backends.serverState(context, pair.id).absolutePath)
        .put("mode", when (pair.mode) {
            SyncMode.BIDIRECTIONAL -> "bisync"
            SyncMode.UPLOAD_ONLY -> "upload"
            SyncMode.DOWNLOAD_ONLY -> "download"
        })
        .put("conflict", when (pair.conflictPolicy) {
            ConflictPolicy.KEEP_BOTH -> "none"
            ConflictPolicy.NEWER -> "newer"
            ConflictPolicy.LOCAL -> "path1"
            ConflictPolicy.REMOTE -> "path2"
        })
        .put("excludes", JSONArray(patterns))
        .put("maxDelete", pair.maxDeletePercent)
        .put("force", force)
        .put("allowEmpty", allowEmpty)

    /** Must run inside [RcloneEngine.exclusive]. */
    suspend fun sync(
        pair: PairEntity, patterns: List<String>, force: Boolean, allowEmpty: Boolean, group: String,
        onProgress: suspend (Progress) -> Unit,
    ): JobResult {
        val params = JSONObject().put("server", config().toJson()).put("sync", syncParams(pair, patterns, force, allowEmpty))
        return engine.runJob("synchrotron/davsync", params, group, onProgress)
    }

    suspend fun preview(pair: PairEntity, patterns: List<String>): Preview = engine.exclusive {
        val o = call("synchrotron/davpreview", JSONObject().put("sync", syncParams(pair, patterns, false, false)))
        Preview(o.optInt("toUpload"), o.optInt("toDownload"), o.optInt("different"), o.optInt("identical"))
    }

    companion object {
        private val kindTag = Regex("""\[(auth|access|offline|toolarge|transient|protocol)]\s*""")

        /**
         * Kind of a failed call, from the `[kind]` tag the Go side puts first; null when none. Only the
         * first line counts: the rest is the sync log, where files in error carry their own tags.
         */
        fun kindOf(message: String): String? = kindTag.find(message.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)

        fun translate(raw: String): ServerException {
            val kind = kindOf(raw) ?: "error"
            val first = raw.lines().firstOrNull { kindTag.containsMatchIn(it) } ?: raw.lines().firstOrNull().orEmpty()
            val detail = first.replace(kindTag, "").trim()
            return ServerException(kind, friendly(kind, detail))
        }

        fun friendly(kind: String, detail: String): String = when (kind) {
            "auth" -> "Utilisateur ou mot de passe du serveur refusé : $detail"
            "access" -> "Accès refusé par Cloudflare : vérifie le Client ID et le Client Secret du service token. ($detail)"
            "offline" -> "Serveur injoignable (NAS éteint ou tunnel coupé) : nouvel essai plus tard."
            "transient" -> "Le serveur a mal répondu : nouvel essai plus tard. ($detail)"
            else -> detail.ifBlank { "Erreur du serveur" }
        }
    }
}
