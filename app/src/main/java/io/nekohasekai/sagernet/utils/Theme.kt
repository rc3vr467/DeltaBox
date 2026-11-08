package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21

    private fun defaultTheme() = PINK_SSR

    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    // DeltaBox uses one palette; "Dark" / "Light" is chosen with the night mode setting.
    @Suppress("UNUSED_PARAMETER")
    fun getTheme(theme: Int): Int = R.style.Theme_SagerNet_Delta

    @Suppress("UNUSED_PARAMETER")
    fun getDialogTheme(theme: Int): Int = R.style.Theme_SagerNet_Dialog_Delta

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        // 2 = Light, anything else = Dark (default)
        return if (mode == 2) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
    }

    fun usingNightMode(): Boolean {
        return DataStore.nightTheme != 2
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}