/*
 * savestate.cpp - WhereQ-DOSBox disk save-states (Phase B, milestone B1).
 *
 * NOT part of upstream DOSBox. See include/savestate.h for the contract.
 *
 * What a save-state captures (milestone B1 + the B1.1 corrections):
 *   - the CPU:      cpu_regs, cpu (CPUBlock), Segs
 *   - guest RAM:    the whole MemBase array
 *   - paging regs:  cr3 / cr2 / base / enabled (the page tables live in RAM)
 *   - the VGA:      the whole `vga` struct plus the video memory
 *   - the FPU:      the x87 register file and control word
 *   - the PC speaker
 *   - hardware timing: the two PIC controllers and the three PIT channels
 *   - the clock:    the PIC timestamp the state was taken at
 *   - the paging map of the first megabyte (paging.firstmb)
 *   - the DOS layer: the open file handles, the current drive, the loaded
 *                    code page and the current directory of every drive
 *
 * This is deliberately a *machine snapshot*: restoring it replaces the running
 * machine wholesale, so a state can be loaded into a freshly-booted DOSBox
 * sitting at the C:\> prompt and the machine simply becomes the saved game
 * (C: is always mounted the same way by the app). The open DOS file handles
 * are part of it as well (milestone B2): the guest's file table stores the
 * *index* of a handle in DOSBox's global file table, so that table has to be
 * rebuilt in the same order - see src/dos/dos_state.cpp.
 *
 * Correctness notes:
 *   - The `vga` struct holds host pointers (mem.linear, fastmem, draw bases,
 *     font_tables, lfb.handler). Those are process-local allocations, so on
 *     load we keep the *live* pointers and only restore the register/content
 *     state, then re-run the VGA setup to regenerate all derived draw state.
 *   - The same is true for `cpu.hlt.old_decoder`, a *function* pointer into
 *     the CPU core: it is meaningless in another process, so the live value is
 *     kept instead of the one from the file.
 *   - After replacing RAM we flush the paging TLB and the dynrec code cache so
 *     no stale translation of now-overwritten memory is ever executed.
 *   - Lazy CPU flags are materialised before saving and invalidated on load so
 *     reg_flags is authoritative across the snapshot.
 *   - The PIT keeps its counters in *absolute* PIC timestamps. Those are
 *     shifted by the time that passed between the save and the load, so the
 *     counter phase (and therefore IRQ0) continues where it left off instead
 *     of restarting at zero.
 *   - paging.firstmb is the host's linear->physical map of the first megabyte.
 *     While paging is off DOSBox uses it instead of the guest's page tables,
 *     and the EMS page frame is mapped through it. It is host state, so it
 *     has to travel with the snapshot.
 *
 * The actual save/load runs on the DOSBox thread at an instruction boundary
 * (the outermost DOSBOX_RunMachine() loop); the Android UI thread only posts
 * the request. See src/dosbox.cpp for why only the outermost loop is safe.
 *
 * A request is only serviced when the machine is in a state where replacing it
 * is safe:
 *   - the guest has to be running: during the boot the machine loop already
 *     runs while DOSBox paints its start-up banner, and replacing the machine
 *     there would throw the boot away (SAVESTATE_GuestReady()),
 *   - the video output has to have settled: a restore rebuilds the whole
 *     picture, and on Android the device is rotated into place a moment after
 *     the boot - a picture drawn before that is thrown away by the resize that
 *     follows (GFX_VideoReady()),
 *   - the CPU must not stand in one of DOSBox's own interrupt stubs, where an
 *     IRET is about to pop shadow registers from the stack that a restore
 *     would replace (cpu_in_interrupt_stub()).
 */

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <condition_variable>
#include <chrono>

#include "dosbox.h"
#include "logging.h"
#include "mem.h"
#include "regs.h"
#include "cpu.h"
#include "paging.h"
#include "vga.h"
#include "fpu.h"
#include "pic.h"
#include "callback.h"
#include "dos_inc.h"
#include "video.h"
#include "../cpu/lazyflags.h"
#include "savestate.h"

