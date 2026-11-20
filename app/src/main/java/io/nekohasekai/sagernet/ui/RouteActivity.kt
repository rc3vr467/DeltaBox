package io.nekohasekai.sagernet.ui

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R

/**
 * Hosts [RouteFragment] so the route rules can be opened from the Assets screen
 * (Route used to be a top-level drawer entry).
 */
class RouteActivity : ThemedActivity(R.layout.layout_route_activity) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.route_fragment_holder, RouteFragment())
                .commitAllowingStateLoss()
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(findViewById(R.id.route_fragment_holder), text, Snackbar.LENGTH_LONG)
    }
}
