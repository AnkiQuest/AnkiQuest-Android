// SPDX-License-Identifier: AGPL-3.0-only

package org.ankiquest

import android.content.SharedPreferences
import androidx.core.content.edit
import java.io.IOException
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_INTERNAL_ERROR
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.security.cert.CertPathValidatorException
import java.security.cert.CertPathValidatorException.BasicReason
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Why the last upload for an account failed, kept until an upload succeeds, so the screens that say
 * reviews are not uploaded yet can also say why.
 *
 *     upload fails ──▶ of(error) ──▶ remember(problem) ──▶ Today: "Streak safe · sign in again to upload"
 *     upload works ──────────────▶ remember(null) ─────▶ Today: "Streak safe"
 */
internal enum class AnkiquestUploadProblem(
    val key: String,
) {
    /** The server could not be reached; uploads retry on their own. */
    OFFLINE("offline"),

    /** The server refused this device's token; signing in again fixes it. */
    SIGNED_OUT("signed_out"),

    /** The server failed; uploads retry on their own. */
    SERVER("server"),

    /** The server refused the upload for another reason; Test connection says which. */
    REFUSED("refused"),

    /** The server's certificate has expired; uploads retry on their own. */
    CERT_EXPIRED("cert_expired"),

    /** The server's certificate is signed by itself, not by a trusted authority. */
    CERT_SELF_SIGNED("cert_self_signed"),

    /** The server's certificate names another address, as when the address now serves another site. */
    CERT_WRONG_HOST("cert_wrong_host"),

    /** The server's certificate failed verification for any other reason. */
    CERT_UNVERIFIED("cert_unverified"),
    ;

    companion object {
        private const val PREF_PROBLEM = "ankiquestUploadProblem"

        /** The problem [error] shows, or null when it says nothing about the upload (the account changed, or a bug). */
        fun of(error: Throwable): AnkiquestUploadProblem? =
            when {
                error is HomeAccountChanged -> null
                error is AnkiquestHttpException && (error.code == HTTP_UNAUTHORIZED || error.code == HTTP_FORBIDDEN) -> SIGNED_OUT
                error is AnkiquestHttpException && error.code >= HTTP_INTERNAL_ERROR -> SERVER
                error is AnkiquestHttpException -> REFUSED
                error is SSLException -> certificate(error) ?: OFFLINE
                error is IOException -> OFFLINE
                else -> null
            }

        /**
         * Which certificate problem a TLS failure was, as the add-on names them, or null when the
         * handshake failed for another reason, which reads as offline. OkHttp reports a certificate
         * for another address as [SSLPeerUnverifiedException]; the platform's trust manager reports
         * the rest as a [CertificateException] or [CertPathValidatorException] inside the
         * [javax.net.ssl.SSLHandshakeException].
         */
        private fun certificate(error: SSLException): AnkiquestUploadProblem? {
            if (error is SSLPeerUnverifiedException) {
                return CERT_WRONG_HOST
            }
            val causes = generateSequence(error.cause) { it.cause }.toList()
            if (causes.none { it is CertificateException || it is CertPathValidatorException }) {
                return null
            }

            // Expiry comes either as its own exception or as the validator's reason.
            val path = causes.filterIsInstance<CertPathValidatorException>().firstOrNull()
            if (causes.any { it is CertificateExpiredException } || path?.reason == BasicReason.EXPIRED) {
                return CERT_EXPIRED
            }

            // A chain of one certificate that names itself as issuer is self-signed.
            val leaf = path?.certPath?.certificates?.singleOrNull() as? X509Certificate
            if (leaf != null && leaf.subjectX500Principal == leaf.issuerX500Principal) {
                return CERT_SELF_SIGNED
            }
            return CERT_UNVERIFIED
        }

        /** Keeps [problem] for the account with [scope]; null forgets it, after an upload that worked. */
        fun remember(
            prefs: SharedPreferences,
            scope: String,
            problem: AnkiquestUploadProblem?,
        ) {
            val key = scopedKey(PREF_PROBLEM, scope)
            if (prefs.getString(key, null) == problem?.key) return
            prefs.edit {
                if (problem == null) remove(key) else putString(key, problem.key)
            }
        }

        /** Why the last upload for the account with [scope] failed, or null when it worked or none was made. */
        fun last(
            prefs: SharedPreferences,
            scope: String?,
        ): AnkiquestUploadProblem? {
            scope ?: return null
            val stored = prefs.getString(scopedKey(PREF_PROBLEM, scope), null) ?: return null
            return entries.firstOrNull { it.key == stored }
        }
    }
}
