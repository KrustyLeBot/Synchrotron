package app.synchrotron.integration

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.fragment.app.FragmentActivity
import app.synchrotron.security.BiometricLock
import app.synchrotron.security.Prefs
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.synchrotron.MainActivity
import app.synchrotron.data.AppDb
import app.synchrotron.data.ConflictPolicy
import app.synchrotron.data.PairEntity
import app.synchrotron.remote.Backends
import app.synchrotron.sync.Scheduler
import app.synchrotron.ui.SynchrotronTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * REGISTER_FOLDER: another app proposes a folder to sync. Nothing happens until the user confirms
 * here, with the local folder and the remote path in plain sight.
 *
 * Extras: `local_path` (absolute, inside shared storage), `app_name`, optional `remote_path`.
 */
class RegisterFolderActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.biometricLock(this)) {
            BiometricLock.authenticate(this) { ok -> if (ok) render() else finish() }
        } else {
            render()
        }
    }

    private fun render() {
        val appName = intent.getStringExtra("app_name")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val local = intent.getStringExtra("local_path")?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        val valid = local != null && local != root && local.path.startsWith(root.path + "/")
        val remote = sanitizeRemote(intent.getStringExtra("remote_path"), appName ?: local?.name ?: "App")
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingActivity?.packageName
        val defaults = Defaults.from(intent)
        val loggedIn = Backends.ready(this)
        val backend = Backends.current(this).label

        setContent {
            SynchrotronTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(Modifier.padding(24.dp)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!valid || appName == null) {
                                Text("Demande invalide", style = MaterialTheme.typography.titleLarge)
                                Text("Le dossier doit se trouver dans le stockage partagé du téléphone.")
                                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Fermer") }
                            } else {
                                Request(appName, local!!, remote, backend, defaults, loggedIn, onConfirm = { confirm(appName, local, remote, defaults, caller) }, onCancel = {
                                    setResult(RESULT_CANCELED); finish()
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Request(appName: String, local: File, remote: String, backend: String, defaults: Defaults, loggedIn: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
        Text("$appName veut synchroniser un dossier", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Téléphone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(local.path)
        Text(backend, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("/$remote")
        Text("Réglages proposés", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(defaults.summary())
        if (!loggedIn) {
            Text("Configure d'abord $backend dans Synchrotron.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { startActivity(Intent(this@RegisterFolderActivity, MainActivity::class.java)); finish() }, modifier = Modifier.fillMaxWidth()) {
                Text("Ouvrir Synchrotron")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Refuser") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Autoriser") }
            }
        }
    }

    private fun confirm(appName: String, local: File, remote: String, defaults: Defaults, caller: String?) {
        val ctx = applicationContext
        lifecycleScope.launch {
            val dao = AppDb.get(ctx).pairs()
            val existing = dao.byLocalPath(local.path)
            val id = existing?.id ?: run {
                local.mkdirs()
                val pair = PairEntity(
                    name = appName, localPath = local.path, remotePath = remote, requestedByPackage = caller,
                    conflictPolicy = defaults.conflictPolicy, intervalMinutes = defaults.intervalMinutes,
                    watchChanges = defaults.watchChanges, exclusions = defaults.exclusions,
                )
                dao.insert(pair).also { Scheduler.schedule(ctx, pair.copy(id = it)) }
            }
            setResult(RESULT_OK, Intent().putExtra("pair_id", id))
            // The first sync (with its preview) is confirmed inside Synchrotron.
            startActivity(
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAIR_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }

    /** Optional suggestions from the requesting app; invalid values fall back to Synchrotron's defaults. */
    private class Defaults(
        val conflictPolicy: ConflictPolicy, val intervalMinutes: Int, val watchChanges: Boolean, val exclusions: String,
    ) {
        fun summary() = buildString {
            append(if (intervalMinutes == 0) "Synchro manuelle" else "Synchro toutes les ${intervalMinutes.toLabel()}")
            if (watchChanges) append(" + dès qu'un fichier change")
            append("\nConflits : ${conflictPolicy.label.lowercase()}")
            if (exclusions.isNotBlank()) append("\nExclus : ${exclusions.lines().filter { it.isNotBlank() }.joinToString(", ")}")
        }

        private fun Int.toLabel() = if (this % 60 == 0) "${this / 60} h" else "$this min"

        companion object {
            private val intervals = setOf(0, 15, 30, 60, 180, 360, 720, 1440)

            fun from(intent: Intent): Defaults = Defaults(
                conflictPolicy = runCatching { ConflictPolicy.valueOf(intent.getStringExtra("conflict_policy").orEmpty()) }
                    .getOrDefault(ConflictPolicy.KEEP_BOTH),
                intervalMinutes = intent.getIntExtra("interval_minutes", 60).takeIf { it in intervals } ?: 60,
                watchChanges = intent.getBooleanExtra("watch_changes", false),
                exclusions = intent.getStringExtra("exclusions").orEmpty().lines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains("..") }.take(20).joinToString("\n").take(500),
            )
        }
    }

    private fun sanitizeRemote(requested: String?, appName: String): String {
        val raw = requested?.takeIf { it.isNotBlank() } ?: "Apps/$appName"
        return raw.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/").ifEmpty { "Apps/$appName" }
    }
}
