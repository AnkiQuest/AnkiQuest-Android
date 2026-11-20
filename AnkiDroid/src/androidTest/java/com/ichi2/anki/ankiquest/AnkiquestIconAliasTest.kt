// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Switches the launcher alias on a device with the quest build's manifest, so PackageManager itself
 * decides which entry the launcher shows. Other builds have no aliases and skip it; the emulator
 * workflow runs it on a quest build with [REQUIRED], so a missing alias fails instead of skipping.
 */
@RunWith(AndroidJUnit4::class)
class AnkiquestIconAliasTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Before
    fun questBuild() {
        val required = InstrumentationRegistry.getArguments().getString(REQUIRED) == "true"
        if (required) {
            assertTrue(AnkiquestIcon.available(context), "the quest build must have the launcher aliases")
        } else {
            assumeTrue("only the quest build has the launcher aliases", AnkiquestIcon.available(context))
        }
    }

    @After
    fun restore() {
        AnkiquestIcon.show(context, AnkiquestIcon.Mood.DEFAULT, prefs, AKI)
        prefs.edit(commit = true) { clear() }
    }

    @Test
    fun companionLauncherEntry() {
        // Companion, the alias the launcher should list, and the icon it should draw.
        val cases =
            listOf(
                Triple("ankilope", "AnkiquestIconAnkilopeDefault", "ankilope_launcher"),
                Triple("cow", "AnkiquestIconBlumaDefault", "cow_launcher"),
                Triple("none", "AnkiquestIconPlain", plainIcon()),
                Triple(AKI, "AnkiquestIconDefault", "ankiquest_launcher"),
            )
        for ((companion, alias, icon) in cases) {
            AnkiquestIcon.show(context, AnkiquestIcon.Mood.DEFAULT, prefs, companion)

            val entries = launcherEntries()
            assertEquals(listOf("$ALIAS_PACKAGE.$alias"), entries.map { it.first }, "launcher entries for $companion")
            assertEquals(mipmap(icon), entries.single().second, "icon for $companion")
        }
    }

    /** The launcher entries PackageManager lists for this app, with their icon resources. */
    private fun launcherEntries(): List<Pair<String, Int>> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(launcher, 0).map {
            it.activityInfo.name to it.activityInfo.iconResource
        }
    }

    // Android selects roundIcon during package parsing on devices that enable this framework flag.
    // The flag is not part of the public R class, so the device test looks it up by name.
    @SuppressLint("DiscouragedApi")
    private fun plainIcon(): String {
        val system = Resources.getSystem()
        val flag = system.getIdentifier("config_useRoundIcon", "bool", "android")
        return if (flag != 0 && system.getBoolean(flag)) "ic_launcher_round" else "ic_launcher"
    }

    // The quest icons are not in the other builds' R, which compile this test too.
    @SuppressLint("DiscouragedApi")
    private fun mipmap(name: String): Int {
        val resource = context.resources.getIdentifier(name, "mipmap", context.packageName)
        assertTrue(resource != 0, "the quest build must contain mipmap/$name")
        return resource
    }

    private companion object {
        const val AKI = "aki"
        const val ALIAS_PACKAGE = "com.ichi2.anki.ankiquest"
        const val PREFS = "ankiquest_icon_alias_test"

        /** Instrumentation argument the quest emulator run sets: fail, not skip, without the aliases. */
        const val REQUIRED = "ankiquestQuestBuild"
    }
}