/* Defined in src/cpu/core_dynrec.cpp (compiled with C_DYNREC on Android):
 * drops every translated block so stale code is never executed after a load. */
extern void CPU_Core_Dynrec_Cache_Reset(void);

/* Defined in src/hardware/vga_dac.cpp: re-push the restored DAC palette to the
 * renderer (there is no stock "set entire palette" entry point). */
extern void VGA_DAC_UpdateAllColors(void);

/* Defined in src/hardware/timer.cpp and src/hardware/pic.cpp: the PIT channels
 * and PIC controllers are file-static, so each exposes its own (de)serialiser.
 * TIMER_LoadState also re-bases the channel clocks and re-arms IRQ0.
 * clock_saved is the PIC timestamp of the save; the absolute PIT timestamps
 * are shifted by the time that passed since then. */
extern size_t TIMER_StateSize(void);
extern bool   TIMER_SaveState(FILE *f);
extern bool   TIMER_LoadState(FILE *f, double clock_saved);
extern size_t PIC_StateSize(void);
extern bool   PIC_SaveState(FILE *f);
extern bool   PIC_LoadState(FILE *f);

/* Defined in src/hardware/pcspeaker.cpp: the speaker is file-static as well. */
extern size_t SPKR_StateSize(void);
extern bool   SPKR_SaveState(FILE *f);
extern bool   SPKR_LoadState(FILE *f);

/* ----------------------------------------------------------------- format */

static const char   SS_MAGIC[4] = { 'W', 'Q', 'S', 'S' };
static const uint32_t SS_VERSION = 5;   /* 5: + the open DOS file handles */

/* Fixed header; everything after it is a fixed-order sequence of raw blocks.
 * The config fields double as a guard: a state can only be loaded into a
 * machine with the same memory size / adapter / video-memory size. */
struct SSHeader {
	char     magic[4];
	uint32_t version;
	uint32_t machine;    /* (uint32_t)::machine        */
	uint32_t mem_pages;  /* MEM_TotalPages()           */
	uint32_t vmemsize;   /* vga.vmemsize               */
	uint32_t svga_card;  /* ::svgaCard                 */
};

/* Byte size of the paging block, written field-by-field below. */
static size_t paging_blob_size() {
	return sizeof(paging.cr3) + sizeof(paging.cr2) +
	       sizeof(paging.base.page) + sizeof(paging.base.addr) +
	       sizeof(paging.enabled) +
	       sizeof(paging.firstmb);
}

static size_t expected_payload_size(uint32_t mem_pages, uint32_t vmemsize) {
	return sizeof(cpu_regs) + sizeof(cpu) + sizeof(Segs) +
	       paging_blob_size() +
	       (size_t)mem_pages * 4096u +
	       sizeof(vga) +
	       (size_t)vmemsize +              /* vga.mem.linear           */
	       (size_t)vmemsize * 2u +          /* vga.fastmem (chain4)     */
	       sizeof(double) +                 /* the PIC clock           */
	       PIC_StateSize() + TIMER_StateSize() +
	       sizeof(fpu) +
	       SPKR_StateSize() +
	       DOS_StateSize();                  /* the DOS layer           */
}

/* --------------------------------------------------------------- helpers */

static bool wr(FILE *f, const void *p, size_t n) {
	return fwrite(p, 1, n, f) == n;
}
static bool rd(FILE *f, void *p, size_t n) {
	return fread(p, 1, n, f) == n;
}

/* ------------------------------------------------------------ fingerprint */

/* A one-line summary of the machine state, written on every save and on
 * every load. The two lines have to be identical when a snapshot is loaded
 * back: that is the proof that the restore brought the machine back and not
 * merely "something similar".
 *
 * The two checksums are the interesting part: the first covers the
 * descriptor table (so a mangled GDT shows up even when the base and limit
 * look right), the second the top of the current stack (so a stack that was
 * restored into the wrong place shows up too). */
