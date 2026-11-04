# Sécurité des éditions TV

## Identifiants Xtream

- Saisis une seule fois, chiffrés en **AES-256-GCM** avec une clé WebCrypto **non exportable**
  (`extractable: false`), créée sur la TV et rangée dans IndexedDB. Le code JavaScript ne peut pas
  lire les octets de cette clé, il peut seulement lui demander de chiffrer ou déchiffrer.
- Les chaînes et épisodes d'un compte sont stockés avec une adresse `xtream://<playlist>/<type>/<id>`
  sans nom d'utilisateur ni mot de passe. La vraie adresse est reconstruite en mémoire juste avant la
  lecture et n'est jamais enregistrée.
- Les adresses sont masquées (`redact`) dans tout ce qui s'affiche ou part dans la console.
- Aucun secret dans le JavaScript distribué : pas de clé, pas de jeton, pas de compte par défaut.

**Limite honnête** : sur une TV, il n'existe pas de coffre matériel accessible aux applis web
(pas d'équivalent du Keystore Android). Quelqu'un qui obtient un accès complet au système de la TV
(mode développeur + outil de débogage connecté à l'appli) peut faire déchiffrer les identifiants
par l'appli elle-même. Le chiffrement protège les données au repos (copie du stockage, sauvegarde),
pas contre un accès de débogage à l'appli en cours.

## Code parental

Code à 4 chiffres dérivé par **PBKDF2-SHA-256** (100 000 itérations, sel aléatoire), comparaison en
temps constant. Il protège contre l'accès d'un enfant à la télécommande, pas contre quelqu'un qui
efface les données de l'appli.

## Réseau

- TLS jamais désactivé ; aucune option « ignorer les certificats ».
- Les flux HTTP en clair restent possibles parce que beaucoup de playlists en utilisent : c'est le
  choix de la source, l'appli ne transforme pas un flux HTTPS en HTTP.
- Politique de sécurité du contenu (CSP) : scripts uniquement depuis le paquet (et `webapis.js` de
  Samsung), pas d'`eval`, pas de script en ligne ; images, flux et guides depuis toute origine
  puisqu'ils viennent des playlists de l'utilisateur.

## Signature

- **Samsung** : le `.wgt` est signé avec un certificat auteur et un certificat distributeur Samsung
  (lié au DUID de la TV en mode développeur). Les fichiers `.p12` et leurs mots de passe ne sont
  jamais dans le dépôt : uniquement dans les secrets GitHub (`TIZEN_*`), décodés dans un dossier
  temporaire du runner, effacé en fin de travail.
- **LG** : l'`.ipk` installé en mode développeur n'a pas de signature d'auteur ; la publication sur
  le LG Content Store passe par la signature de LG.
- L'application Android, son keystore (`app/debug.keystore`), sa clé de rotation et ses secrets
  ne sont ni lus ni modifiés par la CI TV.
