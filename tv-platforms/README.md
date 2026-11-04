# StreamX TV : Samsung (Tizen) et LG (webOS)

Les éditions TV de StreamX, sur un cœur TypeScript commun. L'application Android (`../app`) n'est
pas modifiée par ce dossier.

```
shared/         cœur commun : M3U, Xtream, IndexedDB, coffre, guide, lecteur, interface
samsung-tizen/  coque Samsung : AVPlay, touches Tizen, config.xml
lg-webos/       coque LG : vidéo HTML5, touches webOS, appinfo.json
tools/          build (vite-tv.ts), génération des textes depuis Android
docs/           AUDIT (matrice de parité), SECURITY, PLATFORMS (et VIDAA), INSTALL-SAMSUNG, INSTALL-LG
```

```bash
npm ci
npm run typecheck && npm test
npm run build          # dist/samsung et dist/lg
npm run strings        # régénère les textes FR/AR depuis app/src/main/res
```

La CI (`.github/workflows/tv-platforms.yml`) construit `StreamX-Samsung.wgt` (signé si les
certificats Samsung sont dans les secrets) et `StreamX-LG.ipk`, en artefacts.
