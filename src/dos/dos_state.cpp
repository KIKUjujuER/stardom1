/*
 *  dos_state.cpp - A-DOS: the DOS layer as part of a save-state.
 *
 *  NOT part of upstream DOSBox. See include/savestate.h for the contract and
 *  docs/TECHNICAL-REFERENCE.md for the design notes.
 *
 *  Milestone B2: the open DOS file handles.
 *
 *  DOSBox keeps the open files in one global table, Files[] (dos_files.cpp).
 *  A handle the guest gets from INT 21h is *not* a pointer: the guest's Job
 *  File Table (which lives in the PSP, i.e. in the guest's memory) stores the
 *  *index* of the entry in that table. The table therefore has to be rebuilt
 *  in exactly the same order after a restore - otherwise the guest reads
 *  through a handle that points at nothing (DOS reports "invalid handle",
 *  error 6) or at somebody else's file.
 *
 *  Per handle we store:
 *    - what it is: an empty slot, a device or a file,
 *    - the DOS name of the file (the drive-relative path DOSBox built it from),
 *    - the drive it lives on,
 *    - the open mode (DOS_File::flags),
 *    - the position in the file,
 *    - the reference count (a duplicated handle shares one entry),
 *    - time, date and attributes.
 *
 *  A file is restored by *re-opening* it through its drive - exactly the way
 *  DOS_OpenFile() did it when the guest opened it - and then putting the file
 *  pointer back where it was. A device is restored from the device table.
 *
 *  Besides the handles the DOS layer keeps a few pieces of state that are not
 *  in the guest's memory either: the current drive, the loaded code page and
 *  the current directory of every mounted drive. They travel with the state
 *  as well.
 *
 *  Threading: this runs on the DOSBox thread, at the same safe point as the
 *  rest of the snapshot (see src/gui/savestate.cpp). The machine loop runs
 *  inside the DOS shell's wait for a key, so there is no second thread that
 *  could touch the file table while it is rebuilt.
 */

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include "dosbox.h"
#include "dos_inc.h"
#include "dos_system.h"
#include "drives.h"
#include "logging.h"

/* One handle record.
 *
 * Every member is 32 bit wide and the name is padded to a multiple of four, so
 * the record has no padding at all and the whole block has a fixed size. That
 * way a truncated file can be rejected before any live state is touched. */
struct DOSStateHandle {
	uint32_t kind;     /* 0 = empty slot, 1 = device, 2 = file */
	uint32_t flags;    /* DOS_File::flags: the open mode + the inherit flag */
	uint32_t time;
	uint32_t date;
	uint32_t attr;
	uint32_t refCtr;   /* a duplicated handle shares one entry */
	uint32_t pos;      /* the position in the file */
	uint32_t devnum;   /* the device number (kind == 1) */
	uint32_t hdrive;   /* the drive the handle belongs to */
	char     name[DOS_PATHLENGTH];
};

#define DOSSTATE_HANDLE_SIZE (9 * 4 + DOS_PATHLENGTH)

/* The record must not have any padding, otherwise the size of the block in
 * the state file would depend on the compiler. */
static_assert(sizeof(DOSStateHandle) == DOSSTATE_HANDLE_SIZE,
              "DOSStateHandle must not have any padding");

/* The size of the DOS block in the state file. */
size_t DOS_StateSize(void) {
	return 2 * 4                                   /* drive, code page     */
	     + (size_t)DOS_DRIVES * DOS_PATHLENGTH      /* the current dirs    */
	     + (size_t)DOS_FILES * DOSSTATE_HANDLE_SIZE;/* the open handles    */
}

/* ------------------------------------------------------------------ helpers */

static bool wr(FILE *f, const void *p, size_t n) {
	return fwrite(p, 1, n, f) == n;
}

static bool rd(FILE *f, void *p, size_t n) {
	return fread(p, 1, n, f) == n;
}

/* Handles that were open when a state was loaded.
 *
 * They are not deleted right away: the machine loop is entered from the DOS
 * shell's wait for a key, so a read on the console device may still be in
 * flight while the table is rebuilt. The wrapper object is not touched again
 * once the call has been dispatched into the device, but keeping it around
 * until the next save or load costs nothing and removes the question
 * entirely. */
