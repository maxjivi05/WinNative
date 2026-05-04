package com.winlator.cmod.feature.stores.steam.linux

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Shared HTTP and hashing utilities for the Linux Steam bootstrap path.
 * Used by both the Steam client fetcher and the sniper-arm64 runtime
 * fetcher; both need streaming downloads with progress, SHA-256 verify,
 * and small text fetches.
 */
internal object SteamDownloadUtil {

    @Throws(IOException::class)
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Throws(IOException::class)
    fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} fetching $url")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Streams [url] to [dest]. The callback receives running totals on
     * each buffer write so the UI can drive a progress bar. Throws on
     * HTTP errors or if the response was truncated (when Content-Length
     * was reported and didn't match the bytes received).
     */
    @Throws(IOException::class)
    fun downloadFile(
        url: String,
        dest: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode} fetching $url")
            }
            val total = conn.contentLength.toLong()
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        downloaded += n
                        onProgress?.invoke(downloaded, total)
                    }
                }
            }
            if (total > 0 && dest.length() != total) {
                dest.delete()
                throw IOException("Incomplete download for $url: ${dest.length()}/$total")
            }
        } finally {
            conn.disconnect()
        }
    }
}
