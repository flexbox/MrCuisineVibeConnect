# Installer Toque sur le Monsieur Cuisine Connect depuis un Mac

🇬🇧 **[English version](INSTALLATION.md)**

> **Avec un agent IA** (Claude, ChatGPT…) qui pilote votre Mac : donnez-lui ce dépôt et
> demandez-lui de suivre [AGENTS.md](../AGENTS.md). Le script
> [`tools/setup/toque-setup.sh`](../tools/setup/toque-setup.sh) automatise les étapes ci-dessous ;
> vous pouvez aussi le lancer vous-même (`bash tools/setup/toque-setup.sh status` dit où vous en êtes).

Durée : 1 à 2 heures la première fois. Les étapes 1 et 2 (root) sont les plus délicates : une
erreur peut rendre la tablette du robot inutilisable. Lisez tout avant de commencer.

Cette version macOS remplace SP Flash Tool et son pilote Windows par
[mtkclient](https://github.com/bkerler/mtkclient), pour les deux opérations qui demandent le mode
téléchargement MediaTek du robot (la sauvegarde et la première écriture de TWRP). La procédure
d'origine a été faite sur un vrai robot depuis Windows ; celle pour macOS fait les mêmes lectures
et écritures aux mêmes adresses, mais **n'a pas encore été essayée sur un robot**. Si mtkclient ne
joint pas le robot, rien n'est écrit : voir *Si mtkclient ne joint pas le robot* plus bas.

## Ce qu'il faut

- Un Mac avec [Homebrew](https://brew.sh) : `brew install python libusb` (Python 3.10 ou plus
  récent), et git (`xcode-select --install`). Aucun pilote à installer.
- Un câble de données **USB-A mâle / USB-A mâle** et un petit tournevis Torx. Un câble USB-A ↔ USB-C
  ne marche **pas** : le port du robot est un port « appareil », le port USB-C du Mac ne le détecte
  jamais. Sur un Mac qui n'a que des ports USB-C, mettre un **adaptateur USB-C vers USB-A**
  (USB-A femelle) ou un hub USB, puis le câble A-A.
- Le guide de référence : <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (fichier de partition `l706_dfbh_v_695scatter.txt` et recovery TWRP `hacks/recovery.img`).
- L'APK officiel de [Magisk](https://github.com/topjohnwu/Magisk/releases) (v31.0 testée ; Android 6
  est pris en charge).

`bash tools/setup/toque-setup.sh prepare` télécharge tout cela dans `~/Toque` (adb, Magisk, TWRP,
le fichier scatter, le dernier APK de Toque, mtkclient), vérifie chaque SHA-256 et signale un paquet
Homebrew manquant. Les commandes ci-dessous utilisent ces chemins.

> **Allumer le robot câble débranché.** Allumé avec le câble branché, il reste bloqué sur un écran
> de batterie (charge). Brancher le câble une fois qu'il a démarré. Seule exception : le mode
> téléchargement, où il faut au contraire allumer le robot *avec* le câble branché.

> Sur un Mac Apple silicon, macOS peut demander d'autoriser l'accessoire la première fois qu'il voit
> le robot. Cliquer sur **Autoriser** ; en mode téléchargement, éteindre puis rallumer le robot.

## 1. Sauvegarde et TWRP

1. Robot éteint (interrupteur arrière). Dévisser la trappe de maintenance sous le robot.
2. Brancher le Mac **uniquement sur le port de droite, marqué « Android USB »** (l'autre peut
   détruire la carte).
3. `bash tools/setup/toque-setup.sh backup`, puis allumer le robot quand mtkclient dit qu'il attend
   l'appareil. mtkclient lit `boot` (début `0x1d20000`, longueur `0x1000000`, région `EMMC_USER`) et
   `recovery` (début `0x2d20000`, longueur `0x1000000`) dans `~/Toque/backup/boot-original.img` et
   `recovery-original.img` (16 Mo chacun) ; le script les vérifie. **À garder précieusement, c'est
   votre seule voie de retour** (en faire une copie sur une clé USB ou dans le cloud).
   À la main, depuis `~/Toque/mtkclient/run` :
   `DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib" ../venv/bin/python ../src/mtk.py multi "ro 0x1d20000 0x1000000 boot-original.img --parttype user;ro 0x2d20000 0x1000000 recovery-original.img --parttype user"`
   (le chemin de libusb est nécessaire sur Apple silicon).
