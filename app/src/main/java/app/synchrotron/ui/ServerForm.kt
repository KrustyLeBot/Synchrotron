package app.synchrotron.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.synchrotron.AppViewModel
import kotlinx.coroutines.launch

/** Settings of the Synchrotron server: tested (PROPFIND on the root) before being saved. */
@Composable
fun ServerForm(vm: AppViewModel, saveLabel: String = "Enregistrer", onSaved: () -> Unit) {
    var cfg by remember { mutableStateOf(vm.serverConfig()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) } // (ok, text)
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Serveur WebDAV auto-hébergé, derrière Cloudflare Access. Les réglages sont chiffrés par le Keystore Android.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Adresse", cfg.url, keyboard = KeyboardType.Uri) { cfg = cfg.copy(url = it) }
        Field("Utilisateur", cfg.user) { cfg = cfg.copy(user = it) }
        Field("Mot de passe", cfg.password, secret = true) { cfg = cfg.copy(password = it) }
        Field("Cloudflare Client ID", cfg.cfId) { cfg = cfg.copy(cfId = it) }
        Field("Cloudflare Client Secret", cfg.cfSecret, secret = true) { cfg = cfg.copy(cfSecret = it) }
        Field(
            "Adresse locale (facultatif)", cfg.lanUrl, keyboard = KeyboardType.Uri,
            hint = "ex. http://192.168.1.20:8090 : utilisée à la place de l'autre quand elle répond (à la maison).",
        ) { cfg = cfg.copy(lanUrl = it) }
        message?.let { (ok, text) ->
            Text(text, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && cfg.complete, modifier = Modifier.weight(1f),
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        val err = vm.testServer(cfg)
                        busy = false
                        message = if (err == null) true to "Connexion réussie" else false to err
                    }
                },
            ) { Text("Tester la connexion") }
            Button(
                enabled = !busy && cfg.complete, modifier = Modifier.weight(1f),
                onClick = {
                    busy = true; message = null
                    scope.launch {
                        val err = vm.saveServer(cfg)
                        busy = false
                        if (err == null) { message = true to "Enregistré"; onSaved() } else message = false to err
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                Text(saveLabel)
            }
        }
    }
}

@Composable
private fun Field(
    label: String, value: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    hint: String? = null, onChange: (String) -> Unit,
) {
    // Secrets can be shown: a keyboard that changes a character is the usual cause of a refused login.
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret && !shown) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
        ),
        trailingIcon = if (secret) {
            {
                androidx.compose.material3.IconButton(onClick = { shown = !shown }) {
                    androidx.compose.material3.Icon(
                        if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (shown) "Masquer" else "Afficher",
                    )
                }
            }
        } else null,
        supportingText = if (hint != null) { { Text(hint) } } else null,
    )
}
