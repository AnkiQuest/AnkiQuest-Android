// SPDX-License-Identifier: AGPL-3.0-or-later

package com.ichi2.anki.ankiquest

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest

/** Fetches a URL's bytes, at most [limit]; the network behind the downloader. Blocking. */
internal fun interface PackFetch {
    fun get(
        url: String,
        limit: Long,
    ): ByteArray
}

/** The AnkiQuest-Art release a download takes its packs from. */
internal sealed interface PackSource {
    /** The release the build pins (`ankiquest-art.json`): its `packs.json` must have [sha256]. */
    data class Pinned(
        val tag: String,
        val sha256: String,
    ) : PackSource

    /** The latest release; its `packs.json` is taken over HTTPS from GitHub. */
    data object Latest : PackSource
}

/** What [AnkiquestPackDownload.update] did. */
internal sealed interface PackUpdate {
    /** Every pack of the latest release was installed already. */
    data object UpToDate : PackUpdate

    /** These companions' packs were installed or replaced. */
    data class Updated(
        val ids: List<String>,
    ) : PackUpdate
}

/**
 * Downloads the companion packs of an AnkiQuest-Art release ([PackSource]) that are newer than the
 * installed ones. Each pack must match the index's size and SHA-256 and pass the reader's checks
 * before any of them is installed.
 */
internal class AnkiquestPackDownload(
    private val library: AnkiquestPackLibrary,
    private val fetch: PackFetch,
) {
    /**
     * Brings the downloaded packs up to [source]. Throws [PackRefused] when the release breaks a
     * rule and [IOException] when the network fails; nothing is installed then. One update runs
     * at a time, so a second caller waits and then finds the packs installed.
     */
    @Synchronized
    fun update(source: PackSource = PackSource.Latest): PackUpdate {
        val bytes = index(source)
        val index = PackIndex.parse(bytes.decodeToString())

        // A pinned index is the one the build names, for the release it names.
        if (source is PackSource.Pinned && index.tag != source.tag) {
            throw PackRefused(PackRefusal.BAD_INDEX, "${PackFormat.RELEASE_INDEX} of ${index.tag}, pinned ${source.tag}")
        }

        // Only packs newer than what is installed, all checked before any is kept.
        val wanted = index.companions.filter { it.version > library.installedVersion(it.id) }
        if (wanted.isEmpty() && (library.index()?.version ?: 0) >= index.version) {
            return PackUpdate.UpToDate
        }
        val packs = wanted.map { download(index, it) }
        library.installDownloaded(packs, bytes)
        return PackUpdate.Updated(packs.map { it.info.id })
    }

    /** The bytes of [source]'s `packs.json`; a pinned one must match the build's SHA-256. */
    private fun index(source: PackSource): ByteArray =
        when (source) {
            PackSource.Latest -> fetch.get(LATEST_INDEX, MAX_INDEX)
            is PackSource.Pinned -> {
                val bytes = fetch.get(assetUrl(source.tag, PackFormat.RELEASE_INDEX), MAX_INDEX)
                if (sha256(bytes) != source.sha256) {
                    throw PackRefused(PackRefusal.CHECKSUM, "${PackFormat.RELEASE_INDEX} of ${source.tag}")
                }
                bytes
            }
        }

    /** [entry]'s pack, checked against the index and the pack rules. */
    private fun download(
        index: PackIndex,
        entry: PackEntry,
    ): Pack {
        val bytes = fetch.get(assetUrl(index.tag, entry.file), entry.size)
        if (bytes.size.toLong() != entry.size || sha256(bytes) != entry.sha256) {
            throw PackRefused(PackRefusal.CHECKSUM, entry.file)
        }
        val pack = AnkiquestPackReader.read(bytes.inputStream(), index.names)

        // The pack is the one the index promised.
        if (pack.info.id != entry.id || pack.info.version != entry.version) {
            throw PackRefused(PackRefusal.BAD_INDEX, "${entry.file} holds ${pack.info.id} ${pack.info.version}")
        }
        return pack
    }

    companion object {
        /** The Art repository's releases on GitHub. */
        private const val RELEASES = "https://github.com/AnkiQuest/AnkiQuest-Art/releases"

        /** The index of the latest release; GitHub redirects to the asset. */
        const val LATEST_INDEX = "$RELEASES/latest/download/${PackFormat.RELEASE_INDEX}"

        /** Largest index accepted. */
        const val MAX_INDEX = 1024L * 1024

        private val TAG = Regex("art-[1-9][0-9]{0,8}")

        /** The download URL of release [tag]'s asset [file]. */
        fun assetUrl(
            tag: String,
            file: String,
        ): String {
            if (!TAG.matches(tag)) {
                throw PackRefused(PackRefusal.BAD_INDEX, "tag $tag")
            }
            return "$RELEASES/download/$tag/$file"
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * [PackFetch] over HTTPS to GitHub only. Redirects are followed here, not by OkHttp, so every hop
 * is checked against [HOSTS].
 */
internal class GithubPackFetch(
    client: OkHttpClient,
) : PackFetch {
    private val client =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override fun get(
        url: String,
        limit: Long,
    ): ByteArray {
        var at = url.toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(at)) {
                throw PackRefused(PackRefusal.NOT_ALLOWED, at.host)
            }
            client.newCall(Request.Builder().url(at).build()).execute().use { response ->
                // Follow a redirect by hand, so the next hop is checked too.
                if (response.isRedirect) {
                    at = response.header("Location")?.let { at.resolve(it) } ?: throw IOException("redirect without a location")
                    return@repeat
                }
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} for $at")
                }

                // The body, refused past the limit whatever its length header says.
                val body = response.body
                if (body.contentLength() > limit) {
                    throw PackRefused(PackRefusal.TOO_LARGE, at.toString())
                }
                return try {
                    BoundedStream(body.byteStream(), limit).readBytes()
                } catch (e: TooLarge) {
                    throw PackRefused(PackRefusal.TOO_LARGE, e.message ?: at.toString())
                }
            }
        }
        throw IOException("too many redirects from $url")
    }

    companion object {
        /** GitHub and the hosts it redirects release downloads to. */
        val HOSTS = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        /** Most redirects followed for one download. */
        const val MAX_REDIRECTS = 5

        /** Whether a download may go to [url]. */
        fun allowed(url: HttpUrl) = url.isHttps && url.host in HOSTS
    }
}
