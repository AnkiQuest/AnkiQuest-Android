// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.ioDispatcher
import com.ichi2.testutils.EmptyApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class, sdk = [33])
@Category(EmptyApplicationCategory::class)
class AnkiquestRwkvImportTest : RobolectricTest(loadBackend = false) {
    @Test
    fun `import hashes the picked files on the io dispatcher`() =
        runTest {
            val saved = ioDispatcher
            val dispatched = AtomicBoolean()
            ioDispatcher =
                object : CoroutineDispatcher() {
                    // Runs the block inline, so the test only records that import dispatched.
                    override fun dispatch(
                        context: kotlin.coroutines.CoroutineContext,
                        block: Runnable,
                    ) {
                        dispatched.set(true)
                        block.run()
                    }
                }
            try {
                val results = AnkiquestRwkv.import(targetContext, listOf { ByteArray(0).inputStream() })
                assertTrue(dispatched.get(), "import must dispatch its file hashing onto ioDispatcher, like download")
                assertEquals(RwkvImportError.UNKNOWN_FILE, (results.single() as RwkvImport.Rejected).error)
            } finally {
                ioDispatcher = saved
            }
        }
}
