// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import dev.sigstore.KeylessVerificationException
import dev.sigstore.KeylessVerifier
import dev.sigstore.TrustedRootProvider
import dev.sigstore.VerificationOptions
import dev.sigstore.VerificationOptions.CertificateMatcher
import dev.sigstore.bundle.Bundle
import dev.sigstore.bundle.BundleParseException
import dev.sigstore.strings.StringMatcher
import dev.sigstore.trustroot.SigstoreConfigurationException
import dev.sigstore.tuf.SigstoreTufClient
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.embeddedKotlinVersion
import org.gradle.kotlin.dsl.exclude
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.File
import java.util.HexFormat

/**
 * The build provenance attestation of a rules release (`ankiquest_rules.sigstore.json`, see
 * AnkiQuest-Server's docs/shared-rules.md): an asset counts only when the bundle verifies against
 * Sigstore's public trust root, its certificate names [SIGNER] and [ISSUER], and the asset's
 * SHA-256 is one of its subjects. `SHA256SUMS` alone can be replaced with the assets it lists.
 *
 * Only [VerifyRulesAttestation] calls this, in a class loader of its own
 * ([rulesAttestationClasspath]): the verifier's dependencies (protobuf, Guava, Bouncy Castle) never
 * meet the Android Gradle plugin's. So this object uses no Gradle types, and the build's own class
 * loader never loads it (it could not resolve the verifier's classes).
 */
object AnkiquestAttestation {
    const val ISSUER = "https://token.actions.githubusercontent.com"

    /** The release workflow of AnkiQuest-Server, on `master`. */
    const val SIGNER = "https://github.com/AnkiQuest/AnkiQuest-Server/.github/workflows/rules-release.yml@refs/heads/master"

    /**
     * Fails unless [bundle] attests each of [digests] (asset name to SHA-256) for [signer]. The trust
     * root comes from Sigstore's TUF repository, cached in [tufCache] for a day, or from
     * [trustedRoot] (tests).
     */
    fun check(
        bundle: File,
        digests: Map<String, String>,
        tufCache: File,
        signer: String = SIGNER,
        trustedRoot: File? = null,
    ) {
        val root =
            trustedRoot?.let { TrustedRootProvider.from(it.toPath()) }
                ?: TrustedRootProvider.from(SigstoreTufClient.builder().usePublicGoodInstance().tufCacheLocation(tufCache.toPath()))
        val verifier =
            try {
                KeylessVerifier.builder().trustedRootProvider(root).build()
            } catch (e: SigstoreConfigurationException) {
                throw IllegalStateException("Could not load Sigstore's trust root: ${e.message}", e)
            }
        val parsed =
            try {
                Bundle.from(bundle.toPath(), Charsets.UTF_8)
            } catch (e: BundleParseException) {
                throw IllegalStateException("${bundle.name} is not a Sigstore bundle: ${e.message}", e)
            }
        val identity =
            CertificateMatcher
                .fulcio()
                .subjectAlternativeName(StringMatcher.string(signer))
                .issuer(StringMatcher.string(ISSUER))
                .build()
        val options = VerificationOptions.builder().addCertificateMatchers(identity).build()

        // One attestation names every asset, so each digest is checked against the same bundle.
        for ((name, sha256) in digests.toSortedMap()) {
            try {
                verifier.verify(HexFormat.of().parseHex(sha256), parsed, options)
            } catch (e: KeylessVerificationException) {
                throw IllegalStateException("$name (SHA-256 $sha256) is not attested by $signer: ${e.message}", e)
            }
        }
    }
}

/** Runs [AnkiquestAttestation.check] in the class loader of [rulesAttestationClasspath]. */
abstract class VerifyRulesAttestation : WorkAction<VerifyRulesAttestation.Parameters> {
    interface Parameters : WorkParameters {
        val bundle: RegularFileProperty
        val digests: MapProperty<String, String>
        val tufCache: DirectoryProperty
    }

    override fun execute() {
        try {
            AnkiquestAttestation.check(parameters.bundle.get().asFile, parameters.digests.get(), parameters.tufCache.get().asFile)
        } catch (e: IllegalStateException) {
            throw GradleException(e.message!!, e)
        }
    }
}

/**
 * A configuration with the verifier and the Kotlin library [AnkiquestAttestation] needs, for
 * [VerifyRulesAttestation]'s class loader. Kept apart from [AnkiquestAttestation], which the build's
 * class loader must not load.
 */
fun Project.rulesAttestationClasspath(): Configuration {
    val configuration =
        configurations.create("ankiquestAttestation") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    dependencies.add(configuration.name, "dev.sigstore:sigstore-java:${libsVersionFor("sigstoreJava")}") {
        // The transports of signing, not needed to verify a bundle.
        exclude(group = "io.grpc")
        exclude(group = "com.google.oauth-client", module = "google-oauth-client-jetty")
    }
    dependencies.add(configuration.name, "org.jetbrains.kotlin:kotlin-stdlib:$embeddedKotlinVersion")
    return configuration
}
