package com.aios.messaging.mms

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Narrow cross-process file boundary used only by the platform MMS service. */
class MmsPduProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(requireNotNull(context), token(uri))
        val flags = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            else -> throw FileNotFoundException("unsupported MMS PDU mode")
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (fileFor(requireNotNull(context), token(uri)).delete()) 1 else 0

    override fun getType(uri: Uri): String = MMS_MIME
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.aios.messaging.mms.pdu"
        private const val MMS_MIME = "application/vnd.wap.mms-message"
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun create(context: android.content.Context, token: String): Uri {
            require(TOKEN.matches(token)) { "invalid MMS operation token" }
            val file = fileFor(context, token)
            check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true) {
                "cannot create MMS PDU directory"
            }
            if (!file.exists()) check(file.createNewFile()) { "cannot create MMS PDU file" }
            return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build()
        }

        fun fileFor(context: android.content.Context, token: String): File {
            require(TOKEN.matches(token)) { "invalid MMS operation token" }
            val root = File(context.noBackupFilesDir, "mms_pdu").canonicalFile
            val file = File(root, "$token.pdu").canonicalFile
            check(file.parentFile == root) { "MMS PDU escaped its private directory" }
            return file
        }

        fun remove(context: android.content.Context, token: String) {
            runCatching { fileFor(context, token).delete() }
        }

        fun removeOrphans(context: android.content.Context, activeTokens: Set<String>) {
            val root = File(context.noBackupFilesDir, "mms_pdu").canonicalFile
            root.listFiles()?.forEach { candidate ->
                val token = candidate.name.removeSuffix(".pdu")
                if (candidate.isFile &&
                    candidate.name.endsWith(".pdu") &&
                    TOKEN.matches(token) &&
                    token !in activeTokens) {
                    runCatching { candidate.delete() }
                }
            }
        }

        private fun token(uri: Uri): String {
            check(uri.authority == AUTHORITY && uri.pathSegments.size == 1) {
                "invalid MMS PDU URI"
            }
            return uri.lastPathSegment.orEmpty().also {
                check(TOKEN.matches(it)) { "invalid MMS operation token" }
            }
        }
    }
}
