// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertTrue

/**
 * Android merges the library's manifest into every client app. Each listed provider needs
 * its permission and a `<queries>` entry there, or clients cannot see or use it.
 */
internal class KnownProvidersManifestTest {
    // Unit tests run in the module directory; the build type's manifest is merged over main's
    private val manifests =
        listOf("main", BuildConfig.BUILD_TYPE)
            .map { File("src/$it/AndroidManifest.xml") }
            .filter { it.exists() }

    @Test
    fun everyProviderIsDeclared() {
        val permissions = values("uses-permission", "android:name")
        val packages = values("package", "android:name")
        val authorities = values("provider", "android:authorities")

        for (provider in KnownProviders.ALL) {
            assertTrue(provider.permission in permissions, "no <uses-permission> for $provider")
            assertTrue(provider.packageName in packages, "no <queries><package> for $provider")
            assertTrue(provider.authority in authorities, "no <queries><provider> for $provider")
        }
    }

    @Test
    fun chooserIsDeclared() {
        val activities = values("activity", "android:name")

        assertTrue(ProviderChooserActivity::class.java.name in activities)
    }

    /** The [attribute] of every [tag] in the manifests */
    private fun values(
        tag: String,
        attribute: String,
    ): Set<String> {
        val factory = DocumentBuilderFactory.newInstance()
        return manifests
            .flatMap { file ->
                val elements = factory.newDocumentBuilder().parse(file).getElementsByTagName(tag)
                (0 until elements.length).map { (elements.item(it) as Element).getAttribute(attribute) }
            }.toSet()
    }
}
