// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R

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
 * The optional RWKV scheduler's Android side (#433): the saved switch, the checks read from this
 * phone, and the settings text for each state. Nothing reads the state to schedule cards yet, so
 * the switch changes no scheduling.
 */
internal object AnkiquestRwkv {
    const val PREF_ENABLED = "ankiquestRwkv"

    private val engine: RwkvEngine = NoRwkvEngine
    private val models: RwkvModelSource = NoRwkvModel

    fun switch(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): RwkvSwitch =
        if (prefs.getBoolean(PREF_ENABLED, false)) RwkvSwitch.ON else RwkvSwitch.OFF

    /** The state for [switch] on this phone. */
    suspend fun state(
        switch: RwkvSwitch,
        ram: RwkvRam,
    ): RwkvState = RwkvState.restore(switch, checks(ram))

    private suspend fun checks(ram: RwkvRam): RwkvChecks {
        // The collection is read only when an engine could use it.
        val cards =
            when (engine.availability) {
                RwkvEngine.Availability.PRESENT -> CollectionManager.withCol { cardCount() }
                RwkvEngine.Availability.ABSENT -> 0
            }
        return RwkvChecks(
            engine = engine,
            deviceAbis = Build.SUPPORTED_ABIS.toList(),
            totalRamBytes = ram.totalBytes(),
            cards = cards,
            model = models.chosen(),
        )
    }

    /** The settings row's summary for [state]. */
    @StringRes
    fun summary(state: RwkvState): Int =
        when (state) {
            RwkvState.Off -> R.string.ankiquest_rwkv_off
            RwkvState.Ready -> R.string.ankiquest_rwkv_ready
            is RwkvState.Unavailable -> reason(state.reason)
        }

    @StringRes
    private fun reason(blocker: RwkvBlocker): Int =
        when (blocker) {
            RwkvBlocker.NO_ENGINE -> R.string.ankiquest_rwkv_no_engine
            RwkvBlocker.UNSUPPORTED_ABI -> R.string.ankiquest_rwkv_unsupported_abi
            RwkvBlocker.NOT_ENOUGH_MEMORY -> R.string.ankiquest_rwkv_not_enough_memory
            RwkvBlocker.NO_MODEL -> R.string.ankiquest_rwkv_no_model
        }
}
