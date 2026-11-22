// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.FragmentActivity
import org.junit.Test
import kotlin.test.assertSame

class AnkiquestHostTest {
    /** A host that answers nothing; only its identity matters here. */
    private object Unused : AnkiquestHost {
        override fun intent(
            context: Context,
            screen: AnkiquestScreen,
        ): Intent = Intent()

        override val actionIcon: Int = 0

        override fun inForeground(): Boolean = false

        override fun presentMomentsSoon() = Unit

        override fun resting(activity: Activity): Boolean = false

        override fun schedule(context: Context) = Unit

        override fun refreshNow(context: Context) = Unit

        override fun refreshAt(
            context: Context,
            dayEndsAt: Long,
        ) = Unit

        override suspend fun conqueredCard(at: Long): AnkiquestShare.Labels? = null

        override suspend fun conqueredDeck(at: Long): String? = null

        override fun chooseDeck(
            activity: FragmentActivity,
            account: HomeAccount,
            at: Long,
            chosen: (String?) -> Unit,
        ) = Unit
    }

    @Test
    fun `the views reach the host the app installed`() {
        AnkiquestHost.install(Unused)
        assertSame(Unused, AnkiquestHost.current)
    }
}
