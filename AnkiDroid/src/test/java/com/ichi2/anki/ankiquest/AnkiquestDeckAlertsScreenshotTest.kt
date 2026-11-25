// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.ichi2.anki.R
import com.ichi2.anki.ScreenshotTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.ichi2.anki.ankiquest.ui.R as AnkiquestR

/** The alert action and its Undo stay visible on narrow screens and at large text sizes. */
class AnkiquestDeckAlertsScreenshotTest : ScreenshotTest() {
    private enum class Stage { ALERT, UNDO }

    private enum class TextSize { NORMAL, LARGE }

    override fun applyDeviceConfig() {
        super.applyDeviceConfig()
        if (device == DeviceConfig.PHONE) RuntimeEnvironment.setQualifiers("w320dp-h740dp-port-mdpi")
    }

    @Before
    fun prepare() {
        val account = HomeAccount("https://quest.example.test", "hill", "visual-test-token")
        mockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestSubscriptions, HomeAccount.Companion)
        every { HomeAccount.current(any()) } returns account
        coEvery { AnkiquestSocial.overview(any()) } throws IOException("offline")
        val completion =
            AnkiquestFeed.item(
                JSONObject()
                    .put("id", 31)
                    .put("title", "Cerro finished Spanish")
                    .put("body", "Spanish is done for today.")
                    .put("created_at", 1793183400)
                    .put("sender", "cerro")
                    .put("kind", "completion"),
            )!!
        val page = AnkiquestFeed.parsePage(JSONObject(AnkiquestFeedSamples.PAGE))
        coEvery { AnkiquestFeed.page(account, any(), any(), any()) } returns
            page.copy(items = listOf(completion), attention = emptyList())
        val repository = mockk<SubscriptionRepository>()
        val settings = IncomingSubscriptions(true, emptySet(), listOf(IncomingSender("cerro", "Cerro")))
        every { AnkiquestSubscriptions.repository } returns repository
        coEvery { repository.load(account) } returns settings
        coEvery { repository.setSubscribed(account, "cerro", false) } returns settings.copy(unsubscribedSenders = setOf("cerro"))
    }

    @After
    fun cleanup() {
        unmockkObject(AnkiquestHomeData, AnkiquestSocial, AnkiquestFeed, AnkiquestSubscriptions, HomeAccount.Companion)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun alert() = capture(Stage.ALERT, TextSize.NORMAL)

    @Test
    fun alertLarge() = capture(Stage.ALERT, TextSize.LARGE)

    @Test
    fun undo() = capture(Stage.UNDO, TextSize.NORMAL)

    @Test
    fun undoLarge() = capture(Stage.UNDO, TextSize.LARGE)

    private fun capture(
        stage: Stage,
        size: TextSize,
    ) {
        RuntimeEnvironment.setFontScale(if (size == TextSize.LARGE) 2f else 1f)
        val screen =
            startActivityNormallyOpenCollectionWithIntent(
                AnkiquestFriendsActivity::class.java,
                AnkiquestFriendsSections.feedIntent(targetContext),
            )
        advanceRobolectricLooperUntil {
            !screen.findViewById<View>(R.id.aq_feed_loading).isVisible &&
                !screen.findViewById<View>(AnkiquestR.id.aq_friends_feed_loading).isVisible
        }
        val stop =
            screen.window.decorView.allViews.filterIsInstance<TextView>().first {
                it.text.toString() == screen.getString(AnkiquestR.string.aq_deck_alerts_stop, "cerro")
            }
        val scroll = screen.findViewById<NestedScrollView>(R.id.aq_feed_scroll)
        val bounds = Rect(0, 0, stop.width, stop.height)
        scroll.offsetDescendantRectToMyCoords(stop, bounds)
        scroll.scrollTo(0, bounds.top)
        advanceRobolectricLooper()
        assertTrue(stop.getGlobalVisibleRect(Rect()), "Stop deck alerts must be reachable in the feed")
        if (stage == Stage.UNDO) {
            stop.performClick()
            // Show the snackbar without advancing through its timeout.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val undo = assertNotNull(screen.findViewById<View>(com.google.android.material.R.id.snackbar_action))
            val undoBounds = Rect()
            assertTrue(undo.getGlobalVisibleRect(undoBounds))
            val navigation =
                screen.window.decorView.allViews
                    .filterIsInstance<BottomNavigationView>()
                    .first { it.isVisible }
            val navBounds = Rect()
            assertTrue(navigation.getGlobalVisibleRect(navBounds))
            assertTrue(undoBounds.bottom <= navBounds.top, "Undo must not cover the tabs")
        }
        captureScreen("deck_alerts_${stage.name.lowercase()}_${size.name.lowercase()}")
        assertFullLabel(stop)
    }

    private fun assertFullLabel(label: TextView) {
        val layout = assertNotNull(label.layout)
        val width = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        for (line in 0 until layout.lineCount) {
            assertEquals(0, layout.getEllipsisCount(line), "The sender name must not be ellipsized")
            // Wrapping can leave an invisible space past the line edge; measure the visible text.
            val visibleWidth = layout.getLineMax(line)
            assertTrue(
                visibleWidth <= width + 1,
                "The complete Stop label must fit: visible $visibleWidth, full ${layout.getLineWidth(line)}, available $width",
            )
        }
        assertEquals(label.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
    }
}
