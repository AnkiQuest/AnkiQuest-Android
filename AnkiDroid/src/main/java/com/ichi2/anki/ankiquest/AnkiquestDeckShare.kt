// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Right after a deck is finished for the day, asks whether to tell friends and which ones. Decks
 * already announced to chosen people every day are left to the server; players can turn the
 * question off in the sharing settings. Nobody is ticked to begin with, and the deck's name only
 * leaves the phone for the friends the player picks. A deck's menu sets the same per deck, see [choose].
 */
internal object AnkiquestDeckShare {
    const val PREF_ASK = "ankiquestAskToShareDecks"
    private const val PREF_ASKED = "ankiquestDeckShareAsked"

    data class Recipient(
        val user: String,
        val display: String,
    )

    /** The question for one finished deck: its [name] and the people the player may tell. */
    data class Prompt(
        val deck: Long,
        val name: String,
        val recipients: List<Recipient>,
    )

    /** Whether the question is on; it is unless the player turned it off. */
    fun enabled(prefs: SharedPreferences = AnkiDroidApp.sharedPrefs()): Boolean = prefs.getBoolean(PREF_ASK, true)

    /**
     * The question to ask about [deck] from the server's deck settings (`GET /api/decks`), or null
     * when the deck already tells its chosen people by itself or there is nobody to tell.
     */
    fun prompt(
        deck: Long,
        name: String,
        settings: JSONObject,
    ): Prompt? {
        val automatic =
            settings.optJSONArray("decks")?.objects().orEmpty().any {
                it.optString("id") == deck.toString() &&
                    it.optBoolean("enabled") &&
                    (it.optJSONArray("recipients")?.length() ?: 0) > 0
            }
        if (automatic) return null
        val recipients = recipients(settings)
        return if (recipients.isEmpty()) null else Prompt(deck, name, recipients)
    }

    /** The people the server lets the player tell, from its deck settings. */
    private fun recipients(settings: JSONObject): List<Recipient> =
        settings.optJSONArray("recipients")?.objects().orEmpty().mapNotNull { person ->
            val user = person.optString("user").ifEmpty { return@mapNotNull null }
            Recipient(user, person.optString("display").ifEmpty { user })
        }

    /** The people already told whenever [deck] is finished, as the server's deck settings keep them. */
    fun told(
        deck: Long,
        settings: JSONObject,
    ): Set<String> =
        settings
            .optJSONArray("decks")
            ?.objects()
            .orEmpty()
            .firstOrNull { it.optString("id") == deck.toString() && it.optBoolean("enabled") }
            ?.optJSONArray("recipients")
            ?.strings()
            .orEmpty()
            .toSet()

    /** Whether a deck's menu offers [choose]: only to a signed-in player. */
    fun available(): Boolean = AnkiquestHomeData.account()?.token?.isNotEmpty() == true

