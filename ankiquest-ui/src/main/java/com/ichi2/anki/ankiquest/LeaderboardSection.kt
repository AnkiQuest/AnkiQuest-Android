// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.ichi2.anki.CommonString
import com.ichi2.anki.ankiquest.ui.R
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection.HTTP_CONFLICT
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED

internal class LeaderboardSection(
    activity: AppCompatActivity,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
    launchPeriod: String?,
) : FriendsSection(
        activity,
        AnkiquestFriendsSections.Destination.LEADERBOARD,
        R.string.ankiquest_dashboard_title,
        R.string.aq_board_loading,
        R.id.aq_friends_board_content,
        R.id.aq_friends_board_loading,
        saved,
        initial,
    ) {
    private enum class Scope { FRIENDS, GROUP, GLOBAL }

    private enum class Support { UNKNOWN, AVAILABLE, UNAVAILABLE }

    private enum class Refresh { CACHED_SCOPES, CHECK_SCOPES }

    private enum class HiddenPanel { CLOSED, OPEN }

    /** Discovery -> ready -> one save; stale account results never replace this state. */
    private sealed interface Hides {
        data object Unknown : Hides

        data object Unavailable : Hides

        data class Loading(
            val panel: HiddenPanel,
        ) : Hides

        data class Failed(
            val panel: HiddenPanel,
            @StringRes val message: Int,
        ) : Hides

        data class Ready(
            val players: List<AnkiquestGlobalHides.Player>,
            val panel: HiddenPanel,
            val message: String? = null,
        ) : Hides

        data class Saving(
            val before: Ready,
        ) : Hides
    }

    private sealed interface Selection {
        data object Friends : Selection

        data object Global : Selection

        data class Group(
            val id: Long?,
        ) : Selection
    }

    private var account: HomeAccount? = HomeAccount.current()
    private var period = AnkiquestBoards.Period.WEEK
    private var selection: Selection = Selection.Friends
    private var groups: AnkiquestFriendsSections.Groups = AnkiquestFriendsSections.Groups.Loading
    private var scopes = Support.UNKNOWN
    private val availableGroups get() = (groups as? AnkiquestFriendsSections.Groups.Ready)?.values.orEmpty()
    private val group get() = (selection as? Selection.Group)?.id
    private val scope
        get() =
            when (selection) {
                Selection.Friends -> Scope.FRIENDS
                Selection.Global -> Scope.GLOBAL
                is Selection.Group -> Scope.GROUP
            }
    private var rows: AnkiquestBoards.Leaderboard? = null
    private var weekEnds: Long? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null
    private var generation = 0
    private var loadJob: Job? = null

    private var hides: Hides = Hides.Unknown
    private var hideGeneration = 0
    private var hideJob: Job? = null

    init {
        // Only a fresh link supplies a period; recreation uses this account's saved selection.
        period = AnkiquestBoards.Period.of(saved?.getString(STATE_PERIOD) ?: launchPeriod)
        selection =
            when (saved?.getString(STATE_SCOPE)) {
                Scope.GLOBAL.name -> Selection.Global
                Scope.GROUP.name -> Selection.Group(saved.getLong(STATE_GROUP).takeIf { it > 0 })
                else -> Selection.Friends
            }
        if (saved == null) {
            val addressed = activity.intent.getStringExtra(AnkiquestFriendsSections.EXTRA_BOARD_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) {
                status = getString(R.string.aq_board_account_changed)
            }
        }
    }

    override fun reload() = load(Refresh.CHECK_SCOPES)

    /** Reuses Friends' overview, including membership changes, without another social request. */
    fun updateGroups(next: AnkiquestFriendsSections.Groups) {
        if (account?.scope != HomeAccount.current()?.scope || groups == next) {
            return
        }
        groups = next
        if (scope == Scope.GROUP) {
            if (next is AnkiquestFriendsSections.Groups.Ready && group != null && availableGroups.none { it.id == group }) {
                selection = Selection.Friends
                status = getString(R.string.aq_board_group_missing)
            } else if (group == null) {
                selection = Selection.Group(availableGroups.firstOrNull()?.id)
            }
            rows = null
            refresh()
        } else {
            render()
        }
    }

    override fun saveFilters(out: Bundle) {
        if (account?.scope != HomeAccount.current()?.scope) {
            return
        }
        out.putString(STATE_PERIOD, period.query)
        out.putString(STATE_SCOPE, scope.name)
        group?.let { out.putLong(STATE_GROUP, it) }
    }

    /** Loads [period]'s board for the current account, and when the week resets. */
    private fun load(refresh: Refresh = Refresh.CACHED_SCOPES) {
        val current = HomeAccount.current()
        if (current?.scope != account?.scope) {
            hideJob?.cancel()
            hideGeneration++
            hides = Hides.Unknown
            account = current
            rows = null
            period = AnkiquestBoards.Period.WEEK
            selection = Selection.Friends
            groups = AnkiquestFriendsSections.Groups.Loading
            scopes = Support.UNKNOWN
            weekEnds = null
            status = null
        }
        loadJob?.cancel()
        val turn = ++generation
        val asked = period
        failure = null
        if (current == null) {
            rows = null
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                var gone = false
                var groupDenied = false
                val problem =
                    boardAttempt {
                        if (scopes == Support.UNKNOWN || refresh == Refresh.CHECK_SCOPES) {
                            val supported = optionalScopes(current)
                            if (turn != generation || current.scope != HomeAccount.current()?.scope) {
                                return@boardAttempt
                            }
                            if (supported != null) {
                                scopes = supported
                            }
                        }
                        if (scopes == Support.UNAVAILABLE && scope != Scope.FRIENDS) {
                            selection = Selection.Friends
                            rows = null
                            status = getString(R.string.ankiquest_widget_global_unavailable)
                        }
                        if (scope != Scope.FRIENDS && scopes != Support.AVAILABLE) {
                            throw IOException("Leaderboard scopes unavailable")
                        }
                        val source =
                            when (scope) {
                                Scope.FRIENDS -> AnkiquestBoards.Source.Friends
                                Scope.GLOBAL -> AnkiquestBoards.Source.Global
                                Scope.GROUP -> {
                                    val chosen = availableGroups.firstOrNull { it.id == group }
                                    if (chosen == null) {
                                        rows = null
                                        return@boardAttempt
                                    }
                                    AnkiquestBoards.Source.Group(chosen.id)
                                }
                            }
                        try {
                            val board = AnkiquestBoards.leaderboard(current, asked, source)
                            val ends = if (asked == AnkiquestBoards.Period.WEEK) optionalWeekEnds(current) else weekEnds
                            if (turn == generation && current.scope == HomeAccount.current()?.scope) {
                                rows = board
                                weekEnds = ends
                            }
                        } catch (e: AnkiquestHttpException) {
                            when {
                                e.code == HTTP_FORBIDDEN && source is AnkiquestBoards.Source.Group -> {
                                    groupDenied = true
                                }
                                e.code == HTTP_NOT_FOUND -> gone = true
                                else -> throw e
                            }
                        }
                    }
                if (turn != generation || current.scope != HomeAccount.current()?.scope) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    load()
                    return@launch
                }
                // A superseded group's failure must not clear the current selection's rows.
                if (groupDenied) {
                    rows = null
                }
                failure =
                    when {
                        groupDenied -> R.string.aq_board_group_unavailable
                        gone -> R.string.aq_server_outdated
                        else -> problem
                    }
                loading = false
                render()
                if (scope == Scope.GLOBAL && (hides == Hides.Unknown || refresh == Refresh.CHECK_SCOPES)) {
                    loadHidden(current)
                }
                if (problem == null) loadPictures(current)
            }
    }

    /** Hidden players are optional and load independently of the standings. */
    private fun loadHidden(current: HomeAccount) {
        if (hides is Hides.Saving || current.scope != HomeAccount.current()?.scope) {
            return
        }
        val panel =
            when (val previous = hides) {
                is Hides.Ready -> previous.panel
                is Hides.Failed -> previous.panel
                is Hides.Loading -> previous.panel
                else -> HiddenPanel.CLOSED
            }
        hideJob?.cancel()
        val turn = ++hideGeneration
        hides = Hides.Loading(panel)
        render()
        hideJob =
            lifecycleScope.launch {
                val next =
                    try {
                        AnkiquestGlobalHides.load(current)?.let { Hides.Ready(it, panel) } ?: Hides.Unavailable
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Hides.Failed(panel, hideFailure(e))
                    }
                if (turn != hideGeneration || current.scope != HomeAccount.current()?.scope) {
                    return@launch
                }
                hides = next
                render()
            }
    }

    private fun hiddenReady(): Hides.Ready? =
        when (val state = hides) {
            is Hides.Ready -> state
            is Hides.Saving -> state.before
            else -> null
        }

    private fun hideOwner(current: HomeAccount): Boolean =
        scope == Scope.GLOBAL && content.isShown && AnkiquestPostStudyPrompts.resumed(activity) &&
            account?.scope == current.scope && HomeAccount.current()?.scope == current.scope

    /** Save once, then reload the selected period so the server keeps ownership of rank gaps. */
    private fun changeHidden(
        current: HomeAccount,
        user: String,
        action: AnkiquestGlobalHides.Action,
    ) {
        val before = hides as? Hides.Ready ?: return
        if (!hideOwner(current)) {
            return
        }
        val allowed =
            when (action) {
                AnkiquestGlobalHides.Action.HIDE ->
                    rows?.standings.orEmpty().any { it.user == user && it.user != current.user && !it.hasToday }
                AnkiquestGlobalHides.Action.SHOW -> before.panel == HiddenPanel.OPEN && before.players.any { it.user == user }
            }
        if (!allowed) {
            return
        }
        val turn = ++hideGeneration
        hides = Hides.Saving(before.copy(message = null))
        render()
        hideJob =
            lifecycleScope.launch {
                val next =
                    try {
                        val players = AnkiquestGlobalHides.change(current, user, action)
                        val message =
                            if (action ==
                                AnkiquestGlobalHides.Action.HIDE
                            ) {
                                R.string.aq_board_hidden_saved
                            } else {
                                R.string.aq_board_shown_saved
                            }
                        Hides.Ready(players, before.panel, getString(message))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (turn == hideGeneration && current.scope == HomeAccount.current()?.scope) {
                            hides = before.copy(message = getString(hideFailure(e)))
                            render()
                        }
                        return@launch
                    }
                if (turn != hideGeneration || current.scope != HomeAccount.current()?.scope) {
                    return@launch
                }
                hides = next
                if (scope == Scope.GLOBAL) {
                    rows = null
                    load()
                } else {
                    render()
                }
            }
    }

    @StringRes
    private fun hideFailure(error: Exception): Int =
        when ((error as? AnkiquestHttpException)?.code) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> R.string.aq_home_auth
            HTTP_NOT_FOUND -> R.string.aq_board_hide_gone
            HTTP_CONFLICT -> R.string.aq_board_hide_limit
            else -> R.string.aq_board_hide_failed
        }

    /** Optional controls must not prevent a normal Friends board from loading. */
    private suspend fun optionalScopes(current: HomeAccount): Support? =
        try {
            if (AnkiquestBoards.hasScopes(current)) {
                Support.AVAILABLE
            } else {
                Support.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: AnkiquestHttpException) {
            if (e.code == HTTP_UNAUTHORIZED || e.code == HTTP_FORBIDDEN) {
                throw e
            }
            if (e.code == HTTP_NOT_FOUND) {
                Support.UNAVAILABLE
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }

    /** When the week resets; the board shows without it when the server cannot say. */
    private suspend fun optionalWeekEnds(current: HomeAccount): Long? =
        try {
            AnkiquestBoards.weekEnds(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeAccountChanged) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest week end unavailable")
            null
        }

    private fun loadPictures(current: HomeAccount) {
        val users = rows?.standings.orEmpty().map { it.user }
        if (users.isEmpty()) return
        lifecycleScope.launch {
            try {
                AnkiquestAvatars.refresh(users)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "AnkiQuest pictures unavailable")
                return@launch
            }
            if (account?.scope == current.scope && !loading) render()
        }
    }

    private fun render() {
        val position = scroll.scrollY
        content.removeAllViews()
        progress.isVisible = loading && content.isVisible
        status?.let { line ->
            views.text(content, line, small = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        }
        val current = account
        if (current == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_board_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        if (scopes == Support.AVAILABLE) {
            scopeChoices()
            if (scope == Scope.GROUP) {
                val available = availableGroups
                if (available.isEmpty()) {
                    val explanation =
                        when (groups) {
                            AnkiquestFriendsSections.Groups.Loading -> R.string.ankiquest_widget_settings_groups_loading
                            AnkiquestFriendsSections.Groups.Unavailable -> R.string.ankiquest_widget_settings_groups_failed
                            is AnkiquestFriendsSections.Groups.Ready -> R.string.aq_friends_groups_empty
                        }
                    views.text(content, getString(explanation), small = true)
                } else {
                    views.choices(content, available.map { it.id to it.name }, selected = group, enabled = true) { chosen ->
                        if (group != chosen) {
                            selection = Selection.Group(chosen)
                            rows = null
                            load()
                        } else {
                            render()
                        }
                    }
                }
            }
        }
        views.choices(
            content,
            AnkiquestBoards.Period.entries.map { it to getString(it.label) },
            selected = period,
            enabled = true,
        ) { choice ->
            if (choice != period) {
                period = choice
                rows = null
                load()
            } else {
                render()
            }
        }
        if (scope == Scope.GLOBAL) {
            hiddenControls(current)
        }
        views.section(content, getString(R.string.aq_board_xp_in, getString(periodPhrase(period))))
        weekEnds?.takeIf { period == AnkiquestBoards.Period.WEEK }?.let { ends ->
            val now = TimeManager.time.intTimeMS()
            if (ends > now) {
                val left = DateUtils.getRelativeTimeSpanString(ends, now, DateUtils.MINUTE_IN_MILLIS)
                views.text(content, getString(R.string.aq_board_resets, left), small = true)
            }
        }
        val board = rows?.standings
        val lock = rows?.lock
        when {
            board == null -> if (loading) views.text(content, getString(R.string.aq_board_loading), small = true)
            lock != null -> {
                // Others stay hidden until the player's first upload; only their own row can show.
                board.forEachIndexed { index, row -> row(row.rank ?: (index + 1), row, row.user == current.user, board.first().xp) }
                if (!loading) empty(lock.text)
            }
            board.isEmpty() -> if (!loading) empty(R.string.aq_board_empty)
            board.all { it.xp == 0L } && scope != Scope.GLOBAL -> if (!loading) empty(R.string.aq_board_no_xp)
            else -> {
                standing(current, board)
                board.forEachIndexed { index, row -> row(row.rank ?: (index + 1), row, row.user == current.user, board.first().xp) }
            }
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        views.button(content, getString(R.string.aq_board_my_profile)) { openProfile(current.user) }
        views.button(content, getString(R.string.aq_calendar_title)) {
            startActivity(AnkiquestHost.current.intent(this, AnkiquestScreen.Calendar))
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun hiddenControls(current: HomeAccount) {
        when (val state = hides) {
            Hides.Unknown, Hides.Unavailable -> return
            is Hides.Loading -> {
                views.text(content, getString(R.string.aq_board_hidden_loading), small = true)
                return
            }
            is Hides.Failed -> {
                views.text(content, getString(state.message), small = true)
                views.button(content, getString(CommonString.retry)) {
                    if (hideOwner(current)) {
                        loadHidden(current)
                    }
                }
                return
            }
            else -> Unit
        }
        val ready = hiddenReady() ?: return
        val saving = hides is Hides.Saving
        views
            .button(content, getString(R.string.aq_board_hidden_players)) {
                if (hides === ready && hideOwner(current)) {
                    hides = ready.copy(panel = if (ready.panel == HiddenPanel.CLOSED) HiddenPanel.OPEN else HiddenPanel.CLOSED)
                    render()
                }
            }.apply {
                isEnabled = !saving
                val toggle = if (ready.panel == HiddenPanel.OPEN) CommonString.collapse else CommonString.expand
                contentDescription = "$text, ${getString(toggle)}"
            }
        val message = if (saving) getString(R.string.ankiquest_goal_saving) else ready.message
        message?.let {
            views.text(content, it, small = true).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (ready.panel != HiddenPanel.OPEN) {
            return
        }
        val panel = views.card(content)
        views.text(panel, getString(R.string.aq_board_hidden_explained), small = true)
        if (ready.players.isEmpty()) {
            views.text(panel, getString(R.string.aq_board_hidden_empty))
        }
        for (player in ready.players) {
            views.text(panel, player.display, heading = true)
            views
                .button(panel, getString(R.string.aq_board_show_again)) {
                    changeHidden(current, player.user, AnkiquestGlobalHides.Action.SHOW)
                }.apply {
                    isEnabled = !saving
                    contentDescription = getString(R.string.aq_board_show_person, player.display)
                }
        }
    }

    /** All three scopes stay visible at large text sizes instead of hiding the active choice offscreen. */
    private fun scopeChoices() {
        val choices = ChipGroup(this).apply { isSingleSelection = true }
        val labels =
            listOf(
                Scope.FRIENDS to R.string.ankiquest_nav_friends,
                Scope.GROUP to R.string.aq_board_scope_group,
                Scope.GLOBAL to R.string.ankiquest_widget_who_global,
            )
        for ((choice, label) in labels) {
            choices.addView(
                Chip(this).apply {
                    text = getString(label)
                    textSize = 15f
                    minimumHeight = views.dp(48)
                    chipMinHeight = maxOf(views.dp(32).toFloat(), paint.fontMetrics.run { descent - ascent } + views.dp(14))
                    isCheckable = true
                    isChecked = choice == scope
                    isCheckedIconVisible = false
                    setTextColor(getColor(if (isChecked) R.color.aq_home_primary_text else R.color.aq_home_text))
                    chipBackgroundColor =
                        ColorStateList.valueOf(getColor(if (isChecked) R.color.aq_home_primary else R.color.aq_home_surface))
                    chipStrokeWidth = if (isChecked) 0f else views.dp(1).toFloat()
                    chipStrokeColor = ColorStateList.valueOf(getColor(R.color.aq_home_outline))
                    setOnClickListener {
                        if (scope != choice) {
                            selection =
                                when (choice) {
                                    Scope.FRIENDS -> Selection.Friends
                                    Scope.GLOBAL -> Selection.Global
                                    Scope.GROUP -> Selection.Group(availableGroups.firstOrNull()?.id)
                                }
                            rows = null
                            status = null
                            load()
                        } else {
                            render()
                        }
                    }
                },
            )
        }
        content.addView(choices)
    }

    private fun empty(
        @StringRes message: Int,
    ) {
        content.addView(AnkiquestAki.image(this, R.drawable.aki_welcome, 72))
        views.text(content, getString(message), heading = true)
    }

    /** Where the player stands, with the companion's podium art for the top three. */
    private fun standing(
        current: HomeAccount,
        board: List<AnkiquestBoards.Standing>,
    ) {
        val index = board.indexOfFirst { it.user == current.user }
        if (index < 0) return
        val own = board[index]
        val place = own.rank ?: (index + 1)
        val panel = views.card(content, tinted = true, horizontal = true)
        AnkiquestArt.place(place)?.takeIf { board[index].xp > 0 && AnkiquestCompanion.visible() }?.let { art ->
            panel.addView(AnkiquestAki.image(this, art, 56))
        }
        views.column(panel).also { column ->
            val position =
                if (own.rank != null) {
                    getString(R.string.aq_board_your_rank, place)
                } else {
                    getString(R.string.aq_board_your_place, place, board.size)
                }
            views.text(column, position, heading = true)
            if (own.shown == false) {
                views.text(column, getString(R.string.aq_board_private_row), small = true)
            }
        }
    }

    private fun row(
        place: Int,
        row: AnkiquestBoards.Standing,
        mine: Boolean,
        leader: Long,
    ) {
        // Large text needs the full card width for study status and XP.
        // A private action needs its own width instead of squeezing the player's name and streak.
        val hiding = scope == Scope.GLOBAL && !mine && !row.hasToday && hiddenReady() != null
        val stacked = resources.configuration.fontScale >= STACKED_TEXT_SCALE || hiding
        val panel = views.card(content, tinted = mine, horizontal = !stacked)
        val identity =
            if (stacked) {
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    panel.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            } else {
                panel
            }
        val medal = AnkiquestArt.medal(place)
        if (medal != null) {
            identity.addView(
                ImageView(this).apply {
                    setImageResource(medal)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(views.dp(28), views.dp(28)),
            )
        } else {
            identity.addView(
                TextView(this).apply {
                    text = views.number(place.toLong())
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minWidth = views.dp(28)
                    maxLines = 1
                    setTextColor(getColor(R.color.aq_home_muted))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
        }
        views.avatar(identity, row.user, row.display, 40)
        val details = views.column(identity)
        val name = if (mine) getString(R.string.aq_challenges_you, row.display) else row.display
        views.text(details, name, heading = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, false)
        }
        val streak = streakLine(row)
        val summary = if (stacked) panel else details
        views
            .text(
                summary,
                listOfNotNull(getString(R.string.ankiquest_widget_level, row.level), streak).joinToString(" · "),
                small = true,
            ).apply {
                if (row.streak > 0 && row.hasToday) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(streakIcon(row.state), 0, 0, 0)
                    compoundDrawablePadding = views.dp(4)
                }
            }
        views.bar(summary, AnkiquestBoards.percent(row.xp, leader.coerceAtLeast(1)))
        val alignment = if (stacked) Gravity.START else Gravity.END
        val score =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = alignment
            }
        panel.addView(
            score,
            LinearLayout
                .LayoutParams(
                    if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = if (stacked) 0 else views.dp(8)
                },
        )
        views.text(score, getString(R.string.aq_board_xp, views.number(row.xp)), heading = true).apply {
            gravity = alignment
            ViewCompat.setAccessibilityHeading(this, false)
        }
        if (row.hasToday) {
            views.text(score, getString(R.string.aq_board_today_reviews, views.number(row.todayReviews)), small = true).gravity = alignment
        }
        // Around the weekly switch, XP that counts for the week before or after the board's.
        val now = TimeManager.time.intTimeMS()
        val week = period == AnkiquestBoards.Period.WEEK
        val closing = row.closingXp(now)?.takeIf { week }
        val next = row.nextXp(now, weekEnds)?.takeIf { week }
        closing?.let { xp ->
            views.text(score, getString(R.string.aq_board_closing_xp, views.number(xp)), small = true).gravity = alignment
        }
        next?.let { xp ->
            views.text(score, getString(R.string.aq_board_next_xp, views.number(xp)), small = true).gravity = alignment
        }
        val current = account
        if (hiding && current != null) {
            views
                .button(score, getString(R.string.aq_board_hide_for_me)) {
                    changeHidden(current, row.user, AnkiquestGlobalHides.Action.HIDE)
                }.apply {
                    isEnabled = hides is Hides.Ready
                    contentDescription = getString(R.string.aq_board_hide_person, row.display)
                }
        }
        panel.contentDescription =
            listOfNotNull(
                getString(R.string.aq_board_row_description, place, name, row.level, views.number(row.xp)),
                streak?.let {
                    if (row.hasToday) {
                        getString(
                            R.string.ankiquest_streak_description,
                            views.number(row.streak.toLong()),
                            it,
                        )
                    } else {
                        it
                    }
                },
                closing?.let { getString(R.string.aq_board_closing_xp, views.number(it)) },
                next?.let { getString(R.string.aq_board_next_xp, views.number(it)) },
                getString(R.string.aq_board_private_row).takeIf { mine && row.shown == false },
            ).joinToString(". ")
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }
        panel.foreground = AppCompatResources.getDrawable(this, ripple.resourceId)
        panel.setOnClickListener { openProfile(row.user) }
    }

    /** Today's status for a row with a streak, in words; null without a streak. */
    private fun streakLine(row: AnkiquestBoards.Standing): String? {
        if (row.streak <= 0) return null
        if (!row.hasToday) {
            return getString(R.string.aq_board_streak_count, views.number(row.streak.toLong()))
        }
        val status = AnkiquestDay.words(this, row.state, row.todayMinutes, row.streakMinutes)
        return "${views.number(row.streak.toLong())} · $status"
    }

    private fun streakIcon(state: AnkiquestDay.State): Int =
        when (state) {
            AnkiquestDay.State.STUDIED -> R.drawable.ankiquest_streak_studied
            AnkiquestDay.State.PROTECTED -> R.drawable.ankiquest_streak_protected
            else -> R.drawable.ankiquest_streak_pending
        }

    private fun openProfile(user: String) = startActivity(AnkiquestHost.current.intent(this, AnkiquestScreen.Profile(user)))

    private fun settings() = startActivity(AnkiquestHost.current.intent(this, AnkiquestScreen.Settings))

    companion object {
        private const val STATE_PERIOD = "ankiquest.board_period"
        private const val STATE_SCOPE = "ankiquest.board_scope"
        private const val STATE_GROUP = "ankiquest.board_group"
        private const val STACKED_TEXT_SCALE = 1.5f

        /** The phrase for [period] after "XP", such as "this week". */
        @StringRes
        private fun periodPhrase(period: AnkiquestBoards.Period): Int =
            when (period) {
                AnkiquestBoards.Period.HOUR -> R.string.ankiquest_period_hour
                AnkiquestBoards.Period.DAY -> R.string.ankiquest_period_day
                AnkiquestBoards.Period.WEEK -> R.string.ankiquest_period_week
                AnkiquestBoards.Period.MONTH -> R.string.ankiquest_period_month
                AnkiquestBoards.Period.YEAR -> R.string.ankiquest_period_year
                AnkiquestBoards.Period.ALL -> R.string.ankiquest_period_all
            }
    }
}
