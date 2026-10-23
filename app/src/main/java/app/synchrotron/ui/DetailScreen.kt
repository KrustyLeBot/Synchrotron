package app.synchrotron.ui

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.synchrotron.AppViewModel
import app.synchrotron.data.HistoryEntity
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncMode
import app.synchrotron.data.SyncStatus
import app.synchrotron.sync.ConflictFile
import app.synchrotron.sync.ConflictResolver
import app.synchrotron.sync.ConflictScanner
import app.synchrotron.sync.Preview
import app.synchrotron.sync.SyncRunner
import app.synchrotron.sync.WIPE_LOCAL
import app.synchrotron.sync.WIPE_REMOTE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DetailScreen(vm: AppViewModel, pairId: Long, onEdit: (PairEntity) -> Unit, onBack: () -> Unit) {
    val pair by vm.observePair(pairId).collectAsState(initial = null)
    val history by vm.observeHistory(pairId).collectAsState(initial = emptyList())
    val progress by vm.progress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = pair

    var conflicts by remember { mutableStateOf<List<ConflictFile>>(emptyList()) }
    var conflictTick by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var logOf by remember { mutableStateOf<HistoryEntity?>(null) }

    LaunchedEffect(p?.lastSyncAt, conflictTick) {
        if (p != null) conflicts = withContext(Dispatchers.IO) { ConflictScanner.scan(File(p.localPath)) }
    }

    Scaffold(topBar = {
        BackTopBar(p?.name ?: "", onBack) {
            if (p != null) {
                IconButton(onClick = { onEdit(p) }) { Icon(Icons.Default.Edit, "Modifier") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        }
    }) { padding ->
        if (p == null) return@Scaffold
        val running = progress[p.id]
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Text("${p.localPath}\n⇄ /${p.remotePath}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap(8)
            StatusBadge(if (running != null) SyncStatus.RUNNING else p.lastStatus)
            if (p.lastMessage.isNotBlank() && running == null) Text(p.lastMessage, style = MaterialTheme.typography.bodyMedium)
            if (p.lastSyncAt > 0) Text("Dernière synchro ${relativeTime(p.lastSyncAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Gap()

            if (running != null) {
                if (running.totalBytes > 0) LinearProgressIndicator(progress = { (running.bytes.toFloat() / running.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    buildString {
                        append(if (running.file.isNotEmpty()) running.file else "Analyse…")
                        if (running.totalBytes > 0) append("  ·  ${formatBytes(running.bytes)} / ${formatBytes(running.totalBytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedButton(onClick = { vm.cancelSync(p.id) }, modifier = Modifier.fillMaxWidth()) { Text("Annuler") }
            } else if (p.mode == SyncMode.BIDIRECTIONAL && !p.initialized) {
                FirstSyncCard(
                    preview = preview, loading = previewLoading, error = previewError,
                    onAnalyze = {
                        previewLoading = true; previewError = null
                        scope.launch {
                            vm.preview(p).onSuccess { preview = it }.onFailure { previewError = it.message ?: "Analyse impossible" }
                            previewLoading = false
                        }
                    },
                    onConfirm = { vm.syncNow(p.id, resync = true) },
                )
            } else {
                Button(onClick = { vm.syncNow(p.id) }, modifier = Modifier.fillMaxWidth()) { Text("Synchroniser maintenant") }
            }

            if (p.lastStatus == SyncStatus.ERROR && running == null && p.mode == SyncMode.BIDIRECTIONAL && p.initialized) {
                OutlinedButton(onClick = { vm.syncNow(p.id, resync = true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Reconstruire la base de synchro")
                }
                Text(
                    "Compare à nouveau les deux dossiers sans rien supprimer. À essayer si l'erreur persiste.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val localEmpty = p.lastMessage.startsWith(SyncRunner.MSG_LOCAL_EMPTY)
            val remoteEmpty = p.lastMessage.startsWith(SyncRunner.MSG_REMOTE_EMPTY)
            if (p.lastStatus == SyncStatus.BLOCKED && running == null && (localEmpty || remoteEmpty)) {
                Gap()
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.lastMessage, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Rien n'a été supprimé. Était-ce voulu ? Tu peux répercuter la suppression de l'autre côté, ou tout restaurer.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { confirmWipe = true }) {
                            Text(if (localEmpty) "Vider aussi Proton" else "Vider aussi le téléphone")
                        }
                        TextButton(onClick = { vm.syncNow(p.id, resync = true) }) {
                            Text(if (localEmpty) "Restaurer depuis Proton" else "Restaurer depuis le téléphone")
                        }
                    }
                }
            } else if (p.lastStatus == SyncStatus.BLOCKED && running == null) {
                Gap()
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Synchro bloquée par le garde-fou", fontWeight = FontWeight.SemiBold)
                        Text("Elle supprimerait plus de ${p.maxDeletePercent} % des fichiers. Vérifie que c'est voulu avant de continuer.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.syncNow(p.id, force = true) }) { Text("Forcer cette synchro") }
                    }
                }
            }

            if (conflicts.isNotEmpty()) {
                SectionTitle("Conflits (${conflicts.groupBy { it.originalName }.size})")
                conflicts.groupBy { it.originalName }.forEach { (name, group) ->
                    ConflictCard(name, group, File(p.localPath), context, onResolved = {
                        conflictTick++
                        vm.syncNow(p.id)
                    })
                    Gap(8)
                }
            }

            SectionTitle("Historique")
            if (history.isEmpty()) Text("Rien pour l'instant.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.forEach { h ->
                Row(Modifier.fillMaxWidth().clickable { logOf = h }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        StatusBadge(h.status)
                        Text(h.message, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(relativeTime(h.startedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Gap(32)
        }
    }

    if (confirmDelete && p != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la paire ?") },
            text = { Text("Les fichiers restent intacts sur le téléphone et sur Proton Drive ; seule la synchronisation s'arrête.") },
            confirmButton = { TextButton(onClick = { vm.deletePair(p.id); confirmDelete = false; onBack() }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
    if (confirmWipe && p != null) {
        val localEmpty = p.lastMessage.startsWith(SyncRunner.MSG_LOCAL_EMPTY)
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(if (localEmpty) "Vider Proton aussi ?" else "Vider le téléphone aussi ?") },
            text = {
                Text(
                    if (localEmpty) "Tous les fichiers de /${p.remotePath} sur Proton Drive seront supprimés, comme sur le téléphone."
                    else "Tous les fichiers de ${p.localPath} seront supprimés du téléphone, comme sur Proton Drive."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    vm.syncNow(p.id, resync = true, wipe = if (localEmpty) WIPE_REMOTE else WIPE_LOCAL)
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Annuler") } },
        )
    }
    logOf?.let { h ->
        AlertDialog(
            onDismissRequest = { logOf = null },
            title = { Text("Journal") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(h.log.ifBlank { h.message }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { logOf = null }) { Text("Fermer") } },
        )
    }
}

@Composable
private fun FirstSyncCard(preview: Preview?, loading: Boolean, error: String?, onAnalyze: () -> Unit, onConfirm: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Première synchronisation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "On compare d'abord les deux dossiers. Rien n'est supprimé : les fichiers qui n'existent que d'un côté sont copiés de l'autre.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                preview != null -> {
                    Text("${preview.toUpload} fichier(s) envoyés vers Proton", fontWeight = FontWeight.Medium)
                    Text("${preview.toDownload} fichier(s) reçus sur le téléphone", fontWeight = FontWeight.Medium)
                    if (preview.different > 0) Text("${preview.different} fichier(s) différents des deux côtés (les deux versions seront gardées)", fontWeight = FontWeight.Medium)
                    Text("${preview.identical} déjà identiques", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirmer et synchroniser") }
                }
                else -> {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = onAnalyze, modifier = Modifier.fillMaxWidth()) { Text("Analyser") }
                }
            }
        }
    }
}

@Composable
private fun ConflictCard(name: String, group: List<ConflictFile>, root: File, context: android.content.Context, onResolved: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text("Modifié des deux côtés", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                group.sortedBy { it.side }.forEach { c ->
                    TextButton(onClick = { openFile(context, c.file) }) { Text(if (c.side == 1) "Ouvrir (téléphone)" else "Ouvrir (Proton)") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ConflictResolver.keep(root, group, 1); onResolved() }) { Text("Garder téléphone") }
                OutlinedButton(onClick = { ConflictResolver.keep(root, group, 2); onResolved() }) { Text("Garder Proton") }
            }
        }
    }
}

private fun openFile(context: android.content.Context, file: File) {
    val ext = file.name.removeSuffix(Regex("""\.conflict\d+$""").find(file.name)?.value ?: "").substringAfterLast('.', "")
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: "text/plain"
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
