// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
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
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Offer
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import kotlin.math.cos
import kotlin.math.sin

/** Account-scoped artwork cache. The server owns the choice; offline UI uses its last answer. */
internal object AnkiquestCompanion {
    private const val KEY_PREFIX = "ankiquestCompanion:"

    /** The cache name of silhouettes ([AnkiquestArt.filtered]). */
    private const val SILHOUETTE = "silhouette"

    /** The companions this app has art for, as the server names them. */
    val drawn: List<String> = Choice.entries.filter { it != Choice.NONE }.map { it.id }

    /** The companions, in the order the picker shows them; [id] is the value the server stores. */
    enum class Choice(
        val id: String,
        @StringRes val title: Int,
    ) {
        AKI("aki", R.string.ankiquest_companion_aki),
        ANKILOPE("ankilope", R.string.ankiquest_companion_ankilope),

        /** Bluma, earned at a milestone like Ankilope ([AnkiquestUnlocks]). */
        COW("cow", R.string.ankiquest_companion_cow),
        NONE("none", R.string.ankiquest_companion_none),
        ;

        companion object {
            fun of(id: String?): Choice = entries.firstOrNull { it.id == id } ?: AKI
        }
    }

    /** The poses a preview card shows, as Aki's: face, fire and sad. */
    private val previewPoses = listOf(R.drawable.aki_face, R.drawable.aki_streak, R.drawable.aki_sad)

    /** [choice]'s preview poses as Aki's, without two that look the same. */
    fun preview(choice: Choice): List<Int> = previewPoses.distinctBy { resource(it, choice) }

    /** Degrees that turn Ankilope's tan fur purple for the interim cow. */
    private const val COW_HUE_DEGREES = 250.0

    /** [ColorMatrix] is 4 rows of 5: red, green, blue, alpha and a constant. */
    private const val MATRIX_COLUMNS = 5
    private const val MATRIX_SIZE = 4 * MATRIX_COLUMNS
    private const val ALPHA_INDEX = 3 * MATRIX_COLUMNS + 3

    /** The silhouette's grey (0-255) and its opacity as a share of the art's. */
    private const val SILHOUETTE_GREY = 128f
    private const val SILHOUETTE_ALPHA = 110f / 255f

    // The CSS hue-rotate() matrix: per output channel, weights for red, green and blue, plus cosine
    // and sine terms of the angle.
    private val hueLuminance = floatArrayOf(0.213f, 0.715f, 0.072f)
    private val hueCos =
        listOf(floatArrayOf(0.787f, -0.715f, -0.072f), floatArrayOf(-0.213f, 0.285f, -0.072f), floatArrayOf(-0.213f, -0.715f, 0.928f))
    private val hueSin =
        listOf(floatArrayOf(-0.213f, -0.715f, 0.928f), floatArrayOf(0.143f, 0.140f, -0.283f), floatArrayOf(-0.787f, 0.715f, 0.072f))

    /**
     * The cow is drawn as Ankilope with its hues rotated until it has art of its own (float3/ankiquest#88),
     * with the matrix of CSS `hue-rotate(250deg)` so every client draws the same purple.
     */
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

    /**
     * A locked companion's art as a grey shadow of its shape, as the desktop draws it: grey at
     * [SILHOUETTE_ALPHA] of the art's own opacity.
     */
    private val silhouetteFilter: ColorFilter by lazy {
        val matrix = FloatArray(MATRIX_SIZE)
        for (row in 0 until 3) {
            matrix[row * MATRIX_COLUMNS + MATRIX_COLUMNS - 1] = SILHOUETTE_GREY
        }
        matrix[ALPHA_INDEX] = SILHOUETTE_ALPHA
        ColorMatrixColorFilter(ColorMatrix(matrix))
    }

    fun normalize(value: String?) = Choice.of(value).id

    /** The current account's companion in [prefs], Aki without an account. */
    fun choice(prefs: SharedPreferences? = AnkiDroidApp.sharedPrefsOrNull()): Choice {
        if (prefs == null) {
            return Choice.AKI
        }
        val account = HomeAccount.current(prefs) ?: return Choice.AKI
        return Choice.of(prefs.getString(KEY_PREFIX + account.scope, null))
    }

    fun selected(prefs: SharedPreferences? = AnkiDroidApp.sharedPrefsOrNull()): String = choice(prefs).id

    fun remember(
        account: HomeAccount,
        value: String,
    ): Boolean {
        if (AnkiquestHomeData.account()?.scope != account.scope) return false
        val previous = selected()
        AnkiDroidApp.sharedPrefs().edit { putString(KEY_PREFIX + account.scope, normalize(value)) }

        // The launcher icon shows the companion's face.
        if (selected() != previous) {
            AnkiquestIcon.onAppStart(AnkiDroidApp.instance)
        }
        return true
    }

