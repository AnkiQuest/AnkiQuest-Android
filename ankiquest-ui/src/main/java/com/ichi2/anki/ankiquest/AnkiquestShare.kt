// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.service.chooser.ChooserAction
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.ankiquest.ui.R
import com.ichi2.anki.common.time.TimeManager
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Passing a win on: to friends through the server, or anywhere through Android's share sheet with the server's card. */
object AnkiquestShare {
    const val EXTRA_TITLE = "ankiquestShareTitle"
    const val EXTRA_BODY = "ankiquestShareBody"
    const val EXTRA_WIN = "ankiquestShareWin"
    const val EXTRA_NOTIFICATION = "ankiquestShareNotification"
    const val EXTRA_PLACE = "ankiquestSharePlace"
    const val EXTRA_SCOPE = "ankiquestShareScope"
    const val EXTRA_TOPIC = "ankiquestShareTopic"

    /** Set on the share sheet's own "AnkiQuest friends" entry, which sends the win without asking again. */
    const val EXTRA_DESTINATION = "ankiquestShareDestination"
    const val DESTINATION_FRIENDS = "friends"

    /** The app's name as share pictures, captions and the gallery folder show it. */
    const val BRAND = "AnkiQuest"

    /** Cards are 1080×1080 PNGs; anything far larger is not a card. */
    private const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
    private const val MAX_PICTURE_SIDE = 1080
    private const val IMAGE_SECONDS = 10L
    private const val CACHE_LIFETIME = DAY_MS

    /** The notification kind behind a Share button, for its picture's art. */
    private const val EXTRA_KIND = "ankiquestShareKind"
    private const val KIND_CELEBRATION = "celebration"
    private const val KIND_CHALLENGE_COMPLETE = "challenge_complete"
    private const val KIND_WEEKLY_RECAP = "reminder_weekly_recap"
    private val SHAREABLE = setOf(KIND_CELEBRATION, KIND_CHALLENGE_COMPLETE, KIND_WEEKLY_RECAP)

    private val imageClient =
        AnkiquestHttp.client
            .newBuilder()
            .callTimeout(IMAGE_SECONDS, TimeUnit.SECONDS)
            .build()

    /**
     * Public display content selected for the picture, with no credentials or hidden fields. A
     * [streak] makes it a streak picture: the number in front, on its heat's colours. [companion]
     * draws [art] as that companion instead of the chosen one, for a companion that just joined.
     */
    data class Card(
        val title: String,
        val body: String,
        @DrawableRes val art: Int = R.drawable.aki_celebrate,
        val details: List<String> = emptyList(),
        val streak: Int? = null,
        val companion: AnkiquestCompanion.Choice? = null,
    )

    /** [card] drawn on the phone; shares, previews and downloads use this one renderer. */
    fun render(
        context: Context,
        card: Card,
    ): Bitmap = AnkiquestPicture.render(AnkiquestLanguage.context(context), card)

    /**
     * The PNG to share: the server's [image] byte for byte when it arrives within the picture
     * deadline, otherwise [card] drawn on the phone. It blocks.
     */
    fun cardPng(
        context: Context,
        card: Card,
        image: String? = null,
    ): ByteArray = image?.let(::quickPng) ?: renderPng(context, card)

    /**
     * "Download picture": [card] drawn on the phone for the gallery. It makes no request, so saving
     * publishes nothing. Null when the picture cannot be prepared. Call away from the main thread.
     */
    fun download(
        context: Context,
        card: Card,
    ): Intent? {
        val picture = cache(context) { renderPng(context, card) } ?: return null
        return AnkiquestHost.current
            .intent(context, AnkiquestScreen.Gallery)
            .setDataAndType(picture, AnkiquestGallery.PNG)
            .putExtra(EXTRA_SCOPE, HomeAccount.current()?.scope)
    }

    /**
     * Aki's pose for the picture of a Share button's [share] intent. Buttons without a kind, such as
     * a climb to [place] or one posted by an older version, use what they carry.
     */
    @DrawableRes
    fun art(
        share: Intent,
        place: Int?,
    ): Int {
        val kind = share.getStringExtra(EXTRA_KIND) ?: if (place == null) KIND_CELEBRATION else KIND_WEEKLY_RECAP
        return AnkiquestArt.forNotice(kind, share.getStringExtra(EXTRA_TOPIC) ?: place?.let { "place:$it" })
    }

    /** Notification kinds that are a player's own win, and so worth sharing. */
    fun shareable(kind: String) = kind in SHAREABLE

