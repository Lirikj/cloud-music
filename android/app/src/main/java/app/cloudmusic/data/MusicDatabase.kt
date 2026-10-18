package app.cloudmusic.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MusicDatabase(context: Context, name: String = "cloud-music.db") : SQLiteOpenHelper(context, name, null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE folders (id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, provider TEXT NOT NULL, resource_key TEXT NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, folder_id TEXT NOT NULL REFERENCES folders(id) ON DELETE CASCADE, provider TEXT NOT NULL, remote_id TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, path TEXT NOT NULL, resource_key TEXT NOT NULL, size INTEGER NOT NULL, favorite INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX tracks_folder ON tracks(folder_id)")
        createStreamLinks(db)
        createDownloads(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createStreamLinks(db)
        if (oldVersion < 3) createDownloads(db)
    }
    private fun createStreamLinks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS stream_links (track_id TEXT PRIMARY KEY, folder_id TEXT NOT NULL REFERENCES folders(id) ON DELETE CASCADE, revision INTEGER NOT NULL, url TEXT NOT NULL, expires_at INTEGER NOT NULL)")
    }
    private fun createDownloads(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS downloads (source_id TEXT PRIMARY KEY, manager_id INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, filename TEXT NOT NULL, expected_size INTEGER NOT NULL, status TEXT NOT NULL, bytes INTEGER NOT NULL, total INTEGER NOT NULL, uri TEXT NOT NULL, error TEXT NOT NULL)")
    }
    fun downloads(): List<MusicDownload> = readableDatabase.rawQuery("SELECT * FROM downloads ORDER BY manager_id DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(MusicDownload(c.str("source_id"), c.getLong(c.getColumnIndexOrThrow("manager_id")), c.str("title"), c.str("artist"), c.str("album"), c.str("filename"), c.getLong(c.getColumnIndexOrThrow("expected_size")), DownloadStatus.valueOf(c.str("status")), c.getLong(c.getColumnIndexOrThrow("bytes")), c.getLong(c.getColumnIndexOrThrow("total")), c.str("uri"), c.str("error"))) }
    }
    fun saveDownload(d: MusicDownload) { writableDatabase.insertWithOnConflict("downloads", null, ContentValues().apply {
        put("source_id", d.sourceId); put("manager_id", d.managerId); put("title", d.title); put("artist", d.artist); put("album", d.album); put("filename", d.filename); put("expected_size", d.expectedSize)
        put("status", d.status.name); put("bytes", d.bytes); put("total", d.total); put("uri", d.uri); put("error", d.error)
    }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun removeDownload(sourceId: String) { writableDatabase.delete("downloads", "source_id=?", arrayOf(sourceId)) }
    fun saveStreamLinks(folder: CloudFolder, links: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("stream_links", "expires_at<=?", arrayOf(System.currentTimeMillis().toString()))
            links.entries.take(512).forEach { (id, url) ->
                if (url.startsWith("https://") && url.length <= 4096) db.insertWithOnConflict("stream_links", null, ContentValues().apply {
                    put("track_id", id); put("folder_id", folder.id); put("revision", folder.updatedAt); put("url", url); put("expires_at", System.currentTimeMillis() + 240_000)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.execSQL("DELETE FROM stream_links WHERE track_id NOT IN (SELECT track_id FROM stream_links ORDER BY expires_at DESC LIMIT 512)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun activeStreamLinks(): List<SavedStreamLink> {
        val now = System.currentTimeMillis()
        writableDatabase.delete("stream_links", "expires_at<=?", arrayOf(now.toString()))
        return readableDatabase.rawQuery("SELECT l.* FROM stream_links l JOIN folders f ON f.id=l.folder_id AND f.updated_at=l.revision JOIN tracks t ON t.id=l.track_id WHERE l.expires_at>?", arrayOf(now.toString())).use { c ->
            buildList { while (c.moveToNext()) add(SavedStreamLink(c.str("track_id"), c.str("folder_id"), c.getLong(c.getColumnIndexOrThrow("revision")), c.str("url"), c.getLong(c.getColumnIndexOrThrow("expires_at")))) }
        }
    }
    fun snapshot(): CollectionSnapshot {
        val folders = readableDatabase.rawQuery("SELECT * FROM folders ORDER BY updated_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.folder()) }
        }
        val tracks = readableDatabase.rawQuery("SELECT * FROM tracks", null).use { c ->
            buildList { while (c.moveToNext()) add(c.track()) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return CollectionSnapshot(folders, tracks)
    }
    fun findTrack(id: String): Track? = readableDatabase.rawQuery("SELECT * FROM tracks WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.track() else null }
    fun findFolder(id: String): CloudFolder? = readableDatabase.rawQuery("SELECT * FROM folders WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.folder() else null }
    fun replaceFolder(folder: CloudFolder, tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val favoriteIds = db.rawQuery("SELECT id FROM tracks WHERE folder_id=? AND favorite=1", arrayOf(folder.id)).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            db.insertWithOnConflict("folders", null, ContentValues().apply {
                put("id", folder.id); put("name", folder.name); put("url", folder.url); put("provider", folder.provider.name)
                put("resource_key", folder.resourceKey); put("updated_at", folder.updatedAt)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.delete("tracks", "folder_id=?", arrayOf(folder.id))
            tracks.forEach { t ->
                db.insertOrThrow("tracks", null, ContentValues().apply {
                    put("id", t.id); put("folder_id", t.folderId); put("provider", t.provider.name); put("remote_id", t.remoteId)
                    put("title", t.title); put("artist", t.artist); put("album", t.album); put("path", t.path)
                    put("resource_key", t.resourceKey); put("size", t.size); put("favorite", if (t.favorite || t.id in favoriteIds) 1 else 0)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun setFavorite(id: String, favorite: Boolean) { writableDatabase.update("tracks", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "id=?", arrayOf(id)) }
    fun removeFolder(id: String) { writableDatabase.delete("folders", "id=?", arrayOf(id)) }
    private fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.folder() = CloudFolder(str("id"), str("name"), str("url"), Provider.valueOf(str("provider")), str("resource_key"), getLong(getColumnIndexOrThrow("updated_at")))
    private fun Cursor.track() = Track(str("id"), str("folder_id"), Provider.valueOf(str("provider")), str("remote_id"), str("title"), str("artist"), str("album"), str("path"), str("resource_key"), getLong(getColumnIndexOrThrow("size")), getInt(getColumnIndexOrThrow("favorite")) == 1)
}
