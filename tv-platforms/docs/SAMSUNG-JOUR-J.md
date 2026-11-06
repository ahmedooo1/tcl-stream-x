# StreamX Samsung : installation à distance, TV chez tes parents, PC chez toi

Le PC fixe reste allumé chez toi avec Claude Code. Sur place, tu n'as que ton téléphone Android.
La procédure locale reste dans `INSTALL-SAMSUNG.md`.

```text
PC (Tizen Studio, sdb) ── tunnel Tailscale chiffré ── téléphone (socat) ── réseau local ── TV
```

Le téléphone sert de relais : Tailscale ne peut pas s'installer sur une TV Samsung, et la TV
n'accepte `sdb` que depuis l'IP déclarée en mode développeur. Elle voit la connexion venir du
téléphone : **c'est donc l'IP Wi-Fi du téléphone qu'on déclare sur la TV**, pas celle du PC.

Aucun port n'est ouvert sur internet ni sur la box. `socat` n'écoute que sur l'adresse Tailscale
du téléphone et n'accepte que l'adresse Tailscale du PC. **Ne jamais faire de redirection de port
sur la box vers 26101** : `sdb` n'est pas chiffré.

Limite connue : cette méthode découle du fonctionnement de `sdb`, mais aucune source ne la
documente sur une TV Samsung. Le premier `sdb connect` sera le vrai test.

## Avant de partir (sur le PC)

- Veille et hibernation sur secteur désactivées.
- Terminal Claude Code ouvert avec `/remote-control`, session visible dans l'appli Claude.
- Chrome Remote Desktop testé depuis le téléphone, y compris le déverrouillage de Windows.
- Mises à jour Windows suspendues, pour éviter un redémarrage.
- Certificat auteur créé, mots de passe dans le Gestionnaire d'identification.

## Sur le téléphone

Installe **Termux** depuis F-Droid (pas la version du Play Store), puis :

```sh
pkg update && pkg install -y socat
```

Répétition à la maison, téléphone sur le Wi-Fi et Tailscale actif :

```sh
socat TCP-LISTEN:26101,bind=<IP_TAILSCALE_TEL>,range=<IP_TAILSCALE_PC>/32,reuseaddr,fork TCP:192.168.1.1:80
```

Depuis le PC, `curl.exe http://<IP_TAILSCALE_TEL>:26101` doit répondre avec la page de la box.

Réglages Android : batterie de Termux et de Tailscale **sans restriction**, écran allumé, chargeur
branché. Dans Termux, lance `termux-wake-lock`.

## Chez tes parents

1. Mets le téléphone sur le **même Wi-Fi que la TV**, Tailscale actif. Si le Wi-Fi isole les
   appareils (réseau invité), active plutôt le **point d'accès** du téléphone et connecte la TV
   dessus ; Tailscale passe alors par la 4G/5G.
2. Relève l'IP du téléphone : Paramètres → À propos → État → Adresse IP (point d'accès : `ifconfig`
   dans Termux).
3. Sur la TV : Applications → **1 2 3 4 5** → **Developer mode : On** → saisis **l'IP du téléphone**
   → redémarre la TV (débranche-la 30 s si besoin).
4. Relève l'IP de la TV : Paramètres → Général → Réseau → État du réseau → Paramètres IP.
5. Lance le relais et laisse Termux ouvert :

   ```sh
   socat -d -d TCP-LISTEN:26101,bind=<IP_TAILSCALE_TEL>,range=<IP_TAILSCALE_PC>/32,reuseaddr,fork TCP:<IP_TV>:26101
   ```

6. Donne l'IP de la TV à Claude Code.

## Depuis le PC, à distance

```powershell
$env:Path += ';C:\tizen-studio\tools;C:\tizen-studio\tools\ide\bin'
sdb connect <IP_TAILSCALE_TEL>:26101
sdb devices
sdb shell 0 getduid
```

La connexion s'ouvre puis se ferme aussitôt : la TV ne reconnaît pas l'IP déclarée. Vérifie que
c'est bien celle du téléphone, puis redémarre la TV.

## Certificat distributeur

Le navigateur de connexion Samsung s'ouvre sur le PC : utilise **Chrome Remote Desktop** depuis le
téléphone. Dans Tools → Certificate Manager, garde le certificat auteur existant :

1. Privilège **Public**.
2. Coche le **DUID de la TV**, ou saisis celui lu par `getduid`.
3. Mot de passe : `pwsh -STA tools/tizen-secrets.ps1 clip TizenDistributorCert 180`, puis colle-le.
4. Connexion au compte Samsung.

## Signer et installer

Depuis `tv-platforms`, sur le PC :

```powershell
pwsh tools/tizen-secrets.ps1 gh-set ahmedooo1/tcl-stream-x "$HOME\SamsungCertificate\StreamX"
gh workflow run "StreamX TV (Samsung, LG)" --repo ahmedooo1/tcl-stream-x --ref multiplatform-tv
gh run watch --repo ahmedooo1/tcl-stream-x
gh run download <ID_DU_RUN> --repo ahmedooo1/tcl-stream-x -n StreamX-Samsung-signed -D wgt
pwsh tools/tizen-secrets.ps1 decrypt wgt\StreamX-Samsung.wgt.enc wgt\StreamX-Samsung.wgt
tizen install -n StreamX-Samsung.wgt -t <CIBLE> -- wgt
tizen run -p StrmXTclX1.StreamX -t <CIBLE>
sdb shell 0 applist | findstr StrmXTclX1
```

- Installation refusée pour permission : `tizen install-permit -t <CIBLE>`, puis recommence.
- `install failed [118, -12]` : DUID absent du certificat distributeur, ou mauvais profil.

Une installation n'est réussie que si `tizen install` le dit **et** que l'appli s'ouvre sur la TV.

## Tests sur la TV

Note OK, KO ou non testé, puis reporte dans `AUDIT.md`. Note le modèle exact et la version de Tizen.

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

## Avant de repartir

Coupe `socat` (Ctrl+C dans Termux). Vérifie si StreamX s'ouvre encore après être repassé en
**Developer mode : Off** : ce comportement n'est pas documenté ici, note ce que tu observes.
