package ly.anoma.hex.notes

import android.content.ContentValues
import android.content.Context
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

internal data class VoiceNote(val id: Long, val recordedAt: Long, val text: String, val excerpt: String,
                              val recordingMillis: Int?)

private const val RECORDINGS_TABLE = "CREATE TABLE voice_note_recordings (note_id INTEGER PRIMARY KEY, wav BLOB NOT NULL)"

private val searchWord = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*")

/** Treat punctuation and quoted operators as user text. FTS4 uses a trailing star inside the quotes for prefixes. */
internal fun noteSearch(query: String): String =
    searchWord.findAll(query).take(20).joinToString(" ") { "\"" + it.value + "*\"" }

/** The FTS table owns both the transcript and its index, so each save or delete updates both atomically. */
internal class VoiceNoteStore private constructor(context: Context) : SQLiteOpenHelper(context, "voice-notes.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE voice_notes USING fts4(body, recorded_at, notindexed=recorded_at, tokenize=unicode61)")
        db.execSQL(RECORDINGS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL(RECORDINGS_TABLE)
    }

    fun add(text: String, recordedAt: Long, recording: ShortArray?) {
        val values = ContentValues().apply {
            put("body", text)
            put("recorded_at", recordedAt)
        }
        val wav = recording?.let(::voiceNoteWav)
        writableDatabase.transaction {
            val id = insertOrThrow("voice_notes", null, values)
            if (wav != null) insertOrThrow("voice_note_recordings", null, ContentValues().apply {
                put("note_id", id)
                put("wav", wav)
            })
        }
    }

    fun find(query: String): List<VoiceNote> {
        val filter = noteSearch(query)
        if (query.isNotBlank() && filter.isEmpty()) return emptyList()
        val search = filter.isNotEmpty()
        val excerpt = if (search) "snippet(voice_notes, '', '', ' … ', 0, 32)" else "substr(body, 1, 240)"
        val where = if (search) " WHERE voice_notes MATCH ?" else ""
        val arguments = if (search) arrayOf(filter) else null
        return readableDatabase.rawQuery(
            "SELECT rowid, recorded_at, body, $excerpt, " +
                "(SELECT length(wav) FROM voice_note_recordings WHERE note_id = voice_notes.rowid) " +
                "FROM voice_notes$where ORDER BY rowid DESC", arguments,
        ).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val recordingMillis = if (rows.isNull(4)) null else voiceNoteDurationMillis(rows.getInt(4))
                    add(VoiceNote(rows.getLong(0), rows.getLong(1), rows.getString(2), rows.getString(3), recordingMillis))
                }
            }
        }
    }

    fun recording(id: Long): ByteArray? = readableDatabase.rawQueryWithFactory(
        { _, driver, table, query ->
            SQLiteCursor(driver, table, query).apply {
                // A minute of 16 kHz mono PCM16 and its WAV header fit in this window.
                window = CursorWindow("Hex note audio", 2L * 1024 * 1024)
            }
        },
        "SELECT wav FROM voice_note_recordings WHERE note_id = ?", arrayOf(id.toString()), "voice_note_recordings",
    ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    fun delete(id: Long) {
        val args = arrayOf(id.toString())
        writableDatabase.transaction {
            delete("voice_note_recordings", "note_id = ?", args)
            delete("voice_notes", "rowid = ?", args)
        }
    }

    companion object {
        private var instance: VoiceNoteStore? = null

        @Synchronized
        fun get(context: Context): VoiceNoteStore =
            instance ?: VoiceNoteStore(context.applicationContext).also { instance = it }
    }
}
