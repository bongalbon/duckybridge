# Installer et démarrer DuckyBridge

## Prérequis de compilation

- Android : Java 17, SDK Android 36 et build-tools, accès aux dépôts Google/Maven pour la première compilation. Android 8 (API 26) minimum, sans root. Verrouillage Android par code ou biométrie requis pour exécuter une commande.
- Agent : Rust stable, outils de compilation C natifs (MSVC Build Tools sous Windows ; compilateur C sous Linux ; Xcode Command Line Tools sous macOS). Windows 10/11 ou Linux de bureau avec Secret Service (par exemple GNOME Keyring / KWallet), session utilisateur ouverte et magasin déverrouillé. macOS expérimental tant que la validation locale n'est pas effectuée.
- Les appareils appartiennent au propriétaire et se trouvent sur un réseau local de confiance. Aucun compte administrateur nécessaire à l'agent. Un réseau invité peut bloquer les connexions entre appareils ; ne pas désactiver les protections pour l'éviter.

## Compiler

À la racine :

```sh
cargo test --workspace
cargo build --release
```

Binaire : `target/release/duckybridge-agent` ou `.exe` sous Windows.

Pour Android :

```sh
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Sous PowerShell : ` .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.
APK de développement : `android-app/app/build/outputs/apk/debug/app-debug.apk`. L'installer sur **son propre téléphone**, par exemple `adb install -r app-debug.apk` après avoir autorisé le débogage USB. Aucun accès ADB n'est nécessaire au fonctionnement de l'application. Les builds debug ne sont pas des distributions de production signées.

Si le SDK n'est pas trouvé, définir `ANDROID_HOME` ou `sdk.dir` dans `android-app/local.properties` (fichier ignoré par Git).

## Première configuration de l'agent

Sous Windows, depuis un dossier personnel contenant le binaire :

```powershell
.\duckybridge-agent.exe init --name "Mon PC" --cwd "$env:USERPROFILE"
.\duckybridge-agent.exe identity
.\duckybridge-agent.exe serve --bind 192.168.1.20 --pairing --discover
```

Sous Linux :

```sh
./duckybridge-agent init --name "Mon Ubuntu" --cwd "$HOME"
./duckybridge-agent identity
./duckybridge-agent serve --bind 192.168.1.20 --pairing --discover
```

Remplacer l'adresse par celle de **ce PC** sur le LAN. L'adresse doit être locale et précise. Par défaut, `serve` écoute uniquement `127.0.0.1`, utile aux essais sur la même machine. Aucune écoute globale `0.0.0.0` ou adresse Internet n'est acceptée. Ne pas utiliser de tunnel/redirection Internet dans cette version.

L'agent reste visible au premier plan ; Ctrl+C l'arrête. `--discover` active facultativement l'annonce mDNS. Ne pas lancer plusieurs agents sur le même port. Les fichiers de données se trouvent dans le dossier local utilisateur calculé par `directories::ProjectDirs` (Windows généralement `%LOCALAPPDATA%\DuckyBridge\DuckyBridge\data`, Linux `~/.local/share/duckybridge`). `--data-dir CHEMIN` permet un dossier privé explicite ; utiliser le même pour toutes les commandes.

Si le pare-feu refuse la connexion, aucune règle n'est ajoutée par DuckyBridge. L'utilisateur doit décider lui-même s'il autorise ce programme sur son réseau privé. Ne jamais désactiver le pare-feu global.

## Appairer le téléphone

1. Ouvrir **Appareils**. Rechercher l'agent ou saisir son adresse et le port 9843.
2. Recopier l'empreinte complète affichée dans la console PC par `identity` ou `serve`. La comparer puis cocher la confirmation.
3. Toucher **Demander l'appairage** pendant la fenêtre de deux minutes.
4. Dans une seconde console locale du PC, afficher les demandes :

```sh
duckybridge-agent pending
duckybridge-agent approve EMPREINTE_ANDROID --commands hostname,whoami
```

Comparer les **64 caractères de l'empreinte Android affichée sur le téléphone** à ceux de `pending` avant l'approbation. Le nom du téléphone seul ne suffit pas. La demande expire après deux minutes ; si elle expire, la refaire dans une nouvelle fenêtre.

5. Sur Android, toucher **Se connecter**. Le statut doit devenir authentifié et les alias doivent apparaître.
6. Dans Console : `hostname`, **Exécuter**, confirmation puis code/biométrie. Vérifier stdout et code 0.
7. Dans Sécurité : **Révoquer cet appairage**. Une reconnexion doit être refusée. On peut également révoquer localement :

```sh
duckybridge-agent devices
duckybridge-agent revoke EMPREINTE_ANDROID
```

## Dépannage

- **Magasin sécurisé indisponible** : sous Linux, ouvrir une session de bureau avec Secret Service déverrouillé. Les sessions WSL/headless n'en disposent pas forcément. Aucun fichier de clé en clair n'est proposé en repli.
- **Fenêtre fermée / demande expirée** : arrêter l'agent et relancer avec `--pairing`, puis renvoyer la demande. Les appairages déjà approuvés restent enregistrés.
- **Authentification refusée** : vérifier approbation, expiration de 30 jours, horloges et empreintes. Refaire l'appairage si une identité a changé.
- **Hors ligne** : vérifier adresse exacte, même réseau, isolation Wi-Fi, agent actif et politique locale de pare-feu. La découverte est facultative ; l'ajout manuel reste disponible.
- **Commande refusée** : consulter `devices`. Seuls les alias accordés localement sont permis. Aucun argument arbitraire ne peut être saisi depuis Android.
- **Gradle “Unable to establish loopback connection” sous Windows** : essayer un chemin temporaire local court via `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\chemin\temporaire`, limité au terminal de compilation. Voir le rapport de validation ; aucune protection système n'a besoin d'être désactivée.

## Désinstaller

Révoquer les téléphones, arrêter l'agent puis `duckybridge-agent uninstall --confirm`. Supprimer le binaire manuellement. Sur Android, effacer l'historique si nécessaire puis désinstaller l'application. La désinstallation Android seule ne supprime pas les autorisations côté PC.
