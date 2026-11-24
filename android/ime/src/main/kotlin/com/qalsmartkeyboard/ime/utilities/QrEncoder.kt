package com.qalsmartkeyboard.ime.utilities

/** A QR symbol: [size] x [size] modules, `true` = dark. No quiet zone is included. */
class QrMatrix internal constructor(val size: Int, val version: Int, val mask: Int, private val modules: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y * size + x]
}

/** The text does not fit the largest version this encoder supports. */
class QrTooLongException : Exception("text is too long for a QR code")

/**
 * A small self-contained QR Code (Model 2) encoder: byte mode, error correction level M, versions 1 to 10 (up to
 * [MAX_BYTES] bytes of UTF-8), the eight masks scored with the standard penalty rules. No dependencies, no I/O.
 *
 * The tables are those of ISO/IEC 18004 for level M: per version the error-correction codewords per block and the block
 * layout (blocks of `dataShort` codewords, then blocks of `dataShort + 1`).
 */
object QrEncoder {
    const val MAX_VERSION = 10

    /** Largest payload in bytes (version 10, level M). */
    const val MAX_BYTES = 213

    private class Spec(val ecPerBlock: Int, val shortBlocks: Int, val dataShort: Int, val longBlocks: Int) {
        val blocks get() = shortBlocks + longBlocks
        val dataCodewords get() = shortBlocks * dataShort + longBlocks * (dataShort + 1)
        val totalCodewords get() = dataCodewords + blocks * ecPerBlock
    }

    // Index 0 = version 1. Level M.
    private val SPECS = arrayOf(
        Spec(10, 1, 16, 0),
        Spec(16, 1, 28, 0),
        Spec(26, 1, 44, 0),
        Spec(18, 2, 32, 0),
        Spec(24, 2, 43, 0),
        Spec(16, 4, 27, 0),
        Spec(18, 4, 31, 0),
        Spec(22, 2, 38, 2),
        Spec(22, 3, 36, 2),
        Spec(26, 4, 43, 1),
    )

    private val ALIGNMENT = arrayOf(
        intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
        intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
    )

    private fun dataCapacityBytes(version: Int): Int {
        val countBits = 8 // character count bits for byte mode, versions 1 to 9 (16 from version 10)
        val bits = SPECS[version - 1].dataCodewords * 8 - 4 - if (version >= 10) 16 else countBits
        return bits / 8
    }

    /** Encodes [text] as UTF-8. Throws [QrTooLongException] beyond [MAX_BYTES] bytes. */
    fun encode(text: String): QrMatrix = encode(text.toByteArray(Charsets.UTF_8))

    fun encode(data: ByteArray): QrMatrix = encode(data, null)

    /** [forceMask] (0..7) skips the penalty scoring; tests use it to compare each mask with reference output. */
    internal fun encode(data: ByteArray, forceMask: Int?): QrMatrix {
        val version = (1..MAX_VERSION).firstOrNull { dataCapacityBytes(it) >= data.size } ?: throw QrTooLongException()
        val spec = SPECS[version - 1]
        val codewords = interleave(dataCodewords(data, version, spec), spec)
        val size = version * 4 + 17
        val grid = Grid(size, version)
        grid.drawFunctionPatterns()
        grid.drawCodewords(codewords)
        var best = -1
        var bestScore = Int.MAX_VALUE
        for (m in if (forceMask != null) listOf(forceMask) else (0..7).toList()) {
            grid.applyMask(m)
            grid.drawFormat(m)
            val score = grid.penalty()
            if (score < bestScore) {
                bestScore = score
                best = m
            }
            grid.applyMask(m) // undo (xor)
        }
        grid.applyMask(best)
        grid.drawFormat(best)
        return QrMatrix(size, version, best, grid.modules.copyOf())
    }

    // ---- data codewords ------------------------------------------------------------------------------------------

