package app.synchrotron.remote

import android.content.Context
import app.synchrotron.rclone.ConfigVault
import app.synchrotron.rclone.RcloneEngine
import org.json.JSONObject
import java.io.File

/** Where the pairs are synced. One backend at a time for every pair, chosen in the settings. */
enum class Backend(val label: String, val short: String) {
    PROTON("Proton Drive", "Proton"),
    SERVER("Serveur Synchrotron", "le serveur"),
}

/** The self-hosted WebDAV server (NAS behind Cloudflare Access). Stored encrypted, never logged. */
data class ServerConfig(
    val url: String = "https://synchrotron.jbrhomelab.com/",
    val user: String = "",
    val password: String = "",
    val cfId: String = "",
    val cfSecret: String = "",
    /** Optional http://IP:8090 used instead of [url] whenever it answers (at home, without Cloudflare). */
    val lanUrl: String = "",
) {
    val complete: Boolean get() = url.isNotBlank() && user.isNotBlank() && password.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        // Pasted or autofilled values often carry a stray space or line break at an end.
        .put("url", url.trim()).put("user", user.trim()).put("password", password.trim())
        .put("cfId", cfId.trim()).put("cfSecret", cfSecret.trim()).put("lanUrl", lanUrl.trim())

    fun toMap() = mapOf("url" to url, "user" to user, "password" to password, "cfId" to cfId, "cfSecret" to cfSecret, "lanUrl" to lanUrl)

    override fun toString() = "ServerConfig(url=$url, user=$user, lanUrl=$lanUrl)" // keeps secrets out of logs

    companion object {
        fun fromMap(m: Map<String, String>) = ServerConfig(
            m["url"].orEmpty(), m["user"].orEmpty(), m["password"].orEmpty(),
            m["cfId"].orEmpty(), m["cfSecret"].orEmpty(), m["lanUrl"].orEmpty(),
        )
    }
}

object Backends {
    private const val KEY = "backend"
    @Volatile private var vault: ConfigVault? = null

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun vault(c: Context) = vault ?: synchronized(this) {
        vault ?: ConfigVault(c.applicationContext, "server").also { vault = it }
    }

    fun current(c: Context): Backend =
        runCatching { Backend.valueOf(prefs(c).getString(KEY, null) ?: "") }.getOrDefault(Backend.PROTON)

    fun server(c: Context): ServerConfig? = vault(c).load()?.let { ServerConfig.fromMap(it) }?.takeIf { it.complete }

    fun saveServer(c: Context, cfg: ServerConfig) = vault(c).save(cfg.toMap())

    /** The chosen backend can sync: Proton session open, or server settings filled in. */
    fun ready(c: Context): Boolean = when (current(c)) {
        Backend.PROTON -> RcloneEngine.get(c).hasSession
        Backend.SERVER -> server(c) != null
    }

    /** Server sync state of a pair (hashes at the last sync), private to the app. */
    fun serverState(c: Context, pairId: Long): File = File(c.noBackupFilesDir, "server-state/pair-$pairId.json")

    /**
     * Switches every pair to [to]. The sync state of the other backend is not carried over: each side
     * starts from an empty state, so the next sync only compares contents (MD5 on the server, a merge
     * resync on Proton) and never deletes anything. Pairs keep their folders and settings, so the apps
     * asking for syncs see no difference.
     */
    fun switch(c: Context, to: Backend) {
        if (to == current(c)) return
        File(c.noBackupFilesDir, "server-state").deleteRecursively()
        RcloneEngine.get(c).workDir.listFiles()?.forEach { it.deleteRecursively() }
        prefs(c).edit().putString(KEY, to.name).apply()
    }

    fun forgetPair(c: Context, pairId: Long) {
        serverState(c, pairId).delete()
    }
}
