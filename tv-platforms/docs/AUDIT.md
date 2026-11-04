# StreamX TV : audit de l'application Android et matrice de parité

Base auditée : `main` (application Android `fr.ahmad.tclstreamx`, 62 fichiers Kotlin, ~12 700 lignes).
Éditions : **StreamX Samsung** (Tizen, `.wgt`) et **StreamX LG** (webOS, `.ipk`), sur un cœur
TypeScript commun (`tv-platforms/shared`). VIDAA : étude seulement (voir `PLATFORMS.md`).

## 1. Inventaire de l'application Android

| Domaine | Fichiers Android | Rôle |
|---|---|---|
| Démarrage, plantages | `TclApp.kt`, `MainActivity.kt`, `AppContainer.kt` | Injection, journal de plantage, écran de veille |
| Base de données | `data/db/*` (Room) | Playlists, chaînes, favoris, historique, reprise, guide, verrous |
| Import M3U | `data/m3u/M3uParser.kt`, `PlaylistImporter.kt`, `RegionTagger.kt` | Lecture en flux, classement Direct/Films/Séries, pays et langue |
| Catalogue intégré | `data/catalog/BuiltInCatalog.kt`, `assets/catalog.json` | 62 chaînes officielles gratuites |
| Comptes Xtream | `data/xtream/*` | API player_api, liste en flux, coffre chiffré, adresses sans identifiants |
| Guide | `data/epg/XmltvParser.kt`, `EpgRepository.kt` | XMLTV (gzip), maintenant/ensuite, grille |
| Disponibilité | `data/check/AvailabilityChecker.kt`, `StreamProbe.kt` | Pastilles en ligne / hors ligne |
| Domaine public | `data/vod/VodRepository.kt`, `assets/open_media.json` | Internet Archive, films sous licence libre |
| Sport | `data/sports/SportsRepository.kt`, `assets/sports_destinations.json` | Matchs (ESPN), diffuseurs par région |
| Envoi depuis le téléphone | `data/lan/LanUploadServer.kt`, `ui/screens/LanScreen.kt` | Serveur HTTP local + QR |
| Télécommande téléphone | `data/remote/RemoteHub.kt`, `RemotePlayServer.kt` | Découverte NSD, lecture à distance |
| Chromecast | `player/cast/ChromecastController.kt`, `CastPanel.kt` | Envoi vers un Chromecast |
| Mises à jour | `data/update/Updater.kt`, `UpdateRow.kt` | Vérification GitHub toutes les 30 min, bannière |
| Lecteur | `player/*`, `PlayerScreen.kt`, `PlayerPanels.kt`, `ReplayPanel.kt` | Media3, pistes, format, vitesse, minuterie, relances, replay |
| Écrans | `ui/screens/*`, `ui/nav/AppRoot.kt` | Accueil, Chaînes, Films, Séries, Animation, Sport, Mes fichiers, Recherche, Playlists, Réglages, Guide |
| Recherche vocale | `ui/screens/Voice.kt` | SpeechRecognizer intégré |
| Langues | `res/values*/strings.xml` | Français, arabe (RTL) |

## 2. Matrice de parité

États : **Implémentée et testée** (tests automatiques ou essai dans un navigateur au format TV) ·
**Implémentée mais non testée sur TV réelle** · **Partiellement compatible** · **Bloquée par la
plateforme** · **Non implémentée**.

Aucune fonction n'est encore « testée sur TV réelle » : ce sera le cas après ton essai sur le
Samsung 55" 2025 (voir `INSTALL-SAMSUNG.md`).

