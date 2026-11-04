# Guide d'utilisation

**Accueil** présente les appareils enregistrés et les opérations récentes. Une dernière connexion enregistrée ne signifie pas que l'appareil est actuellement disponible. L'indicateur authentifié concerne seulement la session courante.

**Appareils** conserve plusieurs PC dans Room. La découverte mDNS est une aide de saisie : toujours comparer l'empreinte sur le PC. Une entrée ajoutée attend l'approbation locale avant de pouvoir exécuter quoi que ce soit. Une connexion manuelle active un PC à la fois.

**Console** affiche les alias accordés par l'agent. Les diagnostics initiaux sont `hostname` et `whoami`, sans paramètres distants. Avant chaque exécution, confirmer puis s'authentifier avec le verrou Android. La réponse affiche stdout, stderr, code et statut. Délai Android par défaut 20 secondes ; l'agent refuse plus de 60 secondes. **Arrêter** demande l'annulation du processus direct. L'arrêt d'un shell et de ses descendants n'est pas pris en charge ; aucun shell n'est accordé par défaut.

**Scripts** accepte le format JSON documenté dans `shared-protocol/script-v1.schema.json`. Copier du texte dans l'éditeur ne l'exécute jamais. **Simuler** valide la structure, les droits, les conditions et délais déclarés ; n'exécute rien et ne prédit pas les effets système. **Enregistrer** valide de nouveau et persiste localement. **Autoriser et exécuter** exige une confirmation et le verrou Android, puis revalide selon les permissions de la session courante.

Une étape contient soit `command` (alias autorisé), soit `delay_ms` (0–10000). `when_exit` limite son exécution au code de l'étape précédente. Le script s'arrête au premier résultat non nul, délai dépassé, annulation ou erreur réseau ; un résultat incertain ne déclenche jamais de nouvelle tentative automatique. Trente étapes maximum, texte limité à 16 Ko. Pas encore de variables, shell embarqué, coloration syntaxique ni import/export de fichier.

**Automatisations** fournit des boutons de déclenchement manuel pour les scripts enregistrés. La planification en arrière-plan et les notifications système ne sont pas encore implémentées. Aucun script ne s'exécute à l'import ou au lancement de l'application.

**Historique** conserve au maximum 100 résultats locaux, limités à 8 Ko par résultat. L'effacement est local au téléphone. **Paramètres** permet le thème clair/sombre et la désactivation de l'historique. L'audit agent reste indépendant et n'enregistre pas les sorties de commandes.

**Sécurité** affiche l'empreinte Android et permet la révocation du PC actif. Hors ligne, effectuer la révocation depuis la console locale du PC. Fermer l'application ou effacer une entrée locale ne constitue pas une révocation distante. Les autorisations expirent après 30 jours ; une session dure au maximum 10 minutes. Reconnecter manuellement après expiration, sans rejouer une commande dont le résultat est incertain.
