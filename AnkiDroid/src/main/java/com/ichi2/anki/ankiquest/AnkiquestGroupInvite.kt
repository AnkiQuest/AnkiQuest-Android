// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.R

/**
 * Inviting someone to a group, from Friends and from another player's profile: sharing a group's
 * invite link through the system share sheet, and choosing which group when there are several.
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

    /**
     * Hands the only group in [groups] to [chosen] at once, or asks which one first and returns
     * that dialog, so the caller can close it.
     */
    fun pick(
        context: Context,
        groups: List<AnkiquestSocial.Group>,
        chosen: (AnkiquestSocial.Group) -> Unit,
    ): AlertDialog? {
        val only = groups.singleOrNull()
        if (only != null) {
            chosen(only)
            return null
        }
        if (groups.isEmpty()) {
            return null
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.aq_profile_invite_pick)
            .setItems(groups.map { it.name }.toTypedArray()) { _, which -> chosen(groups[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
