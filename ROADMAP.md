# Feuille de route DuckyBridge

État : développement de la **première tranche de phase 1**. Les phases suivantes sont des objectifs, pas des fonctionnalités annoncées comme disponibles. Le détail livré figure dans `docs/FEATURES.md` ; les résultats réels dans `docs/VALIDATION.md`.

## Phase 1 — MVP local sécurisé

Objectif : Android sans root et agents Windows/Ubuntu, réseau local uniquement, consentement vérifiable et révocation effective.

### Tranche 0.1 actuelle

- [x] Structure monorepo, contrat v1 et décisions d'architecture.
- [x] Code agent TLS, identité en magasin sécurisé, demandes temporaires et approbation locale.
- [x] Code permissions par appareil, signatures ES256, anti-rejeu, expiration et révocation.
- [x] Code Android Compose français, Room, DataStore et identité Keystore.
- [x] Code ajout manuel, découverte mDNS, console par alias, résultat et annulation directe.
- [x] Code scripts JSON v1 séquentiels, conditions simples, délais, validation et simulation statique.
- [x] Guides d'installation/utilisation et analyse de sécurité.
- [ ] Compilation et tests de l'agent sur Windows **et Ubuntu** validés.
- [ ] Validation physique Android → PC sous compte non administrateur.
- [ ] Tests système du magasin sécurisé, pare-feu conservé, Wi-Fi, révocation pendant exécution et horloges décalées.

### Compléter le MVP

- [ ] Connexion/reconnexion automatique aux PC approuvés avec temporisation, sans rejeu des commandes.
- [ ] Bibliothèque persistante : catégories, recherche, favoris, compatibilité OS, paramètres strictement typés, import/export avec validation explicite.
- [ ] Variables de scripts, gestion d'erreurs configurable, coloration syntaxique et journaux d'exécution dédiés.
- [ ] Gestion robuste des descendants (Job Objects Windows, groupes de processus Unix) avant terminal général.
- [ ] Biométrie liée cryptographiquement à la signature pour actions sensibles ; politique locale de sensibilité.
- [ ] Rotation d'audit, tests de panne de stockage et durcissement ACL Windows.
- [ ] Installateurs propres Windows/Linux, mises à jour signées ; démarrage manuel par défaut.
- [ ] Tests instrumentés Android et audit indépendant du protocole.

**Critère de sortie obligatoire :** depuis un Android réel, appairer explicitement un PC Windows puis Ubuntu, exécuter une commande autorisée, récupérer stdout/stderr/code, révoquer et constater le refus des requêtes suivantes, y compris sur une session déjà ouverte. Une compilation ou un test serveur en mémoire ne suffit pas à cocher ce critère.

## Phase 2 — Multiplateforme avancée

- Support macOS installé et testé ; intégration Keychain et désinstallation.
- Plusieurs connexions simultanées avec isolation des résultats et annulation par appareil.
- Transferts de fichiers avec consentement, quotas, dossiers explicitement accordés et contrôle d'intégrité.
- Console PTY, répertoire contrôlé et limitation des processus.
- Planification locale via WorkManager, contraintes de disponibilité et notifications de succès/échec ; gestion des limites de batterie et de précision Android.
- Ressources français/anglais, accessibilité, grands caractères et essais petits écrans.

**Sortie :** tests sur les trois OS, transferts refusés hors périmètre, tâches annulables, permissions distinctes par téléphone, aucune élévation automatique.

## Phase 3 — Connexions alternatives

- SSH avec vérification des clés d'hôte et authentification explicitement configurée.
- USB Host/Accessory selon API et capacités détectées ; ne pas annoncer un mode clavier USB universel.
- ADB uniquement après autorisation explicite du propriétaire, avec explication du débogage requis.
- Étude Bluetooth/BLE, débit, appairage et restrictions système.
- Modèle de capacités par appareil, transports activables séparément, tests de perte de connexion.

**Sortie :** matrice de compatibilité mesurée ; fonctions indisponibles clairement désactivées. Aucun root ou noyau personnalisé requis.

## Phase 4 — Contrôle Android et iOS

- Compagnon Android utilisant uniquement les API autorisées.
- Étude ADB et restrictions de versions, permissions et profils.
- iOS via mécanismes officiels disponibles (raccourcis, app compagnon, protocoles autorisés).
- Documentation précise des actions impossibles, des confirmations requises et des limites en arrière-plan.

**Sortie :** scénarios démontrés sur appareils consentants, aucune promesse de contrôle système universel.

## Phase 5 — HID matériel optionnel

- Module microcontrôleur USB HID externe, piloté par Android via transport autorisé.
- Appairage/identité du module et confirmation locale de chaque séquence approuvée.
- Définition versionnée de séquences de touches, simulation et arrêt matériel.
- Essais sur systèmes acceptant un clavier standard ; documentation des dispositions clavier et limites d'accès.

**Sortie :** HID émulé par le matériel externe, téléphone sans root, aucun contournement de l'écran verrouillé ou des permissions.

## Phase 6 — Automatisation avancée

- Éditeur visuel, branches et conditions, modules à permissions déclarées.
- Sauvegarde/restauration chiffrées et restauration contrôlée des identités.
- Synchronisation facultative sans dépendance obligatoire au cloud.
- Versions, migration et comparaison des scripts ; import toujours inerte avant validation et autorisation.

**Sortie :** tests de restauration, migrations, conflits et annulation ; audit clair de la source et des permissions de chaque workflow.

## Phase 7 — Étude du fonctionnement sans agent préalable

Étudier uniquement des protocoles standards et périphériques acceptés officiellement. Produire une matrice distinguant :

| Catégorie | Exemple de question à documenter |
|---|---|
| Possible sans agent logiciel | Un périphérique HID approuvé peut-il saisir des touches dans une session déjà autorisée ? |
| Autorisation préalable nécessaire | SSH/ADB est-il activé et la clé a-t-elle été approuvée ? |
| Matériel supplémentaire requis | Le téléphone ne peut-il agir en HID que via un module externe ? |
| Limitation du système | Une application iOS peut-elle réellement effectuer l'action demandée via une API officielle ? |

**Sortie :** résultats expérimentaux reproductibles et restrictions explicites. Ne jamais chercher à contourner authentification, verrouillage, protections ou permissions.
