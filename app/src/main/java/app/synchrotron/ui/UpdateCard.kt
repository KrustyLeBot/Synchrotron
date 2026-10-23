package app.synchrotron.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synchrotron.AppViewModel
import app.synchrotron.BuildConfig
import app.synchrotron.UpdateState
import app.synchrotron.update.Updater

/**
 * Self-update UI. Needs no Proton login, so it is shown on the onboarding screen too.
 * With [alwaysVisible] false it only appears when there is something to do.
 */
@Composable
fun UpdateCard(vm: AppViewModel, alwaysVisible: Boolean = false) {
    val context = LocalContext.current
    val update by vm.update.collectAsState()
    val progress by vm.progress.collectAsState()
    var syncWarning by remember { mutableStateOf(false) }

    if (!alwaysVisible && (update is UpdateState.Idle || update is UpdateState.UpToDate || update is UpdateState.Checking || update is UpdateState.Failed)) return

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
            when (val u = update) {
                UpdateState.Idle -> Unit
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateState.UpToDate -> Text("Tu as la dernière version.", style = MaterialTheme.typography.bodySmall)
                is UpdateState.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is UpdateState.Available -> {
                    Text("Build ${u.info.build} disponible (${formatBytes(u.info.sizeBytes)})")
                    Button(onClick = { vm.downloadUpdate(u.info) }) { Text("Télécharger") }
                }
                is UpdateState.Downloading -> {
                    Text("Téléchargement…")
                    LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpdateState.Ready -> Button(onClick = {
                    when {
                        progress.isNotEmpty() -> syncWarning = true // never install in the middle of a sync
                        !Updater.canInstall(context) -> Updater.openInstallPermission(context)
                        else -> Updater.install(context, u.apk)
                    }
                }) { Text("Installer le build ${u.info.build}") }
            }
            if (alwaysVisible && update !is UpdateState.Downloading) {
                OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Vérifier maintenant") }
            }
        }
    }
    if (syncWarning) {
        AlertDialog(
            onDismissRequest = { syncWarning = false },
            title = { Text("Synchro en cours") },
            text = { Text("Attends la fin de la synchronisation avant d'installer, ou annule-la depuis l'accueil.") },
            confirmButton = { TextButton(onClick = { syncWarning = false }) { Text("OK") } },
        )
    }
}
