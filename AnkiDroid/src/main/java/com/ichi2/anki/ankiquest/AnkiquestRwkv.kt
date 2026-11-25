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
    }
}

/**
 * The optional RWKV scheduler's Android side (#433): the saved switch, the imported models, the
 * checks read from this phone, starting the backend's engine, and the settings text for each state.
 * Each transition saves the switch its state shows, so a refused scheduler is saved as off.
 */
internal object AnkiquestRwkv {
    const val PREF_ENABLED = "ankiquestRwkv"

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

    /** Emits when the backend's model state is installed: the deck list redraws its counts. */
    val running: SharedFlow<Unit> = runningFlow

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
    ): RwkvState = transition(context, prefs) { RwkvState.Off.enable(checks(context, ram)) }

    /** The player turns it off. */
    suspend fun disable(
        context: Context,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvState = transition(context, prefs) { RwkvState.Off.disable(engine.load) }

    /** The state for the saved switch, as when the app starts or the settings open; a saved "on" that is now blocked is refused. */
    suspend fun restore(
        context: Context,
        ram: RwkvRam,
        prefs: SharedPreferences = AnkiDroidApp.sharedPrefs(),
    ): RwkvState = transition(context, prefs) { RwkvState.restore(switch(prefs), checks(context, ram), engine.load) }

    /** Imports picked files, then hands the backend the new models if the scheduler is on. */
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
        if (switch() == RwkvSwitch.ON) {
            restore(context, AndroidRwkvRam(context))
        }
        return results
    }

    /** A preset's mode changed: which models run depends on the presets, so the engine is handed them again. */
    suspend fun presetsChanged(context: Context) {
        if (switch() == RwkvSwitch.ON) {
            restore(context, AndroidRwkvRam(context))
        }
    }

    private suspend fun transition(
        context: Context,
        prefs: SharedPreferences,
        next: suspend () -> RwkvState,
    ): RwkvState =
        transitions.withLock {
            // The checks and the start read and hash the model files.
            val state = withContext(ioDispatcher) { next().start(store(context), engine) }
            prefs.edit { putBoolean(PREF_ENABLED, state.switch == RwkvSwitch.ON) }
            if (state == RwkvState.Ready) {
                watch()
            }
            state
        }

    private suspend fun checks(
        context: Context,
        ram: RwkvRam,
    ): RwkvChecks =
        RwkvChecks(
            totalRamBytes = ram.totalBytes(),
            cards = CollectionManager.withCol { cardCount() },
            models = store(context).models(RwkvVerify.SIZE),
        )

    /**
     * The deck list was drawn with the standard counts while the backend replays the history on its
     * own thread: once that is done, [running] redraws it with RWKV's. The backend cannot call back,
     * so this polls, as kuma3 does.
     */
    private fun watch() {
        watcher?.cancel()
        watcher =
            scope.launch {
                var idle = 0
                repeat(POLLS) {
                    delay(POLL_MS)
                    when (val progress = engine.progress()) {
                        RwkvProgress.Running -> {
                            Timber.i("RWKV: model state installed")
                            runningFlow.tryEmit(Unit)
                            return@launch
                        }
                        is RwkvProgress.Replaying -> idle = 0
                        RwkvProgress.Unavailable -> {
                            if (++idle >= IDLE_POLLS) {
                                Timber.w("RWKV: no replay under way; stopped waiting")
                                return@launch
                            }
                        }
                    }
                }
                Timber.w("RWKV: not ready after %d polls; stopped waiting", POLLS)
            }
    }

    /** The settings row's summary for [state]. */
    @StringRes
    fun summary(state: RwkvState): Int =
        when (state) {
            RwkvState.Off -> R.string.ankiquest_rwkv_off
            RwkvState.Ready -> R.string.ankiquest_rwkv_ready
            RwkvState.Stopping -> R.string.ankiquest_rwkv_stopping
            is RwkvState.Refused -> reason(state.reason)
        }

    @StringRes
    private fun reason(blocker: RwkvBlocker): Int =
        when (blocker) {
            RwkvBlocker.NOT_ENOUGH_MEMORY -> R.string.ankiquest_rwkv_not_enough_memory
            RwkvBlocker.NO_MODEL -> R.string.ankiquest_rwkv_no_model
            RwkvBlocker.BACKEND_FAILED -> R.string.ankiquest_rwkv_backend_failed
        }
}
