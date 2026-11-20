// SPDX-FileCopyrightText: Copyright (c) 2026 hill <hill@hilll.dev>
// SPDX-License-Identifier: LGPL-3.0-or-later
package com.ichi2.anki.api

import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Bundle
import com.ichi2.anki.FlashCardsContract
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric doesn't shadow the Tiramisu+ `resolveContentProvider`, see [AddContentApiTest] */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2])
internal class AnkiDroidProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        setCaller(Caller.RELEASE)
    }

    @Test
    fun genuineCertificateIsAccepted() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(AnkiDroidPackages.RELEASE))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun fdroidCertificateIsAccepted() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(AnkiDroidPackages.FDROID))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun unknownCertificateIsRejected() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
        assertEquals(-1, AddContentApi(context).apiHostSpecVersion)
    }

    @Test
    fun packageWithoutCertificateIsRejected() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current())

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun otherPackageWithGenuineCertificateIsRejected() {
        AnkiDroidPackages.install(IMPOSTOR, Signing.Current(AnkiDroidPackages.RELEASE))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun otherPackageIsRejected() {
        AnkiDroidPackages.install(IMPOSTOR, Signing.Current(UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    /** A rotated key is signed with the new key, and lists the release key in its history */
    @Test
    fun rotatedKeyIsAccepted() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Rotated(past = AnkiDroidPackages.RELEASE, current = UNKNOWN))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun rotationFromUnknownKeyIsRejected() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Rotated(past = UNKNOWN, current = OTHER_UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun anyOfSeveralSignersIsAccepted() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(UNKNOWN, AnkiDroidPackages.RELEASE))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun genuineCertificateIsAcceptedBeforeApi28() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(AnkiDroidPackages.RELEASE))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O_MR1])
    fun unknownCertificateIsRejectedBeforeApi28() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun genuineCertificateIsAcceptedFromApi28() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(AnkiDroidPackages.RELEASE))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.P])
    fun unknownCertificateIsRejectedFromApi28() {
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    /** Developers of a debuggable client can test against their own AnkiDroid build */
    @Test
    fun debuggableCallerAcceptsUnknownCertificate() {
        setCaller(Caller.DEBUGGABLE)
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(UNKNOWN))

        assertEquals(ANKIDROID, AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun debuggableCallerRejectsOtherPackage() {
        setCaller(Caller.DEBUGGABLE)
        AnkiDroidPackages.install(IMPOSTOR, Signing.Current(UNKNOWN))

        assertNull(AddContentApi.getAnkiDroidPackageName(context))
    }

    @Test
    fun specVersionIsReadFromGenuineProvider() {
        AnkiDroidPackages.install(
            ANKIDROID,
            Signing.Current(AnkiDroidPackages.RELEASE),
            Bundle().apply { putInt("com.ichi2.anki.provider.spec", 2) },
        )

        assertEquals(2, AddContentApi(context).apiHostSpecVersion)
    }

    /** Calls through [AddContentApi] must not reach a provider that isn't AnkiDroid */
    @Test
    fun impostorProviderIsNotCalled() {
        val provider = installFakeCardProvider()
        AnkiDroidPackages.install(IMPOSTOR, Signing.Current(UNKNOWN))

        assertFalse(AddContentApi(context).setFlag(NOTE_ID, Flag.RED))
        assertEquals(listOf(Flag.NONE.code), provider.getNoteFlags(NOTE_ID))
    }

    @Test
    fun genuineProviderIsCalled() {
        val provider = installFakeCardProvider()
        AnkiDroidPackages.install(ANKIDROID, Signing.Current(AnkiDroidPackages.RELEASE))

        assertTrue(AddContentApi(context).setFlag(NOTE_ID, Flag.RED))
        assertEquals(listOf(Flag.RED.code), provider.getNoteFlags(NOTE_ID))
    }

    private enum class Caller { RELEASE, DEBUGGABLE }

    private fun setCaller(caller: Caller) {
        val info = context.applicationInfo
        info.flags =
            when (caller) {
                Caller.RELEASE -> info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
                Caller.DEBUGGABLE -> info.flags or ApplicationInfo.FLAG_DEBUGGABLE
            }
    }

    private fun installFakeCardProvider(): FakeCardContentProvider =
        Robolectric
            .buildContentProvider(FakeCardContentProvider::class.java)
            .create(FlashCardsContract.AUTHORITY)
            .get()
            .apply { addNote(NOTE_ID, cardCount = 1) }

    companion object {
        private val ANKIDROID = BuildConfig.ANKI_PACKAGE_NAME
        private const val IMPOSTOR = "com.example.impostor"
        private const val NOTE_ID = 1L
        private val UNKNOWN = Signature(byteArrayOf(1, 2, 3))
        private val OTHER_UNKNOWN = Signature(byteArrayOf(4, 5, 6))
    }
}

/** Whether a test provider declares [FlashCardsContract.ACTION_FLASHCARDS_PROVIDER] */
internal enum class ProviderInterface { DECLARED, NOT_DECLARED }

/** Whether the client app declares a test provider's permission */
internal enum class PermissionRequest { REQUESTED, NOT_REQUESTED }

/** How a test package is signed */
internal sealed interface Signing {
    /** Signed by [signers], without rotation */
    class Current(
        vararg val signers: Signature,
    ) : Signing

    /** Signed by [current], rotated from [past] */
    class Rotated(
        val past: Signature,
        val current: Signature,
    ) : Signing
}

/** Installs packages that hold [FlashCardsContract.AUTHORITY], or another [authority] */
internal object AnkiDroidPackages {
    /**
     * AnkiDroid's release certificate, from the APK signing block of AnkiDroid-2.25.3-arm64-v8a.apk
     * on GitHub. SHA-256: 2071534F0F4B5E54AE952DD275D70DA6E3459EE69909D2AB1B4843C4C5B21A45
     */
    val RELEASE =
        Signature(
            Base64.getMimeDecoder().decode(
                """
                MIIB4zCCAUygAwIBAgIESkbphTANBgkqhkiG9w0BAQUFADA1MRswGQYDVQQKExJo
                dHRwOi8vbnJ3LmZyZWUuZnIxFjAUBgNVBAMTDU5pY29sYXMgUmFvdWwwIBcNMDkw
                NjI4MDM1NDQ1WhgPMjE1OTA1MjMwMzU0NDVaMDUxGzAZBgNVBAoTEmh0dHA6Ly9u
                cncuZnJlZS5mcjEWMBQGA1UEAxMNTmljb2xhcyBSYW91bDCBnzANBgkqhkiG9w0B
                AQEFAAOBjQAwgYkCgYEAiNuZzNVFVrd/7cGRFQIfMt3KQp9ub5nJ1VFHFwPNzqY8
                J0ZoDCATXEyrbX5Aauhmd15OodHvrlmLg6pMCeOr87gywDWYKO8fCarwNZ1VDUN9
                qvJvyA7fnDjf5fO+cWPPPLIfQHoGc+WE5/CklXdviUZHBIdR08cv1RCFkbdeG18C
                AwEAATANBgkqhkiG9w0BAQUFAAOBgQBueva+wKhbM1UWijy+AzImhhq+S8PjalDv
                017puEL+orrtkb/MSzlDiO9QTvICI0sq1By8Mpzx9bj0dwobh623/Z8tkl4HJOc/
                q9vkMdb2yjlOty7UEZ2ExtzhyDh7v1go5EA4U/vllnx7cm+B+NywZueNhgDPzo2f
                8O5qre1YSA==
                """.trimIndent(),
            ),
        )

    /**
     * The certificate F-Droid signs its AnkiDroid builds with, from the APK signing block of
     * com.ichi2.anki_225033004.apk on f-droid.org.
     * SHA-256: 24A3D4756C62DD69C86DF69AA7FA327B2A6BF55F55C4A1749F99BD1DA75906BB
     */
    val FDROID =
        Signature(
            Base64.getMimeDecoder().decode(
                """
                MIIDPDCCAiSgAwIBAgIETSOoMzANBgkqhkiG9w0BAQUFADBgMQswCQYDVQQGEwJVSzEMMAoGA1UE
                CBMDT1JHMQwwCgYDVQQHEwNPUkcxEzARBgNVBAoTCmZkcm9pZC5vcmcxDzANBgNVBAsTBkZEcm9p
                ZDEPMA0GA1UEAxMGRkRyb2lkMB4XDTExMDEwNDIzMDczMVoXDTM4MDUyMjIzMDczMVowYDELMAkG
                A1UEBhMCVUsxDDAKBgNVBAgTA09SRzEMMAoGA1UEBxMDT1JHMRMwEQYDVQQKEwpmZHJvaWQub3Jn
                MQ8wDQYDVQQLEwZGRHJvaWQxDzANBgNVBAMTBkZEcm9pZDCCASIwDQYJKoZIhvcNAQEBBQADggEP
                ADCCAQoCggEBAMQ3NiAVlRx4R5+jAy9Q7ZUI4494MWDqcxizyVcC1jPD9cR1KKiYDUvIQ7TM5mPm
                /VFY0e4/k1Eoc7NTo56i6kFBnFM/eALM2Vso4232AdxyIlBFkRgpFSP6zB6B66W2ZVdXYv0tSqIq
                Cci9XtatXH/7dqY9oZeUmK9r0AtLVbMKM1A9XTuv78Js4EXE/nCVXNcpR+yE9IHs6MFHm092RZVi
                sdgdchvfvbHa9cs7EvV80cdl+kMsT2ONGkEx71FXGPMouG9xtsszU4LPtj2qWOH/wE8lI/q0ieqS
                BRkFvxJVW1XswtWogClXHCe8qWsQ4bku/bT/Y8Ax8HltqN5N+bsCAwEAATANBgkqhkiG9w0BAQUF
                AAOCAQEAbThxX4eoVwglcVDfSkoQr9ga/gtHpJ/9oPxYFcGewhjS8fV25ts2+Kb8JHxdCIYjYdLR
                RC2fTkuaek/rVlPwW5/cDTTSX5oyLGZamus9lic+brLri1lo/bh2d/wKOzPbLS4NroxxV2Q6DO9X
                cR9AuRPUyexnIKars+rjBUCU23wSfaoQw2bjzgwSbrLa1bkkxCNfA8ZIgG1VKj1pQdcnAaTf5cqz
                6yIzg90sOxzdOuJGIVNJ9OIe4Kc1yyWXQ0wZBIymP2ZJKd5D4LM8Y+vi/5XNLDsvhXKr2pjpc8qf
                mDHP0OnTAv/K+Y/o1QD7WggKkdD3KXIn55FjWSnKXCJ9zg==
                """.trimIndent(),
            ),
        )

    /**
     * AnkiQuest's current release certificate, from the v3 signature of AnkiQuest.apk in the
     * quest-23 release on GitHub. SHA-256: B397A61ECFAF0FE354DA611772515E683CCB6FD0E952D477B8B3BCA49E6C490D
     */
    val ANKIQUEST_RELEASE =
        Signature(
            Base64.getMimeDecoder().decode(
                """
                MIIE8DCCAtigAwIBAgIJAML0qH7KwcdSMA0GCSqGSIb3DQEBDAUAMCUxDzANBgNV
                BAoTBmZsb2F0MzESMBAGA1UEAxMJQW5raVF1ZXN0MCAXDTI2MDkyNDA2MjQxM1oY
                DzIxMjYwODMxMDYyNDEzWjAlMQ8wDQYDVQQKEwZmbG9hdDMxEjAQBgNVBAMTCUFu
                a2lRdWVzdDCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAMkAX1Y4QuzJ
                xZOt7xqfoZFkQRQRSC2ejwKab5kN/GHhOhQ+989fyz4dWvdzewLBt42iY9QboS5i
                vokSenBjUgq4GVJMgYTuRZ93YpjemlzyGBtyL/Jw37ErrVgeGhLo/iNVXoXzhmz8
                aJhckkwy81yv6q2tEcWGH4Fr90b4cac/w/5Vl2fAQDgH6ihT4wbYh3ZCm2QXzJ5u
                2R0/lK1pWH3go9iKHN59Bcl662Nf9rajzmPxrKb1yIxJIRR7EHikFJO4Ph4OBZvp
                KYwYgrfjdfAPVivjnH4vLqJ7YYF2qp5ygnApgjqc/m3czH6XCJO6/wNI1ZiXrg64
                U3v75yppPggnfztoEeJbZH7aeVtU/vuAu1jfxn1y8i+Z/eMZzg5CQWXugeK45LqT
                Hcb0mt5Sts2Lr2boO4T09Xd0uu1L8mI4UnYxqGhR2BHvca/K4HqecmP8qlFJKB2n
                QUCFwwUOUQ5CJTEISwxUXnQ6324CSnK26hwrdG5K6uRheAovNDSTidOqDoOmtWh8
                rE9fPwCVzfa+VJR8FqW8iTqNVvHLkBkh1izq/1v7frN6i7Z/d8lv1iFnbgrYBrAm
                wc22TCNZ8ZeZw5BQgDEm8/Ih3eItpZD2GyHku0weANyMLcccFgSd1WtbvUpGjNIC
                Qcme4vBdH9IFg5sWTwnupvrR0raQvsA/AgMBAAGjITAfMB0GA1UdDgQWBBTx1Dq+
                9YFV7EquJR7/fUeKOtmVcDANBgkqhkiG9w0BAQwFAAOCAgEAwfOhaCtcv7SMSYL0
                K+HeVPXlnBtl0haKBQSF12hW+GZfqoOko8bJjxTxckoS1J8husvWQHEn+Q7rtlxi
                Q933MbuRh6ZNns1gK+GSjNn3UgOI4bbxBK/wxv2U5GxD8Yo3rz8DlkQVucGe84kQ
                6B8B4z25hyxRyOa7mPbr45k7UTYjZiEXWLEcCzQBdXjOlb6X2UuFCCUulmIOqouu
                cjbQYY8ap7BpOgXrnORCDX92KWnPdTuE1rYtcXZX/IzsxZeXpZ0DT0BObJFSEgqf
                8JKzCcHHeX5Y7lnTK/QTwGo2A+09HLf6MZ9p68Dln1sFCoiWOQZygWsnitYAYAN3
                AE7na8RfD3pcfPU/8OpPbUiIvfxBwgVsXhLA3x7jsuoXXAAdNwv3LpFfwrsE5F3r
                h6k/wjVYIIkq1Z5wqq2iw44Pnir6Ca/Lyhdd7bcIvdS+8pULQC5kGVTaQ5VqtD2G
                7cV+Jef9Eh5fCuq8RBtfwIU3xLJ+02EOrjIq6aPGjOwNeqjgb+eojGfMOdJdplAm
                hU5sPs9xGXaEy7LngHG9u4NhJnxquDnVTSMbOHUlM2uAJ6WizTTi1U8sC0X3ACnu
                3aCG4Vu5JQ8x4OJ7fbHwGUzPmrGD83tvcwirZwMqkgRN1Hwrux0mkoUkH79JHx19
                6Osnkftrax+tSq4o6V4n2flcSOU=
                """.trimIndent(),
            ),
        )

    @Suppress("DEPRECATION") // signatures, set only before API 28
    fun install(
        packageName: String,
        signing: Signing,
        metaData: Bundle? = null,
        authority: String = FlashCardsContract.AUTHORITY,
        filter: ProviderInterface = ProviderInterface.NOT_DECLARED,
        permission: PermissionRequest = PermissionRequest.REQUESTED,
    ) {
        val manager = shadowOf(RuntimeEnvironment.getApplication().packageManager)

        // Signatures and signing info as the platform reports them for the running API level
        val info =
            PackageInfo().apply {
                this.packageName = packageName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    signingInfo = signingInfo(signing)
                } else {
                    signatures = current(signing)
                }
            }
        manager.installPackage(info)

        // The provider is added after the package, so it keeps the package's signatures
        val provider =
            ProviderInfo().apply {
                this.authority = authority
                this.packageName = packageName
                name = "com.ichi2.anki.provider.CardContentProvider"
                this.metaData = metaData
            }
        manager.addOrUpdateProvider(provider)

        // The client app declares the provider's permission, as it must to be granted it
        if (permission == PermissionRequest.REQUESTED) {
            request(FlashCardsContract.getPermission(packageName))
        }

        // Current builds declare FlashCardsContract.ACTION_FLASHCARDS_PROVIDER on the provider
        if (filter == ProviderInterface.DECLARED) {
            manager.addIntentFilterForProvider(
                ComponentName(packageName, provider.name),
                IntentFilter(FlashCardsContract.ACTION_FLASHCARDS_PROVIDER),
            )
        }
    }

    /** Adds [permission] to the client app's `<uses-permission>` list */
    private fun request(permission: String) {
        val context = RuntimeEnvironment.getApplication()
        val info = shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName)
        val requested = info.requestedPermissions?.toList().orEmpty()
        if (permission in requested) {
            return
        }
        info.requestedPermissions = (requested + permission).toTypedArray()
    }

    private fun current(signing: Signing): Array<Signature> =
        when (signing) {
            is Signing.Current -> arrayOf(*signing.signers)
            is Signing.Rotated -> arrayOf(signing.current)
        }

    /** The platform lists past certificates oldest first, ending with the current one */
    private fun signingInfo(signing: Signing): SigningInfo {
        val info = Shadow.newInstanceOf(SigningInfo::class.java)
        val shadow = Shadow.extract<ShadowSigningInfo>(info)
        shadow.setSignatures(current(signing))
        if (signing is Signing.Rotated) {
            shadow.setPastSigningCertificates(arrayOf(signing.past, signing.current))
        }
        return info
    }
}
