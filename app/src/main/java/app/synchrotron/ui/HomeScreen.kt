package app.synchrotron.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synchrotron.AppViewModel
import app.synchrotron.UpdateState
import app.synchrotron.data.PairEntity
import app.synchrotron.data.SyncStatus
import app.synchrotron.rclone.Progress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenPair: (Long) -> Unit,
    onNewPair: () -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
) {
    val pairs by vm.pairs.collectAsState()
    val progress by vm.progress.collectAsState()
    val loggedIn by vm.loggedIn.collectAsState()
    val update by vm.update.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(app.synchrotron.R.drawable.ic_logo), null,
                            Modifier.size(32.dp),
                        )
                        Text("  Synchrotron", fontWeight = FontWeight.Bold)
                    }
                },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewPair, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Nouvelle paire") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pairs.isNotEmpty()) item { SummaryCard(pairs, progress.size) }
            if (!loggedIn) item {
                BannerCard(
                    icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
                    title = "Session Proton expirée",
                    text = "Les synchronisations sont en pause.",
                    action = "Se reconnecter", onAction = onLogin,
                )
            }
            (update as? UpdateState.Available)?.let { u ->
                item {
                    BannerCard(
                        icon = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "Mise à jour disponible", text = "Build ${u.info.build}",
                        action = "Voir", onAction = onSettings,
                    )
                }
            }
            if (pairs.isEmpty()) item { EmptyState() }
            items(pairs, key = { it.id }) { pair ->
                PairCard(
                    pair = pair, progress = progress[pair.id],
                    onClick = { onOpenPair(pair.id) },
                    onSync = { if (progress.containsKey(pair.id)) vm.cancelSync(pair.id) else if (pair.initialized || pair.mode != app.synchrotron.data.SyncMode.BIDIRECTIONAL) vm.syncNow(pair.id) else onOpenPair(pair.id) },
                )
            }
        }
    }
}

@Composable
private fun BannerCard(icon: @Composable () -> Unit, title: String, text: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(app.synchrotron.R.drawable.ic_logo), null,
            Modifier.size(96.dp).padding(bottom = 16.dp),
        )
        Text("Aucune paire pour l'instant", style = MaterialTheme.typography.titleMedium)
        Text(
            "Une paire relie un dossier du téléphone à un dossier Proton Drive.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun PairCard(pair: PairEntity, progress: Progress?, onClick: () -> Unit, onSync: () -> Unit) {
    val running = progress != null
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(if (running) SyncStatus.RUNNING else pair.lastStatus)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pair.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${pair.localPath.removePrefix("/storage/emulated/0/")}  ⇄  /${pair.remotePath}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalIconButton(onClick = onSync) {
                    Icon(if (running) Icons.Default.Close else Icons.Default.Sync, if (running) "Annuler" else "Synchroniser")
                }
            }
            Gap(8)
            if (running) {
                val p = progress!!
                if (p.totalBytes > 0) LinearProgressIndicator(progress = { (p.bytes.toFloat() / p.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    if (p.file.isNotEmpty()) p.file else "Analyse…",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(pair.lastStatus)
                    Text(
                        if (pair.lastSyncAt > 0) "  ·  ${relativeTime(pair.lastSyncAt)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pair.lastMessage.isNotBlank() && pair.lastStatus != SyncStatus.OK) {
                    Text(pair.lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: SyncStatus) {
    val color = statusColor(status)
    val icon = when (status) {
        SyncStatus.OK -> Icons.Default.CheckCircle
        SyncStatus.ERROR -> Icons.Default.ErrorOutline
        SyncStatus.CONFLICTS, SyncStatus.BLOCKED -> Icons.Default.Warning
        else -> Icons.Default.Sync
    }
    Box(
        Modifier.size(44.dp).background(color.copy(alpha = 0.14f), androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = color) }
}

@Composable
private fun SummaryCard(pairs: List<PairEntity>, running: Int) {
    val problems = pairs.count { it.lastStatus == SyncStatus.ERROR || it.lastStatus == SyncStatus.BLOCKED || it.lastStatus == SyncStatus.CONFLICTS }
    val last = pairs.maxOf { it.lastSyncAt }
    val title = when {
        running > 0 -> "Synchronisation en cours…"
        problems > 0 -> "$problems paire${if (problems > 1) "s" else ""} à vérifier"
        pairs.any { it.lastStatus == SyncStatus.NEVER } -> "Prêt à synchroniser"
        else -> "Tout est à jour"
    }
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    Box(Modifier.fillMaxWidth().background(brush, MaterialTheme.shapes.large).padding(20.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            Text(
                "${pairs.size} paire${if (pairs.size > 1) "s" else ""}" + if (last > 0) "  ·  dernière synchro ${relativeTime(last)}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            )
        }
    }
}
