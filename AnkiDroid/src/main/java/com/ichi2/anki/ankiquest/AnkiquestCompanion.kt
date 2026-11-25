// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Offer
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The chosen companion and its pictures. Every companion is a pack in [AnkiquestPacks.library]:
 * bundled, downloaded or imported. The server owns the choice of an official companion; offline UI
 * uses its last answer. An imported companion is chosen on this phone only, since the server knows
 * only official ones.
 */
internal object AnkiquestCompanion {
    private const val KEY_PREFIX = "ankiquestCompanion:"

    /** An imported companion chosen on this phone, per account; it wins while its pack is installed. */
    private const val KEY_IMPORTED = "ankiquestCompanionImported:"

    /** The cache name of silhouettes ([AnkiquestArt.filtered]). */
    private const val SILHOUETTE = "silhouette"

    /** A companion by the id the server stores and its pack has; [NONE] shows no companion. */
    @JvmInline
    value class Choice(
        val id: String,
    ) {
        companion object {
            /** The server's default and a free choice. */
            val AKI = Choice("aki")
            val NONE = Choice("none")

            /** [id] as a choice; Aki when it is missing or not a companion id. */
            fun of(id: String?): Choice = id?.takeIf { PackFormat.validId(it) }?.let(::Choice) ?: AKI
        }
    }

    /** The official companions this app can draw, as the companion-art header names them. */
    val drawn: List<String>
        get() =
            AnkiquestPacks
                .libraryOrNull()
                ?.let(::official)
                .orEmpty()
                .map { it.info.id }

    private fun library(context: Context) = AnkiquestPacks.library(context)

    private fun official(library: AnkiquestPackLibrary) = library.companions().filter { it.origin != PackOrigin.IMPORTED }

    /** The poses a preview card shows: face, fire and sad. */
    private val previewPoses = listOf(AnkiquestPose.FACE, AnkiquestPose.STREAK, AnkiquestPose.AT_RISK)

    /** [choice]'s preview poses, without two its pack shows with the same picture. */
    fun preview(
        context: Context,
        choice: Choice,
    ): List<AnkiquestPose> {
        val pack = library(context).companion(choice.id) ?: return emptyList()
        val names = library(context).names()
        // Poses that share a picture show it once: compare the files they resolve to.
        return previewPoses.distinctBy { pose -> names.chain(pose.id).firstNotNullOfOrNull { pack.info.poses[it] } }
    }

    /** [choice]'s name: its pack's, or "No companion". */
    fun name(
        context: Context,
        choice: Choice,
    ): String {
        if (choice == Choice.NONE) {
            return context.getString(R.string.ankiquest_companion_none)
        }
        return library(context).companion(choice.id)?.info?.name ?: choice.id
    }

    /** [ColorMatrix] is 4 rows of 5: red, green, blue, alpha and a constant. */
    private const val MATRIX_COLUMNS = 5
    private const val MATRIX_SIZE = 4 * MATRIX_COLUMNS
    private const val ALPHA_INDEX = 3 * MATRIX_COLUMNS + 3

    /** The silhouette's grey (0-255) and its opacity as a share of the art's. */
    private const val SILHOUETTE_GREY = 128f
    private const val SILHOUETTE_ALPHA = 110f / 255f

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

    /**
     * The current account's companion in [prefs], Aki without an account: an imported companion
     * chosen on this phone while its pack is installed, else the server's last answer.
     */
    fun choice(prefs: SharedPreferences? = AnkiDroidApp.sharedPrefsOrNull()): Choice {
        if (prefs == null) {
            return Choice.AKI
        }
        val account = HomeAccount.current(prefs) ?: return Choice.AKI
        val imported = prefs.getString(KEY_IMPORTED + account.scope, null)?.let { Choice.of(it) }
        if (imported != null && origin(AnkiDroidApp.instance, imported) == PackOrigin.IMPORTED) {
            return imported
        }
        return Choice.of(prefs.getString(KEY_PREFIX + account.scope, null))
    }

    fun selected(prefs: SharedPreferences? = AnkiDroidApp.sharedPrefsOrNull()): String = choice(prefs).id

    /** Where [choice]'s pack came from, or null when none is installed. */
    private fun origin(
        context: Context,
        choice: Choice,
    ): PackOrigin? = library(context).companion(choice.id)?.origin

