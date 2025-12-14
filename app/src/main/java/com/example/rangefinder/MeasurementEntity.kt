package com.example.rangefinder

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey val id: String,
    val imagePath: String,
    val distance: Float?,  // null means infinity
    val distanceLabel: String,
    val timestamp: Long,
    val cameraMode: String
) {
    fun toRangefinderMeasurement(): RangefinderMeasurement {
        return RangefinderMeasurement(
            id = id,
            imagePath = imagePath,
            distance = distance,
            distanceLabel = distanceLabel,
            timestamp = timestamp,
            cameraMode = cameraMode
        )
    }
    
    companion object {
        fun fromRangefinderMeasurement(measurement: RangefinderMeasurement): MeasurementEntity {
            return MeasurementEntity(
                id = measurement.id,
                imagePath = measurement.imagePath,
                distance = measurement.distance,
                distanceLabel = measurement.distanceLabel,
                timestamp = measurement.timestamp,
                cameraMode = measurement.cameraMode
            )
        }
    }
}