    private fun dataCodewords(data: ByteArray, version: Int, spec: Spec): ByteArray {
        val bits = ArrayList<Boolean>()
        fun put(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 == 1)
        }
        put(0b0100, 4) // byte mode
        put(data.size, if (version >= 10) 16 else 8)
        for (b in data) put(b.toInt() and 0xFF, 8)
        val capacityBits = spec.dataCodewords * 8
        repeat(minOf(4, capacityBits - bits.size)) { bits.add(false) }
        while (bits.size % 8 != 0) bits.add(false)
        val out = ByteArray(spec.dataCodewords)
        for (i in 0 until bits.size / 8) {
            var v = 0
            for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
            out[i] = v.toByte()
        }
        var pad = 0xEC
        for (i in bits.size / 8 until out.size) {
            out[i] = pad.toByte()
            pad = pad xor (0xEC xor 0x11)
        }
        return out
    }

    private fun interleave(data: ByteArray, spec: Spec): ByteArray {
        val blocks = ArrayList<ByteArray>()
        val ecs = ArrayList<ByteArray>()
        var pos = 0
        for (b in 0 until spec.blocks) {
            val len = spec.dataShort + if (b >= spec.shortBlocks) 1 else 0
            val block = data.copyOfRange(pos, pos + len)
            pos += len
            blocks.add(block)
            ecs.add(reedSolomon(block, spec.ecPerBlock))
        }
        val out = ByteArray(spec.totalCodewords)
        var o = 0
        for (i in 0..spec.dataShort) for (b in blocks) if (i < b.size) out[o++] = b[i]
        for (i in 0 until spec.ecPerBlock) for (e in ecs) out[o++] = e[i]
        return out
    }

    // ---- Reed-Solomon over GF(256), polynomial 0x11D ---------------------------------------------------------------

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** The [ecLen] error-correction codewords of [data] (remainder of data * x^ecLen divided by the generator). */
    internal fun reedSolomon(data: ByteArray, ecLen: Int): ByteArray {
        val gen = IntArray(ecLen)
        gen[ecLen - 1] = 1
        var root = 1
        for (i in 0 until ecLen) {
            for (j in 0 until ecLen) {
                gen[j] = gfMul(gen[j], root)
                if (j + 1 < ecLen) gen[j] = gen[j] xor gen[j + 1]
            }
            root = gfMul(root, 2)
        }
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            for (i in 0 until ecLen - 1) rem[i] = rem[i + 1]
            rem[ecLen - 1] = 0
            for (i in 0 until ecLen) rem[i] = rem[i] xor gfMul(gen[i], factor)
        }
        return ByteArray(ecLen) { rem[it].toByte() }
    }

    // ---- module grid ---------------------------------------------------------------------------------------------

    private class Grid(val size: Int, val version: Int) {
        val modules = BooleanArray(size * size)
        private val function = BooleanArray(size * size)

        private fun set(x: Int, y: Int, dark: Boolean) {
            modules[y * size + x] = dark
            function[y * size + x] = true
        }

        private fun dark(x: Int, y: Int) = modules[y * size + x]

        fun drawFunctionPatterns() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val pos = ALIGNMENT[version - 1]
            for (a in pos.indices) for (b in pos.indices) {
                val skip = (a == 0 && b == 0) || (a == 0 && b == pos.size - 1) || (a == pos.size - 1 && b == 0)
                if (!skip) alignment(pos[a], pos[b])
            }
            drawFormat(0) // reserve the area; rewritten once the mask is known
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, dist != 2 && dist != 4)
            }
        }

        private fun alignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        /** 15 format bits for level M (indicator 00) and [mask], BCH(15,5) with generator 0x537, xor 0x5412. */
        fun drawFormat(mask: Int) {
            val bits = formatBits(mask)
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // the always-dark module
        }

        private fun drawVersion() {
            if (version < 7) return
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val b = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b)
                set(c, a, b)
            }
        }

        /** Zigzag placement of the codeword bits into the free modules; leftover modules (remainder bits) stay light. */
        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!function[y * size + x] && i < data.size * 8) {
                            modules[y * size + x] = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) == 1
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (!function[y * size + x] && maskBit(mask, x, y)) modules[y * size + x] = !modules[y * size + x]
            }
        }

        fun penalty(): Int {
            var result = 0
            // Rule 1: runs of five or more in a row or column; rule 3: finder-like 1:1:3:1:1 with four light on a side.
            for (horizontal in listOf(true, false)) {
                for (a in 0 until size) {
                    val line = BooleanArray(size) { if (horizontal) dark(it, a) else dark(a, it) }
                    var run = 1
                    for (i in 1 until size) {
                        if (line[i] == line[i - 1]) {
                            run++
                        } else {
                            if (run >= 5) result += 3 + (run - 5)
                            run = 1
                        }
                    }
                    if (run >= 5) result += 3 + (run - 5)
                    // 1011101 with four light modules before or after (beyond the symbol counts as light).
                    for (s in 0..size - 7) {
                        if ((0 until 7).any { line[s + it] != FINDER_LIKE[it] }) continue
                        if ((1..4).all { s - it < 0 || !line[s - it] }) result += 40
                        if ((7..10).all { s + it >= size || !line[s + it] }) result += 40
                    }
                }
            }
            // Rule 2: 2x2 blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark(x, y)
                if (c == dark(x + 1, y) && c == dark(x, y + 1) && c == dark(x + 1, y + 1)) result += 3
            }
            // Rule 4: balance of dark modules.
            val total = size * size
            val darkCount = modules.count { it }
            val k = (Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1
            result += k * 10
            return result
        }
    }

    private val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true)

    internal fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    internal fun formatBits(mask: Int): Int {
        val data = mask // level M = 00 in the top two bits of the five data bits
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        return ((data shl 10) or rem) xor 0x5412
    }

    internal fun versionBits(version: Int): Int {
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        return (version shl 12) or rem
    }
}
