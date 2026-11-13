package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.openvpn.OpenVPNBean
import io.nekohasekai.sagernet.fmt.openvpn.OpenVpnFmt
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

/**
 * Prompts for the OpenVPN username/password this profile's config needs,
 * right before connecting, when nothing is saved for it yet.
 *
 * Usage (see MainActivity's fab click handler):
 *
 *     OpenVpnCredentialsDialog.maybeShow(activity, profile) { proceed() }
 *
 * `onReady` always runs eventually: immediately if no prompt is needed,
 * after a successful save if the user confirms, or never if the user
 * cancels (so the caller never starts the VPN with missing credentials).
 */
object OpenVpnCredentialsDialog {

    /**
     * Checks whether [profile] is an OpenVPN profile that needs auth-user-pass
     * and has no username/password saved. If so, shows the dialog and saves
     * what the user enters before calling [onReady]. Otherwise calls
     * [onReady] right away without showing anything.
     */
    fun maybeShow(activity: Activity, profile: ProxyEntity, onReady: () -> Unit) {
        val bean = try {
            profile.requireBean() as? OpenVPNBean
        } catch (_: Exception) {
            null
        }
        if (bean == null) {
            onReady()
            return
        }

        if (bean.username.isNotBlank() && bean.password.isNotBlank()) {
            // Already have something saved; connect with it as-is. If it
            // turns out to be wrong, the connection error (surfaced via the
            // normal service error path) is how the user finds out - we
            // don't re-prompt here, per the "ask only when empty" behavior.
            onReady()
            return
        }

        val needsCredentials = try {
            OpenVpnFmt.inspect(bean.config).needsCredentials
        } catch (_: Exception) {
            false
        }
        if (!needsCredentials) {
            onReady()
            return
        }

        show(activity, profile, bean, onReady)
    }

    private fun show(activity: Activity, profile: ProxyEntity, bean: OpenVPNBean, onReady: () -> Unit) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.layout_openvpn_credentials_dialog, null, false)
        val usernameLayout = view.findViewById<TextInputLayout>(R.id.username_layout)
        val passwordLayout = view.findViewById<TextInputLayout>(R.id.password_layout)
        val usernameInput = view.findViewById<TextInputEditText>(R.id.username)
        val passwordInput = view.findViewById<TextInputEditText>(R.id.password)
        val rememberBox = view.findViewById<MaterialCheckBox>(R.id.remember)

        usernameInput.setText(bean.username)
        passwordInput.setText(bean.password)

        lateinit var dialog: AlertDialog
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.openvpn_credentials_title, bean.displayName()))
            .setMessage(R.string.openvpn_credentials_message)
            .setView(view)
            .setPositiveButton(R.string.connect, null) // validated manually below, see setOnShowListener
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = usernameInput.text?.toString().orEmpty()
                val password = passwordInput.text?.toString().orEmpty()
                var valid = true
                if (username.isBlank()) {
                    usernameLayout.error = activity.getString(R.string.openvpn_credentials_required)
                    valid = false
                } else {
                    usernameLayout.error = null
                }
                if (password.isBlank()) {
                    passwordLayout.error = activity.getString(R.string.openvpn_credentials_required)
                    valid = false
                } else {
                    passwordLayout.error = null
                }
                if (!valid) return@setOnClickListener

                if (rememberBox.isChecked) {
                    bean.username = username
                    bean.password = password
                    profile.putBean(bean)
                    runOnDefaultDispatcher {
                        ProfileManager.updateProfile(profile)
                        runOnMainDispatcher {
                            dialog.dismiss()
                            onReady()
                        }
                    }
                } else {
                    // Not saved to the profile; the engine/config builder
                    // still needs it for *this* connection attempt only.
                    bean.username = username
                    bean.password = password
                    profile.putBean(bean)
                    dialog.dismiss()
                    onReady()
                }
            }
        }

        dialog.show()
    }
}
