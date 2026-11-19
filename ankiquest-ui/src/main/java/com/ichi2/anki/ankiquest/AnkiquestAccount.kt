// SPDX-License-Identifier: AGPL-3.0-only

package com.ichi2.anki.ankiquest

import android.content.Context
import android.os.Build
import com.ichi2.anki.ankiquest.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.UnknownHostException

/** Signs in with a username and password, or creates an account, and keeps only the device token it returns. */
object AnkiquestAccount {
    const val DEFAULT_SERVER = "https://ankiquest.rationality-munich.com"

    /** The capability of a server that offers two-factor authentication. */
    const val TWO_FACTOR = "two_factor"

    /** Where the website sets two-factor authentication up: its own card in Settings. */
    const val TWO_FACTOR_PAGE = "/settings#two-factor"

    /**
     * [code] is the two-factor code or a recovery code, sent only once the server asked for one:
     * servers without two-factor refuse fields they do not know.
     */
    data class Credentials(
        val url: String,
        val user: String,
        val password: String,
        val display: String,
        val create: Boolean,
        val code: String = "",
    )

    data class Signed(
        val url: String,
        val user: String,
        val token: String,
    )

    fun device(): String = "AnkiDroid (${Build.MODEL})"

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
            Signed(request.url.trimEnd('/'), answer.getString("user"), answer.getString("token"))
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

    fun message(
        context: Context,
        error: Throwable,
        url: String,
    ): String =
        when (error) {
            is AnkiquestHttpException ->
                when (error.code) {
                    400 -> context.getString(R.string.ankiquest_sign_in_invalid)
                    401 -> context.getString(R.string.ankiquest_sign_in_wrong)
                    403 -> context.getString(R.string.ankiquest_sign_in_closed)
                    409 -> context.getString(R.string.ankiquest_sign_in_taken)
                    429 -> context.getString(R.string.ankiquest_sign_in_limited)
                    else -> context.getString(R.string.ankiquest_check_http, error.code)
                }
            is SecondFactorNeeded ->
                when (error.step) {
                    SecondFactor.ASKED -> context.getString(R.string.ankiquest_two_factor_asked)
                    SecondFactor.REFUSED -> context.getString(R.string.ankiquest_two_factor_refused)
                    SecondFactor.LOCKED -> context.getString(R.string.ankiquest_two_factor_locked)
                }
            is InsecureServer -> context.getString(R.string.ankiquest_check_insecure, url)
            is UnknownHostException -> context.getString(R.string.ankiquest_check_unreachable, url)
            is IllegalArgumentException -> context.getString(R.string.ankiquest_check_bad_url, url)
            else -> {
                Timber.w(error, "ankiquest sign-in failed")
                context.getString(R.string.ankiquest_check_failed, error.message ?: error.javaClass.simpleName)
            }
        }
}
