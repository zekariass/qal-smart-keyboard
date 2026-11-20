package com.qalsmartkeyboard.app.billing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * The order a hosted checkout is open for, kept across process death (the payer may be in the telebirr app when
 * Android stops ours): ids and the chosen method only, nothing about the payer. Cleared when the order reaches a final
 * state or is abandoned for good.
 */
data class PendingCheckout(
    val orderId: String,
    val attemptId: String?,
    val methodCode: String,
    val productCode: String,
    val billingPeriod: String,
    val startedAtMs: Long,
    /** The checkout came back through the return URL (or the app's return URI). */
    val returned: Boolean = false,
    /** The `result` of that return (`success`, `cancel`, `error`), UX only. */
    val result: String? = null,
)

interface PendingCheckoutStore {
    fun load(): PendingCheckout?
    fun save(p: PendingCheckout)
    fun clear()
}

class MemoryPendingCheckoutStore(private var value: PendingCheckout? = null) : PendingCheckoutStore {
    override fun load() = value
    override fun save(p: PendingCheckout) { value = p }
    override fun clear() { value = null }
}

/** A small JSON file in `noBackupFilesDir` (never backed up or moved to another device). */
class FilePendingCheckoutStore(private val file: File) : PendingCheckoutStore {
    @Synchronized override fun load(): PendingCheckout? = try {
        if (!file.isFile) null else parse(file.readText())
    } catch (e: IOException) {
        null
    }

    @Synchronized override fun save(p: PendingCheckout) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(p))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: IOException) {
        }
    }

    @Synchronized override fun clear() {
        file.delete()
    }

    companion object {
        fun encode(p: PendingCheckout): String = buildJsonObject {
            put("orderId", p.orderId)
            p.attemptId?.let { put("attemptId", it) }
            put("methodCode", p.methodCode)
            put("productCode", p.productCode)
            put("billingPeriod", p.billingPeriod)
            put("startedAtMs", p.startedAtMs)
            put("returned", p.returned)
            p.result?.let { put("result", it) }
        }.toString()

        fun parse(text: String): PendingCheckout? {
            val o = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: RuntimeException) {
                null
            } ?: return null
            fun s(n: String) = (o[n] as? JsonPrimitive)?.contentOrNull
            return PendingCheckout(
                orderId = s("orderId") ?: return null,
                attemptId = s("attemptId"),
                methodCode = s("methodCode") ?: return null,
                productCode = s("productCode") ?: return null,
                billingPeriod = s("billingPeriod") ?: return null,
                startedAtMs = (o["startedAtMs"] as? JsonPrimitive)?.longOrNull ?: return null,
                returned = (o["returned"] as? JsonPrimitive)?.booleanOrNull ?: false,
                result = s("result"),
            )
        }
    }
}
