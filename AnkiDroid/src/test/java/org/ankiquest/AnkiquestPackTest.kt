// SPDX-License-Identifier: AGPL-3.0-or-later

package org.ankiquest

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Companion packs (AnkiQuest-Art docs/packs.md): parsing, checks, fallbacks, selection, download and import. */
class AnkiquestPackTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val core = listOf("face", "asleep", "happy", "streak", "sad", "hopeful", "frozen", "neglected")

    private val names =
        PoseNames(
            core,
            mapOf("streak_hot" to "streak", "streak_blazing" to "streak_hot", "place_1" to "winner", "winner" to "happy", "icon" to "face"),
        )

    /** A 2x3 PNG header: signature and IHDR, enough for the size check. */
    private fun png(
        width: Int = 2,
        height: Int = 3,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a))
        out.write(byteArrayOf(0, 0, 0, 13))
        out.write("IHDR".toByteArray())
        for (value in listOf(width, height)) {
            out.write(byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()))
        }
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    /** A lossy WebP header of [width] by [height]. */
    private fun webp(
        width: Int = 4,
        height: Int = 5,
    ): ByteArray {
        val bytes = ByteArray(30)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBPVP8 ".toByteArray().copyInto(bytes, 8)
        bytes[26] = width.toByte()
        bytes[27] = (width shr 8).toByte()
        bytes[28] = height.toByte()
        bytes[29] = (height shr 8).toByte()
        return bytes
    }

    /** `companion.json` for [id] with [poses], every pose its own file unless given. */
    private fun info(
        id: String = "aki",
        version: Int = 1,
        poses: Map<String, String> = core.associateWith { "$it.webp" },
        format: Int = PackFormat.VERSION,
        name: String = "Aki",
    ): String =
        JSONObject()
            .put("format", format)
            .put("id", id)
            .put("name", name)
            .put("version", version)
            .put("licence", "AGPL-3.0-or-later")
            .put("credit", "AnkiQuest")
            .put("poses", JSONObject(poses))
            .toString()

    /** A zip of [entries], in order. */
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A valid pack of [id]: its index and a WebP for each pose file. */
    private fun pack(
        id: String = "aki",
        version: Int = 1,
        poses: Map<String, String> = core.associateWith { "$it.webp" },
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val pictures = poses.values.distinct().map { it to if (it.endsWith(".png")) png() else webp() }
        return zip(listOf(PackFormat.PACK_INDEX to info(id, version, poses).toByteArray()) + pictures + extra)
    }

    private fun refusal(block: () -> Unit): PackRefusal = assertFailsWith<PackRefused> { block() }.reason

    private fun read(bytes: ByteArray) = AnkiquestPackReader.read(bytes.inputStream(), names)

    @Test
    fun `a valid pack is read with its pictures`() {
        val poses = core.associateWith { "$it.webp" } + mapOf("streak_hot" to "hot.png", "icon" to "face.webp")
        val pack = read(pack(poses = poses))
        assertEquals("aki", pack.info.id)
        assertEquals(poses, pack.info.poses)
        assertEquals((core.map { "$it.webp" } + "hot.png").toSet(), pack.pictures.keys)
    }

    @Test
    fun `entry names that leave the pack are refused`() {
        for (name in listOf("../face.webp", "art/face.webp", "/face.webp", "..\\face.webp", "Face.webp", "face.jpg", "face.webp.exe")) {
            val bytes = pack(extra = listOf(name to webp()))
            assertEquals(PackRefusal.BAD_ENTRY_NAME, refusal { read(bytes) }, name)
        }
    }

    @Test
    fun `an entry twice is refused`() {
        // ZipOutputStream refuses duplicates, so write a look-alike name and patch it in.
        val bytes = pack(extra = listOf("fbce.webp" to webp()))
        val text = String(bytes, Charsets.ISO_8859_1)
        var at = text.indexOf("fbce.webp")
        while (at >= 0) {
            bytes[at + 1] = 'a'.code.toByte()
            at = text.indexOf("fbce.webp", at + 1)
        }
        assertEquals(PackRefusal.DUPLICATE_ENTRY, refusal { read(bytes) })
    }

    @Test
    fun `too many entries are refused`() {
        val extra = (0 until AnkiquestPackReader.MAX_ENTRIES).map { "extra_$it.webp" to webp() }
        assertEquals(PackRefusal.TOO_MANY_ENTRIES, refusal { read(pack(extra = extra)) })
    }

    @Test
    fun `an entry larger than the limit is refused while it is read`() {
        val big = webp() + ByteArray((AnkiquestPackReader.MAX_ENTRY + 1).toInt())
        assertEquals(PackRefusal.TOO_LARGE, refusal { read(pack(extra = listOf("big.webp" to big))) })
    }

    @Test
    fun `files that are not pictures are refused`() {
        val poses = core.associateWith { "$it.webp" }
        val bytes = zip(listOf(PackFormat.PACK_INDEX to info(poses = poses).toByteArray()) + poses.values.map { it to png() })
        assertEquals(PackRefusal.NOT_A_PICTURE, refusal { read(bytes) })
    }

    @Test
    fun `pictures larger than the limit are refused`() {
        val poses = core.associateWith { "$it.png" }
        val edge = AnkiquestPackReader.MAX_EDGE + 1
        val bytes = zip(listOf(PackFormat.PACK_INDEX to info(poses = poses).toByteArray()) + poses.values.map { it to png(edge, 1) })
        assertEquals(PackRefusal.PICTURE_TOO_LARGE, refusal { read(bytes) })
    }

    @Test
    fun `unknown pose names are refused`() {
        val poses = core.associateWith { "$it.webp" } + ("dance" to "face.webp")
        assertEquals(PackRefusal.UNKNOWN_POSE, refusal { read(pack(poses = poses)) })
    }

    @Test
    fun `a pack without a core pose is refused`() {
        val poses = core.drop(1).associateWith { "$it.webp" }
        assertEquals(PackRefusal.MISSING_CORE_POSE, refusal { read(pack(poses = poses)) })
    }

    @Test
    fun `named files must be there and every picture named`() {
        val poses = core.associateWith { "$it.webp" }
        val missing = zip(listOf(PackFormat.PACK_INDEX to info(poses = poses).toByteArray()) + poses.values.drop(1).map { it to webp() })
        assertEquals(PackRefusal.MISSING_FILE, refusal { read(missing) })
        assertEquals(PackRefusal.UNUSED_FILE, refusal { read(pack(extra = listOf("spare.webp" to webp()))) })
        assertEquals(PackRefusal.MISSING_FILE, refusal { read(zip(listOf("face.webp" to webp()))) })
    }

    @Test
    fun `bad ids, names and formats are refused`() {
        assertEquals(PackRefusal.BAD_ID, refusal { PackInfo.parse(info(id = "../aki"), names) })
        assertEquals(PackRefusal.BAD_ID, refusal { PackInfo.parse(info(id = "Aki"), names) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackInfo.parse(info(name = ""), names) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackInfo.parse(info(name = "x".repeat(PackFormat.MAX_NAME + 1)), names) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackInfo.parse(info(version = 0), names) })
        assertEquals(PackRefusal.NEWER_FORMAT, refusal { PackInfo.parse(info(format = PackFormat.VERSION + 1), names) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackInfo.parse("{\"format\": 1}", names) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackInfo.parse("not json", names) })
    }

    @Test
    fun `fallback chains end at a core pose`() {
        assertEquals(listOf("place_1", "winner", "happy"), names.chain("place_1"))
        assertEquals(listOf("face"), names.chain("face"))
        assertEquals(PackRefusal.BAD_INDEX, refusal { PoseNames(core, mapOf("a" to "b", "b" to "a")) })
        assertEquals(PackRefusal.BAD_INDEX, refusal { PoseNames(core, mapOf("a" to "nowhere")) })
    }

    @Test
    fun `the built-in names are the app's poses`() {
        val builtIn = PoseNames.builtIn()
        assertEquals(core, builtIn.core)
        assertEquals("streak", builtIn.fallbacks["streak_hot"])
    }

    /** `packs.json` listing [packs] (id to bytes) as release [version]. */
    private fun index(
        version: Int,
        packs: Map<String, ByteArray>,
    ): String =
        JSONObject()
            .put("format", PackFormat.VERSION)
            .put("tag", "art-$version")
            .put("commit", "0".repeat(40))
            .put("version", version)
            .put("core", JSONArray(core))
            .put("fallbacks", JSONObject(names.fallbacks))
            .put(
                "companions",
                JSONArray(
                    packs.map { (id, bytes) ->
                        JSONObject()
                            .put("id", id)
                            .put("name", id.replaceFirstChar { it.uppercase() })
                            .put("version", version)
                            .put("file", "pack-$id.zip")
                            .put("size", bytes.size)
                            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    },
                ),
            ).toString()

    @Test
    fun `an index with a path in a file name is refused`() {
        val bytes = pack()
        val text = index(2, mapOf("aki" to bytes)).replace("pack-aki.zip", "../pack-aki.zip")
        assertEquals(PackRefusal.BAD_INDEX, refusal { PackIndex.parse(text) })
    }

    private fun library(bundled: PackFolder = FilePackFolder(folder.newFolder())) =
        AnkiquestPackLibrary(bundled, FilePackFolder(folder.newFolder()), FilePackFolder(folder.newFolder()))

    /** A folder holding [packs] as installed packs and [index]. */
    private fun bundled(
        index: String?,
        vararg packs: ByteArray,
    ): FilePackFolder {
        val bundled = FilePackFolder(folder.newFolder())
        packs.forEach { bundled.install(read(it)) }
        index?.let { bundled.writeIndex(it.toByteArray()) }
        return bundled
    }

    @Test
    fun `a missing picture shows its fallback`() {
        val poses = core.associateWith { "$it.webp" } + ("streak_hot" to "hot.webp")
        val library = library(bundled(index(1, emptyMap()), pack(poses = poses)))
        val hot = library.picture("aki", "streak_hot")
        val streak = library.picture("aki", "streak")
        assertTrue(hot.contentEquals(library.companion("aki")!!.file("hot.webp")))
        assertTrue(library.picture("aki", "streak_blazing").contentEquals(hot))
        assertTrue(library.picture("aki", "place_1").contentEquals(library.picture("aki", "happy")))
        assertTrue(streak != null)
        assertNull(library.picture("ankilope", "face"))
    }

    @Test
    fun `the higher version shows an official companion`() {
        val bundledPacks = bundled(index(3, emptyMap()), pack(version = 3), pack("ankilope", version = 3))
        val library = library(bundledPacks)
        library.installDownloaded(listOf(read(pack(version = 4)), read(pack("ankilope", version = 2))), index(4, emptyMap()).toByteArray())
        assertEquals(PackOrigin.DOWNLOADED, library.companion("aki")!!.origin)
        assertEquals(PackOrigin.BUNDLED, library.companion("ankilope")!!.origin)
        assertEquals(4, library.index()!!.version)
    }

    @Test
    fun `official companions follow the index order and imported ones come after`() {
        val akiPack = pack()
        val cowPack = pack("cow")
        val bundledPacks = bundled(index(1, linkedMapOf("cow" to cowPack, "aki" to akiPack)), akiPack, cowPack)
        val library = library(bundledPacks)
        library.import(pack("zebra").inputStream())
        library.import(pack("bee").inputStream())
        assertEquals(listOf("cow", "aki", "bee", "zebra"), library.companions().map { it.info.id })
        assertEquals(PackOrigin.IMPORTED, library.companion("bee")!!.origin)
    }

    @Test
    fun `an import may not take an official id`() {
        val library = library(bundled(index(1, mapOf("ankilope" to pack("ankilope"))), pack()))
        assertEquals(PackRefusal.RESERVED_ID, refusal { library.import(pack().inputStream()) })
        assertEquals(PackRefusal.RESERVED_ID, refusal { library.import(pack("ankilope").inputStream()) })
        assertEquals(listOf("aki"), library.companions().map { it.info.id })
    }

    @Test
    fun `an imported pack replaces the imported pack with its id and can be removed`() {
        val library = library()
        library.import(pack("bee", version = 1).inputStream())
        library.import(pack("bee", version = 7).inputStream())
        assertEquals(7, library.companion("bee")!!.info.version)
        library.removeImported("bee")
        assertNull(library.companion("bee"))
    }

    @Test
    fun `an interrupted replacement keeps the last complete pack`() {
        val root = folder.newFolder()
        val installed = FilePackFolder(root)
        installed.install(read(pack("bee", version = 1)))
        assertTrue(File(root, "bee").renameTo(File(root, ".bee.old")))
        assertTrue(File(root, ".bee.new").mkdir())

        val reopened = FilePackFolder(root)
        assertEquals(listOf("bee"), reopened.ids())
        val metadata = reopened.read("bee", PackFormat.PACK_INDEX)!!.decodeToString()
        assertEquals(1, PackInfo.parse(metadata, names).version)
        reopened.install(read(pack("bee", version = 2)))
        assertEquals(2, PackInfo.parse(reopened.read("bee", PackFormat.PACK_INDEX)!!.decodeToString(), names).version)
    }

    @Test
    fun `a failed download install refreshes the list of stored packs`() {
        val disk = FilePackFolder(folder.newFolder())
        val downloaded =
            object : WritablePackFolder by disk {
                override fun install(pack: Pack) {
                    if (pack.info.id == "cow") {
                        throw IOException("cannot write cow")
                    }
                    disk.install(pack)
                }
            }
        val library = AnkiquestPackLibrary(FilePackFolder(folder.newFolder()), downloaded, FilePackFolder(folder.newFolder()))
        val packs = mapOf("aki" to pack(), "cow" to pack("cow"))
        assertEquals(emptyList(), library.companions())

        assertFailsWith<IOException> {
            library.installDownloaded(packs.values.map(::read), index(1, packs).toByteArray())
        }
        assertEquals(listOf("aki"), library.companions().map { it.info.id })
        assertNull(library.index())
    }

    /** A [PackFetch] serving [assets] by URL, recording what was asked. */
    private class FakeFetch(
        val assets: Map<String, ByteArray>,
    ) : PackFetch {
        val asked = mutableListOf<String>()

        override fun get(
            url: String,
            limit: Long,
        ): ByteArray {
            asked += url
            return assets[url] ?: throw IOException("404 $url")
        }
    }

    private fun release(
        version: Int,
        packs: Map<String, ByteArray>,
        index: String = index(version, packs),
    ): FakeFetch =
        FakeFetch(
            packs.mapKeys { (id, _) -> AnkiquestPackDownload.assetUrl("art-$version", "pack-$id.zip") } +
                (AnkiquestPackDownload.LATEST_INDEX to index.toByteArray()),
        )

    @Test
    fun `newer packs are downloaded and installed`() {
        val library = library(bundled(index(1, emptyMap()), pack(version = 1)))
        val fetch = release(2, mapOf("aki" to pack(version = 2), "cow" to pack("cow", version = 2)))
        val result = AnkiquestPackDownload(library, fetch).update()
        assertEquals(PackUpdate.Updated(listOf("aki", "cow")), result)
        assertEquals(2, library.installedVersion("cow"))
        assertEquals(PackOrigin.DOWNLOADED, library.companion("aki")!!.origin)

        // A second check asks only for the index.
        fetch.asked.clear()
        assertIs<PackUpdate.UpToDate>(AnkiquestPackDownload(library, fetch).update())
        assertEquals(listOf(AnkiquestPackDownload.LATEST_INDEX), fetch.asked)
    }

    @Test
    fun `a pack above the archive size limit is refused before downloading it`() {
        val packs = mapOf("aki" to pack(version = 2))
        val oversized = JSONObject(index(2, packs))
        oversized.getJSONArray("companions").getJSONObject(0).put("size", PackFormat.MAX_ARCHIVE + 1)
        val fetch = release(2, packs, index = oversized.toString())
        val library = library()

        assertEquals(PackRefusal.TOO_LARGE, refusal { AnkiquestPackDownload(library, fetch).update() })
        assertEquals(listOf(AnkiquestPackDownload.LATEST_INDEX), fetch.asked)
        assertNull(library.companion("aki"))
    }

    @Test
    fun `a pack that does not match its checksum installs nothing`() {
        val library = library()
        val good = pack("aki", version = 2)
        val listed = mapOf("aki" to good, "cow" to pack("cow", version = 2))
        val fetch = release(2, listed)
        val tampered = FakeFetch(fetch.assets + (AnkiquestPackDownload.assetUrl("art-2", "pack-cow.zip") to pack("cow", version = 3)))
        assertEquals(PackRefusal.CHECKSUM, refusal { AnkiquestPackDownload(library, tampered).update() })
        assertTrue(library.companions().isEmpty())
        assertNull(library.index())
    }

    @Test
    fun `a pack that is not the one the index lists is refused`() {
        val other = pack("cow", version = 2)
        val fetch = release(2, mapOf("aki" to other), index(2, mapOf("aki" to other)))
        assertEquals(PackRefusal.BAD_INDEX, refusal { AnkiquestPackDownload(library(), fetch).update() })
    }

    /** The SHA-256 of [bytes] as lower-case hex. */
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** [fetch] serving its index as release [version]'s `packs.json` too, as a pinned download asks for it. */
    private fun pinnedRelease(
        version: Int,
        fetch: FakeFetch,
    ): FakeFetch {
        val index = fetch.assets.getValue(AnkiquestPackDownload.LATEST_INDEX)
        return FakeFetch(fetch.assets + (AnkiquestPackDownload.assetUrl("art-$version", PackFormat.RELEASE_INDEX) to index))
    }

    @Test
    fun `the pinned release is checked against the build's checksum`() {
        val library = library()
        val fetch = pinnedRelease(2, release(2, mapOf("aki" to pack(version = 2))))
        val index = fetch.assets.getValue(AnkiquestPackDownload.LATEST_INDEX)
        val result = AnkiquestPackDownload(library, fetch).update(PackSource.Pinned("art-2", sha256(index)))
        assertEquals(PackUpdate.Updated(listOf("aki")), result)
        assertEquals(AnkiquestPackDownload.assetUrl("art-2", PackFormat.RELEASE_INDEX), fetch.asked.first())
        assertTrue(AnkiquestPackDownload.LATEST_INDEX !in fetch.asked)
    }

    @Test
    fun `a pinned index that does not match the build's checksum installs nothing`() {
        val library = library()
        val fetch = pinnedRelease(2, release(2, mapOf("aki" to pack(version = 2))))
        val wrong = PackSource.Pinned("art-2", "0".repeat(64))
        assertEquals(PackRefusal.CHECKSUM, refusal { AnkiquestPackDownload(library, fetch).update(wrong) })
        assertTrue(library.companions().isEmpty())
        assertNull(library.index())
    }

    @Test
    fun `a pinned index of another release is refused`() {
        val fetch = release(3, mapOf("aki" to pack(version = 3)))
        val index = fetch.assets.getValue(AnkiquestPackDownload.LATEST_INDEX)
        val misplaced = FakeFetch(fetch.assets + (AnkiquestPackDownload.assetUrl("art-2", PackFormat.RELEASE_INDEX) to index))
        assertEquals(
            PackRefusal.BAD_INDEX,
            refusal { AnkiquestPackDownload(library(), misplaced).update(PackSource.Pinned("art-2", sha256(index))) },
        )
    }

    @Test
    fun `downloads go only to GitHub over HTTPS`() {
        val allowed = { url: String -> GithubPackFetch.allowed(url.toHttpUrl()) }
        assertTrue(allowed(AnkiquestPackDownload.LATEST_INDEX))
        assertTrue(allowed("https://release-assets.githubusercontent.com/x"))
        assertTrue(!allowed("http://github.com/AnkiQuest/AnkiQuest-Art/releases"))
        assertTrue(!allowed("https://github.com.example.org/x"))
        assertTrue(!allowed("https://example.org/x"))
        assertEquals(PackRefusal.BAD_INDEX, refusal { AnkiquestPackDownload.assetUrl("../evil", "pack-aki.zip") })
    }

    @Test
    fun `a broken installed pack is skipped`() {
        val root = folder.newFolder()
        val bundledPacks = FilePackFolder(root)
        bundledPacks.install(read(pack()))
        File(root, "aki/${PackFormat.PACK_INDEX}").writeText("{}")
        assertTrue(library(bundledPacks).companions().isEmpty())
    }
}
