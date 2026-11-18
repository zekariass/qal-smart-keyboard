package com.qalsmartkeyboard.app.privacy

import com.qalsmartkeyboard.api.ApiError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

enum class ExportPhase {
    /** Nothing requested (or the last export is gone). */
    IDLE,
    REQUESTING,

    /** `REQUESTED` or `PROCESSING` on the server. */
    IN_PROGRESS,
    READY,
    DOWNLOADING,

    /** The archive was written to the chosen place. */
    SAVED,
    EXPIRED,
    FAILED,
}

data class ExportState(
    val phase: ExportPhase = ExportPhase.IDLE,
    val export: DataExportInfo? = null,
    /** True when `POST` answered with an export that was already running (one open export per person). */
    val alreadyRunning: Boolean = false,
    /** After a 429: the earliest time (device clock) a new export may be requested; null when unknown. */
    val retryAtMs: Long? = null,
    val savedBytes: Long? = null,
    val message: PrivacyMessage? = null,
)

/** Remembers the id of the last export so the screen can pick it up again after leaving it. An id only. */
interface ExportMemory {
    var lastId: String?
}

class MemoryExportMemory(override var lastId: String? = null) : ExportMemory

/**
 * Data export (A2.6, B6.1): request (`POST /me/data-exports`, 202), poll the status, then stream the
 * archive from the authenticated endpoint into an output stream the person chose (on Android a
 * document picked with `ACTION_CREATE_DOCUMENT`; a byte array in tests). At most 3 exports per
 * 24 hours (429 `Retry-After`) and one open export (the same export is returned again); the archive
 * link lasts 7 days (`downloadExpiresAt`); guests and accounts with a pending deletion may export.
 * Nothing of the archive is kept or logged: it goes straight to [download]'s sink.
 *
 * [pause] is the wait between polls (replaced in tests); [now] the device clock.
 */
class ExportModel(
    private val api: PrivacyApi,
    private val memory: ExportMemory,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /** Picks up the remembered export (screen opened again): refreshes its status. */
    suspend fun resume() {
        val id = memory.lastId ?: return
        if (_state.value.phase != ExportPhase.IDLE) return
        try {
            show(api.export(id), alreadyRunning = false)
        } catch (e: ApiError.Http) {
            if (e.status == 404) memory.lastId = null else fail(e)
        } catch (e: ApiError) {
            fail(e)
        }
    }

    suspend fun request() {
        val s = _state.value
        if (s.phase == ExportPhase.REQUESTING || s.phase == ExportPhase.DOWNLOADING) return
        val retryAt = s.retryAtMs
        if (retryAt != null && now() < retryAt) return
        _state.update { it.copy(phase = ExportPhase.REQUESTING, message = null, retryAtMs = null, savedBytes = null) }
        try {
            val export = api.requestExport()
            val known = memory.lastId
            memory.lastId = export.id
            // The server returns the export already in flight instead of a second one.
            show(export, alreadyRunning = export.inFlight && known == export.id)
        } catch (e: ApiError.RateLimited) {
            _state.update {
                it.copy(
                    phase = ExportPhase.FAILED,
                    message = PrivacyMessage.RateLimited(e.retryAfterSeconds),
                    retryAtMs = e.retryAfterSeconds?.let { sec -> now() + sec * 1000 },
                )
            }
        } catch (e: ApiError) {
            fail(e)
        }
    }

    /** One status read of the current export. */
    suspend fun refresh() {
        val id = _state.value.export?.id ?: memory.lastId ?: return
        try {
            show(api.export(id), alreadyRunning = _state.value.alreadyRunning)
        } catch (e: ApiError) {
            fail(e)
        }
    }

    /**
     * Polls until the export is no longer in flight (or [maxPolls] reads): every 3 s for the first
     * minute, then every 10 s. The screen runs it while it is shown.
     */
    suspend fun pollUntilDone(maxPolls: Int = 120) {
        var polls = 0
        while (_state.value.phase == ExportPhase.IN_PROGRESS && polls < maxPolls) {
            pause(if (polls < 20) 3_000L else 10_000L)
            polls++
            refresh()
        }
    }

    /**
     * Writes the archive to the stream [open] returns (null: the person cancelled the picker, nothing
     * happens). [discard] removes a half-written document after a failure.
     */
    suspend fun download(open: () -> OutputStream?, discard: () -> Unit = {}) {
        val export = _state.value.export ?: return
        if (_state.value.phase != ExportPhase.READY && _state.value.phase != ExportPhase.FAILED) return
        val raw = open() ?: return
        _state.update { it.copy(phase = ExportPhase.DOWNLOADING, message = null) }
        val guard = SinkGuard(raw)
        try {
            val bytes = raw.use { api.downloadExport(export.id, guard) }
            _state.update { it.copy(phase = ExportPhase.SAVED, savedBytes = bytes) }
        } catch (e: ApiError) {
            runCatching { discard() }
            if (guard.failed) {
                _state.update { it.copy(phase = ExportPhase.READY, message = PrivacyMessage.SaveFailed) }
            } else if (e is ApiError.Http && e.status == 404) {
                // Expired, or its keys are gone: ask the server what it is now.
                _state.update { it.copy(phase = ExportPhase.READY, message = PrivacyMessage.NotFound) }
                try {
                    show(api.export(export.id), alreadyRunning = false)
                } catch (_: ApiError) {
                    _state.update { it.copy(phase = ExportPhase.EXPIRED) }
                }
            } else {
                _state.update { it.copy(phase = ExportPhase.READY, message = e.toPrivacyMessage()) }
            }
        } catch (e: IOException) {
            runCatching { discard() }
            _state.update { it.copy(phase = ExportPhase.READY, message = PrivacyMessage.SaveFailed) }
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun show(export: DataExportInfo, alreadyRunning: Boolean) {
        val phase = when (export.status) {
            ExportStatus.REQUESTED, ExportStatus.PROCESSING -> ExportPhase.IN_PROGRESS
            ExportStatus.READY -> if (_state.value.phase == ExportPhase.SAVED) ExportPhase.SAVED else ExportPhase.READY
            ExportStatus.EXPIRED -> ExportPhase.EXPIRED
            ExportStatus.FAILED, ExportStatus.UNKNOWN -> ExportPhase.FAILED
        }
        _state.update {
            it.copy(
                phase = phase,
                export = export,
                alreadyRunning = alreadyRunning,
                message = if (phase == ExportPhase.FAILED) PrivacyMessage.Failed else null,
            )
        }
    }

    private fun fail(e: ApiError) {
        _state.update { it.copy(phase = if (it.export == null) ExportPhase.FAILED else it.phase, message = e.toPrivacyMessage()) }
    }

    private class SinkFailure(cause: IOException) : IOException(cause)

    /** Tells a failed write to the chosen document apart from a failed network read (both are I/O errors). */
    private class SinkGuard(out: OutputStream) : FilterOutputStream(out) {
        @Volatile var failed = false
            private set

        override fun write(b: Int) = guard { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
        override fun flush() = guard { out.flush() }
        private inline fun guard(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                failed = true
                throw SinkFailure(e)
            }
        }
    }
}
