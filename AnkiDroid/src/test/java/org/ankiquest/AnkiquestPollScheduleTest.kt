// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ichi2.testutils.EmptyApplication
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The periodic poll survives the package move to org.ankiquest. */
@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
class AnkiquestPollScheduleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var manager: WorkManager

    @Before
    fun setUp() {
        val configuration =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        manager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `a poll stored under the old class name is replaced`() {
        // Stands in for the poll an install from before the move stored: tagged with the old name.
        val legacy =
            PeriodicWorkRequestBuilder<StoredWorker>(15, TimeUnit.MINUTES)
                .addTag(AnkiquestPoll.LEGACY_POLL_WORKER)
                .build()
        manager.enqueueUniquePeriodicWork(AnkiquestPoll.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, legacy)

        AnkiquestPoll.schedule(context)

        val active = polls().filterNot { it.state.isFinished }
        assertEquals(1, active.size, "$active")
        assertTrue(AnkiquestPollWorker::class.java.name in active.single().tags)
        assertFalse(AnkiquestPoll.LEGACY_POLL_WORKER in active.single().tags)
    }

    @Test
    fun `a current poll is kept`() {
        AnkiquestPoll.schedule(context)
        val first = polls().single()

        AnkiquestPoll.schedule(context)

        assertEquals(listOf(first.id), polls().map(WorkInfo::id))
        assertEquals(WorkInfo.State.ENQUEUED, polls().single().state)
    }

    private fun polls(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(AnkiquestPoll.PERIODIC_NAME).get()

    /** Never runs: the tests only look at what is queued. */
    class StoredWorker(
        context: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = Result.success()
    }
}
