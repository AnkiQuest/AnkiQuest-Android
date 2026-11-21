// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CommonString
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.model.SelectableDeck
import com.ichi2.anki.pages.AnkiPackageImporterFragment
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The daily study surface is native and local, like every other AnkiQuest screen: activity
 * and friends ([AnkiquestFriendsActivity]), progress, study history and records ([AnkiquestProfileActivity]).
 */
class AnkiquestHomeActivity : AnkiActivity(R.layout.activity_ankiquest_home) {
    private enum class GoalSetup { READY, SAVING, FAILED }

    private var goalSetup = GoalSetup.READY
    private lateinit var content: LinearLayout
    private val views = AnkiquestBoardViews(this, AnkiquestBoardViews.Spacing.ROOMY)
    private lateinit var scroll: NestedScrollView
    private var account: HomeAccount? = null
    private var local: HomeLocal? = null
    private var localFailed = false
    private var profile: JSONObject? = null

    @StringRes private var profileFailure: Int? = null
    private var loading = false
    private var networkJob: Job? = null
    private var avatarJob: Job? = null
    private var copyJob: Job? = null
    private lateinit var deckChoices: AnkiquestDeckChoices
    private var copyInbox: CopyInbox = CopyInbox.Loading
    private var copyState: CopyState = CopyState.Ready
    private val copyBusy get() = copyState is CopyState.Working
    private var copyStatus: String? = null
    private var importedCopyFile: File? = null
    private val copyImporter =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            importedCopyFile?.delete()
            importedCopyFile = null
            refresh()
        }
    private var passwordPrompt: PasswordPrompt = PasswordPrompt.Hidden
    private var passwordJob: Job? = null
    private var generation = 0
    private var focus: String? = null
    private var questsHeading: View? = null

    /** The card asking a player who signs in only with a token to set a password. */
    private sealed interface PasswordPrompt {
        data object Hidden : PasswordPrompt

        data class Offered(
            val account: HomeAccount,
        ) : PasswordPrompt
    }

    private sealed interface CopyInbox {
        data object Loading : CopyInbox

        data object Unavailable : CopyInbox

        data class Ready(
            val data: JSONObject,
        ) : CopyInbox
    }

    private sealed interface CopyState {
        data object Ready : CopyState

        sealed interface Working : CopyState {
            val account: HomeAccount
        }

        data class Sending(
            override val account: HomeAccount,
        ) : Working

        data class Importing(
            override val account: HomeAccount,
        ) : Working

        data class Dismissing(
            override val account: HomeAccount,
        ) : Working
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) return
        super.onCreate(savedInstanceState)
        content = findViewById(R.id.aq_home_content)
        scroll = findViewById(R.id.aq_home_scroll)
        findViewById<MaterialButton>(R.id.aq_home_activity).apply {
            setTextColor(getColor(R.color.aq_home_text))
            setOnClickListener { startActivity(AnkiquestFriendsSections.feedIntent(this@AnkiquestHomeActivity)) }
        }
        findViewById<View>(R.id.aq_home_account).apply {
            setOnClickListener { startActivity(AnkiquestProfileActivity.intent(this@AnkiquestHomeActivity)) }
            ViewCompat.setAccessibilityDelegate(
                this,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.Button::class.java.name
                    }
                },
            )
        }
        AnkiquestTabBar.attach(this, findViewById(R.id.aq_home_root), R.id.ankiquest_nav_today)
        account = AnkiquestHomeData.account()
        deckChoices = AnkiquestDeckChoices(this, ::selectDeck, ::copyChosen)
        if (savedInstanceState == null) focus = intent.getStringExtra(EXTRA_FOCUS)
        render()
    }

    private var localRefreshJob: Job? = null

    override fun onPause() {
        localRefreshJob?.cancel()
        super.onPause()
    }

    private fun scheduleLocalRefresh(nextAt: Long?) {
        localRefreshJob?.cancel()
        if (nextAt == null) return
        localRefreshJob =
            lifecycleScope.launch {
                delay((nextAt - TimeManager.time.intTimeMS()).coerceAtLeast(1_000L))
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refresh()
            }
    }

    override fun onResume() {
        super.onResume()
        if (!::content.isInitialized) return
        AnkiquestMoments.clearQuiet(AnkiDroidApp.sharedPrefs())
        refresh()
    }

    private fun refresh() {
        val current = AnkiquestHomeData.account()
        if (current?.scope != account?.scope) {
            // A new owner starts with empty caches and a fresh loading state.
            goalSetup = GoalSetup.READY
            account = current
            local = null
            localFailed = false
            scheduleLocalRefresh(null)
            profile = null
            profileFailure = null
            copyInbox = CopyInbox.Loading
            copyStatus = null
            copyState = CopyState.Ready
            passwordPrompt = PasswordPrompt.Hidden
        }
        val turn = ++generation
        lifecycleScope.launch {
            try {
                val result = AnkiquestHomeData.local()
                if (isCurrent(turn, current?.scope)) {
                    applyLocal(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest local study could not be loaded")
                if (isCurrent(turn, current?.scope)) {
                    applyLocal(null)
                }
            }
        }
        networkJob?.cancel()
        avatarJob?.cancel()
        copyJob?.cancel()
        passwordJob?.cancel()
        if (current == null) {
            loading = false
            render()
            return
        }
        loading = true
        render()
        refreshAvatar(current, turn)
        refreshCopies(current, turn)
        refreshPasswordPrompt(current, turn)
        networkJob =
            lifecycleScope.launch {
                val failure =
                    try {
                        val result = AnkiquestHomeData.profile(current)
                        if (AnkiquestHomeData.account()?.scope == current.scope) {
                            restoreOldGoal(current, result)
                        }
                        if (turn == generation && AnkiquestHomeData.account()?.scope == current.scope) {
                            profile = result
                            AnkiquestDailyGoal.remember(result)
                        }
                        if (current.token.isNotEmpty()) {
                            try {
                                // Unlocks and the choice, both kept per account on the server.
                                if (AnkiquestCompanion.refresh(current)) {
                                    AnkiquestWidget.requestUpdate(this@AnkiquestHomeActivity)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "AnkiQuest companion could not be refreshed")
                            }
                        }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AnkiquestHttpException) {
                        if (e.code == 401 || e.code == 403) R.string.aq_home_auth else R.string.aq_home_server_error
                    } catch (_: IOException) {
                        R.string.aq_home_offline
                    } catch (_: JSONException) {
                        R.string.aq_home_server_error
                    }
                if (isCurrent(turn, current.scope)) {
                    profileFailure = failure
                    if (failure == R.string.aq_home_auth) profile = null
                    loading = false
                    render()
                }
            }
    }

    private fun isCurrent(
        turn: Int,
        scope: String?,
    ): Boolean = turn == generation && ownsScope(scope)

    private fun ownsScope(scope: String?): Boolean = account?.scope == scope && AnkiquestHomeData.account()?.scope == scope

    private fun applyLocal(value: HomeLocal?) {
        local = value
        localFailed = value == null
        scheduleLocalRefresh(value?.nextUpdateAt)
        render()
    }

    private fun refreshCopies(
        current: HomeAccount,
        turn: Int = generation,
    ) {
        copyJob?.cancel()
        copyJob =
            lifecycleScope.launch {
                val result =
                    try {
                        CopyInbox.Ready(AnkiquestDeckCopies.inbox(current))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "AnkiQuest deck copies could not be loaded")
                        CopyInbox.Unavailable
                    }
                if (isCurrent(turn, current.scope)) {
                    copyInbox = result
                    render()
                }
            }
    }

    /** Asks the server how the player signs in, unless Not now still hides the card. */
    private fun refreshPasswordPrompt(
        current: HomeAccount,
        turn: Int,
    ) {
        val prefs = AnkiDroidApp.sharedPrefs()
        val snoozedAt = AnkiquestPasswordPrompt.snoozedAt(prefs, current)
        val now = TimeManager.time.intTimeMS()
        if (current.token.isEmpty() || (snoozedAt != null && now - snoozedAt < AnkiquestPasswordPrompt.SNOOZE_MS)) {
            passwordPrompt = PasswordPrompt.Hidden
            return
        }
        passwordJob =
            lifecycleScope.launch {
                val offered =
                    try {
                        val methods = AnkiquestAccountSettings.methods(current)
                        // Not now may have been chosen while the server was answering.
                        AnkiquestPasswordPrompt.offered(methods, AnkiquestPasswordPrompt.snoozedAt(prefs, current), now)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.w(error, "AnkiQuest sign-in methods could not be loaded")
                        false
                    }
                val next = if (offered) PasswordPrompt.Offered(current) else PasswordPrompt.Hidden
                if (isCurrent(turn, current.scope) && next != passwordPrompt) {
                    passwordPrompt = next
                    render()
                }
            }
    }

    private fun render() {
        if (!::content.isInitialized) return
        val position = scroll.scrollY
        content.removeAllViews()
        val face = findViewById<ImageView>(R.id.aq_home_companion)
        AnkiquestCompanion.show(face, R.drawable.aki_face, face.layoutParams.width)
        renderAvatar()
        findViewById<View>(R.id.aq_home_loading).isVisible = loading
        renderToday()
        val target = questsHeading?.takeIf { focus == FOCUS_QUESTS }
        if (target != null || profile != null) focus = null
        if (target != null) {
            target.doOnLayout { scroll.smoothScrollTo(0, it.top) }
        } else {
            scroll.post { scroll.scrollTo(0, position) }
        }
    }

    /** The same account-scoped photo or initial used on profiles, with a 48 dp touch target. */
    private fun renderAvatar() {
        val avatar = findViewById<LinearLayout>(R.id.aq_home_account)
        avatar.removeAllViews()
        val user = account?.user.orEmpty()
        val display = profile?.optString("display").orEmpty().ifBlank { user.ifBlank { getString(R.string.ankiquest_me) } }
        views.avatar(avatar, user, display, 32)
    }

    /** Optional photos must neither delay Today nor apply after its account changes. */
    private fun refreshAvatar(
        current: HomeAccount,
        turn: Int,
    ) {
        val captured = AnkiquestAvatars.account()?.takeIf { it.scope == current.scope } ?: return
        avatarJob =
            lifecycleScope.launch {
                boardAttempt { AnkiquestAvatars.refresh(listOf(current.user), captured) }
                if (isCurrent(turn, current.scope)) {
                    renderAvatar()
                }
            }
    }

    private fun renderToday() {
        questsHeading = null
        title(R.string.ankiquest_nav_today, R.string.aq_home_today_subtitle)
        content.addView(AnkiquestAki.encouragement(this, AnkiquestAki.mood(local, profile)))
        profile?.let {
            views.text(content, getString(R.string.aq_home_streak_level, it.optInt("streak"), it.optInt("level")), small = true)
            AnkiquestBoards.ownWeekLine(this, AnkiquestBoards.parseOwnWeek(it), TimeManager.time.intTimeMS())?.let { line ->
                views.text(views.card(content, tinted = true), line)
            }
        }
        val study = views.card(content, tinted = true)
        views.text(study, getString(R.string.aq_home_next_step), small = true)
        val focus = local?.focus
        when {
            focus != null -> {
                views.text(study, focus.name, heading = true)
                views.text(study, getString(R.string.aq_home_counts, focus.review, focus.learning, focus.new), small = true)
                val daily = focus.daily
                val label = views.text(study, daily.label(this), small = true)
                label.setTextColor(getColor(daily.color))
                if (daily.icon != 0) {
                    label.setCompoundDrawablesRelativeWithIntrinsicBounds(daily.icon, 0, 0, 0)
                    TextViewCompat.setCompoundDrawableTintList(label, ColorStateList.valueOf(getColor(daily.color)))
                    label.compoundDrawablePadding = views.dp(6)
                }
                if (daily.status == DailyDeckStatus.DONE) study.background = views.shape(R.color.aq_deck_done_background)
                if (daily.status == DailyDeckStatus.REVIEW_NOW) {
                    views.button(
                        study,
                        getString(R.string.aq_home_study_due, daily.readyCount.toInt()),
                        primary = true,
                    ) { openDecks(focus.id) }
                }
                button(study, R.string.aq_home_change_deck) { chooseDeck() }
            }
            localFailed -> {
                views.text(study, getString(R.string.aq_home_local_unavailable))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
            local == null -> views.text(study, getString(R.string.aq_home_loading_local))
            else -> {
                views.text(study, getString(R.string.aq_home_no_decks))
                button(study, R.string.aq_home_open_decks) { openDecks() }
            }
        }
        renderPasswordPrompt()
        val signed = account?.takeIf { it.token.isNotEmpty() }
        val suggested =
            AnkiquestDailyGoal.ServerGoal
                .parse(profile?.optJSONObject("goal"))
                ?.takeIf { it.chosen == false }
                ?.choice
        if (signed != null && suggested != null) {
            renderGoalSetup(signed, suggested)
        }
        local?.goal?.let { renderGoal(AnkiquestDailyGoal.withProfile(it, profile), suggested) }
        if (account == null) connectionCard()
        if (account != null) renderDeckCopies()
        AnkiquestStudySession.latest()?.let { summary ->
            val panel = views.card(content)
            panel.addView(AnkiquestAki.image(this, if (summary.remaining == 0) R.drawable.aki_celebrate else R.drawable.aki_streak, 56))
            views.text(panel, getString(R.string.aq_home_last_session), heading = true)
            views.text(panel, getString(R.string.aq_home_session_summary, summary.reviews, summary.duration(this), summary.remaining))
        }
        profile?.optJSONArray("quests")?.objects()?.let { quests ->
            if (quests.isNotEmpty()) {
                if (quests.all { it.optBoolean("done") }) content.addView(AnkiquestAki.image(this, R.drawable.aki_winner, 56))
                questsHeading = views.text(content, getString(R.string.aq_home_quests), heading = true)
                quests.forEach { quest ->
                    val panel = views.card(content)
                    views.text(panel, quest.optString("title"), heading = true)
                    meter(panel, quest.optLong("progress"), quest.optLong("target"))
                    if (quest.optBoolean(
                            "done",
                        )
                    ) {
                        views.text(panel, getString(R.string.aq_home_quest_done, quest.optLong("reward")), small = true)
                    }
                }
            }
        }
        if (account != null) {
            button(
                content,
                R.string.aq_history_title,
            ) { startActivity(AnkiquestMeSections.intent(this, AnkiquestMeSections.Destination.HISTORY)) }
            button(
                content,
                R.string.aq_records_title,
            ) { startActivity(AnkiquestMeSections.intent(this, AnkiquestMeSections.Destination.RECORDS)) }
        }
        profileFailure?.let { failure ->
            views.text(content, getString(failure), small = true)
            if (failure == R.string.aq_home_auth) button(content, R.string.aq_home_settings) { settings() }
        }
        if (account != null) button(content, R.string.aq_home_retry, enabled = !loading) { refresh() }
    }

    /** Below the next study step, so it never stands between the player and their cards. */
    private fun renderPasswordPrompt() {
        val offered = passwordPrompt as? PasswordPrompt.Offered ?: return
        val panel = views.card(content, tinted = true)
        views.text(panel, getString(R.string.ankiquest_password_set_title), heading = true)
        views.text(panel, getString(R.string.ankiquest_password_prompt_body))
        button(panel, R.string.ankiquest_password_set_title, primary = true) {
            AnkiquestPasswordPrompt.ask(this, offered.account) { hidePasswordPrompt(offered) }
        }
        button(panel, R.string.ankiquest_deck_share_not_now) {
            AnkiquestPasswordPrompt.snooze(AnkiDroidApp.sharedPrefs(), offered.account, TimeManager.time.intTimeMS())
            hidePasswordPrompt(offered)
        }
    }

    /** Hides the card after Not now or a saved password; an answer still on its way is dropped. */
    private fun hidePasswordPrompt(offered: PasswordPrompt.Offered) {
        if (passwordPrompt != offered) return
        passwordJob?.cancel()
        passwordPrompt = PasswordPrompt.Hidden
        render()
    }

    private fun renderGoal(
        status: AnkiquestDailyGoal.Status,
        suggested: AnkiquestDailyGoal.Choice?,
    ) {
        val panel = views.card(content)
        views.text(panel, getString(R.string.aq_goal_today), heading = true)
        val secondsPerCard = local?.secondsPerCard ?: AnkiquestGoalPlan.DEFAULT_SECONDS_PER_CARD
        AnkiquestGoalBar.display(this, status, secondsPerCard)?.let { display ->
            panel.addView(
                AnkiquestGoalProgress(this).apply { show(display, SLOT_TODAY) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = views.dp(6)
                },
            )
        }
        panel.addView(
            AnkiquestStreakChip(this).apply { show(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = views.dp(10)
            },
        )
        // The goal is set where it shows; there is no goal setting elsewhere.
        if (suggested == null) {
            account?.takeIf { it.token.isNotEmpty() }?.let { signed ->
                button(panel, R.string.aq_goal_change) { AnkiquestGoalDialog.edit(this, signed) { refresh() } }
            }
        }
    }

    private suspend fun restoreOldGoal(
        owner: HomeAccount,
        result: JSONObject,
    ) {
        if (owner.token.isEmpty()) return
        val prefs = AnkiDroidApp.sharedPrefs()
        if (AnkiquestDailyGoal.localChoice(prefs) == null || AnkiquestDailyGoal.cached(prefs, owner.scope) != null) return

        // Fetch once before caching the first profile, so a chosen device goal can migrate.
        try {
            val restored = AnkiquestDailyGoal.fetch(owner, prefs)
            if (restored.chosen == true) {
                result.put("goal", restored.json().put("chosen", true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AnkiQuest device goal could not be restored")
        }
    }

    private fun renderGoalSetup(
        owner: HomeAccount,
        choice: AnkiquestDailyGoal.Choice,
    ) {
        val panel = views.card(content)
        views.text(panel, getString(R.string.ankiquest_goal_setup_title), heading = true)
        views.text(panel, AnkiquestDailyGoal.describe(this, choice))
        if (goalSetup == GoalSetup.FAILED) {
            views.text(panel, getString(R.string.aq_goal_setup_failed), small = true)
        }
        if (goalSetup == GoalSetup.SAVING) {
            views.text(panel, getString(R.string.ankiquest_goal_saving), small = true)
            return
        }
        button(panel, CommonString.save, primary = true) { saveSuggestedGoal(owner, choice) }
        button(panel, R.string.ankiquest_goal_custom) {
            AnkiquestGoalDialog.show(this, owner, choice) { applyGoal(owner, it) }
        }
    }

    private fun saveSuggestedGoal(
        owner: HomeAccount,
        choice: AnkiquestDailyGoal.Choice,
    ) {
        if (goalSetup == GoalSetup.SAVING || AnkiquestHomeData.account()?.scope != owner.scope) return
        goalSetup = GoalSetup.SAVING
        render()
        lifecycleScope.launch {
            try {
                applyGoal(owner, AnkiquestDailyGoal.save(owner, choice, restart = choice.mode == AnkiquestDailyGoal.Mode.BACKLOG))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest suggested goal could not be saved")
                if (AnkiquestHomeData.account()?.scope == owner.scope) {
                    goalSetup = GoalSetup.FAILED
                    render()
                }
            }
        }
    }

    private fun applyGoal(
        owner: HomeAccount,
        goal: AnkiquestDailyGoal.ServerGoal,
    ) {
        if (AnkiquestHomeData.account()?.scope != owner.scope) return
        goalSetup = GoalSetup.READY
        profile?.put("goal", goal.json().put("chosen", true))
        refresh()
    }

    private fun renderDeckCopies() {
        val current = account ?: return
        val panel = views.card(content)
        // Both companions: sharing is something friends do together.
        if (AnkiquestCompanion.visible()) panel.addView(AnkiquestAki.image(this, R.drawable.duo_friends, 72))
        views.text(panel, getString(R.string.aq_copies_title), heading = true)
        views.text(panel, getString(R.string.aq_copies_description), small = true)
        copyStatus?.let { views.text(panel, it, small = true) }
        val inbox = copyInbox
        if (inbox !is CopyInbox.Ready) {
            val label = if (inbox == CopyInbox.Unavailable) R.string.aq_copies_unavailable else R.string.aq_home_loading_local
            views.text(panel, getString(label), small = true)
            return
        }
        val data = inbox.data
        val friends = data.optJSONArray("friends")?.objects().orEmpty()
        val decks = local?.decks.orEmpty()
        button(panel, R.string.aq_copies_share, enabled = !copyBusy && friends.isNotEmpty() && decks.isNotEmpty()) {
            chooseDeckForCopy(current, decks, friends)
        }
        if (friends.isEmpty()) views.text(panel, getString(R.string.aq_copies_no_friends), small = true)
        val offers = data.optJSONArray("offers")?.objects().orEmpty()
        if (offers.isEmpty()) {
            views.text(panel, getString(R.string.aq_copies_none), small = true)
        } else {
            views.text(panel, getString(R.string.aq_copies_received), heading = true)
            for (offer in offers) {
                val sender = offer.optString("sender")
                val display = friends.firstOrNull { it.optString("user") == sender }?.optString("display") ?: sender
                views.text(panel, getString(R.string.aq_copies_offer, display, offer.optString("deck")))
                val id = offer.optLong("id")
                button(panel, R.string.aq_copies_import, enabled = !copyBusy) { importCopy(id) }
                button(panel, R.string.aq_copies_dismiss, enabled = !copyBusy) { dismissCopy(id) }
            }
        }
    }

    private fun chooseDeckForCopy(
        current: HomeAccount,
        decks: List<HomeDeck>,
        friends: List<JSONObject>,
    ) {
        if (copyBusy || !ownsScope(current.scope)) {
            return
        }
        deckChoices.copy(current, decks, local?.focus?.id, friends)
    }

    private fun copyChosen(
        current: HomeAccount,
        deck: SelectableDeck.Deck,
        recipients: List<String>,
    ) {
        if (copyBusy || !ownsScope(current.scope) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            return
        }
        val work = CopyState.Sending(current)
        copyState = work
        copyStatus = getString(R.string.aq_copies_sending)
        render()
        lifecycleScope.launch {
            // Starting a send requires the same resumed account; an accepted send can finish while hidden.
            if (!ownsCopy(work) || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                finishCopy(work, null)
                return@launch
            }
            val status =
                try {
                    AnkiquestDeckCopies.share(current, deck.deckId, deck.name, recipients, cacheDir)
                    getString(R.string.aq_copies_sent)
                } catch (error: CancellationException) {
                    finishCopy(work, null)
                    throw error
                } catch (_: HomeAccountChanged) {
                    finishCopy(work, null)
                    if (copyState === work && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        refresh()
                    }
                    return@launch
                } catch (error: AnkiquestDeckCopies.TooLarge) {
                    getString(R.string.aq_copies_too_large)
                } catch (error: Exception) {
                    Timber.w(error, "AnkiQuest deck copy could not be sent")
                    getString(R.string.aq_copies_failed)
                }
            finishCopy(work, status)
        }
    }

    private fun finishCopy(
        work: CopyState.Working,
        status: String?,
    ) {
        if (!ownsCopy(work)) {
            return
        }
        copyStatus = status
        copyState = CopyState.Ready
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            render()
        }
    }

    // A result from an earlier visit cannot finish a newer operation, even for the same account.
    private fun ownsCopy(work: CopyState.Working): Boolean = copyState === work && ownsScope(work.account.scope)

    private fun importCopy(id: Long) {
        val current = account ?: return
        if (copyBusy || !ownsScope(current.scope)) {
            return
        }
        val work = CopyState.Importing(current)
        copyState = work
        copyStatus = getString(R.string.aq_copies_downloading)
        render()
        lifecycleScope.launch {
            try {
                val file = AnkiquestDeckCopies.download(current, id, cacheDir)
                if (ownsCopy(work)) {
                    copyStatus = null
                    importedCopyFile = file
                    copyImporter.launch(AnkiPackageImporterFragment.getIntent(this@AnkiquestHomeActivity, file.absolutePath))
                } else {
                    file.delete()
                }
            } catch (error: CancellationException) {
                finishCopy(work, null)
                throw error
            } catch (error: Exception) {
                Timber.w(error, "AnkiQuest deck copy could not be imported")
                if (ownsCopy(work)) {
                    copyStatus = getString(R.string.aq_copies_failed)
                }
            }
            finishCopy(work, copyStatus)
        }
    }

    private fun dismissCopy(id: Long) {
        val current = account ?: return
        if (copyBusy || !ownsScope(current.scope)) {
            return
        }
        val work = CopyState.Dismissing(current)
        copyState = work
        render()
        lifecycleScope.launch {
            try {
                AnkiquestDeckCopies.dismiss(current, id)
                if (ownsCopy(work)) {
                    finishCopy(work, null)
                    refreshCopies(current)
                }
            } catch (error: CancellationException) {
                finishCopy(work, null)
                throw error
            } catch (error: Exception) {
                Timber.w(error, "AnkiQuest deck copy could not be dismissed")
                finishCopy(work, getString(R.string.aq_copies_failed))
            }
        }
    }

    private fun chooseDeck() {
        deckChoices.focus(local?.decks.orEmpty())
    }

    private fun selectDeck(
        deck: Long,
        owner: String?,
    ) {
        lifecycleScope.launch {
            // Commit the choice first, then replace any refresh that saw the previous deck.
            try {
                CollectionManager.withCol {
                    if (AnkiquestHomeData.account()?.scope == owner && decks.get(deck) != null) {
                        decks.select(deck)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest focus deck could not be selected")
            }
            if (ownsScope(owner)) {
                refresh()
            }
        }
    }

    private fun openDecks(deck: Long? = null) {
        startActivity(AnkiquestNavigation.decks(this, deck))
    }

    private fun settings() = startActivity(PreferencesActivity.getIntent(this, AnkiquestSettingsFragment::class))

    private fun connectionCard() {
        val panel = views.card(content)
        views.text(panel, getString(R.string.aq_home_connected))
        button(panel, R.string.aq_home_connect) { settings() }
    }

    private fun title(
        @StringRes title: Int,
        @StringRes subtitle: Int,
    ) {
        views.text(content, getString(title), heading = true).textSize = TITLE_SIZE
        views.text(content, getString(subtitle), small = true)
    }

    private fun button(
        parent: LinearLayout,
        @StringRes label: Int,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ): MaterialButton = views.button(parent, getString(label), primary, enabled, click)

    private fun meter(
        parent: LinearLayout,
        value: Long,
        max: Long,
    ) {
        views.text(parent, getString(R.string.aq_home_progress_fraction, value, max), small = true)
        parent.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                this.max = 1000
                progress = if (max <= 0) 0 else (value.toDouble() / max * 1000).coerceIn(0.0, 1000.0).toInt()
                progressTintList = ColorStateList.valueOf(getColor(R.color.aq_home_primary))
                contentDescription = getString(R.string.aq_home_progress_fraction, value, max)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, views.dp(8)).apply { bottomMargin = views.dp(6) },
        )
    }

    companion object {
        /** The text size of the screen title above the cards. */
        private const val TITLE_SIZE = 26f

        const val EXTRA_SKIP_HOME = "ankiquest.skip_home"
        const val EXTRA_STUDY_DECK = "ankiquest.study_deck"

        /** A section to scroll to once it is shown, such as [FOCUS_QUESTS]. */
        const val EXTRA_FOCUS = "ankiquest.focus"
        const val FOCUS_QUESTS = "quests"

        /** The goal bar memory of the Today screen, see [AnkiquestGoalBar.show]. */
        const val SLOT_TODAY = "today"

        fun intent(context: Context): Intent = Intent(context, AnkiquestHomeActivity::class.java)
    }
}
