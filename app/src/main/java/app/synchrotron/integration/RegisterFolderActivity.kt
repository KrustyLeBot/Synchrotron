package app.synchrotron.integration

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
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
import app.synchrotron.data.PairEntity
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.sync.Scheduler
import app.synchrotron.ui.SynchrotronTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * REGISTER_FOLDER: another app proposes a folder to sync. Nothing happens until the user confirms
 * here, with the local folder and the Proton path in plain sight.
 *
 * Extras: `local_path` (absolute, inside shared storage), `app_name`, optional `remote_path`.
 */
class RegisterFolderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appName = intent.getStringExtra("app_name")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val local = intent.getStringExtra("local_path")?.let { runCatching { File(it).canonicalFile }.getOrNull() }
        val valid = local != null && local != root && local.path.startsWith(root.path + "/")
        val remote = sanitizeRemote(intent.getStringExtra("remote_path"), appName ?: local?.name ?: "App")
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host ?: callingActivity?.packageName
        val loggedIn = RcloneEngine.get(this).hasSession

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
                                Request(appName, local!!, remote, loggedIn, onConfirm = { confirm(appName, local, remote, caller) }, onCancel = {
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
    private fun Request(appName: String, local: File, remote: String, loggedIn: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
        Text("$appName veut synchroniser un dossier", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Téléphone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(local.path)
        Text("Proton Drive", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("/$remote")
        if (!loggedIn) {
            Text("Connecte-toi d'abord à Proton dans Synchrotron.", color = MaterialTheme.colorScheme.error)
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

    private fun confirm(appName: String, local: File, remote: String, caller: String?) {
        val ctx = applicationContext
        lifecycleScope.launch {
            val dao = AppDb.get(ctx).pairs()
            val existing = dao.byLocalPath(local.path)
            val id = existing?.id ?: run {
                local.mkdirs()
                val pair = PairEntity(name = appName, localPath = local.path, remotePath = remote, requestedByPackage = caller)
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

    private fun sanitizeRemote(requested: String?, appName: String): String {
        val raw = requested?.takeIf { it.isNotBlank() } ?: "Apps/$appName"
        return raw.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/").ifEmpty { "Apps/$appName" }
    }
}
