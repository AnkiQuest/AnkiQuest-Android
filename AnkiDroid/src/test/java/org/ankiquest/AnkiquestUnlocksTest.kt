// SPDX-License-Identifier: AGPL-3.0-only

package org.ankiquest

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.ankiquest.AnkiquestCompanion.Choice
import org.ankiquest.AnkiquestUnlocks.Found
import org.ankiquest.AnkiquestUnlocks.Milestone
import org.ankiquest.AnkiquestUnlocks.Offer
import org.ankiquest.AnkiquestUnlocks.Progress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Companions earned at milestones, against the shared fixture `tests/fixtures/companion_unlock.json`
 * from the pinned server commit and a fake server that grants and refuses like the real one.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestUnlocksTest : RobolectricTest() {
    private lateinit var server: HttpServer
    private lateinit var account: HomeAccount

    /** Each request as "METHOD path body", and the companion header of each companion save. */
    private val requests = CopyOnWriteArrayList<String>()
    private val companionHeaders = CopyOnWriteArrayList<String?>()

    // What the fake server holds.
    @Volatile private var features = listOf<String>()

    @Volatile private var unlocks = setOf<String>()

    @Volatile private var companion = "aki"

    /** The value each live companion's milestone has reached, for its progress. */
    @Volatile private var reached = 0

    /** Runs once the fake server has handled a companion request, before it answers. */
    @Volatile private var beforeCompanionAnswer: () -> Unit = {}

    @Before
    fun serve() {
        AnkiquestCapabilities.forgetMemory()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val sent = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += "${exchange.requestMethod} $path $sent".trim()
            var status = OK
            val answer =
                when {
                    path == "/api/capabilities" -> JSONObject().put("version", 1).put("features", JSONArray(features))
                    path.startsWith("/api/unlocks/") && AnkiquestUnlocks.FEATURE in features -> {
                        // A reported unlock is granted; the server no longer checks taps.
                        if (sent.isNotEmpty()) {
                            unlocks = unlocks + JSONObject(sent).getString("unlock")
                        }
                        unlocksAnswer()
                    }
                    path.startsWith("/api/companion/") -> {
                        if (sent.isNotEmpty()) {
                            val header = exchange.requestHeaders.getFirst(COMPANIONS_HEADER)
                            companionHeaders += header
                            val chosen = JSONObject(sent).getString("companion")
                            if (refuses(chosen, header)) {
                                status = CONFLICT
                            } else {
                                companion = chosen
                            }
                        }
                        beforeCompanionAnswer()
                        JSONObject().put("companion", companion)
                    }
                    else -> null
                }
            val bytes = answer?.toString()?.toByteArray()
            if (bytes == null) {
                exchange.sendResponseHeaders(NOT_FOUND, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        account = signIn("cerro")
    }

    @After
    fun stop() {
        runCatching { server.stop(0) }
        AnkiquestCapabilities.forgetMemory()
    }

    private val fixture = UnlockFixture.json

    /** The fixture's companions that can be earned now; planned ones are ignored, as the server does. */
    private val live: List<JSONObject> =
        UnlockFixture.objects(fixture.getJSONArray("companions")).filter { it.getString("status") == "live" }

    /** A live companion's entry in the fixture. */
    private fun live(choice: Choice): JSONObject = live.first { it.getString("companion") == choice.id }

    /** The progress the server lists for a live companion, at [value]. */
    private fun progress(
        choice: Choice,
        value: Int,
    ): Progress {
        val entry = live(choice)
        return Progress(Milestone.of(entry.getString("kind")), value, entry.getInt("goal"))
    }

    /**
     * The fake server's `GET /api/unlocks/{user}` with milestones: the unlocks, and progress toward
     * each live companion that is neither unlocked nor secret.
     */
    private fun unlocksAnswer(): JSONObject {
        val answer = JSONObject().put("unlocks", JSONArray(unlocks.sorted()))
        if (MILESTONES !in features) {
            return answer
        }
        val progress = JSONObject()
        for (entry in live) {
            val id = entry.getString("companion")
            if (id !in unlocks && !entry.getBoolean("secret")) {
                progress.put(id, JSONObject().put("kind", entry.getString("kind")).put("value", reached).put("goal", entry.getInt("goal")))
            }
        }
        return answer.put("progress", progress)
    }

    /** Whether the server refuses to save [chosen]: a locked companion, for a client that shows locks. */
    private fun refuses(
        chosen: String,
        header: String?,
    ): Boolean {
        val earned = live.any { it.getString("companion") == chosen }
        return earned && chosen !in unlocks && (header == SHOWS_LOCKS || chosen == Choice.COW.id)
    }

    private fun signIn(user: String): HomeAccount {
        AnkiDroidApp.sharedPrefs().edit(commit = true) {
            putString(Ankiquest.PREF_URL, "http://127.0.0.1:${server.address.port}")
            putString(Ankiquest.PREF_USER, user)
            putString(Ankiquest.PREF_TOKEN, "token-$user")
        }
        return requireNotNull(AnkiquestHomeData.account())
    }

    /** The server reports [added] from now on, as the app learns at its next capabilities check. */
    private fun serverHas(vararg added: String) =
        runBlocking {
            features = features + added
            AnkiquestCapabilities.refresh(account)
        }

    @Test
    fun `the app knows every live companion and milestone of the shared fixture`() {
        assertTrue(live.isNotEmpty())
        for (entry in live) {
            val id = entry.getString("companion")
            assertNotNull(Choice.entries.firstOrNull { it.id == id }, id)
            assertNotNull(Milestone.of(entry.getString("kind")), id)
        }

        // Planned companions have no art yet, so the app has no choice for them.
        val planned = UnlockFixture.objects(fixture.getJSONArray("companions")).filter { it.getString("status") == "planned" }
        assertTrue(planned.none { entry -> Choice.entries.any { it.id == entry.getString("companion") } })
    }

    @Test
    fun `Aki and no companion are always free`() {
        for (found in listOf(null, Found.NONE, Found(setOf(Choice.COW.id), mapOf(Choice.ANKILOPE to progress(Choice.ANKILOPE, 2))))) {
            assertEquals(Offer.Free, AnkiquestUnlocks.offer(Choice.AKI, found, Choice.NONE))
            assertEquals(Offer.Free, AnkiquestUnlocks.offer(Choice.NONE, found, Choice.AKI))
        }
    }

    @Test
    fun `a companion with progress is locked with its condition`() {
        val level = progress(Choice.ANKILOPE, 2)
        val hours = progress(Choice.COW, 3)
        val found = Found(emptySet(), mapOf(Choice.ANKILOPE to level, Choice.COW to hours))

        assertEquals(Offer.Locked(level), AnkiquestUnlocks.offer(Choice.ANKILOPE, found, Choice.AKI))
        assertEquals(Offer.Locked(hours), AnkiquestUnlocks.offer(Choice.COW, found, Choice.AKI))
        assertEquals(Milestone.LEVEL, level.milestone)
        assertEquals(Milestone.STUDY_HOURS, hours.milestone)
    }

    @Test
    fun `an unlocked companion can be chosen`() {
        val found = Found(setOf(Choice.ANKILOPE.id, Choice.COW.id), emptyMap())
        assertEquals(Offer.Unlocked, AnkiquestUnlocks.offer(Choice.ANKILOPE, found, Choice.AKI))
        assertEquals(Offer.Unlocked, AnkiquestUnlocks.offer(Choice.COW, found, Choice.AKI))
    }

    @Test
    fun `a companion chosen before milestones stays a choice`() {
        val found = Found(emptySet(), mapOf(Choice.ANKILOPE to progress(Choice.ANKILOPE, 2)))
        assertEquals(Offer.Unlocked, AnkiquestUnlocks.offer(Choice.ANKILOPE, found, Choice.ANKILOPE))
        assertEquals(Offer.Unlocked, AnkiquestUnlocks.offer(Choice.ANKILOPE, null, Choice.ANKILOPE))
    }

    @Test
    fun `a server without progress keeps Ankilope free and the cow hidden until unlocked`() {
        assertEquals(Offer.Free, AnkiquestUnlocks.offer(Choice.ANKILOPE, Found.NONE, Choice.AKI))
        assertEquals(Offer.Hidden, AnkiquestUnlocks.offer(Choice.COW, Found.NONE, Choice.AKI))
        assertEquals(Offer.Unlocked, AnkiquestUnlocks.offer(Choice.COW, Found(setOf(Choice.COW.id), emptyMap()), Choice.AKI))
    }

    @Test
    fun `earned companions stay hidden until the server answers`() {
        assertEquals(Offer.Hidden, AnkiquestUnlocks.offer(Choice.ANKILOPE, null, Choice.AKI))
        assertEquals(Offer.Hidden, AnkiquestUnlocks.offer(Choice.COW, null, Choice.AKI))
        assertNull(AnkiquestUnlocks.found(account))
        assertEquals(listOf(Choice.AKI, Choice.NONE), AnkiquestCompanion.offers(account, Choice.AKI).keys.toList())
    }

    @Test
    fun `the picker shows the server's unlocks and progress in order`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(Choice.COW.id)
            reached = 2

            AnkiquestUnlocks.sync(account)

            assertEquals(
                mapOf(
                    Choice.AKI to Offer.Free,
                    Choice.ANKILOPE to Offer.Locked(progress(Choice.ANKILOPE, 2)),
                    Choice.COW to Offer.Unlocked,
                    Choice.NONE to Offer.Free,
                ),
                AnkiquestCompanion.offers(account, Choice.AKI),
            )
            assertEquals(
                listOf(Choice.AKI, Choice.ANKILOPE, Choice.COW, Choice.NONE),
                AnkiquestCompanion.offers(account, Choice.AKI).keys.toList(),
            )
            assertTrue(requests.none { it.startsWith("POST /api/unlocks") }, "$requests")
        }

    @Test
    fun `an old server offers Ankilope and no cow`() =
        runBlocking {
            AnkiquestCapabilities.refresh(account)

            AnkiquestUnlocks.sync(account)

            assertEquals(Found.NONE, AnkiquestUnlocks.found(account))
            assertEquals(listOf(Choice.AKI, Choice.ANKILOPE, Choice.NONE), AnkiquestCompanion.offers(account, Choice.AKI).keys.toList())
            assertTrue(requests.none { it.contains("/api/unlocks/") }, "$requests")
        }

    @Test
    fun `unlocks belong to the account`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(Choice.COW.id)
            AnkiquestUnlocks.sync(account)
            assertEquals(setOf(Choice.COW), AnkiquestUnlocks.found(account)?.unlocks)

            assertNull(AnkiquestUnlocks.found(signIn("other")))

            // Signing in again with a new token keeps them: they belong to the player, not the token.
            AnkiDroidApp.sharedPrefs().edit(commit = true) { putString(Ankiquest.PREF_USER, "cerro") }
            AnkiDroidApp.sharedPrefs().edit(commit = true) { putString(Ankiquest.PREF_TOKEN, "renewed") }
            assertEquals(setOf(Choice.COW), AnkiquestUnlocks.found(requireNotNull(AnkiquestHomeData.account()))?.unlocks)
        }

    @Test
    fun `every companion save says the app shows locked companions`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)

            AnkiquestCompanion.choose(account, Choice.NONE)
            AnkiquestCompanion.choose(account, Choice.AKI)

            assertEquals(listOf<String?>(SHOWS_LOCKS, SHOWS_LOCKS), companionHeaders.toList())
        }

    @Test
    fun `the server refuses a locked companion and the saved one stays`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            AnkiquestCompanion.remember(account, Choice.AKI.id)

            val refused = assertFailsWith<AnkiquestHttpException> { AnkiquestCompanion.choose(account, Choice.ANKILOPE) }

            assertEquals(CONFLICT, refused.code)
            assertEquals(Choice.AKI, AnkiquestCompanion.choice())
            assertEquals("aki", companion)
        }

    @Test
    fun `an unlock and a choice from another device arrive without settings sync`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(Choice.COW.id)
            companion = Choice.COW.id

            assertTrue(AnkiquestCompanion.refresh(account))

            assertEquals(Choice.COW, AnkiquestCompanion.choice())
            assertEquals(Offer.Unlocked, AnkiquestCompanion.offers(account, Choice.COW)[Choice.COW])
            assertTrue(requests.none { it.startsWith("POST") }, "$requests")
        }

    @Test
    fun `an unlocked cow is saved on the server`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(Choice.COW.id)

            assertEquals(Choice.COW, AnkiquestCompanion.choose(account, Choice.COW))

            assertEquals("cow", companion)
            assertEquals(Choice.COW, AnkiquestCompanion.choice())
        }

    @Test
    fun `unlocks that are not choices here are kept by id`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(Choice.COW.id, ANKI_TAN)

            AnkiquestUnlocks.sync(account)

            val found = assertNotNull(AnkiquestUnlocks.found(account))
            assertEquals(setOf(Choice.COW), found.unlocks)
            assertEquals(setOf(Choice.COW.id, ANKI_TAN), found.ids)
        }

    @Test
    fun `a tapped chibi is reported without its taps and kept`() =
        runBlocking {
            serverHas(AnkiquestUnlocks.FEATURE, MILESTONES)
            unlocks = setOf(ANKI_TAN)
            val chibi = AnkiquestChibi.ALL.single { it.base == ANKI_TAN }

            AnkiquestUnlocks.claim(account, chibi)

            assertEquals("POST /api/unlocks/cerro {\"unlock\":\"${chibi.id}\"}", requests.single { it.startsWith("POST") })
            assertEquals(setOf(ANKI_TAN, chibi.id), AnkiquestUnlocks.found(account)?.ids)
        }

    @Test
    fun `the chibi hint is worded as the fixture says`() {
        for (left in 1..AnkiquestChibi.TAPS - AnkiquestChibi.HINT_FROM) {
            val shown = targetContext.resources.getQuantityString(R.plurals.ankiquest_companion_chibi_hint, left, left)
            assertEquals(UnlockFixture.hint(left), shown)
        }
    }

    @Test
    fun `a choice queued for a signed-out account is never sent`() =
        runBlocking {
            val previous = account
            signIn("another-member")

            assertFailsWith<HomeAccountChanged> { AnkiquestCompanion.choose(previous, Choice.NONE) }

            assertTrue(requests.none { it.startsWith("POST /api/companion/") }, "$requests")
            assertEquals("aki", companion)
        }

    @Test
    fun `a choice answered after the account changed is not used`() =
        runBlocking {
            AnkiquestCompanion.remember(account, Choice.AKI.id)
            val previous = account
            beforeCompanionAnswer = { signIn("another-member") }

            assertFailsWith<HomeAccountChanged> { AnkiquestCompanion.choose(previous, Choice.NONE) }

            // Neither account's companion on this phone took the late answer.
            assertEquals(Choice.AKI, AnkiquestCompanion.choice())
            beforeCompanionAnswer = {}
            signIn("cerro")
            assertEquals(Choice.AKI, AnkiquestCompanion.choice())
        }

    @Test
    fun `a refresh queued for a signed-out account reads nothing`() =
        runBlocking {
            val previous = account
            signIn("another-member")

            assertFailsWith<HomeAccountChanged> { AnkiquestCompanion.refresh(previous) }

            assertTrue(requests.none { it.contains("/api/companion/") }, "$requests")
        }

    @Test
    fun `a refresh answered after the account changed is not used`() =
        runBlocking {
            companion = Choice.NONE.id
            val previous = account
            beforeCompanionAnswer = { signIn("another-member") }

            assertFailsWith<HomeAccountChanged> { AnkiquestCompanion.refresh(previous) }

            assertEquals(Choice.AKI, AnkiquestCompanion.choice())
        }

    private companion object {
        const val ANKI_TAN = "anki_tan"
        const val OK = 200
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val MILESTONES = "companion_milestones"
        const val COMPANIONS_HEADER = "X-AnkiQuest-Companions"
        const val SHOWS_LOCKS = "milestones"
    }
}
