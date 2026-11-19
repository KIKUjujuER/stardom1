package com.whereq.ados

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libsdl.app.SDLActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Minimal SDL2 shell around the DOSBox emulator.
 *
 * Responsibilities of this class (everything else stays inside DOSBox):
 *  - load `libdosbox.so` instead of a separate `libSDL2.so` — SDL2 is statically
 *    linked into the DOSBox library, so asking SDL to load "SDL2" would fail
 *    with UnsatisfiedLinkError;
 *  - point SDL at `libdosbox.so` and at the `SDL_main` entry point inside it;
 *  - hand DOSBox a private, writable working directory and `$HOME`;
 *  - materialise a DOSBox configuration tuned for a phone;
 *  - put the on-screen keyboard on top of the emulator surface;
 *  - host the bundle launcher, which installs and starts DOS bundles.
 *
 * Touch input needs no code here: SDL's Android back end already turns touches
 * into mouse events (see `SDLSurface.onTouch`), and DOSBox maps the mouse back
 * through the letterboxed clip rectangle. The on-screen keyboard is the only
 * thing that has to be added, because it has to produce *key* events, and those
 * are injected into SDL's event queue from [VirtualKeyboardView] via
 * [DosboxNative.nativePushKey].
 */
class AdosActivity : SDLActivity() {

    /**
     * Opens a bundle archive for reading.
     *
     * The archive comes from the system file picker, from a file manager or from
     * a folder, and each of them reads it in its own way.
     */
    private fun interface ArchiveSource {
        @Throws(IOException::class)
        fun open(): InputStream
    }

    /** The on-screen keyboard, drawn on top of the DOSBox surface. */
    private var keyboard: VirtualKeyboardView? = null

    /** The bundle launcher, drawn on top of everything. */
    private var launcher: GameLauncherView? = null

    /** The installed bundles. */
    private lateinit var library: GameLibrary

    /** Save-state storage: the local working copy + the durable folder mirror. */
    private val saveStore by lazy { SaveStore(this) }

    /** When the user connects a saves folder from a game's States dialog, the
     *  game to reopen that dialog for once the folder is set (its saves then
     *  show). */
    private var pendingStatesGame: GameBundle? = null

    /**
     * The folder of the game this process actually booted into (null at a clean
     * prompt). Set once in [onCreate] from the pending launch. Used so "Save
     * state" is only offered for the game that is really running.
     */
    private var currentGameFolder: String? = null

    /**
     * Scope for the background install/import work. Tied to the activity and
     * cancelled in [onDestroy], so a running unpack can never post to a
     * destroyed activity (the raw-thread version could). `Main.immediate` means
     * the UI updates in the coroutine body run on the main thread.
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Serialises installs: two archives must not unpack into the games
     * directory at the same time (the index is read, then a folder is created).
     */
    private val installMutex = Mutex()

    override fun getLibraries(): Array<String> {
        // SDL2 is statically linked into libdosbox.so, so "SDL2" must not be
        // requested here.
        return arrayOf(NATIVE_LIB)
    }

    override fun getMainSharedObject(): String =
        SDLActivity.getContext().applicationInfo.nativeLibraryDir + "/lib" + NATIVE_LIB + ".so"

    override fun getMainFunction(): String = "SDL_main"

