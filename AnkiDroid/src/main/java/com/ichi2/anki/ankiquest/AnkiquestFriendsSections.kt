// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.CommonString
import com.ichi2.anki.R

/** Friends -> independent sections; an unavailable overview does not block either one. */
internal class AnkiquestFriendsSections(
    private val activity: AnkiActivity,
    savedState: Bundle?,
    undo: AnkiquestUndo,
) {
    enum class Destination { FEED, LEADERBOARD }

    /** Friends overview -> group picker -> scoped board; only ready membership can select a group. */
    sealed interface Groups {
        data object Loading : Groups

        data object Unavailable : Groups

        data class Ready(
            val values: List<AnkiquestSocial.Group>,
        ) : Groups
    }

    private val restored = savedState?.takeIf { it.getString(STATE_ACCOUNT) == AnkiquestHomeData.account()?.scope }
    private val initial = Destination.entries.firstOrNull { it.name == activity.intent.getStringExtra(EXTRA_SECTION) }
    private var pendingTarget = initial.takeIf { savedState == null }
    private val feed = FeedSection(activity, restored, initial, undo)
    private val board =
        LeaderboardSection(
            activity,
            restored,
            initial,
            activity.intent.getStringExtra(AnkiquestLeaderboardActivity.EXTRA_PERIOD).takeIf { savedState == null },
        )
    private val root =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(feed.root)
            addView(board.root)
        }

    fun attach(
        parent: LinearLayout,
        groups: Groups,
    ) {
        (root.parent as? ViewGroup)?.removeView(root)
        parent.addView(root)
        board.updateGroups(groups)
    }

    fun refresh() {
        feed.refresh()
        board.refresh()
    }

    fun save(out: Bundle) {
        out.putString(STATE_ACCOUNT, AnkiquestHomeData.account()?.scope)
        feed.save(out)
        board.save(out)
    }

    fun showTarget() {
        when (pendingTarget) {
            Destination.FEED -> feed.showHeader()
            Destination.LEADERBOARD -> board.showHeader()
            null -> return
        }
        pendingTarget = null
    }

    companion object {
        private const val EXTRA_SECTION = "ankiquest.friends_section"
        private const val STATE_ACCOUNT = "ankiquest.friends_sections_account"

        fun intent(
            context: Context,
            destination: Destination,
        ): Intent = AnkiquestFriendsActivity.intent(context).putExtra(EXTRA_SECTION, destination.name)
    }
}

/** Expansion and filters survive parent rerenders; only expanded sections load. */
internal abstract class FriendsSection(
    protected val activity: AnkiActivity,
    target: AnkiquestFriendsSections.Destination,
    @StringRes title: Int,
    @StringRes loadingLabel: Int,
    contentId: Int,
    loadingId: Int,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
) : ContextWrapper(activity) {
    protected val scroll: NestedScrollView = activity.findViewById(R.id.aq_feed_scroll)
    protected val lifecycleScope get() = activity.lifecycleScope
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    protected val content =
        LinearLayout(activity).apply {
            id = contentId
            orientation = LinearLayout.VERTICAL
        }
    protected val progress =
        ProgressBar(activity).apply {
            id = loadingId
            contentDescription = getString(loadingLabel)
            isVisible = false
        }
    private val stateKey = "ankiquest.friends_expanded.${target.name}"
    private var expanded = saved?.getBoolean(stateKey) ?: (initial == target)
    private val heading =
        AnkiquestBoardViews(activity).button(root, getString(title)) {
            expanded = !expanded
            showExpansion()
            if (expanded) {
                reload()
            }
        }

    init {
        ViewCompat.setAccessibilityHeading(heading, true)
        root.addView(progress)
        root.addView(content)
        showExpansion()
    }

    private fun showExpansion() {
        content.isVisible = expanded
        heading.contentDescription = "${heading.text}, ${getString(if (expanded) CommonString.collapse else CommonString.expand)}"
        if (!expanded) {
            progress.isVisible = false
        }
    }

    fun refresh() {
        if (expanded) {
            reload()
        }
    }

    fun save(out: Bundle) {
        out.putBoolean(stateKey, expanded)
        saveFilters(out)
    }

    fun showHeader() {
        heading.post {
            val bounds = Rect(0, 0, heading.width, heading.height)
            scroll.offsetDescendantRectToMyCoords(heading, bounds)
            scroll.scrollTo(0, bounds.top)
        }
    }

    protected abstract fun reload()

    protected abstract fun saveFilters(out: Bundle)
}
