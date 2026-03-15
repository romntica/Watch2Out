// [Module: :wear]
package com.jinn.watch2out.wear.data

import com.jinn.watch2out.shared.model.WatchSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A high-performance, circular buffer for storing sensor data.
 * Designed to be memory-efficient and thread-safe for use in onSensorChanged.
 */
class DataLogger {

    private val mutex = Mutex()

    // Pre-allocated array for sensor data. The size will be dynamically adjusted.
    private lateinit var buffer: FloatArray
    private var capacity: Int = 0
    private var currentIndex: Int = 0

    // The number of float values per sensor reading (12 channels as per AGENTS.md)
    private val columns = 12

    /**
     * Reconfigures the logger with new settings. This clears the existing buffer.
     */
    suspend fun reconfigure(settings: WatchSettings) = mutex.withLock {
        val newCapacity = (settings.bufferSeconds * 1000) / 20 // Assuming 20Hz/50ms default for now
        if (newCapacity != capacity) {
            capacity = newCapacity
            buffer = FloatArray(capacity * columns)
            currentIndex = 0
        }
    }

    /**
     * Adds a new sensor data record. This must NOT allocate memory.
     */
    suspend fun addRecord(record: FloatArray) {
        if (!::buffer.isInitialized || record.size != columns) return

        mutex.withLock {
            val startIndex = (currentIndex % capacity) * columns
            record.copyInto(buffer, startIndex)
            currentIndex++
        }
    }

    /**
     * Returns a snapshot of the buffered data, ordered from oldest to newest.
     */
    suspend fun getOrderedSnapshot(): List<FloatArray> = mutex.withLock {
        if (!::buffer.isInitialized) return emptyList()

        val sortedData = mutableListOf<FloatArray>()
        val start = if (currentIndex < capacity) 0 else currentIndex % capacity
        val count = minOf(currentIndex, capacity)

        for (i in 0 until count) {
            val readIndex = (start + i) % capacity
            val record = FloatArray(columns)
            buffer.copyInto(record, 0, readIndex * columns, (readIndex + 1) * columns)
            sortedData.add(record)
        }
        return sortedData
    }
}
