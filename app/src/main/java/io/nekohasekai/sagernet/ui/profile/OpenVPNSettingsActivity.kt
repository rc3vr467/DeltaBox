package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVpnFmt
import moe.matsuri.nb4a.ui.EditConfigPreference

class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {

    override fun createEntity() = OpenVPNBean()

    override fun OpenVPNBean.init() {
        DataStore.profileName = name
        DataStore.serverConfig = config
        DataStore.serverUsername = username
        DataStore.serverPassword = password
    }

    override fun OpenVPNBean.serialize() {
        name = DataStore.profileName
        config = DataStore.serverConfig ?: ""
        username = DataStore.serverUsername ?: ""
        password = DataStore.serverPassword ?: ""

        // Re-derive serverAddress/serverPort from the pasted config so the
        // profile list / TCPing show the real remote, and fail fast here
        // (before the user leaves this screen) if the config can't be parsed
        // at all, rather than only failing later when actually connecting.
        try {
            val info = OpenVpnFmt.inspect(config)
            serverAddress = info.host
            serverPort = info.port
        } catch (e: OpenVpnFmt.UnsupportedException) {
            throw IllegalArgumentException(e.message ?: "Invalid OpenVPN profile", e)
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.openvpn_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!

        findPreference<EditTextPreference>(Key.SERVER_USERNAME)!!.apply {
            summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        }
        findPreference<EditTextPreference>(Key.SERVER_PASSWORD)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

}
