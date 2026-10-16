package dev.lystic.fanyixia

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

internal data class SavedSession(
    val id: String,
    val title: String,
    val chinese: String,
    val english: String,
    val recordedAt: String,
    val publishedAt: String? = null,
    val publishedServer: String? = null
) {
    fun payload() = JSONObject().put("title", title).put("chinese", chinese).put("english", english)
        .put("recordedAt", recordedAt).put("transcriptionEngine", "whisper-base").put("translationEngine", "ml-kit")
}

internal class SessionStore(context: Context) : SQLiteOpenHelper(context, "sessions.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            chinese TEXT NOT NULL,
            english TEXT NOT NULL,
            recordedAt TEXT NOT NULL,
            publishedAt TEXT,
            publishedServer TEXT
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported session database version $oldVersion → $newVersion")
    }

    fun list(): List<SavedSession> = readableDatabase.query(
        "sessions", null, null, null, null, null, "rowid DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                add(SavedSession(text("id"), text("title"), text("chinese"), text("english"), text("recordedAt"),
                    text("publishedAt"), text("publishedServer")))
            }
        }
    }

    // Saved text is immutable. Editing happens in a new draft with a new UUID at save.
    fun save(session: SavedSession) {
        val values = ContentValues().apply {
            put("id", session.id)
            put("title", session.title)
            put("chinese", session.chinese)
            put("english", session.english)
            put("recordedAt", session.recordedAt)
        }
        writableDatabase.insertOrThrow("sessions", null, values)
    }

    fun acknowledge(id: String, server: String, publishedAt: String) {
        val count = writableDatabase.update("sessions", ContentValues().apply {
            put("publishedAt", publishedAt)
            put("publishedServer", server)
        }, "id = ?", arrayOf(id))
        check(count == 1) { "Publication succeeded but the local acknowledgement could not be stored. Retry the same saved session." }
    }
}