    /**
     * The Share button for a win's notification: a [win] the server keeps, or for older servers one of
     * the player's own [notification]s or a [place] they climbed to. Null when the win belongs to an
     * account that is no longer signed in. The button carries the account's [scope], so a tap after
     * switching accounts shares nothing. [kind] is the notification's, for the picture's art.
     */
    fun action(
        context: Context,
        tag: Int,
        title: String,
        body: String,
        win: Long?,
        notification: Long?,
        place: Int?,
        scope: String?,
        topic: String? = null,
        kind: String? = null,
    ): NotificationCompat.Action? {
        val current = HomeAccount.current() ?: return null
        if (current.token.isEmpty() || (scope != null && current.scope != scope)) return null
        val intent =
            AnkiquestHost.current
                .intent(context, AnkiquestScreen.Share)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_SCOPE, current.scope)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        win?.let { intent.putExtra(EXTRA_WIN, it) }
        notification?.let { intent.putExtra(EXTRA_NOTIFICATION, it) }
        place?.let { intent.putExtra(EXTRA_PLACE, it) }
        topic?.let { intent.putExtra(EXTRA_TOPIC, it) }
        kind?.let { intent.putExtra(EXTRA_KIND, it) }
        return NotificationCompat.Action
            .Builder(
                R.drawable.ic_share_notify,
                AnkiquestLanguage.context(context).getString(R.string.ankiquest_share),
                PendingIntent.getActivity(context, tag, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            ).build()
    }

    /**
     * A win's picture and invitation text. A missing server image uses the local [card].
     * It downloads, so call it away from the main thread.
     */
    fun linkChooser(
        context: Context,
        link: AnkiquestWins.Link,
        friends: PendingIntent? = null,
        card: Card = Card(BRAND, link.text),
    ): Intent = cardChooser(context, link.text, link.image, friends, card)

