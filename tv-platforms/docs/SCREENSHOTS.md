# Comparaison visuelle Android / Samsung / LG

Mêmes écrans, pilotés uniquement à la télécommande.

- **Android** : l'application Android inchangée, sur l'émulateur Android TV de la CI
  (`.github/workflows/android-screenshots.yml`, test existant `UiTourTest`, 1280×640), avec le
  catalogue réel. Les images sont publiées dans la pré-release `tv-comparison-screens` (images
  seules). Le parcours de ce test date d'une version antérieure de l'interface : certaines de ses
  captures ne montrent plus l'écran que leur nom annonce (par exemple « live » ouvre « Mes
  fichiers »). Je n'ai pas modifié ce test Android ; seules les captures correctement identifiées
  sont reprises ici, sous un nom qui dit ce qu'elles montrent.
- **Samsung** et **LG** : les éditions construites, chargées depuis `file://` dans Chrome 1920×1080
  (`tools/e2e.mjs`), avec une playlist générée de 2 000 entrées, des réponses simulées pour
  Internet Archive et ESPN, et une image de test pour la lecture. **Ce ne sont pas des captures de
  téléviseur** : ni AVPlay (Samsung) ni le moteur webOS (LG) ne sont utilisés ici.

Les contenus diffèrent (vraies chaînes à gauche, liste de test ailleurs) : ce qui se compare, c'est
la structure, la navigation, la typographie et le sens de lecture.

## Accueil

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-home-fr.jpg) | ![Samsung](screenshots/samsung-01-home.jpg) | ![LG](screenshots/lg-01-home.jpg) |

## Séries (à la une)

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-series-fr.jpg) | ![Samsung](screenshots/samsung-07a-series-start.jpg) | ![LG](screenshots/lg-07a-series-start.jpg) |

## Animation (à la une)

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-animation-fr.jpg) | ![Samsung](screenshots/samsung-12a-animation.jpg) | ![LG](screenshots/lg-12a-animation.jpg) |

## Mes fichiers

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-local-fr.jpg) | ![Samsung](screenshots/samsung-15-local.jpg) | ![LG](screenshots/lg-15-local.jpg) |

## Confirmation de sortie

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-exit-fr.jpg) | ![Samsung](screenshots/samsung-16-exit.jpg) | ![LG](screenshots/lg-16-exit.jpg) |

## Accueil en arabe (RTL)

| Android | Samsung | LG |
|---|---|---|
| ![Android](screenshots/android-home-ar.jpg) | ![Samsung](screenshots/samsung-13-home-ar.jpg) | ![LG](screenshots/lg-13-home-ar.jpg) |

## Écrans des éditions TV sans capture Android à jour

| Écran | Samsung | LG |
|---|---|---|
| Import d'une playlist (2 000 entrées) | ![](screenshots/samsung-02-playlists.jpg) | ![](screenshots/lg-02-playlists.jpg) |
| Chaînes | ![](screenshots/samsung-03-live.jpg) | ![](screenshots/lg-03-live.jpg) |
| Défilement | ![](screenshots/samsung-04-live-scrolled.jpg) | ![](screenshots/lg-04-live-scrolled.jpg) |
| Filtre pays / langue | ![](screenshots/samsung-05-live-region.jpg) | ![](screenshots/lg-05-live-region.jpg) |
| Films (à la une) | ![](screenshots/samsung-06-movies.jpg) | ![](screenshots/lg-06-movies.jpg) |
| Séries regroupées | ![](screenshots/samsung-07-series-shows.jpg) | ![](screenshots/lg-07-series-shows.jpg) |
| Recherche | ![](screenshots/samsung-08-search.jpg) | ![](screenshots/lg-08-search.jpg) |
| Réglages | ![](screenshots/samsung-09-settings.jpg) | ![](screenshots/lg-09-settings.jpg) |
| Lecteur (image de test décodée) | ![](screenshots/samsung-10-player.jpg) | ![](screenshots/lg-10-player.jpg) |
| Options du lecteur | ![](screenshots/samsung-11-player-options.jpg) | ![](screenshots/lg-11-player-options.jpg) |
| Sport (calendrier simulé) | ![](screenshots/samsung-12-sports.jpg) | ![](screenshots/lg-12-sports.jpg) |
| Chaînes en arabe | ![](screenshots/samsung-14-live-ar.jpg) | ![](screenshots/lg-14-live-ar.jpg) |

Android, autres captures : ![](screenshots/android-home-row-fr.jpg) ![](screenshots/android-local-ar.jpg)

Mesures : `screenshots/samsung-metrics.json`, `screenshots/lg-metrics.json` (2 000 entrées) et
`screenshots/samsung-metrics-200k.json` (200 000 entrées).
