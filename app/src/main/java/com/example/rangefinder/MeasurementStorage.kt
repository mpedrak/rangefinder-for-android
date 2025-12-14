package com.example.rangefinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MeasurementStorage(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).measurementDao()
    private val imagesDir = File(context.filesDir, "rangefinder_images").apply { mkdirs() }

    fun saveMeasurement(
        bitmap: Bitmap, distance: Float?, distanceLabel: String, cameraMode: String
    ): RangefinderMeasurement = runBlocking {
        val id = UUID.randomUUID().toString()
        val imageFile = File(imagesDir, "$id.jpg")
        
        addDistanceLabel(bitmap, distanceLabel).compress(Bitmap.CompressFormat.JPEG, 95, 
            FileOutputStream(imageFile))
        
        val measurement = RangefinderMeasurement(
            id, imageFile.absolutePath, distance, distanceLabel, 
            System.currentTimeMillis(), cameraMode
        )
        
        dao.insert(MeasurementEntity.fromRangefinderMeasurement(measurement))
        measurement
    }

    private fun addDistanceLabel(bitmap: Bitmap, distanceLabel: String): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = bitmap.height * 0.05f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        
        val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        
        val textWidth = textPaint.measureText(distanceLabel)
        val textHeight = textPaint.textSize
        val padding = 20f
        val x = (bitmap.width - textWidth) / 2
        val y = bitmap.height * 0.08f
        
        canvas.drawRoundRect(
            x - padding, y - textHeight - padding / 2,
            x + textWidth + padding, y + padding / 2,
            15f, 15f, bgPaint
        )
        canvas.drawText(distanceLabel, x, y, textPaint)
        
        return mutableBitmap
    }

    fun getAllMeasurements() = runBlocking {
        dao.getAllSync().map { it.toRangefinderMeasurement() }
    }

    fun deleteMeasurement(id: String) = runBlocking {
        dao.getById(id)?.let { File(it.imagePath).delete() }
        dao.deleteById(id)
    }

    fun getMeasurementsSortedByDistance(ascending: Boolean = true) = runBlocking {
        (if (ascending) dao.getAllByDistanceAscending() else dao.getAllByDistanceDescending())
            .first().map { it.toRangefinderMeasurement() }
    }

    fun getMeasurementsSortedByDate(newestFirst: Boolean = true) = runBlocking {
        (if (newestFirst) dao.getAllByDateDescending() else dao.getAllByDateAscending())
            .first().map { it.toRangefinderMeasurement() }
    }
}