    /**
     * Attach the server PNG when quickly available, otherwise draw [fallback] locally. Only a
     * failure to prepare the picture leaves a text-only share. Call away from the main thread.
     */
    fun cardChooser(
        context: Context,
        text: String,
        image: String?,
        friends: PendingIntent? = null,
        fallback: Card = Card(BRAND, text),
    ): Intent {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, text)
        val card = cache(context) { cardPng(context, fallback, image) }
        if (card == null) {
            send.setType("text/plain")
        } else {
            send
                .setType(AnkiquestGallery.PNG)
                .putExtra(Intent.EXTRA_STREAM, card)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(BRAND, card)
        }
        return chooser(context, send, friends)
    }

    /**
     * Writes the PNG from [png] to its own file in the share cache and exposes it, after removing
     * expired pictures. Null when it cannot be drawn, written or exposed.
     */
    private fun cache(
        context: Context,
        png: () -> ByteArray,
    ): Uri? =
        try {
            val bytes = png()
            val directory = File(context.cacheDir, "ankiquest-share").apply { mkdirs() }
            val expired = TimeManager.time.intTimeMS() - CACHE_LIFETIME
            directory.listFiles()?.filter { it.isFile && it.extension == "png" && it.lastModified() < expired }?.forEach { it.delete() }

            // A second share must never overwrite a picture another app is still reading.
            val file = File(directory, "${UUID.randomUUID()}.png").apply { writeBytes(bytes) }
            FileProvider.getUriForFile(context, AnkiquestGallery.authority(context), file)
        } catch (e: IOException) {
            Timber.w(e, "AnkiQuest share picture could not be prepared")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Timber.w(e, "AnkiQuest share picture could not be rendered or exposed")
            null
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "AnkiQuest share picture could not be allocated")
            null
        }

    private fun quickPng(url: String): ByteArray? =
        try {
            val bytes = readImage(url, AnkiquestApi.client(AnkiquestApi.Timeout.PICTURE))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sides = 1..MAX_PICTURE_SIDE
            bytes.takeIf { bounds.outMimeType == AnkiquestGallery.PNG && bounds.outWidth in sides && bounds.outHeight in sides }
        } catch (e: IOException) {
            Timber.d(e, "AnkiQuest server picture unavailable; drawing locally")
            null
        }

    private fun renderPng(
        context: Context,
        card: Card,
    ): ByteArray {
        val bitmap = render(context, card)
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Could not encode the share picture")
                }
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A local picture and invitation for servers that keep no win record. */
    fun textChooser(
        context: Context,
        title: String,
        body: String,
        friends: PendingIntent? = null,
        @DrawableRes art: Int = R.drawable.aki_celebrate,
        streak: Int? = null,
        companion: AnkiquestCompanion.Choice? = null,
    ): Intent {
        val language = AnkiquestLanguage.context(context)
        val text =
            HomeAccount.current()?.server?.let { language.getString(R.string.ankiquest_share_text, title, body, it) }
                ?: language.getString(R.string.ankiquest_share_text_offline, title, body)
        return cardChooser(context, text, null, friends, Card(title, body, art, streak = streak, companion = companion))
    }

    /**
     * Android's share sheet for [send]. With [friends], and on Android 14 or later, the sheet itself
     * also offers "AnkiQuest friends", so friends and other apps are one choice in one place.
     */
    private fun chooser(
        context: Context,
        send: Intent,
        friends: PendingIntent?,
    ): Intent {
        val language = AnkiquestLanguage.context(context)
        val chooser =
            Intent
                .createChooser(send, language.getString(R.string.ankiquest_share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (friends != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val action =
                ChooserAction
                    .Builder(
                        Icon.createWithResource(context, R.drawable.ic_ankiquest_friends),
                        language.getString(R.string.ankiquest_share_friends_target),
                        friends,
                    ).build()
            chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf(action))
        }
        return chooser
    }

    /** Whether [chooser] can offer AnkiQuest friends itself, so no separate question is needed first. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun sheetOffersFriends(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** The share sheet's entry for AnkiQuest friends: [share] again, told to send the win to friends. */
    fun friendsTarget(
        context: Context,
        share: Intent,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            share.getLongExtra(EXTRA_WIN, 0).toInt(),
            Intent(share)
                .setComponent(AnkiquestHost.current.intent(context, AnkiquestScreen.Share).component)
                .putExtra(EXTRA_DESTINATION, DESTINATION_FRIENDS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The review id in a conquered card's win key `conquered:{at}`, or null for any other key. */
    fun conqueredAt(key: String?): Long? = key?.removePrefix("conquered:")?.takeIf { it != key }?.toLongOrNull()

    /** What the card answered at review [at] says on its front ([AnkiquestCardLabels.suggestedLabel]), for the player to choose to share; null when it is gone. */
    suspend fun conqueredCardText(at: Long): String? = conqueredCardLabels(at)?.suggested

    /** The label [AnkiquestCardLabels.suggestedLabel] offers for a card, and the one earlier versions offered and may have saved. */
    data class Labels(
        val suggested: String?,
        val legacy: String?,
    ) {
        /** [suggested], when a label saved as [legacy] would read better as it. */
        fun better(saved: String?): String? = suggested?.takeIf { saved != null && saved == legacy && it != legacy }
    }

    /** [Labels] for the card answered at review [at]; null when it is gone. */
    suspend fun conqueredCardLabels(at: Long): Labels? =
        try {
            AnkiquestHost.current.conqueredCard(at)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest conquered card text unavailable")
            null
        }

    /**
     * The name of the deck holding the card answered at review [at], as this phone's collection has
     * it; null when the card is gone. A filtered deck gives way to the card's home deck. Deck names
     * stay on the phone: only its owner sees this, and it leaves only in what they share themselves.
     */
    suspend fun conqueredCardDeck(at: Long): String? =
        try {
            AnkiquestHost.current.conqueredDeck(at)?.ifEmpty { null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ankiquest conquered card deck unavailable")
            null
        }

    /**
     * A card image from the server, at most [MAX_IMAGE_BYTES]. Cards are public, so no credentials are
     * sent with the request. Notifications retain their ten-second deadline; shares use the shorter picture deadline.
     *
     * @throws IOException when the address is not a web address, the server refuses, or the image is too large
     */
    fun image(url: String): ByteArray = readImage(url, imageClient)

    private fun readImage(
        url: String,
        client: OkHttpClient,
    ): ByteArray {
        val address = url.toHttpUrlOrNull() ?: throw IOException("Not a card address")
        return client.newCall(Request.Builder().url(address).build()).execute().use { response ->
            if (!response.isSuccessful) throw AnkiquestHttpException(response.code)
            if (response.body.contentLength() > MAX_IMAGE_BYTES) throw IOException("The card image is too large")
            val source = response.body.source()
            if (source.request(MAX_IMAGE_BYTES + 1)) throw IOException("The card image is too large")
            source.buffer.readByteArray()
        }
    }

    /** A card image for a notification's big picture, or null when it cannot be fetched or decoded. It blocks. */
    fun picture(url: String): Bitmap? =
        try {
            val bytes = image(url)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PICTURE_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: IOException) {
            Timber.w(e, "ankiquest card picture unavailable")
            null
        }
}
