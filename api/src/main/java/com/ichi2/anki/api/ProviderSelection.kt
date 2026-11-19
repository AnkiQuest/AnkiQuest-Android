// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.Context

/** Which provider the API talks to */
internal sealed interface Resolution {
    /** Talk to [provider]: AnkiDroid, or the provider the user chose */
    data class Ready(
        val provider: FlashCardsProvider,
    ) : Resolution

    /** AnkiDroid is not installed, but other [providers] are: the user has to choose one */
    data class NeedsChoice(
        val providers: List<FlashCardsProvider>,
    ) : Resolution

    /** No listed provider is installed and verified */
    data object NotInstalled : Resolution
}

/**
 * Picks the provider the API talks to, from the [known] providers that are installed and verified.
 * AnkiDroid, the first of [known], is used unless the user chose another provider.
 */
internal class ProviderSelection(
    private val context: Context,
    private val known: List<FlashCardsProvider> = KnownProviders.ALL,
    private val store: ChoiceStore = ChoiceStore(context),
) {
    private val default: FlashCardsProvider = known.first()

    /** The known providers that are installed and verified, AnkiDroid first */
    fun installed(): List<FlashCardsProvider> = known.filter { isVerified(it) }

    fun resolve(): Resolution {
        // The user's choice holds while its app is installed, verified and keeps its certificate
        val chosen = chosen()
        if (chosen != null) {
            return Resolution.Ready(chosen)
        }

        // Otherwise AnkiDroid, and a choice is only needed without it
        val installed = installed()
        if (default in installed) {
            return Resolution.Ready(default)
        }
        if (installed.isEmpty()) {
            return Resolution.NotInstalled
        }
        return Resolution.NeedsChoice(installed)
    }

    /** Opens the chooser if the user has to choose, once per process, see [ChooserLauncher] */
    fun offerChoice(from: Context) {
        if (resolve() !is Resolution.NeedsChoice) {
            return
        }
        ChooserLauncher.PROCESS.offer(from)
    }

    /** The provider the API talks to, or null if none is installed or the user has yet to choose */
    fun inUse(): FlashCardsProvider? = (resolve() as? Resolution.Ready)?.provider

    /** Stores the user's choice of [provider], pinned to its current certificate */
    fun choose(provider: FlashCardsProvider) {
        // AnkiDroid is the default, so choosing it needs nothing stored
        if (provider == default) {
            store.clear()
            return
        }

        val certificate = AnkiDroidProvider.signer(context, provider.packageName) ?: return
        store.save(Choice(provider.packageName, certificate))
    }

    /** The stored choice if still valid; an invalid choice is dropped */
    private fun chosen(): FlashCardsProvider? {
        val choice = store.load() ?: return null

        val provider = known.firstOrNull { it.packageName == choice.packageName }
        val valid =
            provider != null &&
                isVerified(provider) &&
                AnkiDroidProvider.signer(context, provider.packageName) == choice.certificate
        if (!valid) {
            store.clear()
            return null
        }
        return provider
    }

    /** Whether [provider] is installed as listed, see [AnkiDroidProvider] */
    private fun isVerified(provider: FlashCardsProvider): Boolean = AnkiDroidProvider.resolve(context, listed = provider) != null
}
