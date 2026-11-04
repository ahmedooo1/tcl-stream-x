# StreamX TV : audit de l'application Android et matrice de parité

Base auditée : `main` (application Android `fr.ahmad.tclstreamx`, 62 fichiers Kotlin, ~12 700 lignes),
non modifiée par la branche `multiplatform-tv` (`git diff origin/main -- app` est vide).
Éditions : **StreamX Samsung** (Tizen, `.wgt`) et **StreamX LG** (webOS, `.ipk`), sur un cœur
TypeScript commun (`tv-platforms/shared`). VIDAA : étude seulement (voir `PLATFORMS.md`).

## États utilisés

- **Implémentée et testée** : couverte par les tests automatiques (Vitest) et/ou par le parcours
  de bout en bout dans Chrome (`tools/e2e.mjs`, application construite chargée depuis `file://`,
  pilotée uniquement aux touches de la télécommande). **Ce n'est pas un test sur téléviseur.**
- **Implémentée mais non testée sur TV réelle** : code écrit et construit, comportement qui dépend
  du moteur de la TV (AVPlay, touches Tizen/webOS, écran de veille, réseau de la TV).
- **Partiellement compatible**, **Bloquée par la plateforme**, **Non implémentée** : comme leur nom.

**Aucune ligne n'est « validée sur TV réelle »** : ce sera possible après ton essai sur le
Samsung 55" 2025 (`INSTALL-SAMSUNG.md`) et, si tu en as une, sur une TV LG.

## Inventaire de l'application Android

| Domaine | Fichiers Android | Rôle |
|---|---|---|
| Démarrage, plantages | `TclApp.kt`, `MainActivity.kt`, `AppContainer.kt` | Injection, journal de plantage, rafraîchissements périodiques |
| Base de données | `data/db/*` (Room) | Playlists, chaînes, favoris, historique, reprise, guide, verrous |
| Import M3U | `data/m3u/*` | Lecture en flux, classement Direct/Films/Séries, pays et langue |
| Catalogue intégré | `data/catalog/BuiltInCatalog.kt` | 62 chaînes officielles gratuites |
| Comptes Xtream | `data/xtream/*` | API player_api, liste en flux, coffre chiffré, adresses sans identifiants, replay |
| Guide | `data/epg/*` | XMLTV (gzip), maintenant/ensuite, grille |
| Disponibilité | `data/check/*` | Pastilles en ligne / hors ligne |
| Domaine public | `data/vod/VodRepository.kt`, `open_media.json` | Internet Archive, films sous licence libre, services officiels |
| Sport | `data/sports/*`, `sports_destinations.json` | Matchs (ESPN), diffuseurs par région |
| Téléphone / réseau | `data/lan/*`, `data/remote/*`, `player/cast/*` | Envoi de playlist, télécommande téléphone, Chromecast |
| Mises à jour | `data/update/Updater.kt` | Vérification GitHub toutes les 30 min, bannière, installation |
| Lecteur | `player/*`, `PlayerScreen.kt`, `PlayerPanels.kt`, `ReplayPanel.kt` | Media3, pistes, format, vitesse, minuterie, relances, replay |
| Écrans | `ui/screens/*`, `ui/nav/AppRoot.kt` | Accueil, Chaînes, Films, Séries, Animation, Sport, Mes fichiers, Recherche, Playlists, Réglages, Guide |

## Matrice de parité

