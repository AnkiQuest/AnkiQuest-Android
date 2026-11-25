// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import com.ichi2.anki.R
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals

class AnkiquestRwkvTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val twoHead = ByteArray(64) { it.toByte() }
    private val lock =
        listOf(
            RwkvLockEntry("sm92", RwkvSize.SMALL, RwkvHead.TWO_HEAD, "rwkv-small-two-head.zip", twoHead.size.toLong(), sha256(twoHead)),
        )
    private val store by lazy { RwkvModelStore(File(folder.root, "rwkv"), lock) }

    /** An engine that records what it was handed, and fails if asked to. */
    private class FakeEngine(
        private val refuse: Boolean = false,
    ) : RwkvEngine {
        val prepared = mutableListOf<RwkvModels>()
        override val load = RwkvLoad.NOT_LOADED

        override suspend fun prepare(models: RwkvModels): Long {
            if (refuse) {
                throw IllegalStateException("RWKV model file not found")
            }
            prepared.add(models)
            return 0
        }

        override suspend fun progress(): RwkvProgress = RwkvProgress.Unavailable
    }

    @Test
    fun `every state has its own summary`() {
        val states = listOf(RwkvState.Off, RwkvState.Ready, RwkvState.Stopping) + RwkvBlocker.entries.map { RwkvState.Refused(it) }
        val summaries = states.map(AnkiquestRwkv::summary)
        assertEquals(states.size, summaries.toSet().size)
        assertEquals(R.string.ankiquest_rwkv_backend_failed, AnkiquestRwkv.summary(RwkvState.Refused(RwkvBlocker.BACKEND_FAILED)))
    }

    @Test
    fun `starting a ready state hands the engine the verified models`() =
        runTest {
            store.import(twoHead.inputStream())
            val engine = FakeEngine()
            assertEquals(RwkvState.Ready, RwkvState.Ready.start(store, engine))
            assertEquals(lock[0], engine.prepared.single().twoHead.entry)
        }

    @Test
    fun `a backend that refuses the models refuses the state`() =
        runTest {
            store.import(twoHead.inputStream())
            assertEquals(RwkvState.Refused(RwkvBlocker.BACKEND_FAILED), RwkvState.Ready.start(store, FakeEngine(refuse = true)))
        }

    @Test
    fun `a model changed since the checks never reaches the engine`() =
        runTest {
            store.import(twoHead.inputStream())
            File(folder.root, "rwkv/${lock[0].sha256}.bin").writeBytes(ByteArray(twoHead.size))
            val engine = FakeEngine()
            assertEquals(RwkvState.Refused(RwkvBlocker.NO_MODEL), RwkvState.Ready.start(store, engine))
            assertEquals(emptyList(), engine.prepared)
        }

    @Test
    fun `the off states never start the engine`() =
        runTest {
            store.import(twoHead.inputStream())
            val engine = FakeEngine()
            for (state in listOf(RwkvState.Off, RwkvState.Stopping, RwkvState.Refused(RwkvBlocker.NO_MODEL))) {
                assertEquals(state, state.start(store, engine))
            }
            assertEquals(emptyList(), engine.prepared)
        }

    private fun sha256(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
}
