package app.synchrotron.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/** Result of a finished sync, delivered by [SynchrotronClient.onSyncFinished]. */
data class SyncResult(val pairId: Long, val status: String, val changes: Int, val localPath: String?) {
    val ok get() = status == "OK" || status == "CONFLICTS"
}

/** Why [SynchrotronClient.syncOnOpen] did not wait for a sync: the local data is as fresh as it can be. */
enum class SkipReason { NOT_INSTALLED, NOT_REGISTERED, NEEDS_SETUP, NOT_LOGGED_IN, FRESH, NETWORK, COOLDOWN, UNAVAILABLE }

/** What the host app should show while [SynchrotronClient.syncOnOpen] runs. Always delivered on the main thread. */
sealed interface SyncState {
    /** A sync is running: show the data read-only until [Done], [Failed] or [TimedOut]. */
    data object Syncing : SyncState
    /** Finished: reload the data from disk when [changes] > 0. */
    data class Done(val changes: Int, val hasConflicts: Boolean) : SyncState
    data class Failed(val status: String) : SyncState
    /** Took too long: make the app editable again. The late [Done] / [Failed] is still reported afterwards. */
    data object TimedOut : SyncState
    /** No sync to wait for. [NOT_REGISTERED] is the cue to propose [SynchrotronClient.registerFolder]. */
    data class Skipped(val reason: SkipReason) : SyncState
}

/**
 * Thin client for the Synchrotron app. Every call degrades silently when Synchrotron is not installed.
 * The host app needs `<queries><package android:name="app.synchrotron" /></queries>` in its manifest.
 *
 * ```
 * // Activity start: show the data read-only, reload when the sync is done.
 * val cancel = SynchrotronClient.syncOnOpen(this, notesDir.path) { state -> ... }
 * ```
 */
object SynchrotronClient {
    const val PACKAGE = "app.synchrotron"
    const val ACTION_REGISTER_FOLDER = "app.synchrotron.action.REGISTER_FOLDER"
    const val ACTION_SYNC_NOW = "app.synchrotron.action.SYNC_NOW"
    const val ACTION_SYNC_FINISHED = "app.synchrotron.action.SYNC_FINISHED"

    // Result codes of SYNC_NOW (ordered broadcast), see SyncNowReceiver in Synchrotron.
    private const val R_STARTED = 1
    private const val R_RUNNING = 2
    private const val R_NOT_REGISTERED = 3
    private const val R_NEEDS_SETUP = 4
    private const val R_NOT_LOGGED_IN = 5
    private const val R_FRESH = 6
    private const val R_NETWORK = 7
    private const val NO_ANSWER = -1000

