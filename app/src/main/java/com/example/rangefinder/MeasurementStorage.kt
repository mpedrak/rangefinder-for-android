package com.example.rangefinder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MeasurementStorage(private val context: Context) {
    private val dao = AppDatabase.getDatabase(context).measurementDao()
    private val imagesDir = File(context.filesDir, "rangefinder_images").apply { mkdirs() }

    fun saveMeasurement(
        jpegBytes: ByteArray, distance: Float?, distanceLabel: String, cameraMode: String
    ): RangefinderMeasurement = runBlocking {
        val id = UUID.randomUUID().toString()
        val imageFile = File(imagesDir, "$id.jpg")
        
        val originalExif = ExifInterface(java.io.ByteArrayInputStream(jpegBytes))
        val orientation = originalExif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        
        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) 
            ?: return@runBlocking RangefinderMeasurement("", "", null, "", 0, "")
        
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        
        if (rotation != 0f) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation)
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        
        val labeledBitmap = addDistanceLabel(bitmap, distanceLabel)
        FileOutputStream(imageFile).use { out ->
            labeledBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        
        // Set EXIF to NORMAL since bitmap is now correctly oriented
        val savedExif = ExifInterface(imageFile.absolutePath)
        savedExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        savedExif.saveAttributes()
        
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
        
        val circleRadius = bitmap.height * 0.065f
        
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        val circlePaint = Paint().apply {
            color = Color.parseColor("#FF00FF00")
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, bitmap.height * 0.001f)
            isAntiAlias = true
            alpha = (255 * 0.7).toInt()
        }
        canvas.drawCircle(centerX, centerY, circleRadius, circlePaint)
        
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
