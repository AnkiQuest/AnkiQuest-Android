// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import com.ichi2.anki.FlashCardsContract

/**
 * An app offering the [FlashCardsContract] content provider: AnkiDroid, AnkiQuest, or another
 * app declaring [FlashCardsContract.ACTION_FLASHCARDS_PROVIDER].
 *
 * [AddContentApi.getProviders] returns the installed ones. With one installed the API uses it;
 * with several the user picks one with [AddContentApi.chooseProvider].
 *
 * @property packageName package of the app offering the provider
 * @property authority authority of the provider, used for every content URI
 * @property permission permission needed to read and write the provider's collection
 */
public class FlashCardsProvider internal constructor(
    public val packageName: String,
    public val authority: String,
    public val permission: String,
    /**
     * SHA-256 digests of the certificates the app may be signed with, as upper-case hex.
     * Empty for an app not in [KnownProviders]: the user's choice pins its certificate.
     */
    internal val certificates: Set<String>,
) {
    override fun equals(other: Any?): Boolean = other is FlashCardsProvider && other.packageName == packageName

    override fun hashCode(): Int = packageName.hashCode()

    override fun toString(): String = "FlashCardsProvider($packageName, $authority)"
}
