// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.ichi2.anki.common.preferences.sharedPrefs
import timber.log.Timber

/**
 * The old app's side of [AnkiquestMove]: answers `call(METHOD_EXPORT)` with the settings payload,
 * only for the new app signed with the same key. The manifest declares it in the AnkiQuest
 * build only, exported and guarded by the signature permission [AnkiquestMove.PERMISSION];
 * [AnkiquestMove.verdict] checks the caller again in code.
 */
internal class AnkiquestMoveProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != AnkiquestMove.METHOD_EXPORT) {
            return null
        }
        val context = requireNotNull(context)

        // Only the verdict is logged; the payload never is.
        val verdict = AnkiquestMove.verdict(context, callingPackage)
        if (verdict != AnkiquestMove.Verdict.ALLOWED) {
            Timber.w("ankiquest move refused: %s", verdict)
            throw SecurityException("ankiquest move refused: $verdict")
        }
        val payload = AnkiquestMovePayload.encode(context.sharedPrefs().all, AnkiquestMovePayload.Exclusions.of(context))
        Timber.i("ankiquest move: settings handed to the new app")
        return Bundle().apply { putString(AnkiquestMove.KEY_PAYLOAD, payload) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