    override fun getArguments(): Array<String> {
        // DOSBox reads its configuration from this file; the path is relative to
        // the working directory, which nativePrepareEnvironment() has set to the
        // app's files directory.
        return arrayOf("-conf", CONF_NAME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // loads the native library

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // NOTE: do NOT resize/pad the SDL surface here to make room for the
        // keyboard. Changing the SurfaceView size breaks DOSBox's Android
        // texture renderer (black screen) — the porting log warns the window
        // must not be recreated/resized. The surface stays full-screen and the
        // keyboard only uses the letterbox bars left and right of the picture.

        val filesDir = filesDir

        // A game is launched by relaunching the app into a fresh DOSBox boot
        // that auto-runs it (see play()): that is the only way to get a clean
        // slate and, crucially, a freshly mounted C: whose directory cache
        // includes the installed bundle. (DOSBox caches the C: listing at mount
        // time and only re-scans floppies on FindFirst — localDrive.cpp:184 —
        // so an installer that enumerates its files finds nothing otherwise.)
        // Read the pending launch and clear it immediately, so a crash or a
        // force-quit lands back at a clean C:\> prompt instead of looping.
        val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
        val pendingLaunch = prefs.getString(KEY_PENDING_LAUNCH, null)
        // A "resume" also carries the slot to restore: the game is not started
        // from the beginning, its saved machine is loaded instead.
        val pendingState = prefs.getInt(KEY_PENDING_STATE, 0)
        if (pendingLaunch != null) {
            prefs.edit().remove(KEY_PENDING_LAUNCH).remove(KEY_PENDING_STATE).commit()
        } else if (pendingState != 0) {
            prefs.edit().remove(KEY_PENDING_STATE).commit()
        }
        // This is the game actually running this boot (null = a clean prompt);
        // "Save state" is offered only for it, so a slot is never overwritten
        // with an unrelated machine.
        currentGameFolder = pendingLaunch
        // Regenerate the config from the asset on every boot: it keeps the
        // shipped settings current (e.g. core=auto reaches existing installs)
        // and appends the autostart line for the pending game. With a slot to
        // restore there is nothing to autostart - the restore brings the game
        // back by itself.
        writeConfig(filesDir, pendingLaunch, autostartGame = pendingState == 0)

        library = GameLibrary(filesDir)

        // Two games can carry the same archive name, and the durable save
        // store is keyed by the display name, so their saves would overwrite
        // each other. The names are made unique once, here.
        library.makeNamesUnique()

        // When a game is auto-starting, keep this process alive while the player
        // switches to another app, so SDL's paused emulator survives and resumes
        // the exact state on return (DOSBox has no save-state). A clean prompt
        // boot runs no service.
        if (pendingLaunch != null) {
            requestNotificationPermission()
            val name = library.list().firstOrNull { it.folder == pendingLaunch }?.displayName
                ?: pendingLaunch
            DosboxForegroundService.start(this, name)
        }

        // HOME + working directory must be in place before SDL_main() runs; the
        // SDL thread is only started once the surface is ready, i.e. after
        // onResume(), so doing this here is early enough.
        DosboxNative.nativePrepareEnvironment(filesDir.absolutePath)

        installVirtualKeyboard()

        // A bundle that was opened from a file manager is installed right away —
        // the user asked for it with a tap.
        handleOpenIntent(intent)

        // "Resume" asked for a slot to be restored: do it now. The native side
        // only services the request once the guest is running, so the call
        // simply waits for the emulator to come up.
        if (pendingLaunch != null && pendingState > 0) {
            restoreAfterBoot(pendingLaunch, pendingState)
        }

        Log.i(TAG, "prepared environment, config dir = " + DosboxNative.nativeConfigDir())
    }

    /**
     * Installs the bundle of an `ACTION_VIEW` intent.
     *
     * That is how a bundle reaches the app when the user picks it in a file
     * manager and chooses A-DOS.
     */
    private fun handleOpenIntent(intent: Intent?) {
        if (intent == null || Intent.ACTION_VIEW != intent.action) {
            return
        }
        val uri = intent.data ?: return
        val name = displayNameOf(uri)
        showLauncher()
        install(
            ArchiveSource {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("the file could not be opened")
            },
            name
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
    }

    /**
     * Puts the on-screen keyboard on top of the DOSBox surface.
     *
     * The keyboard is added to the activity's content view, i.e. it is drawn
     * above the SDL `SurfaceView`. Its keys only occupy the space next to the
     * DOS picture, so touches on the picture still reach the emulator — which is
     * what turns a tap into a click inside the DOS picture (SDL turns the touch
     * into a mouse event, DOSBox maps it back through the letterboxed clip
     * rectangle).
     */
    private fun installVirtualKeyboard() {
        val keyboard = VirtualKeyboardView(this)
        keyboard.setOnFilesRequestedListener { showLauncher() }

        // Full-screen overlay: the keyboard positions its keys beside the DOS
        // picture itself and leaves the picture free (a tap there falls
        // through to DOSBox as a mouse click).
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        addContentView(keyboard, lp)
        this.keyboard = keyboard
    }

    /* --------------------------------------------------------- the launcher */

    /** Opens the game launcher over the emulator. */
    private fun showLauncher() {
        var launcher = this.launcher
        if (launcher == null) {
            launcher = GameLauncherView(this, object : GameLauncherView.Listener {
                override fun onInstallRequested() = pickZipFile()

                override fun onBrowseRequested() = browsePhoneFiles()

                override fun onPlayRequested(bundle: GameBundle) = play(bundle)

                override fun onEditLaunchRequested(bundle: GameBundle) {
                    this@AdosActivity.launcher?.showLaunchEditor(
                        bundle,
                        library.launchScriptFor(bundle),
                        library.listCandidates(bundle),
                    )
                }

                override fun onLaunchChosen(bundle: GameBundle, script: String) {
                    // Save the launch commands and boot straight into them.
                    // Multi-file games (driver TSRs + main exe) need a few tries
                    // to get the sequence right; relaunching on each save makes
                    // that loop fast.
                    try {
                        library.setLaunch(bundle, script)
                    } catch (e: IOException) {
                        this@AdosActivity.launcher?.setStatus(
                            "Could not set the launch command: " + e.message
                        )
                        return
                    }
                    play(bundle)
                }

                override fun onDeleteRequested(bundle: GameBundle) = confirmDelete(bundle)

                override fun onExitToPromptRequested() = exitToPrompt()

                override fun onConfigRequested() = showConfigDialog()

                override fun onResumeRequested() = resumeLastGame()

                override fun onSaveStateRequested(bundle: GameBundle, slot: Int) =
                    saveState(bundle, slot)

                override fun onLoadStateRequested(bundle: GameBundle, slot: Int) =
                    loadState(bundle, slot)

                override fun stateSlotLabel(bundle: GameBundle, slot: Int): String? =
                    slotLabel(bundle, slot)

                override fun hasSavesFolder(): Boolean = saveStore.hasFolder()

                override fun onConnectSavesFolderRequested(bundle: GameBundle) =
                    connectSavesFolder(bundle)

                override fun onCloseRequested() = closeLauncher()
            })

            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addContentView(launcher, lp)
            this.launcher = launcher
        }
        launcher.showFiles(library.list())
        refreshResumeButton()
        launcher.setCurrentGame(currentGameFolder)
        launcher.setStatus("")
        launcher.visibility = View.VISIBLE
    }

    /**
     * Updates the "Resume" button: the last-played game and what it will
     * resume from (its newest save, if it has one).
     */
    private fun refreshResumeButton() {
        val folder = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_GAME, null)
        val bundle = library.list().firstOrNull { it.folder == folder }
        val slot = bundle?.let { newestSlot(it) }
        val detail = if (bundle != null && slot != null) {
            val age = slotLabel(bundle, slot)
            if (age == null) null else "slot $slot · $age"
        } else {
            null
        }
        launcher?.setResumeGame(bundle?.displayName, detail)
    }

    /**
     * Relaunches into the last-played game (the "Resume" button).
     *
     * "Resume" means *resume*: when the game has a saved slot, the newest one
     * is restored, so the player continues exactly where they left off instead
     * of starting the game from the beginning. Without a slot the game is
     * simply started again.
     *
     * The emulator keep-alive handles the common case of returning to a
     * backgrounded game; this covers the process actually having been killed.
     */
    private fun resumeLastGame() {
        val folder = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_LAST_GAME, null) ?: return
        val bundle = library.list().firstOrNull { it.folder == folder } ?: return
        val slot = newestSlot(bundle)
        Log.i(TAG, "resume " + bundle.displayName + " from slot " + (slot ?: "none"))
        relaunch(bundle.folder, slot)
    }

