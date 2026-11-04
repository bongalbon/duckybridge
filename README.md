# DuckyBridge

Application Android personnelle et agent natif pour exécuter des diagnostics autorisés sur ses propres PC, sans root et sans cloud obligatoire. Interface française, consentement sur les deux appareils, communication TLS et requêtes signées ES256.

**État : prototype local 0.1, première tranche de la V1.** Le code de l'application et de l'agent est présent ; les fonctions non validées et les blocages de compilation sont distingués dans le [rapport de validation](docs/VALIDATION.md). Ne pas considérer la V1 complète ou son protocole comme audités.

## Premier parcours

### Compilation simplifiée sous Windows

Depuis PowerShell, lancer les trois scripts dans cet ordre :

```powershell
cd C:\apps_ctd\duckybridge
.\scripts\install-dependencies.ps1
.\scripts\build-apk.ps1
.\scripts\build-agent.ps1
```

Arrêter si un script signale une erreur. Après compilation réussie, les fichiers sont disponibles dans `dist` :

- `duckybrige.apk` : application Android de développement ;
- `agent_duckybridge_x64.exe` : agent Windows x64 ;
- une somme SHA-256 à côté de chaque livrable.

Le nom `duckybrige.apk` reprend exactement le nom demandé. Les tests sont activés par défaut. L'installation peut demander une confirmation Windows et nécessite Internet ; aucun agent n'est lancé et aucun pare-feu ou démarrage automatique n'est configuré. Voir le [guide des scripts Windows](docs/installation/WINDOWS-SCRIPTS.md).

### Compilation et configuration manuelles

```sh
cargo build --release
target/release/duckybridge-agent init --name "Mon PC" --cwd .
target/release/duckybridge-agent identity
target/release/duckybridge-agent serve --bind 192.168.1.20 --pairing --discover
```

Sur Windows, le binaire porte l'extension `.exe`. Remplacer l'adresse par l'adresse privée réelle du PC. Aucun pare-feu, service système ou démarrage automatique n'est modifié. Le magasin sécurisé du système doit être disponible.

Dans Android : **Appareils** → adresse/port → empreinte TLS comparée à la console PC → demande. Dans une seconde console PC :

```sh
duckybridge-agent pending
duckybridge-agent approve EMPREINTE_ANDROID_COMPLETE --commands hostname,whoami
```

Comparer l'empreinte Android complète avant approbation. La fenêtre de **deux minutes** concerne les nouvelles demandes ; les autorisations approuvées durent 30 jours. Sur Android : **Se connecter** → Console → `hostname` → confirmer avec le verrou Android → résultat. **Sécurité** → révoquer → vérifier le refus d'accès.

## Compiler Android

Prérequis : Java 17 et SDK Android 36.

```sh
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Sous PowerShell, depuis le dossier `android-app` :

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK attendu après une compilation réussie : `android-app/app/build/outputs/apk/debug/app-debug.apk`.

## Documents

- [Installation et désinstallation](docs/installation/INSTALLATION.md)
- [Utilisation](docs/UTILISATION.md)
- [Architecture et décisions](docs/architecture/ARCHITECTURE.md)
- [Protocole v1](shared-protocol/README.md)
- [Schéma des scripts](shared-protocol/script-v1.schema.json) et [exemple](shared-protocol/diagnostic.ducky.json)
- [Analyse de sécurité et limites](docs/security/SECURITY.md)
- [Suivi des fonctionnalités](docs/FEATURES.md)
- [Validation réellement exécutée](docs/VALIDATION.md)
- [Feuille de route en sept phases](ROADMAP.md)

La console actuelle exécute des **alias de programmes locaux aux arguments fixes**, avec sortie finale. Terminal PTY, scripts à variables, bibliothèque avancée, planification, transferts et transports alternatifs restent à développer. L'annonce mDNS n'autorise jamais un appareil. Aucun accès clandestin, root, noyau personnalisé ou exposition automatique sur Internet.
