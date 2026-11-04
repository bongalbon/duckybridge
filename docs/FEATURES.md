# Suivi des fonctionnalités

« Implémenté » décrit le code présent ; cela ne signifie pas validé sur matériel. Voir le rapport de validation pour les commandes réellement exécutées.

| Domaine | Implémenté dans 0.1 | Restant / limite |
|---|---|---|
| Accueil | Appareils enregistrés, compteurs, opérations récentes | Disponibilité réseau continue et actions rapides enrichies |
| Appareils | Room, ajout IP/hôte, demande HTTPS, identité par empreinte, approbation PC, révocation, dernière connexion, plusieurs fiches | Une connexion active ; reconnexion manuelle ; pas de détection permanente |
| Découverte | Annonce agent opt-in et recherche Android mDNS 15 s | À valider sur Wi-Fi réel ; pertes/retours et résolutions concurrentes à améliorer |
| Console | Alias autorisés, stdout/stderr/code final, timeout, annulation du processus direct, compte courant, confirmation + verrouillage Android | Aucun PTY, stdin interactif, streaming ou changement distant de répertoire ; descendants non gérés |
| Bibliothèque | Deux diagnostics prédéfinis et politique locale extensible via config agent | Enregistrement Android, catégories, paramètres, recherche, favoris, import/export non implémentés |
| Scripts | JSON v1, validation, séquence, délai, condition sur code précédent, arrêt sur erreur, annulation, stockage Room, simulation statique | Variables, branches générales, coloration, import/export et versions de scripts à développer |
| Automatisation | Raccourcis manuels des scripts enregistrés | Planification locale et notifications système non implémentées |
| Historique | 100 résultats locaux max., désactivation/effacement, limites de taille | Chiffrement applicatif séparé, rétention par durée et filtres |
| Interface | Huit vues françaises, thème clair/sombre, progression et erreurs | Localisation anglaise, audit d'accessibilité et QA visuelle sur téléphone |
| Identités | Keystore Android, keyring PC, certificat agent épinglé manuellement | Rotation/récupération et tests matériels des magasins |
| Protocole | TLS + JWS standard ES256, requêtes expirantes, audience par socket, anti-rejeu, permissions relues et sessions bornées | Revue indépendante ; pas de mTLS |
| Agent local | CLI init/identity/pending/approve/devices/revoke/uninstall, mode manuel | Assistant graphique, installateurs signés, autostart opt-in |
| Audit | JSONL des métadonnées sensibles ; écriture requise avant exécution | Rotation, quotas et intégrité externe |
| Windows | Code natif et chemins System32 absolus | Compilation et tests réels à confirmer dans VALIDATION |
| Ubuntu | Code natif, Secret Service sans repli en clair | Aucun Ubuntu disponible localement ; validation sur CI / machine consentante requise |
| macOS | Branche Keychain et code portable | Expérimental, aucune validation locale ; prévu phase 2 |
| CI | Jobs Android, Rust multi-OS, audit Cargo, inventaire dépendances | Exécution GitHub distante non déclenchée dans cette session |
| USB/ADB/SSH/BLE/HID/iOS | Documentation de la feuille de route | Aucune de ces connexions implémentée |
