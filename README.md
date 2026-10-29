# Synchrotron

Bidirectional sync between folders on your Android phone and Proton Drive, or a self-hosted
**Synchrotron server** (WebDAV on a NAS, behind Cloudflare Access). One backend at a time, chosen in the settings.

> Third-party app, not officially supported by Proton.

- Pairs: phone folder ⇄ remote folder, with mode (two-way / upload only / download only),
  conflict policy, exclusions, schedule (WiFi / charging) and optional sync-on-change.
- Powered by [rclone](https://rclone.org) (`protondrive` backend + `bisync`), embedded through `librclone`.
- Proton authentication (SRP + 2FA) and end-to-end encryption are done by rclone's Proton client: files are
  encrypted on Proton's servers and in clear on the phone.
- Synchrotron server backend: Synchrotron's own WebDAV sync engine (`rclone-bridge/davsync`), see below.
- Other apps can ask Synchrotron to sync their folder with the tiny [`client`](client) library.
- Updates itself from the rolling `latest` GitHub release.

## How it works

| Piece | Where |
|---|---|
| Android app (Compose, Room, WorkManager) | `app/` |
| gomobile wrapper around rclone, tests | `rclone-bridge/bridge` (rclone version pinned in `go.mod`) |
| WebDAV client + sync engine of the Synchrotron server backend, tests | `rclone-bridge/davsync` |
| `synchrotron-client` library for other apps | `client/` |
| CI: build `librclone.aar`, test, sign, publish `latest` | `.github/workflows/build-apk.yml` |

### Synchrotron server backend
The server is `rclone serve webdav --etag-hash MD5` on the NAS (default address `https://synchrotron.jbrhomelab.com/`),
behind a Cloudflare Access service token; Windows PCs also write its files directly over SMB. Same folder tree
as on Proton (`Apps/<App>/…`). Settings: address, user, password, Cloudflare Client ID / Secret and an optional
LAN address (`http://IP:8090`, used whenever it answers), encrypted with the Keystore (`server.bin`), tested
with a `PROPFIND Depth: 0` on the root before being saved.

- Every request carries preemptive Basic auth and the `CF-Access-Client-Id/Secret` headers; redirects are never
  followed (a redirect to `*.cloudflareaccess.com` means a bad token). Folders are listed with `Depth: 1` only.
- Changes are found by content: the server ETag is the MD5 of the file, compared with the MD5 recorded per file
  at the last sync (`noBackupFiles/server-state/pair-N.json`; the local MD5 is only recomputed when size or
  time changed). The server's modification times are upload times and are never used to decide.
- Uploads go to `.<name>.synctmp-<uuid>` then `MOVE` with `Overwrite: T`, after a `PROPFIND Depth: 0` checks
  that the target still has the known ETag (the server has no conditional PUT); otherwise it is a conflict.
  Downloads are written the same way locally (temporary, `fsync`, rename). A `409` creates the parents with
  `MKCOL`. Leftover `*.synctmp-*` older than an hour are deleted on both sides.
- Conflicts follow the pair's policy and bisync's naming (`name.conflictN`); deletions are guarded like with
  bisync (share of deletions, emptied side). A missing or unreadable state never deletes anything: the sync
  only merges by content. That is also how a backend switch works: the state starts empty, identical files
  are recognised by their MD5 and nothing is downloaded again.
- Errors: 401 → check the user/password; 403, redirect or HTML page → check the Cloudflare token (and Bot
  Fight Mode); 502 / 520–530 / unreachable → offline, retried by WorkManager with a backoff (30 s → 16 min),
  without alerting; 413 (over ~100 MB) → the file is reported once and skipped until it changes.

```
cd rclone-bridge && go test ./davsync/                                  # against an in-process WebDAV server
DAVSYNC_RCLONE=/path/to/rclone go test -run Real ./davsync/            # against the real rclone serve webdav
```

### Speed
- rclone keeps each opened remote (the Proton client with its unlocked keys) for the life of the process,
  so only the first sync after a process start pays the Proton login (remotes are dropped when Proton
  rotates the tokens). Proton's metadata cache stays on: folder contents are always fetched, and
  without it each listed file costs one API call per parent folder.
- Syncs run one at a time on purpose: two Proton clients refreshing the same session concurrently
  would get it revoked.
- Apps reach Synchrotron through the bound `SyncService`; the folder watcher runs syncs in its foreground
  service. WorkManager (which may delay jobs of a rarely opened app) is only used for periodic and UI syncs.
- Each sync log starts with the time spent in every phase (start, queue, engine, Proton, sync).

### Session & security
- The Proton password is used once to log in and **never stored**. The resulting session tokens are kept in an
  AES-256-GCM blob whose key lives in the Android Keystore (`ConfigVault`).
- rclone needs an `rclone.conf`; it is written to app-private storage only while a sync runs and deleted right after.
- Backups are disabled (`allowBackup=false`).

### Integration intents
| Intent | Extras | Effect |
|---|---|---|
| `app.synchrotron.action.REGISTER_FOLDER` (activity) | `local_path`, `app_name`, `remote_path?` | Shows a confirmation, then creates a pair. Never silent. |
| `app.synchrotron.action.BIND_SYNC` (bound service, Messenger) | `local_path` in `MSG_SYNC` | Fast path used by `syncOnOpen`: syncs an **existing** pair at once in Synchrotron's process (kept at the caller's priority while bound), answers `MSG_ANSWER` then `MSG_FINISHED`. |
| `app.synchrotron.action.SYNC_NOW` (broadcast) | `pair_id` or `local_path` | Syncs an **existing** pair through WorkManager. Cannot create anything. |
| `app.synchrotron.action.SYNC_FINISHED` (broadcast) | `pair_id`, `status`, `changes` (+`local_path` for the requesting app) | Sent after each sync. |

