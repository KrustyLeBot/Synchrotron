// Suite kit: the master copy is in Synchrotron (app/src/main/java/app/synchrotron/suite/). Copy it
// unchanged into each app of the suite; only the package line differs.
package app.synchrotron.suite

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** [note] says why the install did not start (permission to grant, sync running…). */
    data class Ready(val release: Release, val apk: File, val note: String? = null) : UpdateState
    /** [release] is set when the download failed, so it can be retried from the banner. */
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

/**
 * The same update flow in every app: a quiet check when the app opens, a banner when a build is
 * available (download progress and install in the banner itself), and a manual check in Settings.
 * [installBlocker] returns why installing now is a bad idea, or null.
 */
class SuiteUpdates(
    context: Context,
    private val updater: SuiteUpdater,
    private val scope: CoroutineScope,
    private val installBlocker: () -> String? = { null },
) {
    private val prefs = context.getSharedPreferences("suite_updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set
    private var laterBuild by mutableStateOf(prefs.getInt(KEY_LATER, 0))

    val installedBuild = updater.installedBuild()

    /** Whether the banner shows: an offer not put off with "Plus tard", or a download under way. */
    val showBanner: Boolean
        get() = when (val s = state) {
            is UpdateState.Available -> laterBuild != s.release.build
            is UpdateState.Downloading, is UpdateState.Ready -> true
            is UpdateState.Failed -> s.release != null
            else -> false
        }

    init {
        updater.cleanup()
    }

    /** Quiet check when the app opens (at most once an hour); failures stay silent. */
    fun checkOnOpen() {
        val now = System.currentTimeMillis()
        val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Ready
        if (busy || now - prefs.getLong(KEY_CHECKED, 0) < HOUR_MS) return
        run(quiet = true)
    }

    /** "Rechercher une mise à jour" in Settings: also reports "up to date" and errors. */
    fun check() {
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        setLater(0)
        run(quiet = false)
    }

    private fun run(quiet: Boolean) {
        job?.cancel()
        state = UpdateState.Checking
        job = scope.launch {
            state = try {
                val release = updater.latest()
                prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                if (release != null && release.build > installedBuild) UpdateState.Available(release) else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quiet) UpdateState.Idle else UpdateState.Failed(describe(e))
            }
        }
    }

    fun download(release: Release) {
        job?.cancel()
        state = UpdateState.Downloading(release, 0f)
        job = scope.launch {
            state = try {
                var last = -1
                val apk = updater.download(release) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != last) {
                        last = percent
                        state = UpdateState.Downloading(release, p)
                    }
                }
                UpdateState.Ready(release, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(describe(e), release)
            }
            (state as? UpdateState.Ready)?.let { install(it) }
        }
    }

    /** Hands the APK to Android, after the "install unknown apps" permission if it is missing. */
    fun install(ready: UpdateState.Ready) {
        val blocked = installBlocker()
        state = when {
            blocked != null -> ready.copy(note = blocked)
            !updater.canInstall() -> {
                updater.openInstallPermission()
                ready.copy(note = "Autorise l'installation d'applications, puis touche « Installer ».")
            }
            else -> {
                updater.install(ready.apk)
                ready.copy(note = null)
            }
        }
    }

    /** "Plus tard": no banner for this build until a newer one or a manual check. */
    fun later() {
        val s = state
        if (s is UpdateState.Available) setLater(s.release.build)
        if (s is UpdateState.Failed) state = UpdateState.Idle
    }

    private fun setLater(build: Int) {
        laterBuild = build
        prefs.edit().putInt(KEY_LATER, build).apply()
    }

    fun cancel() {
        job?.cancel()
        state = UpdateState.Idle
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Pas de connexion internet"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val KEY_CHECKED = "last_check"
        const val KEY_LATER = "later_build"
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
