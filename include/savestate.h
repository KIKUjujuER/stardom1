/*
 * savestate.h - WhereQ-DOSBox disk save-states (Phase B, milestone B1).
 *
 * NOT part of upstream DOSBox. Snapshots the emulated machine (CPU + guest RAM
 * + paging + VGA) to a file and restores it, so a game can be resumed exactly -
 * even after the Android process was killed or the device rebooted. The
 * implementation lives in src/gui/savestate.cpp; design notes are in the
 * external knowledge base (/home/whereq/git/whereq-dosbox-claude), not the repo.
 *
 * Threading: a request is posted from any thread (the Android UI thread) via
 * SAVESTATE_Request(); the work itself runs on the DOSBox thread from the
 * outermost machine loop (see src/dosbox.cpp) at an instruction boundary
 * (SAVESTATE_RunPending()).
 */

#ifndef DOSBOX_SAVESTATE_H
#define DOSBOX_SAVESTATE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Result codes returned by SAVESTATE_Request(). */
#define SAVESTATE_OK            0
#define SAVESTATE_ERR_IO        1   /* file could not be written / read fully  */
#define SAVESTATE_ERR_FORMAT    2   /* not a save-state, or wrong version      */
#define SAVESTATE_ERR_MISMATCH  3   /* state is for a different machine config */
#define SAVESTATE_ERR_TIMEOUT   4   /* DOSBox thread did not service the request */
#define SAVESTATE_ERR_BUSY      5   /* another request is already in flight    */

/*
 * Posts a save (op == 1) or load (op == 2) request and blocks until the DOSBox
 * thread has serviced it (or a timeout elapses). Safe to call from the UI
 * thread. Returns one of the SAVESTATE_* codes above.
 */
int SAVESTATE_Request(int op, const char *path);

/*
 * Tells the save-state module that the guest is running.
 *
 * DOSBox paints its start-up banner and the autoexec's output through the
 * BIOS, and the BIOS runs the machine loop while it does so - the loop is
 * therefore already running long before the guest program is. Replacing the
 * machine at that point would throw the boot away, so a request is only
 * serviced once the DOS shell has reached its prompt, which is after the
 * autoexec (i.e. after the game has been launched) and before the guest
 * program is executed for the first time.
 *
 * A game that was started by the autoexec never lets the shell reach its
 * prompt (the shell is blocked inside the program), so the module is also
 * told from the other side: as soon as a guest program has been loaded and
 * the CPU has been pointed at it (src/dos/dos_execute.cpp).
 *
 * Called from the DOS shell (src/shell/shell_misc.cpp) and from the DOS
 * kernel (src/dos/dos_execute.cpp).
 */
void SAVESTATE_GuestReady(void);

/*
 * Services a pending request, if any. Must be called on the DOSBox thread at a
 * safe point: the OUTERMOST machine loop, where the CPU is in the guest
 * program at an instruction boundary and no host frame is holding a copy of
 * the registers. (invoked from src/dosbox.cpp).
 */
void SAVESTATE_RunPending(void);

#ifdef __cplusplus
}
#endif

#endif /* DOSBOX_SAVESTATE_H */
