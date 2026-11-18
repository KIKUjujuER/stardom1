# Downloads

The release APK of **WhereQ-DOSBox**, for people who want to try the app
without building it.

| file | version | size |
|---|---|---|
| [whereq-dosbox-1.0.0.11.apk](whereq-dosbox-1.0.0.11.apk) | 1.0.0.11 | 15.7 MB |
| [whereq-dosbox-1.0.0.10.apk](whereq-dosbox-1.0.0.10.apk) | 1.0.0.10 | 15.7 MB |
| [whereq-dosbox-1.0.0.9.apk](whereq-dosbox-1.0.0.9.apk) | 1.0.0.9 | 15.7 MB |
| [whereq-dosbox-1.0.0.8.apk](whereq-dosbox-1.0.0.8.apk) | 1.0.0.8 | 15.5 MB |
| [whereq-dosbox-1.0.0.7.apk](whereq-dosbox-1.0.0.7.apk) | 1.0.0.7 | 15.5 MB |
| [whereq-dosbox-1.0.0.6.apk](whereq-dosbox-1.0.0.6.apk) | 1.0.0.6 | 15.5 MB |
| [whereq-dosbox-1.0.0.5.apk](whereq-dosbox-1.0.0.5.apk) | 1.0.0.5 | 15.5 MB |
| [whereq-dosbox-1.0.0.4.apk](whereq-dosbox-1.0.0.4.apk) | 1.0.0.4 | 5.0 MB |
| [whereq-dosbox-1.0.0.3.apk](whereq-dosbox-1.0.0.3.apk) | 1.0.0.3 | 5.0 MB |

## What changed in 1.0.0.11

* **The mouse has a right button.** A DOS mouse has two buttons and a
  touchscreen has one, so there was no way to send a right click — and in most
  DOS games the right button is *cancel* or *back*. The on-screen keyboard now
  has an **RMB** key on the left pad, next to **Tab**, and a **second finger**
  on the picture does the same at that finger's own position.
* **A click is a held button, not a tap.** The first finger holds the **left**
  button for as long as it is down, so a drag still drags, and the right button
  is held the same way. This is what a game that reads the mouse *live* needs:
  the button state at INT 33h AX=3. A click that was over in a millisecond
  could fall between two polls and be missed altogether.
* **The mouse survives a save state.** The INT 33h driver keeps its state
  outside the guest's memory — the game's event-callback registration, the
  cursor range and the button state. It was not in the snapshot, so after a
  restore the cursor still moved but the game's clicks stopped reaching it: a
  dialog's *confirm* button did nothing. The driver is now part of the state.

**Save states written by 1.0.0.10 cannot be loaded by this build.** The state
file grew a version when the mouse driver joined the snapshot, and an older
state is refused rather than misread.

The rest of the emulator core is unchanged — this release is the app layer and
the mouse driver.

## What changed in 1.0.0.10

* **The sound survives a save state.** The FM chip is now snapshotted
  *exactly* — the envelope, the wave phase and the LFO counter all come back
  with the state. In 1.0.0.9 the chip was not in the snapshot at all: the music
  kept playing while the rest of the machine was rewound, and it was gone
  entirely when a state was restored into a fresh process. Now the sound comes
  back with the machine, in the middle of the note rather than at the
  beginning of it. The PC speaker comes back silent instead of droning on its
  own.
* **A key no longer sticks.** Rotating the phone while a key was held left
  that key pressed in DOSBox for ever. Every key that is down is now released
  when the layout is rebuilt, and a modifier that is shown in two places (the
  pad and the fold-up panel) counts its fingers instead of assuming there is
  only one.
* **The launcher fits on the screen.** The **Files** panel measures the space
  it has and caps the game list to it, so a third game no longer pushes its
  buttons off the bottom edge where nothing could bring them back.
* **The desktop build links again.** The save-state code was missing from the
  autotools build and the two archives had a circular dependency — the
  desktop baseline compiles and links again.
* **A technical reference.** `docs/TECHNICAL-REFERENCE.md` in the repository
  describes the whole implementation with the diagrams: the native build, the
  save-state machine, the exact FM snapshot, the bundle launcher, the
  on-screen keyboard, the throughput benchmark, what is still open and the
  screenshot gallery.

**Save states written by 1.0.0.9 cannot be loaded by this build.** The state
file grew a version when the sound chips became part of the snapshot, and an
older state is refused rather than misread.

This release also carries the **milestone tag** for the end of the Android
migration phase. What follows is maintenance: bug fixes and documentation.

## What changed in 1.0.0.9

* **The keyboard is a console now.** In landscape the keys sit in the two
  black bars beside the picture, the way a handheld console is built: the
  picture in the middle, one hand on each side, nothing on top of the
  picture. The **left pad** holds the arrow keys as a D-pad (up on top, left
  and right side by side, down below) and the left half of a split QWERTY,
  with **File**, **Esc** and **Tab** along the top. The **right pad** holds
  the action keys — **Alt**, **Ctrl**, **Shift**, **Space**, **Enter** — and
  the right half of the QWERTY, with **#+=**, **Fn** and Backspace along the
  top.
