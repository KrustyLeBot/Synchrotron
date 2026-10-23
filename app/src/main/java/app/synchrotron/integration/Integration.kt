package app.synchrotron.integration

import android.content.Context
import android.content.Intent
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncStatus

/** Action names shared with the `synchrotron-client` library. */
object Integration {
    const val ACTION_SYNC_FINISHED = "app.synchrotron.action.SYNC_FINISHED"

    fun broadcastFinished(context: Context, pair: PairEntity, status: SyncStatus, changes: Int) {
        fun intent() = Intent(ACTION_SYNC_FINISHED)
            .putExtra("pair_id", pair.id)
            .putExtra("status", status.name)
            .putExtra("changes", changes)
        // Implicit: reaches apps that registered a receiver at run time. Carries no path.
        context.sendBroadcast(intent())
        // Explicit: only the app that created this pair, which may be in the background.
        pair.requestedByPackage?.let { pkg ->
            context.sendBroadcast(intent().setPackage(pkg).putExtra("local_path", pair.localPath))
        }
    }
}
