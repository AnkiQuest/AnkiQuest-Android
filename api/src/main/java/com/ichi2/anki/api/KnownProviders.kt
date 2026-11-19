// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import com.ichi2.anki.FlashCardsContract

/**
 * Apps AnkiDroid's maintainers list as offering the [FlashCardsContract] provider.
 * The API only ever talks to these, and only after checking their signing certificate.
 *
 * To list an app, add it here and declare its permission and a `<queries>` entry in the
 * library's AndroidManifest.xml; KnownProvidersManifestTest checks both.
 */
internal object KnownProviders {
    /** AnkiDroid, the default */
    val ANKIDROID =
        FlashCardsProvider(
            packageName = BuildConfig.ANKI_PACKAGE_NAME,
            authority = FlashCardsContract.AUTHORITY,
            permission = FlashCardsContract.READ_WRITE_PERMISSION,
            certificates = AnkiDroidProvider.RELEASE_CERTIFICATES,
        )

    /** AnkiQuest, a fork of AnkiDroid: https://github.com/AnkiQuest/AnkiQuest-Android */
    val ANKIQUEST =
        FlashCardsProvider(
            packageName = "org.ankiquest.anki",
            authority = "org.ankiquest.anki.flashcards",
            permission = "org.ankiquest.anki.permission.READ_WRITE_DATABASE",
            // TODO(hill): SHA-256 digest of AnkiQuest's release signing certificate, as upper-case hex
            //  (`apksigner verify --print-certs`). Until it is filled in, AnkiQuest is never trusted,
            //  except by debuggable clients like any other build.
            certificates = emptySet(),
        )

    /** AnkiDroid first */
    val ALL = listOf(ANKIDROID, ANKIQUEST)
}
