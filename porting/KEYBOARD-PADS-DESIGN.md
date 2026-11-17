# The two console-style pads — design note

This is the design of the next on-screen keyboard: the DOS picture stays in
the middle, one hand on each side, and the keys become the two halves of a
handheld console.

It was drawn and written by **Claude Code** in a session of its own
(`https://claude.ai/code/session_012G34KgN8euzVymy43fQNiJ`) and brought into
this repository as two commits on the `designs/keyboard-pads` branch:

| Commit | What it does |
|---|---|
| `a9e25b8` | *Dock the DOS picture left and put a keyboard dock on the right* — the first attempt, with the picture off-centre. |
| `d9eb89b` | *Lay the keyboard out as two console-style pads* — the picture back in the middle, the keyboard split into a left and a right pad. |

**It shipped in 1.0.0.9.** The branch was squashed into `main` as one commit,
so these two commits stay where they are — the design record — and the
released history carries the change itself.

**The design needs no native change.** The picture-docking hook of the first
commit was reverted, so the C++ is exactly what shipped in 1.0.0.8 — the
whole design lives in `android/app/src/main/java/com/whereq/dosbox/VirtualKeyboardView.kt`
and `WhereqDosboxActivity.kt`.

---

## The layout

> The keyboard is now two console-style pads with the DOS picture centred
> between them.
>
> **Left pad (left thumb):** the arrow keys as a D-pad at the bottom, in the
> same up / left-right / down shape; above it the left half of a split QWERTY
> (1–5, qwert, asdfg, zxcvb); File, Esc and Tab along the top.
>
> **Right pad (right thumb):** the action keys at the bottom — Alt, Ctrl,
> Shift, Space, Enter; above them the right half of the QWERTY; #+=, Fn and
> Backspace along the top.
>
> **Letter layer:** the right half carries `: . / \` so a DOS path needs no
> layer switch; comma and semicolon moved to the #+= layer.
>
> **Layers:** #+= swaps both halves to punctuation; Fn swaps them to F1–F12 on
> the left and Ins/Del, Home/End, PgUp/PgDn, Caps, PrtSc, Pause on the right.
>
> On your screen (about 873×397dp) each pad is 172dp wide, so letter keys are
> roughly 32×44dp — wider than in the previous one-sided dock.
>
> Holding Ctrl, Alt or Shift keeps it pressed while your finger is down, so
> you can steer with the left thumb and fire with the right at the same time.
> A quick tap still arms it for the next key.
>
> 18:9 phones are tight. The pads still fit, but letter keys drop to about
> 21dp wide. 16:9 phones get columns instead. The bars are too narrow for half
> a keyboard, so each side is a single column (arrows left, action keys right)
> with the old fold-up QWERTY.

## The three layers

The pads carry **three layers**, and the switch is a key in the top row of the
right pad:

| Key | Layer | The key itself changes to |
|---|---|---|
| — | **Letters** (the default) | — |
| **#+=** | **Symbols** | `abc` — tapping it again returns to the letters |
| **Fn** | **Functions** | stays `Fn`, but the pad lights up blue while the layer is active |

The active layer key is drawn in the armed colour (`COLOR_KEY_ARMED`, a blue),
so the layer is visible at a glance.

### Left pad

| Layer | Row 1 | Row 2 | Row 3 | Row 4 |
|---|---|---|---|---|
| Letters | `1 2 3 4 5` | `q w e r t` | `a s d f g` | `z x c v b` |
| Symbols | `1 2 3 4 5` | `! @ # $ %` | `- = [ ] \`` | `_ + { } ~` |
| Functions | `F1 F2 F3` | `F4 F5 F6` | `F7 F8 F9` | `F10 F11 F12` |

### Right pad

| Layer | Row 1 | Row 2 | Row 3 | Row 4 |
|---|---|---|---|---|
| Letters | `6 7 8 9 0` | `y u i o p` | `h j k l` `:` `;` | `n m` `.` `/` `\` |
| Symbols | `6 7 8 9 0` | `^ & * ( )` | `; ' , \` `\|` | `:` `"` `<` `>` `?` |
| Functions | `Ins Home PgUp` | `Del End PgDn` | `Caps PrtSc Pause` | — |

