package app.cloudmusic.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.cloudmusic.CloudMusicApplication
import java.io.File
import java.io.FileNotFoundException

/** Read-only cached JPEGs. The non-exported provider requires an explicit URI grant. */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true
    private fun image(uri: Uri): File {
        if (uri.pathSegments.size != 1) throw FileNotFoundException()
        val app = context!!.applicationContext as CloudMusicApplication
        val track = app.repository.database.findTrack(uri.pathSegments.single()) ?: throw FileNotFoundException()
        return app.artwork.cachedFile(track) ?: throw FileNotFoundException()
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(image(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = image(uri); val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) f.name else if (it == OpenableColumns.SIZE) f.length() else null }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
