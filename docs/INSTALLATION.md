# Install Toque on the Monsieur Cuisine Connect from a Mac

🇫🇷 **[Version française](INSTALLATION.fr.md)**

This guide starts from zero: no programming needed. You need a Mac, a cable, a screwdriver and
1 to 2 hours (mostly waiting). **Read it once from start to end before you begin.**

On a **Linux PC**, the same script works: read [part 8](#8-from-a-linux-pc) first, it replaces
part 2. On a Windows PC, this guide does not apply: follow the one in the
[original repository](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/docs/INSTALLATION.md).

## Contents

1. [Before you start](#1-before-you-start): the robot, the hardware, the risks, the 3 rules
2. [Prepare the Mac](#2-prepare-the-mac-once) (once, 15 minutes)
3. [Install with Claude](#3a-install-with-claude-recommended) (recommended) **or**
   [install it yourself](#3b-install-it-yourself-step-by-step), step by step
4. [After the install](#4-after-the-install): first run, recipes, Wi-Fi
5. [If something goes wrong](#5-if-something-goes-wrong) · [Undo](#6-undo) ·
   [Technical details](#7-technical-details)
6. [From a Linux PC](#8-from-a-linux-pc): what differs

## 1. Before you start

### Your robot

- **Monsieur Cuisine Connect**, model **SKMC 1200** (written on the robot's label).
- A **Monsieur Cuisine Smart** (SKMS 1200) or any other model: **stop here**, this guide does not
  apply.

### Hardware

| | You need | Note |
|---|---|---|
| ☐ | A **Mac** (Apple silicon or Intel), connected to the Internet | You will be asked for the Mac's login password |
| ☐ | A **USB-A male ↔ USB-A male** data cable | Both ends are the large rectangular USB plugs. A USB-C ↔ USB-A cable does **not** work |
| ☐ | If the Mac has only USB-C ports: a **USB-C → USB-A adapter** (female) or a hub | The A-A cable plugs into it |
| ☐ | A **small Torx screwdriver** | For the maintenance cover under the robot |
| ☐ | The home **Wi-Fi password** | For the end |
| ☐ | The robot **near the Mac**, plugged into the mains, **bowl empty** | It stays plugged into the mains from start to end |

### Risks

- The robot's warranty is void.
- A mistake can leave the robot's tablet unusable. The script checks everything before it writes
  anything, and the **backup** made at the start is your way back: never skip it, keep a copy.
- Toque runs every day on two robots: one installed from Windows (October 2026), one from a Mac
  (November 2026). That is not many: at your own risk.

### The 3 rules to remember

Under the robot, behind the cover, there are two USB ports:

<img src="https://raw.githubusercontent.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/master/informations/usbports.jpg" alt="The two USB ports under the cover: 'Power PCB USB' on the left, 'Android USB' on the right" width="360">

1. **Always the right-hand port, marked "Android USB".** Never the one marked "Power PCB USB": it
   can destroy the robot's board.
2. **Switch the robot on with the cable unplugged**, then plug the cable in once the screen is on.
   Switched on with the cable in, it stops on a battery screen: switch off, unplug, switch on, plug
   in. **Only exception**: the `backup` and `flash-twrp` steps, where you plug the cable in with the
   robot off, then switch it on when the Mac asks.
3. **Never switch the robot off or unplug the cable while a step is running.** Wait for the
   `OK: ... done` line.

The robot's power switch is at the back.

## 2. Prepare the Mac (once)

Everything happens in **Terminal**: open it with `Cmd + Space`, type `Terminal`, `Return`.
For each command below: copy it, paste it into Terminal (`Cmd + V`), `Return`, and wait for it to
finish (the cursor comes back).

1. **Install Homebrew** (the tool that installs the rest):
   ```
   /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
   ```
   It asks for the Mac's password: type it, **nothing shows while you type, that is normal**, then
   `Return`. It also installs Apple's command line tools (git among them): 5 to 15 minutes.
   At the end, if it shows **Next steps** with commands to run, copy and run them, then **close
   Terminal and open it again**.
2. **Install Python and libusb**:
   ```
   brew install python libusb
   ```
3. **Download this repository** into a `MrCuisineVibeConnect` folder in your home folder:
   ```
   git clone https://github.com/flexbox/MrCuisineVibeConnect.git ~/MrCuisineVibeConnect
   ```
4. **Go into that folder**:
   ```
   cd ~/MrCuisineVibeConnect
   ```
   Each time you open Terminal again, type this command before the others.

Then choose: **3a** (Claude runs the commands for you) or **3b** (you type them). Either way, the
robot only changes at the steps that say so.

## 3a. Install with Claude (recommended)

[Claude Code](https://code.claude.com/docs/en/quickstart) is an AI assistant that types the
commands in Terminal, reads the results and tells you what to do with your hands. It needs a paid
Claude plan (Pro or higher): the free plan is not enough.

1. Install it:
   ```
   curl -fsSL https://claude.ai/install.sh | bash
   ```
   then close Terminal and open it again. `claude --version` must show a version number.
2. Start it in the repository folder:
   ```
   cd ~/MrCuisineVibeConnect
   claude
   ```
   The first time, it opens the browser to sign in to your Claude account.
3. Paste this message:
   > Install Toque on my Monsieur Cuisine Connect by following AGENTS.md.

Then:

- Claude explains the risks and asks for a **yes** before it starts.
- Before each command, Claude Code asks your permission: read it, then accept.
- Before each step that writes to the robot, it says what the step does and waits for your **yes**.
- When something needs your hands (cover, cable, power switch, a tap on the robot's screen), it
  tells you: do it, then answer "done".

Keep this guide open next to it: part 3b shows what to expect at each step.

## 3b. Install it yourself, step by step

One script does everything: `tools/setup/toque-setup.sh`. Each step runs with
`bash tools/setup/toque-setup.sh <step>`, from the repository folder. What it prints:

- `OK:` done and verified;
- `HUMAN:` something for you to do (cable, power switch, robot's screen). Do it; if the step
  stopped, run the same command again;
- `STOP:` a problem, **nothing irreversible was done**. The line says what to do; see also
  [If something goes wrong](#5-if-something-goes-wrong).

Lost? This command says what is done and what the next step is:

```
bash tools/setup/toque-setup.sh status
```

Every step is safe to run again.

### Step 1 · Download the tools — `prepare`

The robot is not involved.

```
bash tools/setup/toque-setup.sh prepare
```

It downloads into the `~/Toque` folder: adb, Magisk, TWRP, the latest Toque and mtkclient, and
checks every file. If it prints `MISSING:`, install what it says, then run it again.

✅ At the end: `OK: prepare done. Next: backup`

### Step 2 · Back up the robot — `backup`

The robot is only read, nothing is written.

1. **Switch the robot off** (switch at the back).
2. Take the bowl out and unscrew the **maintenance cover** under the robot.
3. Plug the A-A cable into the right-hand **"Android USB"** port and into the Mac.
4. Run:
   ```
   bash tools/setup/toque-setup.sh backup
   ```
5. When Terminal shows *Waiting for PreLoader VCOM* (then *Handshake failed, retrying...* every 10
   seconds, that is normal), **switch the robot on**, once. The robot's screen stays black: that is
   normal too.
6. If the Mac asks to **allow the accessory** to connect: click **Allow**, then switch the robot
   off and on again while the step is waiting.

✅ At the end: `OK: backup done. Next: flash-twrp`

The `~/Toque/backup` folder now holds your two backups. **Copy it to a USB stick or the cloud**:
it is your only way back. Then **switch the robot off** (black screen: it stayed in download mode).

### Step 3 · Put TWRP on the robot — `flash-twrp`

Writes to the robot: TWRP, a maintenance system, temporarily replaces the normal start-up.

1. Robot **off**, cable **plugged in**.
2. Run:
   ```
   bash tools/setup/toque-setup.sh flash-twrp
   ```
3. As for the backup: switch the robot on when Terminal waits for the device.

✅ At the end: `OK: flash-twrp done`

4. **Switch** the robot **off**, **unplug** the cable, **switch it on**: the **TWRP** screen
   appears. The first time, TWRP asks *Keep System Read only?*: tap **Keep Read Only** (do not
   swipe). **Plug** the cable back in.

### Step 4 · Prepare root — `root`

Robot on the TWRP screen, cable plugged in.

```
bash tools/setup/toque-setup.sh root
```

From your backup, it prepares the "rooted" version of the robot's start-up (with
[Magisk](https://github.com/topjohnwu/Magisk)), and enables the connection with the Mac in Android.

✅ At the end: `OK: Magisk boot image ready ... Next: flash-root`

### Step 5 · Write root — `flash-root`

Writes to the robot. Still on TWRP, cable plugged in.

```
bash tools/setup/toque-setup.sh flash-root
```

**Do not touch the robot** until it ends. The script first checks what is on the robot (and writes
nothing if it does not recognise it), writes, then reads back to verify.

✅ At the end: `OK: flash-root done. Next: android`

Then **switch** the robot **off**, **unplug** the cable, **switch it on**: the usual Monsieur
Cuisine app starts. **Plug** the cable back in.

### Step 6 · Check root — `android`

```
bash tools/setup/toque-setup.sh android
```

- If the robot asks to **allow USB debugging**: tick *Always allow*, tap **OK**, run the step
  again.
- When Magisk asks for superuser access for *Shell*: tap **Grant** on the robot's screen **within
  10 seconds**. Missed it? Run the step again.

✅ At the end: `OK: Root works (uid 0). Next: install`

### Step 7 · Install Toque — `install`

```
bash tools/setup/toque-setup.sh install
```

✅ At the end: `OK: Toque installed` and Toque opens on the robot. Do the first run
([part 4](#4-after-the-install)), then come back to step 8.

### Step 8 · No more cable — `wifi`

The robot must be on the home Wi-Fi. If it is not: in Toque, tap **the clock 5 times quickly** at
the top right (Android settings), join the Wi-Fi, come back with the **"Back to Toque"** button.

```
bash tools/setup/toque-setup.sh wifi
```

✅ At the end: `OK: ADB over Wi-Fi: <IP address>:5555`. Unplug the cable and **screw the cover back
on**. Done.

## 4. After the install

### First run — EMPTY bowl

1. In Toque: **Settings › Machine › Real machine › Apply**. Toque turns off the stock app's motor
   service (reversible) and opens the serial port.
2. The badge at the top right must turn to **"Ready"**. If not: Settings › Tools › Motor base
   diagnostics (frames received?) and Log.
3. Empty bowl, lid closed, **hand near the power switch**:
   - Manual cooking: 10 s, speed 2 → the motor turns, then stops by itself.
   - Open the lid during a test → immediate stop, "Paused: lid open".
   - 30 s at 37 °C, speed 1 → a temperature appears at the top and rises (cold, the base gives
     none and Toque shows nothing rather than 0 °C).
   - Scale: put an object of known weight on it, Tare.
   - Take the bowl off: the top bar must say **"Bowl removed"**, and Settings › Safety "Sensor:
     bowl not detected" (and "bowl detected" with the bowl back, lid open or closed). If so, you
     can turn on "Require bowl detection". Checked on an SKMC 1200 whose base reports version
     4922; on another base, look before trusting it.
4. At the slightest odd behaviour: **power switch**, then go back to the stock app
   ([part 6](#6-undo)).

### Lidl recipes

**Settings › Lidl recipes › Sync** (robot on Wi-Fi). Toque downloads the official recipes from
monsieur-cuisine.com, without an account: about 15 minutes the first time, then only the new ones,
every night at 3:30. They are in the interface language.

Other source: **"Import from the stock app"** copies the recipes already in the stock app (they
replace the ones from the site). If it has none yet, **"Open the stock app"** lets it sync with
Lidl's servers; come back with the **"Back to Toque"** button. If that import fails, the file
`Android/data/app.toque/files/diagnostic-recettes.txt` describes the stock database.

### Cookomix recipes

**Settings › Cookomix recipes › Sync Cookomix** (robot on Wi-Fi). Toque reads the cookomix.com
recipes, written for the Thermomix, and keeps the ones the MCC can make, with their settings
converted: an hour or more the first time, then only new or changed ones. They are tagged
"Cookomix" (and "Adaptée MCC" when a setting had to be adapted). In **Recipes**, the
**All / Lidl / Cookomix** tabs keep the two sources apart and **"Categories…"** lists every
category. Error codes CKX-01 to CKX-07: [COOKOMIX-ERREURS.md](COOKOMIX-ERREURS.md) (in French).

### Every day

- Toque opens by itself when the robot starts.
- The robot has no Home or Back button: when another app is open (the stock app, the Android
  settings), the **"Back to Toque"** button stays on top (you can move it).
- Android settings: tap the clock at the top right 5 times quickly.
- Updates: **Settings › Updates** installs new versions.
- From the Mac, over Wi-Fi: `~/Toque/platform-tools/adb connect <robot IP address>:5555`, then
  `~/Toque/platform-tools/adb shell am start -n app.toque/.ui.MainActivity` opens Toque.

## 5. If something goes wrong

| What you see | Why | What to do |
|---|---|---|
| The robot shows a **battery** instead of starting | Switched on with the cable plugged in | Switch off, unplug, switch on, plug in once the screen is on |
| `backup` / `flash-twrp` wait forever (*Waiting for device*) | Wrong cable, wrong port, USB-C port without adapter, or robot already on | A-A cable, "Android USB" port, USB-C → USB-A adapter. Robot off, run the step again, switch on when asked |
| *Handshake failed, retrying...* every 10 s | Normal: the Mac is waiting for the robot | Switch the robot on, once |
| *Sync error* or *Failed to upload da* | The robot was already in download mode, or `prepare` not done | `prepare`, switch the robot off, run the step again |
| Robot's screen black after `backup` or `flash-twrp` | It stays in download mode | Switch it off |
| The Mac asks to **allow the accessory** | Apple silicon Mac security | **Allow**, then switch the robot off and on again |
| `MISSING: Python` or `MISSING: libusb` | Missing packages | `brew install python libusb`. `brew` not found: redo Homebrew's *Next steps* ([part 2](#2-prepare-the-mac-once)) |
| The Mac does not see the robot in TWRP | Cable plugged in before TWRP was on screen | Unplug, plug back in |
| Battery screen between `root` and `flash-root` | The robot restarted with the cable in | Switch off, unplug, switch on: TWRP comes back. Plug in, continue |
| `flash-root`: *write failed* or *read-back differs* | Write error | **Do not switch the robot off** (TWRP is still running). Run `flash-root` again |
| `flash-root`: *holds something unexpected* | Unexpected content on the robot | Nothing was written. Do not continue: `status`, and ask for help |
| `android`: the **Grant** tap was missed | Magisk's prompt closes after 10 s | Run `android` again and tap quickly. Refused for good: Magisk app › Superuser › Shell › allow |
| adb: *more than one device/emulator* | After `wifi`, with the cable still plugged in, adb sees the robot twice | Unplug the cable, or run `adb disconnect <robot IP address>:5555` |
| Toque shows "No base" (no motor link) | Magisk's busybox missing | Run `install` again |
| Magisk messages saying Toque was denied superuser rights | Magisk setting | Run `install` again |
| Stuck in the stock app | Normal, no Back button on this robot | **"Back to Toque"** button |

Still stuck? Send `~/Toque/setup-log.txt` (and `~/Toque/mtk-<step>-out.txt` for `backup` or
`flash-twrp`) to whoever helps you, or open an
[issue](https://github.com/flexbox/MrCuisineVibeConnect/issues).

### If the Mac never reaches the robot in download mode

Nothing is written as long as the Mac cannot reach the robot. In order:

1. Right after switching the robot on, `system_profiler SPUSBDataType` must list a
   **MT65xx Preloader** (vendor ID `0x0e8d`). If not, it is the cable, the port or the adapter.
2. If the robot is seen but the step stops on *Sync error*: `status` must show
   *mtkclient: v2.1.4.1 + macOS serial fixes* (else run `prepare`). Switch the robot off and run the
   step again. `--libusb` tries another way to connect (it never worked on the robot it was tried on).
3. Otherwise, do **only** `backup` and `flash-twrp` on a Windows PC with the
   [original guide](https://github.com/Wuki60/MrCuisineVibeConnect/blob/main/docs/INSTALLATION.md)
   (SP Flash Tool), copy the two backups into `~/Toque/backup/` on the Mac, run `backup` (it then
   only checks them) and continue with `root` on the Mac: everything else goes through adb.

## 6. Undo

- **Back to the stock app** (keeping root): in Toque, Settings › Tools › "Back to the stock app",
  or from the Mac `bash tools/restore-robot.sh --uninstall`. Toque changes neither the system nor
  the stock app: it only disables the stock motor service, which both methods undo.
- **Also remove root and TWRP** (after the line above): `bash tools/setup/toque-setup.sh unroot`
  puts your backups `boot-original.img` and `recovery-original.img` back on the robot, from TWRP.
  If the robot no longer starts at all: `unroot --download-mode` (robot off, cable in, like
  `backup`). What stays on the robot afterwards: adb remains enabled (USB, and port 5555 if you ran
  `wifi`), without root, and the Magisk app remains installed, without effect.

## 7. Technical details

For the curious, or to do it all without the script. Files are in `~/Toque` (work folder, change
with `--work`), the log in `~/Toque/setup-log.txt`.

**What is downloaded** (and checked by SHA-256): adb (Google), the official
[Magisk](https://github.com/topjohnwu/Magisk/releases) v31.0 APK, the TWRP recovery
`hacks/recovery.img` and the partition file `l706_dfbh_v_695scatter.txt` from the
[reference guide](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack), the latest Toque
APK, and [mtkclient](https://github.com/bkerler/mtkclient) v2.1.4.1. mtkclient replaces SP Flash
Tool and its Windows driver for the two operations that need the robot's MediaTek download mode
(the backup and the first TWRP write). mtkclient needed three fixes for the macOS serial driver:
[tools/setup/mtkclient-macos-serial.patch](../tools/setup/mtkclient-macos-serial.patch), applied by
`prepare`.

**`backup`** reads `boot` (start `0x1d20000`, length `0x1000000`, region `EMMC_USER`) and
`recovery` (start `0x2d20000`, length `0x1000000`) into `~/Toque/backup/boot-original.img` and
`recovery-original.img` (16 MiB each), and checks they are Android boot images. By hand, from
`~/Toque/mtkclient/run`:

```
DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib" ../venv/bin/python ../src/mtk.py --serialport DETECT multi "ro 0x1d20000 0x1000000 boot-original.img --parttype user;ro 0x2d20000 0x1000000 recovery-original.img --parttype user"
```

(the libusb path is needed on Apple silicon; `--serialport` only works with the fixes `prepare`
applies).

**`flash-twrp`** writes TWRP (`hacks/recovery.img`) into `boot` only, and reads it back to verify.

**`root`**, from TWRP, amounts to:

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
authorizes your Mac's ADB key (nothing to confirm on the robot's screen).

**`flash-root`** writes TWRP into `recovery` and `boot-magisk.img` into `boot` from TWRP, with
[`twrp-flash.sh`](../tools/robot/twrp-flash.sh): it first checks that `recovery` holds your original
recovery (or TWRP) and `boot` holds TWRP (or the Magisk boot), writes nothing otherwise, then reads
each partition back.

**`android`** installs the Magisk app and checks root: by hand,
`adb install ~/Toque/downloads/Magisk-v31.0.apk` then `adb shell su -c id`, which must print
`uid=0`.

**`install`** runs [`tools/install-robot.sh`](../tools/install-robot.sh) (over Wi-Fi:
`--ip <robot IP address>`; another APK: `--apk <file>`). It also allows Toque in Magisk (no prompt
on the robot: Magisk's closes by itself after 10 s and then refuses for good) and puts Magisk's
**busybox** in place (`/data/adb/magisk/busybox`), which Toque needs for the serial port.

**`wifi`** amounts to:

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

Only on the home network: any device connected to it could then reach the robot.

## 8. From a Linux PC

The same script and the same steps work from Linux (x86_64 or ARM). **Tried once on a real robot**, from
Arch Linux on an x86_64 laptop: `prepare` to `wifi` and `unroot` all worked, through libusb;
`backup` and `flash-twrp` also worked with `--serial`. Not tried yet from Linux:
`unroot --download-mode`, and ARM.

Instead of part 2:

1. Install Python 3.10+, libusb, git, curl and unzip with your distribution's tool:
   - Arch: `sudo pacman -S python libusb git curl unzip`
   - Debian/Ubuntu: `sudo apt install python3 python3-venv libusb-1.0-0 git curl unzip`
   - Fedora: `sudo dnf install python3 libusb1 git curl unzip`
2. Download this repository and go into its folder (steps 3 and 4 of part 2).
3. Give your user access to the robot's USB port, with the
   [udev rule](../tools/setup/51-toque-robot.rules) of this repository (it asks for your password):
   ```
   sudo install -m 644 tools/setup/51-toque-robot.rules /etc/udev/rules.d/
   sudo udevadm control --reload-rules
   ```
   The rule also keeps ModemManager away from the robot. It gives access to the person logged in
   at the computer, not to an SSH session. If you manage USB access another way, add
   `--no-udev-check` to every command.

Then follow part 3a or 3b as written, reading "computer" for "Mac". What differs:

- There is no *Allow accessory to connect?* prompt.
- mtkclient talks to the robot through **libusb** by default (on a Mac: the serial driver). If
  `backup` stops on a handshake or *Sync error*, switch the robot off and try again with `--serial`
  (it uses `/dev/ttyACM0` and the same fixes as on a Mac).
- To see whether the computer sees the robot in download mode: `lsusb` right after switching it on
  must list `0e8d:2000` (MediaTek). If not, it is the cable, the port or the adapter. In TWRP the
  robot shows as `18d1:4ee2`, in Android with adb as `0e8d:201d` (`0e8d:2008` before the root).
- `sudo dmesg` repeats *new low-speed USB device* and *device descriptor read/64, error -71*: the
  cable is not pushed in all the way. Unplug it and plug it in again.
- `adb` says *no permissions*: the udev rule is missing; install it, then unplug and replug the cable.
- On ARM Linux, Google publishes no adb: install your distribution's (`adb` or `android-tools`).
