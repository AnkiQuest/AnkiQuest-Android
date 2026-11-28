// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.content.getSystemService
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** The phone's total RAM, behind a seam so the checks can be tested without a device. */
internal fun interface RwkvRam {
    fun totalBytes(): Long
}

/** Total RAM as `ActivityManager.MemoryInfo` reports it. */
internal class AndroidRwkvRam(
    private val context: Context,
) : RwkvRam {
    override fun totalBytes(): Long {
        val manager = context.getSystemService<ActivityManager>() ?: return 0
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.totalMem
    }
}

/**
 * Starts the engine for a [RwkvState.Ready] state: the files are hashed again first, so an
 * unverified file never reaches the backend. A backend that refuses them fails the state.
 */
internal suspend fun RwkvState.start(
    store: RwkvModelStore,
    engine: RwkvEngine,
): RwkvState {
    if (this != RwkvState.Ready) {
        return this
    }
    val models = store.models(RwkvVerify.HASH) ?: return RwkvState.Refused(RwkvBlocker.NO_MODEL)
    return try {
        val replayed = engine.prepare(models)
        Timber.i("RWKV: %s and %s, replaying %d reviews", models.twoHead.entry.id, models.instant?.entry?.id, replayed)
        this
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "RWKV: the backend refused the models; using the standard scheduler")
        fail()
    } catch (e: OutOfMemoryError) {
        // A model past the memory limit, allowed by the player, may not load: off, not a crash.
        Timber.w(e, "RWKV: out of memory loading the models; using the standard scheduler")
        fail()
    }
}

/**
 * The optional RWKV scheduler's Android side (#433): the saved switch, the imported models, the
 * checks read from this phone, starting the backend's engine, and the settings text for each state.
 * Each transition saves the switch its state shows, so a refused scheduler is saved as off.
 */
internal object AnkiquestRwkv {
    const val PREF_ENABLED = "ankiquestRwkv"

    /** The lock entry ids the player allowed past the memory limit ("I know what I'm doing"). */
    const val PREF_OVERRIDES = "ankiquestRwkvOverrides"

    /** Set while the engine starts with a model past the memory limit; see [RwkvLaunch]. */
    const val PREF_LOADING = "ankiquestRwkvLoading"

    /** App-private, not backed up: the files come back by importing them again. */
    private const val MODEL_DIR = "rwkv"

    /** How often, and how many times, [watch] asks whether the replay is done; 10 minutes covers a large history on a slow phone. */
    private const val POLL_MS = 500L
    private const val POLLS = 1200

    /** Polls without a replay under way after which [watch] stops: the build did not start, or failed. */
    private const val IDLE_POLLS = 60

    private val engine: RwkvEngine = BackendRwkvEngine
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val transitions = Mutex()
    private var watcher: Job? = null
    private val runningFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Downloads being checked or not kept, by lock entry id, for the Download list; this process only. */
    private val attempts = ConcurrentHashMap<String, RwkvAttempt>()

    /** Emits when the backend's model state is installed: the deck list redraws its counts. */
    val running: SharedFlow<Unit> = runningFlow

    private val refusedFlow = MutableSharedFlow<RwkvState>(extraBufferCapacity = 1)

    /** Emits when a running engine failed after it started and RWKV turned itself off: the settings show why. */
    val refused: SharedFlow<RwkvState> = refusedFlow

