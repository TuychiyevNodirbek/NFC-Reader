package uz.rahmat.nfcreader

import android.nfc.tech.IsoDep
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.Random

// ─── Result ───────────────────────────────────────────────────────────────────

data class CardReadResult(
    val pan: String,        // e.g. "9860090101823160"
    val expiry: String,     // YYMM e.g. "2902"
    val scheme: String,     // HUMO / VISA / MASTERCARD / UZCARD / etc.
    val aid: String         // raw AID hex
)

sealed class CardReadError : Exception() {
    object NotSupported : CardReadError()
    object ReadFailed : CardReadError()
    object NoPanFound : CardReadError()
}

// ─── Public entry point ───────────────────────────────────────────────────────

object NfcCardReader {

    private const val TAG = "NfcCardReader"

    /**
     * Call from NFC tag discovered callback.
     * Throws [CardReadError] on failure.
     */
    fun read(isoDep: IsoDep): CardReadResult {
        isoDep.connect()
        return try {
            val provider = NfcProvider(isoDep)
            readCard(provider)
        } catch (e: Exception) {
            Log.e("NFC", "READ FAILED", e)
            throw e
        } finally {
            runCatching { isoDep.close() }
        }
    }

    private fun readCard(provider: NfcProvider): CardReadResult {
        val card = MutableCardData()

        // 1. Try PPSE first
        val ppseResp = provider.transceive(Apdu.select("325041592E5359532E4444463031".hexToBytes()))
        if (ResponseUtils.isOk(ppseResp)) {
            val aids = extractAidsFromPpse(ppseResp.stripStatus())
            for (aid in aids) {
                if (tryReadAid(provider, aid, card)) break
            }
        }

        // 2. Fallback to known AIDs
        if (card.pan.isEmpty()) {
            for (aid in KnownAids.list) {
                if (tryReadAid(provider, aid, card)) break
            }
        }

        if (card.pan.isEmpty()) throw CardReadError.NoPanFound

        return CardReadResult(
            pan = card.pan,
            expiry = card.expiry,
            scheme = detectScheme(card.aid),
            aid = card.aid
        )
    }

    private fun tryReadAid(provider: NfcProvider, aid: ByteArray, card: MutableCardData): Boolean {
        val aidHex = aid.toHex()
        Log.d(TAG, "SELECT AID: $aidHex")

        val selectResp = provider.transceive(Apdu.select(aid))
        if (!ResponseUtils.isOk(selectResp)) return false

        card.aid = aidHex

        // PDOL → GPO
        val pdol = TlvUtil.findTag(selectResp.stripStatus(), 0x9F38)
        val pdolData = buildMinimalPdolData(pdol)
        val gpoResp = provider.transceive(Apdu.gpo(pdolData))

        if (!ResponseUtils.isOk(gpoResp)) {
            // GPO failed → try direct ReadRecord fallback
            Log.d(TAG, "GPO failed → fallback ReadRecord")
            return tryFallbackRecord(provider, card)
        }

        val gpoData = gpoResp.stripStatus()

        // Try to get PAN from GPO response directly (some cards)
        extractPanExpiry(gpoData, card)
        if (card.pan.isNotEmpty()) return true

        // AFL → ReadRecords
        val afl = TlvUtil.findTag(gpoData, 0x94) ?: return tryFallbackRecord(provider, card)
        val aflEntries = AflParser.parse(afl)

        for (entry in aflEntries) {
            for (rec in entry.firstRecord..entry.lastRecord) {
                val p2 = (entry.sfi shl 3) or 4
                val resp = provider.transceive(Apdu.readRecord(rec, p2))
                if (ResponseUtils.isOk(resp)) {
                    extractPanExpiry(resp.stripStatus(), card)
                    if (card.pan.isNotEmpty()) return true
                }
            }
        }

        return card.pan.isNotEmpty()
    }

    private fun tryFallbackRecord(provider: NfcProvider, card: MutableCardData): Boolean {
        val resp = provider.transceive(Apdu.readRecord(1, 0x0C))
        if (ResponseUtils.isOk(resp)) {
            extractPanExpiry(resp.stripStatus(), card)
        }
        return card.pan.isNotEmpty()
    }

    // Extract PAN + expiry from any TLV payload
    private fun extractPanExpiry(payload: ByteArray, card: MutableCardData) {
        if (card.pan.isNotEmpty()) return

        // Try Track2 (57) first — most reliable
        val track2 = TlvUtil.findTag(payload, 0x57)
        if (track2 != null) {
            val track2Str = parseTrack2Bcd(track2)
            Log.d(TAG, "Track2: $track2Str")
            val dIdx = track2Str.indexOf('D')
            if (dIdx > 0) {
                card.pan = track2Str.take(dIdx)
                val after = track2Str.substring(dIdx + 1)
                if (after.length >= 4) card.expiry = after.take(4)
                return
            }
        }

        // Try PAN (5A)
        val panTag = TlvUtil.findTag(payload, 0x5A)
        if (panTag != null) {
            card.pan = panTag.toHex().trimEnd('F')
        }

        // Try Expiry (5F24)
        val expTag = TlvUtil.findTag(payload, 0x5F24)
        if (expTag != null && expTag.size >= 2) {
            card.expiry = "%02X%02X".format(expTag[0], expTag[1])
        }
    }