static void log_fingerprint(const char *tag) {
	const Bit32u fnv = 16777619u;
	Bit32u gdt_sum = 0x811c9dc5, stack_sum = 0x811c9dc5;
	Bitu gdt_base = cpu.gdt.GetBase();
	Bitu gdt_limit = cpu.gdt.GetLimit();
	Bitu sp_off = reg_esp & cpu.stack.mask;
	Bitu i;

	for (i = 0; i < gdt_limit && i < 4096; i++)
		gdt_sum = (gdt_sum ^ mem_readb(gdt_base + i)) * fnv;
	for (i = 0; i < 256; i++)
		stack_sum = (stack_sum ^ mem_readb(SegPhys(ss) +
		                                  ((sp_off + i) & cpu.stack.mask))) * fnv;

	LOG_MSG("savestate: %s cs:%04x/%08x ip:%08x sp:%08x ss:%04x/%08x ds:%04x es:%04x "
	        "fs:%04x gs:%04x flags:%08x cpl:%u pmode:%d cr0:%08x cr3:%08x paging:%d "
	        "gdt:%08x/%08x idt:%08x/%08x ldt:%04x gdtck:%08x stckck:%08x",
	        tag,
	        (unsigned)SegValue(cs), (unsigned)SegPhys(cs),
	        (unsigned)reg_eip, (unsigned)reg_esp,
	        (unsigned)SegValue(ss), (unsigned)SegPhys(ss),
	        (unsigned)SegValue(ds), (unsigned)SegValue(es),
	        (unsigned)SegValue(fs), (unsigned)SegValue(gs),
	        (unsigned)reg_flags, (unsigned)cpu.cpl, (int)cpu.pmode,
	        (unsigned)cpu.cr0, (unsigned)paging.cr3, (int)paging.enabled,
	        (unsigned)gdt_base, (unsigned)gdt_limit,
	        (unsigned)cpu.idt.GetBase(), (unsigned)cpu.idt.GetLimit(),
	        (unsigned)cpu.gdt.SLDT(),
	        (unsigned)gdt_sum, (unsigned)stack_sum);
}

/* ------------------------------------------------------------------ safety */

/*
 * Whether the CPU is standing in one of DOSBox's own interrupt stubs.
 *
 * Those stubs (src/cpu/callback.cpp) are the "extra callback" instruction
 * (FE 38) followed by an IRET: the host handler runs *inside* the FE 38
 * instruction, and the IRET that follows pops the shadow registers the
 * interrupt pushed. A restore that lands between the two would replace the
 * stack the IRET pops from. The "real interrupt" stubs are worse: they stop
 * the emulated machine right after the handler returned, so a restore there
 * would end the program that was just restored.
 *
 * Both stub areas live in the F000 segment, between CB_SOFFSET and the end
 * of the real-interrupt table. The BIOS' own code is outside of it - the
 * stubs overwrote that part of the ROM image when DOSBox was started.
 */
static bool cpu_in_interrupt_stub(void) {
	if (SegValue(cs) != CB_SEG) return false;
	const Bitu ip = reg_eip;
	return (ip >= CB_SOFFSET) &&
	       (ip < CB_SOFFSET + (Bitu)CB_MAX * CB_SIZE + 0x100 * 6);
}

/* ------------------------------------------------------------------ save */