    fun visible() = choice() != Choice.NONE

    /**
     * The companions the picker shows to [account] whose companion is [current], in order, with how
     * each is offered; hidden ones are left out.
     */
    fun offers(
        account: HomeAccount?,
        current: Choice,
    ): Map<Choice, Offer> {
        val found = account?.let { AnkiquestUnlocks.found(it) }
        return Choice.entries
            .associateWith { AnkiquestUnlocks.offer(it, found, current) }
            .filterValues { it != Offer.Hidden }
    }

    /**
     * Saves [choice] for [account] on the server; answers the saved choice.
     *
     * @throws HomeAccountChanged when [account] is no longer signed in before the request is sent or once it is answered
     */
    suspend fun choose(
        account: HomeAccount,
        choice: Choice,
    ): Choice {
        val saved = AnkiquestHomeData.saveCompanion(account, choice.id)
        remember(account, saved)
        return Choice.of(saved)
    }

    /**
     * Brings [account]'s unlocks and the server's choice to this phone. Answers whether the shown
     * companion changed.
     *
     * @throws HomeAccountChanged when [account] is no longer signed in before the choice is read or once it is answered
     */
    suspend fun refresh(account: HomeAccount): Boolean {
        syncUnlocks(account)
        val answer = AnkiquestHomeData.companion(account)
        if (choice().id == normalize(answer)) {
            return false
        }
        return remember(account, answer)
    }

    /** [AnkiquestUnlocks.sync], whose failure leaves the unlocks as this phone last read them. */
    private suspend fun syncUnlocks(account: HomeAccount) {
        try {
            AnkiquestUnlocks.sync(account)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest unlocks could not be read")
        }
    }

    /** Aki's art for each pose she has. She has no frozen 2 or 3 art: those show her frozen pose. */
    private val akiPoses =
        mapOf(
            AnkiquestPose.FACE to R.drawable.aki_face,
            AnkiquestPose.ASLEEP to R.drawable.aki_asleep,
            AnkiquestPose.HAPPY to R.drawable.aki_celebrate,
            AnkiquestPose.STREAK to R.drawable.aki_streak,
            AnkiquestPose.STREAK_HOT to R.drawable.aki_streak_hot,
            AnkiquestPose.STREAK_BLAZING to R.drawable.aki_streak_blazing,
            AnkiquestPose.STREAK_LEGEND to R.drawable.aki_streak_legend,
            AnkiquestPose.STREAK_MILESTONE to R.drawable.aki_streak_milestone,
            AnkiquestPose.SAD to R.drawable.aki_sad,
            AnkiquestPose.HOPEFUL to R.drawable.aki_hopeful,
            AnkiquestPose.FROZEN to R.drawable.aki_frozen,
            AnkiquestPose.NEGLECTED to R.drawable.aki_neglected,
        )

    /** Ankilope's art for each pose she has; the cow draws it recoloured. */
    private val ankilopePoses =
        mapOf(
            AnkiquestPose.FACE to R.drawable.ankilope_face,
            AnkiquestPose.ASLEEP to R.drawable.ankilope_asleep,
            AnkiquestPose.HAPPY to R.drawable.ankilope_celebrate,
            // Interim: Ankilope has no on-fire art yet, so she studies (hill, 2026-11-18).
            AnkiquestPose.STREAK to R.drawable.ankilope_study,
            AnkiquestPose.SAD to R.drawable.ankilope_sad,
            AnkiquestPose.HOPEFUL to R.drawable.ankilope_hopeful,
            AnkiquestPose.FROZEN to R.drawable.ankilope_frozen,
            AnkiquestPose.NEGLECTED to R.drawable.ankilope_neglected,
            AnkiquestPose.WELCOME to R.drawable.ankilope_welcome,
            AnkiquestPose.REVIEW to R.drawable.ankilope_study,
        )

