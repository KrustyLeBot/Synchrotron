package app.synchrotron

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.synchrotron.data.AppDb
import app.synchrotron.data.PairEntity
import app.synchrotron.rclone.RcloneEngine
import app.synchrotron.rclone.RcloneException
import app.synchrotron.rclone.RemoteEntry
import app.synchrotron.remote.Backend
import app.synchrotron.remote.Backends
import app.synchrotron.remote.ServerConfig
import app.synchrotron.remote.ServerException
import app.synchrotron.remote.ServerRemote
import app.synchrotron.sync.Preview
import app.synchrotron.sync.Scheduler
import app.synchrotron.sync.SyncRegistry
import app.synchrotron.sync.WatchService
import app.synchrotron.sync.computePreview
import app.synchrotron.suite.SuiteUpdates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File


class AppViewModel(app: Application) : AndroidViewModel(app) {
    val engine = RcloneEngine.get(app)
    private val db = AppDb.get(app)

    val pairs: StateFlow<List<PairEntity>> =
        db.pairs().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress = SyncRegistry.progress
    val busy = engine.busy

    private val _loggedIn = MutableStateFlow(engine.hasSession)
    val loggedIn: StateFlow<Boolean> = _loggedIn
    private val _email = MutableStateFlow(engine.username)
    val email: StateFlow<String?> = _email

    private val _backend = MutableStateFlow(Backends.current(app))
    val backend: StateFlow<Backend> = _backend
    private val _ready = MutableStateFlow(Backends.ready(app))

    /** The chosen backend can sync (Proton session open, or server settings saved). */
    val ready: StateFlow<Boolean> = _ready
    val backendLabel: String get() = _backend.value.label
    private val server = ServerRemote(app)

    /** The pair being created or edited; kept here so the folder pickers can fill it in. */
    val draft = MutableStateFlow(newDraft())

    val updates = SuiteUpdates(app, (app as SynchrotronApp).updater, viewModelScope) {
        if (progress.value.isNotEmpty()) "Attends la fin de la synchro en cours, puis touche « Installer »." else null
    }

    fun refreshSession() {
        val ctx: Context = getApplication()
        _loggedIn.value = engine.hasSession
        _email.value = engine.username
        _backend.value = Backends.current(ctx)
        _ready.value = Backends.ready(ctx)
    }

    fun serverConfig(): ServerConfig = Backends.server(getApplication()) ?: ServerConfig()

    /** Returns an error message, or null when the server answered (PROPFIND on its root). */
    suspend fun testServer(cfg: ServerConfig): String? = try {
        server.test(cfg)
        null
    } catch (e: ServerException) {
        e.message
    } catch (e: Exception) {
        e.message ?: "Connexion impossible"
    }

    /** Tests then saves the server settings; returns an error message, or null on success. */
    suspend fun saveServer(cfg: ServerConfig): String? {
        if (!cfg.complete) return "Adresse, utilisateur et mot de passe sont obligatoires."
        testServer(cfg)?.let { return it }
        Backends.saveServer(getApplication(), cfg)
        refreshSession()
        return null
    }

    /** Every pair now syncs with [to]; see [Backends.switch]. Running syncs are stopped first. */
    fun switchBackend(to: Backend) {
        progress.value.keys.forEach { SyncRegistry.cancel(it) }
        Backends.switch(getApplication(), to)
        refreshSession()
    }

    fun observePair(id: Long) = db.pairs().observe(id)
    fun observeHistory(id: Long) = db.history().observe(id)

    /** Returns an error message, or null on success. */
    suspend fun login(email: String, password: String, twoFa: String, mailbox: String): String? = try {
        engine.login(email.trim(), password, twoFa, mailbox)
        refreshSession()
        null
    } catch (e: Exception) {
        friendlyLoginError(e.message.orEmpty())
    }