    private const val COOLDOWN_MS = 45_000L
    private const val GIVE_UP_MS = 10 * 60_000L

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Asks Synchrotron to sync [localPath] with Proton Drive. Synchrotron shows a confirmation to the
     * user; nothing is created without it. [remotePath] defaults to `Apps/<appName>`.
     * The optional suggestions are shown in that confirmation: [conflictPolicy] is one of
     * `KEEP_BOTH`, `NEWER`, `LOCAL`, `REMOTE`; [intervalMinutes] one of 0 (manual), 15, 30, 60, 180, 360, 720, 1440.
     * @return false when Synchrotron is not installed.
     */
    fun registerFolder(
        context: Context,
        localPath: String,
        appName: String,
        remotePath: String? = null,
        conflictPolicy: String = "KEEP_BOTH",
        intervalMinutes: Int = 60,
        watchChanges: Boolean = true,
        exclusions: List<String> = emptyList(),
    ): Boolean {
        if (!isInstalled(context)) return false
        val intent = Intent(ACTION_REGISTER_FOLDER).setPackage(PACKAGE)
            .putExtra("local_path", localPath)
            .putExtra("app_name", appName)
            .putExtra("conflict_policy", conflictPolicy)
            .putExtra("interval_minutes", intervalMinutes)
            .putExtra("watch_changes", watchChanges)
            .putExtra("exclusions", exclusions.joinToString("\n"))
        if (remotePath != null) intent.putExtra("remote_path", remotePath)
        return try {
            // The system reports this app as the caller (Activity.getReferrer) to Synchrotron.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Triggers a sync of an already registered folder. Does nothing if the folder is not registered. */
    fun syncNow(context: Context, localPath: String): Boolean {
        if (!isInstalled(context)) return false
        context.sendBroadcast(Intent(ACTION_SYNC_NOW).setPackage(PACKAGE).putExtra("local_path", localPath))
        return true
    }

    /** Same, by pair id (as returned in [SyncResult.pairId]). */
    fun syncNow(context: Context, pairId: Long): Boolean {
        if (!isInstalled(context)) return false
        context.sendBroadcast(Intent(ACTION_SYNC_NOW).setPackage(PACKAGE).putExtra("pair_id", pairId))
        return true
    }

    /** Listens for finished syncs while [context] is alive. Returns a function that stops listening. */
    fun onSyncFinished(context: Context, listener: (SyncResult) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = listener(intent.toResult())
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SYNC_FINISHED), ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * "Be up to date when I open": asks Synchrotron to sync [localPath] now and reports the outcome through
     * [onState] (main thread). Typical use: show the data read-only on [SyncState.Syncing], reload on
     * [SyncState.Done] with changes, become editable again on anything else.
     * A 45 s cool-down per folder avoids hammering Proton when the app is opened repeatedly.
     * @return a function that stops listening (call it from `onDestroy`).
     */
    fun syncOnOpen(context: Context, localPath: String, timeoutMs: Long = 20_000, onState: (SyncState) -> Unit): () -> Unit {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        var stop: () -> Unit = {}
        var finished = false
        fun end(state: SyncState) {
            if (finished) return
            finished = true
            stop()
            main.removeCallbacksAndMessages(null)
            onState(state)
        }
        val cancel = { finished = true; stop(); main.removeCallbacksAndMessages(null) }

        fun skip(reason: SkipReason) = main.post { end(SyncState.Skipped(reason)) }
        if (!isInstalled(app)) { skip(SkipReason.NOT_INSTALLED); return cancel }
        val prefs = app.getSharedPreferences("synchrotron_client", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_$localPath", 0) < COOLDOWN_MS) { skip(SkipReason.COOLDOWN); return cancel }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (cm.activeNetwork == null) { skip(SkipReason.NETWORK); return cancel }

        var pairId = -1L
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val r = intent.toResult()
                if (pairId >= 0 && r.pairId != pairId) return
                end(if (r.ok) SyncState.Done(r.changes, r.status == "CONFLICTS") else SyncState.Failed(r.status))
            }
        }
        // Listen before asking, so a very fast sync cannot be missed.
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_SYNC_FINISHED), ContextCompat.RECEIVER_EXPORTED)
        stop = { runCatching { app.unregisterReceiver(receiver) } }

        val request = Intent(ACTION_SYNC_NOW).setPackage(PACKAGE).putExtra("local_path", localPath)
        app.sendOrderedBroadcast(request, null, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (finished) return
                val extras: Bundle? = getResultExtras(false)
                when (resultCode) {
                    R_STARTED, R_RUNNING -> {
                        pairId = extras?.getLong("pair_id", -1L) ?: -1L
                        prefs.edit().putLong("last_$localPath", System.currentTimeMillis()).apply()
                        onState(SyncState.Syncing)
                        // Too slow: let the user work again, but keep listening to report the late result.
                        main.postDelayed({ if (!finished) onState(SyncState.TimedOut) }, timeoutMs)
                        main.postDelayed({ finished = true; stop() }, GIVE_UP_MS)
                    }
                    R_NOT_REGISTERED -> end(SyncState.Skipped(SkipReason.NOT_REGISTERED))
                    R_NEEDS_SETUP -> end(SyncState.Skipped(SkipReason.NEEDS_SETUP))
                    R_NOT_LOGGED_IN -> end(SyncState.Skipped(SkipReason.NOT_LOGGED_IN))
                    R_FRESH -> end(SyncState.Skipped(SkipReason.FRESH))
                    R_NETWORK -> end(SyncState.Skipped(SkipReason.NETWORK))
                    else -> end(SyncState.Skipped(SkipReason.UNAVAILABLE))
                }
            }
        }, main, NO_ANSWER, null, null)
        return cancel
    }

    private fun Intent.toResult() = SyncResult(
        pairId = getLongExtra("pair_id", -1),
        status = getStringExtra("status").orEmpty(),
        changes = getIntExtra("changes", 0),
        localPath = getStringExtra("local_path"),
    )
}
