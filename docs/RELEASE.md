# Build, signature, release et mise à jour automatique

Ce document explique comment une modification poussée sur `main` devient une mise à jour installée sur le téléphone, et ce qui protège ce circuit. Aucune valeur secrète n'est écrite ici.

## 1. Vue d'ensemble

```
git push main
   │
   ▼
GitHub Actions (.github/workflows/build-apk.yml)
   ├─ tests Go du pont rclone            (rclone-bridge/bridge/*_test.go)
   ├─ build de librclone.aar (gomobile)  (scripts/build-librclone.sh, mis en cache)
   ├─ décodage de la clé de signature    (secrets de l'environnement « release »)
   ├─ ./gradlew :app:assembleRelease     (APK signé)
   └─ release GitHub « latest » + synchrotron.apk
                                           │
                                           ▼
Téléphone : Updater.kt lit « latest » → télécharge → vérifie → l'utilisateur installe
```

## 2. Versions

| Élément | Valeur | Où |
|---|---|---|
| `versionCode` | numéro d'exécution du workflow (`GITHUB_RUN_NUMBER`) | `app/build.gradle.kts` |
| `versionName` | `0.1.<build>` | idem |
| Titre de la release | `Synchrotron build <N>` | workflow, étape « Publish rolling release » |
| Version de rclone | épinglée dans `rclone-bridge/go.mod` | affichée dans Réglages |

Le `versionCode` ne fait qu'augmenter, ce que Android exige pour accepter une mise à jour. Le numéro d'exécution continue de monter même si on supprime des runs ; il ne repart de zéro que si le dépôt est recréé (à éviter, voir § 7).

## 3. La CI (`build-apk.yml`)

Déclenchement : push sur `main`, pull request, ou lancement manuel.

1. **Environnement `release`** : le job l'utilise seulement pour un push sur `main` (sinon aucun environnement). C'est ce qui donne accès aux secrets de signature. L'environnement doit être restreint à la branche `main` (Settings → Environments → release → Deployment branches).
2. **Tests Go** : ils exécutent le vrai moteur rclone sur des dossiers locaux (bisync, conflits, exclusions, garde-fous). Un test rouge bloque le build.
3. **`librclone.aar`** : construit par `scripts/build-librclone.sh` (Go + gomobile + NDK, arm64 uniquement). Le résultat est mis en cache avec une clé calculée sur `go.mod`, `go.sum`, `bridge.go` et le script : changer la version de rclone reconstruit le fichier, sinon le build est rapide (~2 min).
4. **Décodage de la clé** : le secret `SYNCHROTRON_KEYSTORE_BASE64` est décodé dans un fichier temporaire du runner.
   - Sur `main` : si le secret est absent, le build **échoue** volontairement (on ne publie jamais une release non signée).
   - Sur une pull request : pas d'accès aux secrets, l'APK est signé avec la clé de debug, ne sert que de test (artefact du workflow) et n'est jamais publié.
5. **Build** : `assembleRelease` lit la clé dans les variables d'environnement `SYNCHROTRON_KEYSTORE`, `SYNCHROTRON_KEYSTORE_PASSWORD`, `SYNCHROTRON_KEY_ALIAS` (= `synchrotron`) et `SYNCHROTRON_KEY_PASSWORD`.
6. **Publication** : la release `latest` est supprimée (avec son tag) puis recréée sur le commit courant avec `synchrotron.apk`. L'application ne dépend que de ce nom de fichier et du titre `build N`.

## 4. La clé de signature

### Pourquoi elle est critique
Android n'installe une mise à jour que si elle est signée par **la même clé** que l'app déjà installée. Si la clé change ou est perdue : plus aucune mise à jour automatique possible, il faut désinstaller l'app (ce qui efface les paires et la session Proton) et réinstaller. Si la clé fuit : quelqu'un peut publier une fausse mise à jour acceptée par ton téléphone.