4. `bash tools/setup/toque-setup.sh flash-twrp` : robot éteint, câble branché, l'allumer quand c'est
   demandé. Il écrit TWRP (`hacks/recovery.img`) dans `boot` uniquement et le relit pour vérifier.
5. Éteindre le robot, débrancher le câble, rallumer : **TWRP** démarre. Rebrancher le câble :
   `adb devices` affiche le robot en mode `recovery`.

### Si mtkclient ne joint pas le robot

- Vérifier d'abord le câble, le port et l'adaptateur. Juste après avoir allumé le robot,
  `system_profiler SPUSBDataType` doit afficher un **MT65xx Preloader** (identifiant fabricant `0x0e8d`).
- Si mtkclient voit le preloader mais que l'échange initial échoue, ajouter `--serial` à l'étape :
  mtkclient passe alors par le pilote série de macOS, comme SP Flash Tool sous Windows.
- Sinon, faire la sauvegarde et l'écriture de TWRP sur un PC Windows avec le guide d'origine
  ([Wuki60/MrCuisineVibeConnect](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/docs/INSTALLATION.fr.md),
  SP Flash Tool), copier les deux sauvegardes dans `~/Toque/backup/`, lancer `backup` sur le Mac (il
  ne fait que les vérifier) et continuer ci-dessous : tout le reste passe uniquement par adb.

## 2. ADB et root (Magisk)

