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

La CI (`.github/workflows/tv-platforms.yml`) teste, construit, parcourt les deux éditions dans
Chrome à la télécommande (50 000 entrées) et publie une pré-release `tv-build-N` :
`StreamX-LG.ipk`, `StreamX-Samsung-unsigned.zip`, et `StreamX-Samsung.wgt` signé quand les
certificats Samsung sont dans les secrets. Téléchargement sans compte :
<https://github.com/ahmedooo1/tcl-stream-x/releases>.

```bash
node tools/e2e.mjs samsung 200000 e2e-out   # parcours complet dans Chrome, captures + mesures
```
