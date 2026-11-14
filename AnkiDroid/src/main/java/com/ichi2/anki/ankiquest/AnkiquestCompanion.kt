// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Unlock
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.sin

/** Account-scoped artwork cache. The server owns the choice; offline UI uses its last answer. */
internal object AnkiquestCompanion {
    private const val KEY_PREFIX = "ankiquestCompanion:"

    /** The companions, in the order the picker shows them; [id] is the value the server stores. */
    enum class Choice(
        val id: String,
        @StringRes val title: Int,
    ) {
        AKI("aki", R.string.ankiquest_companion_aki),
        ANKILOPE("ankilope", R.string.ankiquest_companion_ankilope),

        /** Hidden until unlocked ([AnkiquestUnlocks]). */
        COW("cow", R.string.ankiquest_companion_cow),
        NONE("none", R.string.ankiquest_companion_none),
        ;

        companion object {
            fun of(id: String?): Choice = entries.firstOrNull { it.id == id } ?: AKI
        }
    }

    /** The poses a companion's preview card shows, as Aki's; [resource] maps them to the companion's. */
    val previewPoses = listOf(R.drawable.aki_face, R.drawable.aki_streak, R.drawable.aki_sleeping)

    /** Degrees that turn Ankilope's tan fur purple for the interim cow. */
    private const val COW_HUE_DEGREES = 250.0

    /** [ColorMatrix] is 4 rows of 5: red, green, blue, alpha and a constant. */
    private const val MATRIX_COLUMNS = 5
    private const val MATRIX_SIZE = 4 * MATRIX_COLUMNS
    private const val ALPHA_INDEX = 3 * MATRIX_COLUMNS + 3

    // The standard luminance-preserving hue rotation: per output channel, weights for red, green
    // and blue, plus cosine and sine terms of the angle.
    private val hueLuminance = floatArrayOf(0.213f, 0.715f, 0.072f)
    private val hueCos =
        listOf(floatArrayOf(0.787f, -0.715f, -0.072f), floatArrayOf(-0.213f, 0.285f, -0.072f), floatArrayOf(-0.213f, -0.715f, 0.928f))
    private val hueSin =
        listOf(floatArrayOf(-0.213f, -0.715f, 0.928f), floatArrayOf(0.143f, 0.140f, -0.283f), floatArrayOf(-0.787f, 0.715f, 0.072f))

