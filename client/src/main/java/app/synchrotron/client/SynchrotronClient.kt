package app.synchrotron.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Result of a finished sync, delivered by [SynchrotronClient.onSyncFinished]. */
data class SyncResult(val pairId: Long, val status: String, val changes: Int, val localPath: String?) {
    val ok get() = status == "OK" || status == "CONFLICTS"
}

/**
 * Thin client for the Synchrotron app. Every call degrades silently when Synchrotron is not installed.
 *
 * ```
 * SynchrotronClient.registerFolder(context, notesDir.path, "Griffonote")   // once, user confirms
 * SynchrotronClient.syncNow(context, notesDir.path)                        // after an important save
 * val stop = SynchrotronClient.onSyncFinished(context) { reloadNotes() }   // call stop() in onDestroy
 * ```
 */
object SynchrotronClient {
    const val PACKAGE = "app.synchrotron"
    const val ACTION_REGISTER_FOLDER = "app.synchrotron.action.REGISTER_FOLDER"
    const val ACTION_SYNC_NOW = "app.synchrotron.action.SYNC_NOW"
    const val ACTION_SYNC_FINISHED = "app.synchrotron.action.SYNC_FINISHED"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Asks Synchrotron to sync [localPath] with Proton Drive. Synchrotron shows a confirmation to the
     * user; nothing is created without it. [remotePath] defaults to `Apps/<appName>`.
     * @return false when Synchrotron is not installed.
     */
    fun registerFolder(context: Context, localPath: String, appName: String, remotePath: String? = null): Boolean {
        if (!isInstalled(context)) return false
        val intent = Intent(ACTION_REGISTER_FOLDER).setPackage(PACKAGE)
            .putExtra("local_path", localPath)
            .putExtra("app_name", appName)
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
            override fun onReceive(c: Context, intent: Intent) {
                listener(
                    SyncResult(
                        pairId = intent.getLongExtra("pair_id", -1),
                        status = intent.getStringExtra("status").orEmpty(),
                        changes = intent.getIntExtra("changes", 0),
                        localPath = intent.getStringExtra("local_path"),
                    )
                )
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SYNC_FINISHED), ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }
}
