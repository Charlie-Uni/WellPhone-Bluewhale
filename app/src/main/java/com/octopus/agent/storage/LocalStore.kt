package com.octopus.agent.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import com.octopus.core.AgentEvent
import com.octopus.core.EventJournal
import com.octopus.core.WriteRecord
import com.octopus.core.WriteStatus
import com.octopus.core.WriteStore
import com.octopus.core.CanonicalStore
import com.octopus.core.CanonicalItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LocalStore(context: Context) : SQLiteOpenHelper(context, "octopus.db", null, 2), WriteStore, EventJournal, CanonicalStore {
    private val log = File(context.filesDir, "agent-events.jsonl")
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE writes (key TEXT PRIMARY KEY, status TEXT NOT NULL, locator TEXT)")
        createCanonical(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) createCanonical(db) else error("Explicit migration required")
    }
    private fun createCanonical(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE canonical (source TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override suspend fun load(sourceFingerprint: String): List<CanonicalItem>? = withContext(Dispatchers.IO) {
        readableDatabase.query("canonical", arrayOf("payload"), "source = ?", arrayOf(sourceFingerprint),
            null, null, null).use { cursor ->
            if (cursor.moveToFirst()) Json.decodeFromString<List<CanonicalItem>>(cursor.getString(0)) else null
        }
    }
    override suspend fun insert(sourceFingerprint: String, items: List<CanonicalItem>): List<CanonicalItem> =
        withContext(Dispatchers.IO) {
            writableDatabase.insertWithOnConflict("canonical", null, ContentValues().apply {
                put("source", sourceFingerprint); put("payload", Json.encodeToString(items))
            }, SQLiteDatabase.CONFLICT_IGNORE)
            requireNotNull(load(sourceFingerprint))
        }
    override suspend fun update(sourceFingerprint: String, item: CanonicalItem) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val records = requireNotNull(load(sourceFingerprint))
            check(records.any { it.id == item.id })
            db.update("canonical", ContentValues().apply {
                put("payload", Json.encodeToString(records.map { if (it.id == item.id) item else it }))
            }, "source = ?", arrayOf(sourceFingerprint))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override suspend fun insertIfAbsent(record: WriteRecord): WriteRecord = withContext(Dispatchers.IO) {
        writableDatabase.insertWithOnConflict("writes", null, values(record), SQLiteDatabase.CONFLICT_IGNORE)
        requireNotNull(read(record.key))
    }
    override suspend fun read(key: String): WriteRecord? = withContext(Dispatchers.IO) {
        readableDatabase.query("writes", arrayOf("key", "status", "locator"), "key = ?", arrayOf(key),
            null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else WriteRecord(cursor.getString(0),
                WriteStatus.valueOf(cursor.getString(1)), if (cursor.isNull(2)) null else cursor.getString(2))
        }
    }
    override suspend fun compareAndSet(expected: WriteRecord, update: WriteRecord): Boolean =
        withContext(Dispatchers.IO) {
            require(expected.key == update.key)
            val locatorClause = if (expected.targetLocator == null) "locator IS NULL" else "locator = ?"
            val args = listOfNotNull(expected.key, expected.status.name, expected.targetLocator).toTypedArray()
            writableDatabase.update("writes", values(update), "key = ? AND status = ? AND $locatorClause", args) == 1
        }
    private fun values(record: WriteRecord) = ContentValues().apply {
        put("key", record.key); put("status", record.status.name); put("locator", record.targetLocator)
    }
    @Synchronized override fun record(event: AgentEvent) {
        // Strict schema excludes raw goal, observations, addresses, messages, keys and URL tokens.
        log.appendText("{\"monotonicMillis\":${SystemClock.elapsedRealtime()},\"event\":${Json.encodeToString(event)}}\n")
    }
}
