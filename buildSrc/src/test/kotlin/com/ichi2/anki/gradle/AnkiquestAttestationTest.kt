// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * Tests for [AnkiquestAttestation], run as the build runs it: in a class loader with only this
 * module's classes, the Kotlin library and the verifier ([rulesAttestationClasspath]), which the
 * test task passes as `ankiquest.attestationClasspath`. The bundle is a real GitHub build provenance
 * attestation from sigstore-java's tests, checked offline against the trust root next to it.
 */
class AnkiquestAttestationTest {
    @TempDir
    lateinit var dir: File

    private val bundle = resource("github-provenance.sigstore.json")
    private val trustedRoot = resource("trusted_root.json")

    // The attested file and the workflow that attested it.
    private val subject = "a0cfc71271d6e278e57cd332ff957c3f7043fdda354c4cbb190a30d56efa01bf"
    private val workflow = "https://github.com/loosebazooka/aa-test/.github/workflows/provenance.yaml@refs/heads/main"

    private fun resource(name: String) = File(javaClass.getResource("/sigstore/$name")!!.toURI())

    /** [AnkiquestAttestation.check] in its own class loader; its error, if any. */
    private fun check(
        digests: Map<String, String>,
        signer: String,
    ): Throwable? {
        val classpath = System.getProperty("ankiquest.attestationClasspath").split(File.pathSeparator)
        URLClassLoader(classpath.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            val type = loader.loadClass("com.ichi2.anki.gradle.AnkiquestAttestation")
            val method =
                type.getMethod("check", File::class.java, Map::class.java, File::class.java, String::class.java, File::class.java)
            return try {
                method.invoke(type.getField("INSTANCE").get(null), bundle, digests, dir, signer, trustedRoot)
                null
            } catch (e: InvocationTargetException) {
                e.targetException
            }
        }
    }

    @Test
    fun `an attested asset of the signing workflow verifies`() {
        assertEquals(null, check(mapOf("a.txt" to subject), workflow))
    }

    @Test
    fun `another workflow's attestation is refused`() {
        val error = check(mapOf("a.txt" to subject), AnkiquestAttestation.SIGNER)!!
        assertEquals(IllegalStateException::class.java.name, error.javaClass.name)
        assertTrue(error.message!!.contains("not attested by ${AnkiquestAttestation.SIGNER}"), error.message)
    }

    @Test
    fun `an asset the attestation does not name is refused`() {
        val other = "b".repeat(64)
        val error = check(mapOf("a.txt" to subject, "b.txt" to other), workflow)!!
        assertTrue(error.message!!.startsWith("b.txt (SHA-256 $other) is not attested"), error.message)
    }

    @Test
    fun `the build's class loader never loads the verifier`() {
        assertThrows<ClassNotFoundException> { Class.forName("dev.sigstore.KeylessVerifier") }
    }
}
