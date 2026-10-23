package app.synchrotron.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.synchrotron.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    var filesOk by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var step by remember { mutableIntStateOf(if (Environment.isExternalStorageManager()) 2 else 0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesOk = Environment.isExternalStorageManager() }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpdateCard(vm) // updates must be possible before any login
            Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary)
            Text("Synchrotron", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            when (step) {
                0 -> {
                    Text(
                        "Synchronise des dossiers de ton téléphone avec Proton Drive, dans les deux sens.",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Application tierce, non officiellement supportée par Proton.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tes fichiers sont chiffrés de bout en bout par Proton sur ses serveurs. Sur le téléphone ils restent en clair, comme n'importe quel dossier.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Commencer") }
                }
                1 -> {
                    Text("Autorisations", style = MaterialTheme.typography.titleLarge)
                    PermissionRow(
                        "Accès à tous les fichiers",
                        "Indispensable pour lire et écrire les dossiers d'autres applications.",
                        granted = filesOk,
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                        var notifOk by remember {
                            mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        }
                        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                            notifOk = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        PermissionRow("Notifications", "Pour te prévenir des erreurs et des conflits.", granted = notifOk) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    Button(onClick = { step = 2 }, enabled = filesOk, modifier = Modifier.fillMaxWidth()) { Text("Continuer") }
                }
                else -> LoginForm(vm, onDone)
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, text: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) Icon(Icons.Default.CheckCircle, "Accordé", tint = androidx.compose.ui.graphics.Color(0xFF2E9E5B))
        else OutlinedButton(onClick = onGrant) { Text("Autoriser") }
    }
}

@Composable
fun LoginForm(vm: AppViewModel, onDone: () -> Unit) {
    var email by remember { mutableStateOf(vm.email.value.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var mailbox by remember { mutableStateOf("") }
    var twoPasswords by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Text("Connexion à Proton", style = MaterialTheme.typography.titleLarge)
    Text(
        "Ton mot de passe sert uniquement à ouvrir la session. Il n'est jamais enregistré : seule la session est gardée, chiffrée par le Keystore Android.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(email, { email = it }, label = { Text("Email Proton") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
    OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code 2FA (si activée)") }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    if (twoPasswords) {
        OutlinedTextField(mailbox, { mailbox = it }, label = { Text("Mot de passe de boîte") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    } else {
        TextButton(onClick = { twoPasswords = true }) { Text("Mon compte a deux mots de passe") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        enabled = !busy && email.isNotBlank() && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            busy = true; error = null
            scope.launch {
                val err = vm.login(email, password, code, mailbox)
                busy = false
                if (err == null) { password = ""; onDone() } else error = err
            }
        },
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
        Text(if (busy) "Connexion…" else "Se connecter")
    }
}
