// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.RobolectricTest
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Which library [AnkiquestPacks] hands out. The plain JVM tests of the API client cover the case
 * without an app: every request names the drawn companions.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestPacksTest : RobolectricTest() {
    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun reset() {
        AnkiquestPacks.use(null)
    }

    /** An application whose files live in a folder of its own and whose assets hold nothing. */
    private fun app(): Context {
        val app = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { assets.list(any()) } returns emptyArray()
        every { app.applicationContext } returns app
        every { app.filesDir } returns folder.newFolder()
        every { app.assets } returns assets
        return app
    }

    @Test
    fun `one application keeps its library`() {
        val app = app()

        assertSame(AnkiquestPacks.library(app), AnkiquestPacks.library(app))
    }

    @Test
    fun `a new application never reads the previous one's folders`() {
        // Regression: the first application's library, over its deleted files, served every later one.
        val first = AnkiquestPacks.library(app())

        assertNotSame(first, AnkiquestPacks.library(app()))
    }

    @Test
    fun `a library in use serves every application`() {
        val library = AnkiquestTestPacks.library(folder.newFolder())
        AnkiquestPacks.use(library)

        assertSame(library, AnkiquestPacks.library(app()))
        assertSame(library, AnkiquestPacks.libraryOrNull())
    }

    @Test
    fun `without a context the app's own library is read`() {
        assertSame(AnkiquestPacks.library(AnkiDroidApp.instance), AnkiquestPacks.libraryOrNull())
    }
}
