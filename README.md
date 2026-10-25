# Synchrotron

Bidirectional sync between folders on your Android phone and Proton Drive.

> Third-party app, not officially supported by Proton.

- Pairs: phone folder ⇄ Proton Drive folder, with mode (two-way / upload only / download only),
  conflict policy, exclusions, schedule (WiFi / charging) and optional sync-on-change.
- Powered by [rclone](https://rclone.org) (`protondrive` backend + `bisync`), embedded through `librclone`.
- Proton authentication (SRP + 2FA) and end-to-end encryption are done by rclone's Proton client: files are
  encrypted on Proton's servers and in clear on the phone.
- Other apps can ask Synchrotron to sync their folder with the tiny [`client`](client) library.
- Updates itself from the rolling `latest` GitHub release.

## How it works

| Piece | Where |
|---|---|
| Android app (Compose, Room, WorkManager) | `app/` |
| gomobile wrapper around rclone, tests | `rclone-bridge/` (rclone version pinned in `go.mod`) |
| `synchrotron-client` library for other apps | `client/` |
| CI: build `librclone.aar`, test, sign, publish `latest` | `.github/workflows/build-apk.yml` |

### Speed
- rclone keeps each opened remote (the Proton client with its unlocked keys) for the life of the process,
  so only the first sync after a process start pays the Proton login (Proton's metadata cache is off so
  changes made on other devices are always seen; remotes are dropped when Proton rotates the tokens).
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
