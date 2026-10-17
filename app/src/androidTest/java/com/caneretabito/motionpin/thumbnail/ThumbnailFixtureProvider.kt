package com.caneretabito.motionpin.thumbnail

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicInteger

/** Local test-only provider: no thumbnail implementation, but seekable original video bytes. */
class ThumbnailFixtureProvider : ContentProvider() {
    private val typedCalls = AtomicInteger()
    private val fileCalls = AtomicInteger()
    private val preparedFiles = mutableSetOf<String>()

    override fun onCreate() = true

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?): AssetFileDescriptor =
        openTypedAssetFile(uri, mimeTypeFilter, opts, null)

    override fun openTypedAssetFile(uri: Uri, mimeTypeFilter: String, opts: Bundle?,
                                   signal: CancellationSignal?): AssetFileDescriptor {
        signal?.throwIfCanceled()
        typedCalls.incrementAndGet()
        throw FileNotFoundException("Fixture provider deliberately has no thumbnail API")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only fixture")
        val name = uri.lastPathSegment
        if (name !in setOf("black-lead.mp4", "black-lead.mkv"))
            throw FileNotFoundException("Unknown fixture")
        val context = checkNotNull(context)
        val file = File(context.cacheDir, "thumbnail-fixtures/$name")
        synchronized(this) {
            if (name !in preparedFiles) {
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                context.assets.open("thumbnail-fixtures/$name").use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                preparedFiles += checkNotNull(name)
            }
        }
        fileCalls.incrementAndGet()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = if (uri.lastPathSegment?.endsWith(".mkv") == true)
        "video/x-matroska" else "video/mp4"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf("typed_calls", "file_calls")).apply {
            addRow(arrayOf(typedCalls.get(), fileCalls.get()))
        }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        const val AUTHORITY = "com.caneretabito.motionpin.test.thumbnailfixtures"
        fun uri(file: String): Uri = Uri.parse("content://$AUTHORITY/$file")
    }
}
