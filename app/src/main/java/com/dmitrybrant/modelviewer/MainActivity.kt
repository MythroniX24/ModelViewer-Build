package com.dmitrybrant.modelviewer

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContentResolverCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.dmitrybrant.modelviewer.databinding.ActivityMainBinding
import com.dmitrybrant.modelviewer.gvr.ModelGvrActivity
import com.dmitrybrant.modelviewer.obj.ObjModel
import com.dmitrybrant.modelviewer.ply.PlyModel
import com.dmitrybrant.modelviewer.stl.StlModel
import com.dmitrybrant.modelviewer.util.Util.closeSilently
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.CheckBox
import android.widget.TextView
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sampleModels: List<String>
    private var sampleModelIndex = 0
    private var modelView: ModelSurfaceView? = null

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && it.data?.data != null) {
            val uri = it.data?.data
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            beginLoadModel(uri!!)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            beginOpenModel()
        } else {
            Toast.makeText(this, R.string.read_permission_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.mainToolbar)

        binding.progressBar.isVisible = false
        binding.actionButton.setOnClickListener { startVrActivity() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val newStatusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val newNavBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val newCaptionBarInsets = insets.getInsets(WindowInsetsCompat.Type.captionBar())
            val newSystemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topInset = max(max(max(newStatusBarInsets.top, newCaptionBarInsets.top), newSystemBarInsets.top), newNavBarInsets.top)
            val bottomInset = max(max(max(newStatusBarInsets.bottom, newCaptionBarInsets.bottom), newSystemBarInsets.bottom), newNavBarInsets.bottom)
            binding.mainToolbarContainer.updatePadding(top = topInset)
            binding.actionButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottomInset
                leftMargin = newNavBarInsets.left
                rightMargin = newNavBarInsets.right
            }
            binding.progressBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottomInset
                leftMargin = newNavBarInsets.left
                rightMargin = newNavBarInsets.right
            }
            binding.dimensionInfoCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = bottomInset + 16
            }
            insets
        }

        sampleModels = assets.list("")!!.filter { it.endsWith(".stl") }

        if (intent.data != null && savedInstanceState == null) {
            beginLoadModel(intent.data!!)
        }
    }

    override fun onStart() {
        super.onStart()
        createNewModelView(ModelViewerApplication.currentModel)
        if (ModelViewerApplication.currentModel != null) {
            title = ModelViewerApplication.currentModel!!.title
            updateDimensionDisplay(ModelViewerApplication.currentModel!!)
        }
    }

    override fun onPause() {
        super.onPause()
        modelView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        modelView?.onResume()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_open_model -> {
                checkReadPermissionThenOpen()
                true
            }
            R.id.menu_resize_model -> {
                showResizeDialog()
                true
            }
            R.id.menu_load_sample -> {
                loadSampleModel()
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Resize feature
    // ──────────────────────────────────────────────────────────────────────────

    private fun showResizeDialog() {
        val model = ModelViewerApplication.currentModel
        if (model == null) {
            Toast.makeText(this, R.string.resize_model_no_model, Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_resize_model, null)

        val textOriginalX = dialogView.findViewById<TextView>(R.id.textOriginalX)
        val textOriginalY = dialogView.findViewById<TextView>(R.id.textOriginalY)
        val textOriginalZ = dialogView.findViewById<TextView>(R.id.textOriginalZ)
        val editX = dialogView.findViewById<TextInputEditText>(R.id.editSizeX)
        val editY = dialogView.findViewById<TextInputEditText>(R.id.editSizeY)
        val editZ = dialogView.findViewById<TextInputEditText>(R.id.editSizeZ)
        val layoutX = dialogView.findViewById<TextInputLayout>(R.id.layoutX)
        val layoutY = dialogView.findViewById<TextInputLayout>(R.id.layoutY)
        val layoutZ = dialogView.findViewById<TextInputLayout>(R.id.layoutZ)
        val checkLock = dialogView.findViewById<CheckBox>(R.id.checkLockAspect)

        val origX = model.originalSizeX
        val origY = model.originalSizeY
        val origZ = model.originalSizeZ

        // Show original dimensions
        textOriginalX.text = formatMm(origX)
        textOriginalY.text = formatMm(origY)
        textOriginalZ.text = formatMm(origZ)

        // Pre-fill with current (possibly already-scaled) dimensions
        editX.setText(formatMm(model.currentSizeXmm))
        editY.setText(formatMm(model.currentSizeYmm))
        editZ.setText(formatMm(model.currentSizeZmm))

        // Lock-aspect-ratio logic
        var isUpdatingFromLock = false

        val xWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!checkLock.isChecked || isUpdatingFromLock || origX == 0f) return
                val newX = s.toString().toFloatOrNull() ?: return
                val ratio = newX / origX
                isUpdatingFromLock = true
                editY.setText(formatMm(origY * ratio))
                editZ.setText(formatMm(origZ * ratio))
                isUpdatingFromLock = false
            }
        }

        val yWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!checkLock.isChecked || isUpdatingFromLock || origY == 0f) return
                val newY = s.toString().toFloatOrNull() ?: return
                val ratio = newY / origY
                isUpdatingFromLock = true
                editX.setText(formatMm(origX * ratio))
                editZ.setText(formatMm(origZ * ratio))
                isUpdatingFromLock = false
            }
        }

        val zWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!checkLock.isChecked || isUpdatingFromLock || origZ == 0f) return
                val newZ = s.toString().toFloatOrNull() ?: return
                val ratio = newZ / origZ
                isUpdatingFromLock = true
                editX.setText(formatMm(origX * ratio))
                editY.setText(formatMm(origY * ratio))
                isUpdatingFromLock = false
            }
        }

        editX.addTextChangedListener(xWatcher)
        editY.addTextChangedListener(yWatcher)
        editZ.addTextChangedListener(zWatcher)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.resize_model_title)
            .setView(dialogView)
            .setPositiveButton(R.string.resize_apply, null)   // Override below to prevent auto-dismiss on error
            .setNegativeButton(R.string.resize_cancel, null)
            .setNeutralButton(R.string.resize_reset, null)
            .create()

        dialog.setOnShowListener {
            // Apply button: validate → apply scale
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                layoutX.error = null
                layoutY.error = null
                layoutZ.error = null

                val newX = editX.text.toString().toFloatOrNull()
                val newY = editY.text.toString().toFloatOrNull()
                val newZ = editZ.text.toString().toFloatOrNull()

                var valid = true
                if (newX == null || newX <= 0f) { layoutX.error = "Enter a positive number"; valid = false }
                if (newY == null || newY <= 0f) { layoutY.error = "Enter a positive number"; valid = false }
                if (newZ == null || newZ <= 0f) { layoutZ.error = "Enter a positive number"; valid = false }

                if (!valid) return@setOnClickListener

                val scaleX = if (origX > 0f) newX!! / origX else 1f
                val scaleY = if (origY > 0f) newY!! / origY else 1f
                val scaleZ = if (origZ > 0f) newZ!! / origZ else 1f

                applyModelScale(model, scaleX, scaleY, scaleZ)
                dialog.dismiss()
            }

            // Reset button: restore to original (scale = 1,1,1)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                applyModelScale(model, 1f, 1f, 1f)
                Toast.makeText(this, R.string.resize_reset_done, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * Applies scale multipliers to the model and updates the dimension display.
     */
    private fun applyModelScale(model: Model, scaleX: Float, scaleY: Float, scaleZ: Float) {
        modelView?.applyModelScale(scaleX, scaleY, scaleZ)
        // Update stored values immediately so the display reflects the new size
        model.customScaleX = scaleX
        model.customScaleY = scaleY
        model.customScaleZ = scaleZ
        updateDimensionDisplay(model)
        Toast.makeText(this, R.string.resize_applied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Updates the X/Y/Z dimension pill at the bottom of the screen.
     */
    private fun updateDimensionDisplay(model: Model) {
        val x = model.currentSizeXmm
        val y = model.currentSizeYmm
        val z = model.currentSizeZmm
        if (x > 0f || y > 0f || z > 0f) {
            binding.dimensionInfoCard.visibility = View.VISIBLE
            binding.textDimX.text = formatMm(x)
            binding.textDimY.text = formatMm(y)
            binding.textDimZ.text = formatMm(z)
        } else {
            binding.dimensionInfoCard.visibility = View.GONE
        }
    }

    /** Format a float as a compact mm string (e.g. "45.2" or "120") */
    private fun formatMm(value: Float): String {
        return if (value == 0f) "0"
        else if (value < 0.1f) String.format("%.3f", value)
        else if (value < 10f) String.format("%.2f", value)
        else if (value < 100f) String.format("%.1f", value)
        else String.format("%.0f", value)
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Existing methods (unchanged except updateDimensionDisplay call)
    // ──────────────────────────────────────────────────────────────────────────

    private fun checkReadPermissionThenOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)) {
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            beginOpenModel()
        }
    }

    private fun beginOpenModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
        openDocumentLauncher.launch(intent)
    }

    private fun createNewModelView(model: Model?) {
        if (modelView != null) {
            binding.containerView.removeView(modelView)
        }
        modelView = ModelSurfaceView(this, model)
        binding.containerView.addView(modelView, 0)
    }

    private fun beginLoadModel(uri: Uri) {
        lifecycleScope.launch(CoroutineExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            Toast.makeText(applicationContext, getString(R.string.open_model_error, throwable.message), Toast.LENGTH_SHORT).show()
        }) {
            binding.progressBar.isVisible = true

            var model: Model? = null
            withContext(Dispatchers.IO) {
                var stream: InputStream? = null
                try {
                    val cr = applicationContext.contentResolver
                    val fileName = getFileName(cr, uri)
                    stream = if ("http" == uri.scheme || "https" == uri.scheme) {
                        val client = OkHttpClient()
                        val request: Request = Request.Builder().url(uri.toString()).build()
                        val response = client.newCall(request).execute()
                        ByteArrayInputStream(response.body.bytes())
                    } else {
                        cr.openInputStream(uri)
                    }
                    if (stream != null) {
                        if (!fileName.isNullOrEmpty()) {
                            model = when {
                                fileName.lowercase(Locale.ROOT).endsWith(".stl") -> StlModel(stream)
                                fileName.lowercase(Locale.ROOT).endsWith(".obj") -> ObjModel(stream)
                                fileName.lowercase(Locale.ROOT).endsWith(".ply") -> PlyModel(stream)
                                else -> StlModel(stream)
                            }
                            model!!.title = fileName
                        } else {
                            model = StlModel(stream)
                        }
                    }
                    model!!
                } finally {
                    closeSilently(stream)
                }
            }
            model?.let { setCurrentModel(it) }
            binding.progressBar.isVisible = false
        }
    }

    private fun getFileName(cr: ContentResolver, uri: Uri): String? {
        if ("content" == uri.scheme) {
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            ContentResolverCompat.query(cr, uri, projection, null, null, null, null as CancellationSignal?)?.use { metaCursor ->
                if (metaCursor.moveToFirst()) {
                    return metaCursor.getString(0)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun setCurrentModel(model: Model) {
        ModelViewerApplication.currentModel = model
        createNewModelView(model)
        Toast.makeText(applicationContext, R.string.open_model_success, Toast.LENGTH_SHORT).show()
        title = model.title
        binding.progressBar.isVisible = false
        updateDimensionDisplay(model)
    }

    private fun startVrActivity() {
        if (ModelViewerApplication.currentModel == null) {
            Toast.makeText(this, R.string.view_vr_not_loaded, Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, ModelGvrActivity::class.java))
        }
    }

    private fun loadSampleModel() {
        try {
            val stream = assets.open(sampleModels[sampleModelIndex++ % sampleModels.size])
            setCurrentModel(StlModel(stream))
            stream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.about_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
