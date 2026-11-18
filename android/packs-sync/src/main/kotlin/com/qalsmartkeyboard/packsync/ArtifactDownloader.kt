package com.qalsmartkeyboard.packsync

import com.qalsmartkeyboard.api.TransportConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A download that did not produce a usable file. [dest] has been deleted. */
class DownloadException(val reason: FailReason, cause: Throwable? = null) : IOException(reason.name, cause)

/** Fetches one artifact to a file. */
fun interface ArtifactDownloader {
    /**
     * Streams the artifact to [dest] (created or overwritten). [expectedBytes] is the container size
     * from discovery (null: unknown), [maxBytes] the hard cap. Throws [DownloadException].
     */
    fun download(url: String, dest: File, expectedBytes: Long?, maxBytes: Long)
}

/**
 * OkHttp downloader. Uses its own client with no API interceptors: the bearer token and client
 * headers must never go to the CDN host, and a signed `artifactUrl` needs nothing else. HTTPS
 * only (cleartext only for the local stack hosts, like [TransportConfig]); no resume.
 *
 * The file is written through [openOut] so tests can simulate a full disk.
 */
class HttpArtifactDownloader(
    private val client: OkHttpClient = defaultClient(),
    private val openOut: (File) -> OutputStream = { FileOutputStream(it) },
) : ArtifactDownloader {

    override fun download(url: String, dest: File, expectedBytes: Long?, maxBytes: Long) {
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || !(parsed.isHttps || parsed.host in TransportConfig.LOCAL_HOSTS)) {
            throw DownloadException(FailReason.URL_REJECTED)
        }
        val limit = minOf(maxBytes, expectedBytes ?: Long.MAX_VALUE)
        try {
            client.newCall(Request.Builder().url(parsed).get().build()).execute().use { response ->
                if (response.code != 200) throw DownloadException(FailReason.HTTP_STATUS)
                val body = response.body ?: throw DownloadException(FailReason.HTTP_STATUS)
                val declared = body.contentLength()
                if (declared > limit) throw DownloadException(FailReason.TOO_LARGE)
                var total = 0L
                val out = try {
                    openOut(dest)
                } catch (e: IOException) {
                    throw DownloadException(FailReason.DISK_WRITE, e)
                }
                try {
                    val input = body.byteStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = try {
                            input.read(buf)
                        } catch (e: IOException) {
                            throw DownloadException(FailReason.NETWORK, e)
                        }
                        if (n < 0) break
                        total += n
                        if (total > limit) throw DownloadException(FailReason.TOO_LARGE)
                        try {
                            out.write(buf, 0, n)
                        } catch (e: IOException) {
                            throw DownloadException(FailReason.DISK_WRITE, e)
                        }
                    }
                    try {
                        out.close() // flush errors (disk full) show up here
                    } catch (e: IOException) {
                        throw DownloadException(FailReason.DISK_WRITE, e)
                    }
                } finally {
                    try {
                        out.close()
                    } catch (_: IOException) {
                    }
                }
                // Fewer bytes than announced (server cut the stream, or a lying length): never use it.
                if ((expectedBytes != null && total != expectedBytes) || (declared >= 0 && total != declared)) {
                    throw DownloadException(FailReason.TRUNCATED)
                }
            }
        } catch (e: DownloadException) {
            dest.delete()
            throw e
        } catch (e: IOException) {
            dest.delete()
            throw DownloadException(FailReason.NETWORK, e)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(false) // never https -> http
            .build()
    }
}
