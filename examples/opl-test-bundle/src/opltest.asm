# ===========================================================================
#  OPLTEST - the WhereQ-DOSBox OPL save-state test
# ===========================================================================
#
#  A tiny 16-bit DOS program that programs one sustained tone on the FM chip
#  (the OPL at port 388h/389h) and then waits for a key.  It exists to test
#  one thing, and it is the reason the exact chip snapshot exists:
#
#  a save state has to restore the chip's LIVE state - the envelope, the
#  operator phase, the LFO - not only the registers.  A driver writes "key
#  on" once and then only "key off"; everything that happens after that
#  lives inside the chip, where no register records it.  Restoring the
#  registers alone re-triggers the note from the attack, so the sound comes
#  back *different* from the one that was saved.
#
#  How to use it:
#
#    1. install this bundle in WhereQ-DOSBox and play it
#    2. let the tone settle (a second or two)
#    3. save a state (Files -> States -> Save)
#    4. wait a few seconds, then load the state again
#
#  The port writes a line to the log on every save and every load:
#
#    savestate: OPL chip saved: ch0.op0 sustain rate=0 phase=... vol=...
#    savestate: OPL chip restored: ch0.op0 sustain rate=0 phase=... vol=...
#
#  The two lines have to be identical.  With the old register replay the
#  restored line says "attack rate=0 phase=80000000" - that is the bug this
#  program makes visible.
#
#  The program itself:
#
#    * programs channel 0 of the FM chip - the modulator on registers
#      20h/40h/60h/80h/E0h and the carrier on 23h/43h/63h/83h/E3h
#    * with an envelope that attacks quickly and then sustains forever
#    * prints what it is doing on the screen
#    * waits for a key and turns the note off on the way out
#
#  The file is a .COM image: DOS loads it at offset 100h of its segment and
#  enters it there, so the image is linked at 100h.  Data is addressed with
#  the "offset" operator, which is relative to the start of the section and
#  therefore to the start of the segment - the same thing for a .COM.
# ===========================================================================

        .intel_syntax noprefix
        .code16
        .text
        .globl _start

# --- the OPL register map -------------------------------------------------
#  The chip has two ports: 388h takes the register number and 389h takes the
#  value.  A value written to 388h only selects the register that the *next*
#  write to 389h goes to - writing both bytes to 388h programs nothing.
        .equ OPL_ADR,   0x388
        .equ OPL_DAT,   0x389

#  Write one register of the FM chip.
        .macro  OPL reg, val
        mov dx, OPL_ADR
        mov al, \reg
        out dx, al
        mov dx, OPL_DAT
        mov al, \val
        out dx, al
        .endm

_start:
        mov dx, offset msg_start
        call print

        # --- the FM chip ---------------------------------------------------
        #  Channel 0, operator 1 (the modulator): MULT=1, envelope on, and
        #  the total level turned all the way down so it only colours the
        #  carrier a little.
        OPL 0x20, 0x31          # AM=0 VIB=0 EGT=1 KSR=1 MULT=1
        OPL 0x40, 0x3f          # KSL=0 TL=3Fh - quiet
        OPL 0x60, 0xf0          # AR=Fh DR=0h - fast attack
        OPL 0x80, 0x77          # SL=7h RR=7h - sustain, slow release
        OPL 0xe0, 0x00          # waveform 0 - the sine

        #  Channel 0, operator 2 (the carrier): the same envelope, but with
        #  the total level at 0 so it is played at full volume.
        OPL 0x23, 0x31          # AM=0 VIB=0 EGT=1 KSR=1 MULT=1
        OPL 0x43, 0x00          # KSL=0 TL=0h - full volume
        OPL 0x63, 0xf0          # AR=Fh DR=0h - fast attack
        OPL 0x83, 0x77          # SL=7h RR=7h - sustain, slow release
        OPL 0xe3, 0x00          # waveform 0 - the sine

        #  The frequency: 399h with block 0 gives a middle C.
        OPL 0xa0, 0x99          # the frequency number, low byte
        OPL 0xb0, 0x23          # KEY ON, block 0, the frequency's high bits

        mov dx, offset msg_playing
        call print

# --- wait for a key -------------------------------------------------------
#
# The keyboard is polled with INT 16h AH=01h, which does not block.  The
# pause between the polls is a delay loop in *guest* code on purpose: a
# program that sits inside the BIOS handler all the time starves the
# save-state request (the port refuses to snapshot the machine while the
# CPU stands in an interrupt handler), and the save would time out.
wait_key:
        mov ah, 0x01
        int 0x16
        jnz leave
        call pause
        jmp wait_key

# --- a pause of roughly 25 ms in guest code -------------------------------
pause:
        push ax
        push cx
        push dx
        mov dx, 20
.pause_outer:
        mov cx, 800
.pause_inner:
        loop .pause_inner
        dec dx
        jnz .pause_outer
        pop dx
        pop cx
        pop ax
        ret

leave:
        # key off: the same B0h register without the KEY-ON bit
        mov dx, OPL_ADR
        mov al, 0xb0
        out dx, al
        mov dx, OPL_DAT
        mov al, 0x03
        out dx, al

        mov dx, offset msg_off
        call print

        # back to DOS
        mov ax, 0x4C00
        int 0x21

# --- print the $-terminated string at DS:DX --------------------------------
#  INT 21h AH=09h prints the string that DS:DX points at, up to the '$'.
print:
        push ax
        push dx
        mov ah, 0x09
        int 0x21
        pop dx
        pop ax
        ret

# --- the messages ---------------------------------------------------------
msg_start:
        .ascii "OPL test: one sustained tone on the FM chip.\r\n"
        .ascii "Save a state, wait, load it - the tone must not restart.\r\n"
        .ascii "\r\n"
        .byte 0x24                 # '$' - the end of the string for INT 21h

msg_playing:
        .ascii "The tone is playing. Press any key to stop it.\r\n"
        .byte 0x24

msg_off:
        .ascii "The note is off. Back to DOS.\r\n"
        .byte 0x24
