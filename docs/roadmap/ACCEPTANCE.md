# Recette manuelle de la phase 1

À exécuter séparément sur Windows et Ubuntu, avec Android réel. Ne cocher qu'après observation.

- [ ] Compilation native, tests Rust et analyse statique réussis.
- [ ] APK installé ; thème clair/sombre et navigation lisibles sur petit écran.
- [ ] `init` conserve l'identité après redémarrage de session ; magasin verrouillé : échec explicite, pas de clé en clair.
- [ ] Sans `--pairing`, une demande d'appairage est refusée.
- [ ] Avec `--pairing`, l'agent est découvrable seulement si `--discover` est activé ; ajout manuel possible.
- [ ] Empreinte TLS incorrecte : connexion et appairage refusés.
- [ ] Demande reçue mais non approuvée : aucune commande acceptée.
- [ ] Demande expirée après 120 secondes : approbation impossible.
- [ ] Empreinte Android comparée sur les deux écrans ; approbation locale limitée à `hostname`.
- [ ] `hostname` retourne stdout et code 0 ; `whoami` est refusé pour ce téléphone tant qu'il n'est pas accordé.
- [ ] Verrou Android absent : exécution refusée ; confirmation annulée : aucun processus lancé.
- [ ] Autorisation de `whoami` : compte courant non administrateur, aucune élévation.
- [ ] Script valide : étapes ordonnées, délai et condition respectés ; erreur : arrêt au premier échec.
- [ ] Texte collé/importé : aucune exécution automatique ; alias inconnu : validation refusée.
- [ ] Annulation d'une commande lente de test explicitement ajoutée localement : processus direct terminé.
- [ ] Timeout : processus direct terminé et statut `timeout`.
- [ ] Coupure Wi-Fi : état hors ligne, aucune commande rejouée à la reconnexion.
- [ ] Révocation sur le PC avec session ouverte : fermeture et refus de la requête suivante.
- [ ] Révocation depuis Android : entrée supprimée sur le PC, reconnexion refusée.
- [ ] Arrêt/redémarrage de l'agent : ancienne audience et ancien JWT refusés.
- [ ] Historique désactivé : aucun nouveau résultat conservé ; effacement effectif.
- [ ] Désinstallation : clé et données connues supprimées, aucun service ou autostart résiduel.

Consigner OS, modèle Android, version API, résultats et limites dans `docs/VALIDATION.md`. Les tests de bibliothèque en mémoire ne remplacent pas cette recette.
