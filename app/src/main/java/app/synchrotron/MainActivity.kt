package app.synchrotron

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.synchrotron.ui.DetailScreen
import app.synchrotron.ui.EditScreen
import app.synchrotron.ui.FolderItem
import app.synchrotron.ui.FolderPicker
import app.synchrotron.ui.HomeScreen
import app.synchrotron.ui.OnboardingScreen
import app.synchrotron.ui.SettingsScreen
import app.synchrotron.ui.SynchrotronTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val openPair = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openPair.value = intent?.getLongExtra(EXTRA_PAIR_ID, -1L)?.takeIf { it >= 0 }
        setContent {
            SynchrotronTheme { App(vm, openPair) }
        }
        vm.checkUpdate()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openPair.value = intent.getLongExtra(EXTRA_PAIR_ID, -1L).takeIf { it >= 0 }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshSession()
    }

    companion object { const val EXTRA_PAIR_ID = "pair_id" }
}

@Composable
private fun App(vm: AppViewModel, openPair: MutableStateFlow<Long?>) {
    val nav = rememberNavController()
    val start = if (vm.engine.hasSession && Environment.isExternalStorageManager()) "home" else "onboarding"
    val target by openPair.collectAsState()

    LaunchedEffect(target) {
        target?.let { id ->
            if (start == "home") nav.navigate("pair/$id")
            openPair.value = null
        }
    }

    NavHost(nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(vm) { nav.navigate("home") { popUpTo(nav.graph.id) { inclusive = true } } }
        }
        composable("home") {
            HomeScreen(
                vm,
                onOpenPair = { nav.navigate("pair/$it") },
                onNewPair = { vm.startDraft(null); nav.navigate("edit") },
                onSettings = { nav.navigate("settings") },
                onLogin = { nav.navigate("onboarding") },
            )
        }
        composable("pair/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            DetailScreen(
                vm, entry.arguments!!.getLong("id"),
                onEdit = { vm.startDraft(it); nav.navigate("edit") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("edit") {
            EditScreen(
                vm,
                onPickLocal = { nav.navigate("pick-local") },
                onPickRemote = { nav.navigate("pick-remote") },
                onSaved = { id ->
                    val cameFromHome = nav.previousBackStackEntry?.destination?.route == "home"
                    nav.popBackStack()
                    if (cameFromHome) nav.navigate("pair/$id")
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("pick-local") {
            val draft by vm.draft.collectAsState()
            FolderPicker(
                title = "Dossier du téléphone", root = vm.storageRoot, initial = draft.localPath.ifBlank { vm.storageRoot },
                rootLabel = "Stockage interne",
                list = { path -> Result.success(vm.localDirs(path).map { FolderItem(it.name, it.absolutePath) }) },
                create = { path -> runCatching { check(java.io.File(path).mkdirs()) { "Impossible de créer le dossier" } } },
                onPick = { path ->
                    vm.draft.value = draft.copy(
                        localPath = path,
                        name = draft.name.ifBlank { java.io.File(path).name },
                        remotePath = if (draft.remotePath.isBlank()) "Synchrotron/" + java.io.File(path).name else draft.remotePath,
                    )
                    nav.popBackStack()
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable("pick-remote") {
            val draft by vm.draft.collectAsState()
            FolderPicker(
                title = "Dossier Proton Drive", root = "", initial = draft.remotePath, rootLabel = "Proton Drive",
                list = { path ->
                    vm.remoteDirs(path).map { dirs -> dirs.map { FolderItem(it.name, if (path.isEmpty()) it.name else "$path/${it.name}") } }
                },
                create = { path -> vm.createRemoteDir(path) },
                onPick = { path -> vm.draft.value = draft.copy(remotePath = path); nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                vm,
                onLogin = { nav.navigate("onboarding") },
                onLoggedOut = { nav.navigate("onboarding") { popUpTo(nav.graph.id) { inclusive = true } } },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
