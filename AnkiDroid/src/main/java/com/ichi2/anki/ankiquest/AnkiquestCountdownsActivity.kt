// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.R
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdown
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Countdowns
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Days
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.Pace
import com.ichi2.anki.ankiquest.AnkiquestCountdowns.State
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The wording of a countdown, as `wording` in the shared fixture. */
internal object AnkiquestCountdownTexts {
    fun headline(
        context: Context,
        event: Countdown,
        days: Days,
    ): String =
        when (days.state) {
            State.UPCOMING ->
                context.resources.getQuantityString(
                    R.plurals.ankiquest_countdown_upcoming,
                    days.days.toInt(),
                    event.name,
                    days.days.toInt(),
                )
            State.TOMORROW -> context.getString(R.string.ankiquest_countdown_tomorrow, event.name)
            State.TODAY -> context.getString(R.string.ankiquest_today, event.name)
            State.PAST -> context.getString(R.string.ankiquest_countdown_past, event.name)
        }

    /** The offer to add [count] countdowns made while signed out to [user]'s account. */
    fun importQuestion(
        context: Context,
        count: Int,
        user: String,
    ): String =
        if (count == 1) {
            context.getString(R.string.ankiquest_countdowns_import_one, user)
        } else {
            context.getString(R.string.ankiquest_countdowns_import_other, count, user)
        }

    /** What is said about [count] countdowns that did not fit and stay on this phone. */
    fun left(
        context: Context,
        count: Int,
    ): String = context.resources.getQuantityString(R.plurals.ankiquest_countdowns_left, count, count)

    /** The pace line, or null. */
    fun pace(
        context: Context,
        pace: Pace,
        deck: String?,
    ): String? {
        val label = deck?.let { AnkiquestDeckNames.clean(it).ifEmpty { it } } ?: return null
        return when (pace) {
            is Pace.PerDay ->
                context.resources.getQuantityString(R.plurals.ankiquest_countdown_pace, pace.cards.toInt(), pace.cards.toInt(), label)
            Pace.Done -> context.getString(R.string.ankiquest_countdown_done, label)
            Pace.None -> null
        }
    }
}

/**
 * The countdown list: add, edit and delete events and exams. Every change is saved at once and
 * synced with the player's other devices through settings sync. Only the player sees the list.
 */
class AnkiquestCountdownsActivity : AnkiActivity(R.layout.activity_ankiquest_countdowns) {
    private lateinit var list: LinearLayout
    private var deckNames: List<String> = emptyList()

    /** The collection's day start hour; Anki's default until it is read. */
    private var rollover = DEFAULT_ROLLOVER

    /** What a row or form was made from: one account and one version of its list. */
    private data class Snapshot(
        val account: HomeAccount?,
        val countdowns: Countdowns,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)

        // Header and insets, as on the other AnkiQuest pages
        findViewById<TextView>(R.id.aq_countdowns_title).let { ViewCompat.setAccessibilityHeading(it, true) }
        findViewById<View>(R.id.aq_countdowns_back).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val root = findViewById<View>(R.id.aq_countdowns_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        list = findViewById(R.id.aq_countdowns_list)
        findViewById<MaterialButton>(R.id.aq_countdowns_add).setOnClickListener { add() }

        // Deck names for the form and the day start; the list works without them.
        lifecycleScope.launch {
            try {
                val (names, hour) =
                    CollectionManager.withCol {
                        decks.allNamesAndIds(includeFiltered = false).map { it.name } to AnkiquestDecks.rollover(this)
                    }
                deckNames = names
                rollover = hour
                render()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "AnkiQuest countdown decks could not be read")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) {
            render()
        }
    }

    private fun today(): LocalDate =
        AnkiquestCountdowns.ankiToday(
            LocalDateTime.ofInstant(Instant.ofEpochMilli(TimeManager.time.intTimeMS()), ZoneId.systemDefault()),
            rollover,
        )

    private fun snapshot(): Snapshot {
        val prefs = AnkiDroidApp.sharedPrefs()
        return Snapshot(AnkiquestHomeData.account(prefs), AnkiquestCountdowns.read(prefs))
    }

    /** A sync or another account changed the list since [before]; say so and show the current one. */
    private fun changed(before: Snapshot) {
        val message =
            if (AnkiquestHomeData.account()?.scope != before.account?.scope) {
                R.string.ankiquest_account_changed
            } else {
                R.string.ankiquest_countdowns_changed
            }
        showThemedToast(this, message, shortLength = false)
        render()
    }

    /** Saves [events] unless the list or the account changed since [before]. */
    private fun save(
        before: Snapshot,
        events: List<Countdown>,
    ) {
        if (!AnkiquestSettingsSync.editCountdowns(AnkiDroidApp.sharedPrefs(), before.account, before.countdowns, Countdowns(events))) {
            changed(before)
            return
        }
        render()
    }

