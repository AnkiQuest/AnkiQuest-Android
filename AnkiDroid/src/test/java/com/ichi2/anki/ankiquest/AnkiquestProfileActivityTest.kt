// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.content.IntentCompat
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.preferences.PreferencesActivity
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp")
class AnkiquestProfileActivityTest : RobolectricTest() {
    private val identity = HomeAccount("https://anki.example.test", "hill", "secret")
    private val profile = AnkiquestBoards.parseProfile(JSONObject(AnkiquestBoardsTest.PROFILE))
    private val own =
        AnkiquestBoards.PlayerPage(
            profile,
            own = true,
            wins = listOf(AnkiquestBoards.Win("Daily goal reached!", "5 cards today in 0 minutes.", 1793183400)),
        )

    @Before
    fun prepareProfile() {
        mockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestSocial, AnkiquestSubscriptions, AnkiquestRecords)
        every { AnkiquestHomeData.account(any()) } returns identity
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("offline")
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(any()) } throws IOException("offline")
        coEvery { AnkiquestRecords.records(any(), any()) } returns emptyList()
        coEvery { AnkiquestRecords.scopes(any()) } returns emptyList()
        coEvery { AnkiquestRecords.study(any(), any()) } throws IOException("offline")
    }

    private val repository = mockk<SubscriptionRepository>()

    @After
    fun clearProfileMocks() {
        unmockkObject(AnkiquestHomeData, AnkiquestBoards, AnkiquestSocial, AnkiquestSubscriptions, AnkiquestRecords)
    }

    private fun person(user: String) = AnkiquestSocial.Person(user, user.replaceFirstChar(Char::uppercase))

    private fun overview(
        vararg muted: String,
        friends: List<String> = emptyList(),
        incoming: List<String> = emptyList(),
        outgoing: List<String> = emptyList(),
        groups: List<AnkiquestSocial.Group> = emptyList(),
    ) = AnkiquestSocial.Overview(
        friends = friends.map(::person),
        incoming = incoming.map(::person),
        outgoing = outgoing.map(::person),
        muted = muted.map(::person),
        groups = groups,
        public = true,
    )

    /** An overview where the player stands with cerro as [relation]. */
    private fun standing(relation: AnkiquestSocial.Relation): AnkiquestSocial.Overview =
        when (relation) {
            AnkiquestSocial.Relation.NONE -> overview()
            AnkiquestSocial.Relation.REQUESTED -> overview(outgoing = listOf("cerro"))
            AnkiquestSocial.Relation.ASKED -> overview(incoming = listOf("cerro"))
            AnkiquestSocial.Relation.FRIENDS -> overview(friends = listOf("cerro"))
        }

    /** A group the player owns, with its invite code, unless [invite] is null as for a group someone else owns. */
    private fun group(
        id: Long,
        name: String,
        members: List<String> = emptyList(),
        invite: String? = "code-$id",
    ) = AnkiquestSocial.Group(id, name, owner = invite != null, invite = invite, members = members.map(::person))

    private fun cerro() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
    }

    @Test
    fun `another player's profile shows the friendship action for each relation`() {
        cerro()
        val add = R.string.aq_profile_add_friend
        val cancel = R.string.aq_friends_cancel_request
        val accept = R.string.aq_profile_accept_request
        val decline = R.string.aq_profile_decline_request
        val expected =
            mapOf(
                AnkiquestSocial.Relation.NONE to setOf(add),
                AnkiquestSocial.Relation.REQUESTED to setOf(cancel),
                AnkiquestSocial.Relation.ASKED to setOf(accept, decline),
                AnkiquestSocial.Relation.FRIENDS to emptySet(),
            )
        for ((relation, shown) in expected) {
            coEvery { AnkiquestSocial.overview(identity) } returns standing(relation)
            val screen = launch("cerro")
            advanceRobolectricLooper()
            for (label in listOf(add, cancel, accept, decline)) {
                assertEquals(label in shown, screen.hasText(screen.getString(label)), "$relation: ${screen.getString(label)}")
            }
            assertEquals(relation == AnkiquestSocial.Relation.REQUESTED, screen.hasText(screen.getString(R.string.aq_profile_request_sent)))
            assertEquals(
                relation == AnkiquestSocial.Relation.ASKED,
                screen.hasText(screen.getString(R.string.aq_profile_request_received, "Cerro")),
            )
            assertEquals(
                relation == AnkiquestSocial.Relation.FRIENDS,
                screen.hasText(screen.getString(R.string.aq_profile_friends, "Cerro")),
            )
        }
    }

    @Test
    fun `each friendship action sends its social change`() {
        cerro()
        val cases =
            listOf(
                Triple(AnkiquestSocial.Relation.NONE, R.string.aq_profile_add_friend, AnkiquestSocial.Change.Befriend("cerro")),
                Triple(AnkiquestSocial.Relation.REQUESTED, R.string.aq_friends_cancel_request, AnkiquestSocial.Change.Unfriend("cerro")),
                Triple(AnkiquestSocial.Relation.ASKED, R.string.aq_profile_accept_request, AnkiquestSocial.Change.Befriend("cerro")),
                Triple(AnkiquestSocial.Relation.ASKED, R.string.aq_profile_decline_request, AnkiquestSocial.Change.Unfriend("cerro")),
            )
        for ((relation, label, change) in cases) {
            coEvery { AnkiquestSocial.overview(identity) } returns standing(relation)
            coEvery { AnkiquestSocial.change(identity, any()) } returns standing(relation)
            val screen = launch("cerro")
            advanceRobolectricLooper()
            screen.click(screen.getString(label))
            coVerify(exactly = 1) { AnkiquestSocial.change(identity, change) }
            clearMocks(AnkiquestSocial, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true)
        }
    }

    @Test
    fun `invite to group shares the link of an owned group they are not in`() {
        cerro()
        val owned = group(1, "Spanish club")
        val joined = group(2, "Night owls", members = listOf("cerro"))
        val foreign = group(3, "Owls", invite = null)
        coEvery { AnkiquestSocial.overview(identity) } returns overview(groups = listOf(joined, foreign))
        val none = launch("cerro")
        advanceRobolectricLooper()
        assertFalse(none.hasText(none.getString(R.string.aq_profile_invite)), "no group to invite them to")

        // One group shares its link at once.
        coEvery { AnkiquestSocial.overview(identity) } returns overview(groups = listOf(owned, joined, foreign))
        val one = launch("cerro")
        advanceRobolectricLooper()
        one.click(one.getString(R.string.aq_profile_invite))
        assertEquals(AnkiquestSocial.inviteLink(identity, "code-1"), sharedLink(one))

        // Several groups ask which one first.
        val second = group(4, "Study night")
        coEvery { AnkiquestSocial.overview(identity) } returns overview(groups = listOf(owned, second))
        val two = launch("cerro")
        advanceRobolectricLooper()
        two.click(two.getString(R.string.aq_profile_invite))
        assertNull(shadowOf(two).nextStartedActivity, "nothing is shared before a group is picked")
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(1, null, list), 1, list.adapter.getItemId(1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AnkiquestSocial.inviteLink(identity, "code-4"), sharedLink(two))
    }

    /** The invite link in the text the screen last handed to the share sheet. */
    private fun sharedLink(screen: AnkiquestProfileActivity): String? {
        val chooser = assertNotNull(shadowOf(screen).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = assertNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        return send.getStringExtra(Intent.EXTRA_TEXT)?.substringAfterLast(' ')
    }

    @Test
    fun `another player can be muted and their deck alerts stopped from their profile`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        coEvery { repository.load(identity) } returns IncomingSubscriptions(true, emptySet(), listOf(IncomingSender("cerro", "Cerro")))
        coEvery { repository.setSubscribed(identity, "cerro", false) } returns
            IncomingSubscriptions(true, setOf("cerro"), listOf(IncomingSender("cerro", "Cerro")))
        val screen = launch("cerro")
        advanceRobolectricLooper()
        assertTrue(screen.hasText(screen.getString(R.string.aq_friends_mute)))
        val stop = screen.getString(R.string.aq_profile_deck_alerts_stop, "Cerro")
        assertTrue(screen.hasText(stop))

        coEvery { repository.load(identity) } returns IncomingSubscriptions(true, setOf("cerro"), listOf(IncomingSender("cerro", "Cerro")))
        screen.click(stop)
        advanceRobolectricLooper()
        coVerify { repository.setSubscribed(identity, "cerro", false) }
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_deck_alerts_start, "Cerro")))
    }

    @Test
    fun `profile mute applies immediately and offers Undo`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        coEvery { AnkiquestSocial.overview(identity) } returns overview()
        coEvery { AnkiquestSocial.change(identity, any()) } returns overview("cerro")
        val screen = launch("cerro")
        advanceRobolectricLooper()
        screen.views<TextView>().first { it.text == screen.getString(R.string.aq_friends_mute) }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Mute("cerro")) }
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertNotNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action)).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        coVerify(exactly = 1) { AnkiquestSocial.change(identity, AnkiquestSocial.Change.Unmute("cerro")) }
    }

    @Test
    fun `controls the server cannot tell about stay hidden, and the player's own profile pauses streak protection`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val theirs = launch("cerro")
        advanceRobolectricLooper()
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_friends_mute)))
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_profile_freezes_pause)))

        val mine = launch()
        assertTrue(mine.hasText(mine.getString(R.string.aq_profile_freezes_pause)))
        assertFalse(mine.hasText(mine.getString(R.string.aq_friends_mute)))
    }

    @Test
    fun `pause and resume apply immediately and Undo restores the previous protection`() {
        mockkObject(Ankiquest)
        try {
            for (initial in listOf(true, false)) {
                var enabled = initial
                val changes = mutableListOf<Boolean>()
                coEvery { AnkiquestBoards.player(identity, "hill") } answers {
                    own.copy(profile = profile.copy(freezesEnabled = enabled))
                }
                coEvery { Ankiquest.setStreakProtection(any()) } answers {
                    enabled = firstArg()
                    changes.add(enabled)
                    enabled
                }
                val screen = launch()
                advanceRobolectricLooper()
                val label = if (initial) R.string.aq_profile_freezes_pause else R.string.aq_profile_freezes_resume
                screen.views<TextView>().first { it.text == screen.getString(label) }.performClick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
                assertEquals(listOf(!initial), changes)
                assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
                assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_count, profile.freezes)))
                val undo = assertNotNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action))
                undo.performClick()
                undo.performClick()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
                assertEquals(listOf(!initial, initial), changes, "Undo restores the setting once")
            }
        } finally {
            unmockkObject(Ankiquest)
        }
    }

    @Test
    fun `a failed protection change keeps the setting and offers no Undo`() {
        mockkObject(Ankiquest)
        try {
            coEvery { Ankiquest.setStreakProtection(false) } throws IOException("offline")
            val screen = launch()
            advanceRobolectricLooper()
            screen.views<TextView>().first { it.text == screen.getString(R.string.aq_profile_freezes_pause) }.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            coVerify(exactly = 1) { Ankiquest.setStreakProtection(false) }
            assertNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action))
            assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_ready)))
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        } finally {
            unmockkObject(Ankiquest)
        }
    }

    @Test
    fun `the protection explanation belongs beside its control on Me`() {
        val screen = launch()
        assertTrue(screen.hasText(screen.getString(R.string.ankiquest_streak_protection_summary)))
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test
    fun `the player's own profile shows level, streak, freezes, quests, wins and achievements`() {
        val screen = launch()
        coVerify { AnkiquestBoards.player(identity, "hill") }
        assertEquals(screen.getString(R.string.ankiquest_me), screen.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_level, 2)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_level_progress, "1", "280", 3, "101")))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_count, 2)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_freezes_ready)))
        assertTrue(screen.hasText(screen.getString(R.string.ankiquest_streak_studied)))
        assertTrue(screen.hasText("Review 15 cards"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_wins)))
        assertTrue(screen.hasText("Daily goal reached!"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_leech)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_best_hour)))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_unlocked, 1, 3)))
        assertTrue(screen.hasText("Done"))
        screen.click(screen.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(screen).nextStartedActivity.component?.className)
    }

    @Test
    fun `a closing week still counting shows its XP until it closes`() {
        val endsAt = TimeManager.time.intTimeMS() + 60 * 60 * 1000L
        coEvery { AnkiquestBoards.player(identity, "hill") } returns
            own.copy(profile = profile.copy(closingWeek = AnkiquestBoards.ClosingWeek(1234, endsAt)))
        val screen = launch()
        val line = assertNotNull(AnkiquestBoards.closingLine(screen, AnkiquestBoards.ClosingWeek(1234, endsAt), endsAt - 1))
        assertTrue(screen.hasText(line))
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own
        val without = launch()
        assertFalse(without.hasText(line))
    }

    @Test
    fun `another player's profile has no wins or settings`() {
        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val screen = launch("cerro")
        assertEquals("Cerro", screen.findViewById<TextView>(R.id.aq_board_title).text.toString())
        assertFalse(screen.hasText(screen.getString(R.string.aq_home_settings)))
        assertFalse(screen.hasText(screen.getString(R.string.aq_profile_wins)))
        screen.click(screen.getString(R.string.aq_profile_leaderboard))
        assertEquals(AnkiquestFriendsActivity::class.java.name, shadowOf(screen).nextStartedActivity.component?.className)
    }

    @Test
    fun `records and history expand on Me while another player's history stays private`() {
        val mine = launch()
        coVerify(exactly = 0) { AnkiquestRecords.records(any(), any()) }
        coVerify(exactly = 0) { AnkiquestRecords.study(any(), any()) }
        mine.click(mine.getString(R.string.aq_records_title))
        assertNull(shadowOf(mine).nextStartedActivity, "records stay on Me")
        advanceRobolectricLooperUntil { mine.hasText(mine.getString(R.string.aq_records_empty)) }
        mine.click(mine.getString(R.string.aq_history_title))
        assertNull(shadowOf(mine).nextStartedActivity, "history stays on Me")
        advanceRobolectricLooperUntil { mine.hasText(mine.getString(R.string.aq_history_intro)) }

        coEvery { AnkiquestBoards.player(identity, "cerro") } returns
            AnkiquestBoards.PlayerPage(profile.copy(user = "cerro", display = "Cerro"), own = false, wins = emptyList())
        val theirs = launch("cerro")
        assertTrue(theirs.hasText(theirs.getString(R.string.aq_records_title)))
        assertFalse(theirs.hasText(theirs.getString(R.string.aq_history_title)), "another player's history is private")
    }

    @Test
    fun `many achievements show the closest first until all are asked for`() {
        val many =
            (1..10).map {
                AnkiquestBoards.Achievement("Goal $it", "", 10, it.toLong(), 100, null)
            }
        coEvery { AnkiquestBoards.player(identity, "hill") } returns own.copy(profile = profile.copy(achievements = many))
        val screen = launch()
        assertTrue(screen.hasText("Goal 10"))
        assertFalse(screen.hasText("Goal 1"))
        screen.click(screen.getString(R.string.aq_profile_show_all))
        assertTrue(screen.hasText("Goal 1"))
        assertTrue(screen.hasText(screen.getString(R.string.aq_profile_show_fewer)))
    }

    @Test
    fun `a hidden player and a failure say so`() {
        coEvery { AnkiquestBoards.player(identity, "ghost") } throws AnkiquestBoards.NotVisible()
        val hidden = launch("ghost")
        assertTrue(hidden.hasText(hidden.getString(R.string.aq_profile_hidden)))

        coEvery { AnkiquestBoards.player(identity, "hill") } throws IOException("offline")
        val offline = launch()
        assertTrue(offline.hasText(offline.getString(R.string.aq_board_offline)))
        offline.click(offline.getString(R.string.aq_home_settings))
        assertEquals(PreferencesActivity::class.java.name, shadowOf(offline).nextStartedActivity.component?.className)
    }

    @Test
    fun `without an account the profile asks to connect`() {
        every { AnkiquestHomeData.account(any()) } returns null
        val screen = launch()
        assertTrue(screen.hasText(screen.getString(R.string.aq_board_connect)))
        coVerify(exactly = 0) { AnkiquestBoards.player(any(), any()) }
    }

    private fun launch(user: String? = null): AnkiquestProfileActivity =
        startRegularActivity<AnkiquestProfileActivity>(AnkiquestProfileActivity.intent(targetContext, user))
            .also { screen -> advanceRobolectricLooperUntil { !screen.findViewById<View>(R.id.aq_board_loading).isVisible } }

    private inline fun <reified T : View> AnkiquestProfileActivity.views(): List<T> =
        findViewById<View>(R.id.aq_board_content).allViews.filterIsInstance<T>().toList()

    private fun AnkiquestProfileActivity.hasText(value: String): Boolean = views<TextView>().any { it.text.toString() == value }

    private fun AnkiquestProfileActivity.click(label: String) {
        val control = views<TextView>().first { it.text.toString() == label && it.isClickable }
        control.performClick()
        advanceRobolectricLooperUntil { !findViewById<View>(R.id.aq_board_loading).isVisible }
        advanceRobolectricLooper()
    }
}
