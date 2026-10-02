package com.ridetrack.app.journal

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.ridetrack.app.R
import com.ridetrack.app.RideTrackApp
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException

/**
 * The "Keppo Moto" folder in Android's file picker, so Keppo Journal (once the rider allows
 * it) can read finished rides on this phone, offline. Read-only. Only listed while
 * Profile › Keppo Journal › "Share rides" is on. Layout: see [JournalTree].
 */
class RideDocumentsProvider : DocumentsProvider() {
    private val source: JournalSource
        get() = (context!!.applicationContext as RideTrackApp).container.journal

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (!runBlocking { source.enabled() }) return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, JournalTree.ROOT)
            add(Root.COLUMN_DOCUMENT_ID, JournalTree.ROOT)
            add(Root.COLUMN_TITLE, "Keppo Moto")
            add(Root.COLUMN_SUMMARY, "Your rides and moments")
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = runBlocking {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        if (!source.enabled()) throw FileNotFoundException(documentId)
        when (val doc = JournalTree.parse(documentId) ?: throw FileNotFoundException(documentId)) {
            JournalTree.Doc.Root -> folderRow(c, documentId, "Keppo Moto", null)
            is JournalTree.Doc.Ride -> {
                val ride = source.ride(doc.rideId) ?: throw FileNotFoundException(documentId)
                folderRow(c, documentId, JournalTree.rideFolderName(ride.startTimeMillis, ride.name), ride.lastUpdateMillis)
            }
            is JournalTree.Doc.File -> fileRow(c, doc, file(doc))
        }
        source.markRead()
        c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = runBlocking {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        if (!source.enabled()) return@runBlocking c
        when (val doc = JournalTree.parse(parentDocumentId)) {
            JournalTree.Doc.Root -> source.rides().forEach { r ->
                folderRow(c, JournalTree.id(JournalTree.Doc.Ride(r.id)), JournalTree.rideFolderName(r.startTimeMillis, r.name), r.lastUpdateMillis)
            }
            is JournalTree.Doc.Ride -> {
                val names = source.momentFiles(doc.rideId).map { it.name }
                JournalTree.rideChildren(doc.rideId, names).forEach { f -> runCatching { fileRow(c, f, file(f)) } }
            }
            else -> Unit
        }
        source.markRead()
        c.setNotificationUri(context!!.contentResolver, android.provider.DocumentsContract.buildChildDocumentsUri(JournalSource.authority(context!!), parentDocumentId))
        c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("Keppo Moto shares rides read-only")
        val doc = JournalTree.parse(documentId) as? JournalTree.Doc.File ?: throw FileNotFoundException(documentId)
        val f = runBlocking {
            if (!source.enabled()) throw FileNotFoundException(documentId)
            file(doc).also { source.markRead() }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        JournalTree.isChild(parentDocumentId, documentId)

    override fun getDocumentType(documentId: String): String = when (val doc = JournalTree.parse(documentId)) {
        is JournalTree.Doc.File -> JournalTree.mime(doc.name)
        null -> throw FileNotFoundException(documentId)
        else -> Document.MIME_TYPE_DIR
    }

    /** The real file behind a document; ride.json and route.png are generated on demand. */
    private suspend fun file(doc: JournalTree.Doc.File): File {
        val ride = source.ride(doc.rideId) ?: throw FileNotFoundException(doc.name)
        val f = when (doc.name) {
            JournalTree.RIDE_JSON -> source.rideJson(ride)
            JournalTree.ROUTE_PNG -> source.routePng(ride)
            else -> source.momentFiles(ride.id).firstOrNull { it.name == doc.name }
        }
        return f?.takeIf { it.isFile } ?: throw FileNotFoundException(doc.name)
    }

    private fun folderRow(c: MatrixCursor, id: String, name: String, modified: Long?) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, modified)
        }
    }

    private fun fileRow(c: MatrixCursor, doc: JournalTree.Doc.File, f: File) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, JournalTree.id(doc))
            add(Document.COLUMN_DISPLAY_NAME, doc.name)
            add(Document.COLUMN_MIME_TYPE, JournalTree.mime(doc.name))
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_SIZE, f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
        }
    }

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES,
        )
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}