    /** Rows by date; each keeps the index of its event in the stored list. */
    private fun render() {
        list.removeAllViews()
        val before = snapshot()
        val events = before.countdowns.events
        text(getString(R.string.ankiquest_countdowns_private), small = true)
        if (events.isEmpty()) {
            text(getString(R.string.ankiquest_countdowns_empty))
        }
        val today = today()
        // The fixture's order: by date, on a tie in stored order, as a stable sort keeps it.
        for (index in events.indices.sortedBy { events[it].date }) {
            val event = events[index]
            val line = AnkiquestCountdownTexts.headline(this, event, AnkiquestCountdowns.days(today, event.date))
            val details = listOfNotNull(AnkiquestCountdowns.format(event.date), event.deck).joinToString(" · ")
            row(line, details) { edit(before, index) }
        }
        findViewById<MaterialButton>(R.id.aq_countdowns_add).isEnabled = events.size < AnkiquestCountdowns.MAX_EVENTS
    }

    private fun add() {
        val before = snapshot()
        if (before.countdowns.events.size >= AnkiquestCountdowns.MAX_EVENTS) {
            showThemedToast(this, R.string.ankiquest_countdowns_full, shortLength = false)
            return
        }
        form(R.string.ankiquest_countdown_add, null) { event -> save(before, before.countdowns.events + event) }
    }

    /** Opens the event at [index] of the list the row was made from, unless that list changed since. */
    private fun edit(
        before: Snapshot,
        index: Int,
    ) {
        if (snapshot() != before) {
            changed(before)
            return
        }
        val events = before.countdowns.events
        val event = events.getOrNull(index) ?: return
        form(R.string.ankiquest_countdown_edit, event, delete = { save(before, events.filterIndexed { i, _ -> i != index }) }) { changed ->
            save(before, events.mapIndexed { i, old -> if (i == index) changed else old })
        }
    }

    /** One event's form: name, date and an optional deck, in the fixture's order. */
    private fun form(
        title: Int,
        event: Countdown?,
        delete: (() -> Unit)? = null,
        done: (Countdown) -> Unit,
    ) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.setPadding(dp(24), dp(8), dp(24), 0)
        val name =
            EditText(this).apply {
                hint = getString(R.string.ankiquest_countdown_name)
                setText(event?.name.orEmpty())
                filters = arrayOf(AnkiquestTextLimit.filter(AnkiquestCountdowns.MAX_NAME_CHARS))
            }
        layout.addView(name)

        // The date opens a picker; a new countdown starts a week from today.
        var date = event?.date ?: today().plusDays(NEW_EVENT_DAYS)
        val dateButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        dateButton.text = AnkiquestCountdowns.format(date)
        dateButton.setOnClickListener {
            val picked =
                DatePickerDialog.OnDateSetListener { _, year, month, day ->
                    date = LocalDate.of(year, month + 1, day)
                    dateButton.text = AnkiquestCountdowns.format(date)
                }
            DatePickerDialog(this, picked, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        layout.addView(TextView(this).apply { text = getString(R.string.ankiquest_countdown_date) })
        layout.addView(dateButton)

        // The deck list keeps a linked deck the collection no longer has, so saving does not drop it.
        val choices = listOf<String?>(null) + (deckNames + listOfNotNull(event?.deck)).distinct()
        val deck = Spinner(this)
        deck.adapter =
            ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                choices.map { it ?: getString(R.string.ankiquest_countdown_no_deck) },
            )
        deck.setSelection(choices.indexOf(event?.deck).coerceAtLeast(0))
        layout.addView(TextView(this).apply { text = getString(R.string.ankiquest_countdown_deck) })
        layout.addView(deck)

        val builder =
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(layout)
                .setPositiveButton(R.string.ankiquest_countdown_save, null)
                .setNegativeButton(android.R.string.cancel, null)
        if (delete != null) {
            builder.setNeutralButton(R.string.ankiquest_countdown_delete) { _, _ -> delete() }
        }
        val dialog = builder.create()

        // Save refuses a blank name, so the list stays one the server stores.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = name.text.toString().trim()
                if (text.isEmpty()) {
                    name.error = getString(R.string.ankiquest_countdown_name_missing)
                    return@setOnClickListener
                }
                done(Countdown(text, date, choices[deck.selectedItemPosition]))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun row(
        line: String,
        details: String,
        click: () -> Unit,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { click() }
                contentDescription = "$line, $details"
            }
        row.addView(label(line, small = false))
        row.addView(label(details, small = true))
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun text(
        value: String,
        small: Boolean = false,
    ) = list.addView(label(value, small).apply { setPadding(0, dp(6), 0, dp(6)) })

    private fun label(
        value: String,
        small: Boolean,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = if (small) 14f else 16f
            setTextColor(getColor(if (small) R.color.aq_home_muted else R.color.aq_home_text))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val NEW_EVENT_DAYS = 7L
        private const val DEFAULT_ROLLOVER = 4

        fun intent(context: Context): Intent = Intent(context, AnkiquestCountdownsActivity::class.java)
    }
}