    /**
     * @return the slot with the most recent save for this game, or null when
     * the game has no save at all.
     */
    private fun newestSlot(bundle: GameBundle): Int? {
        var newest: Int? = null
        var newestTime = 0L
        for (slot in 1..STATE_SLOTS) {
            val local = saveStore.localSlot(bundle.folder, slot)
            val time = if (local.exists() && local.length() > 0L) {
                local.lastModified()
            } else {
                saveStore.durableTimestamp(bundle.displayName, slot) ?: continue
            }
            if (time > newestTime) {
                newestTime = time
                newest = slot
            }
        }
        return newest
    }

    private fun closeLauncher() {
        launcher?.visibility = View.GONE
    }

    /* ------------------------------------------------------------ save states */

    /** @return a short "time ago" label for a saved slot (local or the durable
     *  mirror), or null when the slot is empty everywhere. */
    private fun slotLabel(bundle: GameBundle, slot: Int): String? {
        val local = saveStore.localSlot(bundle.folder, slot)
        val time = if (local.exists() && local.length() > 0L) {
            local.lastModified()
        } else {
            saveStore.durableTimestamp(bundle.displayName, slot) ?: return null
        }
        val now = System.currentTimeMillis()
        if (now - time < DateUtils.MINUTE_IN_MILLIS) {
            return "just now"
        }
        return DateUtils.getRelativeTimeSpanString(
            time, now, DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    /**
     * Snapshots the running machine into a slot. The native call hands the work
     * to the DOSBox thread and blocks until it is done, so it runs off the UI
     * thread. If a durable saves folder is set, the slot is also mirrored there
     * (gzip-compressed) so it survives a reinstall.
     */
    private fun saveState(bundle: GameBundle, slot: Int) {
        launcher?.setStatus("Saving slot $slot…")
        val target = saveStore.localSlot(bundle.folder, slot)
        workScope.launch {
            val rc = withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                DosboxNative.nativeSaveState(target.absolutePath)
            }
            if (rc == DosboxNative.SAVESTATE_OK && saveStore.hasFolder()) {
                launcher?.setStatus("Saved slot $slot · backing up…")
                withContext(Dispatchers.IO) {
                    saveStore.mirrorToDurable(bundle.displayName, bundle.folder, slot)
                }
            }
            launcher?.setStatus(saveStateMessage(rc, slot))
            // The slot that was just written is the newest one, so the "Resume"
            // button has to say so - otherwise it still names the slot it would
            // have used before.
            refreshResumeButton()
            // Offer, once, to keep saves across reinstalls.
            if (rc == DosboxNative.SAVESTATE_OK) {
                maybeOfferSavesFolder()
            }
        }
    }

    /**
     * Restores a slot, replacing the running machine (so it works from within a
     * game or from a fresh prompt). On success the launcher closes so the player
     * sees the restored game. If the local copy is gone (after a reinstall) it is
     * first pulled back from the durable mirror.
     */
    private fun loadState(bundle: GameBundle, slot: Int) {
        launcher?.setStatus("Loading slot $slot…")
        val target = saveStore.localSlot(bundle.folder, slot)
        workScope.launch {
            val rc = withContext(Dispatchers.IO) {
                if (!target.exists()) {
                    saveStore.restoreFromDurable(bundle.displayName, bundle.folder, slot)
                }
                if (!target.exists()) SAVESTATE_NO_SLOT
                else DosboxNative.nativeLoadState(target.absolutePath)
            }
            if (rc == DosboxNative.SAVESTATE_OK) {
                // The restored machine *is* that game now, so the launcher must
                // offer "Save" for it - otherwise a slot could only be written
                // after a normal launch, and a game that was only ever restored
                // could not be saved at all.
                currentGameFolder = bundle.folder
                launcher?.setCurrentGame(bundle.folder)
                // The restored game is also the one to resume after a restart.
                getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_LAST_GAME, bundle.folder).apply()
                refreshResumeButton()
                closeLauncher()
            } else if (rc == SAVESTATE_NO_SLOT) {
                launcher?.setStatus("Slot $slot is empty")
            } else {
                launcher?.setStatus(loadStateMessage(rc))
            }
        }
    }

