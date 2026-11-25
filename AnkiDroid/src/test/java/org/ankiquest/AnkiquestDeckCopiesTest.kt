// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32])
class AnkiquestDeckCopiesTest : RobolectricTest() {
    @Test
    fun `selected member can list download and dismiss a shared deck copy`() =
        runBlocking {
            val packageBytes = byteArrayOf(80, 75, 3, 4) + "sample".toByteArray()
            val server =
                AnkiquestTestServer { request ->
                    when {
                        !request.path.startsWith("/api/deck-copies/hill") -> null
                        request.method == "DELETE" -> TestResponse(204)
                        request.path.endsWith("/7") -> TestResponse(bytes = packageBytes)
                        else ->
                            TestResponse.json(
                                """{"offers":[{"id":7,"sender":"cerro","deck":"Spanish","bytes":10}],"friends":[],"max_bytes":26214400}""",
                            )
                    }
                }
            try {
                val account = HomeAccount(server.url, "hill", "hill-secret")
                val inbox = AnkiquestDeckCopies.inbox(account)
                assertEquals("Spanish", inbox.getJSONArray("offers").getJSONObject(0).getString("deck"))
                val file = AnkiquestDeckCopies.download(account, 7, targetContext.cacheDir)
                try {
                    assertTrue(file.readBytes().contentEquals(packageBytes))
                } finally {
                    file.delete()
                }
                AnkiquestDeckCopies.dismiss(account, 7)
                assertEquals(
                    listOf(
                        "GET Bearer hill-secret /api/deck-copies/hill",
                        "GET Bearer hill-secret /api/deck-copies/hill/7",
                        "DELETE Bearer hill-secret /api/deck-copies/hill/7",
                    ),
                    server.requests.map { "${it.method} ${it.authorization} ${it.path}" },
                )
            } finally {
                server.close()
            }
        }
}
