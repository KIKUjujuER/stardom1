OPLTEST - the WhereQ-DOSBox OPL save-state test
================================================

A tiny 16-bit DOS program that programs one sustained tone on the FM chip
(the OPL at port 388h/389h) and then waits for a key.

It exists to test one thing: a save state has to restore the chip's LIVE
state - the envelope, the operator phase, the LFO - and not only the
registers.  A driver writes "key on" once and then only "key off", so
everything that happens after that lives inside the chip, where no register
records it.  Restoring the registers alone re-triggers the note from the
attack, and the sound comes back different from the one that was saved.

How to use it:

  1. install this bundle in WhereQ-DOSBox and play it
  2. let the tone settle (a second or two)
  3. save a state (Files -> States -> Save)
  4. wait a few seconds, then load the state again

The port writes a line to the log on every save and every load:

  savestate: OPL chip saved:    ch0.op0 sustain rate=0 phase=... vol=...
  savestate: OPL chip restored: ch0.op0 sustain rate=0 phase=... vol=...

The two lines have to be identical.  With the old register replay the
restored line says "attack rate=0 phase=80000000" - that is the bug this
program makes visible.

The whole program is about 40 lines of assembly.  The source is
examples/game-bundle-style 16-bit x86:

      as --32 -o opltest.o opltest.asm
      ld -m elf_i386 --oformat binary -Ttext 0x100 -e _start -o OPLTEST.COM

Press any key to stop the tone and return to the DOS prompt.

Have a look at whereq.com for WhereQ-DOSBox itself.