    /** The pose each of Aki's pictures shows, so that other companions show theirs. */
    private val akiPictures =
        mapOf(
            R.drawable.aki_face to AnkiquestPose.FACE,
            R.drawable.aki_icon_happy to AnkiquestPose.FACE,
            R.drawable.aki_icon_worried to AnkiquestPose.FACE,
            R.drawable.aki_icon_frozen to AnkiquestPose.FACE,
            R.drawable.aki_icon_sleepy to AnkiquestPose.FACE,
            R.drawable.aki_asleep to AnkiquestPose.ASLEEP,
            R.drawable.aki_celebrate to AnkiquestPose.HAPPY,
            R.drawable.aki_achievement to AnkiquestPose.HAPPY,
            R.drawable.aki_goal to AnkiquestPose.HAPPY,
            R.drawable.aki_deck_conquered to AnkiquestPose.HAPPY,
            R.drawable.aki_level_up to AnkiquestPose.HAPPY,
            R.drawable.aki_streak to AnkiquestPose.STREAK,
            R.drawable.aki_streak_hot to AnkiquestPose.STREAK_HOT,
            R.drawable.aki_streak_blazing to AnkiquestPose.STREAK_BLAZING,
            R.drawable.aki_streak_legend to AnkiquestPose.STREAK_LEGEND,
            R.drawable.aki_streak_milestone to AnkiquestPose.STREAK_MILESTONE,
            R.drawable.aki_at_risk to AnkiquestPose.SAD,
            R.drawable.aki_sad to AnkiquestPose.SAD,
            R.drawable.aki_hopeful to AnkiquestPose.HOPEFUL,
            R.drawable.aki_freeze_used to AnkiquestPose.FROZEN,
            R.drawable.aki_frozen to AnkiquestPose.FROZEN,
            R.drawable.aki_freeze to AnkiquestPose.FROZEN_2,
            R.drawable.aki_neglected to AnkiquestPose.NEGLECTED,
            R.drawable.aki_welcome to AnkiquestPose.WELCOME,
            R.drawable.aki_comeback to AnkiquestPose.WELCOME,
            R.drawable.aki_review to AnkiquestPose.REVIEW,
            R.drawable.aki_minimum to AnkiquestPose.REVIEW,
            R.drawable.aki_winner to AnkiquestPose.WINNER,
            R.drawable.aki_leech to AnkiquestPose.LEECH_DEFEATED,
        )

    /** Ankilope's version of Aki's pictures that are scenes rather than poses. */
    private val ankilopeScenes =
        mapOf(
            R.drawable.aki_nudge to R.drawable.ankilope_nudge,
            R.drawable.aki_backlog to R.drawable.ankilope_backlog,
            R.drawable.aki_place_1 to R.drawable.ankilope_place_1,
            R.drawable.aki_place_2 to R.drawable.ankilope_place_2,
            R.drawable.aki_place_3 to R.drawable.ankilope_place_3,
            R.drawable.aki_challenge to R.drawable.ankilope_challenge,
            R.drawable.aki_challenge_won to R.drawable.ankilope_challenge_won,
            R.drawable.aki_challenge_lost to R.drawable.ankilope_challenge_lost,
        )

    /** Whether [choice] is drawn with Ankilope's art: Ankilope, and the cow recoloured. */
    private fun ankilopeArt(choice: Choice) = choice == Choice.ANKILOPE || choice == Choice.COW

    /** [choice]'s art for [pose], or for its nearest fallback ([AnkiquestPose.chain]) where it has none. */
    @DrawableRes
    fun art(
        pose: AnkiquestPose,
        choice: Choice = choice(),
    ): Int {
        val poses = if (ankilopeArt(choice)) ankilopePoses else akiPoses
        return pose.chain().firstNotNullOf { poses[it] }
    }

    /**
     * [choice]'s version of Aki's picture [aki]: by the pose it shows, or as the same scene. Art that is
     * already [choice]'s, such as [AnkiquestMood.pose]'s, is returned as it is.
     */
    @DrawableRes
    fun resource(
        @DrawableRes aki: Int,
        choice: Choice = choice(),
    ): Int {
        if (!ankilopeArt(choice)) {
            return aki
        }
        val pose = akiPictures[aki]
        if (pose != null) {
            return art(pose, choice)
        }
        return ankilopeScenes[aki] ?: aki
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

    /** [choice]'s version of [aki] as a silhouette, for a locked companion; null when it cannot be decoded. */
    fun silhouette(
        context: Context,
        @DrawableRes aki: Int,
        size: Int,
        choice: Choice,
    ): Bitmap? = AnkiquestArt.filtered(context, resource(aki, choice), size, SILHOUETTE, silhouetteFilter)

    /**
     * Shows [choice]'s version of [aki] in [view], decoded for [size] pixels. [choice] is the chosen
     * companion unless a moment is about another one.
     */
    fun show(
        view: ImageView,
        @DrawableRes aki: Int,
        size: Int,
        choice: Choice = choice(),
    ) {
        view.isVisible = choice != Choice.NONE
        if (!view.isVisible) {
            return
        }
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
