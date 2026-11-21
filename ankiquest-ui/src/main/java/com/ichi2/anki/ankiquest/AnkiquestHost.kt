// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentActivity

/**
 * What the app around AnkiQuest's screens gives them: its own screens, its background refresh, its
 * notification action icon and what only it knows about study. The app installs one when it starts
 * ([install]), as Application.onCreate runs before any widget, receiver or screen.
 */
interface AnkiquestHost {
    /** An intent for the app's [screen]; callers add their own flags and extras. */
    fun intent(
        context: Context,
        screen: AnkiquestScreen,
    ): Intent

    /** The icon of the actions on AnkiQuest's notifications, such as cheering a friend. */
    @get:DrawableRes
    val actionIcon: Int

    /** Whether one of the app's screens is in front. */
    fun inForeground(): Boolean

    /** Shows waiting moments as soon as a screen where studying has stopped is in front. */
    fun presentMomentsSoon()

    /**
     * Whether [activity] is a screen where studying has stopped, such as the deck list or Today.
     * Moments never show anywhere else, above all not over a review.
     */
    fun resting(activity: Activity): Boolean

    /** Keeps the periodic refresh of boards, widgets and notifications scheduled. */
    fun schedule(context: Context)

    /** Refreshes boards, widgets and notifications now. */
    fun refreshNow(context: Context)

    /** Refreshes once more when the player's day ends at [dayEndsAt] (Unix ms). */
    fun refreshAt(
        context: Context,
        dayEndsAt: Long,
    )

    /** The labels [AnkiquestShare.Labels] offers for the card answered at review [at] (Unix ms); null when it is gone. */
    suspend fun conqueredCard(at: Long): AnkiquestShare.Labels?

    /**
     * The name of the deck the card conquered with the answer at [at] (Unix ms) belongs to: null
     * when the app cannot tell, empty when the player chose to show none.
     */
    suspend fun conqueredDeck(at: Long): String?

    /** Lets the player choose the deck name shown for the card conquered at [at]; [chosen] gets the name, or null for none. */
    fun chooseDeck(
        activity: FragmentActivity,
        account: HomeAccount,
        at: Long,
        chosen: (String?) -> Unit,
    )

    companion object {
        @Volatile
        private var installed: AnkiquestHost? = null

        /** The app's host; using this module before the app installed one is a bug in the app. */
        val current: AnkiquestHost
            get() = checkNotNull(installed) { "AnkiquestHost.install was not called" }

        /** Called by the app when it starts, before any screen, widget or receiver of this module runs. */
        fun install(host: AnkiquestHost) {
            installed = host
        }
    }
}

/** The app's screens that AnkiQuest's views open. */
sealed interface AnkiquestScreen {
    /** Today, scrolled to [focus] once it is shown, such as [QUESTS]. */
    data class Today(
        val focus: String? = null,
    ) : AnkiquestScreen {
        companion object {
            const val QUESTS = "quests"
        }
    }

    /** The study library, over any AnkiQuest screen and without opening Today. */
    data object Decks : AnkiquestScreen

    /** Friends; a notification for [account] ([HomeAccount.notificationAccount]) says when another account is signed in. */
    data class Friends(
        val account: String? = null,
    ) : AnkiquestScreen

    /** [user]'s profile, or the player's own. */
    data class Profile(
        val user: String? = null,
    ) : AnkiquestScreen

    /** Friend goals, opened on goal [id] when there is one. */
    data class Challenges(
        val id: Long? = null,
        val account: String? = null,
    ) : AnkiquestScreen

    /** The community calendar. */
    data object Calendar : AnkiquestScreen

    /** The card [user] conquered with the answer at [at] (Unix ms). */
    data class Conquest(
        val user: String,
        val at: Long,
    ) : AnkiquestScreen

    /** AnkiQuest's settings. */
    data object Settings : AnkiquestScreen

    /** The share screen, which sends a picture or link out. */
    data object Share : AnkiquestScreen

    /** The gallery a shared picture is saved to. */
    data object Gallery : AnkiquestScreen

    /** One leaderboard widget's settings. */
    data object WidgetSettings : AnkiquestScreen
}