    /** Keeps the server's [value] for [account]; answers false when [account] is no longer signed in. */
    fun remember(
        account: HomeAccount,
        value: String,
    ): Boolean {
        if (AnkiquestHomeData.account()?.scope != account.scope) return false
        keep(account) { putString(KEY_PREFIX + account.scope, normalize(value)) }
        return true
    }

    /** Applies [change] to [account]'s stored choice, and the launcher icon when the shown companion changed. */
    private fun keep(
        account: HomeAccount,
        change: SharedPreferences.Editor.() -> Unit,
    ) {
        val previous = selected()
        AnkiDroidApp.sharedPrefs().edit { change() }

        // The launcher icon shows the companion's face.
        if (selected() != previous) {
            AnkiquestIcon.onAppStart(AnkiDroidApp.instance)
        }
    }

    fun visible() = choice() != Choice.NONE

    /**
     * The companions the picker shows to [account] whose companion is [current], in the library's
     * order and then none, with how each is offered; hidden ones are left out.
     */
    fun offers(
        context: Context,
        account: HomeAccount?,
        current: Choice,
    ): Map<Choice, Offer> {
        val found = account?.let { AnkiquestUnlocks.found(it) }
        val packs = library(context).companions().associate { Choice(it.info.id) to it.origin }
        return (packs + (Choice.NONE to null))
            .mapValues { (choice, origin) -> AnkiquestUnlocks.offer(choice, origin, found, current) }
            .filterValues { it != Offer.Hidden }
    }

    /**
     * Saves [choice] for [account]: an imported companion on this phone, any other on the server.
     * Answers the saved choice.
     *
     * @throws HomeAccountChanged when [account] is no longer signed in before the request is sent or once it is answered
     */
    suspend fun choose(
        account: HomeAccount,
        choice: Choice,
    ): Choice {
        if (origin(AnkiDroidApp.instance, choice) == PackOrigin.IMPORTED) {
            if (AnkiquestHomeData.account()?.scope != account.scope) {
                throw HomeAccountChanged()
            }
            keep(account) { putString(KEY_IMPORTED + account.scope, choice.id) }
            return choice
        }

        // The server keeps official companions; an imported choice on this phone stops showing.
        val saved = AnkiquestHomeData.saveCompanion(account, choice.id)
        if (AnkiquestHomeData.account()?.scope == account.scope) {
            keep(account) {
                remove(KEY_IMPORTED + account.scope)
                putString(KEY_PREFIX + account.scope, normalize(saved))
            }
        }
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
        val shown = choice()
        remember(account, answer)
        return choice() != shown
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

    /**
     * [choice]'s picture for [pose], decoded for [size] pixels, from the pack that shows [choice]
     * with its fallbacks. Null for no companion, without an installed pack (the slim APK before
     * its first download, a removed import) or when it cannot be decoded. Every surface draws the
     * companion through here, RemoteViews included.
     */
    fun bitmap(
        context: Context,
        pose: AnkiquestPose,
        size: Int,
        choice: Choice = choice(),
    ): Bitmap? {
        if (choice == Choice.NONE) {
            return null
        }
        return AnkiquestArt.packed(context, choice.id, pose.id, size)
    }

    /** [choice]'s picture for [pose] as a silhouette, for a locked companion; null as for [bitmap]. */
    fun silhouette(
        context: Context,
        pose: AnkiquestPose,
        size: Int,
        choice: Choice,
    ): Bitmap? {
        val plain = bitmap(context, pose, size, choice) ?: return null
        return AnkiquestArt.filtered(plain, "$SILHOUETTE:${choice.id}:${pose.id}", silhouetteFilter)
    }

    /**
     * Shows [choice]'s picture for [pose] in [view], decoded for [size] pixels. [choice] is the
     * chosen companion unless a moment is about another one. The view is hidden for no companion
     * and left empty while no pack shows [choice].
     */
    fun show(
        view: ImageView,
        pose: AnkiquestPose,
        size: Int,
        choice: Choice = choice(),
    ) {
        view.isVisible = choice != Choice.NONE
        if (!view.isVisible) {
            return
        }
        val bitmap = bitmap(view.context, pose, size, choice)
        if (bitmap == null) {
            view.setImageDrawable(null)
            return
        }
        view.setImageBitmap(bitmap)
    }
}