    private fun extractAidsFromPpse(ppseData: ByteArray): List<ByteArray> {
        val aids = mutableListOf<ByteArray>()

        // Find all Application Templates (0x61) and extract AID (0x4F)
        fun walk(data: ByteArray) {
            val tlvs = TlvUtil.parseAll(data)
            for (tlv in tlvs) {
                if (tlv.tag == 0x61) {
                    val aid = TlvUtil.findTag(tlv.value, 0x4F)
                    if (aid != null) aids.add(aid)
                }
                if ((tlv.tag and 0x20) != 0) walk(tlv.value)
            }
        }
        walk(ppseData)
        return aids
    }

    private fun buildMinimalPdolData(pdol: ByteArray?): ByteArray {
        if (pdol == null) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val r = Random()
        var i = 0
        while (i < pdol.size) {
            var tag = pdol[i].toInt() and 0xFF
            i++
            if ((tag and 0x1F) == 0x1F) {
                while (i < pdol.size) {
                    val b = pdol[i].toInt() and 0xFF
                    tag = (tag shl 8) or b
                    i++
                    if (b and 0x80 == 0) break
                }
            }
            val len = pdol[i].toInt() and 0xFF
            i++

            val value: ByteArray = when (tag) {
                0x9F66 -> byteArrayOf(0xB6.toByte(), 0x00, 0xC0.toByte(), 0x00) // TTQ
                0x9F02 -> ByteArray(6)           // amount = 0
                0x9F03 -> ByteArray(6)           // cashback = 0
                0x9A -> {                       // date
                    val c = Calendar.getInstance()
                    byteArrayOf(
                        (c.get(Calendar.YEAR) % 100).toByte(),
                        (c.get(Calendar.MONTH) + 1).toByte(),
                        c.get(Calendar.DAY_OF_MONTH).toByte()
                    )
                }

                0x9C -> byteArrayOf(0x00)      // transaction type
                0x95 -> ByteArray(5)            // TVR
                0x5F2A -> byteArrayOf(0x08, 0x60) // UZS 860
                0x9F1A -> byteArrayOf(0x08, 0x60) // terminal country UZS
                0x9F37 -> ByteArray(4) { r.nextInt(256).toByte() } // unpredictable number
                0x9F35 -> byteArrayOf(0x21)       // terminal type
                0x9F45 -> ByteArray(2)
                0x9F4C -> ByteArray(8)
                0x9F34 -> byteArrayOf(0x42, 0x03, 0x00)
                else -> ByteArray(len)          // zeros for anything else
            }

            val padded = when {
                value.size == len -> value
                value.size < len -> value + ByteArray(len - value.size)
                else -> value.copyOf(len)
            }
            out.write(padded)
        }
        return out.toByteArray()
    }

    private fun parseTrack2Bcd(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append((b.toInt() shr 4) and 0x0F)
            sb.append(b.toInt() and 0x0F)
        }
        return sb.toString().uppercase(Locale.ROOT).trimEnd('F')
    }

    private fun detectScheme(aidHex: String): String {
        val a = aidHex.uppercase(Locale.ROOT)
        return when {
            a.startsWith("A000000003") -> "VISA"
            a.startsWith("A000000004") -> "MASTERCARD"
            a.startsWith("A0860001000001") -> "HUMO"
            a.startsWith("454F504343415244") -> "UZCARD"
            a.startsWith("A000000658") -> "MIR"
            a.startsWith("A000000333") -> "UNIONPAY"
            else -> "OTHER"
        }
    }

    private fun ByteArray.stripStatus(): ByteArray =
        if (size >= 2) copyOfRange(0, size - 2) else this

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder()
        for (b in this) sb.append("%02X".format(b))
        return sb.toString()
    }

    private fun String.hexToBytes(): ByteArray {
        val clean = replace(" ", "").uppercase()
        return ByteArray(clean.length / 2) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private data class MutableCardData(
        var pan: String = "",
        var expiry: String = "",
        var aid: String = ""
    )
}

// ─── APDU ─────────────────────────────────────────────────────────────────────

internal object Apdu {
    fun select(aid: ByteArray): ByteArray {
        return apdu(0x00, 0xA4, 0x04, 0x00, aid, 0x00)
    }

    fun gpo(pdolData: ByteArray): ByteArray {
        val data = ByteArrayOutputStream().apply {
            write(0x83)
            write(pdolData.size)
            write(pdolData)
        }.toByteArray()
        return apdu(0x80, 0xA8, 0x00, 0x00, data, 0x00)
    }

