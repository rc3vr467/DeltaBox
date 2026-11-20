package com.deltabox.box.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.deltabox.box.R
import com.deltabox.box.bg.BlackWallEngine
import com.deltabox.box.bg.BlackWallEngine.StealthLevel

/**
 * Black Wall — Anti-DPI Stealth Engine settings screen.
 *
 * Opened from the shield icon in the toolbar. Toggling the switch and
 * picking a level both write straight to DataStore (BlackWallEngine), so
 * the change is live for the *next* connection without needing a Save
 * button, and it never touches any other DeltaBox preference key.
 */
class BlackWallActivity : ThemedActivity(R.layout.layout_black_wall) {

    private class LevelRow(
        val container: View,
        val title: TextView,
        val check: View,
        val level: StealthLevel,
    )

    private lateinit var rows: List<LevelRow>
    private lateinit var switchView: SwitchMaterial
    private lateinit var noticeView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.black_wall)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        switchView = findViewById(R.id.black_wall_switch)
        noticeView = findViewById(R.id.black_wall_applies_notice)

        rows = listOf(
            LevelRow(
                findViewById(R.id.level_ghost),
                findViewById(R.id.level_ghost_title),
                findViewById(R.id.level_ghost_check),
                StealthLevel.GHOST,
            ),
            LevelRow(
                findViewById(R.id.level_phantom),
                findViewById(R.id.level_phantom_title),
                findViewById(R.id.level_phantom_check),
                StealthLevel.PHANTOM,
            ),
            LevelRow(
                findViewById(R.id.level_wraith),
                findViewById(R.id.level_wraith_title),
                findViewById(R.id.level_wraith_check),
                StealthLevel.WRAITH,
            ),
        )

        rows.forEach { row ->
            row.container.setOnClickListener {
                BlackWallEngine.level = row.level
                refresh()
            }
        }

        switchView.isChecked = BlackWallEngine.enabled
        switchView.setOnCheckedChangeListener { _, isChecked ->
            BlackWallEngine.enabled = isChecked
            refresh()
        }

        // Black Wall starts at Ghost by default if no level was chosen yet.
        if (BlackWallEngine.level == StealthLevel.OFF) {
            BlackWallEngine.level = StealthLevel.GHOST
        }

        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val enabled = BlackWallEngine.enabled
        val level = BlackWallEngine.level

        val primary = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, 0)
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0)
        val textPrimary = MaterialColors.getColor(this, android.R.attr.textColorPrimary, 0)

        rows.forEach { row ->
            val selected = enabled && row.level == level
            row.container.alpha = if (enabled) 1f else 0.5f
            row.container.isEnabled = enabled
            (row.container.background as? GradientDrawable)?.setColor(
                if (selected) ColorUtils.setAlphaComponent(primary, 40) else surface
            )
            row.title.setTextColor(if (selected) primary else textPrimary)
            row.check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }

        noticeView.visibility = if (enabled) View.VISIBLE else View.GONE
    }
}
