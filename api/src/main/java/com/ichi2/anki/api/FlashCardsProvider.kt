// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import com.ichi2.anki.FlashCardsContract

/**
 * An installed app offering the [FlashCardsContract] content provider:
 * AnkiDroid, one of its parallel builds, or a fork.
 *
 * Get the installed providers from [AddContentApi.getProviders] and pass one to [AddContentApi].
 *
 * @property packageName package of the app offering the provider
 * @property authority authority of the provider, used for every content URI
 */
public class FlashCardsProvider(
    public val packageName: String,
    public val authority: String,
) {
    /**
     * The permission needed to read and write this provider's collection.
     * Declare it in the client's manifest and request it at runtime.
     */
    public val permission: String
        get() = FlashCardsContract.getPermission(packageName)

    override fun toString(): String = "FlashCardsProvider($packageName, $authority)"
}
