package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.widget.ListListener

/**
 * Rewritten without the third-party MaterialAboutFragment library: that library combined with the
 * nested CoordinatorLayout/RecyclerView used to crash on some devices. This screen is now two
 * plain rows inside a MaterialCardView, so there's nothing left that can crash here.
 */
class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        view.findViewById<TextView>(R.id.about_version_subtitle).text =
            SagerNet.appVersionNameForDisplay

        view.findViewById<View>(R.id.about_row_telegram).setOnClickListener {
            it.context.launchCustomTab("https://t.me/v2ray_DA")
        }
    }
}