* **Three layers, one key each.** **#+=** swaps both halves for the
  punctuation, **Fn** for F1–F12, Ins/Del, Home/End, PgUp/PgDn, Caps, PrtSc
  and Pause. The switched key lights up while its layer is on. The letter
  layer of the right half already carries `: . / \`, so a DOS path needs no
  layer switch at all.
* **Modifiers work both ways.** *Tap* Ctrl, Alt or Shift and it stays armed
  for the next key — Ctrl, then C. *Hold* it and it behaves like a real key
  for as long as the finger is down, which is what a game expects from its
  fire and strafe keys while the other thumb steers.
* **The layout follows the screen.** A letterbox bar of at least 116 dp gets
  the full pads; a narrower one gets a single key column per side and the
  fold-up QWERTY; portrait gets a slim strip along the bottom. The letter
  keys come to about 33 dp wide on a 20:9 screen and about 24 dp on an 18:9
  one — tight on the narrowest phones, where the D-pad and the action keys
  keep their full size.

The emulator core is the one from 1.0.0.8, unchanged — the whole change is
in the app layer.

## What changed in 1.0.0.8

* **Save states.** Every game gets three slots. **States** in the Files panel
  opens them; **Save** freezes the whole running machine — the CPU, the DOS
  memory, the video memory, the open DOS file handles, the hardware timers —
  and **Load** jumps back to that exact moment. A slot is a file on the phone,
  so it survives quitting the app and rebooting the phone.
* **Resume resumes.** The **Resume** button used to start the last game from
  the beginning. It now restores its newest save state, so the player
  continues where they left off. With no save state the game starts from the
  beginning, as before.
* **The arrows are an inverted T.** In landscape the four arrow keys sit in
  the right-hand column the way they do on a real keyboard — up on top, left
  and right side by side, down below.
* **The game keeps running.** While a game is running, a small ongoing
  notification holds the emulator process, so switching to another app no
  longer loses the game. Tap the notification to come back to the same frame.

Some old DOS programs do not survive a restore — the extender of Ultima VIII
still aborts on it. That is a limitation of the DOS internals the snapshot
does not capture yet, not of the app; `docs/TECHNICAL-REFERENCE.md` has the
details.

## What changed in 1.0.0.7

* **The CPU core can be changed in the app.** The **Config** button in the
  Files panel offers the five cores DOSBox knows: `auto` (the default),
  `normal`, `dynamic`, `simple` and `prefetch`. In 1.0.0.6 the fallback to
  `core=normal` was only documented — the configuration file is regenerated
  on every boot, so it could not be edited on the phone.
* The keyboard of that release is two columns in the letterbox bars
  (the modifiers on the left, **File**, **Ent**, **Space** and the arrows on
  the right). The README shows it.

## What changed in 1.0.0.6

The CPU core is now `core=auto`. A program that stays in real mode runs on the
interpreter, exactly as before; a program that switches into protected mode
hands over to the recompiling (JIT) core, which is compiled into the native
library for all three ABIs. Both paths were verified on the emulator —
STARFALL in real mode, Ultima VIII in protected mode.

If the JIT code cache cannot be allocated, `core=normal` is the fallback —
from 1.0.0.7 on it can be picked in the app, see above.

## Installing it

1. Copy the apk to the phone (cable, cloud drive, whatever is at hand).
2. Open it with the Files app.  Android asks whether to allow installing
   unknown apps from that app — allow it.
3. Install, then open **WhereQ-DOSBox**.

The APK is signed with the release key of this repository, so it can be
updated in place: a later version replaces it without uninstalling first.

* SHA-256 of the 1.0.0.11 APK:
  `1b0b27cfd1e36ee820dc8c0b9b03000a24c48181f6c6f1a3b8640b9a816d905d`
* SHA-256 of the 1.0.0.10 APK:
  `80bdce1b4bdeba809159310441e450e704f6c322e2a3ff98bcc822ed50077018`
* SHA-256 of the 1.0.0.9 APK:
  `cd6d0bdafd0f84c7324936c89a6cab14ed87a21e4211ed8909078b4a7a51ac02`
* SHA-256 of the 1.0.0.8 APK:
  `ed17f39bddeb9e871485df390f772f19c5a43abdbf3b5845c7abd4afbb602ca3`
* SHA-256 of the 1.0.0.7 APK:
  `2e97f12f3901acbdc23f62395316df25aa51f4a081d941a950b2d7dfa36910a1`
* SHA-256 of the 1.0.0.6 APK:
  `a41d76eeb2c79eccf5afb4f77dcff8d7525d8c9c55c2fad982707520feed369a`
* SHA-256 of the 1.0.0.5 APK:
  `562598cdf2ca9214f50af94604843acba8af90c292eeb7e06320532b313e4ac4`
* Signing certificate (SHA-256):
  `b16b480d5a1ff6d35baff0b15fa3fc4b0ee815e500861fbff1b062c9bf7d3933`

## What to try first

The app starts in a DOS prompt.  The **File** button in the on-screen
keyboard opens the launcher, where a bundle can be installed — and
[`examples/game-bundle`](../examples/game-bundle) holds **STARFALL**, a small
arcade game that shows what a bundle looks like when it runs.  Its bundle
zip is [`whereq-demo-bundle.zip`](../examples/game-bundle/dist/whereq-demo-bundle.zip);
install it with *Install a bundle (.zip)*.

To hear the save-state fix for yourself, install
[`examples/opl-test-bundle`](../examples/opl-test-bundle): it programs one
sustained tone on the FM chip and then waits for a key.  Save a state while it
plays, wait a couple of minutes, load the state — the tone continues from the
middle of its attack instead of starting over, and the log lines written by the
save and by the load are identical.

## Building it yourself

The APK is built from this repository with Gradle:

```sh
WHEREQ_RELEASE_KEYSTORE="$PWD/build/release.keystore" \
WHEREQ_RELEASE_STORE_PASS='<the release key password>' \
WHEREQ_RELEASE_ALIAS=whereq \
    ./bin/build.sh apk --release
```

Without the release key the Gradle build falls back to the debug key, and the
resulting APK is *debuggable* — it cannot be installed over a release-signed
one (Android refuses it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

See [docs/TECHNICAL-REFERENCE.md](../docs/TECHNICAL-REFERENCE.md) for the details.
