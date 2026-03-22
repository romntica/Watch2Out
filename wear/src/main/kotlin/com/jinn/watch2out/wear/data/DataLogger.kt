// [Module: :wear]
package com.jinn.watch2out.wear.data

import com.jinn.watch2out.shared.model.WatchSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A high-performance, circular buffer for storing sensor data.
 * v23.6: Includes absolute Long timestamps for professional analysis.
 */
class DataLogger {

    private val mutex = Mutex()

    private lateinit var sensorBuffer: FloatArray
    private lateinit var timeBuffer: LongArray
    private var capacity: Int = 0
    private var currentIndex: Long = 0

    // 12 sensor channels
    private val columns = 12

    suspend fun reconfigure(settings: WatchSettings) = mutex.withLock {
        val rate = settings.samplingRateMs.coerceAtLeast(20)
        val newCapacity = (settings.bufferSeconds * 1000) / rate
        
        if (newCapacity != capacity) {
            capacity = newCapacity
            sensorBuffer = FloatArray(capacity * columns)
            timeBuffer = LongArray(capacity)
            currentIndex = 0
        }
    }

    /**
     * Adds a new sensor data record.
     */
    suspend fun addRecord(timestamp: Long, record: FloatArray) {
        if (!::sensorBuffer.isInitialized || capacity == 0 || record.size != columns) return

        mutex.withLock {
            val idx = (currentIndex % capacity).toInt()
            record.copyInto(sensorBuffer, idx * columns)
            timeBuffer[idx] = timestamp
            currentIndex++
        }
    }

    /**
     * Returns a snapshot of the buffered data with timestamps.
     */
    suspend fun getOrderedSnapshot(): List<Pair<Long, FloatArray>> = mutex.withLock {
        if (!::sensorBuffer.isInitialized || capacity == 0) return emptyList()

        val result = mutableListOf<Pair<Long, FloatArray>>()
        val count = if (currentIndex < capacity) currentIndex.toInt() else capacity
        val start = if (currentIndex < capacity) 0 else (currentIndex % capacity).toInt()

        for (i in 0 until count) {
            val readIdx = (start + i) % capacity
            val sensors = FloatArray(columns)
            sensorBuffer.copyInto(sensors, 0, readIdx * columns, (readIdx + 1) * columns)
            result.add(timeBuffer[readIdx] to sensors)
        }
        return result
    }
}
