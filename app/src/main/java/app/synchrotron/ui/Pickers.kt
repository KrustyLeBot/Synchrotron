package app.synchrotron.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class FolderItem(val name: String, val path: String)

/**
 * Generic folder browser. [list] returns the sub-folders of a path, [create] makes a new one.
 * [root] is the topmost folder the user can reach ("" for the Proton Drive root).
 */
@Composable
fun FolderPicker(
    title: String,
    root: String,
    initial: String,
    rootLabel: String,
    list: suspend (String) -> Result<List<FolderItem>>,
    create: suspend (String) -> Result<Unit>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    var current by remember { mutableStateOf(if (initial.startsWith(root)) initial else root) }
    var items by remember { mutableStateOf<List<FolderItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current, reload) {
        loading = true; error = null
        list(current).onSuccess { items = it }.onFailure { items = emptyList(); error = it.message ?: "Erreur" }
        loading = false
    }

    fun parent(p: String) = if (p == root) null else p.substringBeforeLast('/', "").let { if (it.length < root.length) root else it }

    Scaffold(topBar = { BackTopBar(title, onBack) }, bottomBar = {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Dossier choisi : " + (current.ifEmpty { "/" }.removePrefix(root.trimEnd('/')).ifEmpty { "/" }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { onPick(current) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Choisir ce dossier")
            }
        }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (current == root) rootLabel else current.substringAfterLast('/'),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                )
                parent(current)?.let { up -> IconButton(onClick = { current = up }) { Icon(Icons.Default.KeyboardArrowUp, "Dossier parent") } }
                IconButton(onClick = { creating = true }) { Icon(Icons.Default.CreateNewFolder, "Nouveau dossier") }
            }
            when {
                loading -> CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                items.isEmpty() -> Text("Aucun sous-dossier", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(items, key = { it.path }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { current = f.path },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nouveau dossier") },
            text = { OutlinedTextField(name, { name = it.replace("/", "") }, singleLine = true, label = { Text("Nom") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val target = (current.trimEnd('/') + "/" + name.trim()).removePrefix(if (root.isEmpty()) "/" else "")
                    scope.launch {
                        create(target).onSuccess { current = target }.onFailure { error = it.message }
                        creating = false
                    }
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Annuler") } },
        )
    }
}