static int do_save(const char *path) {
	/* Make reg_flags authoritative before we snapshot it. */
	FillFlags();

	FILE *f = fopen(path, "wb");
	if (!f) {
		LOG_MSG("savestate: cannot open %s for writing", path);
		return SAVESTATE_ERR_IO;
	}

	SSHeader h;
	memcpy(h.magic, SS_MAGIC, 4);
	h.version   = SS_VERSION;
	h.machine   = (uint32_t)machine;
	h.mem_pages = (uint32_t)MEM_TotalPages();
	h.vmemsize  = (uint32_t)vga.vmemsize;
	h.svga_card = (uint32_t)svgaCard;

	bool ok = wr(f, &h, sizeof(h));
	/* CPU */
	ok = ok && wr(f, &cpu_regs, sizeof(cpu_regs));
	ok = ok && wr(f, &cpu,      sizeof(cpu));
	ok = ok && wr(f, &Segs,     sizeof(Segs));
	/* paging registers (page tables themselves live in guest RAM) */
	ok = ok && wr(f, &paging.cr3,       sizeof(paging.cr3));
	ok = ok && wr(f, &paging.cr2,       sizeof(paging.cr2));
	ok = ok && wr(f, &paging.base.page, sizeof(paging.base.page));
	ok = ok && wr(f, &paging.base.addr, sizeof(paging.base.addr));
	ok = ok && wr(f, &paging.enabled,   sizeof(paging.enabled));
	/* The host's linear->physical map of the first megabyte. While paging is
	 * off DOSBox resolves the first megabyte through this array instead of
	 * the guest's page tables, and the EMS page frame is mapped through it -
	 * so it is part of the machine, not of the host. */
	ok = ok && wr(f, paging.firstmb, sizeof(paging.firstmb));
	/* guest RAM */
	ok = ok && wr(f, GetMemBase(), (size_t)h.mem_pages * 4096u);
	/* VGA registers + video memory */
	ok = ok && wr(f, &vga, sizeof(vga));
	if (ok && vga.mem.linear && h.vmemsize)
		ok = ok && wr(f, vga.mem.linear, h.vmemsize);
	/* The "fastmem" mirror is what the EGA/LIN4 modes are drawn from; without
	 * it those modes come back as garbage after a load. */
	if (ok && vga.fastmem && h.vmemsize)
		ok = ok && wr(f, vga.fastmem, (size_t)h.vmemsize * 2u);
	/* The clock, so the PIT phase can be continued on load. */
	double clock = PIC_FullIndex();
	ok = ok && wr(f, &clock, sizeof(clock));
	/* hardware timing: PIC controllers, then the PIT channels */
	ok = ok && PIC_SaveState(f);
	ok = ok && TIMER_SaveState(f);
	/* the x87 state and the PC speaker */
	ok = ok && wr(f, &fpu, sizeof(fpu));
	ok = ok && SPKR_SaveState(f);
	/* the DOS layer: the open file handles and the rest of its state */
	ok = ok && DOS_SaveState(f);

	fclose(f);
	if (!ok) {
		LOG_MSG("savestate: write failed, removing %s", path);
		remove(path);
		return SAVESTATE_ERR_IO;
	}
	LOG_MSG("savestate: saved %s (cs:%04x ip:%08x mode %d)",
	        path, (unsigned)SegValue(cs), (unsigned)reg_eip, (int)vga.mode);
	log_fingerprint("saved");
	return SAVESTATE_OK;
}

/* ------------------------------------------------------------------ load */

