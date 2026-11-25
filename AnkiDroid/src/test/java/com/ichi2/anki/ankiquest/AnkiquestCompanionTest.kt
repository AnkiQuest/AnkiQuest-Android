// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Color
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.view.isVisible
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ankiquest.AnkiquestCompanion.Choice
import com.ichi2.anki.ankiquest.AnkiquestUnlocks.Offer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AnkiquestCompanionTest : RobolectricTest() {
    private fun account(user: String): HomeAccount {
        val preferences = AnkiDroidApp.sharedPrefs()
        preferences.edit(commit = true) {
            putString(Ankiquest.PREF_URL, "https://example.com")
            putString(Ankiquest.PREF_USER, user)
            putString(Ankiquest.PREF_TOKEN, "token-$user")
        }
        return requireNotNull(AnkiquestHomeData.account())
    }

    @get:Rule
    val folder = TemporaryFolder()

    /** Aki with her own place pictures, Ankilope with the core poses only, and Bluma whose fire is her face. */
    @Before
    fun installPacks() {
        AnkiquestPacks.use(
            AnkiquestTestPacks.library(
                folder.newFolder(),
                AnkiquestTestPacks.pack(
                    "aki",
                    AnkiquestTestPacks.colours(AKI_COLOUR, mapOf("place_1" to AKI_PLACE, "winner" to AKI_WINNER)),
                ),
                AnkiquestTestPacks.pack("ankilope", AnkiquestTestPacks.colours(ANKILOPE_COLOUR, mapOf("happy" to ANKILOPE_HAPPY))),
                AnkiquestTestPacks.pack(
                    "cow",
                    AnkiquestTestPacks.colours(COW_COLOUR) - "streak",
                    shared = mapOf("streak" to "face"),
                    name = "Bluma",
                ),
            ),
        )
    }

    @After
    fun restorePacks() {
        AnkiquestPacks.use(null)
    }

    @Test
    fun `choice follows only the current account`() {
        val alice = account("alice")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
        assertEquals("ankilope", AnkiquestCompanion.selected())

        val bob = account("bob")
        assertEquals("aki", AnkiquestCompanion.selected())
        assertFalse(AnkiquestCompanion.remember(alice, "none"))
        assertTrue(AnkiquestCompanion.remember(bob, "none"))
        assertFalse(AnkiquestCompanion.visible())

        account("alice")
        assertEquals("ankilope", AnkiquestCompanion.selected())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `every companion is drawn from its own pack`() {
        assertEquals(AKI_COLOUR, colour(AnkiquestPose.FACE, Choice.AKI))
        assertEquals(ANKILOPE_COLOUR, colour(AnkiquestPose.FACE, Choice.ANKILOPE))
        assertEquals(COW_COLOUR, colour(AnkiquestPose.SAD, Choice.COW))

        // The chosen companion is the default.
        chooseCompanion(Choice.COW)
        assertEquals(COW_COLOUR, colour(AnkiquestPose.FROZEN, AnkiquestCompanion.choice()))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a picture a pack lacks shows the pack's fallback`() {
        // Aki has place 1 and winner; Ankilope has neither, so place 1 falls back through winner to happy.
        assertEquals(AKI_PLACE, colour(AnkiquestPose.PLACE_1, Choice.AKI))
        assertEquals(AKI_WINNER, colour(AnkiquestPose.CHALLENGE_WON, Choice.AKI))
        assertEquals(ANKILOPE_HAPPY, colour(AnkiquestPose.PLACE_1, Choice.ANKILOPE))
        assertEquals(ANKILOPE_COLOUR, colour(AnkiquestPose.STREAK_MILESTONE, Choice.ANKILOPE), "streak tiers end at streak")
        assertEquals(ANKILOPE_COLOUR, colour(AnkiquestPose.ICON_HAPPY, Choice.ANKILOPE), "icons end at face")
    }

    @Test
    fun `no companion and a companion without a pack show nothing`() {
        assertNull(AnkiquestCompanion.bitmap(targetContext, AnkiquestPose.FACE, SIZE, Choice.NONE))
        assertNull(AnkiquestCompanion.bitmap(targetContext, AnkiquestPose.FACE, SIZE, Choice("unicorn")))

        // A view is hidden for no companion and left empty while no pack shows the chosen one.
        val view = ImageView(targetContext).apply { setImageBitmap(createBitmap(1, 1)) }
        AnkiquestCompanion.show(view, AnkiquestPose.FACE, SIZE, Choice("unicorn"))
        assertTrue(view.isVisible)
        assertNull(view.drawable)
        AnkiquestCompanion.show(view, AnkiquestPose.FACE, SIZE, Choice.NONE)
        assertFalse(view.isVisible)
    }

    @Test
    fun `the picker shows the installed companions with their packs' names`() {
        assertEquals("Bluma", AnkiquestCompanion.name(targetContext, Choice.COW))
        assertEquals(targetContext.getString(R.string.ankiquest_companion_none), AnkiquestCompanion.name(targetContext, Choice.NONE))
        assertEquals(listOf("aki", "ankilope", "cow"), AnkiquestCompanion.drawn)
    }

    @Test
    fun `an imported companion is chosen on this phone only`() =
        runBlocking {
            val alice = account("alice")
            assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
            val mine =
                AnkiquestPacks
                    .library(
                        targetContext,
                    ).import(AnkiquestTestPacks.pack("mine", AnkiquestTestPacks.colours(MINE_COLOUR)).inputStream())
            assertEquals(Offer.Free, AnkiquestCompanion.offers(targetContext, alice, Choice.ANKILOPE)[Choice(mine.id)])

            // No request is sent: the server knows only official companions.
            assertEquals(Choice(mine.id), AnkiquestCompanion.choose(alice, Choice(mine.id)))
            assertEquals(Choice(mine.id), AnkiquestCompanion.choice())

            // A refresh keeps it; once the pack is gone the server's choice shows again.
            assertTrue(AnkiquestCompanion.remember(alice, "ankilope"))
            assertEquals(Choice(mine.id), AnkiquestCompanion.choice())
            AnkiquestPacks.library(targetContext).removeImported(mine.id)
            assertEquals(Choice.ANKILOPE, AnkiquestCompanion.choice())
        }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a locked companion is drawn as a grey silhouette of its art`() {
        val art = requireNotNull(AnkiquestCompanion.bitmap(targetContext, AnkiquestPose.FACE, SIZE, Choice.ANKILOPE))
        val shadow = requireNotNull(AnkiquestCompanion.silhouette(targetContext, AnkiquestPose.FACE, SIZE, Choice.ANKILOPE))
        assertEquals(art.width to art.height, shadow.width to shadow.height)

        // Where the art is opaque the silhouette is the desktop's grey at its opacity; transparent stays so.
        for (x in 0 until art.width) {
            for (y in 0 until art.height) {
                val pixel = shadow[x, y]
                val alpha = Color.alpha(art[x, y]) * SILHOUETTE_ALPHA / OPAQUE
                assertTrue(Color.alpha(pixel) in alpha - ROUNDING..alpha + ROUNDING, "opacity at $x,$y")
                if (Color.alpha(art[x, y]) == OPAQUE) {
                    for (channel in listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) {
                        assertTrue(channel in SILHOUETTE_GREY - ROUNDING..SILHOUETTE_GREY + ROUNDING, "grey at $x,$y: $channel")
                    }
                }
            }
        }
    }

    @Test
    fun `previews show face, fire and sad without repeating a picture`() {
        val all = listOf(AnkiquestPose.FACE, AnkiquestPose.STREAK, AnkiquestPose.AT_RISK)
        assertEquals(all, AnkiquestCompanion.preview(targetContext, Choice.AKI))

        // Bluma's fire is her face picture, so it shows once.
        assertEquals(listOf(AnkiquestPose.FACE, AnkiquestPose.AT_RISK), AnkiquestCompanion.preview(targetContext, Choice.COW))
        assertEquals(emptyList(), AnkiquestCompanion.preview(targetContext, Choice("unicorn")))
    }

    @Test
    fun `a server value that is no companion id falls back to Aki`() {
        assertEquals("aki", AnkiquestCompanion.normalize("Not a companion!"))
        assertEquals("aki", AnkiquestCompanion.normalize(null))
        assertEquals("cow", AnkiquestCompanion.normalize("cow"))

        // A companion newer than this app is kept; its pack may come with the next download.
        assertEquals("unicorn", AnkiquestCompanion.normalize("unicorn"))
    }

    @Test
    fun `credits and licences come from each pack, escaped`() {
        val info = PackInfo("mine", "<b>Mine</b>", 1, "CC-BY-SA-4.0", "Me & you", "line one\nline two\n", emptyMap())
        assertEquals(
            "&lt;b&gt;Mine&lt;/b&gt; by Me &amp; you, CC-BY-SA-4.0<br>line one<br>line two<br>",
            AnkiquestPackCredits.lines(info) { name, credit, licence -> "$name by $credit, $licence" },
        )
        assertTrue("Companion art: Bluma by AnkiQuest, AGPL-3.0-or-later." in AnkiquestPackCredits.html(targetContext))
    }

    /** The colour of the middle pixel of [choice]'s picture for [pose]. */
    private fun colour(
        pose: AnkiquestPose,
        choice: Choice,
    ): Int {
        val bitmap = requireNotNull(AnkiquestCompanion.bitmap(targetContext, pose, SIZE, choice)) { "$choice $pose" }
        return bitmap[bitmap.width / 2, bitmap.height / 2]
    }

    private companion object {
        const val SIZE = AnkiquestTestPacks.EDGE
        const val OPAQUE = 255
        val AKI_COLOUR = 0xFFFF8000.toInt()
        val AKI_PLACE = 0xFFFFD700.toInt()
        val AKI_WINNER = 0xFFFF0000.toInt()
        val ANKILOPE_COLOUR = 0xFFA0522D.toInt()
        val ANKILOPE_HAPPY = 0xFF00FF00.toInt()
        val COW_COLOUR = 0xFF8000FF.toInt()
        val MINE_COLOUR = 0xFF0000FF.toInt()

        /** The desktop's silhouette: grey 128 at an opacity of 110. */
        const val SILHOUETTE_GREY = 128
        const val SILHOUETTE_ALPHA = 110
        const val ROUNDING = 2
    }
}