| Fonction Android | Composant source | Samsung | LG | VIDAA | Tests | État réel |
|---|---|---|---|---|---|---|
| Import M3U/M3U8 par URL, en flux, gzip | `M3uParser.kt`, `PlaylistImporter.kt` | `shared/core/m3u.ts`, `importer.ts` | idem | étude | `m3u.test.ts` (13) | Implémentée et testée (hors TV) |
| Classement Direct / Films / Séries, épisodes S01E02 | `M3uParser.kt` | `m3u.ts` (`classify`, `seriesShow`) | idem | étude | `m3u.test.ts` | Implémentée et testée (hors TV) |
| Pays et langue des groupes | `RegionTagger.kt` | `core/region.ts` | idem | étude | `m3u.test.ts` | Implémentée et testée (hors TV) |
| Filtre par région (bandeau) | `RegionStrip.kt` | — | — | — | — | Non implémentée |
| Catalogue intégré (62 chaînes) | `BuiltInCatalog.kt` | `importer.installCatalog` | idem | étude | essai navigateur | Implémentée mais non testée sur TV réelle |
| Compte Xtream (connexion, catégories, listes) | `XtreamClient.kt`, `XtreamRepository.kt` | `xtream/client.ts` | idem | étude | `xtream.test.ts` (12) | Implémentée et testée (hors TV) |
| Identifiants chiffrés, jamais en clair | `SecretBox.kt` (Keystore) | `security/vault.ts` (WebCrypto, clé non exportable) | idem | étude | `storage.test.ts` | Implémentée et testée (hors TV), limites dans `SECURITY.md` |
| Adresses de lecture sans identifiants | `XtreamUrls.kt` | `xtream/urls.ts` (`xtream://`) | idem | étude | `xtream.test.ts` | Implémentée et testée (hors TV) |
| Fiche film / série Xtream (résumé, saisons, épisodes) | `XtreamClient.kt`, `SeriesPanel.kt` | `ui/details.ts`, `screens/vod.ts` | idem | étude | `xtream.test.ts` | Implémentée mais non testée sur TV réelle |
| Guide XMLTV (gzip), maintenant / ensuite | `XmltvParser.kt`, `EpgRepository.kt` | `epg/xmltv.ts`, `epg/service.ts` | idem | étude | `epg.test.ts` | Implémentée et testée (hors TV) |
| Grille du guide | `GuideScreen.kt` | `screens/guide.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Accueil (tuiles, à la une, reprendre, rangées) | `HomeScreen.kt`, `HomeData.kt` | `screens/home.ts` | idem | étude | essai navigateur 1920×1080 | Implémentée mais non testée sur TV réelle |
| Chaînes : groupes, favoris, liste virtualisée, aperçu | `LiveScreen.kt` | `screens/live.ts` | idem | étude | essai navigateur | Implémentée mais non testée sur TV réelle |
| Films / Séries des playlists (grille, groupes) | `VodScreen.kt` | `screens/vod.ts` | idem | étude | essai navigateur | Implémentée mais non testée sur TV réelle |
| Animation (groupes jeunesse des playlists) | `VodScreen.kt` (`ANIMATION_WORDS`) | `screens/vod.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Rayons domaine public (Internet Archive, films libres) | `VodRepository.kt`, `open_media.json` | — | — | — | — | Non implémentée |
| Sport (matchs, diffuseurs) | `SportsRepository.kt`, `SportsScreen.kt` | écran « pas encore disponible » | idem | — | — | Non implémentée |
| Recherche texte (tout, sans accents) | `SearchScreen.kt` | `screens/search.ts` + `db.search` | idem | étude | `storage.test.ts` | Implémentée et testée (hors TV) |
| Recherche vocale | `Voice.kt` | micro du clavier de la TV | micro du clavier de la TV | — | — | Partiellement compatible (pas d'API vocale pour les applis web) |
| Playlists : ajout URL / Xtream, actualisation, suppression | `PlaylistsScreen.kt` | `screens/playlists.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Actualisation automatique (24 h), guide (12 h) | `TclApp.kt` | `ui/app.ts` (`refreshStale`) | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Réglages persistants | `SettingsStore.kt` | `db.setting` (IndexedDB) | idem | étude | `storage.test.ts` | Implémentée et testée (hors TV) |
| Langue FR / AR et sens RTL | `strings.xml` | `i18n` (343 textes générés depuis Android) | idem | étude | `i18n.test.ts` | Implémentée et testée (hors TV) |
| Contrôle parental (code, catégories cachées, adultes) | `SettingsScreen.kt`, `Daos.kt` | `security/pin.ts`, `db.locks` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Historique, reprise des films | `Daos.kt` | `db.touchHistory`, `saveProgress` | idem | étude | `storage.test.ts` | Implémentée et testée (hors TV) |
| Lecture HLS | Media3 | AVPlay (matériel) | `<video>` natif, hls.js sinon | étude | `player.test.ts` | Implémentée mais non testée sur TV réelle |
| Lecture DASH | Media3 | AVPlay | `<video>` natif webOS | étude | — | Implémentée mais non testée sur TV réelle |
| Lecture MP4 / MPEG-TS | Media3 | AVPlay | `<video>` natif webOS | étude | — | Implémentée mais non testée sur TV réelle |
| Pistes audio / sous-titres / qualité | `PlayerPanels.kt` | `avplay.ts` (`getTotalTrackInfo`) | `html5.ts` (pistes natives, niveaux hls.js) | étude | `player.test.ts` | Implémentée mais non testée sur TV réelle |
| Format d'image (ajusté, zoom, étiré) | `PlayerScreen.kt` | `setDisplayMethod` | `object-fit` | étude | — | Implémentée mais non testée sur TV réelle |
| Relances, adresses de secours, délai de blocage | `PlaybackController.kt`, `PlaybackErrors.kt` | `player/controller.ts` | idem | étude | `player.test.ts` | Implémentée et testée (hors TV) |
| Pause du direct, retour au direct | `PlaybackController.kt` | `controller.ts` (`goLive`) | idem | étude | `player.test.ts` | Implémentée mais non testée sur TV réelle |
| Chaîne précédente (0), zapping, saisie du numéro | `PlayerScreen.kt` | `screens/player.ts` | idem | étude | `player.test.ts` | Implémentée mais non testée sur TV réelle |
| Vitesse, minuterie de mise en veille | `PlayerPanels.kt` | `controller.ts` | idem (vitesse : selon le flux) | étude | — | Implémentée mais non testée sur TV réelle |
| User-Agent par chaîne | `PlaybackController.kt` | `setStreamingProperty('USER_AGENT')` | non modifiable par `<video>` | étude | — | Partiellement compatible |
| Referer par chaîne | `PlaybackController.kt` | non accepté par AVPlay | non modifiable par `<video>` | — | — | Bloquée par la plateforme |
| Replay / timeshift Xtream | `ReplayPanel.kt` | adresse prête (`resolveRef`), pas d'écran | idem | — | `xtream.test.ts` | Non implémentée (écran) |
| Pastilles de disponibilité | `AvailabilityChecker.kt`, `StreamProbe.kt` | statut affiché, pas de vérification | idem | — | — | Non implémentée |
| Écran de veille coupé pendant la lecture | `MainActivity.kt` | `webapis.appcommon.setScreenSaver` | géré par webOS pendant `<video>` | étude | — | Implémentée mais non testée sur TV réelle |
| Touches de la télécommande (médias, couleurs, chiffres, Info, Guide) | `AppRoot.kt` | `tizen.tvinputdevice.registerKey` | codes webOS (Retour 461) | étude | essai navigateur (clavier) | Implémentée mais non testée sur TV réelle |
| Appui long OK (favori, options) | `Actions.kt` | `ui/app.ts` (600 ms) | idem | étude | essai navigateur | Implémentée mais non testée sur TV réelle |
| Confirmation de sortie | `ExitDialog.kt` | `tizen.application…exit()` | `webOS.platformBack()` | étude | essai navigateur | Implémentée mais non testée sur TV réelle |
| À propos : Ahmad Ahmad, QR site et GitHub | `About.kt` | `ui/about.ts` | idem | étude | build | Implémentée mais non testée sur TV réelle |
| Mes fichiers (vidéos de la TV, USB) | `LocalScreen.kt` | — | — | — | — | Bloquée par la plateforme |
| Envoi depuis le téléphone (serveur local + QR) | `LanUploadServer.kt` | — | — | — | — | Bloquée par la plateforme (pas de serveur dans une appli web) |
| Télécommande depuis le téléphone | `RemoteHub.kt` | — | — | — | — | Bloquée par la plateforme (pas de serveur ni de NSD) |
| Chromecast | `ChromecastController.kt` | — | — | — | — | Bloquée par la plateforme |
| Mises à jour automatiques | `Updater.kt` | réinstallation du `.wgt` (mode développeur) | réinstallation de l'`.ipk` | — | — | Bloquée par la plateforme (une appli web ne s'installe pas elle-même) |
| Journal de plantage | `TclApp.kt` | console + message à l'écran | idem | — | — | Partiellement compatible |

## 3. Ce que les tests couvrent

`npm test` (47 tests, Vitest) : analyseur M3U et cas réels, régions, URL et API Xtream (faux
serveur), coffre et stockage IndexedDB (`fake-indexeddb`), XMLTV, textes FR/AR, contrôleur de
lecture (faux lecteur : relances, secours, blocage, zapping, chaîne précédente).
`npm run typecheck` : TypeScript strict. Les deux éditions sont construites et ouvertes en
1920×1080 dans Chrome depuis `file://` (comme sur la TV) pour vérifier l'affichage et la navigation
au clavier. La CI refait tout à chaque envoi sur la branche.
