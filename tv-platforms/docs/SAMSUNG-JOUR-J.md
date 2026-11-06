# StreamX Samsung : le jour où tu es devant la TV

À emporter : ce PC (Tizen Studio, `tools/tizen-secrets.ps1` et le Gestionnaire d'identification y
sont), le mot de passe de ton compte Samsung. Le PC et la TV doivent être sur le **même réseau** :
l'adresse `192.168.1.11` de chez toi ne vaudra pas là-bas, relève la nouvelle avec `ipconfig`
(ligne Wi-Fi, pas Tailscale ni WSL).

Déjà fait avant ce jour : Tizen Studio et ses extensions, certificat auteur, mots de passe.
La référence reste `INSTALL-SAMSUNG.md`.

## 1. Sur la TV : mode développeur

1. Applications → touches **1 2 3 4 5** → **Developer mode : On**.
2. Saisis l'IP actuelle du PC, valide, redémarre la TV (débranche-la 30 s si besoin).
3. Paramètres → Général → Réseau → État du réseau → Paramètres IP : note l'IP de la TV.

## 2. Connecter la TV et lire son DUID

```powershell
$env:Path += ';C:\tizen-studio\tools;C:\tizen-studio\tools\ide\bin'
sdb connect <IP_TV>
sdb devices            # nom de la cible : note-le, avec le modèle exact
```

Si la TV n'apparaît pas : IP du PC mal saisie en mode développeur, TV pas redémarrée, ou réseau
invité qui isole les appareils. Le DUID se lit dans Tizen Studio à l'étape suivante, TV connectée
dans **Device Manager**.

## 3. Certificat distributeur

Tools → Certificate Manager → profil `StreamX` → distributeur (ou **+** → Samsung → TV en
réutilisant le certificat auteur existant, **sans en créer un nouveau**) :

1. Privilège **Public**.
2. Vérifie que le **DUID de cette TV** est coché.
3. Mot de passe : `pwsh -STA tools/tizen-secrets.ps1 clip TizenDistributorCert 180`, depuis
   `tv-platforms`, puis colle-le dans les champs demandés.
4. Connexion au compte Samsung.

## 4. Signer

**Option CI** (internet nécessaire), depuis `tv-platforms` :

```powershell
pwsh tools/tizen-secrets.ps1 gh-set ahmedooo1/tcl-stream-x "$HOME\SamsungCertificate\StreamX"
gh workflow run "StreamX TV (Samsung, LG)" --repo ahmedooo1/tcl-stream-x --ref multiplatform-tv
gh run watch --repo ahmedooo1/tcl-stream-x
gh run download <ID_DU_RUN> --repo ahmedooo1/tcl-stream-x -n StreamX-Samsung-signed -D wgt
pwsh tools/tizen-secrets.ps1 decrypt wgt\StreamX-Samsung.wgt.enc wgt\StreamX-Samsung.wgt
```

**Option locale** (si internet est mauvais), le paquet ne quitte pas le PC : décompresse
`StreamX-Samsung-unsigned.zip` dans `streamx`, puis

```powershell
tizen package -t wgt -s StreamX -o wgt -- streamx
```

## 5. Installer et lancer

```powershell
sdb connect <IP_TV>
tizen install -n StreamX-Samsung.wgt -t <CIBLE> -- wgt
tizen run -p StrmXTclX1.StreamX -t <CIBLE>
sdb shell 0 applist | findstr StrmXTclX1
```

`install failed [118, -12]` : DUID absent du certificat distributeur, ou mauvais profil.

Une installation n'est réussie que si `tizen install` le dit **et** que l'appli s'ouvre sur la TV.

## 6. Tests sur la TV

Note OK, KO ou non testé, puis reporte dans `AUDIT.md` : c'est là que les lignes « non testée sur
TV réelle » changent d'état. Note le modèle exact et la version de Tizen.

| Sujet | Essai | Résultat |
|---|---|---|
| Démarrage | Ouverture depuis la liste des applis, accueil sans erreur | |
| Télécommande | Flèches, OK, appui long OK, Retour, confirmation de sortie | |
| Télécommande | Lecture/pause, chiffres, couleurs, Info, Guide | |
| Direct | Chaîne HLS du catalogue, zapping, chaîne précédente (0) | |
| Direct | Flux MPEG-TS d'une playlist, si tu en as une | |
| Direct | Pause et retour au direct | |
| VOD | Film du domaine public, MP4 ; reprise après arrêt | |
| Pistes | Choix audio et qualité | |
| Sous-titres | Intégrés au flux, externes `.srt`/`.vtt`, taille et style | |
| Options | Format d'image, vitesse, minuterie, favoris dans le lecteur | |
| Veille | L'écran de veille ne se déclenche pas pendant 15 min de lecture | |
| Import | Playlist par URL, grande playlist (durée), réseau local | |
| Xtream | Connexion, fiche série, replay, si tu as un compte | |
| Guide | Maintenant / ensuite, grille | |
| USB | Mes fichiers avec une clé USB | |
| Durée | Redémarrage de la TV : l'appli et ses données sont toujours là | |
| Langues | Arabe, sens de lecture droite-gauche | |

## 7. Avant de partir

Le mode développeur reste lié à l'IP du PC. Si tes parents n'en ont pas l'usage, repasse-le sur
**Off** : StreamX peut alors cesser de s'ouvrir, à vérifier sur place avant de partir. La validité
d'une application installée en mode développeur n'est pas documentée ici : note ce que tu observes.
