package com.qalsmartkeyboard.ime.share

import java.io.File

/** An [ImageShare] for tests: records what was inserted and shared; [insertable] and the accept flags steer the outcome. */
class FakeImageShare(
    var insertable: Boolean = true,
    var insertAccepted: Boolean = true,
    var shareAccepted: Boolean = true,
) : ImageShare {
    val inserted = ArrayList<File>()
    val shared = ArrayList<File>()
    val log = ArrayList<String>()

    override fun canInsert(): Boolean = insertable

    override fun insert(png: File, description: String): Boolean {
        log += "insert"
        if (insertAccepted) inserted += png
        return insertAccepted
    }

    override fun share(png: File, title: String): Boolean {
        log += "share"
        if (shareAccepted) shared += png
        return shareAccepted
    }
}
