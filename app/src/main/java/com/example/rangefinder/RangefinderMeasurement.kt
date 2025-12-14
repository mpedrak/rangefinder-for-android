package com.example.rangefinder

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Parcelize
data class RangefinderMeasurement(
    val id: String,
    val imagePath: String,
    val distance: Float?,  // null for infinity
    val distanceLabel: String,
    val timestamp: Long,
    val cameraMode: String
) : Parcelable {
    
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