    fun readRecord(record: Int, p2: Int): ByteArray {
        return apdu(0x00, 0xB2, record, p2, 0x00)
    }

    fun getResponse(le: Int): ByteArray {
        return apdu(0x00, 0xC0, 0x00, 0x00, le)
    }

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, le: Int): ByteArray =
        byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), le.toByte())

    private fun apdu(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray, le: Int): ByteArray {
        val out = ByteArray(5 + data.size + 1)
        out[0] = cla.toByte(); out[1] = ins.toByte()
        out[2] = p1.toByte(); out[3] = p2.toByte()
        out[4] = data.size.toByte()
        System.arraycopy(data, 0, out, 5, data.size)
        out[5 + data.size] = le.toByte()
        return out
    }
}

// ─── NFC Provider ─────────────────────────────────────────────────────────────

internal class NfcProvider(private val isoDep: IsoDep) {
    init {
        isoDep.timeout = 5000
    }

    fun transceive(cmd: ByteArray): ByteArray {
        var resp = isoDep.transceive(cmd)
        if (resp.size >= 2) {
            val sw1 = resp[resp.size - 2].toInt() and 0xFF
            val sw2 = resp[resp.size - 1].toInt() and 0xFF
            when {
                sw1 == 0x6C -> {
                    // Wrong Le — resend with correct Le
                    val fixed = cmd.copyOf()
                    fixed[fixed.size - 1] = sw2.toByte()
                    resp = isoDep.transceive(fixed)
                }

                sw1 == 0x61 -> {
                    // More data available
                    resp = isoDep.transceive(Apdu.getResponse(sw2))
                }
            }
        }
        return resp
    }
}

// ─── Response Utils ───────────────────────────────────────────────────────────

internal object ResponseUtils {
    fun isOk(resp: ByteArray?): Boolean {
        if (resp == null || resp.size < 2) return false
        val sw1 = resp[resp.size - 2].toInt() and 0xFF
        return sw1 == 0x90 || sw1 == 0x61 || sw1 == 0x6C
    }
}

// ─── TLV ─────────────────────────────────────────────────────────────────────

internal object TlvUtil {

    data class TlvItem(val tag: Int, val value: ByteArray)

    fun parseAll(bytes: ByteArray): List<TlvItem> {
        val list = mutableListOf<TlvItem>()
        var i = 0
        while (i < bytes.size) {
            var tag = bytes[i].toInt() and 0xFF
            i++
            if ((tag and 0x1F) == 0x1F) {
                while (i < bytes.size) {
                    val b = bytes[i].toInt() and 0xFF
                    tag = (tag shl 8) or b; i++
                    if (b and 0x80 == 0) break
                }
            }
            if (i >= bytes.size) break
            var len = bytes[i].toInt() and 0xFF; i++
            if (len >= 0x80) {
                val count = len - 0x80; len = 0
                repeat(count) { len = (len shl 8) or (bytes[i++].toInt() and 0xFF) }
            }
            if (i + len > bytes.size) break
            val value = bytes.copyOfRange(i, i + len); i += len
            list.add(TlvItem(tag, value))
        }
        return list
    }

    fun findTag(bytes: ByteArray?, tagToFind: Int): ByteArray? {
        if (bytes == null) return null
        for (tlv in parseAll(bytes)) {
            if (tlv.tag == tagToFind) return tlv.value
            if ((tlv.tag and 0x20) != 0) {
                val nested = findTag(tlv.value, tagToFind)
                if (nested != null) return nested
            }
        }
        return null
    }
}

// ─── AFL Parser ───────────────────────────────────────────────────────────────

internal data class AflEntry(val sfi: Int, val firstRecord: Int, val lastRecord: Int)

internal object AflParser {
    fun parse(afl: ByteArray): List<AflEntry> {
        val res = mutableListOf<AflEntry>()
        var i = 0
        while (i + 3 < afl.size) {
            res.add(
                AflEntry(
                    sfi = (afl[i].toInt() and 0xFF) shr 3,
                    firstRecord = afl[i + 1].toInt() and 0xFF,
                    lastRecord = afl[i + 2].toInt() and 0xFF
                )
            )
            i += 4
        }
        return res
    }
}

// ─── Known AIDs ───────────────────────────────────────────────────────────────

internal object KnownAids {
    val list: List<ByteArray> = listOf(
        "A0860001000001",   // HUMO
        "454F504343415244", // UZCARD
        "A0000000031010",   // Visa
        "A0000000041010",   // Mastercard
        "A0000000043060",   // Maestro
        "A000000658",       // MIR
        "A000000333010101"  // UnionPay
    ).map { it.hexToBytes() }

    private fun String.hexToBytes(): ByteArray {
        val clean = uppercase()
        return ByteArray(clean.length / 2) { i ->
            ((clean[i * 2].digitToInt(16) shl 4) or clean[i * 2 + 1].digitToInt(16)).toByte()
        }
    }
}