static std::vector<DOS_File *> retired_handles;

static void retire_handle(DOS_File *file) {
	if (file) retired_handles.push_back(file);
}

static void drop_retired_handles(void) {
	for (size_t i = 0; i < retired_handles.size(); i++)
		delete retired_handles[i];
	retired_handles.clear();
}

/* --------------------------------------------------------------------- save */

bool DOS_SaveState(FILE *f) {
	drop_retired_handles();

	/* Flush the write buffers of the open files first: the snapshot has to
	 * contain what the guest has written so far. */
	for (Bitu i = 0; i < DOS_FILES; i++) {
		if (!Files[i]) continue;
		localFile *lf = dynamic_cast<localFile *>(Files[i]);
		if (lf) lf->Flush();
	}

	/* the current drive and the loaded code page */
	uint32_t cur_drive = dos.current_drive;
	uint32_t codepage  = dos.loaded_codepage;
	if (!wr(f, &cur_drive, sizeof(cur_drive))) return false;
	if (!wr(f, &codepage, sizeof(codepage))) return false;

	/* the current directory of every drive */
	for (Bitu d = 0; d < DOS_DRIVES; d++) {
		char dir[DOS_PATHLENGTH];
		memset(dir, 0, sizeof(dir));
		if (Drives[d])
			strncpy(dir, Drives[d]->curdir, sizeof(dir) - 1);
		if (!wr(f, dir, sizeof(dir))) return false;
	}

	/* the open file handles */
	Bitu open_count = 0;
	for (Bitu i = 0; i < DOS_FILES; i++) {
		DOSStateHandle h;
		memset(&h, 0, sizeof(h));

		DOS_File *file = Files[i];
		if (file && file->IsOpen()) {
			DOS_Device *dev = dynamic_cast<DOS_Device *>(file);
			if (dev) {
				/* A device. */
				h.kind   = 1;
				h.devnum = dev->GetDeviceNumber();
			} else {
				/* A file. Ask the file where its pointer is; every
				 * implementation of DOS_File::Seek() returns the
				 * current position and leaves it where it was. */
				h.kind = 2;
				Bit32u pos = 0;
				file->Seek(&pos, DOS_SEEK_CUR);
				h.pos = pos;
			}
			h.flags  = file->flags;
			h.time   = file->time;
			h.date   = file->date;
			h.attr   = file->attr;
			h.refCtr = (uint32_t)file->refCtr;
			h.hdrive = file->GetDrive();
			const char *name = file->GetName();
			if (name) strncpy(h.name, name, sizeof(h.name) - 1);
		}
		/* A handle that is closed but still in the table (a file that was
		 * deleted while it was open) is stored as an empty slot: it is
		 * invalid for the guest either way. */
		if (!wr(f, &h, sizeof(h))) return false;
		if (h.kind) open_count++;
	}
	LOG_MSG("savestate: dos state: drive %u, code page %u, %u open handle(s)",
	        (unsigned)cur_drive, (unsigned)codepage, (unsigned)open_count);
	return true;
}

/* --------------------------------------------------------------------- load */

