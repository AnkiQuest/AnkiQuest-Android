// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.URLEncoder

/** Signs in with a username and password, or creates an account, and keeps only the device token it returns. */
object AnkiquestAccount {
    const val DEFAULT_SERVER = "https://ankiquest.org"

    /** The capability of a server that offers two-factor authentication. */
    const val TWO_FACTOR = "two_factor"

    /** Where the website sets two-factor authentication up: its own card in Settings. */
    const val TWO_FACTOR_PAGE = "/settings#two-factor"

    /** Who may open the profile of a new account: the answer to "Public profile" at sign-up. */
    enum class Profile {
        /** Friends and groups only; the answer unless the player ticks the box. */
        PRIVATE,

        /** Anyone, and listed on the global leaderboard. */
        PUBLIC,
    }

    /**
     * [code] is the two-factor code or a recovery code, sent only once the server asked for one:
     * servers without two-factor refuse fields they do not know. [profile] counts only when
     * [create] is set.
     */
    data class Credentials(
        val url: String,
        val user: String,
        val password: String,
        val display: String,
        val create: Boolean,
        val code: String = "",
        val profile: Profile = Profile.PRIVATE,
    )

    data class Signed(
        val url: String,
        val user: String,
        val token: String,
    )

    /** The name the server lists this sign-in under, as on the website's device list. */
    fun device(): String = "AnkiQuest (${Build.MODEL})"

    fun httpRequest(
        request: Credentials,
        device: String,
    ): Request {
        val body =
            JSONObject()
                .put("user", request.user)
                .put("password", request.password)
                .put("device", device)
        if (request.create && request.display.isNotBlank()) body.put("display", request.display)
        if (!request.create && request.code.isNotBlank()) body.put("code", request.code.trim())
        return Request
            .Builder()
            .url(request.url.trimEnd('/') + if (request.create) "/api/accounts" else "/api/accounts/tokens")
            .header("Accept-Language", AnkiquestLanguage.tag())
            .post(AnkiquestApi.body(body))
            .build()
    }

    /** The password would cross the internet unencrypted, so it is not sent. */
    class InsecureServer : IOException("The server address does not use https")

    /** Where a password sign-in held back for its second factor stands. */
    enum class SecondFactor {
        /** The password is right and the account wants a code; none was sent. */
        ASKED,

        /** The code sent is wrong or was used before. */
        REFUSED,

        /** Too many wrong codes lately. */
        LOCKED,
    }

    /** The server wants the two-factor code, or refused the one sent: ask for it. */
    class SecondFactorNeeded(
        val step: SecondFactor,
    ) : IOException("The server asks for a two-factor code")

    /**
     * @throws InsecureServer when [Credentials.url] is plain http to a host outside the local network
     * @throws SecondFactorNeeded when the password is right and the account wants its two-factor code
     * @throws AnkiquestHttpException when the server turns the request down otherwise
     */
    suspend fun submit(
        request: Credentials,
        device: String = device(),
    ): Signed =
        withContext(Dispatchers.IO) {
            if (AnkiquestHosts.insecure(request.url)) throw InsecureServer()
            val answer =
                try {
                    AnkiquestApi.anonymous(httpRequest(request, device))
                } catch (e: AnkiquestHttpException) {
                    if (!e.secondFactor) throw e
                    throw SecondFactorNeeded(secondFactor(e.code, request.code))
                }
            val signed = Signed(request.url.trimEnd('/'), answer.getString("user"), answer.getString("token"))
            if (request.create) chooseProfile(signed, request.profile)
            signed
        }

    /**
     * Stores the sign-up answer with the `visibility` action every server has. Servers from before
     * the answer refuse an unknown sign-up field and make new profiles public, so it is not sent
     * with the sign-up. A failure leaves the server's default, private on current servers; the
     * player can still change it under Friends.
     */
    private fun chooseProfile(
        signed: Signed,
        profile: Profile,
    ) {
        val user = URLEncoder.encode(signed.user, "UTF-8").replace("+", "%20")
        val url = "${signed.url}/api/social/$user".toHttpUrl()
        val body = AnkiquestSocial.Change.Visibility(profile == Profile.PUBLIC).body()
        try {
            AnkiquestApi.anonymous(
                AnkiquestApi.request(url, signed.token, AnkiquestLanguage.tag()).post(AnkiquestApi.body(body)).build(),
            )
        } catch (e: IOException) {
            Timber.w(e, "ankiquest sign-up profile choice not stored")
        }
    }

    private fun secondFactor(
        status: Int,
        code: String,
    ): SecondFactor =
        when {
            status == TOO_MANY_REQUESTS -> SecondFactor.LOCKED
            code.isNotBlank() -> SecondFactor.REFUSED
            else -> SecondFactor.ASKED
        }

    private const val TOO_MANY_REQUESTS = 429
}
