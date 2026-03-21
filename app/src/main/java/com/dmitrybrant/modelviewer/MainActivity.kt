package com.dmitrybrant.modelviewer

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
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
import com.dmitrybrant.modelviewer.obj.ObjModel
import com.dmitrybrant.modelviewer.ply.PlyModel
import com.dmitrybrant.modelviewer.stl.StlModel
import com.dmitrybrant.modelviewer.util.Util.closeSilently
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sampleModels: List<String>
    private var sampleModelIndex = 0
    private var modelView: ModelSurfaceView? = null
    private val ruler = RulerTool()
    private var rulerMode = false

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && it.data?.data != null) {
            val uri = it.data?.data!!
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            checkFileThenLoad(uri)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) beginOpenModel() else Toast.makeText(this, R.string.read_permission_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.mainToolbar)
        binding.progressBar.isVisible = false
        binding.loadingText.isVisible = false
        binding.resetViewButton.setOnClickListener { modelView?.resetView() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cap = insets.getInsets(WindowInsetsCompat.Type.captionBar())
            val top = max(max(max(sb.top, cap.top), sys.top), nb.top)
            val bot = max(max(max(sb.bottom, cap.bottom), sys.bottom), nb.bottom)
            binding.mainToolbarContainer.updatePadding(top = top)
            binding.resetViewButton.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bot+16; rightMargin = nb.right+16 }
            binding.progressBar.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bot; leftMargin = nb.left }
            binding.dimensionInfoCard.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bot+72 }
            insets
        }

        sampleModels = assets.list("")!!.filter { it.endsWith(".stl") }
        if (intent.data != null && savedInstanceState == null) checkFileThenLoad(intent.data!!)
    }

    override fun onStart() {
        super.onStart()
        createNewModelView(ModelViewerApplication.currentModel)
        ModelViewerApplication.currentModel?.let { title = it.title; updateDimensionDisplay(it) }
    }

    override fun onPause() { super.onPause(); modelView?.onPause() }
    override fun onResume() { super.onResume(); modelView?.onResume() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu); return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_open_model   -> { checkReadPermissionThenOpen(); true }
            R.id.menu_resize_model -> { showResizeDialog(); true }
            R.id.menu_resize_part  -> { showResizePartDialog(); true }
            R.id.menu_ruler_tool   -> { toggleRulerMode(); true }
            R.id.menu_export_model -> { showExportDialog(); true }
            R.id.menu_screenshot   -> { takeScreenshot(); true }
            R.id.menu_model_color  -> { showModelColorDialog(); true }
            R.id.menu_bg_color     -> { showBgColorDialog(); true }
            R.id.menu_model_info   -> { showModelInfo(); true }
            R.id.menu_load_sample  -> { loadSampleModel(); true }
            R.id.menu_about        -> { showAboutDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Ruler ─────────────────────────────────────────────────────────────────
    private fun toggleRulerMode() {
        val model = ModelViewerApplication.currentModel
        if (model == null) { Toast.makeText(this, R.string.export_no_model, Toast.LENGTH_SHORT).show(); return }
        rulerMode = !rulerMode
        ruler.reset()
        binding.rulerOverlay.reset()
        if (rulerMode) {
            binding.rulerInfoCard.visibility = View.VISIBLE
            binding.rulerOverlay.visibility = View.VISIBLE
            binding.rulerStatusText.text = getString(R.string.ruler_tap_first)
            binding.rulerDistanceText.visibility = View.GONE
            Toast.makeText(this, "Ruler ON — tap 2 points on model", Toast.LENGTH_SHORT).show()
        } else {
            binding.rulerInfoCard.visibility = View.GONE
            binding.rulerOverlay.visibility = View.GONE
        }
    }

    private fun handleRulerTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val model = ModelViewerApplication.currentModel ?: return
        val mv = modelView ?: return
        val ndcX = (x / viewWidth) * 2f - 1f
        val ndcY = 1f - (y / viewHeight) * 2f
        val renderer = mv.renderer
        val hit = ruler.pickPoint(ndcX, ndcY, x, y, renderer.viewMatrix, renderer.projectionMatrix, model)

        if (!hit) { Toast.makeText(this, R.string.ruler_no_hit, Toast.LENGTH_SHORT).show(); return }

        when {
            ruler.pointA != null && ruler.pointB == null -> {
                // First point set
                ruler.pointAScreen?.let { binding.rulerOverlay.setPointA(it.first, it.second) }
                binding.rulerStatusText.text = getString(R.string.ruler_tap_second)
            }
            ruler.pointA != null && ruler.pointB != null -> {
                // Both points set - show distance
                ruler.pointBScreen?.let { binding.rulerOverlay.setPointB(it.first, it.second) }
                val dist = ruler.distance * model.customScaleX
                binding.rulerDistanceText.text = "📏 ${formatMm(dist)} mm"
                binding.rulerDistanceText.visibility = View.VISIBLE
                binding.rulerStatusText.text = "Tap again to remeasure"
                // Reset for next measurement
                val a = ruler.pointA; val b = ruler.pointB
                ruler.reset()
                binding.rulerOverlay.reset()
                // Show final line briefly then reset
                a?.let {}; b?.let {}
            }
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────
    private fun showExportDialog() {
        val model = ModelViewerApplication.currentModel
        if (model == null) { Toast.makeText(this, R.string.export_no_model, Toast.LENGTH_SHORT).show(); return }
        val formats = arrayOf("STL (Binary) — 3D Printing", "OBJ (Text) — Universal", "PLY (Binary) — Point Cloud")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_menu)
            .setItems(formats) { _, which ->
                val fmt = when (which) {
                    0 -> ModelExporter.Format.STL
                    1 -> ModelExporter.Format.OBJ
                    else -> ModelExporter.Format.PLY
                }
                exportModel(model, fmt)
            }.show()
    }

    private fun exportModel(model: Model, format: ModelExporter.Format) {
        if (model !is ArrayModel) { Toast.makeText(this, "Export not supported.", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(CoroutineExceptionHandler { _, t ->
            Toast.makeText(applicationContext, getString(R.string.export_error, t.message), Toast.LENGTH_LONG).show()
            binding.progressBar.isVisible=false; binding.loadingText.isVisible=false
        }) {
            binding.progressBar.isVisible=true; binding.loadingText.isVisible=true
            binding.loadingText.text=getString(R.string.exporting_model, 0)
            withContext(Dispatchers.IO) {
                val ext = ModelExporter.fileExtension(format)
                val mime = ModelExporter.mimeType(format)
                val base = model.title.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifEmpty { "model" }
                val fileName = "${base}_${formatMm(model.currentSizeXmm)}x${formatMm(model.currentSizeYmm)}x${formatMm(model.currentSizeZmm)}mm.$ext"
                val progressUpdate: (Int) -> Unit = { p -> runOnUiThread { binding.loadingText.text = getString(R.string.exporting_model, p) } }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create file.")
                    contentResolver.openOutputStream(uri)?.use {
                        if (format == ModelExporter.Format.STL) {
                            StlExporter.export(model, it, contentResolver, ModelViewerApplication.currentModelUri, progressUpdate)
                        } else {
                            ModelExporter.export(model, it, format, progressUpdate)
                        }
                    }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use {
                        if (format == ModelExporter.Format.STL) {
                            StlExporter.export(model, it, contentResolver, ModelViewerApplication.currentModelUri, progressUpdate)
                        } else {
                            ModelExporter.export(model, it, format, progressUpdate)
                        }
                    }
                }
                fileName
            }.let { fileName ->
                binding.progressBar.isVisible=false; binding.loadingText.isVisible=false
                Toast.makeText(applicationContext, getString(R.string.export_success, fileName), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Large file — user choice ──────────────────────────────────────────────
    private fun checkFileThenLoad(uri: Uri) {
        val fileSize = getFileSize(uri)
        ModelViewerApplication.currentModelFileSize = fileSize
        if (fileSize > StlModel.LARGE_FILE_THRESHOLD) {
            val sizeMb = fileSize / (1024 * 1024)
            val sizeStr = if (sizeMb >= 1000) "${sizeMb/1024}GB" else "${sizeMb}MB"
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.large_file_title, sizeStr))
                .setMessage(R.string.large_file_msg)
                .setPositiveButton(R.string.large_file_half) { _, _ ->
                    ModelViewerApplication.wantDecimate = true
                    beginLoadModel(uri)
                }
                .setNeutralButton(R.string.large_file_full) { _, _ ->
                    ModelViewerApplication.wantDecimate = false
                    beginLoadModel(uri)
                }
                .setNegativeButton(R.string.large_file_cancel, null)
                .show()
        } else {
            ModelViewerApplication.wantDecimate = false
            beginLoadModel(uri)
        }
    }

    private fun getFileSize(uri: Uri): Long {
        return try {
            if (uri.scheme == "content") contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            else File(uri.path ?: "").length()
        } catch (e: Exception) { 0L }
    }

    // ── Colors ────────────────────────────────────────────────────────────────
    private fun showModelColorDialog() {
        val colors = arrayOf("Default (Blue-Grey)","White","Silver","Gold","Red","Green","Blue","Orange","Pink")
        val rgb = arrayOf(Triple(0.7f,0.7f,0.85f),Triple(0.95f,0.95f,0.95f),Triple(0.75f,0.75f,0.75f),Triple(0.83f,0.68f,0.21f),Triple(0.85f,0.2f,0.2f),Triple(0.2f,0.75f,0.3f),Triple(0.2f,0.4f,0.85f),Triple(0.9f,0.5f,0.1f),Triple(0.9f,0.4f,0.6f))
        MaterialAlertDialogBuilder(this).setTitle(R.string.model_color).setItems(colors) { _, w -> val (r,g,b)=rgb[w]; modelView?.setModelColor(r,g,b) }.show()
    }

    private fun showBgColorDialog() {
        val colors = arrayOf("Dark Grey","Black","White","Navy Blue","Dark Green","Deep Purple")
        val rgb = arrayOf(Triple(0.2f,0.2f,0.2f),Triple(0f,0f,0f),Triple(1f,1f,1f),Triple(0.05f,0.05f,0.2f),Triple(0.05f,0.15f,0.05f),Triple(0.15f,0.05f,0.2f))
        MaterialAlertDialogBuilder(this).setTitle(R.string.bg_color).setItems(colors) { _, w -> val (r,g,b)=rgb[w]; modelView?.setBackgroundColor(r,g,b) }.show()
    }

    // ── Model Info ────────────────────────────────────────────────────────────
    private fun showModelInfo() {
        val model = ModelViewerApplication.currentModel
        if (model==null){Toast.makeText(this,R.string.model_info_no_model,Toast.LENGTH_SHORT).show();return}
        val fileSizeMb = if (model.fileSizeBytes>0) "%.1f MB".format(model.fileSizeBytes/1024f/1024f) else "Unknown"
        val displayTri = formatNumber(model.displayTriangleCount)
        val origTri = if (model.originalTriangleCount>0) formatNumber(model.originalTriangleCount) else displayTri
        val partsInfo = if (model.parts.isNotEmpty()) "\nParts: ${model.parts.size} components" else ""
        val decimatedNote = if (model.isDecimated) "\n⚠️ Preview at 50% quality\nExport = full $origTri triangles" else ""
        val msg = "File: ${model.title}\nSize: $fileSizeMb\nTriangles shown: $displayTri\nOriginal: $origTri\nDimensions: ${formatMm(model.originalSizeX)}×${formatMm(model.originalSizeY)}×${formatMm(model.originalSizeZ)} mm$partsInfo$decimatedNote"
        MaterialAlertDialogBuilder(this).setTitle(R.string.model_info).setMessage(msg).setPositiveButton(android.R.string.ok,null).show()
    }

    private fun formatNumber(n: Int): String = when { n>=1_000_000->"%.1fM".format(n/1_000_000f); n>=1_000->"%.1fK".format(n/1_000f); else->n.toString() }

    // ── Screenshot ────────────────────────────────────────────────────────────
    private fun takeScreenshot() {
        if (ModelViewerApplication.currentModel==null){Toast.makeText(this,R.string.screenshot_no_model,Toast.LENGTH_SHORT).show();return}
        // Hide ruler overlay for clean screenshot
        val rulerWasVisible = binding.rulerOverlay.visibility == View.VISIBLE
        binding.rulerOverlay.visibility = View.GONE
        modelView?.captureScreenshot { bitmap ->
            runOnUiThread {
                if (rulerWasVisible) binding.rulerOverlay.visibility = View.VISIBLE
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val fileName = "3DViewer_${System.currentTimeMillis()}.png"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,fileName);put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES);put(MediaStore.Images.Media.IS_PENDING,1)}
                            val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:throw IOException("Could not create image.")
                            contentResolver.openOutputStream(uri)?.use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);contentResolver.update(uri,values,null,null)
                        } else {
                            val dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);dir.mkdirs()
                            FileOutputStream(File(dir,fileName)).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                        }
                        withContext(Dispatchers.Main){Toast.makeText(applicationContext,R.string.screenshot_saved,Toast.LENGTH_SHORT).show()}
                    }catch(e:Exception){withContext(Dispatchers.Main){Toast.makeText(applicationContext,getString(R.string.screenshot_error,e.message),Toast.LENGTH_LONG).show()}}
                    finally{bitmap.recycle()}
                }
            }
        }
    }

    // ── Resize whole model ────────────────────────────────────────────────────
    private fun showResizeDialog() {
        val model = ModelViewerApplication.currentModel
        if (model==null){Toast.makeText(this,R.string.resize_model_no_model,Toast.LENGTH_SHORT).show();return}
        showResizeDialogInternal(
            title = getString(R.string.resize_model_title),
            origX = model.originalSizeX, origY = model.originalSizeY, origZ = model.originalSizeZ,
            curX = model.currentSizeXmm, curY = model.currentSizeYmm, curZ = model.currentSizeZmm,
            onApply = { nx, ny, nz ->
                applyModelScale(model,
                    if(model.originalSizeX>0f)nx/model.originalSizeX else 1f,
                    if(model.originalSizeY>0f)ny/model.originalSizeY else 1f,
                    if(model.originalSizeZ>0f)nz/model.originalSizeZ else 1f)
            },
            onReset = { applyModelScale(model,1f,1f,1f) }
        )
    }

    private fun showResizePartDialog() {
        val model = ModelViewerApplication.currentModel
        if (model==null){Toast.makeText(this,R.string.resize_model_no_model,Toast.LENGTH_SHORT).show();return}
        val parts = model.parts
        if (parts.isEmpty()){Toast.makeText(this,R.string.parts_no_parts,Toast.LENGTH_SHORT).show();return}
        val names = parts.mapIndexed{i,p->"Part ${i+1}  (${formatMm(p.sizeX)}×${formatMm(p.sizeY)}×${formatMm(p.sizeZ)} mm)"}.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.parts_title, parts.size))
            .setItems(names) { _, which ->
                val part = parts[which]
                showResizeDialogInternal(
                    title = getString(R.string.parts_resize_title, which+1),
                    origX = part.sizeX, origY = part.sizeY, origZ = part.sizeZ,
                    curX = part.currentSizeX, curY = part.currentSizeY, curZ = part.currentSizeZ,
                    onApply = { nx,ny,nz ->
                        part.scaleX=if(part.sizeX>0f)nx/part.sizeX else 1f
                        part.scaleY=if(part.sizeY>0f)ny/part.sizeY else 1f
                        part.scaleZ=if(part.sizeZ>0f)nz/part.sizeZ else 1f
                        modelView?.applyPartScale(which, part.scaleX, part.scaleY, part.scaleZ)
                        Toast.makeText(this,R.string.resize_applied,Toast.LENGTH_SHORT).show()
                    },
                    onReset = {
                        part.scaleX=1f; part.scaleY=1f; part.scaleZ=1f
                        modelView?.applyPartScale(which, 1f, 1f, 1f)
                        Toast.makeText(this,R.string.resize_reset_done,Toast.LENGTH_SHORT).show()
                    }
                )
            }.show()
    }

    private fun showResizeDialogInternal(
        title: String, origX: Float, origY: Float, origZ: Float,
        curX: Float, curY: Float, curZ: Float,
        onApply: (Float, Float, Float) -> Unit, onReset: () -> Unit
    ) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_resize_model, null)
        val textOrigX = dialogView.findViewById<TextView>(R.id.textOriginalX)
        val textOrigY = dialogView.findViewById<TextView>(R.id.textOriginalY)
        val textOrigZ = dialogView.findViewById<TextView>(R.id.textOriginalZ)
        val editX = dialogView.findViewById<TextInputEditText>(R.id.editSizeX)
        val editY = dialogView.findViewById<TextInputEditText>(R.id.editSizeY)
        val editZ = dialogView.findViewById<TextInputEditText>(R.id.editSizeZ)
        val layoutX = dialogView.findViewById<TextInputLayout>(R.id.layoutX)
        val layoutY = dialogView.findViewById<TextInputLayout>(R.id.layoutY)
        val layoutZ = dialogView.findViewById<TextInputLayout>(R.id.layoutZ)
        val checkLock = dialogView.findViewById<CheckBox>(R.id.checkLockAspect)
        textOrigX.text=formatMm(origX); textOrigY.text=formatMm(origY); textOrigZ.text=formatMm(origZ)
        editX.setText(formatMm(curX)); editY.setText(formatMm(curY)); editZ.setText(formatMm(curZ))
        var upd=false
        editX.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}; override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:Editable?){if(!checkLock.isChecked||upd||origX==0f)return;val v=s.toString().toFloatOrNull()?:return;upd=true;editY.setText(formatMm(origY*v/origX));editZ.setText(formatMm(origZ*v/origX));upd=false}})
        editY.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}; override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:Editable?){if(!checkLock.isChecked||upd||origY==0f)return;val v=s.toString().toFloatOrNull()?:return;upd=true;editX.setText(formatMm(origX*v/origY));editZ.setText(formatMm(origZ*v/origY));upd=false}})
        editZ.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}; override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:Editable?){if(!checkLock.isChecked||upd||origZ==0f)return;val v=s.toString().toFloatOrNull()?:return;upd=true;editX.setText(formatMm(origX*v/origZ));editY.setText(formatMm(origY*v/origZ));upd=false}})
        val dialog=MaterialAlertDialogBuilder(this).setTitle(title).setView(dialogView)
            .setPositiveButton(R.string.resize_apply,null).setNegativeButton(R.string.resize_cancel,null).setNeutralButton(R.string.resize_reset,null).create()
        dialog.setOnShowListener{
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                layoutX.error=null;layoutY.error=null;layoutZ.error=null
                val nx=editX.text.toString().toFloatOrNull();val ny=editY.text.toString().toFloatOrNull();val nz=editZ.text.toString().toFloatOrNull()
                var ok=true
                if(nx==null||nx<=0f){layoutX.error="Enter positive number";ok=false}
                if(ny==null||ny<=0f){layoutY.error="Enter positive number";ok=false}
                if(nz==null||nz<=0f){layoutZ.error="Enter positive number";ok=false}
                if(!ok)return@setOnClickListener
                onApply(nx!!,ny!!,nz!!); dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener{ onReset(); dialog.dismiss() }
        }
        dialog.show()
    }

    private fun applyModelScale(model:Model,sx:Float,sy:Float,sz:Float){
        modelView?.applyModelScale(sx,sy,sz); model.customScaleX=sx; model.customScaleY=sy; model.customScaleZ=sz
        updateDimensionDisplay(model); Toast.makeText(this,R.string.resize_applied,Toast.LENGTH_SHORT).show()
    }

    private fun updateDimensionDisplay(model:Model){
        val x=model.currentSizeXmm;val y=model.currentSizeYmm;val z=model.currentSizeZmm
        if(x>0f||y>0f||z>0f){binding.dimensionInfoCard.visibility=View.VISIBLE;binding.textDimX.text=formatMm(x);binding.textDimY.text=formatMm(y);binding.textDimZ.text=formatMm(z)}
        else binding.dimensionInfoCard.visibility=View.GONE
    }

    private fun formatMm(v:Float):String=when{v==0f->"0";v<0.1f->String.format("%.3f",v);v<10f->String.format("%.2f",v);v<100f->String.format("%.1f",v);else->String.format("%.0f",v)}

    // ── Load ──────────────────────────────────────────────────────────────────
    private fun checkReadPermissionThenOpen(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.M&&Build.VERSION.SDK_INT<Build.VERSION_CODES.Q&&
            ContextCompat.checkSelfPermission(this,Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
            requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        else beginOpenModel()
    }

    private fun beginOpenModel(){openDocumentLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"))}

    private fun createNewModelView(model:Model?){
        modelView?.let{binding.containerView.removeView(it)}
        val mv = ModelSurfaceView(this, model)
        mv.setOnTouchInterceptListener { event ->
            if (rulerMode && event.action == MotionEvent.ACTION_DOWN) {
                handleRulerTap(event.x, event.y, mv.width, mv.height); true
            } else false
        }
        modelView = mv
        binding.containerView.addView(mv, 0)
    }

    private fun beginLoadModel(uri:Uri){
        lifecycleScope.launch(CoroutineExceptionHandler{_,t->
            Toast.makeText(applicationContext,getString(R.string.open_model_error,t.message),Toast.LENGTH_SHORT).show()
            binding.progressBar.isVisible=false;binding.loadingText.isVisible=false
        }){
            binding.progressBar.isVisible=true;binding.loadingText.isVisible=true
            binding.loadingText.text=getString(R.string.loading_model,0)
            var model:Model?=null
            val fileSize=ModelViewerApplication.currentModelFileSize
            val wantDecimate=ModelViewerApplication.wantDecimate
            withContext(Dispatchers.IO){
                var stream:InputStream?=null
                try{
                    val cr=applicationContext.contentResolver;val fileName=getFileName(cr,uri)
                    stream=if(uri.scheme=="http"||uri.scheme=="https") ByteArrayInputStream(OkHttpClient().newCall(Request.Builder().url(uri.toString()).build()).execute().body.bytes())
                    else cr.openInputStream(uri)
                    if(stream!=null){
                        val progressCb:(Int)->Unit={p->runOnUiThread{binding.loadingText.text=getString(R.string.loading_model,p)}}
                        // Pass fileSize=0 for full quality, actual size for decimated
                        val effectiveSize = if (wantDecimate) fileSize else 0L
                        model=when{
                            fileName?.lowercase(Locale.ROOT)?.endsWith(".stl")==true->StlModel(stream, fileSize, wantDecimate, progressCb)
                            fileName?.lowercase(Locale.ROOT)?.endsWith(".obj")==true->ObjModel(stream)
                            fileName?.lowercase(Locale.ROOT)?.endsWith(".ply")==true->PlyModel(stream)
                            else->StlModel(stream, fileSize, wantDecimate, progressCb)
                        }
                        if(!fileName.isNullOrEmpty())model!!.title=fileName
                        model!!.fileSizeBytes=fileSize
                    }
                    model!!
                }finally{closeSilently(stream)}
            }
            ModelViewerApplication.currentModelUri=uri
            model?.let{setCurrentModel(it)}
            binding.progressBar.isVisible=false;binding.loadingText.isVisible=false
        }
    }

    private fun getFileName(cr:ContentResolver,uri:Uri):String?{
        if(uri.scheme=="content"){ContentResolverCompat.query(cr,uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null,null as CancellationSignal?)?.use{if(it.moveToFirst())return it.getString(0)}}
        return uri.lastPathSegment
    }

    private fun setCurrentModel(model:Model){
        ModelViewerApplication.currentModel=model;createNewModelView(model)
        title=model.title;updateDimensionDisplay(model)
        binding.resetViewButton.visibility=View.VISIBLE
        val info=if(model.isDecimated)"50% preview${if(model.parts.isNotEmpty())" | ${model.parts.size} parts" else ""}" else "Loaded${if(model.parts.isNotEmpty())" | ${model.parts.size} parts" else ""}"
        Toast.makeText(applicationContext,info,Toast.LENGTH_LONG).show()
    }

    private fun loadSampleModel(){
        try{val stream=assets.open(sampleModels[sampleModelIndex++%sampleModels.size]);setCurrentModel(StlModel(stream));stream.close()}
        catch(e:IOException){e.printStackTrace()}
    }

    private fun showAboutDialog(){MaterialAlertDialogBuilder(this).setTitle(R.string.app_name).setMessage(R.string.about_text).setPositiveButton(android.R.string.ok,null).show()}
}
