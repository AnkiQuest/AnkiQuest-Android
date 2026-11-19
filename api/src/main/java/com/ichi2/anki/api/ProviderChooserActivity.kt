// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import com.ichi2.anki.api.ProviderChooser.State

/**
 * Lets the user pick which installed, verified [FlashCardsProvider] this app talks to.
 * Picking one asks for its permission through Android's permission dialog.
 *
 * Declared in the library's manifest, so client apps get it without changes.
 */
internal class ProviderChooserActivity : Activity() {
    private lateinit var selection: ProviderSelection
    private lateinit var chooser: ProviderChooser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selection = ProviderSelection(this)

        // Nothing to choose from: close
        val providers = selection.installed()
        if (providers.isEmpty()) {
            finish()
            return
        }

        chooser = ProviderChooser(providers, selection.inUse()) { permissionState(it) }
        showList()
    }

    /** Shows the providers by app name and icon, the one in use checked */
    private fun showList() {
        val selected = (chooser.state as? State.Choosing)?.selected
        val adapter = ProviderAdapter(this, chooser.providers, selected)

        AlertDialog
            .Builder(this)
            .setTitle(R.string.ankidroid_api_provider_chooser_title)
            .setAdapter(adapter) { _, which -> render(chooser.pick(chooser.providers[which])) }
            .setOnCancelListener { render(chooser.cancel()) }
            .show()
    }

    /** Acts on the chooser's new state */
    private fun render(state: State) {
        when (state) {
            is State.Choosing -> showList()
            is State.AwaitingPermission -> requestPermission(state.provider)
            is State.Chosen -> {
                selection.choose(state.provider)
                setResult(RESULT_OK)
                finish()
            }
            State.Cancelled -> finish()
        }
    }

    // Before Android 6 permissions are granted at install time, so a missing one stays denied
    private fun requestPermission(provider: FlashCardsProvider) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            render(chooser.onPermission(PermissionState.DENIED))
            return
        }
        requestPermissions(arrayOf(provider.permission), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) {
            return
        }

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        render(chooser.onPermission(if (granted) PermissionState.GRANTED else PermissionState.DENIED))
    }

    private fun permissionState(permission: String): PermissionState {
        val result = checkPermission(permission, Process.myPid(), Process.myUid())
        return if (result == PackageManager.PERMISSION_GRANTED) PermissionState.GRANTED else PermissionState.DENIED
    }

    /** One row per provider: the app's name and icon, checked if in use */
    private class ProviderAdapter(
        context: Context,
        providers: List<FlashCardsProvider>,
        private val selected: FlashCardsProvider?,
    ) : ArrayAdapter<FlashCardsProvider>(context, android.R.layout.select_dialog_singlechoice, providers) {
        private val manager = context.packageManager
        private val iconSize = (ICON_SIZE_DP * context.resources.displayMetrics.density).toInt()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View {
            val view = super.getView(position, convertView, parent) as CheckedTextView
            val provider = getItem(position) ?: return view

            // A provider uninstalled while the list is open keeps its package name
            val app =
                try {
                    manager.getApplicationInfo(provider.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            view.text = app?.loadLabel(manager) ?: provider.packageName
            val icon = app?.loadIcon(manager)?.apply { setBounds(0, 0, iconSize, iconSize) }
            view.setCompoundDrawables(icon, null, null, null)
            view.compoundDrawablePadding = iconSize / 2
            view.isChecked = provider == selected
            return view
        }
    }

    companion object {
        private const val PERMISSION_REQUEST = 1
        private const val ICON_SIZE_DP = 32

        /** Opens the chooser from [context], in a new task if it is not an activity */
        fun start(context: Context) {
            val intent = Intent(context, ProviderChooserActivity::class.java)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * Opens the chooser by itself at most once, so a user who closed it is not asked again
 * until the app restarts or calls [AddContentApi.chooseProvider].
 */
internal class ChooserLauncher {
    private enum class State { NOT_OPENED, OPENED }

    private var state = State.NOT_OPENED

    @Synchronized
    fun offer(context: Context) {
        if (state == State.OPENED) {
            return
        }

        state = State.OPENED
        ProviderChooserActivity.start(context)
    }

    companion object {
        /** The launcher for this process */
        val PROCESS = ChooserLauncher()
    }
}
