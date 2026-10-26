// Suite kit: the master copy is in Synchrotron (app/src/main/java/app/synchrotron/suite/). Copy it
// unchanged into each app of the suite; only the package line differs.
package app.synchrotron.suite

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** [build] is the CI run number, which is also the APK versionCode. */
data class Release(val build: Int, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * Self-update from the rolling `latest` GitHub release of [repo] ("Owner/Name"): its title ends with
 * the build number and it carries one `.apk`. Android only installs an APK signed with the key of the
 * installed app, so a tampered file cannot replace it. [authority] is the app's FileProvider, which
 * must expose `cache/updates/`.
 */
class SuiteUpdater(private val context: Context, private val repo: String, private val authority: String) {
    private val dir get() = File(context.cacheDir, "updates")

    fun installedBuild(): Int =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()

    /** The newest published release, or null when there is none yet. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases/tags/latest")
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 404) return@withContext null
            if (conn.responseCode != 200) throw IOException("GitHub a répondu ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("(\\d+)\\s*$").find(json.optString("name").trim())?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IOException("Numéro de build introuvable dans « ${json.optString("name")} »")
            val assets = json.optJSONArray("assets") ?: return@withContext null
            (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.let {
                val digest = it.optString("digest")
                Release(build, it.getString("browser_download_url"), it.optLong("size"),
                    if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").lowercase() else "")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [release] into the cache, checks it and returns the APK. [onProgress] gets 0..1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "update-${release.build}.apk.part")
        val target = File(dir, "update-${release.build}.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        val conn = open(release.url)
        try {
            if (conn.responseCode != 200) throw IOException("Téléchargement impossible (HTTP ${conn.responseCode})")
            val total = (conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes).coerceAtLeast(1)
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        done += n
                        ensureActive()
                        onProgress((done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        } catch (e: Exception) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        try {
            if (release.sizeBytes > 0 && part.length() != release.sizeBytes) throw IOException("Téléchargement incomplet")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256.isNotEmpty() && hex != release.sha256) throw IOException("Le fichier ne correspond pas à l'empreinte publiée")
            if (!part.renameTo(target)) throw IOException("Impossible d'enregistrer la mise à jour")
            val archive = context.packageManager.getPackageArchiveInfo(target.path, 0)
            if (archive?.packageName != context.packageName) throw IOException("Ce fichier n'est pas une version de l'application")
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw e
        }
        target
    }

    /** Deletes downloads that are installed by now (or unfinished). Call when the app starts. */
    fun cleanup() {
        val installed = installedBuild()
        dir.listFiles()?.forEach { f ->
            val build = Regex("^update-(\\d+)\\.apk$").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (build == null || build <= installed) f.delete()
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Android's "install unknown apps" switch for this app. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens Android's install screen; the user confirms there. */
    fun install(apk: File) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(FileProvider.getUriForFile(context, authority, apk), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "${context.packageName}/${installedBuild()}")
    }
}
