// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.Time
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import timber.log.Timber
import java.io.IOException
import java.util.Calendar

internal class FeedSection(
    activity: AnkiActivity,
    saved: Bundle?,
    initial: AnkiquestFriendsSections.Destination?,
    undo: AnkiquestUndo,
) : FriendsSection(
        activity,
        AnkiquestFriendsSections.Destination.FEED,
        R.string.aq_home_activity,
        R.string.aq_feed_loading,
        R.id.aq_friends_feed_content,
        R.id.aq_friends_feed_loading,
        saved,
        initial,
    ) {
    private enum class Box { INBOX, SENT }

    private var account: HomeAccount? = AnkiquestHomeData.account()
    private var box = Box.INBOX
    private var category = AnkiquestFeed.Category.ALL
    private var unreadOnly = false
    private var page: AnkiquestFeed.Page? = null
    private var sent: AnkiquestFeed.SentPage? = null
    private var loading = false

    @StringRes private var failure: Int? = null
    private var status: String? = null

    /** Rows with a cheer, reply or read request on its way, whose buttons wait for it. */
    private val busy = mutableSetOf<Long>()
    private var markingAll = false
    private val pictures = mutableMapOf<String, Bitmap?>()
    private val deckAlerts = AnkiquestDeckAlerts(activity, undo = undo)
    private var generation = 0
    private var loadJob: Job? = null

    init {
        box = Box.entries.firstOrNull { it.name == saved?.getString(STATE_BOX) } ?: Box.INBOX
        category = AnkiquestFeed.Category.entries.firstOrNull { it.name == saved?.getString(STATE_CATEGORY) }
            ?: AnkiquestFeed.Category.ALL
        unreadOnly = saved?.getBoolean(STATE_UNREAD) ?: false
        if (saved == null) {
            val addressed = activity.intent.getStringExtra(AnkiquestFriendsSections.EXTRA_FEED_ACCOUNT)
            if (addressed != null && addressed != account?.notificationAccount) {
                status = getString(R.string.aq_home_account_route_changed)
            }
        }
    }

    override fun saveFilters(out: Bundle) {
        if (account?.scope != AnkiquestHomeData.account()?.scope) {
            return
        }
        out.putString(STATE_BOX, box.name)
        out.putString(STATE_CATEGORY, category.name)
        out.putBoolean(STATE_UNREAD, unreadOnly)
    }

    override fun reload() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            account = current
            pictures.clear()
            box = Box.INBOX
            category = AnkiquestFeed.Category.ALL
            unreadOnly = false
            status = null
            // The old account's changes no longer answer here.
            busy.clear()
            markingAll = false
        }
        page = null
        sent = null
        load(more = false)
    }

    private fun load(more: Boolean) {
        val current = account
        loadJob?.cancel()
        val turn = ++generation
        failure = null
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        loadJob =
            lifecycleScope.launch {
                val problem =
                    attempt {
                        when (box) {
                            Box.INBOX -> {
                                val before = if (more) page?.nextBefore else null
                                val next = AnkiquestFeed.page(current, category, unreadOnly, before)
                                if (turn == generation && current.scope == AnkiquestHomeData.account()?.scope) {
                                    page = if (before == null) next else page?.let { append(it, next) } ?: next
                                    if (next.unfiltered || next.legacy) {
                                        category = AnkiquestFeed.Category.ALL
                                        unreadOnly = false
                                    }
                                }
                            }
                            Box.SENT -> {
                                val before = if (more) sent?.nextBefore else null
                                val next = AnkiquestFeed.sent(current, before)
                                if (turn == generation && current.scope == AnkiquestHomeData.account()?.scope) {
                                    sent =
                                        sent?.takeIf { before != null }?.let { old ->
                                            AnkiquestFeed.SentPage(
                                                old.items +
                                                    next.items.filter { new ->
                                                        old.items.none { it.item.id == new.item.id }
                                                    },
                                                next.nextBefore,
                                            )
                                        } ?: next
                                }
                            }
                        }
                    }
                if (turn != generation || current.scope != AnkiquestHomeData.account()?.scope) return@launch
                if (problem == R.string.aq_feed_account_changed) {
                    reload()
                    return@launch
                }
                failure = problem
                loading = false
                render()
                if (box == Box.INBOX) loadPictures(current)
            }
    }

    /** Runs [work] and answers the message for its failure, or null when it worked. */
    private suspend fun attempt(work: suspend () -> Unit): Int? =
        try {
            work()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: HomeAccountChanged) {
            R.string.aq_feed_account_changed
        } catch (e: AnkiquestHttpException) {
            Timber.w(e, "AnkiQuest activity request refused")
            if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_feed_server_error
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest activity unreachable")
            R.string.aq_feed_offline
        } catch (e: JSONException) {
            Timber.w(AnkiquestApi.redacted(e), "AnkiQuest activity unreadable")
            R.string.aq_feed_server_error
        }

    private fun append(
        old: AnkiquestFeed.Page,
        next: AnkiquestFeed.Page,
    ): AnkiquestFeed.Page =
        next.copy(
            items =
                old.items +
                    next.items.filter { new ->
                        old.items.none { it.id == new.id }
                    },
            attention = old.attention,
        )

    /** Fetches each friend's win card once, from the account's own server. */
    private fun loadPictures(current: HomeAccount) {
        val wanted =
            page
                ?.items
                .orEmpty()
                .mapNotNull { AnkiquestFeed.image(current, it) }
                .distinct()
                .filter { it !in pictures }
        if (wanted.isEmpty()) return
        wanted.forEach { pictures[it] = null }
        lifecycleScope.launch {
            for (url in wanted) {
                val picture = withContext(Dispatchers.IO) { AnkiquestShare.picture(url) } ?: continue
                if (account?.scope != current.scope) return@launch
                pictures[url] = picture
                render()
            }
        }
    }

    private fun render() {
        val position = scroll.scrollY
        content.removeAllViews()
        progress.isVisible = loading && content.isVisible
        status?.let { line ->
            views.text(content, line, small = true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        }
        if (account == null) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_feed_connect))
            views.button(panel, getString(R.string.aq_home_connect), primary = true) { settings() }
            return
        }
        views.choices(
            content,
            listOf(Box.INBOX to getString(R.string.aq_feed_inbox), Box.SENT to getString(R.string.aq_feed_sent)),
            selected = box,
            enabled = true,
        ) { choice ->
            box = choice
            if ((choice == Box.SENT && sent == null) || (choice == Box.INBOX && page == null)) load(more = false) else render()
        }
        when (box) {
            Box.INBOX -> renderInbox()
            Box.SENT -> renderSent()
        }
        failure?.let { problem ->
            views.text(content, getString(problem), small = true)
            if (problem == R.string.aq_home_auth) views.button(content, getString(R.string.aq_home_settings)) { settings() }
        }
        scroll.post { scroll.scrollTo(0, position) }
    }

    private fun renderInbox() {
        val current = page
        if (current?.legacy != true) {
            views.choices(
                content,
                AnkiquestFeed.Category.entries.map { it to categoryLabel(it, current) },
                selected = category,
                enabled = !loading,
            ) { choice ->
                if (choice != category) {
                    category = choice
                    page = null
                    load(more = false)
                } else {
                    render()
                }
            }
            views.chip(content, getString(R.string.aq_feed_unread_only), checked = unreadOnly, enabled = !loading) {
                unreadOnly = !unreadOnly
                page = null
                load(more = false)
            }
        }
        if (current == null) {
            if (loading) views.text(content, getString(R.string.aq_feed_loading), small = true)
            return
        }
        if (current.unread > 0 && !current.legacy) {
            views.button(content, getString(R.string.aq_feed_mark_all_read), enabled = !markingAll) { markAllRead() }
        }
        if (current.legacy) views.text(content, getString(R.string.aq_feed_legacy), small = true)
        val attention = current.attention.takeIf { category == AnkiquestFeed.Category.ALL }.orEmpty()
        if (attention.isNotEmpty() && current.actionCount > 0) {
            val panel = views.card(content, tinted = true)
            views.text(panel, getString(R.string.aq_feed_needs_action_count, current.actionCount), heading = true)
            views.text(panel, getString(R.string.aq_feed_needs_action_hint), small = true)
            for (item in attention) {
                views.text(panel, item.title, heading = true)
                if (item.body.isNotEmpty()) views.text(panel, item.body)
                item.challengeId?.let { goal ->
                    views.button(panel, getString(R.string.aq_feed_view_invitation), primary = true) { openGoal(item, goal) }
                }
                item.invitation?.let { group ->
                    val waiting = item.id in busy
                    views.button(
                        panel,
                        getString(R.string.aq_friends_join),
                        primary = true,
                        enabled = !waiting,
                    ) { answer(item, group, Answer.JOIN) }
                    views.button(
                        panel,
                        getString(R.string.aq_challenges_decline),
                        enabled = !waiting,
                    ) { answer(item, group, Answer.DECLINE) }
                }
            }
            if (current.actionCount > attention.size) {
                views.button(panel, getString(R.string.aq_feed_all_invitations)) {
                    category = AnkiquestFeed.Category.NEEDS_ACTION
                    page = null
                    load(more = false)
                }
            }
        }
        val shown = current.items.filter { item -> attention.none { it.id == item.id } }

        // Friends' study waits for the player's first upload; "all caught up" would be wrong then.
        current.lock?.let { views.text(content, getString(it.text), heading = true).setPadding(0, views.dp(16), 0, 0) }
        if (shown.isEmpty()) {
            if (!loading && current.lock == null) {
                content.addView(AnkiquestAki.image(this, R.drawable.aki_celebrate, 72))
                views.text(content, getString(emptyMessage(attention.isNotEmpty())), heading = true)
                views.text(content, getString(R.string.aq_feed_empty_hint), small = true)
            }
        } else {
            var day: Long? = null
            for (item in shown) {
                val itemDay = dayOf(item.createdAt)
                if (itemDay != day) {
                    day = itemDay
                    views.text(content, dayLabel(item.createdAt), heading = true).setPadding(0, views.dp(16), 0, 0)
                }
                renderItem(item, current.legacy)
            }
        }
        if (current.nextBefore != null) {
            views.button(
                content,
                getString(if (loading) R.string.aq_feed_loading else R.string.aq_feed_more),
                enabled = !loading,
            ) { load(more = true) }
        }
    }

    private fun emptyMessage(attention: Boolean): Int =
        when {
            unreadOnly -> R.string.aq_feed_empty_unread
            category == AnkiquestFeed.Category.NEEDS_ACTION -> R.string.aq_feed_empty_invitations
            category != AnkiquestFeed.Category.ALL -> R.string.aq_feed_empty_category
            attention -> R.string.aq_feed_empty_rest
            else -> R.string.aq_feed_empty
        }

    private fun renderItem(
        item: AnkiquestFeed.Item,
        legacy: Boolean,
    ) {
        val unread = !legacy && item.unread
        val panel = views.card(content, tinted = unread)
        views.text(panel, item.heading, heading = true)
        val time = DateUtils.formatDateTime(this, item.createdAt * 1000, DateUtils.FORMAT_SHOW_TIME)
        views.text(panel, if (unread) getString(R.string.aq_feed_time_unread, time) else time, small = true)
        item.replyTo?.let { (title, body) -> quote(panel, title, body) }
        item.text?.let { views.text(panel, it) }
        account?.let { AnkiquestFeed.image(it, item) }?.let { url -> pictures[url] }?.let { picture ->
            panel.addView(
                ImageView(this).apply {
                    setImageBitmap(picture)
                    adjustViewBounds = true
                    contentDescription = getString(R.string.aq_feed_card, item.title)
                },
                LinearLayout.LayoutParams(views.dp(160), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = views.dp(6) },
            )
        }
        val actions = views.actions(panel)
        val waiting = item.id in busy
        if (item.cheerable) {
            if (item.reacted) {
                views.chip(actions, getString(R.string.aq_feed_cheered), checked = true, enabled = false) {}
            } else {
                views.chip(actions, getString(R.string.ankiquest_cheer), enabled = !waiting) { cheer(item) }
            }
        }
        item.challengeId?.let { goal ->
            val label = if (item.actionRequired) R.string.aq_feed_view_invitation else R.string.aq_feed_open_goal
            views.chip(actions, getString(label), primary = item.actionRequired) { openGoal(item, goal) }
        }
        item.invitation?.let { group ->
            views.chip(
                actions,
                getString(R.string.aq_friends_join),
                primary = true,
                enabled = !waiting,
            ) { answer(item, group, Answer.JOIN) }
            views.chip(
                actions,
                getString(R.string.aq_challenges_decline),
                enabled = !waiting,
            ) { answer(item, group, Answer.DECLINE) }
        }
        if (item.answerable) {
            quickReply(item.kind)?.let { words ->
                views.chip(actions, getString(words), enabled = !waiting) { reply(item, getString(words)) }
            }
            views.chip(actions, getString(R.string.aq_feed_reply), enabled = !waiting) { askReply(item) }
        }
        if (unread) views.chip(actions, getString(R.string.aq_feed_mark_read), enabled = !waiting) { markRead(item) }
        if (actions.isEmpty()) panel.removeView(actions)
        if (item.replied) views.text(panel, getString(R.string.aq_feed_reply_sent), small = true)
        if (item.kind == "completion" && account?.let { deckAlerts.canStop(it, item.sender) } == true) {
            // A full-width button can wrap the sender name at large text sizes.
            views.button(panel, getString(R.string.aq_deck_alerts_stop, item.sender), enabled = !deckAlerts.busy()) {
                account?.let { deckAlerts.stop(it, item.sender) { render() } }
                render()
            }
        }
    }

    private fun renderSent() {
        val current = sent
        if (current == null) {
            if (loading) views.text(content, getString(R.string.aq_feed_loading), small = true)
            return
        }
        if (current.items.isEmpty() && !loading) {
            views.text(content, getString(R.string.aq_feed_sent_empty), heading = true)
            views.text(content, getString(R.string.aq_feed_sent_empty_hint), small = true)
        }
        for ((recipient, item) in current.items) {
            val panel = views.card(content)
            views.text(panel, getString(R.string.aq_feed_sent_to, recipient), heading = true)
            val time = DateUtils.formatDateTime(this, item.createdAt * 1000, DATE_AND_TIME)
            val seen =
                item.readAt?.let {
                    getString(R.string.aq_feed_seen, DateUtils.formatDateTime(this, it * 1000, DATE_AND_TIME))
                } ?: getString(R.string.aq_feed_not_seen)
            views.text(panel, "$time · $seen", small = true)
            item.replyTo?.let { (title, body) -> quote(panel, title, body) }
            if (item.body.isNotEmpty()) views.text(panel, item.body)
        }
        if (current.nextBefore != null) {
            views.button(
                content,
                getString(if (loading) R.string.aq_feed_loading else R.string.aq_feed_more_sent),
                enabled = !loading,
            ) { load(more = true) }
        }
    }

    private fun categoryLabel(
        category: AnkiquestFeed.Category,
        page: AnkiquestFeed.Page?,
    ): String =
        when (category) {
            AnkiquestFeed.Category.ALL -> getString(R.string.aq_feed_all)
            AnkiquestFeed.Category.NEEDS_ACTION -> {
                val count = page?.actionCount ?: 0
                if (count > 0) getString(R.string.aq_feed_needs_action_count, count) else getString(R.string.aq_feed_needs_action)
            }
            AnkiquestFeed.Category.MESSAGES -> getString(R.string.aq_feed_messages)
            AnkiquestFeed.Category.CHALLENGES -> getString(R.string.aq_feed_challenges)
            AnkiquestFeed.Category.DECK_COMPLETIONS -> getString(R.string.aq_feed_deck_completions)
            AnkiquestFeed.Category.STUDY_UPDATES -> getString(R.string.aq_feed_study_updates)
        }

    /** Sends a 🎉 for a friend's win or finished deck. */
    private fun cheer(item: AnkiquestFeed.Item) =
        act(item, R.string.aq_feed_cheer_failed) { current ->
            AnkiquestFeed.cheer(current, item)
            change(item.id) { it.copy(reacted = true) }
            getString(R.string.aq_feed_cheer_sent)
        }

    private fun askReply(item: AnkiquestFeed.Item) {
        val field =
            EditText(this).apply {
                hint = getString(R.string.ankiquest_reply_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                filters = arrayOf(InputFilter.LengthFilter(AnkiquestReply.MAX_MESSAGE))
                isSingleLine = true
            }
        val frame =
            FrameLayout(this).apply {
                setPadding(views.dp(24), views.dp(8), views.dp(24), 0)
                addView(field)
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.aq_feed_reply_to, item.sender))
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.aq_feed_send) { _, _ ->
                val message = AnkiquestReply.clean(field.text.toString())
                if (message.isNotEmpty()) reply(item, message)
            }.show()
        field.requestFocus()
    }

    /** Answers [item]; a reply also marks it read, which may fail without undoing the reply. */
    private fun reply(
        item: AnkiquestFeed.Item,
        message: String,
    ) = act(item, R.string.aq_feed_reply_failed) { current ->
        AnkiquestFeed.reply(current, item, message)
        change(item.id) { it.copy(replied = true) }
        if (item.unread && page?.legacy != true) {
            try {
                AnkiquestFeed.markRead(current, listOf(item.id))
                read(listOf(item.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest reply sent, read state not saved")
            }
        }
        getString(R.string.aq_feed_reply_done)
    }

    /** How the player answers an invitation to a group. */
    private enum class Answer { JOIN, DECLINE }

    /** Joins or declines the group [item] invites to; the server marks the row read and answered. */
    private fun answer(
        item: AnkiquestFeed.Item,
        group: Long,
        answer: Answer,
    ) = act(item, R.string.aq_feed_invite_failed) { current ->
        val action =
            when (answer) {
                Answer.JOIN -> AnkiquestSocial.Change.AcceptInvite(group)
                Answer.DECLINE -> AnkiquestSocial.Change.DeclineInvite(group)
            }
        val overview = AnkiquestSocial.change(current, action)

        // The row is answered now: read, and no longer waiting for the player.
        read(listOf(item.id))
        change(item.id) { it.copy(actionRequired = false) }
        page =
            page?.let { latest ->
                latest.copy(
                    attention = latest.attention.filter { it.id != item.id },
                    actionCount = (latest.actionCount - 1).coerceAtLeast(0),
                )
            }
        val joined = overview.groups.firstOrNull { it.id == group }
        when {
            answer == Answer.DECLINE -> getString(R.string.aq_feed_invite_declined)
            joined != null -> getString(R.string.aq_friends_joined, joined.name)
            else -> getString(R.string.aq_feed_invite_joined)
        }
    }

    private fun markRead(item: AnkiquestFeed.Item) =
        act(item, R.string.aq_feed_read_failed) { current ->
            AnkiquestFeed.markRead(current, listOf(item.id))
            read(listOf(item.id))
            null
        }

    private fun markAllRead() {
        val current = account ?: return
        val shown = page ?: return
        val through = shown.latestId ?: (shown.items + shown.attention).maxOfOrNull { it.id } ?: return
        markingAll = true
        render()
        lifecycleScope.launch {
            val problem = attempt { AnkiquestFeed.markAllRead(current, through) }
            if (account?.scope != current.scope) return@launch
            markingAll = false
            if (problem == null) {
                val now = TimeManager.time.intTimeMS() / 1000
                page =
                    page?.let { latest ->
                        fun AnkiquestFeed.Item.marked() = if (unread && id <= through) copy(readAt = now) else this
                        latest.copy(
                            items = latest.items.map { it.marked() }.filter { !unreadOnly || it.unread },
                            attention = latest.attention.map { it.marked() },
                            unread = 0,
                        )
                    }
                status = getString(R.string.aq_feed_all_read)
            } else {
                status = getString(R.string.aq_feed_read_failed)
            }
            render()
        }
    }

    /** Opens the goal a row is about and marks the row read. */
    private fun openGoal(
        item: AnkiquestFeed.Item,
        goal: Long,
    ) {
        startActivity(AnkiquestChallengesActivity.intent(this, goal))
        val current = account ?: return
        if (!item.unread || page?.legacy == true) return
        lifecycleScope.launch {
            val problem = attempt { AnkiquestFeed.markRead(current, listOf(item.id)) }
            if (problem == null && account?.scope == current.scope) {
                read(listOf(item.id))
                render()
            }
        }
    }

    /**
     * Runs [work] for [item] while its buttons wait. [work] answers the line to show, or null for none;
     * a failure shows [failed] with why. Nothing is kept once the account changed.
     */
    private fun act(
        item: AnkiquestFeed.Item,
        @StringRes failed: Int,
        work: suspend (HomeAccount) -> String?,
    ) {
        val current = account ?: return
        if (!busy.add(item.id)) return
        render()
        lifecycleScope.launch {
            var line: String? = null
            val problem = attempt { line = work(current) }
            if (account?.scope != current.scope) return@launch
            busy.remove(item.id)
            if (problem == R.string.aq_feed_account_changed) {
                reload()
                return@launch
            }
            status = if (problem == null) line else getString(R.string.aq_feed_failed_because, getString(failed), getString(problem))
            render()
        }
    }

    private fun change(
        id: Long,
        update: (AnkiquestFeed.Item) -> AnkiquestFeed.Item,
    ) {
        page =
            page?.let { latest ->
                latest.copy(
                    items = latest.items.map { if (it.id == id) update(it) else it },
                    attention = latest.attention.map { if (it.id == id) update(it) else it },
                )
            }
    }

    private fun read(ids: List<Long>) {
        val latest = page ?: return
        val newlyRead = (latest.items + latest.attention).filter { it.id in ids && it.unread }.map { it.id }.distinct()
        val now = TimeManager.time.intTimeMS() / 1000
        ids.forEach { id -> change(id) { if (it.unread) it.copy(readAt = now) else it } }
        page =
            page?.let { changed ->
                changed.copy(
                    items = if (unreadOnly) changed.items.filter { it.id !in ids } else changed.items,
                    unread = (changed.unread - newlyRead.size).coerceAtLeast(0),
                )
            }
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun dayOf(seconds: Long): Long =
        Time
            .gregorianCalendar(seconds * 1000)
            .apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

    private fun dayLabel(seconds: Long): String {
        val day = dayOf(seconds)
        val today = dayOf(TimeManager.time.intTimeMS() / 1000)
        val yesterday =
            Time
                .gregorianCalendar(today)
                .apply { add(Calendar.DAY_OF_YEAR, -1) }
                .timeInMillis
        return when (day) {
            today -> getString(R.string.ankiquest_nav_today)
            yesterday -> getString(R.string.aq_feed_yesterday)
            else -> DateUtils.formatDateTime(this, seconds * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
        }
    }

    private fun quote(
        parent: LinearLayout,
        title: String,
        body: String,
    ) {
        val box =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(views.dp(12), views.dp(6), views.dp(12), views.dp(6))
                background = views.shape(R.color.aq_home_background, 10)
            }
        parent.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    views.dp(4)
            },
        )
        views.text(box, getString(R.string.aq_feed_in_reply_to), small = true)
        if (title.isNotEmpty()) views.text(box, title).typeface = Typeface.DEFAULT_BOLD
        if (body.isNotEmpty()) views.text(box, body, small = true)
    }

    companion object {
        private const val STATE_BOX = "ankiquest.feed_box"
        private const val STATE_CATEGORY = "ankiquest.feed_category"
        private const val STATE_UNREAD = "ankiquest.feed_unread"
        private const val DATE_AND_TIME = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH

        /** The suggested one-tap answer to a row of [kind], as the website and notifications suggest it. */
        private fun quickReply(kind: String): Int? =
            when (kind) {
                "completion", "conquest", "share" -> R.string.ankiquest_reply_cheer
                "reply", "reaction" -> R.string.ankiquest_reply_thanks
                "nudge" -> R.string.ankiquest_reply_on_it
                else -> null
            }
    }
}
