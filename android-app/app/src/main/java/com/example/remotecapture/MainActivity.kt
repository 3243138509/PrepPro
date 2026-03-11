package com.PropPro.mobile

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.PropPro.mobile.net.TcpClient
import com.PropPro.mobile.widget.CropEditorView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private var latestBitmap: Bitmap? = null
    private lateinit var cropEditorView: CropEditorView
    private lateinit var parseStateText: TextView
    private lateinit var pageIndicatorText: TextView
    private lateinit var mainPager: ViewPager
    private lateinit var mainContentContainer: View
    private lateinit var floatingPreviewCard: MaterialCardView
    private lateinit var fullscreenTopBar: View
    private lateinit var fullscreenBackButton: TextView
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private lateinit var displaySpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var captureButton: Button
    private lateinit var uploadAnalyzeButton: Button
    private lateinit var realtimeButton: MaterialButton
    private lateinit var analysisText: TextView
    private lateinit var copyAnalysisToPcButton: MaterialButton
    private lateinit var markwon: Markwon

    private val prefs by lazy { getSharedPreferences("crop_prefs", MODE_PRIVATE) }
    private val connPrefs by lazy { getSharedPreferences("conn_prefs", MODE_PRIVATE) }
    private val floatingPrefs by lazy { getSharedPreferences("floating_preview_prefs", MODE_PRIVATE) }

    private var rememberedEditorState: CropEditorView.EditorState? = null
    private var isConnected = false
    private var selectedDisplayId = 1
    private var streamJob: Job? = null
    private var reconnectJob: Job? = null
    private var clipboardJob: Job? = null
    private var knownDisplays: List<TcpClient.DisplayInfo> = emptyList()
    private var latestAnalysisResultText: String = ""
    private var latestClipboardPush: TcpClient.ClipboardPush? = null
    private var clipboardDialog: AlertDialog? = null
    private var pageCaptureRoot: View? = null
    private var pageAnalysisRoot: View? = null
    private var isDraggingPreview = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartCardX = 0f
    private var dragStartCardY = 0f
    private var isPreviewFullscreen = false
    private var fullscreenDialog: Dialog? = null
    private var fullscreenEditorView: CropEditorView? = null
    private var previewNormalX = 0f
    private var previewNormalY = 0f
    private var previewNormalWidth = 0
    private var previewNormalHeight = 0
    private val previewAspectRatio = 2560f / 1600f
    private val previewDefaultWidthDp = 200f
    private val previewMaxWidthDp = 220f
    private var realtimeDefaultTint: ColorStateList? = null
    private var realtimeDefaultStroke: ColorStateList? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                saveCurrentCropToGallery()
            } else {
                Toast.makeText(this, "未授予存储权限，无法保存到相册", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        setContentView(R.layout.activity_main)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isPreviewFullscreen) {
                    exitPreviewFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setupPager()

        val captureRoot = requireNotNull(pageCaptureRoot)
        val analysisRoot = requireNotNull(pageAnalysisRoot)

        mainContentContainer = findViewById(R.id.mainContentContainer)
        floatingPreviewCard = findViewById(R.id.floatingPreviewCard)
        fullscreenTopBar = findViewById(R.id.fullscreenTopBar)
        fullscreenBackButton = findViewById(R.id.buttonFullscreenBack)

        hostInput = captureRoot.findViewById(R.id.inputHost)
        portInput = captureRoot.findViewById(R.id.inputPort)
        statusText = captureRoot.findViewById(R.id.textStatus)
        parseStateText = captureRoot.findViewById(R.id.textParseState)
        cropEditorView = findViewById(R.id.imageResult)
        displaySpinner = captureRoot.findViewById(R.id.spinnerDisplay)
        connectButton = captureRoot.findViewById(R.id.buttonConnect)
        captureButton = captureRoot.findViewById(R.id.buttonCapture)
        val cropSaveButton = captureRoot.findViewById<Button>(R.id.buttonCropSave)
        val modelSettingsButton = captureRoot.findViewById<Button>(R.id.buttonModelSettings)
        uploadAnalyzeButton = findViewById(R.id.buttonUploadAnalyze)
        realtimeButton = captureRoot.findViewById(R.id.buttonRealtime)
        analysisText = analysisRoot.findViewById(R.id.textAnalysis)
        copyAnalysisToPcButton = analysisRoot.findViewById(R.id.buttonCopyAnalysisToPc)
        markwon = Markwon.create(this)
        realtimeDefaultTint = realtimeButton.backgroundTintList
        realtimeDefaultStroke = realtimeButton.strokeColor
        setupFloatingPreviewDrag()
        setupFullscreenControls()
        restoreFloatingPreviewPosition()
        cropEditorView.setCropEditingEnabled(false)

        hostInput.setText(connPrefs.getString("host", "10.189.57.17"))
        portInput.setText(connPrefs.getString("port", "5001"))
        attachHyphenStripper(hostInput)
        attachHyphenStripper(portInput)
        hostInput.clearFocus()
        portInput.clearFocus()
        findViewById<View>(android.R.id.content).requestFocus()
        rememberedEditorState = readEditorStateFromPrefs()
        setupDisplaySpinner(displaySpinner, listOf(TcpClient.DisplayInfo(1, 0, 0, 0, 0)))

        setConnected(false)
        applyRealtimeButtonStyle(isRunning = false)

        connectButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001
            persistConnectionInputs(host, port.toString())

            connectToServer(host, port, autoReconnect = false)
        }

        modelSettingsButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001
            persistConnectionInputs(host, port.toString())

            startActivity(Intent(this, ModelSettingsActivity::class.java).apply {
                putExtra("host", host)
                putExtra("port", port)
            })
        }

        captureButton.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先点击连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001
            persistConnectionInputs(host, port.toString())

            // Save current transform and crop so next screenshot reuses the latest adjustment.
            persistEditorState()

            statusText.text = "正在截图..."
            captureButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        TcpClient(host, port).capture(selectedDisplayId)
                    }
                    latestBitmap = bitmap
                    cropEditorView.setBitmap(bitmap)
                    rememberedEditorState?.let { cropEditorView.applyEditorState(it) }
                    fullscreenEditorView?.let { editor ->
                        editor.setBitmap(bitmap)
                        rememberedEditorState?.let { editor.applyEditorState(it) }
                    }
                    floatingPreviewCard.visibility = View.VISIBLE
                    ensureFloatingPreviewVisible()
                    statusText.text = "截图成功，已沿用上次缩放和裁剪框"
                } catch (ex: Exception) {
                    if (isConnectionFatal(ex)) {
                        setConnected(false)
                        statusText.text = "连接失败: ${ex.message}，请重新连接"
                    } else {
                        statusText.text = "截图失败: ${ex.message}"
                    }
                } finally {
                    captureButton.isEnabled = true
                }
            }
        }

        realtimeButton.setOnClickListener {
            if (streamJob?.isActive == true) {
                stopRealtime(realtimeButton, statusText)
                return@setOnClickListener
            }
            if (!isConnected) {
                Toast.makeText(this, "请先点击连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001
            persistConnectionInputs(host, port.toString())
            startRealtime(host, port, realtimeButton, captureButton, statusText)
        }

        copyAnalysisToPcButton.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val textToCopy = latestAnalysisResultText.trim()
            if (textToCopy.isEmpty()) {
                Toast.makeText(this, "暂无可复制的解析结果", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001
            copyAnalysisToPcButton.isEnabled = false

            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        TcpClient(host, port).setClipboardText(textToCopy)
                    }
                    Toast.makeText(this@MainActivity, "已发送到电脑剪贴板", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(this@MainActivity, "发送失败: ${ex.message}", Toast.LENGTH_LONG).show()
                } finally {
                    copyAnalysisToPcButton.isEnabled = true
                }
            }
        }

        cropSaveButton.setOnClickListener {
            if (latestBitmap == null) {
                Toast.makeText(this, "请先截图", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                return@setOnClickListener
            }
            saveCurrentCropToGallery()
        }

        uploadAnalyzeButton.setOnClickListener {
            if (!isConnected) {
                Toast.makeText(this, "请先连接", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val activeEditor = if (isPreviewFullscreen) {
                fullscreenEditorView ?: cropEditorView
            } else {
                cropEditorView
            }
            val cropped = activeEditor.getCroppedBitmap()
            if (cropped == null) {
                Toast.makeText(this, "请先截图并调整裁剪框", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val analysisBitmap = prepareBitmapForAnalysis(cropped)

            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim().toIntOrNull() ?: 5001

            uploadAnalyzeButton.isEnabled = false
            statusText.text = "上传中，正在解析..."
            setParseStateProcessing()
            renderAnalysisMarkdown("解析结果: (处理中)")

            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        TcpClient(host, port).analyzeImage(
                            analysisBitmap,
                            "请用简洁中文描述这张图里的主要内容，并列出关键元素。",
                        )
                    }
                    val ocrBlock = if (result.ocrText.isBlank()) {
                        "(空)"
                    } else {
                        result.ocrText
                    }
                    val markdown = buildString {
                        append("## 解析结果\n\n")
                        append(result.text)
                        if (result.modelNotice.isNotBlank()) {
                            append("\n\n> ")
                            append(result.modelNotice)
                        }
                        append("\n\n---\n\n")
                        append("## OCR 预扫描\n\n")
                        append("```text\n")
                        append(ocrBlock)
                        append("\n```")
                    }
                    renderAnalysisMarkdown(markdown)
                    latestAnalysisResultText = result.text
                    statusText.text = if (result.modelNotice.isNotBlank()) {
                        "解析完成，${result.modelNotice}"
                    } else {
                        "解析完成"
                    }
                    if (result.modelNotice.isNotBlank()) {
                        Toast.makeText(this@MainActivity, result.modelNotice, Toast.LENGTH_LONG).show()
                    }
                    setParseStateSuccess()
                    mainPager.currentItem = 1
                } catch (ex: Exception) {
                    renderAnalysisMarkdown("解析结果: (失败)")
                    latestAnalysisResultText = ""
                    statusText.text = "上传/解析失败: ${ex.message}"
                    setParseStateFailed()
                } finally {
                    uploadAnalyzeButton.isEnabled = isConnected
                }
            }
        }

        autoReconnectIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        autoReconnectIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        persistConnectionInputs(
            hostInput.text.toString().trim(),
            portInput.text.toString().trim(),
        )
        if (isPreviewFullscreen) {
            exitPreviewFullscreen()
        }
        stopRealtimeSilently()
        stopClipboardSubscription()
        persistEditorState()
        persistFloatingPreviewPosition()
    }

    private fun setupPager() {
        mainPager = findViewById(R.id.viewPagerMain)
        pageIndicatorText = findViewById(R.id.textPageIndicator)

        val inflater = LayoutInflater.from(this)
        val page1 = inflater.inflate(R.layout.page_capture, mainPager, false)
        val page2 = inflater.inflate(R.layout.page_analysis, mainPager, false)
        pageCaptureRoot = page1
        pageAnalysisRoot = page2

        mainPager.adapter = SimplePagerAdapter(listOf(page1, page2))
        mainPager.offscreenPageLimit = 2
        mainPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                pageIndicatorText.text = if (position == 0) "第 1 页 / 2" else "第 2 页 / 2"
            }
        })
    }

    private fun setupDisplaySpinner(spinner: Spinner, displays: List<TcpClient.DisplayInfo>) {
        val labels = displays.map { d ->
            "屏幕 ${d.id} (${d.width}x${d.height}, ${d.left},${d.top})"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(0)
        selectedDisplayId = displays.firstOrNull()?.id ?: 1
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedDisplayId = displays.getOrNull(position)?.id ?: 1
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                selectedDisplayId = displays.firstOrNull()?.id ?: 1
            }
        }
    }

    private fun startRealtime(
        host: String,
        port: Int,
        realtimeButton: MaterialButton,
        captureButton: Button,
        statusText: TextView
    ) {
        realtimeButton.text = "停止实时预览"
        applyRealtimeButtonStyle(isRunning = true)
        captureButton.isEnabled = false
        statusText.text = "实时预览中..."

        streamJob = lifecycleScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                try {
                    // Crop range is authored by fullscreen editor only. Small preview does not write back state.
                    val bitmap = withContext(Dispatchers.IO) {
                        TcpClient(host, port).capture(selectedDisplayId)
                    }
                    consecutiveFailures = 0
                    latestBitmap = bitmap
                    cropEditorView.updateBitmapKeepingState(bitmap)
                    fullscreenEditorView?.updateBitmapKeepingState(bitmap)
                    floatingPreviewCard.visibility = View.VISIBLE
                    ensureFloatingPreviewVisible()
                } catch (ex: Exception) {
                    consecutiveFailures += 1
                    if (isConnectionFatal(ex) && consecutiveFailures >= 2) {
                        setConnected(false)
                        statusText.text = "实时预览失败: ${ex.message}，请重新连接"
                        break
                    }
                    statusText.text = "实时预览短暂失败(${consecutiveFailures})，正在重试..."
                    delay(700)
                }
                delay(350)
            }

            stopRealtime(realtimeButton, statusText)
        }
    }

    private fun stopRealtime(realtimeButton: MaterialButton, statusText: TextView) {
        stopRealtimeSilently()
        persistEditorState()
        realtimeButton.text = "开始实时预览"
        applyRealtimeButtonStyle(isRunning = false)
        captureButton.isEnabled = isConnected
        if (isConnected) {
            statusText.text = "实时预览已停止"
        }
    }

    private fun stopRealtimeSilently() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun applyRealtimeButtonStyle(isRunning: Boolean) {
        if (isRunning) {
            val red = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger_red))
            realtimeButton.backgroundTintList = red
            realtimeButton.strokeColor = red
        } else {
            realtimeButton.backgroundTintList = realtimeDefaultTint
            realtimeButton.strokeColor = realtimeDefaultStroke
        }
    }

    private fun saveCurrentCropToGallery() {
        val cropped = cropEditorView.getCroppedBitmap()
        if (cropped == null) {
            Toast.makeText(this, "裁剪区域无效，请重新调整", Toast.LENGTH_LONG).show()
            return
        }
        val outputBitmap = prepareBitmapForAnalysis(cropped)

        try {
            val filename = "remote_capture_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PropPro")
                }
            }

            val resolver = contentResolver
            val targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建相册文件")

            resolver.openOutputStream(targetUri).use { output ->
                requireNotNull(output) { "写入相册失败" }
                val ok = outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                if (!ok) error("图片写入失败")
            }

            Toast.makeText(this, "已保存到相册: Pictures/PropPro", Toast.LENGTH_LONG).show()
            persistEditorState()
        } catch (ex: Exception) {
            Toast.makeText(this, "保存失败: ${ex.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistEditorState() {
        val state = fullscreenEditorView?.exportEditorState()
            ?: rememberedEditorState
            ?: cropEditorView.exportEditorState()
            ?: return
        rememberedEditorState = state
        val editor = prefs.edit()
        editor
            .putFloat("scaleX", state.scaleX)
            .putFloat("scaleY", state.scaleY)
            .putFloat("transX", state.transX)
            .putFloat("transY", state.transY)
            .putInt("refViewWidth", state.refViewWidth)
            .putInt("refViewHeight", state.refViewHeight)
            .putFloat("cropLeftRatio", state.cropLeftRatio)
            .putFloat("cropTopRatio", state.cropTopRatio)
            .putFloat("cropRightRatio", state.cropRightRatio)
            .putFloat("cropBottomRatio", state.cropBottomRatio)

        state.cropImageLeftPx?.let { editor.putFloat("cropImageLeftPx", it) } ?: editor.remove("cropImageLeftPx")
        state.cropImageTopPx?.let { editor.putFloat("cropImageTopPx", it) } ?: editor.remove("cropImageTopPx")
        state.cropImageRightPx?.let { editor.putFloat("cropImageRightPx", it) } ?: editor.remove("cropImageRightPx")
        state.cropImageBottomPx?.let { editor.putFloat("cropImageBottomPx", it) } ?: editor.remove("cropImageBottomPx")
        editor.apply()
    }

    private fun readEditorStateFromPrefs(): CropEditorView.EditorState? {
        if (!prefs.contains("scaleX")) return null
        return CropEditorView.EditorState(
            scaleX = prefs.getFloat("scaleX", 1f),
            scaleY = prefs.getFloat("scaleY", 1f),
            transX = prefs.getFloat("transX", 0f),
            transY = prefs.getFloat("transY", 0f),
            refViewWidth = prefs.getInt("refViewWidth", 0),
            refViewHeight = prefs.getInt("refViewHeight", 0),
            cropLeftRatio = prefs.getFloat("cropLeftRatio", 0.15f),
            cropTopRatio = prefs.getFloat("cropTopRatio", 0.15f),
            cropRightRatio = prefs.getFloat("cropRightRatio", 0.85f),
            cropBottomRatio = prefs.getFloat("cropBottomRatio", 0.85f),
            cropImageLeftPx = if (prefs.contains("cropImageLeftPx")) prefs.getFloat("cropImageLeftPx", 0f) else null,
            cropImageTopPx = if (prefs.contains("cropImageTopPx")) prefs.getFloat("cropImageTopPx", 0f) else null,
            cropImageRightPx = if (prefs.contains("cropImageRightPx")) prefs.getFloat("cropImageRightPx", 0f) else null,
            cropImageBottomPx = if (prefs.contains("cropImageBottomPx")) prefs.getFloat("cropImageBottomPx", 0f) else null,
        )
    }

    private fun persistConnectionInputs(host: String, port: String) {
        connPrefs.edit()
            .putString("host", host)
            .putString("port", if (port.isBlank()) "5001" else port)
            .apply()
    }

    private fun autoReconnectIfNeeded() {
        if (isConnected || reconnectJob?.isActive == true) {
            return
        }
        val host = connPrefs.getString("host", "")?.trim().orEmpty()
        if (host.isBlank()) {
            return
        }
        val port = connPrefs.getString("port", "5001")?.trim()?.toIntOrNull() ?: 5001
        connectToServer(host, port, autoReconnect = true)
    }

    private fun connectToServer(host: String, port: Int, autoReconnect: Boolean) {
        statusText.text = if (autoReconnect) "正在恢复连接..." else "正在连接..."
        connectButton.isEnabled = false
        setConnected(false)

        reconnectJob = lifecycleScope.launch {
            try {
                val displays = withContext(Dispatchers.IO) {
                    val client = TcpClient(host, port)
                    client.authenticateOnly()
                    client.listDisplays()
                }
                knownDisplays = displays.ifEmpty { listOf(TcpClient.DisplayInfo(1, 0, 0, 0, 0)) }
                setupDisplaySpinner(displaySpinner, knownDisplays)
                setConnected(true)
                startClipboardSubscription(host, port)

                // Load a fresh desktop frame immediately after successful connection.
                try {
                    val bitmap = withContext(Dispatchers.IO) {
                        TcpClient(host, port).capture(selectedDisplayId)
                    }
                    latestBitmap = bitmap
                    cropEditorView.setBitmap(bitmap)
                    rememberedEditorState?.let { cropEditorView.applyEditorState(it) }
                    floatingPreviewCard.visibility = View.VISIBLE
                    ensureFloatingPreviewVisible()
                } catch (_: Exception) {
                    floatingPreviewCard.visibility = View.GONE
                }

                statusText.text = if (autoReconnect) {
                    "连接已恢复，可继续操作"
                } else {
                    "连接成功，可截图或开始实时预览"
                }
            } catch (ex: Exception) {
                setConnected(false)
                statusText.text = if (autoReconnect) {
                    "自动恢复连接失败: ${ex.message}"
                } else {
                    "失败: ${ex.message}"
                }
            } finally {
                connectButton.isEnabled = true
                reconnectJob = null
            }
        }
    }

    private fun setConnected(connected: Boolean) {
        isConnected = connected
        captureButton.isEnabled = connected
        realtimeButton.isEnabled = connected
        uploadAnalyzeButton.isEnabled = connected
        if (!connected) {
            stopClipboardSubscription()
            exitPreviewFullscreen()
            floatingPreviewCard.visibility = View.GONE
            applyCropEditorMode()
            applyRealtimeButtonStyle(isRunning = false)
        }
    }

    private fun setupFloatingPreviewDrag() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val dragListener = View.OnTouchListener { _, event ->
            if (isPreviewFullscreen) {
                // In fullscreen mode, let CropEditorView handle crop edit gestures.
                return@OnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingPreview = false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartCardX = floatingPreviewCard.x
                    dragStartCardY = floatingPreviewCard.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!isDraggingPreview && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        isDraggingPreview = true
                    }
                    if (isDraggingPreview) {
                        floatingPreviewCard.x = dragStartCardX + dx
                        floatingPreviewCard.y = dragStartCardY + dy
                        clampFloatingPreviewPosition()
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingPreview) {
                        clampFloatingPreviewPosition()
                        persistFloatingPreviewPosition()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        enterPreviewFullscreen()
                    }
                    true
                }

                else -> false
            }
        }

        // Attach to both card container and image layer to avoid gesture loss after screenshot updates.
        floatingPreviewCard.setOnTouchListener(dragListener)
        cropEditorView.setOnTouchListener(dragListener)
    }

    private fun setupFullscreenControls() {
        fullscreenBackButton.setOnClickListener {
            if (isPreviewFullscreen) {
                exitPreviewFullscreen()
            }
        }
    }

    private fun clampFloatingPreviewPosition() {
        val parentW = mainContentContainer.width.toFloat()
        val parentH = mainContentContainer.height.toFloat()
        val cardW = floatingPreviewCard.width.toFloat()
        val cardH = floatingPreviewCard.height.toFloat()
        if (parentW <= 0f || parentH <= 0f || cardW <= 0f || cardH <= 0f) return

        val maxX = (parentW - cardW).coerceAtLeast(0f)
        val maxY = (parentH - cardH).coerceAtLeast(0f)
        floatingPreviewCard.x = floatingPreviewCard.x.coerceIn(0f, maxX)
        floatingPreviewCard.y = floatingPreviewCard.y.coerceIn(0f, maxY)
    }

    private fun ensureFloatingPreviewVisible() {
        // Run after layout to avoid clamping with width/height == 0.
        floatingPreviewCard.post {
            clampFloatingPreviewPosition()
            persistFloatingPreviewPosition()
        }
    }

    private fun enterPreviewFullscreen() {
        if (isPreviewFullscreen || floatingPreviewCard.visibility != View.VISIBLE || latestBitmap == null) {
            return
        }

        previewNormalX = floatingPreviewCard.x
        previewNormalY = floatingPreviewCard.y
        previewNormalWidth = floatingPreviewCard.width
        previewNormalHeight = floatingPreviewCard.height

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val editor = CropEditorView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setBitmap(requireNotNull(latestBitmap))
            rememberedEditorState?.let { applyEditorState(it) }
            setCropEditingEnabled(true)
        }

        val exitButton = MaterialButton(this).apply {
            text = "退出"
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dpToPx(12f)
                topMargin = dpToPx(12f)
            }
            setOnClickListener { exitPreviewFullscreen() }
        }

        root.addView(editor)
        root.addView(exitButton)

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            if (isPreviewFullscreen) {
                exitPreviewFullscreen()
            }
        }

        fullscreenDialog = dialog
        fullscreenEditorView = editor
        isPreviewFullscreen = true
        applyCropEditorMode()
        dialog.show()
    }

    private fun exitPreviewFullscreen() {
        if (!isPreviewFullscreen) {
            return
        }

        val exportedState = fullscreenEditorView?.exportEditorState()
        if (exportedState != null) {
            rememberedEditorState = exportedState
        }

        fullscreenDialog?.setOnDismissListener(null)
        fullscreenDialog?.dismiss()
        fullscreenDialog = null
        fullscreenEditorView = null

        val restoreW = clampPreviewWidth(
            if (previewNormalWidth > 0) previewNormalWidth else defaultPreviewWidthPx(),
        )
        val restoreH = if (previewNormalHeight > 0) previewNormalHeight else aspectHeightForWidth(restoreW)
        resizeFloatingPreview(restoreW, restoreH)
        floatingPreviewCard.x = previewNormalX
        floatingPreviewCard.y = previewNormalY
        isPreviewFullscreen = false
        fullscreenTopBar.visibility = View.GONE
        applyCropEditorMode()
        exportedState?.let { cropEditorView.applyEditorState(it) }
        clampFloatingPreviewPosition()
        persistFloatingPreviewPosition()
    }

    private fun applyCropEditorMode() {
        cropEditorView.setCropEditingEnabled(isPreviewFullscreen)
    }

    private fun resizeFloatingPreview(targetWidth: Int, targetHeight: Int) {
        val normalizedWidth = targetWidth.coerceAtLeast(dpToPx(128f))
        val ratioAdjustedHeight = aspectHeightForWidth(normalizedWidth)
        val normalizedHeight = if (targetHeight <= 0) ratioAdjustedHeight else targetHeight
        val lp = floatingPreviewCard.layoutParams
        lp.width = normalizedWidth
        lp.height = max(ratioAdjustedHeight, normalizedHeight.coerceAtLeast(dpToPx(80f)))
        floatingPreviewCard.layoutParams = lp
        floatingPreviewCard.requestLayout()
    }

    private fun aspectHeightForWidth(width: Int): Int {
        return (width / previewAspectRatio).toInt().coerceAtLeast(dpToPx(80f))
    }

    private fun defaultPreviewWidthPx(): Int {
        return dpToPx(previewDefaultWidthDp)
    }

    private fun maxPreviewWidthPx(): Int {
        return dpToPx(previewMaxWidthDp)
    }

    private fun clampPreviewWidth(width: Int): Int {
        return width.coerceIn(dpToPx(128f), maxPreviewWidthPx())
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics,
        ).toInt()
    }

    private fun restoreFloatingPreviewPosition() {
        mainContentContainer.post {
            isPreviewFullscreen = false
            previewNormalX = floatingPrefs.getFloat("normalX", 0f)
            previewNormalY = floatingPrefs.getFloat("normalY", 0f)
            previewNormalWidth = clampPreviewWidth(
                floatingPrefs.getInt("normalWidth", defaultPreviewWidthPx()),
            )
            previewNormalHeight = floatingPrefs.getInt("normalHeight", aspectHeightForWidth(previewNormalWidth))

            // Restore startup window from non-fullscreen metrics to avoid opening in fullscreen size.
            val startupWidth = previewNormalWidth.coerceAtLeast(dpToPx(160f))
            val startupHeight = previewNormalHeight.coerceAtLeast(aspectHeightForWidth(startupWidth))
            resizeFloatingPreview(startupWidth, startupHeight)

            fullscreenTopBar.visibility = View.GONE
            applyCropEditorMode()

            val parentW = mainContentContainer.width.toFloat()
            val parentH = mainContentContainer.height.toFloat()
            val cardW = floatingPreviewCard.width.toFloat()
            val cardH = floatingPreviewCard.height.toFloat()
            val maxX = (parentW - cardW).coerceAtLeast(0f)
            val maxY = (parentH - cardH).coerceAtLeast(0f)

            val hasRatioPos = floatingPrefs.contains("normalXRatio") || floatingPrefs.contains("normalYRatio")
            val hasAbsPos = floatingPrefs.contains("normalX") || floatingPrefs.contains("normalY")
            if (hasRatioPos) {
                val xRatio = floatingPrefs.getFloat("normalXRatio", 0f).coerceIn(0f, 1f)
                val yRatio = floatingPrefs.getFloat("normalYRatio", 0f).coerceIn(0f, 1f)
                floatingPreviewCard.x = maxX * xRatio
                floatingPreviewCard.y = maxY * yRatio
            } else if (hasAbsPos) {
                floatingPreviewCard.x = previewNormalX
                floatingPreviewCard.y = previewNormalY
            } else {
                floatingPreviewCard.x = ((mainContentContainer.width - floatingPreviewCard.width) / 2f).coerceAtLeast(0f)
                floatingPreviewCard.y = ((mainContentContainer.height - floatingPreviewCard.height) / 2f).coerceAtLeast(0f)
            }
            ensureFloatingPreviewVisible()
        }
    }

    private fun persistFloatingPreviewPosition() {
        if (!isPreviewFullscreen) {
            previewNormalX = floatingPreviewCard.x
            previewNormalY = floatingPreviewCard.y
            previewNormalWidth = clampPreviewWidth(floatingPreviewCard.width)
            previewNormalHeight = aspectHeightForWidth(previewNormalWidth)
        }

        val parentW = mainContentContainer.width.toFloat()
        val parentH = mainContentContainer.height.toFloat()
        val cardW = floatingPreviewCard.width.toFloat()
        val cardH = floatingPreviewCard.height.toFloat()
        val maxX = (parentW - cardW).coerceAtLeast(0f)
        val maxY = (parentH - cardH).coerceAtLeast(0f)
        val xRatio = if (maxX > 0f) (previewNormalX / maxX).coerceIn(0f, 1f) else 0f
        val yRatio = if (maxY > 0f) (previewNormalY / maxY).coerceIn(0f, 1f) else 0f

        floatingPrefs.edit()
            .putFloat("cardX", floatingPreviewCard.x)
            .putFloat("cardY", floatingPreviewCard.y)
            .putInt("cardWidth", floatingPreviewCard.width)
            .putInt("cardHeight", floatingPreviewCard.height)
            .putFloat("normalX", previewNormalX)
            .putFloat("normalY", previewNormalY)
            .putFloat("normalXRatio", xRatio)
            .putFloat("normalYRatio", yRatio)
            .putInt("normalWidth", previewNormalWidth)
            .putInt("normalHeight", previewNormalHeight)
            .apply()
    }

    private fun startClipboardSubscription(host: String, port: Int) {
        if (clipboardJob?.isActive == true) {
            return
        }
        clipboardJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    TcpClient(host, port).subscribeClipboard { push ->
                        runOnUiThread {
                            enqueueClipboardPush(push)
                        }
                    }
                }
            } catch (ex: Exception) {
                if (isConnected) {
                    statusText.text = "剪贴板监听已断开: ${ex.message}"
                }
            }
        }
    }

    private fun stopClipboardSubscription() {
        clipboardJob?.cancel()
        clipboardJob = null
        latestClipboardPush = null
        clipboardDialog?.dismiss()
        clipboardDialog = null
    }

    private fun enqueueClipboardPush(push: TcpClient.ClipboardPush) {
        if (!isConnected) {
            return
        }
        latestClipboardPush = push
        if (clipboardDialog?.isShowing == true) {
            updateClipboardDialogMessage(push)
        } else {
            showClipboardDialog(push)
        }
    }

    private fun showClipboardDialog(push: TcpClient.ClipboardPush) {
        val preview = if (push.text.length > 300) {
            push.text.substring(0, 300) + "..."
        } else {
            push.text
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("检测到电脑端复制文本")
            .setMessage("是否解析以下文本？\n\n$preview")
            .setCancelable(true)
            .setPositiveButton("解析") { _, _ ->
                val latestText = latestClipboardPush?.text ?: push.text
                analyzeClipboardText(latestText)
            }
            .setNegativeButton("取消") { _, _ ->
                Toast.makeText(this, "已取消本次解析", Toast.LENGTH_SHORT).show()
            }
            .setOnDismissListener {
                clipboardDialog = null
            }
            .show()

        clipboardDialog = dialog
    }

    private fun updateClipboardDialogMessage(push: TcpClient.ClipboardPush) {
        val dialog = clipboardDialog ?: return
        if (!dialog.isShowing) {
            return
        }
        val preview = if (push.text.length > 300) {
            push.text.substring(0, 300) + "..."
        } else {
            push.text
        }
        dialog.setMessage("是否解析以下文本？\n\n$preview")
    }

    private fun analyzeClipboardText(text: String) {
        if (!isConnected) {
            Toast.makeText(this, "当前未连接，无法解析", Toast.LENGTH_SHORT).show()
            return
        }

        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 5001

        uploadAnalyzeButton.isEnabled = false
        statusText.text = "正在解析剪贴板文本..."
        setParseStateProcessing()
        renderAnalysisMarkdown("解析结果: (处理中)")

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TcpClient(host, port).analyzeText(
                        text,
                        "请根据这段文本给出结论，并简要说明推理过程。",
                    )
                }
                val markdown = buildString {
                    append("## 剪贴板文本解析结果\n\n")
                    append(result.text)
                    if (result.modelNotice.isNotBlank()) {
                        append("\n\n> ")
                        append(result.modelNotice)
                    }
                    append("\n\n---\n\n")
                    append("## 原始文本\n\n")
                    append("```text\n")
                    append(text)
                    append("\n```")
                }
                renderAnalysisMarkdown(markdown)
                latestAnalysisResultText = result.text
                statusText.text = if (result.modelNotice.isNotBlank()) {
                    "剪贴板文本解析完成，${result.modelNotice}"
                } else {
                    "剪贴板文本解析完成"
                }
                if (result.modelNotice.isNotBlank()) {
                    Toast.makeText(this@MainActivity, result.modelNotice, Toast.LENGTH_LONG).show()
                }
                setParseStateSuccess()
                mainPager.currentItem = 1
            } catch (ex: Exception) {
                renderAnalysisMarkdown("解析结果: (失败)")
                latestAnalysisResultText = ""
                statusText.text = "剪贴板文本解析失败: ${ex.message}"
                setParseStateFailed()
            } finally {
                uploadAnalyzeButton.isEnabled = isConnected
            }
        }
    }

    private fun setParseStateProcessing() {
        parseStateText.text = "解析状态: 解析中"
    }

    private fun setParseStateSuccess() {
        parseStateText.text = "解析状态: 解析成功"
    }

    private fun setParseStateFailed() {
        parseStateText.text = "解析状态: 解析失败"
    }

    private fun isConnectionFatal(ex: Exception): Boolean {
        val message = ex.message?.lowercase().orEmpty()
        return message.contains("auth") ||
            message.contains("connection") ||
            message.contains("timeout") ||
            message.contains("socket")
    }

    private fun attachHyphenStripper(input: EditText) {
        input.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (isUpdating || s == null) return
                val original = s.toString()
                if (!original.contains("-")) return
                val cleaned = original.replace("-", "")
                isUpdating = true
                input.setText(cleaned)
                input.setSelection(cleaned.length)
                isUpdating = false
            }
        })
    }

    private fun renderAnalysisMarkdown(markdown: String) {
        markwon.setMarkdown(analysisText, markdown)
    }

    private fun prepareBitmapForAnalysis(source: Bitmap): Bitmap {
        val targetMinShortEdge = 1080
        val maxLongEdge = 2400
        val shortEdge = min(source.width, source.height)
        if (shortEdge >= targetMinShortEdge) {
            return source
        }

        val scale = targetMinShortEdge.toFloat() / shortEdge.toFloat()
        var scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        var scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)

        val longEdge = max(scaledWidth, scaledHeight)
        if (longEdge > maxLongEdge) {
            val clampScale = maxLongEdge.toFloat() / longEdge.toFloat()
            scaledWidth = (scaledWidth * clampScale).toInt().coerceAtLeast(1)
            scaledHeight = (scaledHeight * clampScale).toInt().coerceAtLeast(1)
        }

        return Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
    }

    private class SimplePagerAdapter(
        private val pages: List<View>,
    ) : PagerAdapter() {
        override fun getCount(): Int = pages.size

        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val page = pages[position]
            container.addView(page)
            return page
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }
    }
}
