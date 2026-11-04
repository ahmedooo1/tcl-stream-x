# Plateformes : versions, API, formats, limites

## Versions visées

| | Samsung (Tizen) | LG (webOS) |
|---|---|---|
| Minimum | Tizen 7.0 (TV 2023), Chromium 94 | webOS 23 (TV 2023), Chromium 94 |
| Ta TV | Samsung 55" 2025, Tizen 9 | — |
| Paquet | `.wgt` (zip signé auteur + distributeur) | `.ipk` (`ares-package`) |
| Identifiant | `StrmXTclX1.StreamX` | `fr.ahmad.streamx` |
| Code | un seul script classique (`app.js`), chargé depuis `file://` | idem |

Les TV plus anciennes (Tizen 6.5 et avant, webOS 22 et avant) ont un moteur sans certaines
fonctions CSS et DOM utilisées (`inset`, `replaceChildren`) : non prises en charge pour l'instant.
Les élargir demanderait des polyfills et un essai sur une de ces TV.

## Lecture

| | Samsung | LG |
|---|---|---|
| Moteur | **AVPlay** (`webapis.avplay`), décodage matériel, plan vidéo sous la page | `<video>` HTML5, pipeline média webOS ; **hls.js** seulement si HLS n'est pas natif |
| HLS / DASH / MP4 / MPEG-TS | oui / oui / oui / oui | oui / oui (natif) / oui / oui |
| Pistes audio, sous-titres | `getTotalTrackInfo`, `setSelectTrack`, `setSilentSubtitle` | `audioTracks` / `textTracks`, niveaux hls.js |
| Format d'image | `setDisplayMethod` (letterbox, zoom, plein écran) | `object-fit` |
| User-Agent par chaîne | oui (`setStreamingProperty('USER_AGENT')`) | non (le navigateur fixe le sien) |
| Referer par chaîne | **non** : AVPlay ne l'accepte pas | **non** : interdit par le navigateur |
| DRM | non utilisé (StreamX ne lit pas de flux protégés) | idem |
| Écran de veille | `webapis.appcommon.setScreenSaver` | géré par webOS pendant la lecture |

## Ce qui n'existe pas pour une appli web TV

Serveur HTTP local (envoi depuis le téléphone, télécommande téléphone), découverte réseau (NSD),
Chromecast, accès aux fichiers de la TV et des clés USB, reconnaissance vocale intégrée, mise à jour
par l'appli elle-même. Les écrans correspondants le disent clairement au lieu d'être vides.

## Requêtes réseau

Samsung : `<access origin="*">` dans `config.xml` autorise les requêtes vers les serveurs des
playlists (pas de blocage CORS pour une appli installée). LG : les applis installées sont chargées
depuis `file://`, leurs requêtes vers d'autres serveurs sont autorisées par le moteur webOS.

## VIDAA (Hisense) : étude

- VIDAA OS exécute des applis HTML5 (moteur basé sur Chromium/Opera selon les années) ; le cœur
  partagé et l'interface fonctionneraient avec une coque comme celles de Samsung et LG (touches,
  `<video>` HTML5, sortie de l'appli).
- **Blocage principal** : pas de portail développeur public en libre-service. La publication et
  l'installation passent par le programme partenaires VIDAA (contrat, certification, soumission
  faite par VIDAA). Le système s'appelle désormais aussi « Home OS » / « V » chez Hisense.
- Il existe des méthodes non officielles (installer une PWA via le navigateur de la TV) : je ne les
  utilise pas, elles ne sont ni documentées par VIDAA ni stables d'une mise à jour à l'autre.
- Sources : page partenaires <https://www.vidaa.com/partners/>, guide WebApp VIDAA (PDF partenaires).
- Conclusion : techniquement faisable avec le même code, mais **non installable** sans accord
  partenaire VIDAA. Je ne crée pas de faux paquet. Si tu obtiens un accès partenaire, la coque VIDAA
  tient en un fichier `platform.ts` comme `lg-webos/src/platform.ts`.