| Fonction Android | Composant source | Samsung | LG | VIDAA | Tests | État réel |
|---|---|---|---|---|---|---|
| Import M3U/M3U8 par URL, en flux, gzip | `M3uParser.kt`, `PlaylistImporter.kt` | `core/m3u.ts`, `core/importer.ts` | idem | étude | `m3u.test.ts`, `large.test.ts`, e2e 200 000 entrées | Implémentée et testée |
| Très grandes playlists (plafond 250 000 comme Android) | `PlaylistImporter.kt` | `importer.ts` (`MAX_ENTRIES`) | idem | étude | `large.test.ts` (300 000 lues, plafond vérifié), e2e 200 000 | Implémentée et testée (Chrome de bureau : 51 s ; une TV sera plus lente) |
| Classement Direct / Films / Séries, épisodes S01E02 | `M3uParser.kt` | `m3u.ts` | idem | étude | `m3u.test.ts`, e2e | Implémentée et testée |
| Pays et langue des groupes | `RegionTagger.kt` | `core/region.ts` | idem | étude | `m3u.test.ts` | Implémentée et testée |
| Filtre par pays / langue (Chaînes, Films, Séries, Animation, Recherche) | `RegionStrip.kt`, `observeRegions` | `ui/region.ts`, `ui/lineup.ts`, index `rk` | idem | étude | `storage.test.ts`, `large.test.ts`, e2e | Implémentée et testée |
| Catalogue intégré (62 chaînes), statut et zone de diffusion | `BuiltInCatalog.kt` | `importer.installCatalog`, `catalogStatus` | idem | étude | e2e | Implémentée et testée |
| Compte Xtream (connexion, catégories, listes) | `XtreamClient.kt`, `XtreamRepository.kt` | `xtream/client.ts` | idem | étude | `xtream.test.ts` (faux serveur) | Implémentée et testée (hors TV) |
| Identifiants chiffrés, jamais en clair | `SecretBox.kt` (Keystore) | `security/vault.ts` (WebCrypto, clé non exportable) | idem | étude | `storage.test.ts` | Implémentée et testée, limites dans `SECURITY.md` |
| Adresses de lecture sans identifiants | `XtreamUrls.kt` | `xtream/urls.ts` (`xtream://`) | idem | étude | `xtream.test.ts` | Implémentée et testée |
| Fiche film / série Xtream (résumé, saisons, épisodes) | `SeriesPanel.kt` | `ui/details.ts`, `screens/vod.ts` | idem | étude | `xtream.test.ts` | Implémentée mais non testée sur TV réelle |
| Replay / catch-up Xtream (jours, émissions, lecture) | `ReplayPanel.kt`, `epgTable`, `replayRef` | `ui/replay.ts` (options du lecteur, guide) | idem | étude | `xtream.test.ts` (table, base64, adresse timeshift) | Implémentée mais non testée sur TV réelle |
| Guide XMLTV (gzip), maintenant / ensuite | `XmltvParser.kt`, `EpgRepository.kt` | `epg/*` | idem | étude | `epg.test.ts` | Implémentée et testée |
| Grille du guide | `GuideScreen.kt` | `screens/guide.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Accueil (tuiles, à la une, reprendre, rangées, bannière de mise à jour) | `HomeScreen.kt`, `UpdateRow.kt` | `screens/home.ts` | idem | étude | e2e (FR et AR) | Implémentée et testée |
| Chaînes : groupes, favoris, liste virtualisée, aperçu | `LiveScreen.kt` | `screens/live.ts` | idem | étude | e2e (défilement dans 120 000 chaînes) | Implémentée et testée (aperçu vidéo : non testé sur TV) |
| Pastilles de disponibilité (« Vérifier ces chaînes », catalogue toutes les 6 h) | `AvailabilityChecker.kt`, `StreamProbe.kt` | `check/probe.ts`, bouton dans Chaînes | idem | étude | `probe.test.ts` | Partiellement compatible : pas d'User-Agent/Referer personnalisé, un refus CORS donne « inconnu » (jamais « hors ligne ») |
| Films / Séries / Animation : à la une, groupes, tri (source, A→Z, récents, notes) | `VodScreen.kt` | `screens/vod.ts` | idem | étude | e2e | Implémentée et testée |
| Séries M3U regroupées par série, panneau d'épisodes | `VodScreen.kt` (`PlaylistEpisodeShows`) | `screens/vod.ts` | idem | étude | e2e | Implémentée et testée |
| Domaine public (Internet Archive : genres, recherche, fichiers, sous-titres) | `VodRepository.kt` | `vod/archive.ts`, `screens/vod.ts` | idem | étude | `archive.test.ts`, e2e (réponses simulées) | Implémentée et testée (service réel non joignable depuis l'environnement de test) |
| Films sous licence libre (Blender…) | `open_media.json` | `assets/open_media.json` | idem | étude | `archive.test.ts` | Implémentée et testée |
| Chaînes gratuites films / séries / jeunesse | `VodScreen.kt` (`CategoryChannels`) | `screens/vod.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Services officiels gratuits (ouvrir l'appli ou le Play Store) | `VodScreen.kt` (`ServicesGrid`) | — | — | — | — | Bloquée par la plateforme (une appli web TV n'ouvre pas la boutique d'une autre appli) |
| Sport : calendrier ESPN, scores en direct, diffuseurs FR / Monde arabe, chaînes sport | `SportsScreen.kt`, `SportsRepository.kt` | `sports/sports.ts`, `screens/sports.ts` | idem | étude | `sports.test.ts`, e2e (calendrier simulé) | Implémentée et testée ; « où regarder » affiché en texte (pas d'ouverture de boutique) |
| Recherche (sans accents, sections, source, « en ligne », pays/langue, catégories) | `SearchScreen.kt`, `searchFiltered` | `screens/search.ts`, `db.search` | idem | étude | `large.test.ts`, e2e (4 s dans 200 000) | Implémentée et testée |
| Recherche vocale | `Voice.kt` | micro du clavier de la TV | idem | — | — | Partiellement compatible (pas d'API vocale pour les applis web) |
| Playlists : ajout URL / Xtream, actualisation, suppression | `PlaylistsScreen.kt` | `screens/playlists.ts` | idem | étude | e2e (ajout par la télécommande) | Implémentée et testée |
| Import depuis le réseau local (URL d'un PC / téléphone du réseau) | `PlaylistsScreen.kt` | même champ URL | idem | étude | e2e (même chemin) | Implémentée mais non testée sur TV réelle |
| Envoi depuis le téléphone (serveur local + QR) | `LanUploadServer.kt` | — | — | — | — | Bloquée par la plateforme (une appli web ne peut pas écouter un port) |
| Télécommande depuis le téléphone | `RemoteHub.kt` | — | — | — | — | Bloquée par la plateforme |
| Chromecast | `ChromecastController.kt` | — | — | — | — | Bloquée par la plateforme |
| Ouvrir dans un autre lecteur | `PlayerScreen.kt` | — | — | — | — | Bloquée par la plateforme |
| Mes fichiers (vidéos de la TV, USB) | `LocalScreen.kt` | écran explicatif | idem | — | — | Bloquée par la plateforme |
| Actualisation automatique (playlists 24 h, guide 12 h, catalogue 6 h) | `AppContainer.refreshStale` | `ui/app.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Réglages persistants | `SettingsStore.kt` | IndexedDB (`settings`) | idem | étude | `storage.test.ts`, e2e | Implémentée et testée |
| Langue système / FR / AR et sens RTL | `strings.xml`, `SettingsScreen.kt` | `i18n` (343 textes générés depuis Android) | idem | étude | `i18n.test.ts`, e2e en arabe | Implémentée et testée |
| Sous-titres : taille et style (blanc, jaune, fond sombre) | `SettingsScreen.kt` | `ui/app.ts`, `.cue`, `video::cue` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Contrôle parental (code, catégories cachées, adultes) | `SettingsScreen.kt`, `Daos.kt` | `security/pin.ts`, `db.locks` | idem | étude | `ui/lineup.ts` testé (`large.test.ts`) | Implémentée et testée (logique) |
| Historique, reprise des films | `Daos.kt` | `db.touchHistory`, `saveProgress` | idem | étude | `storage.test.ts` | Implémentée et testée |
| Lecture HLS / DASH / MP4 / MPEG-TS | Media3 | AVPlay (matériel) | `<video>` natif webOS, hls.js sinon | étude | `player.test.ts` ; e2e : décodage réel d'une vidéo de test dans Chrome | Implémentée mais non testée sur TV réelle |
| Pistes audio / qualité | `PlayerPanels.kt` | `avplay.ts` | `html5.ts` | étude | `player.test.ts`, e2e (panneau) | Implémentée mais non testée sur TV réelle |
| Sous-titres intégrés au flux | Media3 | AVPlay remet le texte, l'appli l'affiche (`onsubtitlechange`) | rendu natif `<video>` | étude | build | Implémentée mais non testée sur TV réelle |
| Sous-titres externes .srt / .vtt (domaine public) | Media3 | `player/subtitles.ts` (lecture, encodages, affichage) | idem | étude | `subtitles.test.ts` | Implémentée et testée (logique) |
| Format d'image, vitesse, minuterie, favoris dans le lecteur | `PlayerPanels.kt` | `screens/player.ts` | idem | étude | e2e (panneau d'options) | Implémentée mais non testée sur TV réelle |
| Relances, adresses de secours, délai de blocage | `PlaybackController.kt` | `player/controller.ts` | idem | étude | `player.test.ts` | Implémentée et testée |
| Pause du direct, retour au direct, chaîne précédente (0), zapping, numéro | `PlaybackController.kt`, `PlayerScreen.kt` | `controller.ts`, `screens/player.ts` | idem | étude | `player.test.ts` | Implémentée mais non testée sur TV réelle |
| User-Agent par chaîne | `PlaybackController.kt` | `setStreamingProperty('USER_AGENT')` | non modifiable par `<video>` | — | — | Partiellement compatible |
| Referer par chaîne | `PlaybackController.kt` | non accepté par AVPlay | non modifiable | — | — | Bloquée par la plateforme |
| Écran de veille coupé pendant la lecture | `MainActivity.kt` | `webapis.appcommon.setScreenSaver` | géré par webOS | étude | — | Implémentée mais non testée sur TV réelle |
| Touches de la télécommande (médias, couleurs, chiffres, Info, Guide, Retour) | `AppRoot.kt` | `tizen.tvinputdevice.registerKey` | codes webOS (Retour 461) | étude | e2e (flèches, OK, Retour) | Implémentée mais non testée sur TV réelle |
| Appui long OK, confirmation de sortie | `Actions.kt`, `ExitDialog.kt` | `ui/app.ts`, `tizen…exit()` | `webOS.platformBack()` | étude | e2e | Implémentée mais non testée sur TV réelle |
| À propos : Ahmad Ahmad, QR site et GitHub | `About.kt` | `ui/about.ts` | idem | étude | e2e | Implémentée et testée |
| Mises à jour : vérification toutes les 30 min, bannière | `Updater.kt` | `update/update.ts` (tags `tv-build-N`), bannière + QR | idem | — | `update.test.ts` | Implémentée et testée (logique) |
| Mises à jour : installation par l'appli | `Updater.kt` | — | — | — | — | Bloquée par la plateforme : réinstallation depuis un ordinateur (`tizen install`, `ares-install`) |
| Journal de plantage | `TclApp.kt` | console + message à l'écran | idem | — | — | Partiellement compatible |

## Packages : compilé, signé, installable

| Fichier | Compilé | Signé | Installable |
|---|---|---|---|
| `StreamX-LG.ipk` | oui (CI, `ares-package`) | non requis en mode développeur | oui sur une TV LG en mode développeur (non essayé sur TV) |
| `StreamX-Samsung-unsigned.zip` | oui (CI) | **non** | **non** : la TV refuse un paquet non signé |
| `StreamX-Samsung.wgt` | produit dès que les secrets `TIZEN_*` existent | oui (auteur + distributeur Samsung avec le DUID) | oui sur **ta** TV uniquement (celle du DUID) |

## Résultats des tests (dernier passage)

- `npm run typecheck` : TypeScript strict, 0 erreur.
- `npm test` : 12 fichiers, 69 tests, tous réussis (dont 300 000 entrées lues en 0,7 s).
- `tools/e2e.mjs` dans Chrome depuis `file://`, à la télécommande seulement : Samsung et LG,
  14 écrans chacun, aucune erreur JavaScript. À 200 000 entrées (édition Samsung) : import 51 s,
  recherche 4 s, mémoire JavaScript 20 Mo. Chiffres et captures : `docs/screenshots/`.
- CI `StreamX TV (Samsung, LG)` : tests, construction, e2e à 50 000 entrées, packages et pré-release
  `tv-build-N`, tous verts sur la branche.
