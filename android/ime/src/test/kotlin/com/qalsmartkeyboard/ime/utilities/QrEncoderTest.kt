package com.qalsmartkeyboard.ime.utilities

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.3: the QR encoder against known vectors, and a structural decode of every version: the matrix is read back the way a
 * scanner does (format bits, unmask, zigzag, de-interleave, Reed-Solomon syndromes, byte-mode payload), with its own
 * function-module map and its own GF(256), so it does not share code with the encoder.
 */
class QrEncoderTest {
    @Test fun reedSolomonKnownVector() {
        // ISO 18004 annex / thonky "HELLO WORLD" 1-M: 16 data codewords and their 10 error-correction codewords.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17).map { it.toByte() }.toByteArray()
        val ec = QrEncoder.reedSolomon(data, 10).map { it.toInt() and 0xFF }
        assertEquals(listOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23), ec)
    }

    @Test fun formatAndVersionInformationKnownValues() {
        assertEquals(0b101010000010010, QrEncoder.formatBits(0)) // level M, mask 0
        assertEquals(0b101000100100101, QrEncoder.formatBits(1)) // level M, mask 1
        assertEquals(0x07C94, QrEncoder.versionBits(7))
        assertEquals(0x085BC, QrEncoder.versionBits(8))
        assertEquals(0x09A99, QrEncoder.versionBits(9))
        assertEquals(0x0A4D3, QrEncoder.versionBits(10))
    }

    @Test fun symbolSizesFollowTheVersionAndCapacityBoundariesAreExact() {
        val capacity = intArrayOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)
        for (v in 1..10) {
            val full = QrEncoder.encode("a".repeat(capacity[v - 1]))
            assertEquals("v$v", v, full.version)
            assertEquals(v * 4 + 17, full.size)
            if (v < 10) assertEquals("v${v + 1}", v + 1, QrEncoder.encode("a".repeat(capacity[v - 1] + 1)).version)
        }
        assertEquals(1, QrEncoder.encode("").version)
    }

    @Test(expected = QrTooLongException::class) fun tooLongIsRejected() {
        QrEncoder.encode("a".repeat(QrEncoder.MAX_BYTES + 1))
    }

    @Test fun multibyteTextCountsBytes() {
        // 71 Ethiopic characters are 213 UTF-8 bytes: exactly version 10.
        assertEquals(10, QrEncoder.encode("ሰ".repeat(71)).version)
    }

    @Test fun finderTimingAndDarkModule() {
        val m = QrEncoder.encode("https://example.com")
        val s = m.size
        for ((ox, oy) in listOf(0 to 0, s - 7 to 0, 0 to s - 7)) {
            for (i in 0 until 7) {
                assertTrue(m[ox + i, oy] && m[ox + i, oy + 6] && m[ox, oy + i] && m[ox + 6, oy + i])
            }
            assertTrue(m[ox + 3, oy + 3])
            assertTrue(!m[ox + 1, oy + 1] && !m[ox + 5, oy + 5])
        }
        for (i in 8 until s - 8) assertEquals(i % 2 == 0, m[i, 6])
        assertTrue(m[8, s - 8])
    }

    @Test fun matchesTheReferenceMatricesForEveryMask() {
        for (v in QrReferenceVectors.all) {
            for (mask in 0..7) {
                val m = QrEncoder.encode(v.text.toByteArray(Charsets.UTF_8), forceMask = mask)
                assertEquals(v.version, m.version)
                assertEquals(v.size, m.size)
                val hex = v.maskHex[mask]
                for (i in 0 until m.size * m.size) {
                    val want = (Character.digit(hex[i / 4], 16) shr (3 - i % 4)) and 1 == 1
                    assertEquals("v${v.version} mask $mask module ${i % m.size},${i / m.size}", want, m[i % m.size, i / m.size])
                }
            }
        }
    }

    @Test fun deterministic() {
        val a = QrEncoder.encode("WIFI:T:WPA;S:home;P:secret;;")
        val b = QrEncoder.encode("WIFI:T:WPA;S:home;P:secret;;")
        assertEquals(a.mask, b.mask)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }

    @Test fun everyVersionDecodesBackToThePayload() {
        val rnd = Random(8)
        val lengths = listOf(0, 1, 7, 14, 15, 26, 27, 42, 43, 62, 63, 84, 85, 106, 107, 122, 123, 152, 153, 180, 181, 213)
        for (n in lengths) {
            val bytes = ByteArray(n) { (rnd.nextInt(94) + 32).toByte() } // printable ASCII
            val text = String(bytes, Charsets.US_ASCII)
            val decoded = decode(QrEncoder.encode(text))
            assertEquals("n=$n", text, decoded)
        }
        for (s in listOf("ሰላም ዓለም", "http://qal.example/a?b=c&d=é", "WIFI:T:WPA;S:Qal\\;Net;P:pa\\:ss;;")) {
            assertEquals(s, decode(QrEncoder.encode(s)))
        }
    }

    // ---- an independent reader ------------------------------------------------------------------------------------

    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x > 255) x = x xor 0x11D
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    private fun isFunction(x: Int, y: Int, v: Int, size: Int): Boolean {
        if (x == 6 || y == 6) return true // timing
        if (x < 9 && y < 9) return true // top-left finder, separator, format
        if (x >= size - 8 && y < 9) return true
        if (x < 9 && y >= size - 8) return true
        if (y == 8 && (x < 9 || x >= size - 8)) return true
        if (x == 8 && (y < 9 || y >= size - 8)) return true
        if (v >= 7 && ((x >= size - 11 && y < 6) || (y >= size - 11 && x < 6))) return true
        val pos = when (v) {
            1 -> intArrayOf()
            2 -> intArrayOf(6, 18); 3 -> intArrayOf(6, 22); 4 -> intArrayOf(6, 26); 5 -> intArrayOf(6, 30); 6 -> intArrayOf(6, 34)
            7 -> intArrayOf(6, 22, 38); 8 -> intArrayOf(6, 24, 42); 9 -> intArrayOf(6, 26, 46)
            else -> intArrayOf(6, 28, 50)
        }
        for (cx in pos) for (cy in pos) {
            val finderCorner = (cx == 6 && cy == 6) || (cx == 6 && cy == pos.last()) || (cx == pos.last() && cy == 6)
            if (!finderCorner && Math.abs(x - cx) <= 2 && Math.abs(y - cy) <= 2) return true
        }
        return false
    }

    private fun decode(m: QrMatrix): String {
        val s = m.size
        val v = m.version
        // Format information: the first copy, read as a scanner does, must be a valid BCH word for level M and the mask.
        var fmt = 0
        val order = listOf(8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8, 7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8)
        for ((i, p) in order.withIndex()) if (m[p.first, p.second]) fmt = fmt or (1 shl i)
        assertEquals(QrEncoder.formatBits(m.mask), fmt)
        // Second copy agrees.
        var fmt2 = 0
        for (i in 0..7) if (m[s - 1 - i, 8]) fmt2 = fmt2 or (1 shl i)
        for (i in 8..14) if (m[8, s - 15 + i]) fmt2 = fmt2 or (1 shl i)
        assertEquals(fmt, fmt2)
        // Zigzag read with the mask removed.
        val bits = ArrayList<Boolean>()
        var right = s - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until s) for (j in 0..1) {
                val x = right - j
                val y = if (((right + 1) and 2) == 0) s - 1 - vert else vert
                if (!isFunction(x, y, v, s)) {
                    val masked = QrEncoder.maskBit(m.mask, x, y)
                    bits.add(m[x, y] != masked)
                }
            }
            right -= 2
        }
        val specs = mapOf(
            1 to intArrayOf(10, 1, 16, 0), 2 to intArrayOf(16, 1, 28, 0), 3 to intArrayOf(26, 1, 44, 0), 4 to intArrayOf(18, 2, 32, 0),
            5 to intArrayOf(24, 2, 43, 0), 6 to intArrayOf(16, 4, 27, 0), 7 to intArrayOf(18, 4, 31, 0), 8 to intArrayOf(22, 2, 38, 2),
            9 to intArrayOf(22, 3, 36, 2), 10 to intArrayOf(26, 4, 43, 1),
        )
        val (ecLen, nShort, dShort, nLong) = specs.getValue(v).toList()
        val nBlocks = nShort + nLong
        val total = nShort * (dShort + ecLen) + nLong * (dShort + 1 + ecLen)
        val cw = IntArray(total) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
        // Remainder bits (after the codewords) are light.
        for (i in total * 8 until bits.size) assertTrue(!bits[i])
        // De-interleave.
        val blocks = List(nBlocks) { ArrayList<Int>() }
        var p = 0
        for (i in 0..dShort) for (b in 0 until nBlocks) {
            val len = dShort + if (b >= nShort) 1 else 0
            if (i < len) blocks[b].add(cw[p++])
        }
        for (i in 0 until ecLen) for (b in 0 until nBlocks) blocks[b].add(cw[p++])
        assertEquals(total, p)
        // Reed-Solomon syndromes S_i = c(alpha^i) are zero for a valid block.
        for (blk in blocks) for (i in 0 until ecLen) {
            var acc = 0
            for (c in blk) acc = mul(acc, exp[i]) xor c
            assertEquals("syndrome $i", 0, acc)
        }
        // Payload: byte mode.
        val data = blocks.flatMap { it.take(it.size - ecLen) }
        val dataBits = data.flatMap { c -> (7 downTo 0).map { (c shr it) and 1 == 1 } }
        fun read(at: Int, n: Int) = (0 until n).fold(0) { acc, k -> (acc shl 1) or (if (dataBits[at + k]) 1 else 0) }
        assertEquals(0b0100, read(0, 4))
        val countBits = if (v >= 10) 16 else 8
        val len = read(4, countBits)
        val out = ByteArray(len) { read(4 + countBits + it * 8, 8).toByte() }
        // Terminator then pad bytes 0xEC / 0x11 alternate.
        var at = (4 + countBits + len * 8 + 7) / 8
        var pad = 0xEC
        while (at < data.size) {
            assertEquals(pad, data[at++])
            pad = pad xor (0xEC xor 0x11)
        }
        return String(out, Charsets.UTF_8)
    }
}
