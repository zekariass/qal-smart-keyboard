package com.qalsmartkeyboard.app.creative.backup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted record of what was uploaded: ids and numbers only, one account's, atomic on disk. */
class BackupRecordTest {
    private val dir: File = Files.createTempDirectory("backup-record").toFile()

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private val sample = BackupRecord("3f1c0d2e-0000-4000-8000-000000000001", mapOf("abc123" to RecordEntry(1_700_000_000_123, 4), "zz9yy8" to RecordEntry(5, 1)))

    @Test fun theCodecRoundTrips() {
        assertEquals(sample, BackupRecordCodec.decode(BackupRecordCodec.encode(sample)))
        assertEquals(BackupRecord("u"), BackupRecordCodec.decode(BackupRecordCodec.encode(BackupRecord("u"))))
    }

    @Test fun theEncodedTextHoldsOnlyTheAccountIdItemIdsAndNumbers() {
        val text = BackupRecordCodec.encode(sample)
        assertEquals("""{"v":1,"user":"3f1c0d2e-0000-4000-8000-000000000001","items":{"abc123":[1700000000123,4],"zz9yy8":[5,1]}}""", text)
    }

    @Test fun anythingUnreadableDecodesToNull() {
        val bad = listOf(
            "", "not json", "[]", "{}",
            """{"v":2,"user":"u","items":{}}""",
            """{"v":1,"user":"","items":{}}""",
            """{"v":1,"items":{}}""",
            """{"v":1,"user":"u","items":[]}""",
            """{"v":1,"user":"u","items":{"a":[1]}}""",
            """{"v":1,"user":"u","items":{"a":["x",2]}}""",
            """{"v":1,"user":"u","items":{"a":5}}""",
        )
        for (text in bad) assertNull(text, BackupRecordCodec.decode(text))
    }

    @Test fun theFileStoreSavesLoadsAndClears() {
        val file = File(dir, "nested/record.json")
        val store = FileBackupRecordStore(file)
        assertNull(store.load())
        assertTrue(store.save(sample))
        assertEquals(sample, store.load())
        assertTrue(store.save(BackupRecord("other")))
        assertEquals(BackupRecord("other"), store.load())
        assertFalse("no temporary file is left", File(file.parentFile, "record.json.tmp").exists())
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test fun aDamagedFileIsAnEmptyRecordNotACrash() {
        val file = File(dir, "record.json")
        file.writeText("{\"v\":1,\"user\"")
        assertNull(FileBackupRecordStore(file).load())
        file.writeBytes(ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
        assertNull("an implausibly large file is not read", FileBackupRecordStore(file).load())
        assertNotNull("and it can be replaced", FileBackupRecordStore(file).also { it.save(sample) }.load())
    }
}
