// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.Context

/**
 * Which provider the API talks to.
 *
 * Transitions, all in [ProviderSelection]:
 * - no candidate: [NotInstalled]
 * - one candidate and no valid choice: [Only]
 * - several candidates and no valid choice: [NeedsChoice], until the user picks one ([ProviderSelection.choose])
 * - a valid stored choice: [Chosen]. It is dropped, and the state computed again, when its app is
 *   uninstalled, its certificate changes, or another package holds its authority
 */
internal sealed interface Resolution {
    /** The only installed candidate; used once the client app holds its permission */
    data class Only(
        val provider: FlashCardsProvider,
    ) : Resolution

    /** The provider the user chose, still installed with the pinned certificate and authority */
    data class Chosen(
        val provider: FlashCardsProvider,
    ) : Resolution

    /** Several candidates and no valid choice: the user has to choose one */
    data class NeedsChoice(
        val providers: List<FlashCardsProvider>,
    ) : Resolution

    /** No candidate is installed */
    data object NotInstalled : Resolution
}

/**
 * Picks the provider the API talks to from the [candidates] [ProviderDiscovery] finds.
 * With one candidate it is used; with several the user chooses, and the choice is pinned to the
 * app's package, authority and SHA-256 signing certificate.
 */
internal class ProviderSelection(
    private val context: Context,
    private val candidates: () -> List<FlashCardsProvider> = { ProviderDiscovery.find(context) },
    private val store: ChoiceStore = ChoiceStore(context),
) {
    /** The installed candidates, see [ProviderDiscovery.find] */
    fun installed(): List<FlashCardsProvider> = candidates()

    fun resolve(): Resolution {
        val installed = installed()

        // The user's choice holds while its app keeps its authority and certificate
        val chosen = chosen(installed)
        if (chosen != null) {
            return Resolution.Chosen(chosen)
        }

        // Otherwise the user chooses only between several candidates
        return when (installed.size) {
            0 -> Resolution.NotInstalled
            1 -> Resolution.Only(installed.single())
            else -> Resolution.NeedsChoice(installed)
        }
    }

    /** Opens the chooser if the user has to choose, once per process, see [ChooserLauncher] */
    fun offerChoice(from: Context) {
        if (resolve() !is Resolution.NeedsChoice) {
            return
        }
        ChooserLauncher.PROCESS.offer(from)
    }

    /** The provider the API talks to, or null if none is installed or the user has yet to choose */
    fun inUse(): FlashCardsProvider? =
        when (val resolution = resolve()) {
            is Resolution.Only -> resolution.provider
            is Resolution.Chosen -> resolution.provider
            is Resolution.NeedsChoice, Resolution.NotInstalled -> null
        }

    /** Stores the user's choice of [provider], pinned to its current certificate */
    fun choose(provider: FlashCardsProvider) {
        val certificate = AnkiDroidProvider.signer(context, provider.packageName) ?: return
        store.save(Choice(provider.packageName, provider.authority, certificate))
    }

    /** The stored choice if still valid; an invalid choice is dropped, so the user is asked again */
    private fun chosen(installed: List<FlashCardsProvider>): FlashCardsProvider? {
        val choice = store.load() ?: return null

        val provider = installed.firstOrNull { it.packageName == choice.packageName }
        val valid =
            provider != null &&
                provider.authority == choice.authority &&
                AnkiDroidProvider.signer(context, provider.packageName) == choice.certificate
        if (!valid) {
            store.clear()
            return null
        }
        return provider
    }
}
