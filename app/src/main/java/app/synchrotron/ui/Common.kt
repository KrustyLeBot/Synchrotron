package app.synchrotron.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.synchrotron.data.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
        actions = { actions() },
    )
}

@Composable
fun statusColor(status: SyncStatus): Color = when (status) {
    SyncStatus.OK -> Color(0xFF2E9E5B)
    SyncStatus.RUNNING -> MaterialTheme.colorScheme.primary
    SyncStatus.CONFLICTS, SyncStatus.BLOCKED -> Color(0xFFE08A00)
    SyncStatus.ERROR -> MaterialTheme.colorScheme.error
    SyncStatus.NEVER -> MaterialTheme.colorScheme.outline
}

@Composable
fun StatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(statusColor(status), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(status.label, style = MaterialTheme.typography.labelLarge, color = statusColor(status))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/** A radio list where every option explains itself. */
@Composable
fun <T> ChoiceGroup(options: List<T>, selected: T, label: (T) -> String, hint: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth().selectable(isSelected, role = Role.RadioButton) { onSelect(option) },
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(10.dp))
                    Column {
                        Text(label(option), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(hint(option), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

fun relativeTime(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return "jamais"
    val s = (now - ms) / 1000
    return when {
        s < 60 -> "à l'instant"
        s < 3600 -> "il y a ${s / 60} min"
        s < 86400 -> "il y a ${s / 3600} h"
        s < 7 * 86400 -> "il y a ${s / 86400} j"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b o"
    b < 1024 * 1024 -> "${b / 1024} Ko"
    b < 1024L * 1024 * 1024 -> "%.1f Mo".format(b / 1048576.0)
    else -> "%.2f Go".format(b / 1073741824.0)
}

@Composable
fun Gap(h: Int = 12) = Spacer(Modifier.height(h.dp))

/** Opaque cover shown while the app is locked. */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Synchrotron est verrouillé", style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.Button(onClick = onUnlock) { Text("Déverrouiller") }
        }
    }
}
