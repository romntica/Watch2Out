// [Module: :wear]
package com.jinn.watch2out.wear.data

import com.jinn.watch2out.shared.model.WatchSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A high-performance, circular buffer for storing sensor data.
 * Updated to respect dynamic sampling rates.
 */
class DataLogger {

    private val mutex = Mutex()

    // Pre-allocated array for sensor data.
    private lateinit var buffer: FloatArray
    private var capacity: Int = 0
    private var currentIndex: Long = 0

    // 12 channels: Accel(3), Gyro(3), Pressure(1), Rotation(4), GPS_Speed(1)
    private val columns = 12

    /**
     * Reconfigures the logger with new settings.
     * Capacity is calculated based on bufferSeconds and samplingRateMs.
     */
    suspend fun reconfigure(settings: WatchSettings) = mutex.withLock {
        val rate = settings.samplingRateMs.coerceAtLeast(20)
        val newCapacity = (settings.bufferSeconds * 1000) / rate
        
        if (newCapacity != capacity) {
            capacity = newCapacity
            buffer = FloatArray(capacity * columns)
            currentIndex = 0
        }
    }

    /**
     * Adds a new sensor data record.
     */
    suspend fun addRecord(record: FloatArray) {
        if (!::buffer.isInitialized || capacity == 0 || record.size != columns) return

        mutex.withLock {
            val startIndex = ((currentIndex % capacity).toInt()) * columns
            record.copyInto(buffer, startIndex)
            currentIndex++
        }
    }

    /**
     * Returns a snapshot of the buffered data, ordered from oldest to newest.
     */
    suspend fun getOrderedSnapshot(): List<FloatArray> = mutex.withLock {
        if (!::buffer.isInitialized || capacity == 0) return emptyList()

        val sortedData = mutableListOf<FloatArray>()
        val count = if (currentIndex < capacity) currentIndex.toInt() else capacity
        val start = if (currentIndex < capacity) 0 else (currentIndex % capacity).toInt()

        for (i in 0 until count) {
            val readIndex = (start + i) % capacity
            val record = FloatArray(columns)
            buffer.copyInto(record, 0, readIndex * columns, (readIndex + 1) * columns)
            sortedData.add(record)
        }
        return sortedData
    }
}
