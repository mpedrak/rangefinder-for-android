package com.example.rangefinder

import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class SavedImagesActivity : AppCompatActivity() {

    private lateinit var storage: MeasurementStorage
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var sortSpinner: Spinner
    private lateinit var backButton: Button

    private var currentSortMode = SortMode.DATE_NEWEST

    enum class SortMode(val label: String) {
        DATE_NEWEST("Date (Newest)"), DATE_OLDEST("Date (Oldest)"), DISTANCE_NEAREST("Distance (Nearest)"), DISTANCE_FARTHEST(
            "Distance (Farthest)"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        hideStatusBar()

        storage = MeasurementStorage(this)

        setupUI()
        loadMeasurements()
    }

    private fun hideStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupUI() {
        val context = this
        
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.color(R.color.background_dark))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 60, 0, 0)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 24, 48, 24)
            }
        }

        backButton = Button(context).apply {
            text = "BACK"
            textSize = 14f
            setTextColor(context.color(R.color.text_primary))
            setBackgroundColor(context.color(R.color.background_button))
            setPadding(48, 36, 48, 36)
            setOnClickListener { finish() }
        }
        headerLayout.addView(backButton)

        val titleView = TextView(context).apply {
            text = "SAVED MEASUREMENTS"
            textSize = 20f
            setTextColor(context.color(R.color.text_primary))
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                setMargins(48, 0, 0, 0)
            }
        }
        headerLayout.addView(titleView)

        rootLayout.addView(headerLayout)

        val sortLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 24, 48, 48)
            }
        }

        val sortLabel = TextView(context).apply {
            text = "Sort by: "
            textSize = 16f
            setTextColor(context.color(R.color.text_secondary))
        }
        sortLayout.addView(sortLabel)

        sortSpinner = Spinner(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(context.color(R.color.background_button))
            setPadding(36, 24, 36, 24)
        }

        val sortAdapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_item, SortMode.entries.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sortSpinner.adapter = sortAdapter
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                currentSortMode = SortMode.entries.toTypedArray()[position]
                loadMeasurements()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sortLayout.addView(sortSpinner)

        rootLayout.addView(sortLayout)

        listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            divider = null
            dividerHeight = 24
            setBackgroundColor(context.color(R.color.transparent))
            setPadding(24, 24, 24, 24)
            clipToPadding = false
        }

        emptyView = TextView(context).apply {
            text =
                "No saved measurements yet.\n\nTap the SAVE button in the camera view\nto capture a distance measurement."
            textSize = 16f
            setTextColor(context.color(R.color.text_quaternary))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply {
                setMargins(96, 192, 96, 192)
            }
        }

        rootLayout.addView(listView)
        rootLayout.addView(emptyView)

        rootLayout.post {
            Log.d(
                "SavedImagesActivity",
                "Root layout - width: ${rootLayout.width}, height: ${rootLayout.height}"
            )
            Log.d(
                "SavedImagesActivity",
                "ListView - width: ${listView.width}, height: ${listView.height}, visibility: ${listView.visibility}"
            )
        }

        setContentView(rootLayout)
    }

    private fun loadMeasurements() {
        val allMeasurements = storage.getAllMeasurements()
        Log.d(
            "SavedImagesActivity", "Total measurements loaded: ${allMeasurements.size}"
        )

        val measurements = when (currentSortMode) {
            SortMode.DATE_NEWEST -> storage.getMeasurementsSortedByDate(newestFirst = true)
            SortMode.DATE_OLDEST -> storage.getMeasurementsSortedByDate(newestFirst = false)
            SortMode.DISTANCE_NEAREST -> storage.getMeasurementsSortedByDistance(ascending = true)
            SortMode.DISTANCE_FARTHEST -> storage.getMeasurementsSortedByDistance(ascending = false)
        }

        Log.d(
            "SavedImagesActivity", "Measurements after sorting: ${measurements.size}"
        )
        measurements.forEach { m ->
            Log.d(
                "SavedImagesActivity",
                "Measurement: ${m.id}, path: ${m.imagePath}, exists: ${File(m.imagePath).exists()}"
            )
        }

        if (measurements.isEmpty()) {
            listView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            Log.d("SavedImagesActivity", "No measurements, showing empty view")
        } else {
            listView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            val adapter = MeasurementAdapter(measurements)
            listView.adapter = adapter
            Log.d("SavedImagesActivity", "Adapter set with ${adapter.count} items")
            Log.d(
                "SavedImagesActivity",
                "ListView visibility: ${listView.visibility}, height: ${listView.height}, width: ${listView.width}"
            )

            adapter.notifyDataSetChanged()
            listView.invalidateViews()
            listView.requestLayout()

            listView.post {
                Log.d(
                    "SavedImagesActivity",
                    "ListView after layout - height: ${listView.height}, childCount: ${listView.childCount}"
                )
            }
        }
    }

    private inner class MeasurementAdapter(private val items: List<RangefinderMeasurement>) :
        BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RangefinderMeasurement = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@SavedImagesActivity)
                .inflate(R.layout.item_measurement, parent, false)
            
            val item = items[position]
            val viewHolder = view.tag as? ViewHolder ?: ViewHolder(view).also { view.tag = it }

            viewHolder.bind(item)

            return view
        }

        private inner class ViewHolder(view: View) {
            val cardLayout: LinearLayout = view as LinearLayout
            val imageView: ImageView = view.findViewById(R.id.imageView)
            val distanceView: TextView = view.findViewById(R.id.distanceView)
            val dateView: TextView = view.findViewById(R.id.dateView)
            val deleteButton: Button = view.findViewById(R.id.deleteButton)

            fun bind(item: RangefinderMeasurement) {
                distanceView.text = item.distanceLabel
                dateView.text = item.getFormattedDate()
                cardLayout.setOnClickListener { showFullImage(item) }
                deleteButton.setOnClickListener { showDeleteConfirmation(item) }
                
                imageView.setImageBitmap(
                    File(item.imagePath).takeIf { it.exists() }?.let {
                        BitmapFactory.decodeFile(it.absolutePath, BitmapFactory.Options().apply {
                            inSampleSize = 4
                        })
                    }
                )
            }
        }
    }

    private fun showDeleteConfirmation(measurement: RangefinderMeasurement) {
        AlertDialog.Builder(this).setTitle("Delete Measurement")
            .setMessage("Are you sure you want to delete this measurement?")
            .setPositiveButton("Delete") { _, _ ->
                storage.deleteMeasurement(measurement.id)
                loadMeasurements()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showFullImage(measurement: RangefinderMeasurement) {
        Log.d("SavedImagesActivity", "Showing full image: ${measurement.imagePath}")

        val bitmap = BitmapFactory.decodeFile(measurement.imagePath)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val context = this
        
        val layout = FrameLayout(context).apply {
            setBackgroundColor(context.color(R.color.background_black))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setBackgroundColor(context.color(R.color.background_black))
        }
        layout.addView(imageView)

        val closeButton = Button(context).apply {
            text = "CLOSE"
            textSize = 14f
            setTextColor(context.color(R.color.text_primary))
            setBackgroundColor(context.color(R.color.overlay_semi_transparent))
            setPadding(72, 36, 72, 36)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(48, 144, 48, 48)
            }
            setOnClickListener { dialog.dismiss() }
        }
        layout.addView(closeButton)
        dialog.setContentView(layout)
        dialog.show()

        dialog.window?.setBackgroundDrawableResource(android.R.color.black)

        imageView.post {
            imageView.invalidate()
            imageView.requestLayout()
        }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        loadMeasurements()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }
}

