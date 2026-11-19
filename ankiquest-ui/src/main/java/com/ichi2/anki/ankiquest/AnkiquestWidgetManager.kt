// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Context

/**
 * A copy of `com.ichi2.widget.getAppWidgetManager` from the upstream `:widgets` module, which
 * this module does not depend on.
 *
 * @return An [AppWidgetManager] for [context], or `null`
 * @see AppWidgetManager.getInstance
 */
@SuppressLint("DirectAppWidgetManagerInstanceUsage")
internal fun getAppWidgetManager(context: Context): AppWidgetManager? {
    // The call returns null on a Supernote A5X, but as the underlying platform call is in Java,
    // the result is assumed to be non-null in Kotlin
    return AppWidgetManager.getInstance(context)
}
