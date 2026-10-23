package app.synchrotron.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.synchrotron.AppViewModel
import app.synchrotron.BuildConfig
import app.synchrotron.UpdateState
import app.synchrotron.update.Updater
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, onLogin: () -> Unit, onLoggedOut: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email by vm.email.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    val update by vm.update.collectAsState()
    val progress by vm.progress.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var syncWarning by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Réglages", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Compte Proton")
            SettingsCard {
                Text(if (loggedIn) email ?: "Connecté" else "Non connecté", fontWeight = FontWeight.SemiBold)
                Text(
                    if (loggedIn) "Session active, chiffrée sur le téléphone." else "Session expirée ou absente.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loggedIn) OutlinedButton(onClick = { confirmLogout = true }) { Text("Se déconnecter") }
                else Button(onClick = onLogin) { Text("Se reconnecter") }
            }

            SectionTitle("Mises à jour")
            SettingsCard {
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
                if (update !is UpdateState.Downloading) {
                    OutlinedButton(onClick = { vm.checkUpdate() }) { Text("Vérifier maintenant") }
                }
            }

            SectionTitle("Fonctionnement en arrière-plan")
            SettingsCard {
                Text("Pour des synchros fréquentes, exclus Synchrotron de l'optimisation de batterie.", style = MaterialTheme.typography.bodySmall)
                val pm = context.getSystemService(PowerManager::class.java)
                if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    Text("Déjà exclu ✓", style = MaterialTheme.typography.bodySmall)
                } else {
                    OutlinedButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }) { Text("Exclure de l'optimisation") }
                }
            }

            SectionTitle("Diagnostic")
            SettingsCard {
                Text("rclone ${BuildConfig.RCLONE_VERSION} embarqué", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = {
                    scope.launch {
                        val file = vm.exportLogs()
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Exporter les logs",
                            )
                        )
                    }
                }) { Text("Exporter les logs") }
            }

            SectionTitle("À propos")
            Text(
                "Application tierce, non officiellement supportée par Proton. Synchronisation par rclone (backend Proton Drive, en bêta : une évolution côté Proton peut la casser temporairement).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Gap(32)
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Se déconnecter ?") },
            text = { Text("Les synchronisations s'arrêtent jusqu'à la prochaine connexion. Tes fichiers ne sont pas touchés.") },
            confirmButton = { TextButton(onClick = { vm.logout(); confirmLogout = false; onLoggedOut() }) { Text("Se déconnecter") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Annuler") } },
        )
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

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) { content() }
    }
}
