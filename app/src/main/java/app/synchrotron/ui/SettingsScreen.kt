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
import app.synchrotron.remote.Backend
import androidx.compose.material3.FilterChip
import app.synchrotron.suite.AboutSettings
import app.synchrotron.suite.ConfirmDialog
import app.synchrotron.suite.SectionTitle
import app.synchrotron.suite.SettingsCard
import app.synchrotron.suite.UpdateSettings
import app.synchrotron.security.BiometricLock
import app.synchrotron.security.Prefs
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: AppViewModel, onLogin: () -> Unit, onLoggedOut: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val email by vm.email.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    val backend by vm.backend.collectAsState()
    var switchTo by remember { mutableStateOf<Backend?>(null) }

    Scaffold(topBar = { BackTopBar("Réglages", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Stockage distant")
            SettingsCard {
                Text("Toutes les paires se synchronisent avec :", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Backend.entries.forEach { b ->
                        FilterChip(selected = backend == b, onClick = { if (b != backend) switchTo = b }, label = { Text(b.label) })
                    }
                }
                Text(
                    "Mêmes dossiers des deux côtés (ex. /Apps/Griffonote). Changer ne supprime rien : la synchro suivante compare les fichiers.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (backend == Backend.PROTON) {
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
            } else {
                SectionTitle("Serveur Synchrotron")
                SettingsCard { ServerForm(vm) {} }
            }

            SectionTitle("Notifications")
            SettingsCard {
                Text("Alertes de synchronisation", fontWeight = FontWeight.SemiBold)
                Text(
                    "Une notification te prévient quand une synchro est bloquée (trop de suppressions à confirmer, dossier vide), " +
                        "quand des conflits sont à résoudre ou quand elle échoue. Elle sonne une fois, puis te le rappelle toutes les 6 h si rien n'a changé.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { app.synchrotron.notify.Notifier.test(context) }) { Text("Envoyer une notification de test") }
            }

            run {
                val crashFile = java.io.File(context.filesDir, app.synchrotron.SynchrotronApp.CRASH_FILE)
                var crash by remember { mutableStateOf(crashFile.takeIf { it.isFile }?.readText()) }
                crash?.let { text ->
                    SectionTitle("Dernier plantage")
                    SettingsCard {
                        Text(text.take(900), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Plantage Synchrotron", text))
                            }) { Text("Copier") }
                            OutlinedButton(onClick = { crashFile.delete(); crash = null }) { Text("Effacer") }
                        }
                    }
                }
            }

            UpdateSettings(vm.updates)

            SectionTitle("Sécurité")
            SettingsCard {
                val activity = context as androidx.fragment.app.FragmentActivity
                var enabled by remember { mutableStateOf(Prefs.biometricLock(context)) }
                val available = BiometricLock.available(context)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Verrouillage biométrique", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (available) "Empreinte, visage ou code de l'appareil à l'ouverture."
                            else "Configure d'abord un verrouillage d'écran sur le téléphone.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    androidx.compose.material3.Switch(checked = enabled, enabled = available, onCheckedChange = { want ->
                        // Prove it works (and that it is really the owner) before changing the setting.
                        BiometricLock.authenticate(activity, "Confirmer") { ok ->
                            if (ok) { Prefs.setBiometricLock(context, want); enabled = want; (activity as? app.synchrotron.MainActivity)?.applySecureFlag() }
                        }
                    })
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
                                    // Some apps only read the text: the newest part of the log goes along.
                                    .putExtra(Intent.EXTRA_TEXT, file.readText().takeLast(100_000))
                                    .apply { clipData = android.content.ClipData.newRawUri("logs", uri) } // setClipData returns void
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                "Exporter les logs",
                            )
                        )
                    }
                }) { Text("Exporter les logs") }
            }

            AboutSettings(
                "Synchrotron",
                "Application tierce, non officiellement supportée par Proton. Synchronisation par rclone (backend Proton Drive, en bêta : une évolution côté Proton peut la casser temporairement) ou avec ton serveur WebDAV.",
            )
            Gap(32)
        }
    }

    switchTo?.let { target ->
        ConfirmDialog(
            "Passer à ${target.label} ?",
            "Les paires gardent leurs dossiers et leurs réglages, les apps ne voient aucune différence. " +
                "Rien n'est supprimé : la prochaine synchro compare les fichiers des deux côtés, copie ce qui manque " +
                "et garde les deux versions de ce qui diffère." +
                if (target == Backend.SERVER) " Copie d'abord tes dossiers Proton sur le serveur, dans la même arborescence." else "",
            "Changer", onConfirm = { vm.switchBackend(target); switchTo = null }, onDismiss = { switchTo = null },
        )
    }

    if (confirmLogout) {
        ConfirmDialog(
            "Se déconnecter ?",
            "Les synchronisations s'arrêtent jusqu'à la prochaine connexion. Tes fichiers ne sont pas touchés.",
            "Se déconnecter", onConfirm = { vm.logout(); onLoggedOut() }, onDismiss = { confirmLogout = false }, destructive = true,
        )
    }
}