```kotlin
SynchrotronClient.registerFolder(context, notesDir.path, "Griffonote")
SynchrotronClient.syncNow(context, notesDir.path)
val stop = SynchrotronClient.onSyncFinished(context) { reload() }
```

## The suite

Synchrotron is the sync hub of a small suite of personal apps, built the same way:

| App | Accent | Local folder (Android) | Remote folder | Windows |
|---|---|---|---|---|
| Synchrotron | violet `#6D4AFF` | — | — | — |
| Griffonote (notes) | amber `#F9A825` | folder chosen in the app | `Apps/Griffonote` | the folder synced by Proton's client, or the server's share over SMB |
| Ecoutify (music) | teal `#1AAE9F` | `Ecoutify/` | `Apps/Ecoutify` | same |
| Météorite (weather) | blue `#2563EB` | `Documents/Meteorite/` | `Apps/Meteorite` | — |
| PersonalDashboard | — | `Documents/PersonalDashboard/` | `Apps/PersonalDashboard` | same |

Shared rules:

- **Data in plain files** in a public folder (JSON or Markdown), written atomically: content to
  `.<name>.synctmp-<uuid>` in the same folder, flushed and `fsync`ed, then renamed over the file (the
  temporary is deleted on error). `.*.synctmp-*`, `*.tmp` and `*.part` are always excluded from the sync.
  Reads are tolerant: a file that fails to parse is read again before being reported (a sync may be
  replacing it), and the apps re-read their files after each sync (Windows: when the folder changes).
  No two names differing only by case, no `\ / : * ? " < > |` and no trailing space or dot. An app can leave other files out
  with a `.syncignore` at the root of its folder: one pattern per line, gitignore-style (`*.log`,
  `cache/`, `/top-only`, `#` comments; no `!` negations), added to the pair's own exclusions.
  Changing it makes the next sync a resync (newer file wins), which is automatic. An unreadable file is reported and never overwritten.
  Device-only state (caches, the phone position, UI preferences) stays private and out of the sync.
- **Sync on open**: each app embeds a copy of `client/` (`SynchrotronClient`), asks for a sync when
  it opens, shows its data read-only with a banner while it runs and re-reads it afterwards. The
  apps have no sync settings: folders are added in Synchrotron (policy *newer wins* and watch
  changes recommended). Nothing happens when Synchrotron is absent or the folder is not added.
- **Look**: fixed brand palette per app (never the wallpaper colors), generated with Material's
  tonal-spot recipe from the accent (`SuitePalette.kt`), the same rounded shapes, and a launcher
  icon in the same style: diagonal gradient of the accent, white glyph with one light detail,
  monochrome layer for themed icons. The Windows apps use the same palette and icon.
- **Suite kit**: `app/src/main/java/app/synchrotron/suite/` is copied as is (package line aside) into
  every app: `SuiteUpdater` + `SuiteUpdates` (quiet check on open, banner with download progress and
  install, manual check in Settings), and `SuiteUi` (`SuiteBanner` for states under the top bar,
  `SuiteTopBar`, settings sections and cards, `ConfirmDialog`). Short confirmations ("Note
  supprimée · Annuler") are snackbars; dialogs are only for destructive choices.
- **Builds**: AGP 8.9.1, Kotlin 2.1.20, Compose BOM 2025.04.01, minSdk 26 / target 35; every push to
  `main` publishes a release build to the rolling `latest` release, and each app updates itself
  from it. Signing keys never change.

## Build

```
./scripts/build-librclone.sh      # needs Go (see go.mod), Android NDK, ANDROID_NDK_HOME
./gradlew :app:assembleRelease
cd rclone-bridge && go test ./bridge/   # runs real bisync/rclone calls on local folders
```
Only `arm64-v8a` is built.

## Release signing (one time)

The APK must always be signed with the same key, otherwise Android refuses updates (and the app holds your Proton session).

```
keytool -genkeypair -v -keystore synchrotron.jks -alias synchrotron -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 synchrotron.jks     # macOS: base64 -i synchrotron.jks
```
In GitHub → Settings → Environments → create `release`, restrict it to the `main` branch, and add the secrets
`SYNCHROTRON_KEYSTORE_BASE64`, `SYNCHROTRON_KEYSTORE_PASSWORD`, `SYNCHROTRON_KEY_PASSWORD`.
Keep an offline backup of `synchrotron.jks` and enable 2FA on GitHub. Never change the key afterwards.

Until the secrets exist, CI still builds an APK (debug key) as a workflow artifact but does **not** publish `latest`.