### Où elle vit
- Fichier `synchrotron.jks`, alias `synchrotron`, créé avec `keytool` (validité en jours ; peu importe tant qu'elle est très longue).
- Dans GitHub : trois secrets de l'environnement `release` uniquement.

| Secret | Contenu |
|---|---|
| `SYNCHROTRON_KEYSTORE_BASE64` | le `.jks` encodé en base64 (`base64 -w0 synchrotron.jks`) |
| `SYNCHROTRON_KEYSTORE_PASSWORD` | mot de passe du fichier `.jks` (le coffre) |
| `SYNCHROTRON_KEY_PASSWORD` | mot de passe de la clé à l'intérieur (identique au précédent si `keytool` a repris la valeur par défaut) |

- Le `.jks` est ignoré par git (`.gitignore`) : il ne doit jamais être commité.

### À faire une fois et à ne jamais défaire
- Garder une **sauvegarde hors ligne** du `.jks` et des mots de passe (gestionnaire de mots de passe + copie sur support chiffré). GitHub ne permet pas de relire un secret.
- Activer la double authentification sur le compte GitHub.
- Ne pas donner à d'autres personnes le droit de modifier le workflow sur `main` : quiconque peut le modifier pourrait faire afficher la clé pendant un run.
- Ne jamais ajouter d'étape qui affiche les secrets ; les logs masquent les valeurs mais pas leurs dérivés (base64 re-découpé, etc.).

### Si la clé est compromise
Il n'y a pas de rotation transparente avec Android classique : il faut générer une nouvelle clé, remplacer les secrets, et demander à chaque appareil de désinstaller puis réinstaller. Révoque aussi la session Proton depuis proton.me → Sécurité.

## 5. La mise à jour automatique (`update/Updater.kt`, `update/UpdateWorker.kt`)

### Détection
- `GET https://api.github.com/repos/KrustyLeBot/Synchrotron/releases/tags/latest` (dépôt public, sans identifiant).
- Le numéro de build est lu dans le titre de la release (`build N`) ; il est comparé au `versionCode` installé. Plus grand = mise à jour disponible.
- L'asset téléchargé est celui nommé `synchrotron.apk`.
- Quand : à l'ouverture de l'app, via le bouton « Vérifier maintenant » (Réglages), et par un travail WorkManager quotidien sur réseau non facturé qui envoie une notification « Mise à jour disponible ». L'API GitHub sans authentification est limitée (environ 60 requêtes par heure et par IP) : largement suffisant ici.
- La carte de mise à jour est aussi visible sur l'écran de connexion : on peut se mettre à jour sans être connecté à Proton.

### Téléchargement et vérifications
1. Téléchargement dans `cache/updates/` avec barre de progression.
2. Contrôle avec `PackageManager.getPackageArchiveInfo` : même nom de paquet que l'app, et `versionCode` strictement supérieur. Sinon le fichier est refusé.
3. Installation par l'installeur système (`ACTION_VIEW` via `FileProvider`). C'est **Android qui vérifie la signature** : un APK signé avec une autre clé est rejeté, même s'il passe les contrôles ci-dessus. La permission « Installer des apps inconnues » est demandée au premier usage.

### Garde-fous
- **Jamais pendant une synchro** : si une synchro tourne, le bouton « Installer » affiche « Attends la fin de la synchronisation… » (l'installation tuerait le processus au milieu d'une écriture).
- Les données (paires, session) sont conservées par la mise à jour, puisque c'est la même app signée avec la même clé.
- Après une mise à jour, `BootReceiver` (action `MY_PACKAGE_REPLACED`) replanifie les synchros et le service de surveillance.

### Limites connues
- Pas de signature détachée ni de somme de contrôle supplémentaire : la sécurité repose sur la signature Android et sur le contrôle d'accès au dépôt/à la clé.
- L'APK ne contient que l'architecture arm64 : un appareil 32 bits n'est pas supporté.
- Si la release est en cours de recréation (quelques secondes à chaque build), une vérification peut ne rien trouver ; elle réussira à la suivante.

## 6. Procédures

**Publier une nouvelle version** : commit puis `git push origin main`. Environ 2 à 6 minutes plus tard, la release « Synchrotron build N » apparaît. Sur le téléphone : Réglages → Vérifier maintenant → Télécharger → Installer.

**Mettre à jour rclone** : modifier la version dans `rclone-bridge/go.mod` (`go get github.com/rclone/rclone@vX.Y.Z && go mod tidy`), vérifier `go test ./bridge/`, pousser. Le cache de l'AAR est invalidé automatiquement. À faire vite si Proton change son API (le backend Proton de rclone est en bêta).

**Build local** : `./scripts/build-librclone.sh` (Go, NDK, `ANDROID_NDK_HOME`) puis `./gradlew :app:assembleRelease`. Sans variables de signature, l'APK est signé avec la clé de debug (non utilisable pour les mises à jour).

**Retour en arrière** : republier un ancien commit ne marche pas (le `versionCode` doit augmenter). Corrige en avant avec un nouveau commit.

## 7. Dépannage

| Symptôme | Cause probable |
|---|---|
| Le build échoue avec « SYNCHROTRON_KEYSTORE_BASE64 is missing » | Les secrets ne sont pas dans l'environnement `release`, ou l'environnement n'est pas autorisé sur `main` |
| Build vert mais pas de release | Le push n'est pas sur `main`, ou c'est une pull request |
| « App non installée » à la mise à jour | Signature différente (clé changée) ; désinstaller puis réinstaller, les données seront perdues |
| « Aucune mise à jour » alors qu'une release existe | Le titre ne contient pas `build N`, ou le build n'est pas plus grand que celui installé |
| L'installation ne démarre pas | Permission « Installer des apps inconnues » à accorder pour Synchrotron |
| Erreur après suppression du dépôt GitHub | Le numéro de build repartirait de 1 : ne jamais recréer le dépôt |
