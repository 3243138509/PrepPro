package com.PrepPro.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.PrepPro.mobile.net.TcpClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelSettingsActivity : AppCompatActivity() {

    private val themePrefs by lazy { getSharedPreferences("theme_prefs", Context.MODE_PRIVATE) }
    private lateinit var inputApiUrl: EditText
    private lateinit var inputApiKey: EditText
    private lateinit var inputModelName: EditText
    private lateinit var profilesSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var detectButton: MaterialButton
    private lateinit var addButton: MaterialButton
    private lateinit var useSelectedButton: MaterialButton
    private lateinit var deleteSelectedButton: MaterialButton
    private lateinit var batchDeleteButton: MaterialButton

    private var host: String = ""
    private var port: Int = 5001
    private var profiles: List<TcpClient.ModelSetting> = emptyList()
    private var activeProfileIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener {
            finishAfterTransition()
        }

        host = intent.getStringExtra("host")?.trim().orEmpty()
        port = intent.getIntExtra("port", 5001)

        inputApiUrl = findViewById(R.id.inputModelApiUrl)
        inputApiKey = findViewById(R.id.inputModelApiKey)
        inputModelName = findViewById(R.id.inputModelName)
        profilesSpinner = findViewById(R.id.spinnerModelProfiles)
        statusText = findViewById(R.id.textModelStatus)
        detectButton = findViewById(R.id.buttonDetectModels)
        addButton = findViewById(R.id.buttonAddModelSetting)
        useSelectedButton = findViewById(R.id.buttonUseSelectedProfile)
        deleteSelectedButton = findViewById(R.id.buttonDeleteSelectedProfile)
        batchDeleteButton = findViewById(R.id.buttonBatchDeleteProfiles)

        detectButton.setOnClickListener {
            detectModels()
        }

        addButton.setOnClickListener {
            addModelSetting()
        }

        useSelectedButton.setOnClickListener {
            applySelectedModel()
        }

        deleteSelectedButton.setOnClickListener {
            confirmDeleteSelectedModel()
        }

        batchDeleteButton.setOnClickListener {
            showBatchDeleteDialog()
        }

        setupButtonAnimations()

        loadSettings()
        
        applyThemeSettings()
    }

    private fun applyThemeSettings() {
        val followSystem = themePrefs.getBoolean("follow_system", true)
        val isDarkMode = themePrefs.getBoolean("is_dark_mode", false)
        val themeIndex = themePrefs.getInt("theme_index", 0)

        // Dark mode handling
        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Apply theme color
        val primaryColors = listOf("#0CA7A5", "#3949AB", "#2E7D32", "#D81B60", "#E65100", "#7B1FA2", "#424242", "#FF8F00")
        val backgroundColors = listOf("#D6F1EF", "#E8EAF6", "#E8F5E9", "#FCE4EC", "#FFF3E0", "#F3E5F5", "#F5F5F5", "#FFF8E1")
        val primaryColor = Color.parseColor(primaryColors.getOrNull(themeIndex) ?: "#0CA7A5")
        val backgroundColor = Color.parseColor(backgroundColors.getOrNull(themeIndex) ?: "#D6F1EF")

        // Apply background to root
        findViewById<View>(R.id.rootLayoutModel)?.let { root ->
            root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(backgroundColor, Color.WHITE)
            )
        }

        // Update title
        findViewById<TextView>(R.id.textTitle)?.setTextColor(Color.parseColor("#1A1A1A"))

        // Apply directly to buttons to be 100% sure
        val colorList = ColorStateList.valueOf(primaryColor)
        if (::addButton.isInitialized) {
            addButton.backgroundTintList = colorList
            addButton.setTextColor(Color.WHITE)
        }

        val secondaryButtons = listOfNotNull(
            if (::detectButton.isInitialized) detectButton else null,
            if (::useSelectedButton.isInitialized) useSelectedButton else null,
            if (::deleteSelectedButton.isInitialized) deleteSelectedButton else null,
            if (::batchDeleteButton.isInitialized) batchDeleteButton else null
        )

        secondaryButtons.forEach { btn ->
            btn.strokeColor = colorList
            btn.setTextColor(primaryColor)
            btn.rippleColor = colorList.withAlpha(30)
        }

        // Update all card strokes and text input layouts
        updateAllThemedViews(findViewById(R.id.rootLayoutModel), primaryColor)
    }

    private fun updateAllThemedViews(view: View, color: Int) {
        val colorList = ColorStateList.valueOf(color)
        if (view is MaterialCardView) {
            // Restore gray border for large boxes
            view.strokeWidth = dpToPx(1f)
            view.strokeColor = ContextCompat.getColor(this, R.color.surface_stroke)
        } else if (view is TextInputLayout) {
            view.setBoxStrokeColor(color)
            view.defaultHintTextColor = colorList
            view.hintTextColor = colorList
        } else if (view is MaterialButton) {
            if (view.id == R.id.buttonAddModelSetting) {
                // Primary button - Save and Enable
                view.backgroundTintList = colorList
                view.setTextColor(Color.WHITE)
            } else {
                // Secondary buttons
                view.strokeColor = colorList
                view.setTextColor(color)
                view.rippleColor = colorList.withAlpha(30)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateAllThemedViews(view.getChildAt(i), color)
            }
        }
    }

    private fun dpToPx(dp: Float): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun setupButtonAnimations() {
        listOf(detectButton, addButton, useSelectedButton, deleteSelectedButton, batchDeleteButton).forEach { button ->
            button.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> animateButtonPressed(view, true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> animateButtonPressed(view, false)
                }
                false
            }
        }
    }

    private fun animateButtonPressed(view: View, pressed: Boolean) {
        view.animate()
            .scaleX(if (pressed) 0.98f else 1f)
            .scaleY(if (pressed) 0.98f else 1f)
            .alpha(if (pressed) 0.9f else 1f)
            .setDuration(if (pressed) 90L else 150L)
            .start()
    }

    private fun createStyledDialogBuilder(): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(this)
    }

    private fun animateDialogShow(dialog: AlertDialog) {
        val themeIndex = themePrefs.getInt("theme_index", 0)
        val primaryColors = listOf("#0CA7A5", "#3949AB", "#2E7D32", "#D81B60", "#E65100", "#7B1FA2", "#424242", "#FF8F00")
        val primaryColor = Color.parseColor(primaryColors.getOrNull(themeIndex) ?: "#0CA7A5")

        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_surface)
        dialog.window?.decorView?.let { decor ->
            decor.alpha = 0f
            decor.scaleX = 0.95f
            decor.scaleY = 0.95f
            decor.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .start()
        }
        val ink = ContextCompat.getColor(this, R.color.ink_700)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(primaryColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ink)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ink)
    }

    private fun clientOrNull(): TcpClient? {
        if (host.isBlank()) {
            Toast.makeText(this, "主页面未设置服务器地址", Toast.LENGTH_SHORT).show()
            return null
        }
        return TcpClient(host, port)
    }

    private fun loadSettings() {
        val local = ModelSettingsStore.load(this)
        bindProfiles(local.profiles, local.activeIndex)
        statusText.text = "状态: 已加载手机本地 ${local.profiles.size} 个模型配置"
        syncLocalSettingsToServer(showToast = false)
    }

    private fun bindProfiles(items: List<TcpClient.ModelSetting>, activeIndex: Int) {
        profiles = items
        if (items.isEmpty()) {
            activeProfileIndex = 0
            profilesSpinner.adapter = createPrettySpinnerAdapter(listOf("(无配置)"))
            return
        }

        val labels = items.mapIndexed { index, item ->
            val activeTag = if (index == activeIndex) "[当前] " else ""
            "$activeTag${item.modelName} | ${item.apiUrl}"
        }
        val adapter = createPrettySpinnerAdapter(labels)
        profilesSpinner.adapter = adapter

        val safeIndex = activeIndex.coerceIn(0, items.lastIndex)
        activeProfileIndex = safeIndex
        profilesSpinner.setSelection(safeIndex)

        val selected = items[safeIndex]
        inputApiUrl.setText(selected.apiUrl)
        inputApiKey.setText(selected.apiKey)
        inputModelName.setText(selected.modelName)
    }

    private fun createPrettySpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, R.layout.item_spinner_selected, items).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
    }

    private fun detectModels() {
        val apiUrl = inputApiUrl.text.toString().trim()
        val apiKey = inputApiKey.text.toString().trim()
        if (apiUrl.isBlank() || apiKey.isBlank()) {
            Toast.makeText(this, "请先填写 MODEL_API_URL 和 MODEL_API_KEY", Toast.LENGTH_SHORT).show()
            return
        }

        val client = clientOrNull() ?: return
        statusText.text = "状态: 正在检测模型..."
        detectButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    client.detectModels(apiUrl, apiKey)
                }
                showModelChoiceDialog(models)
                statusText.text = "状态: 检测到 ${models.size} 个模型"
            } catch (ex: Exception) {
                statusText.text = "状态: 模型检测失败 - ${ex.message}"
            } finally {
                detectButton.isEnabled = true
            }
        }
    }

    private fun showModelChoiceDialog(models: List<String>) {
        if (models.isEmpty()) {
            Toast.makeText(this, "未检测到可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        val apiUrl = inputApiUrl.text.toString().trim()
        val apiKey = inputApiKey.text.toString().trim()
        val options = models.map { modelName ->
            if (findProfileIndex(apiUrl, apiKey, modelName) >= 0) {
                "$modelName (已添加)"
            } else {
                modelName
            }
        }.toTypedArray()
        var picked = 0
        val dialog = createStyledDialogBuilder()
            .setTitle("请选择 MODEL_NAME")
            .setSingleChoiceItems(options, 0) { _, which ->
                picked = which
            }
            .setPositiveButton("确定") { _, _ ->
                inputModelName.setText(models[picked])
            }
            .setNegativeButton("取消", null)
            .show()
        animateDialogShow(dialog)
    }

    private fun addModelSetting() {
        val apiUrl = inputApiUrl.text.toString().trim()
        val apiKey = inputApiKey.text.toString().trim()
        val modelName = inputModelName.text.toString().trim()
        if (apiUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) {
            Toast.makeText(this, "请填写 URL / KEY / NAME", Toast.LENGTH_SHORT).show()
            return
        }

        val existingIndex = findProfileIndex(apiUrl, apiKey, modelName)
        if (existingIndex >= 0) {
            bindProfiles(profiles, existingIndex)
            persistLocalSettings()
            syncLocalSettingsToServer(showToast = false)
            statusText.text = "状态: 模型已存在，已切换为当前（手机本地）"
            Toast.makeText(this, "该模型已添加", Toast.LENGTH_SHORT).show()
            return
        }

        val updated = profiles.toMutableList().apply {
            add(
                TcpClient.ModelSetting(
                    apiUrl = apiUrl,
                    apiKey = apiKey,
                    modelName = modelName,
                )
            )
        }
        bindProfiles(updated, updated.lastIndex)
        persistLocalSettings()
        syncLocalSettingsToServer(showToast = false)
        statusText.text = "状态: 已保存到手机并设为当前模型"
        Toast.makeText(this, "已添加并启用", Toast.LENGTH_SHORT).show()
    }

    private fun applySelectedModel() {
        val selectedIndex = profilesSpinner.selectedItemPosition
        if (profiles.isEmpty() || selectedIndex !in profiles.indices) {
            Toast.makeText(this, "没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        bindProfiles(profiles, selectedIndex)
        persistLocalSettings()
        syncLocalSettingsToServer(showToast = false)
        statusText.text = "状态: 已切换当前模型（手机本地）"
        Toast.makeText(this, "当前模型已切换", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteSelectedModel() {
        val selectedIndex = profilesSpinner.selectedItemPosition
        if (profiles.isEmpty() || selectedIndex !in profiles.indices) {
            Toast.makeText(this, "没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        val target = profiles[selectedIndex]
        val dialog = createStyledDialogBuilder()
            .setTitle("删除模型配置")
            .setMessage("确认删除 ${target.modelName} ?")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedModel(selectedIndex)
            }
            .setNegativeButton("取消", null)
            .show()
        animateDialogShow(dialog)
    }

    private fun deleteSelectedModel(selectedIndex: Int) {
        if (profiles.size <= 1) {
            Toast.makeText(this, "至少保留一个模型配置", Toast.LENGTH_SHORT).show()
            return
        }

        val updated = profiles.toMutableList().apply {
            removeAt(selectedIndex)
        }
        val nextActive = when {
            activeProfileIndex == selectedIndex -> maxOf(0, selectedIndex - 1)
            activeProfileIndex > selectedIndex -> activeProfileIndex - 1
            else -> activeProfileIndex
        }.coerceIn(0, updated.lastIndex)

        bindProfiles(updated, nextActive)
        persistLocalSettings()
        syncLocalSettingsToServer(showToast = false)
        statusText.text = "状态: 模型已删除（手机本地）"
        Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show()
    }

    private fun showBatchDeleteDialog() {
        if (profiles.size <= 1) {
            Toast.makeText(this, "至少保留一个模型，无法批量删除", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = profiles.mapIndexed { index, item ->
            val activeTag = if (profilesSpinner.selectedItemPosition == index) "[当前] " else ""
            "$activeTag${item.modelName} | ${item.apiUrl}"
        }.toTypedArray()
        val checked = BooleanArray(labels.size)

        val dialog = createStyledDialogBuilder()
            .setTitle("批量删除模型")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("删除") { _, _ ->
                val picked = mutableListOf<Int>()
                for (i in checked.indices) {
                    if (checked[i]) {
                        picked.add(i)
                    }
                }
                executeBatchDelete(picked)
            }
            .setNegativeButton("取消", null)
            .show()
            animateDialogShow(dialog)
    }

    private fun executeBatchDelete(indices: List<Int>) {
        if (indices.isEmpty()) {
            Toast.makeText(this, "未选择任何模型", Toast.LENGTH_SHORT).show()
            return
        }
        if (profiles.size - indices.size <= 0) {
            Toast.makeText(this, "至少保留一个模型配置", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedSet = indices.toSet()
        val updated = profiles.filterIndexed { index, _ -> index !in selectedSet }
        val removedBeforeActive = indices.count { it < activeProfileIndex }
        val activeDeleted = activeProfileIndex in selectedSet
        val shiftedActive = if (activeDeleted) {
            activeProfileIndex - removedBeforeActive - 1
        } else {
            activeProfileIndex - removedBeforeActive
        }
        val nextActive = if (updated.isEmpty()) 0 else shiftedActive.coerceIn(0, updated.lastIndex)

        bindProfiles(updated, nextActive)
        persistLocalSettings()
        syncLocalSettingsToServer(showToast = false)
        statusText.text = "状态: 已批量删除 ${indices.size} 个模型（手机本地）"
        Toast.makeText(this, "批量删除完成", Toast.LENGTH_SHORT).show()
    }

    private fun persistLocalSettings() {
        ModelSettingsStore.save(this, profiles, activeProfileIndex)
    }

    private fun syncLocalSettingsToServer(showToast: Boolean) {
        val client = clientOrNull() ?: return
        if (profiles.isEmpty()) {
            return
        }

        lifecycleScope.launch {
            try {
                val synced = withContext(Dispatchers.IO) {
                    client.syncModelSettings(profiles, activeProfileIndex)
                }
                bindProfiles(synced.profiles, synced.activeIndex)
                if (showToast) {
                    Toast.makeText(this@ModelSettingsActivity, "已同步到电脑端", Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                statusText.text = "状态: 已保存手机本地，电脑端同步失败 - ${ex.message}"
                if (showToast) {
                    Toast.makeText(this@ModelSettingsActivity, "同步失败：${ex.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun findProfileIndex(apiUrl: String, apiKey: String, modelName: String): Int {
        val targetUrl = apiUrl.trim()
        val targetKey = apiKey.trim()
        val targetModel = modelName.trim()
        return profiles.indexOfFirst {
            it.apiUrl.trim() == targetUrl &&
                it.apiKey.trim() == targetKey &&
                it.modelName.trim() == targetModel
        }
    }
}
