// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestMomentShare.Choice
import com.ichi2.anki.ankiquest.AnkiquestMomentShare.Target
import com.ichi2.anki.ankiquest.AnkiquestSharing.Audience
import com.ichi2.anki.ankiquest.AnkiquestSharing.MomentRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AnkiquestMomentShareTest : RobolectricTest() {
    private val account = HomeAccount("https://quest.example.test", "aldanita", "synthetic-token")
    private val ana = Target.Friend("ana", "Ana")
    private val cerro = Target.Friend("cerro", "Cerro")
    private val club = Target.Group(3, "Spanish club")
    private val members = listOf(AnkiquestSocial.Person("aldanita", "Aldana"), AnkiquestSocial.Person("ana", "Ana"))
    private val alone = AnkiquestSocial.Group(4, "Just me", owner = true, invite = "code", members = members.take(1))
    private val overview =
        AnkiquestSocial.Overview(
            friends = listOf(AnkiquestSocial.Person("cerro", "Cerro"), AnkiquestSocial.Person("ana", "Ana")),
            incoming = emptyList(),
            outgoing = emptyList(),
            muted = emptyList(),
            groups = listOf(AnkiquestSocial.Group(3, "Spanish club", owner = false, invite = null, members = members), alone),
            public = true,
        )

    @Before
    fun prepare() {
        mockkObject(AnkiquestSocial, AnkiquestSharing)
        coEvery { AnkiquestSocial.overview(account) } returns overview
        coEvery { AnkiquestSharing.send(account, any(), any(), any()) } returns AnkiquestSharing.Sent(1, 6, null)
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestSocial, AnkiquestSharing)
    }

    @Test
    fun `current weekly places require their own server capability`() {
        mockkObject(AnkiquestCapabilities)
        var features = setOf("share_moments")
        every { AnkiquestCapabilities.has(account, any()) } answers { secondArg<String>() in features }
        try {
            assertTrue(AnkiquestMomentShare.available(account, MomentRef.Win(17)))
            assertFalse(AnkiquestMomentShare.available(account, MomentRef.Place(1)))
            features = setOf("share_moments", "share_place")
            assertTrue(AnkiquestMomentShare.available(account, MomentRef.Place(1)))
            features = setOf("share_place")
            assertFalse(AnkiquestMomentShare.available(account, MomentRef.Place(1)))
        } finally {
            unmockkObject(AnkiquestCapabilities)
        }
    }

    @Test
    fun `friends come before groups by name, and a group of only the player is left out`() =
        runBlocking {
            assertEquals(listOf(ana, cerro, club), AnkiquestMomentShare.targets(account))
        }

    @Test
    fun `older notices and decks require their own capabilities`() {
        mockkObject(AnkiquestCapabilities)
        var features = emptySet<String>()
        every { AnkiquestCapabilities.has(account, any()) } answers { secondArg<String>() in features }
        try {
            for ((reference, feature) in listOf(
                MomentRef.Notification(42) to "share_notification",
                MomentRef.Deck("7", "My Japanese") to "share_deck_moment",
            )) {
                features = setOf("share_moments")
                assertFalse(AnkiquestMomentShare.available(account, reference))
                features = setOf(feature)
                assertFalse(AnkiquestMomentShare.available(account, reference))
                features = setOf("share_moments", feature)
                assertTrue(AnkiquestMomentShare.available(account, reference))
            }
        } finally {
            unmockkObject(AnkiquestCapabilities)
        }
    }

    @Test
    fun `people sent to lately come first, newest first`() =
        runBlocking {
            AnkiquestMomentShare.send(account, MomentRef.Win(1), Choice.Picked(setOf(club)), null)
            AnkiquestMomentShare.send(account, MomentRef.Win(2), Choice.Picked(setOf(cerro)), null)
            assertEquals(listOf(cerro, club, ana), AnkiquestMomentShare.targets(account))

            // "All friends" names nobody, so it changes nothing.
            AnkiquestMomentShare.send(account, MomentRef.Win(3), Choice.Everyone, null)
            assertEquals(listOf(cerro, club, ana), AnkiquestMomentShare.targets(account))
        }

    @Test
    fun `recent choices belong to one account`() =
        runBlocking {
            AnkiquestMomentShare.send(account, MomentRef.Win(1), Choice.Picked(setOf(club)), null)
            val other = account.copy(user = "other")
            coEvery { AnkiquestSocial.overview(other) } returns overview
            assertEquals(listOf(ana, cerro), AnkiquestMomentShare.targets(other).filterIsInstance<Target.Friend>())
            assertEquals(club, AnkiquestMomentShare.targets(other).last())
        }

    @Test
    fun `choices switch between nobody, all friends and chosen people`() {
        val one = Choice.Nobody.toggle(ana)
        assertEquals(Choice.Picked(setOf(ana)), one)
        assertEquals(Choice.Picked(setOf(ana, club)), one.toggle(club))
        assertEquals(Choice.Nobody, one.toggle(ana))
        assertEquals(Choice.Everyone, one.toggleEveryone())
        assertEquals(Choice.Picked(setOf(cerro)), Choice.Everyone.toggle(cerro))
        assertEquals(Choice.Nobody, Choice.Everyone.toggleEveryone())

        assertNull(Choice.Nobody.audience())
        assertEquals(Audience.Circle, Choice.Everyone.audience())
        assertEquals(Audience.Chosen(listOf("ana"), listOf(3L)), Choice.Picked(setOf(ana, club)).audience())
    }

    @Test
    fun `sending names the chosen friends and groups and cleans the note`() =
        runBlocking {
            AnkiquestMomentShare.send(account, MomentRef.Conquest(100), Choice.Picked(setOf(ana, club)), "  Took me\n a month!  ")
            coVerify {
                AnkiquestSharing.send(
                    account,
                    MomentRef.Conquest(100),
                    Audience.Chosen(listOf("ana"), listOf(3L)),
                    "Took me a month!",
                )
            }

            AnkiquestMomentShare.send(account, MomentRef.Win(7), Choice.Everyone, " \n ")
            coVerify { AnkiquestSharing.send(account, MomentRef.Win(7), Audience.Circle, null) }
        }

    @Test
    fun `a note is one line of at most the server's length`() {
        assertEquals("a b", AnkiquestMomentShare.note(" a \t\n b "))
        assertNull(AnkiquestMomentShare.note("   "))
        assertEquals(AnkiquestSharing.MAX_NOTE, AnkiquestMomentShare.note("x".repeat(200))?.length)
    }

    @Test
    fun `recipient order follows the shared cases`() =
        runBlocking {
            val cases = fixture().getJSONArray("order")
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val who = account.copy(server = "https://quest.example.test/$index", user = case.getString("me"))
                val social = case.getJSONObject("social")
                val groups = social.getJSONArray("groups")
                coEvery { AnkiquestSocial.overview(who) } returns
                    overview.copy(
                        friends = people(social.getJSONArray("friends")),
                        groups =
                            (0 until groups.length()).map { groupIndex ->
                                val group = groups.getJSONObject(groupIndex)
                                AnkiquestSocial.Group(
                                    group.getLong("id"),
                                    group.getString("name"),
                                    owner = false,
                                    invite = null,
                                    members = people(group.getJSONArray("members")),
                                )
                            },
                    )
                val actual = AnkiquestMomentShare.order(AnkiquestMomentShare.targets(who), strings(case.getJSONArray("recent")))
                assertEquals(strings(case.getJSONArray("keys")), actual.map { it.key }, case.getString("name"))
            }
        }

    @Test
    fun `exclusive recipient choices follow the shared cases`() {
        val cases = fixture().getJSONArray("selection")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            var choice: Choice = Choice.Nobody
            for (tap in strings(case.getJSONArray("taps"))) {
                choice =
                    when {
                        tap == "circle" -> choice.toggleEveryone()
                        tap.startsWith("user:") -> choice.toggle(Target.Friend(tap.removePrefix("user:"), tap))
                        else -> choice.toggle(Target.Group(tap.removePrefix("group:").toLong(), tap))
                    }
            }
            val expected =
                when {
                    case.isNull("to") -> null
                    case.optString("to") == "circle" -> Audience.Circle
                    else -> {
                        val to = case.getJSONObject("to")
                        val groups = to.getJSONArray("groups")
                        Audience.Chosen(strings(to.getJSONArray("friends")), (0 until groups.length()).map { groups.getLong(it) })
                    }
                }
            assertEquals(expected, choice.audience(), case.toString())
        }
    }

    @Test
    fun `notes follow the shared Unicode cases`() {
        val cases = fixture().getJSONArray("notes")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val text = if (case.has("unit")) case.getString("unit").repeat(case.getInt("repeat")) else case.getString("text")
            val expected =
                when {
                    case.has("unit") -> case.getString("unit").repeat(case.getInt("expected_repeat"))
                    case.isNull("expected") -> null
                    else -> case.getString("expected")
                }
            assertEquals(expected, AnkiquestMomentShare.note(text), "shared note case $index")
        }
    }

    private fun fixture() = JSONObject(assertNotNull(javaClass.classLoader?.getResource("ankiquest/share_targets.json")).readText())

    private fun strings(values: JSONArray) = (0 until values.length()).map { values.getString(it) }

    private fun people(values: JSONArray) =
        (0 until values.length()).map {
            val person = values.getJSONObject(it)
            AnkiquestSocial.Person(person.getString("user"), person.optString("display"))
        }

    @Test
    fun `search matches names and usernames, ignoring case`() {
        assertTrue(AnkiquestMomentShare.matches(club, "span"))
        assertTrue(AnkiquestMomentShare.matches(Target.Friend("cerro", "Ana María"), "CERRO"))
        assertTrue(AnkiquestMomentShare.matches(ana, "  "))
        assertFalse(AnkiquestMomentShare.matches(ana, "club"))
    }

    @Test
    fun `a sent moment can be undone only before its deadline`() {
        assertTrue(AnkiquestSharing.Sent(1, 6, 2_000).undoable(1_999))
        assertFalse(AnkiquestSharing.Sent(1, 6, 2_000).undoable(2_000))
        assertFalse(AnkiquestSharing.Sent(0, 6, null).undoable(0))
    }
}
