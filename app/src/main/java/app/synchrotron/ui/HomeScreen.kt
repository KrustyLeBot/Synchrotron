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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.synchrotron.AppViewModel
import app.synchrotron.suite.SuiteBanner
import app.synchrotron.suite.Tone
import app.synchrotron.suite.UpdateBanner
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
    val loggedIn by vm.ready.collectAsState()

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
                SuiteBanner(
                    "Les synchronisations sont en pause.", tone = Tone.Error,
                    title = if (vm.backend.value == app.synchrotron.remote.Backend.PROTON) "Session Proton expirée" else "Serveur non configuré",
                    icon = Icons.Default.Warning, inset = false,
                ) {
                    TextButton(onClick = onLogin) {
                        Text(if (vm.backend.value == app.synchrotron.remote.Backend.PROTON) "Se reconnecter" else "Configurer")
                    }
                }
            }
            item { NotificationsBanner() }
            if (vm.updates.showBanner) item { UpdateBanner(vm.updates, inset = false) }
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

/** Without notifications nobody tells the user that a sync is blocked or has conflicts: say so on the home screen. */
@Composable
private fun NotificationsBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reach by remember { mutableStateOf(app.synchrotron.notify.Notifier.reach(context)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) reach = app.synchrotron.notify.Notifier.reach(context) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    /** The system's notification settings of the app; its details page if that screen is not there. Never throws. */
    fun openSettings() {
        val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        val primary = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(flags)
        val fallback = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}")).addFlags(flags)
        try {
            context.startActivity(primary)
        } catch (_: Exception) {
            try { context.startActivity(fallback) } catch (_: Exception) {}
        }
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        reach = app.synchrotron.notify.Notifier.reach(context)
        if (!granted) openSettings() // refused for good: only the system settings can change it
    }
    if (reach != app.synchrotron.notify.Notifier.Reach.OK) {
        SuiteBanner(
            "Tu ne seras pas prévenu des conflits ni des synchros bloquées (suppressions à confirmer).", tone = Tone.Error,
            title = "Notifications désactivées", icon = Icons.Default.Warning, inset = false,
        ) {
            TextButton(onClick = {
                if (reach == app.synchrotron.notify.Notifier.Reach.NO_PERMISSION && android.os.Build.VERSION.SDK_INT >= 33) {
                    try { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { openSettings() }
                } else openSettings()
            }) { Text("Activer") }
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
            "Une paire relie un dossier du téléphone à un dossier dans le cloud (Proton Drive ou ton serveur).",
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
