package com.octopus.core

enum class WriteStatus { PREPARED, WRITE_PENDING, UNKNOWN, VERIFIED }
data class WriteRecord(val key: String, val status: WriteStatus, val targetLocator: String? = null)
interface WriteStore {
    // Implement atomically; persistent Android implementation uses a unique key transaction.
    suspend fun insertIfAbsent(record: WriteRecord): WriteRecord
    suspend fun read(key: String): WriteRecord?
    suspend fun compareAndSet(expected: WriteRecord, update: WriteRecord): Boolean
}
enum class WriteDecision { MAY_PREPARE, VERIFY_EXISTING, ALREADY_VERIFIED }

class WriteLedger(private val store: WriteStore) {
    suspend fun prepare(key: String): WriteDecision {
        val record = store.insertIfAbsent(WriteRecord(key, WriteStatus.PREPARED))
        return when (record.status) {
            WriteStatus.PREPARED -> WriteDecision.MAY_PREPARE
            WriteStatus.WRITE_PENDING, WriteStatus.UNKNOWN -> WriteDecision.VERIFY_EXISTING
            WriteStatus.VERIFIED -> WriteDecision.ALREADY_VERIFIED
        }
    }
    suspend fun beforeSave(key: String) {
        val record = requireNotNull(store.read(key))
        check(record.status == WriteStatus.PREPARED)
        check(store.compareAndSet(record, record.copy(status = WriteStatus.WRITE_PENDING))) { "Concurrent writer" }
    }
    suspend fun unknown(key: String) {
        val record = requireNotNull(store.read(key))
        check(record.status == WriteStatus.WRITE_PENDING)
        check(store.compareAndSet(record, record.copy(status = WriteStatus.UNKNOWN)))
    }
    suspend fun verified(key: String, reopenedLocator: String) {
        require(reopenedLocator.isNotBlank())
        val record = requireNotNull(store.read(key))
        check(record.status in setOf(WriteStatus.WRITE_PENDING, WriteStatus.UNKNOWN))
        check(store.compareAndSet(record, record.copy(status = WriteStatus.VERIFIED, targetLocator = reopenedLocator)))
    }
}