    /**
     * From a deck's menu: who hears whenever [deck] is finished, ticked as the server keeps it now.
     * Ticking nobody stops telling anyone about it.
     */
    fun choose(
        activity: AnkiActivity,
        deck: Long,
    ) {
        val account = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() } ?: return
        val undo = deckUndo(activity)
        undo.clear()
        val owner = AnkiquestDialogOwner(activity, account)
        if (!owner.isCurrent()) {
            owner.close()
            return
        }
        activity.lifecycleScope.launch {
            val language = AnkiquestLanguage.context(activity)
            try {
                val settings = Ankiquest.deckNotificationSettings(account)
                val name = withCol { decks.name(deck) }
                if (!owner.isCurrent()) {
                    owner.close()
                    return@launch
                }
                val people = recipients(settings)
                if (people.isEmpty()) {
                    showThemedToast(activity, language.getString(R.string.ankiquest_deck_notifications_no_people), shortLength = false)
                    owner.close()
                    return@launch
                }
                val chosen = told(deck, settings)
                val picked = people.map { it.user in chosen }.toBooleanArray()
                val dialog =
                    AlertDialog
                        .Builder(activity)
                        .setTitle(language.getString(R.string.ankiquest_deck_menu_share_title, name))
                        .setMultiChoiceItems(people.map { it.display }.toTypedArray(), picked) { _, which, checked ->
                            picked[which] = checked
                        }.setPositiveButton(language.getString(R.string.ankiquest_deck_notifications_save)) { dialog, _ ->
                            if (!owner.take(dialog)) {
                                return@setPositiveButton
                            }
                            val recipients = people.filterIndexed { index, _ -> picked[index] }.map { it.user }
                            val context = activity.applicationContext
                            val message =
                                language.getString(
                                    if (recipients.isEmpty()) {
                                        R.string.ankiquest_deck_menu_share_off
                                    } else {
                                        R.string.ankiquest_deck_menu_share_on
                                    },
                                    name,
                                )
                            undo.apply(
                                account,
                                message,
                                perform = { done -> save(context, account, owner, deck, recipients, done) },
                                inverse = {
                                    val restoreOwner = AnkiquestDialogOwner(activity, account)
                                    save(context, account, restoreOwner, deck, chosen.toList()) {
                                        showThemedToast(
                                            context,
                                            language.getString(R.string.ankiquest_deck_sharing_restored),
                                            shortLength = false,
                                        )
                                    }
                                },
                            )
                        }.setNegativeButton(android.R.string.cancel, null)
                        .create()
                owner.show(dialog)
            } catch (e: CancellationException) {
                owner.close()
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest deck sharing unavailable")
                if (owner.isCurrent()) {
                    val reason = (e as? AnkiquestHttpException)?.explanation ?: e.message ?: e.javaClass.simpleName
                    showThemedToast(activity, language.getString(R.string.ankiquest_check_failed, reason), shortLength = false)
                }
                owner.close()
            }
        }
    }

    // Reopening the picker invalidates any pending completion or Undo from the previous edit.
    private fun deckUndo(activity: AnkiActivity): AnkiquestUndo {
        val root = activity.findViewById<View>(android.R.id.content)
        return root.getTag(R.id.ankiquest_deck_share_undo) as? AnkiquestUndo
            ?: AnkiquestUndo(activity).also { root.setTag(R.id.ankiquest_deck_share_undo, it) }
    }

    private fun save(
        context: Context,
        account: HomeAccount,
        owner: AnkiquestDialogOwner,
        deck: Long,
        recipients: List<String>,
        saved: () -> Unit,
    ) {
        applicationScope.launch {
            val language = AnkiquestLanguage.context(context)
            try {
                val id = listOf(deck.toString())
                if (recipients.isEmpty()) {
                    Ankiquest.saveDeckNotificationSettings(emptyList(), id, emptyList(), account)
                } else {
                    Ankiquest.saveDeckNotificationSettings(id, emptyList(), recipients, account)
                }
                if (owner.isCurrent()) {
                    saved()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ankiquest deck sharing not saved")
                if (owner.isCurrent()) {
                    showThemedToast(context, language.getString(R.string.ankiquest_share_failed), shortLength = false)
                }
            } finally {
                owner.close()
            }
        }
    }

    private fun askedKey(
        account: String,
        deck: Long,
        day: Long,
    ): String = "$account:$day:$deck"

    // Check before fetching recipients without consuming the day's question.
    private fun asked(
        prefs: SharedPreferences,
        account: String,
        deck: Long,
        day: Long,
    ): Boolean = askedKey(account, deck, day) in prefs.getStringSet(PREF_ASKED, emptySet()).orEmpty()

    /** True the first time it is asked for [account], [deck] and Anki [day]; it keeps only the current day's answers. */
    fun firstAsk(
        prefs: SharedPreferences,
        account: String,
        deck: Long,
        day: Long,
    ): Boolean {
        if (asked(prefs, account, deck, day)) {
            return false
        }
        val earlier = prefs.getStringSet(PREF_ASKED, emptySet()).orEmpty()
        val entry = askedKey(account, deck, day)
        prefs.edit { putStringSet(PREF_ASKED, earlier.filter { it.contains(":$day:") }.toSet() + entry) }
        return true
    }

    /**
     * Asks about the selected deck when it was just finished for the day, once per deck and day.
     * Every failure only means no question: the study session itself is already done.
     */
    suspend fun offer(context: FragmentActivity) {
        try {
            val prefs = AnkiDroidApp.sharedPrefs()
            if (!enabled(prefs)) return
            val account = AnkiquestHomeData.account()?.takeIf { it.token.isNotEmpty() } ?: return
            val finished =
                withCol {
                    val now = TimeManager.time.intTimeMS()
                    val deck = decks.selected()
                    val state = AnkiquestDecks.dailyStates(this, now)[deck]
                    if (state?.status != DailyDeckStatus.DONE) {
                        null
                    } else {
                        Triple(deck, decks.name(deck), AnkiquestDecks.studyDay(this, now))
                    }
                } ?: return
            val (deck, name, day) = finished
            if (!AnkiquestPostStudyPrompts.ready(context)) {
                return
            }
            if (asked(prefs, account.scope, deck, day)) {
                return
            }
            val settings = AnkiquestApi.call(account, "GET", "api/decks/${account.encodedUser}")
            val prompt = prompt(deck, name, settings) ?: return
            withContext(Dispatchers.Main) {
                if (!AnkiquestPostStudyPrompts.ready(context) || AnkiquestHomeData.account()?.scope != account.scope) {
                    return@withContext
                }
                if (firstAsk(prefs, account.scope, deck, day)) {
                    show(context, account, prompt)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest finished deck question skipped")
        }
    }

    private fun show(
        context: FragmentActivity,
        account: HomeAccount,
        prompt: Prompt,
    ) {
        val language = AnkiquestLanguage.context(context)
        val reference = AnkiquestSharing.MomentRef.Deck(prompt.deck.toString(), AnkiquestDeckNames.clean(prompt.name))
        if (AnkiquestServerUpdate.refuse(context, account)) {
            return
        }
        val card =
            AnkiquestShare.Card(
                language.getString(R.string.ankiquest_deck_moment_title),
                language.getString(R.string.ankiquest_deck_moment_body, reference.name),
            )
        AnkiquestShareSheet(
            context,
            account,
            reference,
            card,
            elsewhere = { chosen ->
                context.lifecycleScope.launch {
                    if (AnkiquestHomeData.account() != account) {
                        return@launch
                    }
                    val intent =
                        withContext(Dispatchers.IO) { AnkiquestShare.textChooser(context, chosen.title, chosen.body, art = chosen.art) }
                    if (AnkiquestHomeData.account() == account && AnkiquestPostStudyPrompts.resumed(context)) {
                        context.startActivity(intent)
                    }
                }
            },
            slot = AnkiquestShareSheet.Slot.POST_STUDY,
        ).show()
    }
}
