# AGENTS.md: install Toque on a Monsieur Cuisine Connect from a Mac

This file is for an AI agent (Claude, ChatGPT, Copilot…) that a person has pointed at this
repository with a request such as *"Install Toque on my Monsieur Cuisine Connect"*. It is the
complete procedure, written so that the agent does everything it can by itself and asks the person
only for what needs hands, eyes or a macOS prompt.

This is the **macOS** version of [Wuki60/MrCuisineVibeConnect](https://github.com/Wuki60/MrCuisineVibeConnect):
the Windows tools (SP Flash Tool, MediaTek driver, PowerShell) are replaced by
[mtkclient](https://github.com/bkerler/mtkclient) and shell scripts. The Toque app itself is
unchanged. Humans: the same procedure by hand is in [docs/INSTALLATION.md](docs/INSTALLATION.md).

## What you are doing

The Lidl **Monsieur Cuisine Connect** (model **SKMC 1200**) is a cooking robot whose screen is an
Android 6 tablet (MediaTek MT6580). Toque replaces its stock app. To get there you will:

1. back up two partitions of the tablet (boot, recovery) — the only way back;
2. boot TWRP (a recovery system) and patch the original boot with **Magisk** (root);
3. write the patched boot, keep TWRP as recovery;
4. install the Toque APK, enable ADB over Wi-Fi.

The first two writes need the robot's MediaTek **download mode** (robot switched off, reached over
USB): on a Mac this is done by **mtkclient**. Everything else goes through **adb**, and the patched
boot is written from TWRP. Total time 1–2 hours, most of it waiting for the person.

**What has been tried where.** The procedure was done end to end on a real robot in October 2026
from Windows, with SP Flash Tool, and on **15 November 2026 from a Mac** (Apple silicon) on another
real robot (SKMC 1200 **C3**), with these scripts. On the Mac, stock mtkclient v2.1.4.1 did not
work: through libusb the preloader handshake always failed (as in
[bkerler/mtkclient#333](https://github.com/bkerler/mtkclient/issues/333)), and through the macOS
serial driver three bugs stopped it (*Sync error*, then the DA upload stopped half-way). `prepare`
now applies the fixes, [tools/setup/mtkclient-macos-serial.patch](tools/setup/mtkclient-macos-serial.patch),
and the script uses the serial driver by default. With them, `backup` and two later read-backs
gave identical images, and `flash-twrp` wrote and verified TWRP. That is one robot and one Mac:
if download mode does not work from the Mac, see
[When mtkclient does not reach the robot](#when-mtkclient-does-not-reach-the-robot). Tell the
person this before starting.

**Linux.** The same script runs on a Linux PC: see [On Linux](#on-linux) for what differs.
It was run **once** on a real robot (Arch Linux, x86_64, through libusb), from `prepare` to
`wifi`, then `unroot`, and `backup` and `flash-twrp` with `--serial`; `unroot --download-mode` and
ARM were not tried from Linux. Tell the person this
instead of the Mac sentence above.

## Rules for the agent

- **Talk to the person in their language**, in short messages. Before starting, tell them what
  they will need (list below), that it voids the warranty, that a mistake can leave the robot's
  tablet unusable, that the macOS download-mode path has worked on one robot so far (after fixes
  to mtkclient that `prepare` now applies), and get a clear *yes*.
- **Never skip the backup step**, never write anything other than `boot` and `recovery`, and never
  run other mtkclient commands than the ones the script runs (no erase, no full-flash write, no
  preloader or other partitions).
- **Never type passwords, never change macOS security settings yourself.** Installing Homebrew
  packages: ask first, saying which ones.
- Download only from the URLs below, and let the script check the SHA-256 of every file (and the
  mtkclient commit). If a check does not match, stop.
- Ask before each step that writes to the robot (`flash-twrp`, `root`, `flash-root`, `unroot`) — say
  what it does in one sentence and wait for *yes*.
- When a step prints `STOP:`, do not improvise a workaround on the robot: read the cause, fix it
  on the Mac side or ask the person, then run the step again.
- If the robot is not an SKMC 1200 Monsieur Cuisine **Connect** (for example a Monsieur Cuisine
  **Smart**, SKMS 1200), stop: this procedure does not apply.

## What the person needs

- A Mac (Apple silicon or Intel) with Internet, [Homebrew](https://brew.sh), and the agent able to
  run commands in Terminal. Python 3.10+ and libusb: `brew install python libusb`; git: Apple's
  command line tools (`xcode-select --install`). `prepare` says what is missing.
- A **USB-A male ↔ USB-A male data cable**. The robot's port is a device port: a USB-C ↔ USB-A
  cable does **not** work (the Mac's USB-C port never sees it). On a Mac with only USB-C ports, put
  a **USB-C to USB-A adapter** (female USB-A) or a USB hub between the Mac and the A↔A cable.
- A small Torx screwdriver (maintenance cover under the robot).
- The robot within reach of the Mac, connected to the mains, and the home Wi-Fi password (for the
  end).

No driver to install, no SP Flash Tool, no change to macOS security.

The person usually comes from part 2 of [docs/INSTALLATION.md](docs/INSTALLATION.md) (Homebrew,
`brew install python libusb`, this repository cloned into `~/MrCuisineVibeConnect`) and keeps that
guide open: its part 3b shows them what each step looks like. If Homebrew is missing, the person
installs it themselves (it asks for their Mac password): give them the command from that part.

## The tool

Everything is driven by one script, from the repository folder:

```
bash tools/setup/toque-setup.sh <step>
```

Work folder: `~/Toque`, or a `Toque` folder next to the repository if there is one (change with
`--work DIR`; `status` prints it). Every step can be re-run; `status` says what
is done and what comes next. Output lines:

- `OK:` done and verified;
- `HUMAN:` relay this to the person, word for word in their language, and wait until they say it
  is done;
- `STOP:` the step failed safely (exit code 2) — nothing irreversible was done after the failure,
  and the line says what to do;
- exit code 3: the step is waiting for a human action; run it again afterwards.

A log is kept in `~/Toque/setup-log.txt`, state in `~/Toque/state.txt`, mtkclient's output in
`~/Toque/mtk-<step>-out.txt`.

**Download-mode steps** (`backup`, `flash-twrp`, `unroot --download-mode`) wait for the robot to be
switched on, for up to 30 minutes (`--mtk-timeout SECONDS`). Run them in the background or with a
long timeout. Relay the power instructions before starting: robot off, cable in. Then, once the
output says mtkclient is waiting for the device (*Waiting for PreLoader VCOM*), tell the person to
switch the robot on, **once**. While it waits, mtkclient prints *Handshake failed, retrying...*
about every 10 seconds: that is normal. After a session (failed or not), the robot stays in
download mode with a black screen until it is switched off: before running another download-mode
step, have the person switch it off. `ls /dev/cu.usbmodem*` lists nothing once it is really off.

## Procedure

| # | Step | Who | What happens |
|---|------|-----|--------------|
| 0 | clone | agent | Get this repository on the Mac (git clone or a ZIP). |
| 1 | `prepare` | agent | Downloads adb, Magisk v31.0, TWRP and the scatter file, the latest Toque APK, mtkclient v2.1.4.1 and its Python packages (into `~/Toque/mtkclient`); checks every SHA-256 and the mtkclient commit, applies the macOS serial fixes. Says which Homebrew packages are missing, if any. |
| 2 | `backup` | person, then agent | Cover off, cable in the **right-hand "Android USB" port**, robot off; mtkclient reads `boot` and `recovery` when the person switches the robot on. Verifies two 16 MiB Android images before keeping them. |
| 3 | `flash-twrp` | agent + person | Writes TWRP into `boot` in download mode and reads it back. The person power-cycles: off, **unplug**, on, plug back → TWRP. The first time, TWRP asks *Keep System Read only?*: the person taps **Keep Read Only**. |
| 4 | `root` | agent | In TWRP: patches the *original* boot with Magisk, enables ADB in Android, authorizes the Mac's ADB key, pulls `boot-magisk.img`. |
| 5 | `flash-root` | agent | Still in TWRP: writes TWRP into `recovery` and `boot-magisk.img` into `boot`. Checks first that both partitions hold what they should (else writes nothing), reads each one back. Power cycle as above → stock app. |
| 6 | `android` | agent + person | Checks Magisk, installs its app; the person taps **Grant** on the robot once. Checks the boot partition. |
| 7 | `install` | agent | Installs Toque, allows it in Magisk, starts it. |
| 8 | first run | person | Empty bowl: Settings › Machine › Real machine › Apply, badge **Ready**, manual cooking 10 s speed 2. |
| 9 | `wifi` | agent | ADB over Wi-Fi (port 5555), so the cable can go and the cover go back on. |

Then: Toque › Settings › Lidl recipes › **Sync** (about 15 minutes the first time).

### Power-on rules (the source of most confusion)

- **Download-mode steps** (`backup`, `flash-twrp`): robot **off**, cable **plugged in**, start the
  step, then switch the robot **on**. mtkclient catches it before Android starts.
- **adb steps** (`root`, `flash-root`, `android`, `install`, `wifi`): switch the robot on with the
  cable **unplugged**, plug it in once the screen is up. Switched on with the cable in, the robot
  stops on a **battery / charging screen**: switch off, unplug, switch on, plug.
- Only the right-hand port, labelled **"Android USB"**, ever. The other port can destroy the board.
- On an Apple silicon Mac, macOS may ask *"Allow accessory to connect?"* the first time it sees the
  robot (in download mode, in TWRP, in Android). The person clicks **Allow**. In download mode the
  robot does not wait for the answer: after allowing, switch the robot off and on again while the
  step is still waiting.

### When mtkclient does not reach the robot

In order:

1. Check the hardware: A↔A cable, right-hand port, a USB-A port or a USB-C → USB-A adapter on
   the Mac. In `~/Toque/mtk-<step>-out.txt`, *"Waiting for device"* that never changes means macOS
   never saw the robot. `system_profiler SPUSBDataType` run right after switching the robot on
   should list a **MT65xx Preloader** device (vendor ID `0x0e8d`).
2. If mtkclient sees the robot (*Detected 0xe8d:0x2000 device at /dev/cu.usbmodem…*) but stops
   with *Sync error* or *Failed to upload da*: check that `status` says *mtkclient: v2.1.4.1 + macOS
   serial fixes* (else run `prepare`), have the person switch the robot off, and run the step
   again. The step talks through the macOS serial driver (`/dev/cu.usbmodem…`), the way SP Flash Tool
   uses its serial driver on Windows; `--libusb` tries libusb instead, which never got past the
   handshake on the robot it was tried on.
3. If it still fails: do **only the two download-mode steps on a Windows PC** with the original
   project ([toque-setup.ps1](tools/setup/toque-setup.ps1), still in this repository: `-Step prepare`,
   `driver`, `backup`, `flash-twrp`). Copy `C:\Toque\backup\boot-original.img` and
   `recovery-original.img` into `~/Toque/backup/` on the Mac and run `backup` there (it only verifies
   existing files). With TWRP on the robot's screen, continue on the Mac with `root`: every later
   step uses adb, and `status` will still list `flash-twrp`, which is then already done.

Nothing is ever written when mtkclient cannot connect.

### On Linux

Everything above applies, reading "computer" for "Mac", with these differences:

- **Packages** instead of Homebrew: Python 3.10+ (with `venv`), libusb 1.0, git, curl, unzip.
  `prepare` prints the command for Arch, Debian/Ubuntu and Fedora. The person runs `sudo` commands.
- **udev rule**: `prepare` stops (exit code 3) until the person has installed
  [tools/setup/51-toque-robot.rules](tools/setup/51-toque-robot.rules) with the `sudo` command it
  prints. The rule gives the user logged in at the computer (not an SSH session) access to the
  robot's USB port, for mtkclient and adb, and keeps ModemManager away from it. Download-mode steps
  refuse to run without it; `--no-udev-check` is for a person who set up USB access another way.
- **Transport**: mtkclient goes through **libusb** by default (its usual way on Linux), so the
  output shows no *Handshake failed, retrying...* lines while it waits. If it sees the robot but
  fails, have the person switch the robot off and run the step again with `--serial`
  (`/dev/ttyACM*`, with the same fixes as on macOS). `status` says which one is in use.
- **Is the robot seen?** `lsusb` right after switching it on lists `0e8d:2000`; `ls /dev/ttyACM*`
  lists nothing once it is really off. There is no *Allow accessory* prompt. Other modes: TWRP is
  `18d1:4ee2`, Android with adb `0e8d:201d` (`0e8d:2008`, MTP only, before the root).
- The kernel's `cdc_acm` driver creates `/dev/ttyACM0` in download mode and logs *Zero length
  descriptor references*: harmless, libusb still works.
- `adb` state *no permissions*: the udev rule is missing; the step says so.
- On ARM Linux, `prepare` uses the distribution's adb (Google publishes none).
- If download mode does not work from Linux either, the Windows fallback of step 3 above applies.

### Files the script downloads by itself

| File | Source | Check |
|---|---|---|
| Android platform tools (adb) | `https://dl.google.com/android/repository/platform-tools-latest-darwin.zip` (Linux: `…-latest-linux.zip`) | (latest, from Google) |
| Magisk v31.0 | `https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk` | SHA-256 `2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8` |
| TWRP `hacks/recovery.img` | [EliasKotlyar/Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack) | SHA-256 `fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e` |
| Scatter `l706_dfbh_v_695scatter.txt` | same repository | SHA-256 `13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364`; `boot` at `0x1d20000` and `recovery` at `0x2d20000` (16 MiB each) are checked in it |
| Toque `Toque-X.Y.Z.apk` | [latest release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) of the original repository | (signed with the project key) |
| mtkclient v2.1.4.1 | `git clone` of [bkerler/mtkclient](https://github.com/bkerler/mtkclient) | commit `5a863eece86fcaa97cb8325cf747e0aae3c307e4`, then [mtkclient-macos-serial.patch](tools/setup/mtkclient-macos-serial.patch) applied; download-mode steps refuse to run unless exactly that is in place |
| mtkclient's Python packages | PyPI, [tools/setup/mtkclient-requirements.txt](tools/setup/mtkclient-requirements.txt) | pinned versions and SHA-256 (`pip --require-hashes`) |

mtkclient's graphical interface (PySide6) and FUSE support are not installed: they are not needed,
and without macFUSE the FUSE package stops mtkclient from starting
([bkerler/mtkclient#384](https://github.com/bkerler/mtkclient/issues/384)).

## Known problems and fixes

| Symptom | Cause | Fix |
|---|---|---|
| mtkclient waits forever (*Waiting for device*) | A↔C cable, wrong port, USB-C port without adapter, or the robot was already on | A↔A cable, right-hand "Android USB" port, USB-C → USB-A adapter; robot off, then on while the step waits |
| *Handshake failed, retrying...* every 10 s | Normal while mtkclient waits for the robot (serial driver) | Have the person switch the robot on, once |
| mtkclient sees the robot, then *Sync error* or *Failed to upload da* | mtkclient without its macOS serial fixes, or the robot was already in download mode | `prepare`; robot off; run the step again; else the Windows fallback above |
| Black screen, `/dev/cu.usbmodem…` still there after a download-mode step | The robot stays in download mode after an mtkclient session | Switch the robot off |
| *Allow accessory to connect?* on the Mac | Apple silicon accessory security | **Allow**, then switch the robot off and on again |
| `prepare`: *MISSING: libusb / Python* | Homebrew packages | Ask the person, then `brew install libusb python` |
| Robot shows a battery picture instead of booting | Switched on with the cable plugged in | Off, unplug, on, plug in after start-up |
| `adb` shows nothing in TWRP | Cable plugged before TWRP was up | Unplug/replug; `status` |
| Battery screen between `root` and `flash-root` (TWRP gone) | The robot restarted with the cable in | Off, unplug, on: `boot` still holds TWRP, which comes back; plug in, run the next step |
| `root` fails during the Magisk patch | Not the original boot, or a corrupt backup | Nothing was flashed. Check `~/Toque/backup`, redo `backup` |
| `flash-root`: *holds something unexpected* | A partition holds neither what was backed up nor TWRP / the Magisk boot | Nothing was written. Do not continue; check `status` and the backups |
| `flash-root`: *write failed* or *read-back differs* | Write error in TWRP | Do **not** switch the robot off: TWRP is still running. Run `flash-root` again |
| `android`: `su` returns nothing | The Magisk prompt was missed (it closes after 10 s and then refuses) | Person taps **Grant** quickly; if refused for good: Magisk app › Superuser › Shell › allow |
| Toque shows "Base absente" / no motor link | Magisk busybox missing | `install` again (it runs `tools/robot/setup-root.sh`) |
| Stuck in the stock app (no Home/Back buttons on this robot) | Normal | Floating **Back to Toque** button, or `adb shell am start -n app.toque/.ui.MainActivity` |
| `Toque s'est vu refuser les droits` toasts | Magisk policy | `install` again (it allows Toque in Magisk's database) |

## Undo

- Back to the stock app, keeping root: Toque › Settings › Tools › *Back to the stock app*, or
  `bash tools/restore-robot.sh --uninstall`.
- Remove root and TWRP (do the line above first): `bash tools/setup/toque-setup.sh unroot`. It
  restarts the robot into TWRP and writes `boot-original.img` into `boot` and
  `recovery-original.img` into `recovery`, with the same checks as `flash-root`. If the robot no
  longer starts at all: `unroot --download-mode` (robot off, cable in, like `backup`).
  `unroot` leaves what `root` changed in `/system` (adb stays enabled, without root) and the Magisk
  app. Running `root` again afterwards overwrites the saved `system-originals/orig/build.prop` with
  the already modified one: copy that folder elsewhere first.
- After `wifi`, with the cable still in, adb lists the robot twice and commands without `--ip`
  fail with *more than one device/emulator*: `adb disconnect <ip>:5555`, or have the cable unplugged.

## After the install

- Updates: Toque › Settings › Updates installs new releases from the original repository.
- Recipes: Settings › Lidl recipes › Sync (monsieur-cuisine.com, no account). Nightly re-sync.
- Cookomix recipes: Settings › Cookomix recipes › Sync Cookomix (cookomix.com, an hour or more the
  first time). Error codes CKX-01 to CKX-07: [docs/COOKOMIX-ERREURS.md](docs/COOKOMIX-ERREURS.md).
- Clock: set automatically (time zone from the Internet connection); Settings › Date and time.
- To work on the robot later over Wi-Fi: `adb connect <robot IP>:5555`.

## Working on the code

If you were asked to change Toque rather than install it: `core/` is pure Kotlin (no Android) and
holds the protocol, the safety rules, the controller and the recipe parsing; `app/` is the Android
app (minSdk 23, no AndroidX, views built in code). Run `./gradlew :core:test` (unit tests, including
a check that every interface text has an English translation in `app/src/main/assets/lang/en.json`)
and `./gradlew :app:assembleRelease`, with JDK 17 or 21 (Android Studio's:
`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). Interface texts are
written in French and wrapped in `tr(...)`. Motor safety rules live in `core/.../machine/Safety.kt`
and are enforced by `MachineController`: keep them strict, and add a test in `ControllerTest` for any
behaviour change. The Cookomix conversion (Thermomix → MCC) lives in `core/.../recipes/Cookomix.kt`
(tests: `CookomixTest`, and `CookomixTwinTest` with `tools/twin.py` running); every `Cookomix.Error`
code must have a row in `docs/COOKOMIX-ERREURS.md` (checked by `CookomixTest.errorCodes`).
