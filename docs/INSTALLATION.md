# Installing Toque on the Monsieur Cuisine Connect from a Mac

🇫🇷 **[Version française](INSTALLATION.fr.md)**

> **With an AI agent** (Claude, ChatGPT…) that operates your Mac: give it this repository and
> ask it to follow [AGENTS.md](../AGENTS.md). The script
> [`tools/setup/toque-setup.sh`](../tools/setup/toque-setup.sh) automates the steps below; you can
> also run it yourself (`bash tools/setup/toque-setup.sh status` tells you where you are).

Time: 1 to 2 hours the first time. Steps 1 and 2 (root) are the tricky ones: a mistake can leave
the robot's tablet unusable. Read everything before you start.

This macOS version replaces SP Flash Tool and its Windows driver with
[mtkclient](https://github.com/bkerler/mtkclient), for the two operations that need the robot's
MediaTek download mode (the backup and the first TWRP write). The original procedure was done on a
real robot from Windows; the macOS one does the same reads and writes at the same addresses, but
has **not been tried on a robot yet**. If mtkclient cannot reach the robot, nothing is written: see
*If mtkclient does not reach the robot* below.

Menu names below are those of the English interface (*Settings › Langue · Language › English*).

## What you need

- A Mac with [Homebrew](https://brew.sh): `brew install python libusb` (Python 3.10 or newer), and
  git (`xcode-select --install`). No driver to install.
- A **USB-A male / USB-A male** data cable and a small Torx screwdriver. A USB-A ↔ USB-C cable
  does **not** work: the robot's port is a device port, so the Mac's USB-C port never detects it.
  On a Mac with only USB-C ports, use a **USB-C to USB-A adapter** (female USB-A) or a USB hub, then
  the A-A cable.
- The reference guide: <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (partition file `l706_dfbh_v_695scatter.txt` and TWRP recovery `hacks/recovery.img`).
- The official [Magisk](https://github.com/topjohnwu/Magisk/releases) APK (v31.0 tested; Android 6
  is supported).

`bash tools/setup/toque-setup.sh prepare` downloads all of this into `~/Toque` (adb, Magisk, TWRP,
the scatter file, the latest Toque APK, mtkclient), checks every SHA-256, and says if a Homebrew
package is missing. The commands below use those paths.

> **Switch the robot on with the cable unplugged.** If it is switched on with the cable plugged in,
> it stops on a battery (charging) screen. Plug the cable in once it has started. Download mode is
> the exception: there, the robot must be switched on *with* the cable plugged in.

> On an Apple silicon Mac, macOS may ask *"Allow accessory to connect?"* the first time it sees the
> robot. Click **Allow**; in download mode, then switch the robot off and on again.

## 1. Backup and TWRP

1. Robot off (rear switch). Unscrew the maintenance cover under the robot.
2. Connect the Mac **only to the right-hand port, labelled "Android USB"** (the other one can
   destroy the board).
3. `bash tools/setup/toque-setup.sh backup`, then switch the robot on when mtkclient says it is
   waiting for the device. mtkclient reads `boot` (start `0x1d20000`, length `0x1000000`, region
   `EMMC_USER`) and `recovery` (start `0x2d20000`, length `0x1000000`) into
   `~/Toque/backup/boot-original.img` and `recovery-original.img` (16 MB each); the script checks
   them. **Keep them safe, they are your only way back** (copy them to a USB stick or the cloud).
   By hand, from `~/Toque/mtkclient/run`, it is
   `DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib" ../venv/bin/python ../src/mtk.py multi "ro 0x1d20000 0x1000000 boot-original.img --parttype user;ro 0x2d20000 0x1000000 recovery-original.img --parttype user"`
   (the libusb path is needed on Apple silicon).
4. `bash tools/setup/toque-setup.sh flash-twrp`: robot off, cable in, switch it on when asked. It
   writes TWRP (`hacks/recovery.img`) into `boot` only and reads it back to check it.
5. Switch the robot off, unplug the cable, switch it on: **TWRP** starts. Plug the cable back in:
   `adb devices` shows the robot in `recovery` mode.

### If mtkclient does not reach the robot

- Check the cable, the port and the adapter first. Right after switching the robot on,
  `system_profiler SPUSBDataType` should list a **MT65xx Preloader** (vendor ID `0x0e8d`).
- If mtkclient sees the preloader but the handshake fails, add `--serial` to the step: mtkclient
  then goes through the macOS serial driver, like SP Flash Tool does on Windows.
- Otherwise, do the backup and the TWRP write on a Windows PC with the original guide
  ([Wuki60/MrCuisineVibeConnect](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/docs/INSTALLATION.md),
  SP Flash Tool), copy the two backups into `~/Toque/backup/`, run `backup` on the Mac (it only checks
  them), and continue below: everything from here on uses adb only.

## 2. ADB and root (Magisk)

1. With TWRP running: `bash tools/setup/toque-setup.sh root`. By hand, from the repository folder:
   ```
   adb push ~/Toque/downloads/Magisk-v31.0.apk /tmp/magisk.apk
   adb push ~/Toque/backup/boot-original.img /tmp/boot.img
   adb push ~/.android/adbkey.pub /tmp/adbkey.pub
   adb push tools/robot/twrp-root.sh /tmp/
   adb shell sh /tmp/twrp-root.sh
   adb pull /tmp/magisk/new-boot.img ~/Toque/boot-magisk.img
   ```
   [`twrp-root.sh`](../tools/robot/twrp-root.sh) patches your original `boot` with Magisk
   (`boot-magisk.img`), enables ADB in Android (`/system/build.prop`), deletes
   `/system/recovery-from-boot.p` (otherwise Android puts the stock recovery back over TWRP) and
   authorizes your Mac's ADB key (no prompt on the robot's screen).
2. Still in TWRP: `bash tools/setup/toque-setup.sh flash-root`. It writes TWRP into `recovery` and
   `boot-magisk.img` into `boot` from TWRP, with
   [`twrp-flash.sh`](../tools/robot/twrp-flash.sh): it first checks that `recovery` holds your
   original recovery (or TWRP) and `boot` holds TWRP (or the Magisk boot) and writes nothing
   otherwise, then reads each partition back. Do not switch the robot off while it runs.
3. Switch the robot off, unplug the cable, switch it on: the stock app starts. Plug the cable in.
4. `bash tools/setup/toque-setup.sh android`: it installs the Magisk app and runs `su`; tap
   **Grant** on the robot. By hand: `adb install ~/Toque/downloads/Magisk-v31.0.apk`, then
   `adb shell su -c id`, which must print `uid=0`. Toque uses the **busybox** shipped with Magisk
   (`/data/adb/magisk/busybox`).

To undo the root: `bash tools/setup/toque-setup.sh unroot` puts `boot-original.img` back into
`boot` and `recovery-original.img` into `recovery` (from TWRP; `--download-mode` if the robot no
longer starts). Go back to the stock app first (see the end of this guide).

Optional, so you no longer need the cable: enable ADB over Wi-Fi (`toque-setup.sh wifi` does it).

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

Only do this on your home network: anyone on it could then connect to the robot.

## 3. Install Toque

1. Download the latest `Toque-X.Y.Z.apk` from the
   [Releases page](https://github.com/Wuki60/MrCuisineVibeConnect/releases) (`prepare` already did),
   or build it (see the README). With a downloaded APK, add `--apk <path to the APK>` below.
2. `bash tools/install-robot.sh` (over Wi-Fi: add `--ip <robot IP address>`), or
   `bash tools/setup/toque-setup.sh install`.
3. The script also allows Toque in Magisk (no prompt on the robot: Magisk's prompt closes by
   itself after 10 s and then refuses for good) and checks that Magisk's busybox, which Toque
   needs for the serial port, is in place.

## 4. First run — EMPTY bowl

1. In Toque: **Settings › Machine › Real machine › Apply**. Toque turns off the stock app's motor service (reversible) and opens the
   serial port.
2. The badge at the top right must switch to **Ready**. If not: Settings › Tools › Motor base
   diagnostics (are frames received?) and Log.
3. Empty bowl, lid closed, **hand near the main switch**:
   - Manual cooking: 10 s, speed 2 → the motor runs, then stops on its own.
   - Open the lid during a run → immediate stop, "Paused: lid open".
   - 30 s at 37 °C, speed 1 → the displayed temperature rises.
   - Scale: put an object of known weight on it, Tare.
   - Diagnostics: remove the bowl and watch "Bowl detected". If it changes correctly, you can
     enable Settings › Safety › Require bowl detection.
4. At the slightest abnormal behaviour: **main switch**, then `bash tools/restore-robot.sh`.

## 5. Toque at startup

The robot has no Android Home or Back button. Toque opens by itself when the robot starts, and
whenever it opens another app (the stock app, the Android settings), a **Back to Toque** button
stays on top of it (you can drag it). Hidden access to the Android settings: tap the clock at the
top right 5 times quickly.

To open Toque from the Mac: `adb shell am start -n app.toque/.ui.MainActivity`.

## 6. Lidl recipes

**Settings › Lidl recipes › Sync** (the robot needs Wi-Fi). Toque downloads the official
Monsieur Cuisine connect recipes from monsieur-cuisine.com, then their photos: about 15 minutes the
first time, then only new or changed recipes. After that, the sync runs every night at 3:30 am.
Recipes are in the interface language (French by default).

Other source: **Import from the stock app** copies the recipes already in the stock app (they
replace the website's). If the stock app has none yet, **Open the stock app** lets it sync with
Lidl's servers; come back with the **Back to Toque** button. If that import fails, the file
`Android/data/app.toque/files/diagnostic-recettes.txt` describes the stock database.

## Going back to the stock app

- From Toque: Settings › Tools › Back to the stock app.
- From the Mac: `bash tools/restore-robot.sh --uninstall`

Toque changes neither the system nor the stock app: it only disables its motor service, which
both methods undo. Toque then no longer opens at startup.
