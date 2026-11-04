# Installer StreamX Samsung (Tizen) sur ta TV

Une TV Samsung n'installe que des paquets `.wgt` signés avec **ton** certificat Samsung, qui
contient le **DUID** de la TV. C'est la seule étape que je ne peux pas faire à ta place : elle
demande ton compte Samsung et l'accès à la TV.

## 1. Sur ton ordinateur : Tizen Studio (une fois)

1. Télécharge **Tizen Studio** (version avec IDE, plus simple pour les certificats) :
   <https://developer.tizen.org/development/tizen-studio/download>.
2. Ouvre **Package Manager** → onglet **Extension SDK** → installe **TV Extensions** (la plus
   récente) et **Samsung Certificate Extension**.
3. Crée un compte sur <https://developer.samsung.com> si tu n'en as pas (gratuit).

## 2. Sur la TV : mode développeur (une fois)

1. TV et ordinateur sur le même réseau. Note l'adresse IP de l'ordinateur.
2. Sur la TV : **Applications** (Apps), puis tape **1 2 3 4 5** sur la télécommande.
3. Active **Developer mode**, saisis l'IP de l'ordinateur, valide, puis **redémarre la TV**
   (débranche-la 30 s si le menu ne propose pas de redémarrage).
4. Note l'adresse IP de la TV (Paramètres → Général → Réseau → État du réseau → Paramètres IP).

## 3. Connecter la TV et lire son DUID

Dans Tizen Studio : **Tools → Device Manager → Remote Device Manager → Scan** (ou **+** avec l'IP de
la TV, port 26101), puis active **Connection**. La TV apparaît dans la liste. Le DUID est lu
automatiquement à l'étape suivante. En ligne de commande :

```bash
sdb connect <IP_DE_LA_TV>
sdb devices          # note le nom de la cible, par exemple QE55... ou UE55...
```

**Envoie-moi le modèle exact (nom affiché par `sdb devices`) et l'année** : je m'en sers pour la
matrice de compatibilité.

## 4. Créer les certificats (une fois)

**Tools → Certificate Manager → + → Samsung → TV** :

1. Profil : `StreamX`.
2. **Author certificate** : *Create a new author certificate*, nom `Ahmad Ahmad`, un mot de passe
   que tu gardes. Connexion au compte Samsung demandée.
3. **Distributor certificate** : privilège **Public**, et le DUID de ta TV (coché automatiquement si
   la TV est connectée dans Device Manager ; sinon ajoute-le à la main).
4. Termine. Les fichiers sont dans `~/SamsungCertificate/StreamX/` :
   `author.p12` et `distributor.p12`.

**Garde une copie de ces fichiers et des mots de passe** : une mise à jour signée avec un autre
certificat auteur est refusée par la TV (il faudrait désinstaller l'appli et perdre ses données).

## 5. Option A : signer et installer depuis ton ordinateur

Télécharge l'artefact **StreamX-Samsung** de la CI (onglet Actions du dépôt → dernier run
« StreamX TV » → Artifacts), décompresse `StreamX-Samsung-unsigned.zip` dans un dossier `streamx`,
puis :

```bash
tizen package -t wgt -s StreamX -o . -- streamx
tizen install -n StreamX.wgt -t <NOM_DE_LA_CIBLE>
```

## 6. Option B : laisser la CI signer (recommandé pour les mises à jour)

Ajoute toi-même ces **secrets** dans GitHub (dépôt → Settings → Secrets and variables → Actions →
New repository secret). Je ne les écris pas moi-même et ils ne passent jamais dans le dépôt.

| Secret | Contenu |
|---|---|
| `TIZEN_AUTHOR_P12` | `base64 -w0 author.p12` (sur macOS : `base64 -i author.p12`) |
| `TIZEN_AUTHOR_PASSWORD` | mot de passe du certificat auteur |
| `TIZEN_DISTRIBUTOR_P12` | `base64 -w0 distributor.p12` |
| `TIZEN_DISTRIBUTOR_PASSWORD` | mot de passe du certificat distributeur |
| `TIZEN_AUTHOR_CA`, `TIZEN_DISTRIBUTOR_CA` | facultatifs : seulement si la signature échoue avec « Invalid certificate chain » (fichiers `.cer` de `tizen-studio/tools/certificate-generator/certificates/`, en base64) |

Au run suivant, l'artefact contient **StreamX-Samsung.wgt** signé. Installation :

```bash
sdb connect <IP_DE_LA_TV>
tizen install -n StreamX-Samsung.wgt -t <NOM_DE_LA_CIBLE>
```

## Mises à jour

L'appli ne peut pas se mettre à jour seule sur la TV. Pour une nouvelle version : récupère le
nouveau `.wgt` et relance `tizen install` ; les playlists, favoris et réglages sont conservés tant
que le certificat auteur est le même.

## Si quelque chose ne va pas

| Message | Cause probable |
|---|---|
| `install failed [118, -12]` / signature | DUID absent du certificat distributeur, ou mauvais profil |
| `Check certificate error: Invalid certificate chain` | secrets `TIZEN_*_CA` à fournir (voir plus haut) |
| la TV n'apparaît pas | IP de l'ordinateur mal saisie en mode développeur, ou TV non redémarrée |
| écran noir sur une chaîne | flux qui exige un en-tête Referer (non accepté par AVPlay, voir `PLATFORMS.md`) |

Débogage : `tizen run -t <cible> -p StrmXTclX1.StreamX`, ou Tizen Studio → Run As → Tizen Web
Application (debug) qui ouvre les DevTools de Chrome.
