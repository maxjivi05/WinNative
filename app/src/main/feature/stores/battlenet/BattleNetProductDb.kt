package com.winlator.cmod.feature.stores.battlenet

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BattleNetInstall(
    val uid: String,
    val product: String,
    val path: String,
    val installed: Boolean,
    val playable: Boolean,
    val complete: Boolean,
    val progress: Double?,
    val totalBytes: Long,
    val remainingBytes: Long,
) {
    val downloadedBytes: Long get() = (totalBytes - remainingBytes).coerceIn(0, totalBytes)
}

object BattleNetProductDb {
    const val MAX_BYTES = 16 * 1024 * 1024

    fun parse(bytes: ByteArray): List<BattleNetInstall> {
        require(bytes.size <= MAX_BYTES) { "Battle.net database is too large" }
        return fields(bytes).filter { it.number == 1 }.map { entry ->
            val product = fields(entry.data())
            val settings = fields(product.message(3))
            val cached = fields(product.message(4))
            val base = fields(cached.message(1))
            val update = fields(cached.message(4))
            val total = update.integer(4).coerceAtLeast(0)
            val remaining = update.integer(5).coerceIn(0, total)
            val progress = update.firstOrNull { it.number == 2 && it.wire == 1 }?.let {
                java.lang.Double.longBitsToDouble(it.numeric).takeIf { value -> value.isFinite() && value in 0.0..1.0 }
            }
            BattleNetInstall(
                product.text(1), product.text(2), settings.text(1),
                base.integer(1) == 1L, base.integer(2) == 1L, base.integer(3) == 1L,
                progress, total, remaining,
            )
        }.filter { it.product.isNotBlank() && it.path.isNotBlank() }
    }

    internal fun registerNative(bytes: ByteArray, product: String, path: String, buildKey: String, version: String): ByteArray {
        require(product in setOf("wow", "wow_classic", "wow_classic_era"))
        require(buildKey.matches(Regex("[a-f0-9]{32}")))
        require(path.startsWith("C:\\WinNative\\Battle.net\\installed\\") && !path.contains('\n') && !path.contains('\r'))
        val existing = parse(bytes).filter { it.product == product }
        if (existing.isNotEmpty()) {
            require(existing.size == 1 && existing.single().path == path) { "An existing Battle.net installation was preserved." }
            return bytes
        }
        fun number(value: Long): ByteArray {
            var remaining = value
            val out = java.io.ByteArrayOutputStream()
            do {
                val low = (remaining and 127).toInt()
                remaining = remaining ushr 7
                out.write(low or if (remaining != 0L) 128 else 0)
            } while (remaining != 0L)
            return out.toByteArray()
        }
        fun message(n: Int, value: ByteArray) = number((n * 8 + 2).toLong()) + number(value.size.toLong()) + value
        fun text(n: Int, value: String) = message(n, value.toByteArray(Charsets.UTF_8))
        fun integer(n: Int, value: Long) = number((n * 8).toLong()) + number(value)
        val flavor = when (product) { "wow" -> "_retail_"; "wow_classic" -> "_classic_"; else -> "_classic_era_" }
        val settings = text(1, path) + text(2, "us") + integer(5, 3) + text(6, "enUS") + text(7, "enUS") +
            message(8, text(1, "enUS") + integer(2, 3)) + text(11, "USA") + text(12, "US") + text(13, flavor)
        val base = integer(1, 1) + integer(2, 1) + integer(3, 1) + text(7, version) + text(12, buildKey) + text(14, buildKey)
        val entry = text(1, product) + text(2, product) + message(3, settings) + message(4, message(1, base)) + text(6, "wow")
        return (bytes + message(1, entry)).also { require(it.size <= MAX_BYTES); parse(it) }
    }

    internal fun nativeRecord(bytes: ByteArray): ByteArray = fields(bytes).single { it.number == 1 }.data()

    private data class Field(val number: Int, val wire: Int, val numeric: Long = 0, val bytes: ByteArray? = null) {
        fun data(): ByteArray = requireNotNull(bytes) { "Invalid Battle.net database field" }
    }

    private fun List<Field>.message(number: Int): ByteArray = firstOrNull { it.number == number && it.wire == 2 }?.data() ?: byteArrayOf()
    private fun List<Field>.text(number: Int): String = message(number).toString(Charsets.UTF_8)
    private fun List<Field>.integer(number: Int): Long = firstOrNull { it.number == number && it.wire == 0 }?.numeric ?: 0

    private fun fields(bytes: ByteArray): List<Field> {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun varint(): Long {
            var value = 0L
            for (index in 0..9) {
                require(input.hasRemaining()) { "Truncated Battle.net database" }
                val next = input.get().toInt() and 255
                require(index != 9 || next <= 1) { "Invalid Battle.net database integer" }
                value = value or ((next and 127).toLong() shl (index * 7))
                if (next and 128 == 0) return value
            }
            error("Invalid Battle.net database integer")
        }
        val result = mutableListOf<Field>()
        while (input.hasRemaining()) {
            require(result.size < 100_000) { "Too many Battle.net database fields" }
            val tag = varint()
            require(tag in 1..0xffffffffL && tag ushr 3 > 0) { "Invalid Battle.net database tag" }
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            result += when (wire) {
                0 -> Field(number, wire, varint())
                1 -> {
                    require(input.remaining() >= 8) { "Truncated Battle.net database double" }
                    Field(number, wire, input.long)
                }
                2 -> {
                    val size = varint()
                    require(size in 0..input.remaining().toLong()) { "Invalid Battle.net database length" }
                    Field(number, wire, bytes = ByteArray(size.toInt()).also { input.get(it) })
                }
                5 -> {
                    require(input.remaining() >= 4) { "Truncated Battle.net database integer" }
                    Field(number, wire, input.int.toLong())
                }
                else -> throw IllegalArgumentException("Unsupported Battle.net database wire type")
            }
        }
        return result
    }
}
