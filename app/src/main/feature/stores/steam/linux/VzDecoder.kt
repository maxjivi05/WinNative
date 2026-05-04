package com.winlator.cmod.feature.stores.steam.linux

import org.tukaani.xz.LZMAInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Decoder for Valve's `.zip.vz` wrapper.
 *
 * Layout (verified against live `.zip.vz` blobs from media.steampowered.com):
 *   offset 0:    magic "VZa" (3 bytes)
 *   offset 3:    creation timestamp (uint32 LE) — informational
 *   offset 7:    LZMA properties byte (lc/lp/pb encoded)
 *   offset 8:    dictionary size (uint32 LE)
 *   offset 12:   raw LZMA1 stream (no XZ header, no end-marker)
 *   last 10 B:   crc32 (uint32 LE) || uncompressed size (uint32 LE) || "zv"
 *
 * The CRC32 in the trailer is computed over the *uncompressed* payload.
 * `uncompressed size` is required up front to drive `LZMAInputStream`
 * since the raw LZMA1 stream has no end-of-stream marker.
 */
internal object VzDecoder {

    private const val HEADER_SIZE = 12
    private const val FOOTER_SIZE = 10

    @Throws(IOException::class)
    fun decode(input: File, output: File) {
        val total = input.length()
        if (total < HEADER_SIZE + FOOTER_SIZE) {
            throw IOException("VZ blob too small: $total bytes")
        }

        val header = ByteArray(HEADER_SIZE)
        val footer = ByteArray(FOOTER_SIZE)
        RandomAccessFile(input, "r").use { raf ->
            raf.readFully(header)
            raf.seek(total - FOOTER_SIZE)
            raf.readFully(footer)
        }

        if (header[0] != 'V'.code.toByte() ||
            header[1] != 'Z'.code.toByte() ||
            header[2] != 'a'.code.toByte()
        ) {
            throw IOException("VZ magic mismatch")
        }
        if (footer[8] != 'z'.code.toByte() || footer[9] != 'v'.code.toByte()) {
            throw IOException("VZ trailer magic mismatch")
        }

        val propsByte = header[7]
        val dictSize = readUInt32Le(header, 8)
        val expectedCrc = readUInt32Le(footer, 0).toLong() and 0xFFFFFFFFL
        val expectedSize = readUInt32Le(footer, 4).toLong() and 0xFFFFFFFFL

        val payloadLen = total - HEADER_SIZE - FOOTER_SIZE
        if (payloadLen <= 0) throw IOException("VZ payload empty")

        // Stream the payload through LZMAInputStream — never buffer the whole
        // .vz blob in memory (selected packages can exceed 90 MB).
        input.inputStream().buffered(64 * 1024).use { fis ->
            var skipped = 0L
            while (skipped < HEADER_SIZE) {
                val n = fis.skip(HEADER_SIZE.toLong() - skipped)
                if (n <= 0) throw IOException("VZ truncated while skipping header")
                skipped += n
            }
            val bounded = BoundedInputStream(fis, payloadLen)
            LZMAInputStream(bounded, expectedSize, propsByte, dictSize).use { dec ->
                // Steam's `.zip.vz` LZMA1 streams carry BOTH a known
                // uncompressed size and an end-of-payload marker. xz-java's
                // raw-LZMA1 constructor defaults to strict mode, where seeing
                // an EOPM with a known size is treated as corruption (the
                // `.lzma`-file constructor sets this flag automatically; the
                // raw constructor doesn't). Without this, every Steam VZ
                // blob fails with `CorruptedInputException` at the EOS marker.
                dec.enableRelaxedEndCondition()
                output.outputStream().buffered(64 * 1024).use { out ->
                    val crc = CRC32()
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = dec.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        crc.update(buf, 0, n)
                        written += n
                    }
                    if (written != expectedSize) {
                        throw IOException("VZ size mismatch: expected $expectedSize, got $written")
                    }
                    if (crc.value != expectedCrc) {
                        throw IOException(
                            "VZ CRC mismatch: expected ${expectedCrc.toString(16)}, got ${crc.value.toString(16)}",
                        )
                    }
                }
            }
        }
    }

    private fun readUInt32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** Caps how many bytes LZMAInputStream can pull from the underlying file. */
    private class BoundedInputStream(input: InputStream, private var remaining: Long) :
        FilterInputStream(input) {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = `in`.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = `in`.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }

        override fun skip(n: Long): Long {
            val toSkip = minOf(n, remaining)
            val skipped = `in`.skip(toSkip)
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = minOf(`in`.available().toLong(), remaining).toInt()
    }
}