1. TWRP affiché : `bash tools/setup/toque-setup.sh root`. À la main, depuis le dossier du dépôt :
   ```
   adb push ~/Toque/downloads/Magisk-v31.0.apk /tmp/magisk.apk
   adb push ~/Toque/backup/boot-original.img /tmp/boot.img
   adb push ~/.android/adbkey.pub /tmp/adbkey.pub
   adb push tools/robot/twrp-root.sh /tmp/
   adb shell sh /tmp/twrp-root.sh
   adb pull /tmp/magisk/new-boot.img ~/Toque/boot-magisk.img
   ```
   [`twrp-root.sh`](../tools/robot/twrp-root.sh) patche votre `boot` d'origine avec Magisk
   (`boot-magisk.img`), active ADB dans Android (`/system/build.prop`), supprime
   `/system/recovery-from-boot.p` (sinon Android remet le recovery d'origine par-dessus TWRP) et
   autorise la clé ADB de votre Mac (aucune confirmation à faire sur l'écran du robot).
2. Toujours dans TWRP : `bash tools/setup/toque-setup.sh flash-root`. Il écrit TWRP dans `recovery`
   et `boot-magisk.img` dans `boot` depuis TWRP, avec
   [`twrp-flash.sh`](../tools/robot/twrp-flash.sh) : il vérifie d'abord que `recovery` contient
   votre recovery d'origine (ou TWRP) et `boot` contient TWRP (ou le boot Magisk), n'écrit rien
   sinon, puis relit chaque partition. Ne pas éteindre le robot pendant ce temps.
3. Éteindre le robot, débrancher le câble, rallumer : l'appli d'origine démarre. Brancher le câble.
4. `bash tools/setup/toque-setup.sh android` : il installe l'appli Magisk et lance `su` ; appuyer
   sur **Autoriser** sur le robot. À la main : `adb install ~/Toque/downloads/Magisk-v31.0.apk`, puis
   `adb shell su -c id`, qui doit afficher `uid=0`. Toque utilise la **busybox** fournie par Magisk
   (`/data/adb/magisk/busybox`).

Pour retirer le root : `bash tools/setup/toque-setup.sh unroot` remet `boot-original.img` dans
`boot` et `recovery-original.img` dans `recovery` (depuis TWRP ; `--download-mode` si le robot ne
démarre plus). Revenir d'abord à l'appli d'origine (voir la fin de ce guide).

Facultatif, pour ne plus avoir besoin du câble : activer ADB par Wi-Fi (`toque-setup.sh wifi` le fait).

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

À faire uniquement sur le réseau de la maison : n'importe quel appareil connecté dessus
pourrait alors accéder au robot.

## 3. Installer Toque

1. Télécharger le dernier `Toque-X.Y.Z.apk` sur la
   [page des versions](https://github.com/Wuki60/MrCuisineVibeConnect/releases) (`prepare` l'a déjà
   fait), ou le compiler (voir README). Avec un APK téléchargé, ajouter `--apk <chemin de l'APK>`
   ci-dessous.
2. `bash tools/install-robot.sh` (par Wi-Fi : ajouter `--ip <adresse IP du robot>`), ou
   `bash tools/setup/toque-setup.sh install`.
3. Le script autorise aussi Toque dans Magisk (aucune fenêtre sur le robot : celle de Magisk se
   ferme seule au bout de 10 s puis refuse définitivement) et vérifie que la busybox de Magisk,
   dont Toque a besoin pour le port série, est en place.

## 4. Premier essai — bol VIDE

1. Dans Toque : **Réglages › Machine › Machine réelle › Appliquer**. Toque coupe l'appli
   d'origine du moteur (réversible) et ouvre le port série.
2. La pastille en haut à droite doit passer à **« Prête »**. Sinon : Réglages › Outils ›
   Diagnostic (trames reçues ?) et Journal.
3. Bol vide, couvercle fermé, **main près de l'interrupteur principal** :
   - Cuisson manuelle : 10 s, vitesse 2 → le moteur tourne puis s'arrête seul.
   - Ouvrir le couvercle pendant un essai → arrêt immédiat, « Pause : couvercle ouvert ».
   - 30 s à 37 °C, vitesse 1 → la température affichée monte.
   - Balance : poser un objet de poids connu, Tare.
   - Diagnostic : retirer le bol et regarder « Bol détecté ». S'il change bien, vous pouvez
     activer Réglages › Sécurité › « Exiger la détection du bol ».
4. Au moindre comportement anormal : **interrupteur principal**, puis
   `bash tools/restore-robot.sh`.

## 5. Toque au démarrage

Le robot n'a ni bouton Accueil ni bouton Retour Android. Toque s'ouvre tout seul au démarrage du
robot, et quand il ouvre une autre appli (l'appli d'origine, les paramètres Android), un bouton
**« Retour à Toque »** reste affiché par-dessus (on peut le déplacer). Accès caché aux paramètres
Android : 5 appuis rapides sur l'heure en haut à droite.

Pour ouvrir Toque depuis le Mac : `adb shell am start -n app.toque/.ui.MainActivity`.

## 6. Recettes Lidl

**Réglages › Recettes Lidl › Synchroniser** (le robot doit être en Wi-Fi). Toque télécharge les
recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com, puis leurs photos :
environ 15 minutes la première fois, ensuite seulement les recettes nouvelles ou modifiées. La
synchro se refait chaque nuit à 3 h 30. Les recettes sont dans la langue de l'interface (le
français par défaut).

Autre source : **« Importer de l'appli d'origine »** copie les recettes déjà présentes dans l'appli
d'origine (elles remplacent celles du site). Si elle n'en a pas encore, **« Ouvrir l'appli
d'origine »** la laisse se synchroniser avec les serveurs Lidl ; revenez avec le bouton **« Retour
à Toque »**. Si cet import échoue, le fichier `Android/data/app.toque/files/diagnostic-recettes.txt`
décrit la base d'origine.

## Revenir à l'appli d'origine

- Depuis Toque : Réglages › Outils › « Revenir à l'appli d'origine ».
- Depuis le Mac : `bash tools/restore-robot.sh --uninstall`

Toque ne modifie ni le système ni l'appli d'origine : il désactive seulement son service moteur,
ce que ces deux méthodes annulent. Toque ne s'ouvre alors plus au démarrage.
