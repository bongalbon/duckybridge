# Installation de l'agent

Cette tranche distribue un exécutable natif à copier dans un dossier personnel. Aucun installateur MSI/DEB/PKG, service système ou autostart n'est installé. Les scripts ci-dessous servent de points d'entrée manuels. Les paquets signés, la gestion de mise à jour et l'autostart **opt-in** font partie des tâches restantes.

Windows : `start-manual.ps1` ; Linux/macOS : `start-manual.sh`. Chaque script demande une adresse précise et conserve l'agent au premier plan. Lire `docs/installation/INSTALLATION.md` avant utilisation.

Pour désinstaller : arrêter tous les agents puis lancer `duckybridge-agent uninstall --confirm` sous le même compte et avec le même `--data-dir` éventuel. Cela supprime la clé du magasin sécurisé et les fichiers connus de l'application. Supprimer ensuite le binaire ; aucun service ni tâche planifiée à nettoyer. En cas de magasin verrouillé, la désinstallation refuse de prétendre avoir supprimé la clé : déverrouiller le magasin et recommencer.