    private fun friendlyLoginError(raw: String): String {
        val m = raw.lowercase()
        return when {
            "2fa" in m || "two-factor" in m || "totp" in m || "two factor" in m ->
                "Code 2FA manquant ou invalide. Entre le code à 6 chiffres actuel de ton application d'authentification."
            "captcha" in m || "human verification" in m || "9001" in m ->
                "Proton demande une vérification humaine (captcha) que l'app ne peut pas afficher. Connecte-toi d'abord sur drive.proton.me depuis ce téléphone, puis réessaie."
            "password" in m || "credentials" in m || "422" in m || "8002" in m ->
                "Email ou mot de passe incorrect."
            "mailbox" in m || "key" in m && "decrypt" in m ->
                "Mot de passe de boîte incorrect (compte à 2 mots de passe)."
            else -> raw.lines().lastOrNull { it.isNotBlank() }?.take(300) ?: "Connexion impossible"
        }
    }

    fun logout() {
        engine.logout()
        refreshSession()
    }

    fun newDraft(): PairEntity = PairEntity(name = "", localPath = "", remotePath = "")

    fun startDraft(pair: PairEntity?) {
        draft.value = pair ?: newDraft()
    }

    suspend fun saveDraft(): Long {
        val d = draft.value.let { it.copy(name = it.name.ifBlank { File(it.localPath).name }) }
        val ctx: Context = getApplication()
        val id = if (d.id == 0L) db.pairs().insert(d) else {
            // New folders mean a new bisync baseline: the first sync has to be confirmed again.
            val old = db.pairs().get(d.id)
            val moved = old != null && (old.localPath != d.localPath || old.remotePath != d.remotePath)
            db.pairs().update(if (moved) d.copy(initialized = false) else d)
            d.id
        }
        Scheduler.schedule(ctx, d.copy(id = id))
        WatchService.refresh(ctx)
        return id
    }

    fun deletePair(id: Long) {
        val ctx: Context = getApplication()
        viewModelScope.launch {
            SyncRegistry.cancel(id)
            Scheduler.cancel(ctx, id)
            db.pairs().delete(id)
            File(engine.workDir, "pair-$id").deleteRecursively()
            Backends.forgetPair(ctx, id)
            WatchService.refresh(ctx)
        }
    }

    fun syncNow(id: Long, resync: Boolean = false, force: Boolean = false, wipe: Int = 0) =
        Scheduler.syncNow(getApplication(), id, resync, force, wipe)

    fun cancelSync(id: Long) = SyncRegistry.cancel(id)

    suspend fun preview(pair: PairEntity): Result<Preview> = runCatching { computePreview(getApplication(), engine, pair) }

    /** Sub-folders of a remote path ("" = root). */
    suspend fun remoteDirs(path: String): Result<List<RemoteEntry>> = runCatching {
        when (Backends.current(getApplication())) {
            Backend.PROTON -> engine.session { engine.listRemoteDirs(path) }
            Backend.SERVER -> server.listDirs(path)
        }
    }

    suspend fun createRemoteDir(path: String): Result<Unit> = runCatching {
        when (Backends.current(getApplication())) {
            Backend.PROTON -> engine.session { engine.mkdir(path) }
            Backend.SERVER -> server.mkdir(path)
        }
    }

    /** Plain-text dump of recent sync logs, for the "export logs" button. */
    suspend fun exportLogs(): File {
        val ctx: Context = getApplication()
        val names = db.pairs().all().associate { it.id to it.name }
        val text = buildString {
            appendLine("Synchrotron ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · rclone ${BuildConfig.RCLONE_VERSION}")
            db.history().latest(100).forEach { h ->
                appendLine()
                appendLine("== ${names[h.pairId] ?: h.pairId} · ${java.util.Date(h.startedAt)} · ${h.status} · ${h.message}")
                if (h.log.isNotBlank()) appendLine(h.log)
            }
        }
        return File(ctx.cacheDir, "exports").apply { mkdirs() }.resolve("synchrotron-logs.txt").also { it.writeText(text) }
    }

    fun localDirs(path: String): List<File> =
        File(path).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    val storageRoot: String get() = Environment.getExternalStorageDirectory().absolutePath
}
