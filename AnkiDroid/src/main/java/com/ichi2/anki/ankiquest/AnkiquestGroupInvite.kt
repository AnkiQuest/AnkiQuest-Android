// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R

/**
 * Sharing a group's invite link through the system share sheet, from Friends and from another
 * player's profile. The server has no invitation it delivers itself, so the link is all there is.
 */
internal object AnkiquestGroupInvite {
    /** Opens the share sheet for [group]'s invite link; a group without one shares nothing. */
    fun share(
        context: Context,
        account: HomeAccount,
        group: AnkiquestSocial.Group,
    ) {
        val code = group.invite ?: return
        val link = AnkiquestSocial.inviteLink(account, code)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.aq_friends_invite_share_text, group.name, link))
        context.startActivity(Intent.createChooser(send, context.getString(R.string.aq_friends_share_link)))
    }

    /** Shares the only group in [groups] at once, or asks which one first. */
    fun pick(
        context: Context,
        account: HomeAccount,
        groups: List<AnkiquestSocial.Group>,
    ) {
        val only = groups.singleOrNull()
        if (only != null) {
            share(context, account, only)
            return
        }
        if (groups.isEmpty()) return

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.aq_profile_invite_pick)
            .setItems(groups.map { it.name }.toTypedArray()) { _, which -> share(context, account, groups[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
