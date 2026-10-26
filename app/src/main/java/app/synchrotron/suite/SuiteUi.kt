// Suite kit: the master copy is in Synchrotron (app/src/main/java/app/synchrotron/suite/). Copy it
// unchanged into each app of the suite; only the package line differs.
package app.synchrotron.suite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What a [SuiteBanner] is about; sets its colors. */
enum class Tone { Info, Accent, Error }

/**
 * The one way the suite shows a state under the top bar (sync running, offline, update, error):
 * a rounded card with an optional title, icon, actions and progress bar. A [progress] of -1 is an
 * indeterminate bar. [inset] adds the usual side margin; leave it off inside an already padded list.
 */
@Composable
fun SuiteBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Info,
    title: String? = null,
    icon: ImageVector? = null,
    progress: Float? = null,
    inset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val (container, content) = when (tone) {
        Tone.Info -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        Tone.Accent -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = container, contentColor = content, shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = if (inset) 12.dp else 0.dp, vertical = if (inset) 6.dp else 0.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodySmall)
                }
                CompositionLocalProvider(LocalContentColor provides content) { actions() }
            }
            when {
                progress == null -> Unit
                progress < 0f -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = content, trackColor = Color.Transparent)
                else -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = content, trackColor = Color.Transparent)
            }
        }
    }
}

/** The update banner: offer, download progress, install. Shows nothing when there is nothing to do. */
@Composable
fun UpdateBanner(updates: SuiteUpdates, modifier: Modifier = Modifier, inset: Boolean = true) {
    if (!updates.showBanner) return
    when (val s = updates.state) {
        is UpdateState.Available -> SuiteBanner(
            "Build ${s.release.build}${size(s.release)}", modifier, Tone.Accent, "Mise à jour disponible", Icons.Default.SystemUpdate, inset = inset,
        ) {
            TextButton(onClick = updates::later) { Text("Plus tard") }
            TextButton(onClick = { updates.download(s.release) }) { Text("Installer") }
        }
        is UpdateState.Downloading -> SuiteBanner(
            "Build ${s.release.build} · ${(s.progress * 100).toInt()} %", modifier, Tone.Accent, "Téléchargement de la mise à jour",
            Icons.Default.SystemUpdate, progress = s.progress, inset = inset,
        ) {
            TextButton(onClick = updates::cancel) { Text("Annuler") }
        }
        is UpdateState.Ready -> SuiteBanner(
            s.note ?: "Build ${s.release.build} téléchargé", modifier, Tone.Accent, "Mise à jour prête", Icons.Default.SystemUpdate, inset = inset,
        ) {
            TextButton(onClick = { updates.install(s) }) { Text("Installer") }
        }
        is UpdateState.Failed -> SuiteBanner(s.message, modifier, Tone.Error, "Mise à jour impossible", Icons.Default.ErrorOutline, inset = inset) {
            TextButton(onClick = updates::later) { Text("Fermer") }
            s.release?.let { r -> TextButton(onClick = { updates.download(r) }) { Text("Réessayer") } }
        }
        else -> Unit
    }
}

private fun size(r: Release) = if (r.sizeBytes > 0) " · ${(r.sizeBytes + 500_000) / 1_000_000} Mo" else ""

/** Top bar of the suite: bold title, back arrow on inner screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuiteTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = if (onBack == null) FontWeight.Bold else FontWeight.SemiBold) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        actions = actions,
    )
}

/** Settings screens: an accent-colored section title above one or more [SettingsCard]s. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Secondary line of a settings card. */
@Composable
fun SettingsHint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

/** "Synchronisation Proton Drive" section: the folder is set up in Synchrotron, the app only asks for syncs. */
@Composable
fun SyncSettings(folder: String) {
    SectionTitle("Synchronisation Proton Drive")
    SettingsCard {
        Text("Par Synchrotron", fontWeight = FontWeight.SemiBold)
        SettingsHint(
            "Ajoute le dossier $folder dans Synchrotron. L'app lui demande une synchro à chaque ouverture " +
                "et reste en lecture seule le temps qu'elle se termine.",
        )
    }
}

/** "Mise à jour" section: installed build, manual check, and the same download flow as the banner. */
@Composable
fun UpdateSettings(updates: SuiteUpdates) {
    SectionTitle("Mise à jour")
    SettingsCard {
        Text("Build ${updates.installedBuild}", fontWeight = FontWeight.SemiBold)
        when (val s = updates.state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
            UpdateState.UpToDate -> SettingsHint("Tu as la dernière version.")
            is UpdateState.Failed -> SettingsHint(s.message, MaterialTheme.colorScheme.error)
            is UpdateState.Available -> {
                SettingsHint("Build ${s.release.build} disponible${size(s.release)}.")
                Button(onClick = { updates.download(s.release) }) { Text("Télécharger et installer") }
            }
            is UpdateState.Downloading -> {
                SettingsHint("Téléchargement… ${(s.progress * 100).toInt()} %")
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                s.note?.let { SettingsHint(it) }
                Button(onClick = { updates.install(s) }) { Text("Installer le build ${s.release.build}") }
            }
        }
        if (updates.state !is UpdateState.Downloading && updates.state !is UpdateState.Checking) {
            OutlinedButton(onClick = updates::check) { Text("Rechercher une mise à jour") }
        }
    }
}

/** "À propos" section, ending every Settings screen of the suite. */
@Composable
fun AboutSettings(appName: String, description: String) {
    SectionTitle("À propos")
    SettingsCard {
        Text(appName, fontWeight = FontWeight.SemiBold)
        SettingsHint(description)
        SettingsHint("Fait partie de la suite Synchrotron · Griffonote · Ecoutify · Météorite.")
    }
}

/** Confirmation before anything destructive; [destructive] paints the confirm button in the error color. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = { onDismiss(); onConfirm() },
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.textButtonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
