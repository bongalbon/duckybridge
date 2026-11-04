# Protocole DuckyBridge v1

Transport : HTTPS et WebSocket sur TLS 1.2/1.3, port proposé 9843. Aucune route HTTP en clair.
Implémentation : `desktop-agent/src/{model,auth,server}.rs` et `android-app/app/src/main/java/org/duckybridge/{network,security}`.

## Identité et appairage

1. `init` génère un certificat TLS autosigné P-256. Sa clé privée est enregistrée dans le magasin du système ; le certificat public peut être copié.
2. Le propriétaire recopie sur Android l'empreinte **SHA-256 du certificat DER complet**, affichée par `identity`. Elle n'est jamais acceptée automatiquement depuis mDNS. Le client vérifie certificat exact, validité et signature ; un changement exige un nouvel appairage.
3. L'agent est lancé avec `--pairing`. Pendant **120 secondes**, `POST /v1/pair` reçoit `{"name":"Téléphone","public_key_pem":"-----BEGIN PUBLIC KEY-----\n..."}`. La clé doit être une SPKI EC P-256. Corps limité à 8 Ko, nom à 80 octets, dix demandes en attente maximum.
4. Réponse : `{"v":1,"agent_id":"empreinte-certificat","device_id":"empreinte-SPKI","state":"pending_local_approval"}`. Cette réponse **ne donne aucun droit**. La demande expire après 120 secondes ; une nouvelle réception ne renouvelle pas une demande existante.
5. Android affiche son empreinte **SHA-256 de la SPKI DER**. Le propriétaire la compare avec `pending` sur le PC puis exécute `approve EMPREINTE --commands hostname,whoami`. L'autorisation dure 30 jours et porte uniquement sur les alias choisis.

La fenêtre d'appairage et la durée d'autorisation sont deux durées différentes. L'agent ne s'arrête pas lorsque sa fenêtre d'appairage expire. Pour la rouvrir, relancer `serve --pairing`.

## Session et authentification

`GET /v1/ws` établit un WebSocket TLS. Les clients navigateur avec en-tête Origin sont refusés. Huit sockets maximum ; 15 secondes pour présenter une première requête authentifiée ; session de 600 secondes maximum. Une nouvelle connexion reçoit une audience aléatoire propre à cette socket :

```json
{"v":1,"type":"hello","agent_id":"empreinte-certificat","audience":"UUID-aléatoire","session_seconds":600}
```

Chaque message client est un **JWT/JWS compact ES256** (RFC 7515, RFC 7518, RFC 7519), signé par la clé Keystore Android. En-tête :

```json
{"alg":"ES256","typ":"JWT","kid":"empreinte-SPKI-du-client"}
```

Charge utile :

```json
{
  "v":1,"iss":"duckybridge-v1","sub":"empreinte-SPKI-du-client",
  "aud":"audience-de-cette-socket","iat":1793781000,"exp":1793781030,
  "jti":"e49b7114-dd3e-402b-994e-fcf84f3f6380",
  "op":{"type":"execute","command":"hostname","timeout_secs":20}
}
```

L'opération entière est signée. Aucun JWT bearer réutilisable n'est délivré par l'agent. L'agent vérifie la signature avec une clé **déjà approuvée**, ES256 uniquement, émetteur, sujet, version, audience, expiration et identifiant de requête UUID. Durée maximale d'une requête : 60 secondes ; Android émet 30 secondes. Une avance d'horloge de 5 secondes est tolérée pour `iat` ; aucune tolérance après `exp`. Cache anti-rejeu par socket, maximum 2048 entrées non expirées ; audience différente à chaque reconnexion et redémarrage. Les deux appareils doivent avoir une horloge raisonnablement à l'heure ; Internet n'est pas nécessaire.

Les autorisations sont relues à **chaque requête**. Une vérification toutes les 500 ms ferme une session révoquée ou expirée et annule sa commande directe. Une session ne peut pas changer d'identité de client.

## Opérations

| `op.type` | Champs | Effet |
|---|---|---|
| `status` | aucun | Nom, OS, répertoire de travail fixé localement, alias accordés |
| `execute` | `command`, `timeout_secs` (1–60) | Exécute un alias local avec programme absolu et arguments fixes |
| `cancel` | `request_id` | Annule la commande de cette même session |
| `revoke` | aucun | Supprime l'autorisation de ce client et ferme la session |

Une commande par socket. Aucun programme, argument, shell ou répertoire arbitraire n'est accepté du réseau. Le compte d'exécution est celui du processus agent. Environnement nettoyé. Le répertoire est choisi au `init` ; la modification distante n'est pas implémentée.

Réponses : `status`, `started`, `result`, `cancel_requested`, `revoked`, `error`. Les réponses aux opérations contiennent `request_id` égal à `jti`. `result.result` contient `status` (`completed`, `timeout`, `cancelled`), `exit_code` (entier ou null), `stdout`, `stderr`, `output_limit`, `truncated`. Chaque flux est limité à 64 Kio retenus ; les données excédentaires sont drainées et abandonnées. Sortie finale uniquement, pas de PTY ni flux terminal interactif.

Les erreurs d'authentification ferment la session. Les erreurs de permission ou d'exécution restent dans la session authentifiée. Les nouvelles tentatives doivent utiliser un nouveau `jti` ; ne jamais relancer automatiquement une commande après une coupure incertaine.

## Sources normatives et choix

- [JWS, RFC 7515](https://www.rfc-editor.org/rfc/rfc7515)
- [ES256, RFC 7518](https://www.rfc-editor.org/rfc/rfc7518#section-3.4)
- [JWT, RFC 7519](https://www.rfc-editor.org/rfc/rfc7519)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [jsonwebtoken](https://docs.rs/jsonwebtoken/9.3.1/jsonwebtoken/)
- [rustls](https://docs.rs/rustls/0.23/rustls/)

Il s'agit d'un protocole **applicatif** versionné utilisant TLS et JWS standards. Il ne définit aucun algorithme cryptographique, échange de clés ou chiffrement propriétaire. Ce choix doit faire l'objet d'une revue indépendante avant usage au-delà du prototype local. L'authentification mutuelle est TLS côté agent et JWS côté client ; ce n'est pas du mTLS.
