package com.PrepPro.mobile

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.PrepPro.mobile.net.TcpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ModelSettingsActivity : AppCompatActivity() {

    private lateinit var inputApiUrl: EditText
    private lateinit var inputApiKey: EditText
    private lateinit var inputModelName: EditText
    private lateinit var profilesSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var detectButton: Button
    private lateinit var addButton: Button
    private lateinit var useSelectedButton: Button
    private lateinit var deleteSelectedButton: Button
    private lateinit var batchDeleteButton: Button

    private var host: String = ""
    private var port: Int = 5001
    private var profiles: List<TcpClient.ModelSetting> = emptyList()
    private var detectedModels: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_settings)

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

        loadSettings()
    }

    private fun clientOrNull(): TcpClient? {
        if (host.isBlank()) {
            Toast.makeText(this, "主页面未设置服务器地址", Toast.LENGTH_SHORT).show()
            return null
        }
        return TcpClient(host, port)
    }

    private fun loadSettings() {
        val client = clientOrNull() ?: return
        statusText.text = "状态: 正在加载模型配置..."
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.getModelSettings()
                }
                bindProfiles(result.profiles, result.activeIndex)
                statusText.text = "状态: 已加载 ${result.profiles.size} 个模型配置"
            } catch (ex: Exception) {
                statusText.text = "状态: 加载失败 - ${ex.message}"
            }
        }
    }

    private fun bindProfiles(items: List<TcpClient.ModelSetting>, activeIndex: Int) {
        profiles = items
        if (items.isEmpty()) {
            profilesSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("(无配置)"))
            return
        }

        val labels = items.mapIndexed { index, item ->
            val activeTag = if (index == activeIndex) "[当前] " else ""
            "$activeTag${item.modelName} | ${item.apiUrl}"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profilesSpinner.adapter = adapter

        val safeIndex = activeIndex.coerceIn(0, items.lastIndex)
        profilesSpinner.setSelection(safeIndex)

        val selected = items[safeIndex]
        inputApiUrl.setText(selected.apiUrl)
        inputApiKey.setText(selected.apiKey)
        inputModelName.setText(selected.modelName)
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
                detectedModels = models
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
        AlertDialog.Builder(this)
            .setTitle("请选择 MODEL_NAME")
            .setSingleChoiceItems(options, 0) { _, which ->
                picked = which
            }
            .setPositiveButton("确定") { _, _ ->
                inputModelName.setText(models[picked])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addModelSetting() {
        val apiUrl = inputApiUrl.text.toString().trim()
        val apiKey = inputApiKey.text.toString().trim()
        val modelName = inputModelName.text.toString().trim()
        if (apiUrl.isBlank() || apiKey.isBlank() || modelName.isBlank()) {
            Toast.makeText(this, "请填写 URL / KEY / NAME", Toast.LENGTH_SHORT).show()
            return
        }

        val client = clientOrNull() ?: return

        val existingIndex = findProfileIndex(apiUrl, apiKey, modelName)
        if (existingIndex >= 0) {
            addButton.isEnabled = false
            statusText.text = "状态: 模型已添加，正在切换为当前..."
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        client.setActiveModel(existingIndex)
                    }
                    bindProfiles(result.profiles, result.activeIndex)
                    statusText.text = "状态: 已添加（已切换为当前）"
                    Toast.makeText(this@ModelSettingsActivity, "该模型已添加", Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    statusText.text = "状态: 切换失败 - ${ex.message}"
                } finally {
                    addButton.isEnabled = true
                }
            }
            return
        }

        addButton.isEnabled = false
        statusText.text = "状态: 正在保存模型配置..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.addModelSetting(apiUrl, apiKey, modelName, setActive = true)
                }
                bindProfiles(result.profiles, result.activeIndex)
                statusText.text = "状态: 已添加并启用模型"
                Toast.makeText(this@ModelSettingsActivity, "已添加并启用", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                statusText.text = "状态: 保存失败 - ${ex.message}"
            } finally {
                addButton.isEnabled = true
            }
        }
    }

    private fun applySelectedModel() {
        val selectedIndex = profilesSpinner.selectedItemPosition
        if (profiles.isEmpty() || selectedIndex !in profiles.indices) {
            Toast.makeText(this, "没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        val client = clientOrNull() ?: return
        useSelectedButton.isEnabled = false
        statusText.text = "状态: 正在切换模型..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.setActiveModel(selectedIndex)
                }
                bindProfiles(result.profiles, result.activeIndex)
                statusText.text = "状态: 已切换当前模型"
                Toast.makeText(this@ModelSettingsActivity, "当前模型已切换", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                statusText.text = "状态: 切换失败 - ${ex.message}"
            } finally {
                useSelectedButton.isEnabled = true
            }
        }
    }

    private fun confirmDeleteSelectedModel() {
        val selectedIndex = profilesSpinner.selectedItemPosition
        if (profiles.isEmpty() || selectedIndex !in profiles.indices) {
            Toast.makeText(this, "没有可用模型", Toast.LENGTH_SHORT).show()
            return
        }

        val target = profiles[selectedIndex]
        AlertDialog.Builder(this)
            .setTitle("删除模型配置")
            .setMessage("确认删除 ${target.modelName} ?")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedModel(selectedIndex)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedModel(selectedIndex: Int) {
        val client = clientOrNull() ?: return

        deleteSelectedButton.isEnabled = false
        statusText.text = "状态: 正在删除模型..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client.deleteModelSetting(selectedIndex)
                }
                bindProfiles(result.profiles, result.activeIndex)
                statusText.text = "状态: 模型已删除"
                Toast.makeText(this@ModelSettingsActivity, "删除成功", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                statusText.text = "状态: 删除失败 - ${ex.message}"
            } finally {
                deleteSelectedButton.isEnabled = true
            }
        }
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

        AlertDialog.Builder(this)
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

        val client = clientOrNull() ?: return
        batchDeleteButton.isEnabled = false
        statusText.text = "状态: 正在批量删除模型..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    var latest = client.getModelSettings()
                    for (index in indices.sortedDescending()) {
                        latest = client.deleteModelSetting(index)
                    }
                    latest
                }
                bindProfiles(result.profiles, result.activeIndex)
                statusText.text = "状态: 已批量删除 ${indices.size} 个模型"
                Toast.makeText(this@ModelSettingsActivity, "批量删除完成", Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                statusText.text = "状态: 批量删除失败 - ${ex.message}"
            } finally {
                batchDeleteButton.isEnabled = true
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
