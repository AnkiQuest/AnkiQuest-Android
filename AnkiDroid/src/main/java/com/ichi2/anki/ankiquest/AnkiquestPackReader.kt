// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A checked pack: its parsed index, the index's bytes as stored, and its pictures by file name. */
internal class Pack(
    val info: PackInfo,
    val index: ByteArray,
    val pictures: Map<String, ByteArray>,
)

/**
 * Reads a pack zip and checks every rule of docs/packs.md before anything is kept. Downloaded and
 * imported packs go through the same checks; limits hold whatever the zip's headers claim.
 */
internal object AnkiquestPackReader {
    /** Largest pack file. */
    const val MAX_ARCHIVE = 32L * 1024 * 1024

    /** Most entries in a pack. */
    const val MAX_ENTRIES = 128

    /** Largest entry once read. */
    const val MAX_ENTRY = 8L * 1024 * 1024

    /** Most bytes read from all entries together. */
    const val MAX_TOTAL = 64L * 1024 * 1024

    /** Largest index inside a pack. */
    const val MAX_PACK_INDEX = 64L * 1024

    /** Largest picture edge, in pixels. */
    const val MAX_EDGE = 2048

    private val PICTURE_NAME = Regex("[a-z0-9_]{1,64}\\.(webp|png)")

    /**
     * Reads the pack in [input] against [names]. Throws [PackRefused] when a rule fails; [input] is
     * read to the first failure and not closed.
     */
    fun read(
        input: InputStream,
        names: PoseNames,
    ): Pack {
        val entries = entries(BoundedStream(input, MAX_ARCHIVE))
        val index = entries[PackFormat.PACK_INDEX] ?: throw PackRefused(PackRefusal.MISSING_FILE, PackFormat.PACK_INDEX)
        val info = PackInfo.parse(index.decodeToString(), names)
        val pictures = entries - PackFormat.PACK_INDEX

        // Every named file is there, and nothing else is.
        info.poses.values
            .firstOrNull { it !in pictures }
            ?.let { throw PackRefused(PackRefusal.MISSING_FILE, it) }
        pictures.keys.firstOrNull { it !in info.poses.values }?.let { throw PackRefused(PackRefusal.UNUSED_FILE, it) }
        for ((file, bytes) in pictures) {
            checkPicture(file, bytes)
        }
        return Pack(info, index, pictures)
    }

    /** Every entry of the zip in [input], by name, within the limits. */
    private fun entries(input: InputStream): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        try {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break

                // Flat names of known kinds only, each once.
                val name = entry.name
                if (name != PackFormat.PACK_INDEX && !PICTURE_NAME.matches(name)) {
                    throw PackRefused(PackRefusal.BAD_ENTRY_NAME, name)
                }
                if (name in entries) {
                    throw PackRefused(PackRefusal.DUPLICATE_ENTRY, name)
                }
                if (entries.size == MAX_ENTRIES) {
                    throw PackRefused(PackRefusal.TOO_MANY_ENTRIES, "more than $MAX_ENTRIES")
                }

                // Read no more than the limits allow, counting what was actually read.
                val limit = if (name == PackFormat.PACK_INDEX) MAX_PACK_INDEX else MAX_ENTRY
                val bytes = BoundedStream(zip, minOf(limit, MAX_TOTAL - total)).readBytes()
                total += bytes.size
                entries[name] = bytes
            }
        } catch (e: ZipException) {
            throw PackRefused(PackRefusal.BAD_ENTRY_NAME, e.message ?: "not a zip")
        } catch (e: IOException) {
            if (e is TooLarge) {
                throw PackRefused(PackRefusal.TOO_LARGE, e.message ?: "pack")
            }
            throw e
        }
        if (entries.isEmpty()) {
            throw PackRefused(PackRefusal.MISSING_FILE, PackFormat.PACK_INDEX)
        }
        return entries
    }

    /** Refuses [bytes] unless they are a picture of the kind [file] names, small enough. */
    private fun checkPicture(
        file: String,
        bytes: ByteArray,
    ) {
        val size =
            when {
                file.endsWith(".png") -> PictureHeader.png(bytes)
                else -> PictureHeader.webp(bytes)
            } ?: throw PackRefused(PackRefusal.NOT_A_PICTURE, file)
        if (size.first !in 1..MAX_EDGE || size.second !in 1..MAX_EDGE) {
            throw PackRefused(PackRefusal.PICTURE_TOO_LARGE, "$file is ${size.first}x${size.second}")
        }
    }
}

/** Thrown by [BoundedStream] when more than its limit is read. */
internal class TooLarge(
    message: String,
) : IOException(message)

/** [input] that fails with [TooLarge] once more than [limit] bytes were read through it. Does not close [input]. */
internal class BoundedStream(
    private val input: InputStream,
    private val limit: Long,
) : InputStream() {
    private var read = 0L

    override fun read(): Int {
        val byte = input.read()
        if (byte >= 0) {
            count(1)
        }
        return byte
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val n = input.read(buffer, offset, length)
        if (n > 0) {
            count(n)
        }
        return n
    }

    private fun count(n: Int) {
        read += n
        if (read > limit) {
            throw TooLarge("more than $limit bytes")
        }
    }
}

/** The pixel size in a PNG or WebP header, without decoding the picture. */
internal object PictureHeader {
    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a)

    /** PNG signature, then the IHDR chunk with width and height as big-endian 32-bit integers. */
    fun png(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(PNG) || ascii(bytes, 12, 4) != "IHDR") {
            return null
        }
        return big32(bytes, 16) to big32(bytes, 20)
    }

    /** RIFF....WEBP, then a VP8, VP8L or VP8X chunk, each of which stores the size its own way. */
    fun webp(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WEBP") {
            return null
        }
        return when (ascii(bytes, 12, 4)) {
            // Lossy: 14-bit width and height after the frame tag and start code.
            "VP8 " -> (little16(bytes, 26) and 0x3fff) to (little16(bytes, 28) and 0x3fff)
            // Lossless: signature 0x2f, then 14-bit width-1 and height-1.
            "VP8L" -> {
                if (bytes[20] != 0x2f.toByte()) {
                    return null
                }
                val bits = little32(bytes, 21)
                ((bits and 0x3fff) + 1) to (((bits shr 14) and 0x3fff) + 1)
            }
            // Extended: 24-bit canvas width-1 and height-1.
            "VP8X" -> (little24(bytes, 24) + 1) to (little24(bytes, 27) + 1)
            else -> null
        }
    }

    private fun ascii(
        bytes: ByteArray,
        at: Int,
        length: Int,
    ) = String(bytes, at, length, Charsets.US_ASCII)

    private fun byte(
        bytes: ByteArray,
        at: Int,
    ) = bytes[at].toInt() and 0xff

    private fun big32(
        bytes: ByteArray,
        at: Int,
    ) = (byte(bytes, at) shl 24) or (byte(bytes, at + 1) shl 16) or (byte(bytes, at + 2) shl 8) or byte(bytes, at + 3)

    private fun little16(
        bytes: ByteArray,
        at: Int,
    ) = byte(bytes, at) or (byte(bytes, at + 1) shl 8)

    private fun little24(
        bytes: ByteArray,
        at: Int,
    ) = little16(bytes, at) or (byte(bytes, at + 2) shl 16)

    private fun little32(
        bytes: ByteArray,
        at: Int,
    ) = little24(bytes, at) or (byte(bytes, at + 3) shl 24)
}