These are the tables in the code (`LEFT_LAYERS`, `RIGHT_LAYERS` in
`VirtualKeyboardView.kt`); the layer keys are `LAYER_LETTERS`,
`LAYER_SYMBOLS` and `LAYER_FUNCTIONS`.

Two details worth keeping:

- the **letter layer of the right half already carries `: . / \`**, so typing
  a DOS path needs no layer switch at all;
- on the **symbol layer every shifted character sits directly below its
  unshifted key** — the shifted `;` under the plain `;`, the shifted `:` under
  the plain `:`, and so on — so the symbol layer is a grid you can read.

## The drawings

To-scale drawings of the three layers, made before the code was run:

| Layer | Drawing |
|---|---|
| Letters | `porting/screenshots/android-n7-keyboard-pads-design.png` |
| Functions (**Fn**) | `porting/screenshots/android-n7-keyboard-pads-design-fn.png` |
| Symbols (**#+=**) | `porting/screenshots/android-n7-keyboard-pads-design-symbols.png` |

The README of the branch uses the letter drawing; the two layer drawings were
made in the same session and are kept here so that the design is complete
without the chat transcript.

## The design, running

The design was built and run on the emulator (API 37, release APK, x86_64),
and the three layers behave exactly as drawn:

| Layer | Screenshot (20:9) |
|---|---|
| Letters | `porting/screenshots/android-n7-pads-letters.png` |
| Symbols (**#+=**) | `porting/screenshots/android-n7-pads-symbols.png` |
| Functions (**Fn**) | `porting/screenshots/android-n7-pads-functions.png` |

The active layer key is lit blue while its layer is on: **Fn** in the function
layer, **abc** in the symbol layer. Tapping the key again returns to the
letters.

The build needs **no native change** — the 1.0.0.8 `libdosbox.so` is used
unchanged, which is what the design says ("the C++ is back to what you
shipped in 1.0.0.8").

## On an 18:9 phone

The same build was run on a **2160×1080** display — exactly 18:9 — and all
three layers were checked there as well:

| Layer | Screenshot (18:9) |
|---|---|
| Letters | `porting/screenshots/android-n7-pads-18x9-letters.png` |
| Symbols (**#+=**) | `porting/screenshots/android-n7-pads-18x9-symbols.png` |
| Functions (**Fn**) | `porting/screenshots/android-n7-pads-18x9-functions.png` |

The pads are still used — the bar is 137 dp wide and the pads need 116 dp — so
nothing falls back to the columns. The keys do get smaller, and this is the
measurement (420 dpi, so 1 dp = 2.625 px):

| | bar | letter key | key height |
|---|---|---|---|
| **20:9** (2400×1080) | 183 dp | **32.8 dp** wide | 44 dp |
| **18:9** (2160×1080) | 137 dp | **23.6 dp** wide | 44 dp |

So the design note's estimate of "about 21dp" was close: the letter keys are
**23.6 dp** wide on an 18:9 screen, against 32.8 dp on a 20:9 one. The D-pad
and the action keys are unaffected — they keep their full 44 dp height and
their double width.

**This is the number to decide on.** A 23.6 dp key is about 2.9 % of the
screen width, where a normal phone keyboard key is roughly twice that. It is
still tappable, and the typing test on the 18:9 display confirmed that the
keys hit the right characters (`abc` came out as `abc`) — but it is at the
edge of what a fingertip can do reliably, and the design's own answer to that
is a layer switch, not a bigger key.

## What is still open

- **Arrows moved to the left.** That follows the console convention, but it
  reverses the right-hand arrow cluster of 1.0.0.8. Swapping the two thumb
  blocks is a small change if the arrows are preferred on the right.
- **The letter keys are small on an 18:9 screen** — 23.6 dp wide against
  32.8 dp on a 20:9 one. The emulator says they hit the right characters;
  a fingertip says something else. More columns per half, or a smaller
  picture, would be the way out.
- **Not on a real device.** Both the 20:9 and the 18:9 checks were done on
  an emulator at 420 dpi.
- **The picture in the middle** is what the first commit gave up (it docked
  the DOS picture to the left). The revert means the native picture-position
  hook is gone again, so the picture is centred and the pads live in the
  letterbox bars.


