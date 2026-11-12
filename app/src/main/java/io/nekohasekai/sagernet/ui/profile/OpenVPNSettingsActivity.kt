package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class OpenVPNSettingsActivity : ProfileSettingsActivity<OpenVPNBean>() {

    override fun createEntity() = OpenVPNBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val protocol = pbm.add(PreferenceBinding(Type.Text, "protocol"))
    private val mode = pbm.add(PreferenceBinding(Type.Text, "mode"))
    private val mtu = pbm.add(PreferenceBinding(Type.TextToInt, "mtu"))
    private val ca = pbm.add(PreferenceBinding(Type.Text, "ca"))
    private val cert = pbm.add(PreferenceBinding(Type.Text, "cert"))
    private val key = pbm.add(PreferenceBinding(Type.Text, "key"))
    private val staticKey = pbm.add(PreferenceBinding(Type.Text, "staticKey"))
    private val controlWrapType = pbm.add(PreferenceBinding(Type.Text, "controlWrapType"))
    private val controlWrapKey = pbm.add(PreferenceBinding(Type.Text, "controlWrapKey"))
    private val keyDirection = pbm.add(PreferenceBinding(Type.Text, "keyDirection"))
    private val username = pbm.add(PreferenceBinding(Type.Text, "username"))
    private val password = pbm.add(PreferenceBinding(Type.Text, "password"))
    private val cipher = pbm.add(PreferenceBinding(Type.Text, "cipher"))
    private val auth = pbm.add(PreferenceBinding(Type.Text, "auth"))
    private val compression = pbm.add(PreferenceBinding(Type.Text, "compression"))
    private val redirectGateway = pbm.add(PreferenceBinding(Type.Bool, "redirectGateway"))
    private val blockIPv6 = pbm.add(PreferenceBinding(Type.Bool, "blockIPv6"))

    override fun OpenVPNBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun OpenVPNBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.openvpn_preferences)
        pbm.setPreferenceFragment(this)

        (serverPort.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        (mtu.preference as EditTextPreference)
            .setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        (key.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        (password.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }

}