    /** The models the player allowed past the memory limit. */
    fun overrides(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Set<String> =
        prefs.getStringSet(PREF_OVERRIDES, null).orEmpty().toSet()

    /** Saves [entry]'s override; the next check, at a start or in the settings, follows it. */
    fun override(
        entry: RwkvLockEntry,
        override: RwkvOverride,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ) {
        val ids =
            when (override) {
                RwkvOverride.ALLOWED -> overrides(prefs) + entry.id
                RwkvOverride.NOT_ALLOWED -> overrides(prefs) - entry.id
            }
        prefs.edit { putStringSet(PREF_OVERRIDES, ids) }
    }

    private fun launch(prefs: SharedPreferences): RwkvLaunch =
        if (prefs.getBoolean(PREF_LOADING, false)) RwkvLaunch.INTERRUPTED else RwkvLaunch.CLEAN

    fun switch(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): RwkvSwitch =
        if (prefs.getBoolean(PREF_ENABLED, false)) RwkvSwitch.ON else RwkvSwitch.OFF

    fun store(context: Context) = RwkvModelStore(File(context.noBackupFilesDir, MODEL_DIR))

    /** At app start: starts the engine if the switch was saved on. */
    fun startSaved(context: Context) {
        if (switch() != RwkvSwitch.ON) {
            return
        }
        scope.launch {
            try {
                restore(context, AndroidRwkvRam(context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "RWKV: could not start")
            }
        }
    }

    /** The player turns it on; refused if a check fails or the backend refuses the models. */
    suspend fun enable(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvState = checked(context, ram, prefs) { RwkvState.Off.enable(it) }

    /** The player turns it off. */
    suspend fun disable(
        context: Context,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvState = transitions.withLock { settle(prefs, RwkvState.Off.disable()) }

    /** The state for the saved switch, as when the app starts or the settings open; a saved "on" that is now blocked is refused. */
    suspend fun restore(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvState = checked(context, ram, prefs) { RwkvState.restore(switch(prefs), it, launch(prefs)) }

    /** Imports picked files; the caller restores the state afterwards, which hands the backend the new models if the scheduler is on. */
    suspend fun import(
        context: Context,
        inputs: List<() -> InputStream>,
    ): List<RwkvImport> {
        val store = store(context)
        val results =
            transitions.withLock {
                // The caller runs this on the IO dispatcher: each file is copied and hashed.
                inputs.map { open ->
                    try {
                        open().use { store.import(it) }
                    } catch (e: IOException) {
                        RwkvImport.Rejected(RwkvImportError.UNREADABLE)
                    } catch (e: SecurityException) {
                        RwkvImport.Rejected(RwkvImportError.UNREADABLE)
                    }
                }
            }
        return results
    }

    /**
     * Downloads [entry] from the official release, after its size's two-head model if that is not
     * stored: an Instant-only model never runs alone here. The caller restores the state afterwards,
     * as after an import. Each zip is checked against the lock before it is read; [progress] hears
     * which entry is in which phase. The entry's row in [offers] shows the check while it runs and the
     * failure after one.
     */
    suspend fun download(
        context: Context,
        entry: RwkvLockEntry,
        progress: (RwkvLockEntry, RwkvPhase) -> Unit,
    ): List<RwkvImport> =
        transitions.withLock {
            withContext(ioDispatcher) {
                val store = store(context)
                val stored = store.stored()
                val missing = RwkvOffers.runsWith(entry).filter { it == entry || it !in stored }
                val results = mutableListOf<RwkvImport>()
                for (next in missing) {
                    val result = fetchOne(store, next, progress)
                    results.add(result)

                    // Without its two-head model, an Instant-only model is not worth fetching.
                    if (result is RwkvImport.Rejected) {
                        break
                    }
                }
                results
            }
        }

    private fun fetchOne(
        store: RwkvModelStore,
        entry: RwkvLockEntry,
        progress: (RwkvLockEntry, RwkvPhase) -> Unit,
    ): RwkvImport {
        val result =
            RwkvDownload(store, GithubPackFetch(AnkiquestHttp.client)).download(entry) { phase ->
                if (phase == RwkvPhase.VERIFYING) {
                    attempts[entry.id] = RwkvAttempt.Verifying
                }
                progress(entry, phase)
            }
        when (result) {
            is RwkvImport.Imported -> attempts.remove(entry.id)
            is RwkvImport.Rejected -> attempts[entry.id] = RwkvAttempt.Failed(result.error)
        }
        return result
    }

    /** The Download list for this phone and collection. */
    suspend fun offers(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvOfferList =
        withContext(ioDispatcher) {
            val device = RwkvDevice(ram.totalBytes(), collection())
            RwkvOfferList(device, store(context).stored(), attempts.toMap(), overrides(prefs))
        }

    /**
     * The deck options page saved presets: which models run depends on their schedulers, so a
     * running engine is handed them again. Runs on its own, so the page closes at once.
     */
    fun presetsChanged(context: Context) {
        if (switch() != RwkvSwitch.ON) {
            return
        }
        scope.launch {
            try {
                restore(context, AndroidRwkvRam(context))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "RWKV: could not hand over the presets")
            }
        }
    }

    /**
     * Runs the checks, takes the [next] state from them and starts it. A start with a model past
     * the memory limit is marked first, so a process that dies loading it is not started again.
     */
    private suspend fun checked(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences,
        next: (RwkvChecks) -> RwkvState,
    ): RwkvState =
        transitions.withLock {
            // The checks and the start read and hash the model files.
            val state =
                withContext(ioDispatcher) {
                    val checks = checks(context, ram, prefs)
                    val target = next(checks)
                    val risky = target == RwkvState.Ready && checks.fit() is RwkvFit.TooBigOverridden
                    prefs.edit(commit = true) { putBoolean(PREF_LOADING, risky) }
                    target.start(store(context), engine)
                }
            settle(prefs, state)
        }

    /** Saves the switch [state] shows; only a ready scheduler keeps a model: off and refused hand scheduling back at once. */
    private suspend fun settle(
        prefs: SharedPreferences,
        state: RwkvState,
    ): RwkvState {
        prefs.edit { putBoolean(PREF_ENABLED, state.switch == RwkvSwitch.ON) }
        if (state == RwkvState.Ready) {
            watch(prefs)
            return state
        }
        prefs.edit { putBoolean(PREF_LOADING, false) }
        watcher?.cancel()
        engine.unload()
        return state
    }

    /** The engine failed after it started: RWKV turns itself off and the settings say why. */
    private suspend fun failed(prefs: SharedPreferences) {
        val state = transitions.withLock { settle(prefs, RwkvState.Ready.fail()) }
        refusedFlow.tryEmit(state)
    }

    private suspend fun checks(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences,
    ): RwkvChecks =
        RwkvChecks(
            totalRamBytes = ram.totalBytes(),
            collection = collection(),
            models = store(context).models(RwkvVerify.SIZE),
            overrides = overrides(prefs),
        )

    /** Each card and each note keeps a model state. */
    private suspend fun collection(): RwkvCollection = CollectionManager.withCol { RwkvCollection(cardCount(), noteCount()) }

    /**
     * The deck list was drawn with the standard counts while the backend replays the history on its
     * own thread: once that is done, [running] redraws it with RWKV's. The backend cannot call back,
     * so this polls, as kuma3 does.
     */
    private fun watch(prefs: SharedPreferences) {
        watcher?.cancel()
        watcher =
            scope.launch {
                when (awaitRunning()) {
                    RwkvWatch.RUNNING -> {
                        Timber.i("RWKV: model state installed")
                        prefs.edit { putBoolean(PREF_LOADING, false) }
                        runningFlow.tryEmit(Unit)
                    }
                    RwkvWatch.FAILED -> {
                        // Its own coroutine: turning RWKV off cancels this watcher.
                        Timber.w("RWKV: the engine stopped without a model state; turning RWKV off")
                        scope.launch { failed(prefs) }
                    }
                    RwkvWatch.TIMED_OUT -> {
                        // Still replaying, not dead: the next start may try again.
                        Timber.w("RWKV: not ready after %d polls; stopped waiting", POLLS)
                        prefs.edit { putBoolean(PREF_LOADING, false) }
                    }
                }
            }
    }

    /** How [awaitRunning] ended. */
    private enum class RwkvWatch {
        RUNNING,
        FAILED,
        TIMED_OUT,
    }

    /** Polls until the model state is installed; no replay for [IDLE_POLLS] polls, or an error from the backend, is a failure. */
    private suspend fun awaitRunning(): RwkvWatch {
        var idle = 0
        repeat(POLLS) {
            delay(POLL_MS)
            val progress =
                try {
                    engine.progress()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "RWKV: the backend failed while replaying")
                    return RwkvWatch.FAILED
                }
            when (progress) {
                RwkvProgress.Running -> return RwkvWatch.RUNNING
                is RwkvProgress.Replaying -> idle = 0
                RwkvProgress.Unavailable -> {
                    if (++idle >= IDLE_POLLS) {
                        return RwkvWatch.FAILED
                    }
                }
            }
        }
        return RwkvWatch.TIMED_OUT
    }

    /** The settings row's summary for [state]. */
    @StringRes
    fun summary(state: RwkvState): Int =
        when (state) {
            RwkvState.Off -> R.string.ankiquest_rwkv_off
            RwkvState.Ready -> R.string.ankiquest_rwkv_ready
            is RwkvState.Refused -> reason(state.reason)
        }

    @StringRes
    private fun reason(blocker: RwkvBlocker): Int =
        when (blocker) {
            RwkvBlocker.NO_MODEL -> R.string.ankiquest_rwkv_no_model
            RwkvBlocker.NOT_ENOUGH_MEMORY -> R.string.ankiquest_rwkv_not_enough_memory
            RwkvBlocker.BACKEND_FAILED -> R.string.ankiquest_rwkv_backend_failed
        }

    /** What a picked or downloaded file that was not kept says. */
    @StringRes
    fun importError(error: RwkvImportError): Int =
        when (error) {
            RwkvImportError.UNKNOWN_FILE -> R.string.ankiquest_rwkv_import_unknown
            RwkvImportError.UNREADABLE -> R.string.ankiquest_rwkv_import_unreadable
            RwkvImportError.NETWORK_FAILED -> R.string.ankiquest_rwkv_download_network
            RwkvImportError.DOWNLOAD_MISMATCH -> R.string.ankiquest_rwkv_download_mismatch
        }
}