    /**
     * Restores a saved slot right after the boot ("Resume").
     *
     * The saved machine *is* the game, so the boot does not start the game at
     * all - it only mounts C: - and the restore brings the game back. The
     * native side holds the request until the guest is running, so the call
     * can be made right away.
     *
     * If the slot cannot be restored (a save from another machine setup, a
     * damaged file) the game is started the normal way instead, so the player
     * is never left staring at a bare DOS prompt.
     */
    private fun restoreAfterBoot(folder: String, slot: Int) {
        val name = library.list().firstOrNull { it.folder == folder }?.displayName
        workScope.launch {
            val target = saveStore.localSlot(folder, slot)
            val ready = withContext(Dispatchers.IO) {
                if (!target.exists() && name != null) {
                    saveStore.restoreFromDurable(name, folder, slot)
                }
                target.exists()
            }
            if (!ready) {
                Log.w(TAG, "auto-restore: slot $slot of $folder is gone - starting the game instead")
                relaunch(folder, null)
                return@launch
            }
            val rc = withContext(Dispatchers.IO) {
                DosboxNative.nativeLoadState(target.absolutePath)
            }
            if (rc == DosboxNative.SAVESTATE_OK) {
                Log.i(TAG, "auto-restore of $folder slot $slot done")
            } else {
                Log.w(TAG, "auto-restore of $folder slot $slot failed ($rc) - " +
                    "starting the game instead")
                relaunch(folder, null)
            }
        }
    }

    /* ------------------------------------------------- the durable folder */

