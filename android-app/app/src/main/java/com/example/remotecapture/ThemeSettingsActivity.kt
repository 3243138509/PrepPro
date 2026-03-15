package com.PrepPro.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

class ThemeSettingsActivity : AppCompatActivity() {

    data class AppTheme(
        val name: String,
        val primaryColor: String,
        val backgroundColor: String
    )

    private val themes = listOf(
        AppTheme("翠绿", "#0CA7A5", "#D6F1EF"),
        AppTheme("深海", "#3949AB", "#E8EAF6"),
        AppTheme("森林", "#2E7D32", "#E8F5E9"),
        AppTheme("蔷薇", "#D81B60", "#FCE4EC"),
        AppTheme("落日", "#E65100", "#FFF3E0"),
        AppTheme("薰衣草", "#7B1FA2", "#F3E5F5"),
        AppTheme("石墨", "#424242", "#F5F5F5"),
        AppTheme("琥珀", "#FF8F00", "#FFF8E1")
    )

    private val themePrefs by lazy { getSharedPreferences("theme_prefs", Context.MODE_PRIVATE) }
    private lateinit var gridThemes: GridLayout
    private lateinit var cardModeLight: MaterialCardView
    private lateinit var cardModeDark: MaterialCardView
    private lateinit var cardModeAuto: MaterialCardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_settings)

        applyThemeSettings()

        findViewById<ImageButton>(R.id.buttonBack).setOnClickListener {
            finishAfterTransition()
        }

        gridThemes = findViewById(R.id.gridThemes)
        cardModeLight = findViewById(R.id.cardModeLight)
        cardModeDark = findViewById(R.id.cardModeDark)
        cardModeAuto = findViewById(R.id.cardModeAuto)

        updateModeSelectionUi()

        cardModeLight.setOnClickListener {
            setThemeMode(false, false)
        }
        cardModeDark.setOnClickListener {
            setThemeMode(true, false)
        }
        cardModeAuto.setOnClickListener {
            setThemeMode(false, true)
        }

        setupThemeGrid()
    }

    private fun applyThemeSettings() {
        val themeIndex = themePrefs.getInt("theme_index", 0)
        val backgroundColors = listOf("#D6F1EF", "#E8EAF6", "#E8F5E9", "#FCE4EC", "#FFF3E0", "#F3E5F5", "#F5F5F5", "#FFF8E1")
        val backgroundColor = Color.parseColor(backgroundColors.getOrNull(themeIndex) ?: "#D6F1EF")
        val primaryColor = Color.parseColor(themes.getOrNull(themeIndex)?.primaryColor ?: "#0CA7A5")

        findViewById<View>(R.id.rootLayoutTheme)?.let { root ->
            root.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(backgroundColor, Color.WHITE)
            )
            updateAllCardStrokes(root, primaryColor)
        }
    }

    private fun updateAllCardStrokes(view: View, color: Int) {
        if (view is MaterialCardView) {
            // Only update cards that are not mode selection cards (which have special handling)
            val id = view.id
            if (id != R.id.cardModeLight && id != R.id.cardModeDark && id != R.id.cardModeAuto) {
                // Large container cards should have a gray stroke
                view.strokeWidth = dpToPx(1f)
                view.strokeColor = ContextCompat.getColor(this, R.color.surface_stroke)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateAllCardStrokes(view.getChildAt(i), color)
            }
        }
    }

    private fun setThemeMode(isDark: Boolean, followSystem: Boolean) {
        themePrefs.edit()
            .putBoolean("is_dark_mode", isDark)
            .putBoolean("follow_system", followSystem)
            .apply()
        
        updateModeSelectionUi()
        applyThemeChange()
    }

    private fun updateModeSelectionUi() {
        val isDark = themePrefs.getBoolean("is_dark_mode", false)
        val followSystem = themePrefs.getBoolean("follow_system", true)
        val themeIndex = themePrefs.getInt("theme_index", 0)
        val primaryColor = Color.parseColor(themes[themeIndex].primaryColor)

        // Reset all
        resetCardStyle(cardModeLight)
        resetCardStyle(cardModeDark)
        resetCardStyle(cardModeAuto)

        // Apply active style
        when {
            followSystem -> activeCardStyle(cardModeAuto, primaryColor)
            isDark -> activeCardStyle(cardModeDark, primaryColor)
            else -> activeCardStyle(cardModeLight, primaryColor)
        }
    }

    private fun resetCardStyle(card: MaterialCardView) {
        card.strokeColor = ContextCompat.getColor(this, R.color.surface_stroke)
        card.strokeWidth = dpToPx(1f)
        card.cardElevation = dpToPx(2f).toFloat()
        (card.getChildAt(0) as TextView).setTextColor(ContextCompat.getColor(this, R.color.ink_900))
    }

    private fun activeCardStyle(card: MaterialCardView, color: Int) {
        card.strokeColor = color
        card.strokeWidth = dpToPx(2f)
        card.cardElevation = dpToPx(4f).toFloat()
        (card.getChildAt(0) as TextView).setTextColor(color)
    }

    private fun setupThemeGrid() {
        val currentThemeIndex = themePrefs.getInt("theme_index", 0)
        gridThemes.removeAllViews()

        // Calculate item width based on screen width to ensure 4 columns fit perfectly
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels - dpToPx(48f) // 16dp*2 layout padding + 8dp*2 grid padding
        val itemWidth = screenWidth / 4

        themes.forEachIndexed { index, appTheme ->
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = GridLayout.LayoutParams().apply {
                    width = itemWidth
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                }
                setPadding(0, dpToPx(12f), 0, dpToPx(12f))
            }

            val colorCircle = View(this).apply {
                val size = dpToPx(48f)
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(appTheme.primaryColor))
                    if (index == currentThemeIndex) {
                        setStroke(dpToPx(3f), Color.WHITE)
                    }
                }
            }

            val outerCard = MaterialCardView(this).apply {
                val size = dpToPx(54f)
                radius = (size / 2).toFloat()
                cardElevation = if (index == currentThemeIndex) dpToPx(4f).toFloat() else 0f
                strokeWidth = if (index == currentThemeIndex) dpToPx(2f) else 0
                strokeColor = Color.parseColor(appTheme.primaryColor)
                layoutParams = LinearLayout.LayoutParams(size, size)
                setContentPadding(dpToPx(3f), dpToPx(3f), dpToPx(3f), dpToPx(3f))
                addView(colorCircle)
            }

            val label = TextView(this).apply {
                text = appTheme.name
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ThemeSettingsActivity, R.color.ink_700))
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(4f), 0, 0)
            }

            container.addView(outerCard)
            container.addView(label)

            container.setOnClickListener {
                themePrefs.edit().putInt("theme_index", index).apply()
                updateModeSelectionUi() // Colors might change
                setupThemeGrid()
                applyThemeChange()
            }

            gridThemes.addView(container)
        }
    }

    private fun applyThemeChange() {
        val followSystem = themePrefs.getBoolean("follow_system", true)
        val isDarkMode = themePrefs.getBoolean("is_dark_mode", false)

        if (followSystem) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        } else {
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        
        applyThemeSettings()
        Toast.makeText(this, "主题设置已更新", Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }
}