static int do_load(const char *path) {
	FILE *f = fopen(path, "rb");
	if (!f) {
		LOG_MSG("savestate: cannot open %s for reading", path);
		return SAVESTATE_ERR_IO;
	}

	SSHeader h;
	if (!rd(f, &h, sizeof(h)) ||
	    memcmp(h.magic, SS_MAGIC, 4) != 0 || h.version != SS_VERSION) {
		fclose(f);
		return SAVESTATE_ERR_FORMAT;
	}
	if (h.machine   != (uint32_t)machine ||
	    h.mem_pages != (uint32_t)MEM_TotalPages() ||
	    h.vmemsize  != (uint32_t)vga.vmemsize ||
	    h.svga_card != (uint32_t)svgaCard) {
		LOG_MSG("savestate: config mismatch (state m=%u pages=%u vmem=%u card=%u)",
		        h.machine, h.mem_pages, h.vmemsize, h.svga_card);
		fclose(f);
		return SAVESTATE_ERR_MISMATCH;
	}
	/* Validate the whole payload length *before* touching any live state, so a
	 * truncated file is rejected instead of half-restored. */
	long pos = ftell(f);
	fseek(f, 0, SEEK_END);
	long end = ftell(f);
	fseek(f, pos, SEEK_SET);
	if (pos < 0 || end < 0 ||
	    (size_t)(end - pos) != expected_payload_size(h.mem_pages, h.vmemsize)) {
		LOG_MSG("savestate: payload size mismatch, refusing load");
		fclose(f);
		return SAVESTATE_ERR_FORMAT;
	}

	int rc = SAVESTATE_OK;

	/* CPU. cpu.hlt.old_decoder is a function pointer into the CPU core: it is
	 * meaningless in another process, so the live value is kept. */
	CPU_Decoder *l_old_decoder = cpu.hlt.old_decoder;
	if (!rd(f, &cpu_regs, sizeof(cpu_regs)) ||
	    !rd(f, &cpu,      sizeof(cpu)) ||
	    !rd(f, &Segs,     sizeof(Segs)))
		rc = SAVESTATE_ERR_IO;
	else
		cpu.hlt.old_decoder = l_old_decoder;

	/* paging registers into temporaries (applied after RAM is in place) */
	Bitu   p_cr3 = 0, p_cr2 = 0, p_base_page = 0;
	PhysPt p_base_addr = 0;
	bool   p_enabled = false;
	Bit32u p_firstmb[LINK_START];
	memcpy(p_firstmb, paging.firstmb, sizeof(p_firstmb));
	if (rc == SAVESTATE_OK) {
		if (!rd(f, &p_cr3,       sizeof(paging.cr3)) ||
		    !rd(f, &p_cr2,       sizeof(paging.cr2)) ||
		    !rd(f, &p_base_page, sizeof(paging.base.page)) ||
		    !rd(f, &p_base_addr, sizeof(paging.base.addr)) ||
		    !rd(f, &p_enabled,   sizeof(paging.enabled)) ||
		    !rd(f, p_firstmb,    sizeof(p_firstmb)))
			rc = SAVESTATE_ERR_IO;
	}

	/* guest RAM */
	if (rc == SAVESTATE_OK &&
	    !rd(f, GetMemBase(), (size_t)h.mem_pages * 4096u))
		rc = SAVESTATE_ERR_IO;

	/* VGA: restore registers + video memory but keep the live host pointers */
	if (rc == SAVESTATE_OK) {
		Bit8u *l_linear    = vga.mem.linear;
		Bit8u *l_linearorg = vga.mem.linear_orgptr;
		Bit8u *l_fastmem   = vga.fastmem;
		Bit8u *l_fastorg   = vga.fastmem_orgptr;
		Bit8u *l_drawbase  = vga.draw.linear_base;
		Bit8u *l_ft0       = vga.draw.font_tables[0];
		Bit8u *l_ft1       = vga.draw.font_tables[1];
		Bit8u *l_tdraw     = vga.tandy.draw_base;
		Bit8u *l_tmem      = vga.tandy.mem_base;
		PageHandler *l_lfb = vga.lfb.handler;

		if (!rd(f, &vga, sizeof(vga))) {
			rc = SAVESTATE_ERR_IO;
		} else {
			vga.mem.linear         = l_linear;
			vga.mem.linear_orgptr  = l_linearorg;
			vga.fastmem            = l_fastmem;
			vga.fastmem_orgptr     = l_fastorg;
			vga.draw.linear_base   = l_drawbase;
			vga.draw.font_tables[0] = l_ft0;
			vga.draw.font_tables[1] = l_ft1;
			vga.tandy.draw_base    = l_tdraw;
			vga.tandy.mem_base     = l_tmem;
			vga.lfb.handler        = l_lfb;

			if (vga.mem.linear && h.vmemsize &&
			    !rd(f, vga.mem.linear, h.vmemsize))
				rc = SAVESTATE_ERR_IO;
			if (rc == SAVESTATE_OK && vga.fastmem && h.vmemsize &&
			    !rd(f, vga.fastmem, (size_t)h.vmemsize * 2u))
				rc = SAVESTATE_ERR_IO;
		}
	}

	/* the clock of the save, so the PIT phase can be continued */
	double clock_saved = 0;
	if (rc == SAVESTATE_OK && !rd(f, &clock_saved, sizeof(clock_saved)))
		rc = SAVESTATE_ERR_IO;

	/* hardware timing (same order as save): PIC controllers, then the PIT -
	 * TIMER_LoadState re-bases the channel clocks and re-arms IRQ0. */
	if (rc == SAVESTATE_OK &&
	    (!PIC_LoadState(f) || !TIMER_LoadState(f, clock_saved)))
		rc = SAVESTATE_ERR_IO;

	/* the x87 state and the PC speaker */
	if (rc == SAVESTATE_OK && !rd(f, &fpu, sizeof(fpu)))
		rc = SAVESTATE_ERR_IO;
	if (rc == SAVESTATE_OK && !SPKR_LoadState(f))
		rc = SAVESTATE_ERR_IO;
	/* the DOS layer: the open file handles and the rest of its state.
	 *
	 * This comes last on purpose: the handles are re-opened through their
	 * drive, and the drive only knows the current directory of the *saved*
	 * machine once the DOS block has been applied. */
	if (rc == SAVESTATE_OK && !DOS_LoadState(f))
		rc = SAVESTATE_ERR_IO;

	fclose(f);
	if (rc != SAVESTATE_OK) {
		LOG_MSG("savestate: load of %s failed (%d) - machine may be unstable",
		        path, rc);
		return rc;
	}

	/* ---- re-assert derived state so the restored machine actually runs ---- */

	/* reg_flags is now the truth; drop any pending lazy-flag computation. */
	lflags.type = t_UNKNOWN;

	/* Re-apply paging from the restored registers (tables are already in RAM). */
	paging.cr2 = p_cr2;
	paging.base.page = p_base_page;
	paging.base.addr = p_base_addr;
	memcpy(paging.firstmb, p_firstmb, sizeof(paging.firstmb));
	PAGING_SetDirBase(p_cr3);
	PAGING_Enable(p_enabled);
	/* The map of the first megabyte just changed, so every cached
	 * translation of it is stale. */
	PAGING_ClearTLB();

	/* Drop all translated code: it belongs to the memory we just replaced. */
	CPU_Core_Dynrec_Cache_Reset();

	/* Rebuild the VGA pipeline for the restored registers.
	 *
	 * Two things have to be forced here:
	 *
	 * 1. vga.draw.resizing is part of the `vga` struct, so it was restored from
	 *    the file as well - and VGA_StartResize() does nothing at all while it
	 *    is set. That silently skipped the whole re-derivation.
	 *
	 * 2. VGA_SetupDrawing() only rebuilds the output window when the new
	 *    dimensions differ from vga.draw.width/height/... - and those come
	 *    from the file too, so they *matched* and the resize was skipped as
	 *    well. The renderer then kept the old mode (the screen stayed stale or
	 *    black) and the half-drawn frame of the old mode was still in flight:
	 *    its pending draw event fired with the new state and wrote through a
	 *    stale output pointer, which killed the app with SIGSEGV.
	 *
	 * Clearing the derived values makes VGA_SetupDrawing() take its full path:
	 * VGA_KillDrawing() drops the stale draw events and RENDER_SetSize() puts
	 * the renderer into the restored mode. */
	PIC_RemoveEvents(VGA_SetupDrawing);
	vga.draw.resizing = false;
	vga.draw.width = 0;
	vga.draw.height = 0;
	VGA_DetermineMode();
	VGA_SetupHandlers();
	VGA_SetupDrawing(0);
	/* The vertical retrace is a PIC event and the PIC event queue is not part
	 * of the snapshot, so after a restore nothing asks the VGA for the next
	 * line any more: the guest keeps running but the picture freezes. Re-arm
	 * the timer so the screen updates again. */
	VGA_RestartFrameTimer();
	/* ... and push the restored palette to the renderer. */
	VGA_DAC_UpdateAllColors();

	LOG_MSG("savestate: loaded %s (cs:%04x ip:%08x mode %d)",
	        path, (unsigned)SegValue(cs), (unsigned)reg_eip, (int)vga.mode);
	log_fingerprint("loaded");
	return SAVESTATE_OK;
}

