package com.example.rcgallery.data.douyin

import java.security.MessageDigest
import java.util.Base64

/**
 * X-Bogus signature generator for Douyin API.
 *
 * Ported from f2's Python xbogus.py by JohnserfSeed.
 * Generates the X-Bogus request signature required by Douyin's Web API.
 */
class XBogus(private val userAgent: String) {

    private val charTable = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="
    private val uaKey = byteArrayOf(0, 1, 12)

    /** Hex char to value mapping (0-15 for '0'-'9', 'A'-'F', else 255 meaning invalid). */
    private val hexMap = IntArray(128) { -1 }.also { map ->
        // '0'-'9' => 0-9
        for (i in 0..9) map['0'.code + i] = i
        // 'A'-'F' => 10-15
        for (i in 0..5) map['A'.code + i] = 10 + i
    }

    /**
     * If string length > 32: treat as raw bytes (ord of each char).
     * Otherwise: decode hex pairs to bytes.
     */
    private fun md5StrToArray(input: String): IntArray {
        if (input.length > 32) {
            return IntArray(input.length) { input[it].code and 0xFF }
        }
        val result = IntArray(input.length / 2)
        for (i in result.indices) {
            val hi = hexMap[input[i * 2].code]
            val lo = hexMap[input[i * 2 + 1].code]
            result[i] = (hi shl 4) or lo
        }
        return result
    }

    private fun md5(input: IntArray): String {
        val bytes = input.map { it.toByte() }.toByteArray()
        return md5(bytes)
    }

