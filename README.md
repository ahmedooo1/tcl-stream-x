# TCL Stream X

Lecteur IPTV pour Google TV (TCL 2024), en Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, Room et DataStore.
Application personnelle non officielle : « TCL » est une marque de TCL, sans lien avec ce projet.

## Ce que fait l'application

| Domaine | Implémentation |
|---|---|
| Chaînes officielles gratuites | `catalog/official_sources.json` : liste blanche (identifiant de chaîne + domaine appartenant au diffuseur). L'index d'URL vient d'iptv-org, mais une URL n'est retenue que si elle est servie par le domaine du diffuseur (France 24, Al Jazeera, Euronews, DW, Al Arabiya, Sky News Arabia, TRT, KTV, Oman TV, FIFA+, Real Madrid TV…). Les restreams, IP brutes et hôtes pirates sont rejetés par construction. |
| Vérification | À chaque build, `tools/build_catalog.py --check` télécharge le manifeste, une variante et un segment de chaque flux. Sur la TV, `AvailabilityChecker` refait la même vérification (4 en parallèle) et l'état s'affiche en direct (pastille verte / orange / grise). Aucun contournement : 403, géoblocage et DRM sont affichés comme tels. |
| Playlists M3U/M3U8 | Parseur en flux continu (`M3uParser`), import par lots de 500 dans une nouvelle « génération » Room, bascule atomique en fin d'import. Gère `url-tvg`, `#EXTGRP`, `#EXTVLCOPT` (User-Agent, Referer), `#KODIPROP` (DRM signalé, jamais lu), `url|User-Agent=…`, gzip. Testé à 200 000 entrées (JVM) et 50 000 entrées (émulateur). |
| Import | URL, fichier USB (sélecteur système s'il existe), ou envoi depuis le téléphone : page web locale sur le Wi‑Fi, code PIN à 6 chiffres, limite 150 Mo, active seulement pendant l'écran d'envoi. |
| Lecteur | Un seul ExoPlayer partagé entre l'aperçu et le plein écran (pas de second décodeur). HLS, DASH, MP4/TS. Sources de secours, réessai automatique sur erreurs réseau, reprise derrière la fenêtre live, détection HLS pour les URL sans extension. Qualité, audio, sous-titres, format d'image, favoris, zapping ▲▼ / CH±, saisie du numéro de chaîne. |
| EPG | XMLTV (.xml, .gz, .zip) lu en flux, seulement les chaînes présentes et une fenêtre de 39 h. Source : en-tête de la playlist ou URL réglée par l'utilisateur. |
| Sport | Calendrier : point d'accès public ESPN (sans clé, non documenté officiellement : chaque ligue qui échoue est signalée). Diffuseurs légaux par région dans `assets/sports_destinations.json`, avec source et date de vérification ; ouverture du service officiel via le Play Store. |
| Films, séries, animation | Internet Archive (API publique, domaine public), films Blender sous licence CC BY, flux de démonstration DASH/HLS, fichiers de la TV et des clés USB (MediaStore), liens vers des services officiels gratuits. |
| Langues | Français (par défaut) et arabe, mise en page RTL automatique, choix de langue dans l'application. |
| Navigation | 100 % télécommande : rail numéroté, `focusRestorer`, aucune action tactile requise. |

## Construire l'APK

L'environnement de développement de Claude n'a pas accès à Maven/Google : le build se fait sur GitHub Actions (gratuit). Chaque push sur `main` :

1. vérifie toutes les chaînes du catalogue depuis le serveur CI ;
2. sonde les autres sources (films, API sport, Internet Archive, guides XMLTV) ;
3. lance les tests unitaires ;
4. compile l'APK debug ;
5. démarre un émulateur Android TV (API 30) et y exécute : lecture réelle de flux via le `PlaybackController` de l'application, import de 50 000 chaînes, et une visite de l'interface à la télécommande avec captures d'écran en français et en arabe ;
6. publie une release `build-N` avec l'APK, `RESULTS.md`, les rapports JSON et les captures.

En local (Android Studio ou SDK installé) : `./gradlew assembleDebug` puis `app/build/outputs/apk/debug/app-debug.apk`.

## Installer sur la TV TCL avec ADB

1. Sur la TV : Paramètres › Système › À propos › cliquez 7 fois sur « Build du système d'exploitation Android TV » pour activer les options pour les développeurs.
2. Paramètres › Système › Options pour les développeurs › activez **Débogage USB** (sur Google TV il couvre aussi le débogage réseau).
3. Notez l'adresse IP de la TV : Paramètres › Réseau et Internet › votre Wi‑Fi.
4. Sur l'ordinateur (même réseau) :

```bash
adb connect 192.168.1.50:5555        # remplacez par l'IP de la TV, acceptez la demande sur l'écran
adb install -r TCLStreamX-debug.apk
adb shell am start -n fr.ahmad.tclstreamx/.MainActivity
```

Mise à jour : même commande `adb install -r` (toutes les builds sont signées avec la même clé de debug, `app/debug.keystore`, qui n'est pas une clé de publication).
Journal en cas de problème : `adb logcat -s TclStreamX ExoPlayerImpl MediaCodec`.

## Ce qui reste à vérifier sur la vraie TV

- Décodage matériel HEVC/4K et HDR du téléviseur (l'émulateur décode en logiciel).
- Chaînes à restriction géographique (Arte, TV5MONDE…) : non vérifiables depuis un serveur CI hors de France.
- Comportement des touches spécifiques de la télécommande TCL (CH±, INFO, chiffres) : codes Android standard prévus, à confirmer.
- Sélecteur de fichiers USB : absent sur certaines Google TV ; l'envoi depuis le téléphone le remplace.
- Mémoire sur une TV 2 Go avec une très grande playlist et les logos.

## Licences

Polices IBM Plex Sans Arabic, IBM Plex Mono et Barlow Condensed sous SIL Open Font License (`licenses/`).