/* --------------------------------------------------------- thread plumbing */

static std::mutex              g_mtx;
static std::condition_variable g_cv;
static int   g_op = 0;          /* 0 = none, 1 = save, 2 = load */
static char  g_path[4096];
static bool  g_done = false;
static int   g_result = SAVESTATE_OK;

/* Whether the guest is running (see SAVESTATE_GuestReady). Only the DOSBox
 * thread touches it, and it does so from the same thread that runs the shell,
 * so no lock is needed. */
static bool  g_guest_ready = false;

void SAVESTATE_GuestReady(void) {
	g_guest_ready = true;
}

int SAVESTATE_Request(int op, const char *path) {
	if (!path || (op != 1 && op != 2)) return SAVESTATE_ERR_IO;

	std::unique_lock<std::mutex> lk(g_mtx);
	if (g_op != 0) return SAVESTATE_ERR_BUSY;

	strncpy(g_path, path, sizeof(g_path) - 1);
	g_path[sizeof(g_path) - 1] = '\0';
	g_done = false;
	g_result = SAVESTATE_OK;
	g_op = op;

	/* The DOSBox thread services the request from the outermost machine loop.
	 *
	 * A save has to happen right away - the machine is running - so 5 s is a
	 * generous ceiling that still fails fast if the thread is wedged. A load
	 * may have to wait for the guest to start at all (the request is only
	 * serviced once the shell has reached its prompt), so it gets much more
	 * time. */
	const std::chrono::seconds timeout = (op == 2) ? std::chrono::seconds(30)
	                                                : std::chrono::seconds(5);
	bool serviced = g_cv.wait_for(lk, timeout, [] { return g_done; });
	g_op = 0;
	if (!serviced) return SAVESTATE_ERR_TIMEOUT;
	return g_result;
}

