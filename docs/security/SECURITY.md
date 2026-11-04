# Analyse de sécurité — prototype local 0.1

## Périmètre

Menaces couvertes par conception : écoute et modification du réseau local, usurpation d'un agent sans son certificat épinglé, client inconnu, signature invalide, rejeu, commande non accordée, session expirée, révocation. Aucune prétention de sécurité auditée ; la validation effective est recensée dans `docs/VALIDATION.md`.

## Protections

1. Agent identifié par empreinte TLS copiée depuis sa console locale. La découverte réseau n'instaure aucune confiance.
2. Clé Android P-256 non exportable dans Android Keystore. Stockage matériel selon les capacités réelles du téléphone, sans promesse de StrongBox.
3. Clé agent dans Credential Manager Windows, Secret Service Linux ou Keychain macOS via keyring. **Aucun repli automatique vers un fichier de clé en clair** ; `init` échoue si le magasin est indisponible. Sur Linux, magasin consulté avant création du runtime Tokio, comme requis par keyring.
4. Demande d'appairage sans droits, fenêtre de 120 secondes, empreinte du téléphone comparée et approuvée localement, droits explicites, expiration après 30 jours.
5. ES256 obligatoire, signature de toute l'opération, audience aléatoire de session, `jti` UUID à usage unique et validité brève. Vérifications avec jsonwebtoken ; pas de cryptographie maison.
6. Relecture des permissions sur chaque requête, fermeture des sessions révoquées en environ 500 ms, commande directe annulée à la fermeture. Session limitée à dix minutes.
7. Alias de programmes absolus et arguments fixes ; pas de shell ou élévation. L'environnement du processus enfant est nettoyé. Seuls `hostname` et `whoami` sont proposés par défaut.
8. Délai maximal de 60 secondes, une commande par connexion, huit connexions, sortie retenue 64 Kio par flux. Authentification initiale sous 15 secondes. Les sorties sont du texte et ne sont jamais interprétées comme des instructions ou du HTML.
9. Confirmation explicite puis biométrie forte ou code Android avant exécution et révocation depuis l'interface. Sans verrouillage du téléphone, l'action est refusée.
10. Audit requis avant lancement et annulation ; refus d'exécuter si l'écriture échoue. L'agent n'ajoute aucune règle de pare-feu, redirection NAT, service privilégié ou démarrage automatique.

## Limites et travaux nécessaires

- Une machine ou application déjà compromise sous le même compte utilisateur n'est pas isolée par ce modèle. Les fichiers locaux peuvent être modifiés par ce compte ; l'audit n'est pas inviolable. Un administrateur reste maître de la machine.
- Biométrie Android : verrou d'interface, pas encore signature liée à un `CryptoObject` par opération. Un processus Android compromis peut potentiellement signer tant qu'il accède à l'alias Keystore. Renforcer avant d'autoriser des actions sensibles supplémentaires.
- La racine de confiance est la comparaison complète des empreintes. Aucune confiance au premier contact implicite. Une empreinte copiée depuis un canal compromis n'est pas sûre.
- Pas de rotation/récupération transparente des clés ; changement du certificat agent implique réappairage. Refus des certificats expirés. Pas de PKI centrale ni certificat public nécessaire.
- Pas de shell interactif, terminal PTY ni isolation/sandbox des programmes locaux. Annulation du **processus direct** uniquement ; les descendants d'un programme ajouté localement ne sont pas encore gérés par groupes de processus/Job Objects. Conserver la politique initiale de diagnostics sans descendants.
- Limitation mémoire des sorties, mais pas de quota CPU/IO du processus. Huit clients LAN peuvent occuper les sockets ; une attaque de déni de service locale reste possible. Les demandes d'appairage peuvent occuper les dix places temporaires.
- Historique Android et sorties : données locales privées mais non chiffrées séparément par l'application. Protection du sandbox Android et du chiffrement du système ; réglage de désactivation et d'effacement fourni. Pas de sauvegarde Android.
- Le répertoire agent POSIX est protégé en 0700 ; utiliser le dossier utilisateur par défaut sur Windows. Un `--data-dir` personnalisé doit être privé. Durcissement ACL et validation des liens symboliques à compléter.
- Remplacement d'état atomique via tempfile sous verrou ; tests de panne et de durabilité du répertoire à compléter.
- Audit sans rotation pour cette tranche. Administrateur doit surveiller sa taille. Aucun secret ni stdout dans l'audit ; noms d'alias et empreintes sont personnels.
- Pas de test physique Android ↔ PC garanti par une compilation ou par les tests Rust. Voir le rapport de validation.

## Tests et revue avant extension

Les tests automatisés couvrent signatures, expiration, mauvais client, audience de reconnexion, rejeu, permissions, révocation et parcours TLS en mémoire. Vérifier sur appareils réels : certificat changé, approbation expirée, réseau coupé pendant exécution, cancellation, verrou Android, rotation écran, suspension de l'application, magasin sécurisé Windows/Linux, mDNS et révocation concurrente.

Faire une revue indépendante du protocole et de son implémentation avant d'accorder des programmes plus puissants. Les phases USB/ADB/HID ne doivent jamais chercher à contourner permissions ou authentification.
