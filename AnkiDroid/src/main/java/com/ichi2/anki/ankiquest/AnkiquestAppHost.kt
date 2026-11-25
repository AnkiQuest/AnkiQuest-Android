// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.Reviewer
import com.ichi2.anki.StudyOptionsActivity
import com.ichi2.anki.pages.CongratsPage
import com.ichi2.anki.preferences.AnkiquestSettingsFragment
import com.ichi2.anki.preferences.PreferencesActivity
import com.ichi2.anki.ui.windows.reviewer.ReviewerFragment
import timber.log.Timber

/** AnkiDroid as the host of :ankiquest-ui: its activities, its poll and its collection. */
object AnkiquestAppHost : AnkiquestHost {
    override fun intent(
        context: Context,
        screen: AnkiquestScreen,
    ): Intent =
        when (screen) {
            is AnkiquestScreen.Today ->
                AnkiquestHomeActivity
                    .intent(context)
                    .apply { screen.focus?.let { putExtra(AnkiquestHomeActivity.EXTRA_FOCUS, it) } }
            AnkiquestScreen.Decks -> Intent(context, DeckPicker::class.java).putExtra(AnkiquestHomeActivity.EXTRA_SKIP_HOME, true)
            is AnkiquestScreen.Friends -> AnkiquestFriendsActivity.intent(context, screen.account)
            is AnkiquestScreen.Profile -> AnkiquestProfileActivity.intent(context, screen.user)
            is AnkiquestScreen.Challenges -> AnkiquestChallengesActivity.intent(context, screen.id, screen.account)
            AnkiquestScreen.Calendar -> AnkiquestCalendarActivity.intent(context)
            is AnkiquestScreen.Conquest -> AnkiquestConquestActivity.intent(context, screen.user, screen.at)
            AnkiquestScreen.Settings -> PreferencesActivity.getIntent(context, AnkiquestSettingsFragment::class)
            AnkiquestScreen.Share -> Intent(context, AnkiquestShareActivity::class.java)
            AnkiquestScreen.Gallery -> Intent(context, AnkiquestGalleryActivity::class.java)
            AnkiquestScreen.WidgetSettings -> Intent(context, AnkiquestWidgetConfig::class.java)
        }

    override val actionIcon: Int = R.drawable.ic_star_notify

    override fun inForeground(): Boolean = Ankiquest.inForeground()

    override fun presentMomentsSoon() = Ankiquest.presentMomentsSoon()

    /** The deck list, the deck overview or congrats screen, or Today; never a review. */
    override fun resting(activity: Activity): Boolean {
        val fragments = (activity as? FragmentActivity)?.supportFragmentManager?.fragments.orEmpty()
        if (activity is Reviewer || fragments.any { it is ReviewerFragment && it.isVisible }) {
            return false
        }
        return activity is DeckPicker ||
            activity is StudyOptionsActivity ||
            activity is AnkiquestHomeActivity ||
            fragments.any { it is CongratsPage }
    }

    override fun schedule(context: Context) = AnkiquestPoll.schedule(context)

    override fun refreshNow(context: Context) = AnkiquestPoll.refreshNow(context)

    override fun refreshAt(
        context: Context,
        dayEndsAt: Long,
    ) = AnkiquestPoll.atDayEnd(context, dayEndsAt)

    override suspend fun conqueredCard(at: Long): AnkiquestShare.Labels? =
        CollectionManager.withCol {
            val cid = db.queryLongScalar("select cid from revlog where id = ?", at)
            if (cid == 0L) {
                null
            } else {
                val card = getCard(cid)
                val note = card.note(this)
                val question =
                    try {
                        card.question(this)
                    } catch (e: Exception) {
                        Timber.w(e, "ankiquest conquered card front cannot be rendered")
                        ""
                    }
                val template =
                    try {
                        card.template(this).qfmt
                    } catch (e: Exception) {
                        Timber.w(e, "ankiquest conquered card template unavailable")
                        null
                    }
                AnkiquestShare.Labels(
                    AnkiquestCardLabels.suggestedLabel(
                        question,
                        note.fields,
                        note.notetype.isCloze,
                        template,
                        note.notetype.fieldsNames,
                    ),
                    AnkiquestCardLabels.legacyLabel(note.fields),
                )
            }
        }

    override suspend fun conqueredDeck(at: Long): String? = AnkiquestDeckNames.find(at)?.name

    override suspend fun studyTime(now: Long): AnkiquestStudyTime.Totals = CollectionManager.withCol { AnkiquestStudyLog.load(this, now) }

    override fun chooseDeck(
        activity: FragmentActivity,
        account: HomeAccount,
        at: Long,
        chosen: (String?) -> Unit,
    ) = AnkiquestDeckNameDialog.show(activity, account, at, chosen)
}
