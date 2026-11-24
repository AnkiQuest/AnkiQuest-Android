// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.os.Bundle
import android.text.InputFilter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatEditText
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Passes on a win from a notification through [AnkiquestShareSheet]: to friends in AnkiQuest, or
 * anywhere else through Android's share sheet. A conquered card first asks whether to show what the
 * card was.
 */
class AnkiquestShareActivity : AnkiActivity() {
    private var cardOwner: AnkiquestDialogOwner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(AnkiquestShare.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(AnkiquestShare.EXTRA_BODY).orEmpty()
        val win = intent.getLongExtra(AnkiquestShare.EXTRA_WIN, 0).takeIf { it > 0 }
        val notification = intent.getLongExtra(AnkiquestShare.EXTRA_NOTIFICATION, 0).takeIf { it > 0 }
        val place = intent.getIntExtra(AnkiquestShare.EXTRA_PLACE, 0).takeIf { it in 1..3 }
        val scope = intent.getStringExtra(AnkiquestShare.EXTRA_SCOPE)
        if (title.isEmpty() || (win == null && notification == null && place == null)) {
            finish()
            return
        }
        val language = AnkiquestLanguage.context(this)
        val current = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() }
        if (scope == null || current?.scope != scope) {
            showThemedToast(this, language.getString(R.string.ankiquest_share_account_changed), shortLength = false)
            finish()
            return
        }
        val share = Share(win, notification, place, title, body, scope)
        if (win != null && intent.getStringExtra(AnkiquestShare.EXTRA_TOPIC) == "conquered") {
            askAboutCard(current, win, share)
            return
        }
        withRecipients(current, share)
    }

    override fun onStop() {
        super.onStop()
        cardOwner?.let {
            closeCard(it)
            if (!isChangingConfigurations) {
                finish()
            }
        }
    }

    /** What is being shared, as the notification's Share button handed it over. */
    private data class Share(
        val win: Long?,
        val notification: Long?,
        val place: Int?,
        val title: String,
        val body: String,
        val scope: String,
    )

    /** AnkiQuest's share sheet for [share]: chosen friends and groups, or other apps. */
    private fun withRecipients(
        account: HomeAccount,
        share: Share,
    ) {
        val reference =
            when {
                share.win != null -> AnkiquestSharing.MomentRef.Win(share.win)
                share.place != null -> AnkiquestSharing.MomentRef.Place(share.place)
                share.notification != null -> AnkiquestSharing.MomentRef.Notification(share.notification)
                else -> null
            }
        if (reference == null || AnkiquestServerUpdate.refuse(this, account)) {
            finish()
            return
        }
        AnkiquestShareSheet(
            this,
            account,
            AnkiquestShareSheet.Subject.Known(reference),
            AnkiquestShare.Card(share.title, share.body, AnkiquestShare.art(intent, share.place)),
            elsewhere = { shareElsewhere(share) },
            closed = { if (it != AnkiquestShareSheet.Exit.ELSEWHERE) finish() },
        ).show()
    }

    /**
     * A conquered card can say what the card was. Nothing about the card leaves the phone unless the
     * player ticks the box: the text starts as the front's field values, or the label they gave it
     * before, and they see and can change exactly what is shared. Sharing without the box ticked
     * takes an earlier label off the card and its page.
     */
    private fun askAboutCard(
        account: HomeAccount,
        win: Long,
        share: Share,
    ) {
        lifecycleScope.launch {
            val owner =
                lifecycle.withResumed {
                    AnkiquestDialogOwner(this@AnkiquestShareActivity, account).also { cardOwner = it }
                }
            if (!ownsCard(owner)) {
                return@launch
            }
            val language = AnkiquestLanguage.context(this@AnkiquestShareActivity)
            val known =
                try {
                    AnkiquestShare.conqueredAt(AnkiquestSharing.winKey(account, win))?.let { at ->
                        at to AnkiquestSharing.conquestLabel(account, at)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest conquered card unavailable; sharing it without the card")
                    null
                }
            if (!ownsCard(owner)) {
                return@launch
            }
            if (known == null) {
                nextDestination(owner, share)
                return@launch
            }
            val (at, saved) = known
            val suggestion = saved ?: AnkiquestShare.conqueredCardText(at).orEmpty()
            if (!ownsCard(owner)) {
                return@launch
            }
            val include =
                AppCompatCheckBox(this@AnkiquestShareActivity).apply {
                    text = language.getString(R.string.ankiquest_share_card_include)
                    isChecked = saved != null
                }
            val field =
                AppCompatEditText(this@AnkiquestShareActivity).apply {
                    setText(suggestion)
                    hint = language.getString(R.string.ankiquest_share_card_hint)
                    isSingleLine = true
                    filters = arrayOf(InputFilter.LengthFilter(AnkiquestShare.MAX_LABEL))
                    isEnabled = include.isChecked
                }
            include.setOnCheckedChangeListener { _, checked -> field.isEnabled = checked }
            val padding = (20 * resources.displayMetrics.density).toInt()
            val content =
                LinearLayout(this@AnkiquestShareActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding / 2, padding, 0)
                    addView(TextView(context).apply { text = language.getString(R.string.ankiquest_share_card_explanation) })
                    addView(include)
                    addView(field)
                }
            owner.show(
                AlertDialog
                    .Builder(this@AnkiquestShareActivity)
                    .setTitle(language.getString(R.string.ankiquest_share_card_title))
                    .setView(content)
                    .setPositiveButton(language.getString(R.string.ankiquest_share_next)) { dialog, _ ->
                        if (!owner.take(dialog)) {
                            if (cardOwner === owner && !owner.isCurrent()) {
                                closeCard(owner)
                                finish()
                            }
                            return@setPositiveButton
                        }
                        val label = AnkiquestShare.chosenLabel(include.isChecked, field.text?.toString())
                        lifecycleScope.launch {
                            try {
                                if (!ownsCard(owner)) {
                                    return@launch
                                }
                                if (label != saved) {
                                    AnkiquestSharing.setConquestLabel(account, at, label)
                                }
                                nextDestination(owner, share)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "ankiquest conquered card label failed")
                                if (owner.isCurrent()) {
                                    showThemedToast(
                                        this@AnkiquestShareActivity,
                                        language.getString(R.string.ankiquest_share_card_failed),
                                        shortLength = false,
                                    )
                                }
                                finish()
                            } finally {
                                closeCard(owner)
                            }
                        }
                    }.setNegativeButton(language.getString(android.R.string.cancel)) { _, _ ->
                        closeCard(owner)
                        finish()
                    }.setOnCancelListener {
                        closeCard(owner)
                        finish()
                    }.create(),
            )
        }
    }

    private fun ownsCard(owner: AnkiquestDialogOwner): Boolean {
        if (owner.isCurrent()) {
            return true
        }
        closeCard(owner)
        finish()
        return false
    }

    private fun closeCard(owner: AnkiquestDialogOwner) {
        owner.close()
        if (cardOwner === owner) {
            cardOwner = null
        }
    }

    private fun nextDestination(
        owner: AnkiquestDialogOwner,
        share: Share,
    ) {
        if (!ownsCard(owner)) {
            return
        }
        closeCard(owner)
        val account = AnkiquestHomeData.account()?.takeIf { it.scope == share.scope }
        if (account == null) {
            showThemedToast(this, AnkiquestLanguage.context(this).getString(R.string.ankiquest_share_account_changed), shortLength = false)
            finish()
            return
        }
        withRecipients(account, share)
    }

    /**
     * Prefer the canonical picture within the short deadline; otherwise draw the notification's
     * public content locally.
     */
    private fun shareElsewhere(share: Share) {
        lifecycleScope.launch {
            val language = AnkiquestLanguage.context(this@AnkiquestShareActivity)
            val failure =
                try {
                    val art = AnkiquestShare.art(intent, share.place)
                    val card = AnkiquestShare.Card(share.title, share.body, art)
                    val link =
                        try {
                            share.win?.let { Ankiquest.shareWinLink(it, share.scope) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: HomeAccountChanged) {
                            throw e
                        } catch (e: Exception) {
                            Timber.d(e, "AnkiQuest notification: drawing its share picture locally")
                            null
                        }
                    val chooser =
                        withContext(ioDispatcher) {
                            if (link == null) {
                                AnkiquestShare.textChooser(applicationContext, share.title, share.body, art)
                            } else {
                                AnkiquestShare.linkChooser(applicationContext, link, card)
                            }
                        }
                    if (AnkiquestHomeData.account()?.scope != share.scope) {
                        throw HomeAccountChanged()
                    }
                    lifecycle.withResumed {
                        if (AnkiquestHomeData.account()?.scope != share.scope) {
                            throw HomeAccountChanged()
                        }
                        if (!isFinishing && !isDestroyed) {
                            startActivity(chooser)
                        }
                    }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: HomeAccountChanged) {
                    R.string.ankiquest_share_account_changed
                } catch (e: Exception) {
                    Timber.w(e, "ankiquest share link failed")
                    R.string.ankiquest_share_failed
                }
            failure?.let { showThemedToast(this@AnkiquestShareActivity, language.getString(it), shortLength = false) }
            finish()
        }
    }
}
