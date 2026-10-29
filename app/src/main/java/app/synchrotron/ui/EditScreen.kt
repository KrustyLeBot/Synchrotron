package app.synchrotron.ui

import app.synchrotron.suite.SectionTitle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.synchrotron.AppViewModel
import app.synchrotron.data.ConflictPolicy
import app.synchrotron.data.SyncMode
import kotlinx.coroutines.launch

private val intervals = listOf(0 to "Manuel", 15 to "15 min", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h", 720 to "12 h", 1440 to "24 h")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(vm: AppViewModel, onPickLocal: () -> Unit, onPickRemote: () -> Unit, onSaved: (Long) -> Unit, onBack: () -> Unit) {
    val d by vm.draft.collectAsState()
    val scope = rememberCoroutineScope()
    // Syncing a phone folder with the whole Drive root would be a footgun: a sub-folder is required.
    val valid = d.localPath.isNotBlank() && d.remotePath.isNotBlank()

    Scaffold(topBar = { BackTopBar(if (d.id == 0L) "Nouvelle paire" else "Modifier la paire", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(d.name, { vm.draft.value = d.copy(name = it) }, label = { Text("Nom") },
                placeholder = { Text("ex. Notes Griffonote") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Gap()
            FolderRow(Icons.Default.Folder, "Téléphone", d.localPath.ifBlank { "Choisir un dossier" }, onPickLocal)
            Gap(8)
            FolderRow(Icons.Default.Cloud, vm.backendLabel, "/" + d.remotePath, onPickRemote)

            SectionTitle("Mode")
            ChoiceGroup(SyncMode.entries, d.mode, { it.label }, { it.hint }) { vm.draft.value = d.copy(mode = it) }

            if (d.mode == SyncMode.BIDIRECTIONAL) {
                SectionTitle("En cas de conflit")
                ChoiceGroup(ConflictPolicy.entries, d.conflictPolicy, { it.label }, { it.hint }) { vm.draft.value = d.copy(conflictPolicy = it) }
            }

            SectionTitle("Planification")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                intervals.forEach { (min, label) ->
                    FilterChip(selected = d.intervalMinutes == min, onClick = { vm.draft.value = d.copy(intervalMinutes = min) }, label = { Text(label) })
                }
            }
            SwitchRow("Wi-Fi uniquement", d.wifiOnly) { vm.draft.value = d.copy(wifiOnly = it) }
            SwitchRow("Seulement en charge", d.chargingOnly) { vm.draft.value = d.copy(chargingOnly = it) }
            SwitchRow("Synchroniser dès qu'un fichier change", d.watchChanges, "Garde une notification discrète active") {
                vm.draft.value = d.copy(watchChanges = it)
            }

            SectionTitle("Exclusions")
            OutlinedTextField(
                d.exclusions, { vm.draft.value = d.copy(exclusions = it) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Un motif par ligne") }, placeholder = { Text("*.log\n.cache/") },
                supportingText = { Text("S'ajoutent au fichier .syncignore du dossier, s'il existe. *.tmp et *.part sont toujours exclus.") },
            )

            if (d.mode == SyncMode.BIDIRECTIONAL) {
                SectionTitle("Garde-fou : suppressions max. ${d.maxDeletePercent} %")
                Slider(
                    value = d.maxDeletePercent.toFloat(), onValueChange = { vm.draft.value = d.copy(maxDeletePercent = (it / 5).toInt() * 5) },
                    valueRange = 5f..100f,
                )
                Text(
                    "Si une synchro devait supprimer plus de fichiers que ça, elle s'arrête et te demande confirmation.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Gap(24)
            Button(
                enabled = valid, modifier = Modifier.fillMaxWidth(),
                onClick = { scope.launch { onSaved(vm.saveDraft()) } },
            ) { Text("Enregistrer") }
            Gap(24)
        }
    }
}

@Composable
private fun FolderRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
