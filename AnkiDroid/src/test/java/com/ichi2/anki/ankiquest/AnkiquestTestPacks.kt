// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import org.json.JSONObject
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Companion packs made in tests: every picture a small PNG of one colour, so a test can tell which
 * pack and pose it was drawn from. No real companion picture is used.
 */
internal object AnkiquestTestPacks {
    /** Every picture's edge, in pixels. */
    const val EDGE = 8

    /** The core poses every pack has. */
    val core: List<String> = AnkiquestPose.entries.filter { it.group == AnkiquestPose.Group.CORE }.map { it.id }

    /** A PNG of [EDGE] pixels filled with the ARGB [colour], with a transparent left column. */
    fun png(colour: Int): ByteArray {
        val image = BufferedImage(EDGE, EDGE, BufferedImage.TYPE_INT_ARGB)
        for (x in 1 until EDGE) {
            for (y in 0 until EDGE) {
                image.setRGB(x, y, colour)
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /**
     * A pack zip of [id] with a picture of [colours] for each pose named there, the core poses
     * included; [shared] maps more names to the file of a name in [colours].
     */
    fun pack(
        id: String,
        colours: Map<String, Int>,
        shared: Map<String, String> = emptyMap(),
        name: String = id.replaceFirstChar { it.uppercase() },
        notice: String? = null,
    ): ByteArray {
        val poses = colours.keys.associateWith { "$it.png" } + shared.mapValues { (_, to) -> "$to.png" }
        val info =
            JSONObject()
                .put("format", PackFormat.VERSION)
                .put("id", id)
                .put("name", name)
                .put("version", 1)
                .put("licence", "AGPL-3.0-or-later")
                .put("credit", "AnkiQuest")
                .put("notice", notice ?: JSONObject.NULL)
                .put("poses", JSONObject(poses))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(PackFormat.PACK_INDEX))
            zip.write(info.toString().toByteArray())
            for ((pose, colour) in colours) {
                zip.putNextEntry(ZipEntry("$pose.png"))
                zip.write(png(colour))
            }
        }
        return out.toByteArray()
    }

    /** [colour] for every core pose, then [extra]. */
    fun colours(
        colour: Int,
        extra: Map<String, Int> = emptyMap(),
    ): Map<String, Int> = core.associateWith { colour } + extra

    /**
     * Makes Aki, Ankilope and Bluma (`cow`) the app's companions, as they were before Anki-tan went
     * live; undo with `AnkiquestPacks.use(null)`.
     */
    fun useOfficial(root: File) {
        AnkiquestPacks.use(
            library(
                root,
                pack("aki", colours(AKI)),
                pack("ankilope", colours(ANKILOPE)),
                pack("cow", colours(COW), name = "Bluma"),
            ),
        )
    }

    private const val AKI = 0xFFFF8000.toInt()
    private const val ANKILOPE = 0xFFA0522D.toInt()
    private const val COW = 0xFF8000FF.toInt()

    /** A library in [root] with [bundled] packs installed as the app's bundled ones. */
    fun library(
        root: File,
        vararg bundled: ByteArray,
    ): AnkiquestPackLibrary {
        val folder = FilePackFolder(File(root, "bundled"))
        bundled.forEach { folder.install(AnkiquestPackReader.read(it.inputStream(), PoseNames.builtIn())) }
        return AnkiquestPackLibrary(folder, FilePackFolder(File(root, "downloaded")), FilePackFolder(File(root, "imported")))
    }
}
