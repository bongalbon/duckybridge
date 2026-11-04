# Trois scripts pour Windows

Depuis PowerShell :

```powershell
cd C:\apps_ctd\duckybridge
.\scripts\install-dependencies.ps1
.\scripts\build-apk.ps1
.\scripts\build-agent.ps1
```

Attendre la réussite de chaque script avant de lancer le suivant. Ils peuvent aussi être appelés par leur chemin absolu depuis un autre dossier ; le dépôt est localisé à partir du script. PowerShell 5.1 et PowerShell 7 sont pris en charge.

## Installation et téléchargement

`install-dependencies.ps1` détecte les prérequis, installe ceux qui manquent et récupère les dépendances :

- Java 17 Temurin via WinGet si aucun JDK 17 n'est trouvé ;
- Visual Studio 2022 Build Tools avec Visual C++ x64 et SDK Windows ;
- SDK Android 36, platform-tools et build-tools 35/36, si absents ;
- toolchain Rust stable **MSVC x64**, stocké dans `.tools` ;
- bibliothèques Cargo et configurations Gradle de compilation/tests identifiées dans le projet.

Les licences des paquets et du SDK sont acceptées par le script d'installation. Les installateurs Windows peuvent demander une confirmation UAC. Si un redémarrage est demandé, redémarrer puis relancer le script. WinGet et une connexion Internet sont nécessaires pour les installations manquantes.

Les archives Android et rustup nouvellement téléchargées sont vérifiées contre les sommes publiées par leurs fournisseurs sur HTTPS. Aucun certificat TLS ou contrôle de signature n'est désactivé. Les variables Java, Android et Rust configurées par les scripts restent limitées à la session PowerShell ; les installateurs des paquets Windows ont leur comportement normal d'installation.

En cas d'erreur réseau, le script s'arrête avec un code non nul. Le relancer réutilise les installations et caches déjà présents. Des outils internes supplémentaires de Gradle peuvent encore être téléchargés lors de la première compilation. Le script ne lance aucun agent et n'ajoute ni règle de pare-feu ni démarrage automatique.

Pour consulter les prérequis sans lancer les téléchargements ou installateurs :

```powershell
.\scripts\install-dependencies.ps1 -CheckOnly
```

Ce contrôle retourne 1 si un prérequis manque. Il ne vérifie pas que toutes les dépendances du projet sont déjà en cache.

## Application Android

`build-apk.ps1` lance l'assemblage debug, les tests unitaires et le lint. Après réussite, il copie l'APK vers :

```text
C:\apps_ctd\duckybridge\dist\duckybrige.apk
```

Il s'agit d'un APK de développement signé avec la clé debug Android, pas d'une version de production. Le nom demandé `duckybrige.apk` est volontairement conservé. L'identifiant Android reste `org.duckybridge` et le nom affiché reste DuckyBridge.

## Agent Windows

`build-agent.ps1` lance les tests Rust, puis compile en release pour `x86_64-pc-windows-msvc`. Après réussite, il copie le binaire vers :

```text
C:\apps_ctd\duckybridge\dist\agent_duckybridge_x64.exe
```

Le script utilise un target explicite pour produire un exécutable Windows x64. Il ne lance ni `init` ni `serve`. Le binaire n'est pas signé Authenticode dans cette tranche.

Chaque sortie est accompagnée d'un fichier `.sha256`. Le dossier `dist` est ignoré par Git. Un échec de compilation ne publie aucun nouveau livrable ; un livrable d'une précédente compilation peut rester dans ce dossier.

Pour une compilation de diagnostic sans tests/lint :

```powershell
.\scripts\build-apk.ps1 -SkipChecks
.\scripts\build-agent.ps1 -SkipChecks
```

Le mode sans vérifications est indiqué dans la console. Une compilation réussie ne suffit pas à valider le parcours Android vers un PC réel ; voir [la recette de phase 1](../roadmap/ACCEPTANCE.md) et [la validation effectuée](../VALIDATION.md).
