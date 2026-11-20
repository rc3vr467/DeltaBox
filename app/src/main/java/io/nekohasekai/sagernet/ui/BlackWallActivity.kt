package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.AdBlockEngine
import io.nekohasekai.sagernet.bg.BlackWallEngine
import io.nekohasekai.sagernet.bg.BlackWallEngine.StealthLevel

/**
 * Black Wall — Anti-DPI Stealth Engine settings screen.
 *
 * Opened from the shield icon in the toolbar. Toggling the switch and
 * picking a level both write straight to DataStore (BlackWallEngine), so
 * the change is live for the *next* connection without needing a Save
 * button, and it never touches any other NekoBox preference key.
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
    private lateinit var adSwitch: SwitchMaterial
    private lateinit var adStatus: TextView
    private lateinit var adUpdate: TextView

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

        styleSwitch(switchView)

        switchView.isChecked = BlackWallEngine.enabled
        switchView.setOnCheckedChangeListener { _, isChecked ->
            BlackWallEngine.enabled = isChecked
            refresh()
        }

        // ---- AdBlock (independent of Black Wall; same live-on-next-connection behaviour) ----
        adSwitch = findViewById(R.id.ad_block_switch)
        adStatus = findViewById(R.id.ad_block_status)
        adUpdate = findViewById(R.id.ad_block_update)
        styleSwitch(adSwitch)
        adSwitch.isChecked = AdBlockEngine.enabled
        adSwitch.setOnCheckedChangeListener { _, isChecked ->
            AdBlockEngine.enabled = isChecked
            refreshAdBlock()
            if (isChecked) updateAdLists(silent = true)
        }
        adUpdate.setOnClickListener { updateAdLists(silent = false) }
        refreshAdBlock()

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

        val accent = MaterialColors.getColor(this, R.attr.blackWallAccent, 0)
        val surface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, 0)
        val textPrimary = MaterialColors.getColor(this, android.R.attr.textColorPrimary, 0)

        rows.forEach { row ->
            val selected = enabled && row.level == level
            row.container.alpha = if (enabled) 1f else 0.5f
            row.container.isEnabled = enabled
            (row.container.background as? GradientDrawable)?.setColor(
                if (selected) ColorUtils.setAlphaComponent(accent, 40) else surface
            )
            row.title.setTextColor(if (selected) accent else textPrimary)
            row.check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }

        noticeView.visibility =
            if (enabled || AdBlockEngine.enabled) View.VISIBLE else View.GONE
    }

    private fun refreshAdBlock() {
        val on = AdBlockEngine.enabled
        adUpdate.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            runOnDefaultDispatcher {
                val n = AdBlockEngine.domainCount(this@BlackWallActivity)
                runOnMainDispatcher {
                    adStatus.text = getString(R.string.ad_block_domains, n)
                }
            }
        } else {
            adStatus.setText(R.string.ad_block_subtitle)
        }
        refresh()
    }

    private fun updateAdLists(silent: Boolean) {
        adStatus.setText(R.string.ad_block_updating)
        runOnDefaultDispatcher {
            val n = AdBlockEngine.updateLists(this@BlackWallActivity)
            runOnMainDispatcher {
                if (n == 0 && !silent) {
                    Toast.makeText(this@BlackWallActivity, R.string.ad_block_update_failed, Toast.LENGTH_LONG).show()
                }
                refreshAdBlock()
            }
        }
    }

    /**
     * Switch colours: accent when on, plainly visible GRAY when off
     * (the old off-state used the dark surface colour, which looked black / invisible).
     */
    private fun styleSwitch(sw: SwitchMaterial) {
        val accent = MaterialColors.getColor(this, R.attr.blackWallAccent, 0)
        val offThumb = ContextCompat.getColor(this, R.color.delta_switch_off_thumb)
        val offTrack = ContextCompat.getColor(this, R.color.delta_switch_off_track)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        sw.thumbTintList = ColorStateList(states, intArrayOf(accent, offThumb))
        sw.trackTintList = ColorStateList(
            states, intArrayOf(ColorUtils.setAlphaComponent(accent, 120), offTrack)
        )
    }
}
