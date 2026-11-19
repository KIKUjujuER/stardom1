# WhereQ-DOSBox — the interrupt map

> The software interrupt is the one interface that every DOS program from 1981
> to 1994 agrees on. A game does not call the video card; it calls INT 10h. It
> does not read the keyboard; it calls INT 16h. It does not read the mouse; it
> calls INT 33h. This document is the map of those calls in this emulator: which
> vector exists, what it is for, which code implements it, and — where the port
> changed the behaviour — why.

Every claim below carries a `path:line` citation into the tree at the commit
this document was written against. Where something is *not* implemented, that is
said explicitly, because "the emulator answers this call with a log line" and
"the emulator answers this call correctly" are very different facts.

---

## Table of contents

1. [How an interrupt reaches the emulator](#1-how-an-interrupt-reaches-the-emulator)
2. [The map](#2-the-map)
3. [The software interrupts](#3-the-software-interrupts)
4. [The hardware interrupts](#4-the-hardware-interrupts)
5. [Case study: a finger becomes a DOS mouse](#5-case-study-a-finger-becomes-a-dos-mouse)
6. [Case study: which interrupt state belongs in a save state](#6-case-study-which-interrupt-state-belongs-in-a-save-state)
7. [What this port did not change](#7-what-this-port-did-not-change)
8. [Watching the interrupts yourself](#8-watching-the-interrupts-yourself)

---

## 1. How an interrupt reaches the emulator

There is no central interrupt table in this tree. A software interrupt is
handled the way a real BIOS handles it: the vector is pointed at a **stub**, and
the stub calls into C.

**The CPU side.** `INT n` executes opcode `0xCD` (`src/cpu/cpu.cpp:555`), which
lands in `CPU_Interrupt()` (`src/cpu/cpu.cpp:550`). In real mode the handler
pushes flags, CS and EIP, clears IF and TF, and reads the new `CS:IP` out of
the **interrupt vector table** at `base + (num << 2)` (`src/cpu/cpu.cpp:567-572`).

**The BIOS side.** A service installs itself by writing that IVT entry. The
write goes through `RealSetVec()` (`include/mem.h:205`), and the target is
usually not a real handler — it is a small **stub** built by
`CALLBACK_Setup()` (`src/cpu/callback.cpp:537`), which lives in the callback
arena at `F000:1000+` (`include/callback.h:38-39`).

The stub is a few bytes of hand-assembled real-mode code whose shape is decided
by a **callback type** (`include/callback.h:29-35`):

| type | stub ends with | used by |
|---|---|---|
| `CB_IRET` | `IRET` | INT 10h, INT 21h, INT 67h, INT 2Fh |
| `CB_RETF` | `RETF` | far-returning services (the VESA window thunks) |
| `CB_RETF8` | `RETF` + 8 bytes popped | the mouse backdoor |
| `CB_INT16` | `IRET` + a 12-byte NOP sled + a jump back | INT 16h — see below |
| `CB_INT13` | `IRET` + `INT 0Eh` + `IRET` | the disk-error convention |
| `CB_IRQ0` / `CB_IRQ1` / `CB_IRQ12` | EOI to the PIC, then `IRET`/`RETF` | the hardware side |
| `CB_HOOKABLE` | a jump the guest may overwrite | XMS, the keyboard intercept |

The interesting part is DOSBox's **extra callback instruction** — the byte
sequence `FE 38 <callback-id>`. It is a synthetic opcode that the CPU
interpreter recognises and turns into a call to the registered C function, with
the registers set up by the surrounding stub. This is how a DOSBox service
behaves like a BIOS routine: the guest can call it with a far call, and the
emulator gets a C function call in the middle.

Two examples of why the stub shape matters:

- **INT 16h spins instead of returning.** The `CB_INT16` stub is
  `IRET`, then twelve `NOP`s, then a jump back into the callback
  (`src/cpu/callback.cpp:381-383`). When no key is buffered the handler does
  `reg_ip += 1` (`src/ints/bios_keyboard.cpp:508`), which lands it back on the
  NOP sled. The guest therefore *spins in real mode* until IRQ 1 delivers a
  key — exactly what real hardware does, and the reason a DOSBox guest cannot
  be starved by the emulator's event loop.
- **The PS/2 mouse returns with `RETF`.** The guest's PS/2 callback is a far
  routine, so the `CB_IRQ12` stub ends with `RETF`
  (`src/cpu/callback.cpp:313-318`).

---

## 2. The map

### The software interrupts

| Vector | Service | What it is for | Handler | Installed at |
|---|---|---|---|---|
| **10h** | Video BIOS | text and graphics output, the cursor, the palette, VESA/VBE | `INT10_Handler` | `src/ints/int10.cpp:762-764` |
| **13h** | Disk BIOS | sector reads and writes against the disk images | `INT13_DiskHandler` | `src/ints/bios_disk.cpp:569-571` |
| **16h** | Keyboard BIOS | keystrokes out of the BIOS keyboard buffer | `INT16_Handler` | `src/ints/bios_keyboard.cpp:628-630` |
| **21h** | DOS | the DOS API: files, directories, memory, programs | `DOS_21Handler` | `src/dos/dos.cpp:1216` |
| **29h** | Fast console | unbuffered character output, no DOS overhead | `callback[6]` | `src/dos/dos.cpp:1236` |
| **2Ah** | Network redirector | "is there a network stack?" | `INT2A_Handler` | `src/dos/dos_misc.cpp:212-213` |
| **2Eh** | DOS shell | the emulator's own command shell | `INT2E_Handler` | `src/shell/shell.cpp:709-710` |
| **2Fh** | Multiplex | the dispatcher for XMS, CD-ROM, network, and other extensions | `INT2F_Handler` | `src/dos/dos_misc.cpp:207-208` |
| **33h** | Mouse | the DOS mouse driver | `INT33_Handler` | `src/ints/mouse.cpp:1216-1223` |
| **67h** | EMS | expanded memory, plus VCPI | `INT67_Handler` | `src/ints/ems.cpp:1343-1345` |
| **74h** | PS/2 mouse | IRQ 12, the mouse's hardware vector | `INT74_Handler` | `src/ints/mouse.cpp:1269-1270` |
| **41h / 46h** | Disk parameter tables | the geometry of the fixed disks and the floppies | `updateDPT()` | `src/ints/bios_disk.cpp:585-586` |
| **1Fh / 43h / 44h** | Fonts | the 8×8 character blocks (128–255, graphics fonts) | — | `src/ints/int10_memory.cpp:176`, `:243` |
| **4Bh** | Virtual DMA | the VDMA channel API | `INT4B_Handler` | `src/ints/ems.cpp:1320-1321` |
| **73h** | IPX ESR | the Novell network event service routine | `IPX_ESRHandler` | `src/hardware/ipx.cpp:1157` |

### The hardware interrupts

An IRQ number is not a vector number. The 8259 pair has a **vector base** —
`0x08` for the master and `0x70` for the slave (`src/hardware/pic.cpp:612-613`)
— and the vector is computed when the interrupt is taken:

```c
CPU_HW_Interrupt(master.vector_base + i);      /* src/hardware/pic.cpp:367 */
CPU_HW_Interrupt(slave.vector_base + pic1_irq); /* src/hardware/pic.cpp:362 */
```

So master IRQ 0–7 become **INT 08h–0Fh**, and slave IRQ 8–15 become
**INT 70h–77h**.

| IRQ | Vector | Device | Handler | Installed at |
|---|---|---|---|---|
| 0 | 08h | the PIT timer | `INT8_Handler` | `src/ints/bios.cpp:1031-1033` |
| 1 | 09h | the keyboard | `IRQ1_Handler` | `src/ints/bios_keyboard.cpp:632-634` |
| 2 | 0Ah | the cascade to the slave PIC | none — an EOI stub | `src/ints/bios.cpp:1122-1124` |
| 6 | 0Eh | the PCjr keyboard (PCjr only) | none | `src/ints/bios_keyboard.cpp:658-661` |
| 8 | 70h | the real-time clock | — | `src/ints/bios.cpp:1089-1090` |
| 12 | 74h | the PS/2 mouse | `INT74_Handler` | `src/ints/mouse.cpp:1269-1270` |
| 3, 4, 5, 7, 10, 11 | 0Bh, 0Ch, 0Dh, 0Fh, 72h, 73h | everything else | `Default_IRQ_Handler` | `src/ints/bios.cpp:1129-1134` |

The PC's famous **"IRQ 9 = IRQ 2"** trick is the re-vectoring of INT 71h
(`src/ints/bios.cpp:1092-1094`): the `CB_IRQ9` stub remaps the slave and then
enters the master through the IRQ 2 vector, so the cascade semantics survive.

---

## 3. The software interrupts

### INT 10h — the video BIOS

The largest of the software interrupts, and the one with the most interesting
internal structure: DOSBox splits it across **nine files**, and the split is by
*kind of work*, not by function code.

| File | Owns |
|---|---|
| `int10.cpp` | the dispatcher `INT10_Handler()` and the vector installation |
| `int10_modes.cpp` | the video mode tables and mode setting |
| `int10_char.cpp` | character cells: scrolling, cursor, read/write, teletype |
| `int10_memory.cpp` | the fake video ROM at `C000:0000`, the fonts, the VESA ROM |
| `int10_pal.cpp` | the palette and the DAC |
| `int10_put_pixel.cpp` | graphics-mode pixel access |
| `int10_vesa.cpp` | VBE 2.0/3.0, and the protected-mode window thunks |
| `int10_video_state.cpp` | the AH=1Ch save/restore area |
| `int10_vptable.cpp` | the VESA video parameter table |

The AH codes it implements:

| AH | Function | AH | Function |
|---|---|---|---|
| 00h | set video mode | 0Bh | set background/border colour |
| 01h | set cursor shape | 0Ch | write graphics pixel |
| 02h | set cursor position | 0Dh | read graphics pixel |
| 03h | read cursor position | 0Eh | teletype output |
| 04h | read light pen *(stub)* | 0Fh | get video state |
| 05h | select active page | 10h | palette / DAC functions |
| 06h | scroll window up | 11h | load a character generator |
| 07h | scroll window down | 12h | alternate function select (EGA/VGA) |
| 08h | read char and attribute | 13h | write string |
| 09h | write char and attribute | 1Ah | display combination code |
| 0Ah | write char only | 1Bh | functionality state information |
| | | 1Ch | video state save area |
| | | 4Fh | VESA / VBE |
| | | F0h–F5h, FAh | EGA RIL register access |

**AH=1Eh is not implemented.** There is no `case 0x1e:` in the dispatcher; it
falls to the default branch and logs "not supported"
(`src/ints/int10.cpp:698-701`).

#### The VESA banked window

INT 10h AH=4Fh is where the modern-video path lives, and it is the most
interesting mechanism in the BIOS layer. VBE 2.0 lets a protected-mode driver
ask the card to map a 64 KB window of the linear frame buffer anywhere in its
physical address space, by writing one register.

DOSBox emulates this **by remapping the A0000 segment**, not with a physical
aperture. `VESA_SetCPUWindow()` writes the S3 register `0x6A`
(`src/ints/int10_vesa.cpp:283-290`), which sets the bank and calls
`VGA_SetupHandlers()` (`src/hardware/vga_s3.cpp:339-343`). That re-installs the
A0000 page handler over 32 pages with the mask `0x1ffff`
(`src/hardware/vga_memory.cpp:894-903`), and every access through the handler
is offset by the selected bank (`src/hardware/vga_memory.cpp:153-158`).

The **protected-mode interface** is not protected-mode code. It is a set of
real-mode fragments parked in the `C000:` ROM area whose addresses the guest's
VBE driver calls while it is running in protected mode
(`src/ints/int10_vesa.cpp:584-610`). Each has to be its own stub because each
has its own ABI:

| thunk | callback type | contract |
|---|---|---|
| `VESA_SetWindow` | `CB_RETF` | the real-mode entry point; returns a VBE status in AH |
| `VESA_PMSetWindow` | `CB_RETN` | a raw register protocol, no status |
| `VESA_PMSetStart` | `CB_VESA_PM` | sets the display start; may wait for retrace |
| `VESA_PMSetPalette` | `CB_RETN` | writes the DAC directly |

`CB_VESA_WAIT` is the only callback type with **no C handler at all**
(`src/cpu/callback.cpp:488` refuses one). It is pure real-mode code — the
classic "wait for vertical retrace" loop polling the input status register at
`0x3DA` (`src/cpu/callback.cpp:492-495`).

And the whole VESA surface is conditional: it exists only on an S3 Trio
(`src/ints/int10.cpp:532-533`, `src/ints/int10_memory.cpp:143`).

### INT 13h — the disk BIOS

| AH | Function | Status |
|---|---|---|
| 00h | reset the disk system | implemented (`src/ints/bios_disk.cpp:325`) |
| 01h | status of the last operation | implemented (`:348`) |
| 02h | read sectors | implemented (`:358`) |
| 03h | write sectors | implemented (`:408`) |
| 04h | verify sectors | **stub** — the body is a `TODO` (`:433-466`) |
| 05h | format track | returns success without writing (`:467`) |
| 08h | drive parameters | implemented (`:476`) |
| 11h | recalibrate drive | implemented (`:506`) |
| 15h | get disk type | implemented (`:510`) |
| 16h, 18h | media change / set media type | **not implemented** (`:558-562`) |
| 17h | set disk type for format | implemented (`:552`) |

The bridge to the disk image is the CHS-to-LBA conversion in
`imageDisk::Read_Sector()`:

```c
sectnum = ( (cylinder * heads + head) * sectors ) + sector - 1L;
```
`src/ints/bios_disk.cpp:153-159`

### INT 16h — the keyboard BIOS

| AH | Function | Status |
|---|---|---|
| 00h | get a keystroke | implemented (`src/ints/bios_keyboard.cpp:502`) |
| 01h | is a keystroke waiting? | implemented (`:523`) |
| 02h | get the shift flags | implemented (`:559`) |
| 03h | set the typematic rate | implemented (`:562`) |
| 05h | store a keystroke in the buffer | implemented (`:573`) |
| 10h | get a keystroke (enhanced) | implemented (`:511`) |
| 11h | is a keystroke waiting? (enhanced) | implemented (`:545`) |
| 12h | get the extended shift states | implemented (`:577`) |

The BIOS data area layout (`include/bios.h:35-42`):

| Address | What |
|---|---|
| 0x417 | flags 1 — the shift and lock state |
| 0x418 | flags 2 — the "pressed" flags |
| 0x496 | flags 3 — the extended-key flags: **bit 1 means the last code was prefixed E0h** |
| 0x419 | the Alt-numlock token |
| 0x41A / 0x41C | the head and tail offsets of the keyboard buffer |
| 0x41E | the buffer itself — 16 words, used as `0x41E+0x1E` … `0x41E+0x3E` |

### INT 21h — the DOS API

The dispatcher is a single `switch (reg_ah)` (`src/dos/dos.cpp:118`) with
**131 case labels** in the tree. The families:

| AH | Family |
|---|---|
| 00h–0Ch | console I/O: terminate, echo-input, output, direct console, buffered input, status, flush |
| 0Dh–17h | the **FCB** interface: open, close, find, delete, sequential read and write, create, rename |
| 19h–20h | the default drive, the DTA, interrupt vectors, the drive parameter block |
| 21h–29h | the FCB's random access: read, write, size, set the random record, block read and write, parse a name |
| 25h–2Fh | create a PSP, the date and the time, the verify flag, the DTA |
| 30h–35h | the DOS version, terminate-and-stay-resident, the drive parameter block, break checking, the INDos flag, interrupt vectors |
| 36h–38h | free disk space, the switch character, the country code |
| 39h–43h | directories and files: MKDIR, RMDIR, CHDIR, CREATE, OPEN, CLOSE, READ, WRITE, UNLINK, LSEEK, attributes |
| 44h–4Bh | IOCTL, DUP, DUP2, the current directory, allocate, free, resize, **EXEC** |
| 4Ch–5Fh | EXIT, the return code, FINDFIRST/FINDNEXT, the PSP, the list of lists, the drive parameter block, the verify flag, the child PSP, RENAME, the file date and time, the allocation strategy, the extended error information, temporary files, FLOCK, the network calls |
| 60h–6Ch | canonicalise a path, the PSP address, the DBCS, the device driver lookahead, the extended country information, the code page, the handle count, FFLUSH, the disk serial number, and **Extended Open/Create** |
| 71h, E0h, EFh, 18h, 1Dh, 1Eh, 20h, 6Bh, 61h, 5Eh | oddities and compatibility shims — the source comments them as "NULL Function for CP/M compatibility", "Weird call used by some dos apps", "Used in Ancient Art Of War CGA" |

Four details worth knowing:

- **The DOS version** is reported by AH=30h (`src/dos/dos.cpp:477-485`). The
  emulator reports **5.0** — `AX = 0x0005` (`src/dos/dos.cpp:1254-1255`) — with
  `AL=0` marking it as a "fake Microsoft DOS" and `AL=1` saying the DOS is in
  HMA.
- **Two calls abort the emulator.** AH=05h (write a character to the printer)
  and AH=53h (translate a BIOS parameter block) call `E_Exit()`
  (`src/dos/dos.cpp:162-164`, `:853-855`) rather than returning an error to the
  guest. A program that calls either takes the whole emulator down with it.
- **A long tail of codes simply has no case label at all**: 6Ah, 6Dh–70h and
  72h–7Fh. The fallback for an unknown AH logs and returns `AL=0`
  (`src/dos/dos.cpp:1153-1156`).
- The handler deliberately does **not** move the stack for four calls:
  `0x50`, `0x51`, `0x62` and `0x64` (`src/dos/dos.cpp:108`), because those are
  the ones a program calls from an already-prepared stack.

#### The bundle launcher rides the stock EXEC path

AH=4Bh (EXEC) and AH=4Ch (EXIT) are **unchanged from DOSBox**
(`src/dos/dos.cpp:798-811`). The port did not add a launcher API — it chose
a filename and let the stock DOS machinery do the work. Each bundle gets a
generated `RUN.BAT` (`android/app/src/main/java/com/whereq/dosbox/GameLibrary.kt:528-558`)
that mounts the drives, changes to the game folder and runs the entry file;
the launcher appends its DOS path to the emulator's `[autoexec]` section
(`android/app/src/main/java/com/whereq/dosbox/WhereqDosboxActivity.kt:1125-1129`).

So the guest path for starting a game is entirely stock:

```
  [autoexec] → COMMAND.COM → INT 21h AH=4Bh (EXEC) of RUN.BAT
                               → INT 21h AH=4Bh (EXEC) of PLAY.BAT
                               → INT 21h AH=4Ch (EXIT) on return
```

The entry point is guessed from a candidate list — `play.bat, run.bat,
start.bat, game.bat, jogar.bat, …`
(`android/app/src/main/java/com/whereq/dosbox/GameLibrary.kt:629-631`).

### INT 29h, INT 2Ah, INT 2Eh, INT 2Fh

**INT 29h** is the fast console path, and it is worth understanding because of
*how* it is fast. Its handler is **NULL**
(`src/dos/dos.cpp:1236`), and `CALLBACK_Setup` is given no C function at all
(`src/cpu/callback.cpp:545`), so no callback hook is emitted. What the guest
reaches is the raw stub:

```
  push ax ; push bx ; mov ah,0x0e ; mov bx,0x0007 ; int 10h ; pop bx ; pop ax ; iret
```
`src/cpu/callback.cpp:385-401`

So **INT 29h is BIOS teletype output**. The character goes straight to INT 10h
AH=0Eh with BL=7, bypassing the DOS handle layer entirely. Compare INT 21h
AH=02h, which routes the byte through `DOS_WriteFile(STDOUT, …)` and pays the
per-character DOS service overhead — including the `loop` delay the `CB_INT21`
stub inserts (`src/cpu/callback.cpp:470-472`). AH=09h writes string by string
through the same DOS path, and so pays the same price.

**INT 2Ah** is the network-redirector probe. A program calls it to find out
whether a network stack is present; if there is none, the call fails and the
program falls back to local I/O. In this tree the handler is a **pure stub**
(`src/dos/dos_misc.cpp:55-57`):

```c
static Bitu INT2A_Handler(void) {
        return CBRET_NONE;
}
```

It touches no register and no flag — not AX, and not the carry. The practical
consequence is that a guest calling INT 2Ah with AX=0000h gets AX back
unchanged, so the `AX == 0` convention reads as "no redirector" — which is
consistent with the rest of the picture, since AH=5Fh network redirection
always fails (`src/dos/dos.cpp:975-978`) and the shell **hijacks INT 2Eh**, the
"install a redirector" call (`src/shell/shell.cpp:706-710`). But a guest that
tests *only* the carry flag reads an undefined value here, so "INT 2Ah reports
no redirector" is too strong a claim to make about this stub.

**INT 2Eh** is the emulator's own command shell. The handler saves the return
address and the current PSP, switches to the first shell's PSP, copies the
command tail, and rebuilds the stack (`src/shell/shell.cpp:503-515`).

**INT 2Fh** is the **multiplex interrupt** — the way DOS let optional
extensions share one vector. `INT2F_Handler` walks a list of registered
handlers and gives each one a chance to claim the call
(`src/dos/dos_misc.cpp:46-52`); if none does, it logs "Multiplex Unhandled
call". The list includes the DOS-specific calls, the **XMS** entry, and the
various CD-ROM and network extensions.

The XMS entry is the cleanest example of the multiplexer protocol. A guest
probes INT 2Fh with `AX=4300h`; if XMS is enabled the handler answers
`AL=0x80` ("present"), otherwise nobody claims the call, AX comes back
unchanged as zero, and the guest concludes "no XMS"
(`src/ints/xms.cpp:250-262`). With `AL=0x80` the guest asks for the entry
point with `AX=4310h`, gets `ES:BX`, and does a **far call** to it — the XMS
entry point is a `CB_HOOKABLE` stub parked in DOS memory
(`src/ints/xms.cpp:430-431`), so the guest could take it over if it wanted to.

### INT 22h, INT 23h, INT 24h — the three per-program vectors

DOS gave every program three vectors of its own, stored in its PSP:

| Vector | What it is | Where |
|---|---|---|
| **22h** | the terminate address — where to return when the program ends | `include/dos_inc.h:319` |
| **23h** | the Ctrl-Break handler | `include/dos_inc.h:320` |
| **24h** | the critical error handler — a disk error, say | `include/dos_inc.h:321` |

They are saved when a PSP is created and restored when the program terminates
(`src/dos/dos_classes.cpp:275-287`). **INT 22h** is set at EXEC time to the
*caller's* CS:IP (`src/dos/dos_execute.cpp:427-429`), and `DOS_Terminate` reads
it back and pushes it onto the restored stack, so the `RETF` in the INT 21h
stub returns to the parent program exactly as it would on real hardware
(`src/dos/dos_execute.cpp:118`, `:128-132`).

The other two have no DOSBox-level handler. The shell points INT 23h at a byte
sequence `CD 20` — INT 20h, which is terminate — written into the PSP
(`src/shell/shell.cpp:703-704`, `src/dos/dos_classes.cpp:191-192`). The shell
chains INT 24h to whatever was there before (`src/shell/shell.cpp:698-701`).

### The vectors that are only there to be looked at

Five BIOS vectors are deliberately hijacked and pointed at a **single dummy
`IRET` byte** at `0070:00F4`:

```c
/* Let dos claim a few bios interrupts. Makes DOSBox more compatible with
 * buggy games, which compare against the interrupt table. (probably a
 * broken linked list implementation) */
real_writeb(ihseg,ihofs,(Bit8u)0xCF);       //An IRET Instruction
RealSetVec(0x01,RealMake(ihseg,ihofs));    //BioMenace (offset!=4)
RealSetVec(0x02,RealMake(ihseg,ihofs));    //BioMenace (segment<0x8000)
RealSetVec(0x03,RealMake(ihseg,ihofs));    //Alien Incident (offset!=0)
RealSetVec(0x04,RealMake(ihseg,ihofs));    //Shadow President (lower byte of segment!=0)
RealSetVec(0x0f,RealMake(ihseg,ihofs));    //Always a tricky one (soundblaster irq)
```
`src/dos/dos_memory.cpp:382-393`

These are **not** anti-virus hooks and they do nothing. They exist because
some 1990s games walk the interrupt vector table and compare entries against
each other — BioMenace checks that INT 01h's offset is not 4, Alien Incident
that INT 03h's offset is not 0, Shadow President that the low byte of INT 04h's
segment is not 0 — and a table of BIOS handlers or zeros fails those tests. A
uniform row of `IRET` stubs passes them. (Note that this takes over INT 02h,
the NMI vector, and INT 0Fh, IRQ 4 / COM1, which a real PC would not give up.)

### INT 33h — the mouse

The service this port spent the most time on. The handler is a `switch
(reg_ax)` (`src/ints/mouse.cpp:843`) with **35 implemented function codes**:

| AX | Function | AX | Function |
|---|---|---|---|
| 00h | reset the driver, read status | 15h | return the driver's storage requirements |
| 01h | show the cursor | 16h | save the driver state |
| 02h | hide the cursor | 17h | restore the driver state |
| **03h** | **return the position and the button state** | 1Ah | set the sensitivity |
| 04h | position the mouse | 1Bh | return the sensitivity |
| 05h | return the button press data | 1Ch | set the interrupt rate *(no-op)* |
| 06h | return the button release data | 1Dh | define the display page |
| 07h | define the horizontal cursor range | 1Eh | return the display page |
| 08h | define the vertical cursor range | 1Fh | disable the mouse driver |
| 09h | define a graphics cursor | 20h | enable the mouse driver |
| 0Ah | define a text cursor | 21h | software reset |
| 0Bh | read the motion counters | 22h | set the language |
| **0Ch** | **define the interrupt subroutine parameters** | 23h | get the language |
| 0Fh | define the mickey/pixel ratio | 24h | get the software version, type and IRQ |
| 10h | define the screen region for updating | 26h | get the maximum virtual coordinates |
| 11h | get the number of buttons | 2Ah | get the cursor hotspot |
| 13h | define the double-speed threshold | 31h | get the current min/max coordinates |
| 14h | exchange the interrupt subroutine | | |

Anything else logs `"Mouse Function %04X not implemented!"` and **leaves AX
unchanged** (`src/ints/mouse.cpp:1099-1102`). Among the gaps: AH=0Dh/0Eh (light
pen), 12h, **18h (the alternate handler API)**, 19h, 25h, 27h–30h, 32h–35h and
everything above 41h.

Three facts about this driver drive the rest of this document:

1. **The button state is a level, not an edge.** `mouse.buttons` is a bitmask —
   bit 0 left, bit 1 right, bit 2 middle (`src/ints/mouse.cpp:82`) — set by
   `Mouse_ButtonPressed()` and cleared by `Mouse_ButtonReleased()`
   (`src/ints/mouse.cpp:640-700`). **AX=03h returns that live level**
   (`src/ints/mouse.cpp:866`). A game that polls AX=03h every frame sees a
   button that is *down*, not a click that happened.
2. **The guest's callback registration lives outside guest memory.**
   `AX=0Ch` stores the event mask and the far address of the game's handler in
   `mouse.sub_mask`, `mouse.sub_seg` and `mouse.sub_ofs`
   (`src/ints/mouse.cpp:968-973`). Those three words are the *only* way the
   driver can tell the game that something happened.
3. **The whole driver state lives in one file-static struct** in the host
   process (`src/ints/mouse.cpp:81-130`), not in the emulated memory. A guest
   that configured the mouse once at boot cannot rebuild that configuration
   after a restore — which is exactly the bug in §6.

### INT 67h — EMS, and INT 2Fh — XMS

**INT 67h** is the expanded memory manager. The page frame is a fixed segment,
`E000:0000` (`EMM_PAGEFRAME 0xE000`, `src/ints/ems.cpp:36`), holding four
16 KB EMS pages. The implemented calls include 40h (get status), **41h (get the
page frame segment — this returns `BX=0xE000`; 40h is the status call, not the
page-frame one)**, 42h–51h (allocation and mapping), 53h/54h (handle names),
57h (move a memory region), 58h (the mappable physical array), 5Ah (allocate
standard pages) and DEh (the VCPI services). Everything else returns
`EMM_FUNC_NOSUP` (`src/ints/ems.cpp:1009`).

**XMS** rides on INT 2Fh as a multiplex handler, and its entry point is a
*hookable* callback parked in writable memory (`src/ints/xms.cpp:430-431`), so
a guest can take it over.

### The rest of the map

- **INT 41h and INT 46h** hold the disk parameter tables — the geometry of the
  fixed disks and the floppies — filled by `updateDPT()`
  (`src/ints/bios_disk.cpp:63-87`).
- **INT 1Fh** points at the second 8×8 font block, the characters 128–255
  (`src/ints/int10_memory.cpp:176`). **INT 43h** holds the graphics font, set
  by INT 10h AH=11h (`src/ints/int10.cpp:237`). **INT 44h** points at the
  8×8 copy in the BIOS ROM at `F000:FA6E`, and only on the Tandy
  (`src/ints/int10_memory.cpp:242-244`).
- **INT 73h** is IRQ 11, the Novell IPX event service routine. The BIOS
  default handler for it is `Default_IRQ_Handler`; the IPX module replaces it
  with a 20-byte hand-assembled real-mode stub
  (`src/hardware/ipx.cpp:1129-1147`).

---

## 4. The hardware interrupts

### IRQ 0 / INT 08h — the timer

The `CB_IRQ0` stub does `STI`, calls the handler, calls **INT 1Ch** (the
user's system-tick hook, a no-op stub here — `src/ints/bios.cpp:574-576`), then
EOI and `IRET` (`src/cpu/callback.cpp:251-254`).

The tick counter is at **0x46C** (`BIOS_TIMER`, `include/bios.h:65`) and is
advanced by `INT8_Handler` (`src/ints/bios.cpp:534-563`), wrapping at
`0x1800B0` — 1 572 528 ticks, which is 24 hours at 18.2 Hz.

### IRQ 1 / INT 09h — the keyboard

The `CB_IRQ1` stub is **hookable**: it reads the scancode from port `0x60` and
offers it to the guest *first* through `INT 15h AH=4Fh`
(`src/cpu/callback.cpp:262-265`). Only if the guest declines — carry clear —
does the emulated handler run. Here the BIOS side of that hook always reports
failure (`src/ints/bios.cpp:755-758`), so DOSBox keeps the scancode.

`IRQ1_Handler` (`src/ints/bios_keyboard.cpp:233-470`) reads the three flag
bytes and the LEDs, runs `DOS_LayoutKey()`, and then walks a scancode switch
that handles the `0xE0`/`0xE1` prefixes, the modifiers, the three lock keys,
the extended keypad and the normal keys.

### The unexpected IRQ

`Default_IRQ_Handler` (`src/ints/bios.cpp:968-988`) handles an IRQ with no
owner, and it does something worth copying: it **masks the offending line**
rather than ignoring it, so one noisy device cannot storm the CPU. It polls
the in-service register with OCW3, masks the line in the master or the slave
IMR, issues the EOIs, and records the raw ISR byte at **0x46B**
(`BIOS_LAST_UNEXPECTED_IRQ`, `include/bios.h:64`). Note that 0x46B is written
but never read back in this tree.

### The PIC

Two 8259s (`src/hardware/pic.cpp:114-116`), with the mask registers at ports
`0x21` (master) and `0xA1` (slave), and the handler block at `0x20`/`0xA0`.
EOI handling is in OCW2 (`src/hardware/pic.cpp:233-249`): a specific EOI
(`0x60+irq`) clears that in-service bit, a non-specific EOI clears the active
one. IRQ 2 is special-cased at dispatch to enter the slave
(`src/hardware/pic.cpp:379-383`).

---

## 5. Case study: a finger becomes a DOS mouse

This is the part of the interrupt layer the port actually rewrote, and it is
worth following end to end because every step is an interrupt-level decision.

### Step 1 — SDL's own conversion is wrong on Android

SDL's Android back end turns touches into synthetic mouse events. On a phone
that fails twice: the reported position is the *system* mouse's, not the
finger's, and the mapping through the letterbox rectangle — `sdl.clip`, which
is DOSBox's own state — is missing entirely. So the conversion is done inside
DOSBox instead, in `android_handle_touch()`
(`src/gui/sdlmain.cpp:2641`), fenced by `#if defined(__ANDROID__)`.

The mapping is the same arithmetic `HandleMouseMotion()` does for a real mouse:

```
  px = finger.x * windowWidth        /* SDL normalises touches to 0..1 */
  py = finger.y * windowHeight
  rx = (px - clip.x) / (clip.w - 1)
  ry = (py - clip.y) / (clip.h - 1)
  Mouse_CursorMoved(0, 0, rx, ry, sdl.mouse.locked)
```

### Step 2 — the relative-mouse trap

DOSBox captures the mouse when it goes fullscreen, which sets
`sdl.mouse.locked`. With that flag set, `Mouse_CursorMoved()` **ignores the
absolute position** and only accumulates relative movement — so on Android every
tap was ignored and the cursor stayed in the middle of the screen. There is no
real mouse to capture on a phone and relative mode has no meaning, so
`GFX_CaptureMouse()` and `GFX_UpdateSDLCaptureState()` keep the mouse
unlocked on Android (`src/gui/sdlmain.cpp:1501`, `:1529`).

The measurement, before and after, is exact (`docs/TECHNICAL-REFERENCE.md` §4.7):

| | tap (700, 300) | tap (1500, 800) | tap (1000, 500) |
|---|---|---|---|
| before | 320, 100 | 320, 100 | 320, 100 |
| after | 97, 55 | 453, 148 | 231, 92 |

`0.153 × 80 × 8 = 97` — the DOS text screen's 80 × 25 cells at 8 pixels each.

### Step 3 — a click is a held button, because AX=03h is a level

This is the interrupt-level insight the port was built on. `mouse.buttons` is a
level, and AX=03h returns it directly (`src/ints/mouse.cpp:866`). A game that
polls AX=03h — 三国志英杰传's battle UI is the example the source comments cite —
sees *whether the button is down*, not a click event. An instantaneous tap, down
and up between two polls, can be missed entirely.

So the first finger presses the LEFT button on `SDL_FINGERDOWN` and releases it
on `SDL_FINGERUP` (`src/gui/sdlmain.cpp:2664-2697`), and `SDL_FINGERMOTION`
moves the cursor **while the button is held**. A tap is a real
held-then-released click; a drag is a real drag.

### Step 4 — the right button

A DOS mouse has two buttons and a touchscreen has one, so there was no way to
send a right click — and in most DOS games the right button is *cancel* or
*back*. Two mechanisms deliver it:

- **A second finger** on the picture. The left button is released first, so the
  two never overlap, and the right button is held until that finger lifts
  (`src/gui/sdlmain.cpp:2672-2678`).
- **The on-screen RMB key**, next to **Tab** on the left pad
  (`android/app/src/main/java/com/whereq/dosbox/VirtualKeyboardView.kt:253-256`).
  It calls `DosboxNative.nativePushMouseButton()`, which pushes a real
  `SDL_MOUSEBUTTONDOWN`/`UP` for `SDL_BUTTON_RIGHT` into DOSBox's event queue
  (`android/jni/dosbox_android.cpp:359-386`); the event flows through
  `GFX_Events()` → `HandleMouseButton()` → `Mouse_ButtonPressed(1)`.

Both are *held* buttons, exactly like the left one. And note the last one is
not a gesture at all: it is a second event type injected into the same SDL
queue the emulator already reads.

### Step 5 — the driver state and the save state

And here the story turns. The INT 33h driver's state lives in a **host
file-static struct**, not in guest memory (`src/ints/mouse.cpp:81-130`). Three
pieces of it cannot be rebuilt by the guest after a restore:

- the event callback registered with **AX=0Ch** — `sub_seg`, `sub_ofs`,
  `sub_mask`;
- the graphics cursor and its hotspot, from **AX=09h**;
- the cursor range, position, visibility and sensitivity.

Without the first, the symptom is specific and nasty: **the cursor still
moves** (DOSBox draws it) **but the game's clicks stop reaching the game** —
a dialog's *confirm* button simply does nothing. Without the second, the
cursor reverts to the default white block drawn with a zero hotspot, so the
*visible* cursor no longer lines up with the real click point
(`POS_X`/`POS_Y`), and taps land up and to the left of the target.

The fix is a snapshot of the driver's **stable configuration** only
(`MouseStateBlock`, `src/ints/mouse.cpp:156-172`). All the volatile run-time
state — the event queue, the button and click counters, the interrupt
re-entrancy flag `in_UIR`, the cursor-draw backing store — is **reset** on
load, never restored.

That distinction was learned the hard way. The first version of the change
saved the whole struct, and restoring the volatile half wedged mouse-driven
games: 三国志英杰传 hung on load, and stopped responding to clicks after a
minute once the queue filled behind a stuck `in_UIR`. The lesson is in the
source (`src/ints/mouse.cpp:134-154`): **the configuration is worth saving;
the run-time state is poison.**

One more rule, and it is the host-pointer rule in miniature: the struct holds
two **host pointers** — `screenMask` and `cursorMask` — to the cursor bitmaps.
They are never written to disk. They are **re-derived** from the saved cursor
type after a load (`src/ints/mouse.cpp:223-229`): a user-defined graphics
cursor (type 2) uses the restored masks, anything else uses the built-in arrow.

---

## 6. Case study: which interrupt state belongs in a save state

A save state is a snapshot of a machine. "A machine" includes its interrupt
subsystem, and the interesting question is not *whether* to snapshot it but
*which parts*.

Three devices on this list were given save-state support by this port.

### The PIC

`PIC_SaveState` / `PIC_LoadState` (`src/hardware/pic.cpp:125-146`) write the
two controller structs wholesale, and then — this is the part that matters —
the loader **keeps the mask but drops the transient state**:

```c
	/* Keep the game's interrupt MASK and configuration, but drop any stale
	 * pending/in-service state: a restored in-service bit would block all
	 * lower-priority IRQs (so the RTC that drives INT 15h/86h, and thus sound
	 * timing, would stall), and a stale request bit would fire a spurious IRQ.
	 * At the top-of-main-loop restore point no IRQ is genuinely mid-service. */
	pics[i].imrr = ~pics[i].imr;
	pics[i].irr = 0;
	pics[i].isr = 0;
	pics[i].isrr = ~pics[i].isr;
	pics[i].active_irq = 8;
```

The mask is the *configuration* — the guest's decision about which interrupts it
wants. The in-service and request registers are *transient* — a leftover
in-service bit wedges the priority chain, and a leftover request fires a
spurious IRQ. The same split as the mouse: keep the configuration, drop the
run-time state.

### The timer

The PIT's channel start times are absolute PIC timestamps. On load they are
shifted by the time that passed between the save and the load, so the BIOS tick
at 0x46C continues seamlessly rather than jumping (`src/hardware/timer.cpp:82+`).

### The mouse

As in §5 — configuration in, run-time state out.

The format version moved as the design moved: **8** was "the whole INT 33h
mouse driver state" (1.0.0.11), **9** was "slimmed to callback + range +
position", and **10** is "cursor shape, hotspot and the rest of the
configuration". A format that grew a version is a format that changed meaning,
and the port changed the meaning twice — deliberately, and with the version
bumped loudly each time, so that an older save is *refused* rather than
misread.

---

## 7. What this port did not change

Worth stating plainly, because it is the more surprising answer: **the BIOS
interrupt layer is upstream code.**

`git log` over the whole of `src/ints/` shows exactly five post-import commits,
and all five are in `mouse.cpp`:

```
ee9d24e Preserve the save time on restore and drop the mouse diagnostic
958ea97 Log the mouse cursor state on every save and load
8ab0ce0 Restore the mouse cursor shape with the save state
52aacb9 Slim the mouse save state to the stable configuration
a39b5f8 Give the DOS mouse a right button, put it in the save state, release 1.0.0.11
```

`int10.cpp` and its eight siblings, `bios.cpp`, `bios_disk.cpp`,
`bios_keyboard.cpp`, `ems.cpp` and `xms.cpp` are untouched since the import.
The port's changes to the interrupt layer are exactly three things:

1. the **PIC** and **PIT** save-state semantics;
2. the **INT 33h / INT 74h** mouse-driver save state;
3. the **touch-to-mouse** conversion and the right-button delivery — which sit
   *above* the BIOS, in `src/gui/sdlmain.cpp` and the Android layer.

The keyboard is a good illustration of the layering. The on-screen keyboard is
implemented entirely above the BIOS: the Android view feeds SDL key events,
`sdlmain.cpp` maps them through DOSBox's mapper, `src/hardware/keyboard.cpp`
writes port `0x60`, IRQ 1 fires, INT 09h runs, the key lands in the BDA
buffer, and INT 16h hands it to the guest. The BIOS layer is byte-for-byte
upstream — the port did not need to touch it.

The DOS layer is the same story. `src/dos/dos.cpp` — the entire INT 21h
handler — `dos_misc.cpp`, `dos_classes.cpp` and `dos_memory.cpp` are
byte-identical to the imported sources. The port's **single** intrusion into
the DOS interrupt layer is one line, in the EXEC path:

```c
SegSet16(ds,pspseg);SegSet16(es,pspseg);
/* WhereQ: a guest program has taken over the machine, so the boot
 * is over and a save-state request may be serviced. The shell
 * itself never reaches its prompt in this case (it is blocked
 * inside the program it started), so this is the only place where
 * the save-state module learns that the guest is running. */
SAVESTATE_GuestReady();
```
`src/dos/dos_execute.cpp:510-515`

It is there because of the save-state machine, not the interrupts: a program
launched by EXEC blocks the shell forever, so this is the only point at which
the emulator can know that a guest is running and a save state may safely be
taken. The DOS block of the snapshot — the open file handles, the current
drive, the code page, the current directories — lives in a new file,
`src/dos/dos_state.cpp`, and contains no interrupt-vector logic at all.

---

## 8. Watching the interrupts yourself

The cheapest way to see this layer work is the **MOUSE** log group
(`src/debug/debug_gui.cpp:251`), enabled from `dosbox.conf`:

```
[log]
logfile=/sdcard/dosbox.log
mouse=true
```

The Android app ships no `[log]` section, so this is off by default. Once it is
on, every INT 33h call that the driver does *not* implement is reported:

```
Mouse Function 0018 not implemented!
```

which is a direct read-out of what a game is asking for and what this driver
does not answer.

Two more traces exist but are **commented out** in the source, and cannot be
enabled without editing the file:

```c
//	LOG(LOG_MOUSE,LOG_NORMAL)("MOUSE: %04X %X %X %d %d",reg_ax,reg_bx,reg_cx,POS_X,POS_Y);
```
`src/ints/mouse.cpp:842` — the AX, BX, CX and the cursor position of **every**
INT 33h call.

```c
//	LOG(LOG_MOUSE,LOG_ERROR)("INT 74 %X",mouse.event_queue[mouse.events].type );
```
`src/ints/mouse.cpp:1183` — the event type the INT 74h path is delivering.

Uncommenting the first of those is the single most useful change a developer
could make to this tree: it turns the mouse driver into a readable trace of
what a game is actually asking for.

---

## See also

- **[TECHNICAL-REFERENCE.md](TECHNICAL-REFERENCE.md)** — the native build, the
  save-state machine, the exact OPL snapshot, the bundle launcher, the
  on-screen keyboard, and the throughput benchmark.
- **[USER-GUIDE.md](USER-GUIDE.md)** — the guide for the person holding the
  phone.
- The DOSBox core is imported from the **[Em-DOSBox](https://github.com/dreamlayers/em-dosbox)**
  fork of DOSBox r4305 by [Boris Gjenero](https://github.com/dreamlayers), and
  is GPL-2.0 with its `AUTHORS`, `ChangeLog`, `COPYING` and per-file headers
  intact. DOSBox is a trademark of the DOSBox project; this port is not
  affiliated with or endorsed by the DOSBox team.
