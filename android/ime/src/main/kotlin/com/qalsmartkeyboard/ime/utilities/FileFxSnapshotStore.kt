package com.qalsmartkeyboard.ime.utilities

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The rates snapshot kept in one small JSON file in the app's private storage, so a restarted keyboard still converts
 * offline. Rates are public data (identical for everyone), so a plain file is enough.
 *
 * Never blocks the main thread: [load] and [save] touch memory only. [warm] reads the file once and [save] writes it on a
 * single background thread ([io]); call [warm] from a background thread at start. A missing, corrupt or oversized file is
 * ignored (the next fetch replaces it). A write goes to a temporary file and is renamed over the target.
 */
class FileFxSnapshotStore(
    private val file: File,
    private val io: (Runnable) -> Unit = { r -> Thread(r, "fx-store").apply { isDaemon = true }.start() },
) : FxSnapshotStore {
    @Volatile private var held: FxSnapshot? = null
    @Volatile private var warmed = false

    override fun load(): FxSnapshot? = held

    override fun save(snapshot: FxSnapshot) {
        held = snapshot
        warmed = true // a newer one is in hand: an older file read must not replace it
        io(Runnable { write(snapshot) })
    }

    /** Reads the file into memory (disk: off the main thread). Keeps a snapshot saved meanwhile. */
    @Synchronized
    fun warm() {
        if (warmed) return
        warmed = true
        val s = read() ?: return
        if (held == null) held = s
    }

    @Synchronized
    private fun write(s: FxSnapshot) {
        try {
            file.parentFile?.mkdirs()
            val rates = JSONObject()
            for ((k, v) in s.rates) rates.put(k, v)
            val body = JSONObject()
                .put("base", s.base)
                .put("asOf", s.asOfMillis)
                .put("stale", s.stale)
                .put("fetchedAt", s.fetchedAtMillis)
                .put("rates", rates)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(body.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // the copy in memory still serves this process; the next fetch writes again
        } catch (e: SecurityException) {
        } catch (e: JSONException) {
        }
    }

    private fun read(): FxSnapshot? = try {
        if (!file.isFile || file.length() > MAX_BYTES) {
            null
        } else {
            val o = JSONObject(file.readText())
            val r = o.getJSONObject("rates")
            val rates = HashMap<String, Double>()
            for (k in r.keys()) {
                val v = r.optDouble(k, Double.NaN)
                if (k.length == 3 && v.isFinite() && v > 0.0) rates[k] = v
            }
            val base = o.getString("base")
            if (base.length != 3 || rates.isEmpty()) {
                null
            } else {
                FxSnapshot(base, o.getLong("asOf"), rates, o.optBoolean("stale", false), o.optLong("fetchedAt", 0L))
            }
        }
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    } catch (e: SecurityException) {
        null
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024
    }
}
