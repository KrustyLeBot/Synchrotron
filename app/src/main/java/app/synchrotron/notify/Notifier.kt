package app.synchrotron.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.synchrotron.MainActivity
import app.synchrotron.R
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncStatus
import app.synchrotron.rclone.Progress

object Notifier {
    const val CH_PROBLEMS = "problems"
    const val CH_PROGRESS = "progress"
    const val CH_WATCH = "watch"
    const val ID_SESSION = 1
    const val ID_WATCH = 2
    const val ID_UPDATE = 3
    private const val ID_PAIR_BASE = 1000

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROBLEMS, "Erreurs et conflits", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Synchronisation en cours", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WATCH, "Surveillance des dossiers", NotificationManager.IMPORTANCE_MIN))
    }

    private fun open(context: Context, pairId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (pairId != null) putExtra(MainActivity.EXTRA_PAIR_ID, pairId)
        }
        return PendingIntent.getActivity(
            context, (pairId ?: -1).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, n)
    }

    fun problem(context: Context, pair: PairEntity, status: SyncStatus, message: String) {
        val title = if (status == SyncStatus.BLOCKED) "${pair.name} : confirmation requise" else "${pair.name} : erreur de synchro"
        notify(context, ID_PAIR_BASE + pair.id.toInt(), NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open(context, pair.id)).setAutoCancel(true).build())
    }

    fun conflicts(context: Context, pair: PairEntity, message: String) {
        notify(context, ID_PAIR_BASE + pair.id.toInt(), NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("${pair.name} : conflits à résoudre")
            .setContentText(message).setContentIntent(open(context, pair.id)).setAutoCancel(true).build())
    }

    fun clear(context: Context, pair: PairEntity) {
        NotificationManagerCompat.from(context).cancel(ID_PAIR_BASE + pair.id.toInt())
    }

    fun sessionExpired(context: Context) {
        notify(context, ID_SESSION, NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Reconnecte-toi à Proton")
            .setContentText("La session a expiré, les synchronisations sont en pause.")
            .setContentIntent(open(context, null)).setAutoCancel(true).build())
    }

    /** The Synchrotron server refuses the settings (or they are missing): syncs are on hold until fixed. */
    fun serverProblem(context: Context, message: String) {
        notify(context, ID_SESSION, NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Vérifie les réglages du serveur")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open(context, null)).setAutoCancel(true).build())
    }

    fun updateAvailable(context: Context, build: Int) {
        notify(context, ID_UPDATE, NotificationCompat.Builder(context, CH_PROBLEMS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Mise à jour disponible")
            .setContentText("Synchrotron build $build")
            .setContentIntent(open(context, null)).setAutoCancel(true).build())
    }

    fun progress(context: Context, pairName: String, p: Progress): android.app.Notification {
        val text = when {
            p.file.isNotEmpty() -> p.file
            p.transfers > 0 -> "${p.transfers} fichiers"
            else -> "Analyse des changements…"
        }
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync).setContentTitle("Synchronisation de $pairName")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open(context, null))
        if (p.totalBytes > 0) {
            b.setProgress(1000, (p.bytes * 1000 / p.totalBytes).toInt().coerceIn(0, 1000), false)
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }
}
