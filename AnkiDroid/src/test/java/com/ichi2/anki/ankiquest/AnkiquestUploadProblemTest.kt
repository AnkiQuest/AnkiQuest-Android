// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.security.cert.CertPath
import java.security.cert.CertPathValidatorException
import java.security.cert.CertPathValidatorException.BasicReason
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnkiquestUploadProblemTest {
    @Test
    fun `each failure names what the player can do about it`() {
        assertEquals(AnkiquestUploadProblem.SIGNED_OUT, AnkiquestUploadProblem.of(AnkiquestHttpException(401)))
        assertEquals(AnkiquestUploadProblem.SIGNED_OUT, AnkiquestUploadProblem.of(AnkiquestHttpException(403)))
        assertEquals(AnkiquestUploadProblem.SERVER, AnkiquestUploadProblem.of(AnkiquestHttpException(502)))
        assertEquals(AnkiquestUploadProblem.REFUSED, AnkiquestUploadProblem.of(AnkiquestHttpException(400)))
        assertEquals(AnkiquestUploadProblem.OFFLINE, AnkiquestUploadProblem.of(UnknownHostException("anki.example.test")))
        assertEquals(AnkiquestUploadProblem.OFFLINE, AnkiquestUploadProblem.of(IOException("reset")))
    }

    @Test
    fun `a switched account or a bug says nothing about the upload`() {
        assertNull(AnkiquestUploadProblem.of(HomeAccountChanged()))
        assertNull(AnkiquestUploadProblem.of(IllegalStateException("bug")))
    }

    @Test
    fun `a certificate problem is named instead of offline`() {
        val wrongHost = SSLPeerUnverifiedException("Hostname anki.example.test not verified")
        assertEquals(AnkiquestUploadProblem.CERT_WRONG_HOST, AnkiquestUploadProblem.of(wrongHost))
        assertEquals(AnkiquestUploadProblem.CERT_EXPIRED, AnkiquestUploadProblem.of(handshake(CertificateExpiredException("expired"))))
        val expired = CertPathValidatorException("expired", null, path(SELF_SIGNED), 0, BasicReason.EXPIRED)
        assertEquals(AnkiquestUploadProblem.CERT_EXPIRED, AnkiquestUploadProblem.of(handshake(expired)))
        val selfSigned = CertPathValidatorException("Trust anchor for certification path not found.", null, path(SELF_SIGNED), -1)
        assertEquals(AnkiquestUploadProblem.CERT_SELF_SIGNED, AnkiquestUploadProblem.of(handshake(selfSigned)))
        val unknownIssuer = CertPathValidatorException("Trust anchor for certification path not found.", null, path(ISSUED), -1)
        assertEquals(AnkiquestUploadProblem.CERT_UNVERIFIED, AnkiquestUploadProblem.of(handshake(unknownIssuer)))
        assertEquals(AnkiquestUploadProblem.CERT_UNVERIFIED, AnkiquestUploadProblem.of(handshake(null)))
    }

    @Test
    fun `a handshake that fails for another reason reads as offline`() {
        assertEquals(AnkiquestUploadProblem.OFFLINE, AnkiquestUploadProblem.of(SSLHandshakeException("Connection reset by peer")))
    }

    @Test
    fun `each problem is stored under its own key`() {
        val keys = AnkiquestUploadProblem.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    /** A TLS handshake failure as the platform trust manager reports it, wrapping [cause] in a [CertificateException]. */
    private fun handshake(cause: Throwable?): SSLHandshakeException =
        SSLHandshakeException("handshake failed").apply { initCause(CertificateException(cause)) }

    private fun path(pem: String): CertPath {
        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertPath(listOf(factory.generateCertificate(pem.byteInputStream())))
    }

    private companion object {
        /** anki.example.test, signed by itself. */
        const val SELF_SIGNED = """-----BEGIN CERTIFICATE-----
MIIBkDCCATWgAwIBAgIUajwJ0Ia1Q0qHjx3Yv7188zL75rgwCgYIKoZIzj0EAwIw
HDEaMBgGA1UEAwwRYW5raS5leGFtcGxlLnRlc3QwIBcNMjYxMTE5MTAwNjE0WhgP
MjEyNjEwMjYxMDA2MTRaMBwxGjAYBgNVBAMMEWFua2kuZXhhbXBsZS50ZXN0MFkw
EwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEIuR5G9k50W16dOaswx1LwWqj5J6ffLF/
IT1J5tS4dGUwtB+vaVdKGkfpWPIjvB0rYa3uMdR92VYffKCHb0t8NaNTMFEwHQYD
VR0OBBYEFFThekLhwWDuUtqSH4hCKnN6fSntMB8GA1UdIwQYMBaAFFThekLhwWDu
UtqSH4hCKnN6fSntMA8GA1UdEwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSQAwRgIh
AKYtXC4UMGYEEBHkAAXbuuK032dVpIBJxWQfosQDV+LuAiEA9RBWikv6CzF+iooM
2hTjet7/3pILbAv1V1OzPRdDFZ8=
-----END CERTIFICATE-----"""

        /** anki.example.test, signed by "Example Test CA", which nobody trusts. */
        const val ISSUED = """-----BEGIN CERTIFICATE-----
MIIBMjCB2QIUCEUy3Yvefeh715LVJ88jf5OhERQwCgYIKoZIzj0EAwIwGjEYMBYG
A1UEAwwPRXhhbXBsZSBUZXN0IENBMCAXDTI2MTExOTEwMDYxNFoYDzIxMjYxMDI2
MTAwNjE0WjAcMRowGAYDVQQDDBFhbmtpLmV4YW1wbGUudGVzdDBZMBMGByqGSM49
AgEGCCqGSM49AwEHA0IABF/1ZLjjJNLjUQ894QGEw48UaAKenUbWBf53Jfm9nq57
amIP7vV0cHm+Y1dY2ajWvay2FDJdEUMizwXM7DW5DRAwCgYIKoZIzj0EAwIDSAAw
RQIhALhp+GH8814SMtab8/FW7C/MigrYrzb9vq9ON50i7V6PAiAAwXiuhb0mX/aS
9QrtsCvVdquZ7fgEHIgYwr/aS7GWIA==
-----END CERTIFICATE-----"""
    }
}