    private fun md5(input: String): String {
        val bytes = input.encodeToByteArray()
        return md5(bytes)
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Multi-round MD5 encryption of URL params.
     * md5_str_to_array(md5(md5_str_to_array(md5(url_params))))
     */
    private fun md5Encrypt(urlParams: String): IntArray {
        val step1 = md5(urlParams)
        val step2 = md5StrToArray(step1)
        val step3 = md5(step2)
        return md5StrToArray(step3)
    }

    /** RC4 encryption. */
    private fun rc4Encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0..255) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }

        val result = ByteArray(data.size)
        var si = 0
        var sj = 0
        for (idx in data.indices) {
            si = (si + 1) and 0xFF
            sj = (sj + s[si]) and 0xFF
            val tmp = s[si]
            s[si] = s[sj]
            s[sj] = tmp
            val k = s[(s[si] + s[sj]) and 0xFF]
            result[idx] = (data[idx].toInt() xor k).toByte()
        }
        return result
    }

    /**
     * Encode multiple ints into a ISO-8859-1 string segment.
     *
     * Reorders args to match Python's encoding_conversion:
     * y = [a, int(i), b, _, c, x, e, u, d, s, t, l, f, v, r, h, n, p, o]
     * where Python signature is (a, b, c, e, d, t, f, r, n, o, i, _, x, u, s, l, v, h, p)
     */
    private fun encodingConversion(vararg args: Int): String {
        // args = [a, b, c, e, d, t, f, r, n, o, i, _, x, u, s, l, v, h, p]
        // -> reorder to [a, int(i), b, _, c, x, e, u, d, s, t, l, f, v, r, h, n, p, o]
        val bytes = ByteArray(19).also {
            it[0] = args[0].toByte()            // a = args[0]
            it[1] = args[10].toInt().toByte()    // int(i) = int(args[10])
            it[2] = args[1].toByte()             // b = args[1]
            it[3] = args[11].toByte()            // _ = args[11]
            it[4] = args[2].toByte()             // c = args[2]
            it[5] = args[12].toByte()            // x = args[12]
            it[6] = args[3].toByte()             // e = args[3]
            it[7] = args[13].toByte()            // u = args[13]
            it[8] = args[4].toByte()             // d = args[4]
            it[9] = args[14].toByte()            // s = args[14]
            it[10] = args[5].toByte()            // t = args[5]
            it[11] = args[15].toByte()           // l = args[15]
            it[12] = args[6].toByte()            // f = args[6]
            it[13] = args[16].toByte()           // v = args[16]
            it[14] = args[7].toByte()            // r = args[7]
            it[15] = args[17].toByte()           // h = args[17]
            it[16] = args[8].toByte()            // n = args[8]
            it[17] = args[18].toByte()           // p = args[18]
            it[18] = args[9].toByte()            // o = args[9]
        }
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun encodingConversion2(a: Int, b: Int, c: String): String {
        return "${a.toChar()}${b.toChar()}$c"
    }

    /**
     * Encode 3 bytes into 4 characters using the custom base64-like table.
     */
    private fun calculation(a1: Int, a2: Int, a3: Int): String {
        val x1 = (a1 and 0xFF) shl 16
        val x2 = (a2 and 0xFF) shl 8
        val x3 = x1 or x2 or (a3 and 0xFF)
        return buildString {
            append(charTable[(x3 and 0xFC0000) shr 18])
            append(charTable[(x3 and 0x3F000) shr 12])
            append(charTable[(x3 and 0xFC0) shr 6])
            append(charTable[x3 and 0x3F])
        }
    }

    /**
     * Generate X-Bogus signature for the given URL parameters string.
     *
     * @param urlParams The URL query parameters as a key=value&key=value... string.
     * @return Triple of:
     *   - full params string with X-Bogus appended
     *   - the X-Bogus value
     *   - the user agent used
     */
    fun getXBogus(urlParams: String): Triple<String, String, String> {
        // Step 1: RC4 encrypt UA with uaKey, base64, then MD5
        val uaBytes = userAgent.toByteArray(Charsets.ISO_8859_1)
        val uaRc4 = rc4Encrypt(uaKey, uaBytes)
        val uaB64 = Base64.getEncoder().encodeToString(uaRc4)
        val uaMd5 = md5(uaB64)
        val array1 = md5StrToArray(uaMd5)

        // Step 2: MD5 of constant string
        val constMd5 = md5(md5StrToArray("d41d8cd98f00b204e9800998ecf8427e"))
        val array2 = md5StrToArray(constMd5)

        // Step 3: MD5-encrypt the URL params
        val urlParamsArray = md5Encrypt(urlParams)

        // Step 4: Build new array with specific indices + timestamp + constant
        val timer = (System.currentTimeMillis() / 1000).toInt()
        val ct = 536919696

        val newArray: MutableList<Number> = mutableListOf(
            64, 0.00390625, 1, 12,
            urlParamsArray[14], urlParamsArray[15],
            array2[14], array2[15],
            array1[14], array1[15],
            timer shr 24 and 0xFF, timer shr 16 and 0xFF,
            timer shr 8 and 0xFF, timer and 0xFF,
            ct shr 24 and 0xFF, ct shr 16 and 0xFF,
            ct shr 8 and 0xFF, ct and 0xFF
        )

        // Step 5: XOR all elements
        var xorResult = newArray[0].toInt()
        for (i in 1 until newArray.size) {
            xorResult = xorResult xor newArray[i].toInt()
        }
        newArray.add(xorResult)

        // Step 6: Split into even/odd indices then merge
        val array3 = newArray.filterIndexed { index, _ -> index % 2 == 0 }
        val array4 = newArray.filterIndexed { index, _ -> index % 2 == 1 }
        val mergeArray: List<Int> = (array3 + array4).map { it.toInt() }

        // Step 7: Encoding conversion + RC4 encrypt + encoding_conversion2
        val garbledInput = encodingConversion(*mergeArray.toIntArray())
        val rc4Key = byteArrayOf(0xFF.toByte())
        val garbledBytes = garbledInput.toByteArray(Charsets.ISO_8859_1)
        val rc4Result = rc4Encrypt(rc4Key, garbledBytes)
        val rc4String = String(rc4Result, Charsets.ISO_8859_1)
        val garbledCode = encodingConversion2(2, 255, rc4String)

        // Step 8: Final character mapping (3 bytes -> 4 chars)
        val xb = StringBuilder()
        val garbledBytes2 = garbledCode.toByteArray(Charsets.ISO_8859_1)
        var idx = 0
        while (idx + 2 < garbledBytes2.size) {
            val a1 = garbledBytes2[idx].toInt() and 0xFF
            val a2 = garbledBytes2[idx + 1].toInt() and 0xFF
            val a3 = garbledBytes2[idx + 2].toInt() and 0xFF
            xb.append(calculation(a1, a2, a3))
            idx += 3
        }

        val xbValue = xb.toString()
        val fullParams = "$urlParams&X-Bogus=$xbValue"
        return Triple(fullParams, xbValue, userAgent)
    }
}
