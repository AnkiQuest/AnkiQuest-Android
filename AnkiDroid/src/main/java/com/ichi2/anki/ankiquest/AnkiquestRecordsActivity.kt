// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.os.Bundle
import com.ichi2.anki.AnkiActivity

/** Forwards old explicit links to records within Me; released apps may still hold such links. */
class AnkiquestRecordsActivity : AnkiActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(AnkiquestMeSections.intent(this, AnkiquestMeSections.Destination.RECORDS))
        finish()
    }
}