    /** The cow is drawn as Ankilope with its hues rotated until it has art of its own (float3/ankiquest#88). */
    private val cowFilter: ColorFilter by lazy {
        val radians = Math.toRadians(COW_HUE_DEGREES)
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val matrix = FloatArray(MATRIX_SIZE)
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                matrix[row * MATRIX_COLUMNS + column] = hueLuminance[column] + cos * hueCos[row][column] + sin * hueSin[row][column]
            }
        }
        matrix[ALPHA_INDEX] = 1f
        ColorMatrixColorFilter(ColorMatrix(matrix))
    }

    fun normalize(value: String?) = Choice.of(value).id

    /** The current account's companion, Aki without an account. */
    fun choice(): Choice {
        val prefs = AnkiDroidApp.sharedPrefsOrNull() ?: return Choice.AKI
        val account = HomeAccount.current(prefs) ?: return Choice.AKI
        return Choice.of(prefs.getString(KEY_PREFIX + account.scope, null))
    }

    fun selected(): String = choice().id

    fun remember(
        account: HomeAccount,
        value: String,
    ): Boolean {
        if (AnkiquestHomeData.account()?.scope != account.scope) return false
        AnkiDroidApp.sharedPrefs().edit { putString(KEY_PREFIX + account.scope, normalize(value)) }
        return true
    }

    fun visible() = choice() != Choice.NONE

    /** The companions [account] can choose: the cow only once it is unlocked. */
    fun choices(account: HomeAccount?): List<Choice> =
        Choice.entries.filter { it != Choice.COW || (account != null && AnkiquestUnlocks.has(account, Unlock.COW)) }

    /**
     * Saves [choice] for [account] on the server, or only on this phone while the server cannot
     * hold it (a cow it does not know is unlocked). Answers the saved choice.
     */
    suspend fun choose(
        account: HomeAccount,
        choice: Choice,
    ): Choice {
        // A new unlock reaches the server before the choice that needs it.
        if (localOnly(account, choice)) {
            syncUnlocks(account)
        }
        val saved = if (localOnly(account, choice)) choice.id else AnkiquestHomeData.saveCompanion(account, choice.id)
        remember(account, saved)
        return Choice.of(saved)
    }

    /**
     * Brings [account]'s unlocks and the server's choice to this phone. Answers whether the shown
     * companion changed.
     */
    suspend fun refresh(account: HomeAccount): Boolean {
        // Unlocks first, so a choice the server answers is one this phone can show.
        syncUnlocks(account)
        return adopt(account, AnkiquestHomeData.companion(account))
    }

    /** [AnkiquestUnlocks.sync], whose failure leaves the unlocks as this phone knows them. */
    private suspend fun syncUnlocks(account: HomeAccount) {
        try {
            AnkiquestUnlocks.sync(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest unlocks could not be synced")
        }
    }

    /** Takes the server's [answer] for [account], unless this phone shows a cow the server cannot hold yet. */
    private fun adopt(
        account: HomeAccount,
        answer: String,
    ): Boolean {
        val current = choice()
        if (localOnly(account, current) || current.id == normalize(answer)) {
            return false
        }
        return remember(account, answer)
    }

    private fun localOnly(
        account: HomeAccount,
        choice: Choice,
    ) = choice == Choice.COW && AnkiquestUnlocks.state(account, Unlock.COW) != AnkiquestUnlocks.State.SYNCED

    @DrawableRes
    fun resource(
        @DrawableRes aki: Int,
        choice: Choice = choice(),
    ): Int =
        if (choice != Choice.ANKILOPE && choice != Choice.COW) {
            aki
        } else {
            when (aki) {
                R.drawable.aki_face, R.drawable.aki_icon_happy, R.drawable.aki_icon_worried,
                R.drawable.aki_icon_frozen, R.drawable.aki_icon_sleepy,
                -> R.drawable.ankilope_face
                R.drawable.aki_nudge -> R.drawable.ankilope_nudge
                R.drawable.aki_backlog -> R.drawable.ankilope_backlog
                R.drawable.aki_place_1 -> R.drawable.ankilope_place_1
                R.drawable.aki_place_2 -> R.drawable.ankilope_place_2
                R.drawable.aki_place_3 -> R.drawable.ankilope_place_3
                R.drawable.aki_challenge -> R.drawable.ankilope_challenge
                R.drawable.aki_challenge_won -> R.drawable.ankilope_challenge_won
                R.drawable.aki_challenge_lost -> R.drawable.ankilope_challenge_lost
                R.drawable.aki_welcome, R.drawable.aki_sleeping, R.drawable.aki_comeback,
                -> R.drawable.ankilope_welcome
                R.drawable.aki_celebrate, R.drawable.aki_winner, R.drawable.aki_achievement,
                R.drawable.aki_goal, R.drawable.aki_deck_conquered,
                -> R.drawable.ankilope_celebrate
                R.drawable.aki_review, R.drawable.aki_streak, R.drawable.aki_freeze, R.drawable.aki_freeze_used,
                R.drawable.aki_streak_hot, R.drawable.aki_streak_blazing, R.drawable.aki_streak_legend, R.drawable.aki_at_risk,
                R.drawable.aki_streak_milestone, R.drawable.aki_minimum,
                -> R.drawable.ankilope_study
                else -> aki
            }
        }

    /**
     * [choice]'s version of [aki], decoded for [size] pixels, or null when it cannot be decoded.
     * Every surface draws the companion through here, so the cow's colours apply everywhere,
     * RemoteViews included.
     */
    fun bitmap(
        context: Context,
        @DrawableRes aki: Int,
        size: Int,
        choice: Choice = choice(),
    ): Bitmap? {
        val art = resource(aki, choice)
        if (choice != Choice.COW) {
            return AnkiquestArt.bitmap(context, art, size)
        }
        return AnkiquestArt.filtered(context, art, size, Choice.COW.id, cowFilter)
    }

    /** Shows the chosen companion's version of [aki] in [view], decoded for [size] pixels. */
    fun show(
        view: ImageView,
        @DrawableRes aki: Int,
        size: Int,
    ) {
        view.isVisible = visible()
        if (!view.isVisible) return
        val choice = choice()
        val bitmap = bitmap(view.context, aki, size, choice)
        if (bitmap != null) {
            view.colorFilter = null
            view.setImageBitmap(bitmap)
            return
        }

        // Undecodable art falls back to the drawable, recoloured by the view instead.
        view.setImageResource(resource(aki, choice))
        view.colorFilter = if (choice == Choice.COW) cowFilter else null
    }
}