bool DOS_LoadState(FILE *f) {
	uint32_t cur_drive = 0, codepage = 0;
	if (!rd(f, &cur_drive, sizeof(cur_drive)) ||
	    !rd(f, &codepage, sizeof(codepage)))
		return false;

	char dirs[DOS_DRIVES][DOS_PATHLENGTH];
	if (!rd(f, dirs, sizeof(dirs))) return false;

	/* The handle table is read in full before anything live is touched, so a
	 * truncated state is rejected instead of half-restored. */
	static DOSStateHandle handles[DOS_FILES];
	if (!rd(f, handles, sizeof(handles))) return false;

	/* Close and drop everything the loading process has open: the guest's own
	 * file table (in the restored memory) takes its place. */
	for (Bitu i = 0; i < DOS_FILES; i++) {
		DOS_File *file = Files[i];
		if (!file) continue;
		Files[i] = 0;
		if (dynamic_cast<DOS_Device *>(file)) {
			/* May still be in use (the shell waits for a key on the
			 * console), so it is retired instead of deleted. */
			retire_handle(file);
		} else {
			if (file->IsOpen()) file->Close();
			delete file;
		}
	}

	/* The current drive and the code page. The code page belongs to the
	 * machine: the BIOS font that goes with it lives in the video memory,
	 * which is restored from the snapshot as well. */
	if (cur_drive < DOS_DRIVES && (cur_drive < 2 || Drives[cur_drive]))
		dos.current_drive = (Bit8u)cur_drive;
	dos.loaded_codepage = (Bit16u)codepage;

	/* The current directory of every drive. */
	for (Bitu d = 0; d < DOS_DRIVES; d++) {
		if (!Drives[d]) continue;
		strncpy(Drives[d]->curdir, dirs[d], DOS_PATHLENGTH - 1);
		Drives[d]->curdir[DOS_PATHLENGTH - 1] = 0;
	}

	/* Rebuild the table in the same order, so the guest's handles - which are
	 * indices into this table - point at the same files again. */
	Bitu restored = 0;
	for (Bitu i = 0; i < DOS_FILES; i++) {
		const DOSStateHandle *h = &handles[i];
		if (!h->kind) continue;

		if (h->kind == 1) {
			/* A device: a fresh wrapper around the device itself,
			 * exactly like DOS_OpenFile() does it. */
			if (h->devnum >= DOS_DEVICES || !Devices[h->devnum]) {
				LOG_MSG("savestate: device handle %u is gone (device %u)",
				        i, h->devnum);
				continue;
			}
			DOS_Device *dev = new DOS_Device(*Devices[h->devnum]);
			dev->flags  = h->flags;
			dev->time   = h->time;
			dev->date   = h->date;
			dev->attr   = h->attr;
			dev->refCtr = (Bits)h->refCtr;
			dev->open   = true;
			dev->SetDrive((Bit8u)h->hdrive);
			if (h->name[0]) dev->SetName(h->name);
			Files[i] = dev;
			restored++;
			LOG_MSG("savestate: dos handle %u: device %u (%s)", i,
			        (unsigned)h->devnum, h->name);
			continue;
		}

		/* A file. */
		Bit8u drive = (Bit8u)h->hdrive;
		if (drive >= DOS_DRIVES || !Drives[drive]) {
			LOG_MSG("savestate: file handle %u has no drive (%u)", i, drive);
			continue;
		}
		if (!h->name[0]) {
			/* A virtual file has no name of its own, so it cannot
			 * be re-opened; the guest gets an invalid handle for
			 * it, just as it would without the snapshot. */
			LOG_MSG("savestate: file handle %u has no name - skipped", i);
			continue;
		}
		char name[DOS_PATHLENGTH];
		strncpy(name, h->name, sizeof(name) - 1);
		name[sizeof(name) - 1] = 0;

		DOS_File *file = 0;
		if (!Drives[drive]->FileOpen(&file, name, h->flags)) {
			LOG_MSG("savestate: could not reopen %s on drive %u",
			        name, (unsigned)drive);
			continue;
		}
		file->flags  = h->flags;
		file->time   = h->time;
		file->date   = h->date;
		file->attr   = h->attr;
		file->refCtr = (Bits)h->refCtr;
		file->open   = true;
		file->SetDrive(drive);
		/* Put the file pointer back where the guest left it. */
		if (h->pos) {
			Bit32u pos = h->pos;
			file->Seek(&pos, DOS_SEEK_SET);
		}
		Files[i] = file;
		restored++;
		LOG_MSG("savestate: dos handle %u: %s (drive %u, pos %u, refs %u)", i,
		        name, (unsigned)drive, (unsigned)h->pos, (unsigned)h->refCtr);
	}
	LOG_MSG("savestate: dos state restored: drive %u, code page %u, %u handle(s)",
	        (unsigned)dos.current_drive, (unsigned)dos.loaded_codepage,
	        (unsigned)restored);
	return true;
}
