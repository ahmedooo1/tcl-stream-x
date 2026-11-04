# Installer StreamX LG (webOS)

1. Sur la TV, installe l'appli **Developer Mode** depuis le LG Content Store, connecte-toi avec un
   compte <https://webostv.developer.lge.com>, active **Dev Mode Status** puis **Key Server**, et
   redémarre la TV. La session dure 50 h et se prolonge depuis l'appli (bouton *Extend*).
2. Sur l'ordinateur : `npm install -g @webos-tools/cli`.
3. `ares-setup-device` → *add* → nom `tv`, IP de la TV, port 9922, utilisateur `prisoner`.
4. `ares-novacom --device tv --getkey` avec la *passphrase* affichée dans l'appli Developer Mode.
5. Télécharge `StreamX-LG.ipk` depuis la dernière pré-release **tv-build-N**
   (<https://github.com/ahmedooo1/tcl-stream-x/releases>, sans compte), puis :

```bash
ares-install --device tv StreamX-LG.ipk
ares-launch --device tv fr.ahmad.streamx
```

Mise à jour : l'accueil annonce un build plus récent (vérification toutes les 30 minutes) ;
`ares-install` avec le nouvel `.ipk` (les données sont conservées).
Débogage : `ares-inspect --device tv --app fr.ahmad.streamx --open`.