void SAVESTATE_RunPending(void) {
	int  op;
	char path[sizeof(g_path)];
	{
		std::lock_guard<std::mutex> lk(g_mtx);
		if (g_op == 0 || g_done) return;
		/* The machine is only replaced once the guest is running: before
		 * that the loop is already running while DOSBox paints its
		 * start-up banner, and a restore there would throw the boot
		 * away. The request simply waits until the shell has reached its
		 * prompt. */
		if (!g_guest_ready) return;
		op = g_op;
		memcpy(path, g_path, sizeof(path));
	}

	/* A restore also rebuilds the picture: the VGA is re-derived and the
	 * renderer gets a new texture. On Android the device is rotated into
	 * place a moment after the boot, and until that has settled the
	 * picture is scaled for the old orientation - the resize that follows
	 * replaces the texture, so a machine restored in that window was drawn
	 * for the wrong size and then thrown away, which left the screen
	 * black. The restore therefore waits for the output to settle.
	 *
	 * A save touches no video state, so it is not delayed. */
	if (op == 2 && !GFX_VideoReady()) return;

	/* Never touch the machine while the CPU stands in one of DOSBox's own
	 * interrupt stubs (see cpu_in_interrupt_stub). */
	if (cpu_in_interrupt_stub()) return;

	int rc = (op == 1) ? do_save(path) : do_load(path);

	{
		std::lock_guard<std::mutex> lk(g_mtx);
		g_result = rc;
		g_done = true;
	}
	g_cv.notify_all();
}
