// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.data

import android.content.Context
import java.io.File

/**
 * Complete messages as downloaded (".eml"), one file per message row, in app-private storage.
 * Opened attachments get their own cache folder that is cleared after a day (Linux app).
 */
class BodyStore(context: Context) {
    private val dir = File(context.filesDir, "bodies").apply { mkdirs() }
    val attachmentDir = File(context.cacheDir, "attachments").apply { mkdirs() }

    private fun file(rowId: Long) = File(dir, "$rowId.eml")

    fun get(rowId: Long): ByteArray? = file(rowId).takeIf { it.exists() }?.readBytes()

    fun put(rowId: Long, raw: ByteArray) {
        val tmp = File(dir, "$rowId.tmp")
        tmp.writeBytes(raw)
        tmp.renameTo(file(rowId))
    }

    fun drop(rowId: Long) {
        file(rowId).delete()
    }

    @Suppress("UNUSED_PARAMETER")
    fun dropFolder(folderId: Long) = Unit // rows are gone; [prune] removes their files

    /** Deletes files of messages that no longer exist, and attachment copies older than a day. */
    fun prune(existingRowIds: Set<Long>) {
        dir.listFiles()?.forEach { f ->
            val id = f.name.substringBefore('.').toLongOrNull()
            if (id == null || id !in existingRowIds) f.delete()
        }
        val dayAgo = System.currentTimeMillis() - 24 * 3600 * 1000
        attachmentDir.walkBottomUp().filter { it != attachmentDir && it.lastModified() < dayAgo }.forEach { it.delete() }
    }
}
