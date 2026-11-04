# Validation — 4 novembre 2026

## Résultats confirmés à ce stade

- Dépôt initial vide, branche sans commit ; inspection de Java 17, SDK Android 34/35/36/36.1 et Gradle disponibles.
- **6 tests JVM réussis** avec Kotlin 2.2.20, JUnit 4.13.2 et coroutines 1.10.2, exécutés directement sur le JDK local pour isoler le cœur des téléchargements Android :
  - 50 signatures Java P-256 converties de DER vers ES256 R||S avec égalité des deux entiers ;
  - rejet DER malformé ;
  - validation sans exécution d'un script ;
  - rejet de version, commande et étape ambiguës ;
  - séquence arrêtée au premier échec ;
  - condition sur résultat précédent respectée.
- Schéma JSON Draft 2020-12 vérifié avec jsonschema ; exemple de diagnostic conforme.
- **Toutes les sources Android Kotlin/Compose compilées en bytecode** avec Kotlin 2.3.20, son plugin Compose et le classpath Android résolu par Gradle, SDK 36. Compilation directe pour isoler la validation du code des téléchargements du processeur Room. Cela ne valide pas encore la génération de `BridgeDatabase_Impl`, le packaging APK ni le lint.
- Neuf fichiers Rust analysés et formatés par le paquet WebAssembly `rustfmt` 0.1.0, outil de dépannage dans `.tools` ignoré par Git. **Ce n'est ni `cargo check`, ni Clippy, ni une validation des API des dépendances.**
- Wrapper Gradle 9.1.0 généré avec succès ; SHA-256 de distribution ajouté à partir de la somme publiée par Gradle.
- `adb devices -l` : aucun téléphone ni émulateur connecté. Aucun test physique Android effectué.

## Compilation Android

Les premiers lancements Gradle échouaient avant configuration avec `Unable to establish loopback connection` / `UnixDomainSockets.connect0 Invalid argument`. Le réglage temporaire du processus `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\apps_ctd\duckybridge\.tools` permet de démarrer Gradle. Aucune modification globale de Java, du réseau ou du pare-feu.

Un second échec de compilation du script Gradle a révélé l'ancienne DSL `kotlinOptions`, refusée par Kotlin 2.3.20. Remplacée par `kotlin.compilerOptions.jvmTarget`.

**Résultat du build complet : échec après 11 min 4 s**, à `:app:javaPreCompileDebug`. Deux téléchargements ont expiré : `androidx.room:room-compiler:2.8.1` et `androidx.room:room-external-antlr:2.8.1`. Le manifeste et les ressources ont été traités ; la génération Room, l'assemblage APK, les tests via Gradle et le lint ne sont pas déclarés réussis. **Aucun APK produit.** AAPT2 du SDK 36 a été utilisé avec un paramètre local `-Pandroid.aapt2FromMavenOverride=...`, sans modification du projet pour imposer un chemin de cette machine.

À la reprise, la branche `main` était active : retour sur `codex/secure-local-mvp` après conservation d'une copie du wrapper non suivi, dont le hash Git était identique au fichier de la branche. Les six tests JVM ont été réexécutés avec succès. Les tentatives bornées de récupération des deux JAR depuis Google en IPv4 et depuis des miroirs n'ont pas levé le blocage. Aucune vérification TLS ou de signature n'a été désactivée ; aucun JAR incomplet n'a été accepté comme dépendance.

La pile choisie est AGP 8.13.1, Gradle 9.1.0, Kotlin 2.3.20, Compose UI/Foundation 1.10.6, Material 3 1.4.0, compileSdk 36 et targetSdk 35. Plusieurs dépendances Android ont dû être récupérées malgré un cache de binaires préexistant. Des téléchargements Google/Maven ont subi des délais et réponses interrompues.

## Agent Rust — blocage d'environnement

- Aucun `rustc`, Cargo ni compilateur C disponible initialement sous Windows ou dans WSL Kali.
- L'installateur rustup Windows a été téléchargé dans `.tools`, sans modification du PATH global. Installation stable puis Rust 1.90.0 : échec de récupération des manifests officiels (`error decoding response body`, opération expirée). Tentative via rsproxy.cn également interrompue. Aucun toolchain fonctionnel obtenu à ce stade.
- WSL Kali démarre, mais les index APT sont obsolètes : clé de signature manquante `ED65462EC8D5E4C5`, puis archives de développement en erreur 404. Aucune vérification de signature n'a été désactivée et les dépôts n'ont pas été reconfigurés.
- Tentative d'outillage C portable Zig dans `.tools` : téléchargement PyPI interrompu à plusieurs reprises. Aucun binaire utilisable obtenu.
- En conséquence : **aucun test Rust exécuté, aucun agent Windows/Linux/macOS compilé, aucun binaire d'agent livré comme validé**.
- `Cargo.lock` n'a pas pu être généré sans Cargo opérationnel. CI et scripts génèrent un verrou initial si nécessaire ; conserver et revoir le premier verrou validé avant une distribution reproductible.

## Tests fournis mais non exécutés

Sept tests Rust de sécurité/exécution : requête signée/rejeu, révocation sur session existante, audience à la reconnexion, expiration/futur/signature falsifiée/client inconnu, permissions/délais, stdout/code, annulation/timeout/exécutable absent. Un test d'intégration HTTPS + WSS : demande d'appairage sans droits, approbation locale de fixture, exécution, reconnexion et révocation. Les fixtures génèrent leurs identités de test en mémoire ; elles ne prouvent pas l'intégration du magasin sécurisé de chaque OS.

CI GitHub Actions fournie pour Windows, Ubuntu et macOS, compilation APK/tests/lint, audit Cargo et inventaire Android. Déclenchement manuel possible via `workflow_dispatch` après publication de la branche par le propriétaire. Les jobs de qualité Rust sont séparés des compilations pour rendre leurs résultats distincts. Aucune exécution GitHub distante lancée dans cette session. Analyse des dépendances non déclarée réussie.

## Scripts Windows ajoutés

Trois points d'entrée sont disponibles : `scripts/install-dependencies.ps1`, `scripts/build-apk.ps1`, `scripts/build-agent.ps1`. Les noms de distribution sont `dist/duckybrige.apk` et `dist/agent_duckybridge_x64.exe`, copiés seulement après réussite, avec sommes SHA-256. Voir le guide `docs/installation/WINDOWS-SCRIPTS.md`.

Validation locale : analyse syntaxique PowerShell réussie ; exécution du contrôle des prérequis sous Windows PowerShell 5.1 réussie (code 1 attendu : Visual C++, SDK Windows et Rust manquants). Les fonctions de copie/nommage, de calcul des sommes et de rejet d'une sortie absente ont été vérifiées avec des fichiers factices dans `.tools`, ensuite supprimés. Les codes de sortie natifs 0 et 23 ont été testés pour vérifier l'arrêt sur erreur. Le script de compilation agent refuse correctement de continuer sans prérequis. Aucune installation de paquet, acceptation de licence ou compilation complète n'a été lancée pour tester ces scripts ; aucun nouveau livrable APK/EXE réel n'est annoncé.

## Critère d'acceptation

**Non validé** tant que le parcours Android réel → agent natif sous compte utilisateur → commande autorisée → résultat → révocation n'a pas été observé. Utiliser `docs/roadmap/ACCEPTANCE.md`. Les fonctionnalités écrites mais non testées restent décrites comme telles dans `docs/FEATURES.md`.