    /**
     * Offers, once, to set a durable saves folder the first time a save
     * succeeds. If the user declines it is not asked again (the choice is
     * reachable from Config → Saves folder).
     */
    private fun maybeOfferSavesFolder() {
        if (saveStore.hasFolder()) return
        val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SAVES_FOLDER_OFFERED, false)) return
        prefs.edit().putBoolean(KEY_SAVES_FOLDER_OFFERED, true).apply()
        AlertDialog.Builder(this)
            .setTitle("Keep your saves safe?")
            .setMessage(
                "Pick a folder on your phone and A-DOS will also store your " +
                    "save-states there, compressed. They then survive " +
                    "reinstalling the app or moving to a new phone. You can set " +
                    "this later from Config → Saves folder."
            )
            .setPositiveButton("Choose folder") { _, _ -> pickSavesFolder() }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Connect a saves folder from a game's States dialog, then reopen it so the
     *  game's saves (from a previous install) show up. */
    private fun connectSavesFolder(bundle: GameBundle) {
        pendingStatesGame = bundle
        pickSavesFolder()
    }

    /** Opens the system folder picker to choose the durable saves folder. */
    private fun pickSavesFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadRootUri())
        try {
            startActivityForResult(intent, REQUEST_PICK_SAVES_FOLDER)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the folder picker", e)
            Toast.makeText(this, "No folder picker available", Toast.LENGTH_LONG).show()
        }
    }

    /** Mirrors every existing local slot into the (just chosen) durable folder. */
    private fun backUpAllSlotsToDurable() {
        val bundles = library.list()
        workScope.launch(Dispatchers.IO) {
            for (bundle in bundles) {
                for (slot in 1..STATE_SLOTS) {
                    if (saveStore.localSlot(bundle.folder, slot).exists()) {
                        saveStore.mirrorToDurable(bundle.displayName, bundle.folder, slot)
                    }
                }
                saveStore.mirrorGame(bundle, library.folderOf(bundle))
            }
        }
    }

    /** Mirrors a freshly installed game to the durable folder (called on the IO
     *  thread from the install paths). No-op when no folder is set. */
    private fun mirrorGameIfDurable(bundle: GameBundle) {
        if (saveStore.hasFolder()) {
            saveStore.mirrorGame(bundle, library.folderOf(bundle))
        }
    }

    /**
     * After a saves folder is chosen, offers to re-install any games that live
     * in it but are not installed locally (the one-tap recovery after a
     * reinstall). Matched by game name.
     */
    private fun offerRestoreGames() {
        workScope.launch {
            val durable = withContext(Dispatchers.IO) { saveStore.durableGames() }
            val have = library.list().mapTo(HashSet()) { it.displayName }
            val missing = durable.filter { it.name.isNotEmpty() && it.name !in have }
            if (missing.isEmpty()) {
                return@launch
            }
            val n = missing.size
            AlertDialog.Builder(this@AdosActivity)
                .setTitle("Restore games?")
                .setMessage(
                    "This folder has $n game${if (n == 1) "" else "s"} that " +
                        (if (n == 1) "is" else "are") + " not installed. Restore " +
                        (if (n == 1) "it" else "them") + " now? Your saves for " +
                        (if (n == 1) "it" else "them") + " will work again too."
                )
                .setPositiveButton("Restore") { _, _ -> restoreGames(missing) }
                .setNegativeButton("Not now", null)
                .show()
        }
    }

    private fun restoreGames(games: List<SaveStore.DurableGame>) {
        launcher?.setStatus("Restoring ${games.size} game(s)…")
        workScope.launch {
            var done = 0
            for (g in games) {
                withContext(Dispatchers.IO) {
                    try {
                        saveStore.openDurableGame(g.uri)?.use { input ->
                            library.restore(g.name, g.entry, g.launch, g.ownConfig, g.archive, input)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "could not restore game ${g.name}", e)
                    }
                }
                done++
                launcher?.setStatus("Restoring games… ($done/${games.size})")
            }
            launcher?.showFiles(library.list())
            refreshResumeButton()
            launcher?.setStatus("Restored $done game(s). Your saves are ready.")
        }
    }

    /** Config → Saves folder: show the state and let the user set/change it. */
    private fun showSavesFolderDialog() {
        val status = if (saveStore.hasFolder()) {
            "A folder is set — your saves are backed up there (compressed)."
        } else {
            "No folder set — saves are kept only inside the app and are lost if it is uninstalled."
        }
        AlertDialog.Builder(this)
            .setTitle("Saves folder")
            .setMessage(
                status + "\n\nPick a folder and A-DOS mirrors every save-state " +
                    "there so it survives a reinstall or a new phone."
            )
            .setPositiveButton(if (saveStore.hasFolder()) "Change folder" else "Choose folder") { _, _ ->
                pickSavesFolder()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun saveStateMessage(rc: Int, slot: Int): String = when (rc) {
        DosboxNative.SAVESTATE_OK -> "Saved to slot $slot"
        DosboxNative.SAVESTATE_ERR_TIMEOUT -> "Save timed out — is a game running?"
        DosboxNative.SAVESTATE_ERR_BUSY -> "Busy — try again in a moment"
        else -> "Could not save (error $rc)"
    }

    private fun loadStateMessage(rc: Int): String = when (rc) {
        DosboxNative.SAVESTATE_ERR_MISMATCH -> "That save is for a different machine setup"
        DosboxNative.SAVESTATE_ERR_FORMAT -> "That save file is damaged"
        DosboxNative.SAVESTATE_ERR_TIMEOUT -> "Load timed out — is the emulator running?"
        DosboxNative.SAVESTATE_ERR_BUSY -> "Busy — try again in a moment"
        else -> "Could not load (error $rc)"
    }

    /** Removes a game's save-state slots (called when the game is deleted). */
    private fun deleteStateDir(bundle: GameBundle) {
        // Only the local working copies are removed; the durable mirror in the
        // user's folder is theirs to keep or delete.
        saveStore.deleteLocal(bundle.folder)
    }

    /** Asks before a game is removed — the bundle is deleted from the phone. */
    private fun confirmDelete(bundle: GameBundle) {
        AlertDialog.Builder(this)
            .setTitle("Remove " + bundle.displayName + "?")
            .setMessage("The unpacked game is deleted from the phone.")
            .setPositiveButton("Remove") { _, _ ->
                library.delete(bundle)
                deleteStateDir(bundle)
                // Forget the resume target if it was this game.
                val prefs = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                if (prefs.getString(KEY_LAST_GAME, null) == bundle.folder) {
                    prefs.edit().remove(KEY_LAST_GAME).apply()
                }
                launcher?.showFiles(library.list())
                refreshResumeButton()
                launcher?.setStatus("Removed " + bundle.displayName)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /* ---------------------------------------------------------- installing */

    /**
     * Opens the system file picker for a zip archive.
     *
     * The intent has to carry a MIME type: the filter of the system picker
     * declares one, so an intent without a type does not resolve at all and the
     * picker never opens. The type is therefore the general one and the
     * interesting types are handed over separately, which lets the picker show
     * every file but mark the archives.
     */
    private fun pickZipFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        // A lot of file providers do not report a zip as application/zip, so the
        // type list is wide on purpose.
        intent.putExtra(
            Intent.EXTRA_MIME_TYPES,
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "application/x-compressed",
                "application/x-zip",
            )
        )
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The bundles are usually in the download folder, so the picker opens
        // right there instead of in the "recent files" list.
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadRootUri())
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no document picker, trying the content picker", e)
            pickZipFileLegacy()
        } catch (e: Exception) {
            Log.w(TAG, "could not open the file picker", e)
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The older way to ask for a file, used when the device has no document
     * picker.
     */
    private fun pickZipFileLegacy() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivityForResult(intent, REQUEST_PICK_ZIP)
        } catch (e: Exception) {
            Log.w(TAG, "could not open the content picker", e)
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Shows the zip archives of a folder on the phone.
     *
     * The user picks a folder once and every archive in it is installed — the
     * quick way to get a whole collection of DOS programs onto the phone.
     */
    private fun browsePhoneFiles() {
        BundleFolder.pickFolder(this, REQUEST_PICK_FOLDER)
    }

    /** Installs every archive of the folder the user picked. */
    private fun importFolder(treeUri: Uri) {
        launcher?.setStatus("Looking for bundles…")
        workScope.launch {
            installMutex.withLock {
                val entries = withContext(Dispatchers.IO) {
                    BundleFolder.collect(contentResolver, treeUri)
                }
                val total = entries.size
                var installed = 0
                var failed = 0
                val error = if (total == 0) "no .zip bundle found in that folder" else null

                for (entry in entries) {
                    try {
                        withContext(Dispatchers.IO) {
                            entry.open(contentResolver).use { input ->
                                mirrorGameIfDurable(library.install(input, entry.name))
                            }
                        }
                        installed++
                    } catch (e: Exception) {
                        failed++
                        Log.e(TAG, "could not install " + entry.name, e)
                    }
                    val done = installed + failed
                    // Back on the main thread (Main.immediate) for the UI update.
                    launcher?.setStatus("Installing ${entry.name} ($done of $total)…")
                }

                val launcher = this@AdosActivity.launcher
                if (launcher != null) {
                    val bundles = withContext(Dispatchers.IO) { library.list() }
                    launcher.showFiles(bundles)
                    launcher.setStatus(
                        error ?: ("Installed " + installed + " bundle" +
                            (if (installed == 1) "" else "s") +
                            (if (failed > 0) ", $failed failed" else ""))
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_SAVES_FOLDER) {
            // Cleared here so a cancelled pick does not leave it dangling.
            val reopen = pendingStatesGame
            pendingStatesGame = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "could not persist the saves-folder permission", e)
                }
                saveStore.folderUri = uri
                backUpAllSlotsToDurable()
                // Refresh so slot labels pick up the newly connected folder.
                launcher?.showFiles(library.list())
                if (reopen != null) {
                    // Connected from a game's States dialog: reopen it so the
                    // game's saves from a previous install show up now.
                    Toast.makeText(
                        this, "Saves folder connected — your saves are back.",
                        Toast.LENGTH_LONG
                    ).show()
                    launcher?.openStates(reopen)
                } else {
                    Toast.makeText(
                        this, "Saves folder set — your games and saves are backed up here.",
                        Toast.LENGTH_LONG
                    ).show()
                    // If this folder already has games from a previous install,
                    // offer to bring them back.
                    offerRestoreGames()
                }
            }
            return
        }
        if (requestCode == REQUEST_PICK_FOLDER) {
            if (resultCode != RESULT_OK || data?.data == null) {
                return
            }
            importFolder(data.data!!)
            return
        }
        if (requestCode != REQUEST_PICK_ZIP) {
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) {
            return
        }
        val uri = data.data!!
        val name = displayNameOf(uri)
        install(
            ArchiveSource {
                contentResolver.openInputStream(uri)
                    ?: throw IOException("the file could not be opened")
            },
            name
        )
    }

    /**
     * Unpacks a picked archive into the app.
     *
     * The archive is read on a background thread — a bundle is a few megabytes
     * and the emulator must not freeze while it is unpacked.
     *
     * @param source   opens the archive.
     * @param nameHint the file name, when it is already known.
     */
    private fun install(source: ArchiveSource, nameHint: String?) {
        launcher?.setStatus("Unpacking…")
        val name = if (nameHint != null) stripExtension(nameHint) else "game"
        workScope.launch {
            var installed: String? = null
            val error = installMutex.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val bundle = source.open().use { input -> library.install(input, name) }
                        // The display name can differ from the archive name: a
                        // second archive with the same name is installed as
                        // "name (2)", and that is the name the launcher shows.
                        installed = bundle.displayName
                        mirrorGameIfDurable(bundle)
                        null
                    } catch (e: Exception) {
                        Log.e(TAG, "could not install $name", e)
                        if (e.message == null) e.toString() else e.message
                    }
                }
            }
            val launcher = this@AdosActivity.launcher ?: return@launch
            val bundles = withContext(Dispatchers.IO) { library.list() }
            launcher.showFiles(bundles)
            launcher.setStatus(
                if (error == null) "Installed “${installed ?: name}”. Tap Play to launch — it stays installed, no need to add it again."
                else "Could not install $name: $error"
            )
        }
    }

    /** @return the name of the picked file, without the .zip suffix. */
    private fun displayNameOf(uri: Uri): String {
        var name: String? = null
        try {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the name of the picked file", e)
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        if (name == null) {
            name = "game"
        }
        return stripExtension(name!!)
    }

    /* -------------------------------------------------------------- playing */

    /**
     * Launches a game by relaunching the app into a fresh DOSBox boot that
     * auto-runs it.
     *
     * Why a relaunch and not "type the command into the running DOSBox": a DOS
     * program cannot be terminated from the outside, so typing a second game's
     * command just feeds it to whatever is already running — and, worse, DOSBox
     * mounts C: once and caches its directory listing, so a bundle installed
     * after boot is invisible to an installer that enumerates its files. A fresh
     * process gets a fresh mount (cache includes the bundle) and a guaranteed
     * clean slate. The DOS files live in the persistent app directory, so game
     * saves survive the relaunch.
     *
     * The target folder is recorded in SharedPreferences; [onCreate] reads it on
     * the next boot, writes it into the `[autoexec]` of the config, and clears
     * it (so a crash lands back at the prompt rather than looping).
     */
    private fun play(bundle: GameBundle) {
        Log.i(TAG, "launching " + bundle.displayName + " (" + bundle.folder + ") via relaunch")
        relaunch(bundle.folder)
    }

    /**
     * Exits whatever is running back to a clean `C:\>` prompt.
     *
     * This is the escape hatch for a game that hangs or leaves a black screen:
     * a DOS program cannot be stopped from the outside, so the only reliable way
     * out is a fresh boot with nothing queued. Always reachable from the File
     * launcher, which is an Android overlay independent of the DOSBox thread.
     */
    private fun exitToPrompt() {
        Log.i(TAG, "exiting to a clean prompt via relaunch")
        relaunch(null)
    }

    /**
     * Relaunches the app into a completely fresh process (and thus a fresh
     * DOSBox/SDL + a freshly mounted C:). [pendingFolder] is the games folder to
     * auto-run on the next boot, or null to land at the prompt. [stateSlot] is
     * a save-state slot to restore on the next boot instead of starting the
     * game from the beginning (null = start it normally). The values are
     * written synchronously so they survive the process tear-down below.
     */
    private fun relaunch(pendingFolder: String?, stateSlot: Int? = null) {
        val editor = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).edit()
        if (pendingFolder != null) {
            editor.putString(KEY_PENDING_LAUNCH, pendingFolder)
            editor.putInt(KEY_PENDING_STATE, stateSlot ?: 0)
            // Remember it as the game to offer "Resume" for after a cold start.
            editor.putString(KEY_LAST_GAME, pendingFolder)
        } else {
            editor.remove(KEY_PENDING_LAUNCH)
            editor.remove(KEY_PENDING_STATE)
        }
        editor.commit() // synchronous: must be on disk before the process dies

        // The keep-alive service lives in this process; stop it before the tear
        // down (it is START_NOT_STICKY, so the OS will not revive it). The next
        // process starts a fresh one in onCreate only if a game is pending.
        DosboxForegroundService.stop(this)

        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "Could not relaunch the emulator", Toast.LENGTH_LONG).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        // Tear the current process (and its DOSBox/SDL native state) down so the
        // new one boots completely fresh.
        Runtime.getRuntime().exit(0)
    }

    /**
     * The back button folds the keyboard away before it leaves the app, and
     * closes the launcher when it is open.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val launcher = this.launcher
            if (launcher != null && launcher.visibility == View.VISIBLE) {
                closeLauncher()
                return true
            }
            val keyboard = this.keyboard
            if (keyboard != null && keyboard.isPanelVisible()) {
                keyboard.hidePanel()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        // Stop keeping the process alive and cancel pending install/import work
        // so nothing touches the activity after it is gone.
        DosboxForegroundService.stop(this)
        workScope.cancel()
        super.onDestroy()
    }

    /** Requests the notification permission (API 33+) so the keep-alive service's
     *  ongoing notification is visible. The service runs either way. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    /** Full-screen, no status bar / navigation bar (immersive, swipe to peek). */
    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Writes the DOSBox configuration for this boot.
     *
     * The config is regenerated from the asset template every boot (so shipped
     * changes — e.g. core=auto — always take effect, and the file cannot drift).
     * The one exception is the CPU core the user picked in the settings, which
     * is written into the template; a hand-edited dosbox.conf would be lost.
     * The template's `[autoexec]` ends with `mount c .` + `c:`; when a game is
     * pending, its `RUN.BAT` is appended so DOSBox boots straight into it.
     *
     * [autostartGame] is false when a save-state slot is restored on this boot:
     * the saved machine *is* the game, so starting it as well would only flash
     * its intro before the restore replaces it.
     *
     * The DOS files live in the app's files directory, which is mounted as C:,
     * so this only rewrites settings — it never touches the games or saves.
     */
    private fun writeConfig(
        filesDir: File,
        pendingLaunch: String?,
        autostartGame: Boolean = true
    ) {
        val target = File(filesDir, CONF_NAME)
        try {
            val base = assets.open(CONF_ASSET).use { it.readBytes() }
            // The config is regenerated on every boot, so a hand-edited
            // dosbox.conf would be lost — the CPU core the user picked in the
            // settings is written into it instead (see showConfigDialog).
            val core = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
                .getString(KEY_CPU_CORE, null)
            val config = String(base, Charsets.ISO_8859_1)
            val text = if (core != null) applyCpuCore(config, core) else config
            FileOutputStream(target).use { out ->
                out.write(text.toByteArray(Charsets.ISO_8859_1))
                if (pendingLaunch != null && autostartGame) {
                    // DOS line endings; ISO-8859-1 matches how RUN.BAT is written.
                    val autostart = "\r\nC:\\" + GameBundle.DOS_GAMES_DIR + "\\" +
                        pendingLaunch + "\\RUN.BAT\r\n"
                    out.write(autostart.toByteArray(Charsets.ISO_8859_1))
                }
            }
            Log.i(
                TAG,
                "wrote " + target.absolutePath +
                    (if (pendingLaunch != null) " (autostart $pendingLaunch)" else "") +
                    (if (core != null) " (core $core)" else "")
            )
        } catch (e: IOException) {
            Log.e(TAG, "could not write $CONF_NAME", e)
        }
    }

    /**
     * Replaces the `core=` line of the shipped configuration with [core].
     *
     * Only a line that starts with `core=` is touched, so the comment block
     * above it keeps explaining the setting.
     */
    private fun applyCpuCore(config: String, core: String): String =
        config.replace(Regex("(?m)^core=.*$"), "core=" + core)

    /**
     * Lets the user pick the DOSBox CPU core.
     *
     * The app ships with `core=auto`: a real-mode program runs on the
     * interpreter, a protected-mode program on the recompiler. A few old DOS
     * programs misbehave on the recompiler, and the documented fallback
     * (`core=normal`) has to be reachable without a text editor — the config
     * file is regenerated on every boot, so a manual edit would be lost.
     *
     * The choice is stored and applied to the next boot; DOSBox cannot switch
     * its core while it runs.
     */
    private fun showConfigDialog() {
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(arrayOf("CPU core", "Saves folder")) { _, which ->
                when (which) {
                    0 -> showCpuCoreDialog()
                    1 -> showSavesFolderDialog()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCpuCoreDialog() {
        val current = getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE)
            .getString(KEY_CPU_CORE, DEFAULT_CPU_CORE) ?: DEFAULT_CPU_CORE
        val checked = CPU_CORES.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("CPU core")
            .setSingleChoiceItems(CPU_CORE_LABELS, checked) { dialog, which ->
                dialog.dismiss()
                val core = CPU_CORES[which]
                getSharedPreferences(LAUNCH_PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_CPU_CORE, core)
                    .commit()
                confirmRestartForCore(core)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Asks whether to restart now, so the new core takes effect. */
    private fun confirmRestartForCore(core: String) {
        AlertDialog.Builder(this)
            .setTitle("CPU core: " + core)
            .setMessage(
                "DOSBox picks its core when it starts, so the new setting " +
                    "takes effect after a restart."
            )
            .setPositiveButton("Restart") { _, _ -> relaunch(null) }
            .setNegativeButton("Later", null)
            .show()
    }

    companion object {
        private const val TAG = "a-dos"

        /** Name of the native library (without lib prefix / .so suffix). */
        private const val NATIVE_LIB = "dosbox"

        /** SharedPreferences that carry the pending launch across the relaunch. */
        private const val LAUNCH_PREFS = "launch"

        /** The games folder (e.g. "G1") to auto-run on the next boot, or absent. */
        private const val KEY_PENDING_LAUNCH = "pending"

        /** The save-state slot to restore on the next boot (1-based), or 0. */
        private const val KEY_PENDING_STATE = "pending_state"

        /** The last game Played — offered as "Resume" after a cold start. */
        private const val KEY_LAST_GAME = "last_game"

        /** Whether the "keep saves across reinstalls" offer was already shown. */
        private const val KEY_SAVES_FOLDER_OFFERED = "saves_folder_offered"

        /** Save-state slots offered per game (must match GameLauncherView). */
        private const val STATE_SLOTS = 3

        /** loadState() sentinel: the slot does not exist (local or durable). */
        private const val SAVESTATE_NO_SLOT = -1

        /** requestPermissions code for POST_NOTIFICATIONS (keep-alive notice). */
        private const val REQUEST_POST_NOTIFICATIONS = 42

        /** The CPU core the user picked in the settings, or absent for the default. */
        private const val KEY_CPU_CORE = "cpu_core"

        /** The CPU cores DOSBox understands, in the order they are offered. */
        private val CPU_CORES = arrayOf("auto", "normal", "dynamic", "simple", "prefetch")

        /** What the user sees for each core. */
        private val CPU_CORE_LABELS = arrayOf(
            "Auto — interpreter for real mode, JIT for protected mode",
            "Normal — the interpreter only",
            "Dynamic — the JIT only",
            "Simple — a small, fast interpreter",
            "Prefetch — the interpreter with a prefetch unit"
        )

        /** The core the app ships with: the one the documentation describes. */
        private const val DEFAULT_CPU_CORE = "auto"

        /** DOSBox configuration file, written into the app's files directory. */
        private const val CONF_NAME = "dosbox.conf"

        /** The configuration template lives in assets/. */
        private const val CONF_ASSET = "dosbox.conf"

        /** Request code of the system file picker. */
        private const val REQUEST_PICK_ZIP = 1

        /** The request code of the folder picker. */
        private const val REQUEST_PICK_FOLDER = 2

        /** The request code of the durable saves-folder picker. */
        private const val REQUEST_PICK_SAVES_FOLDER = 3

        /**
         * @return the document of the download folder, so the picker can start
         *         there. A device that does not know it simply opens the picker
         *         where it would open anyway.
         */
        private fun downloadRootUri(): Uri? = try {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download"
            )
        } catch (e: Exception) {
            null
        }

        /** @return the file name without its extension. */
        private fun stripExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}
