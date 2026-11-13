package io.nekohasekai.sagernet.fmt.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

/**
 * Stores an imported .ovpn profile as-is. serverAddress/serverPort (from
 * AbstractBean) are kept in sync with the profile's first "remote" so that
 * TCPing / connectivity checks and the profile list UI have something to
 * show without having to re-parse the config.
 *
 * The actual sing-box "openvpn-client" endpoint JSON is produced on demand
 * by OpenVpnFmt.toSingBoxEndpoint(config, ...) - see BoxInstance.kt /
 * OpenVpn3Instance wiring.
 */
public class OpenVPNBean extends AbstractBean {

    /**
     * Raw .ovpn profile text, exactly as imported (inline ca/cert/key/
     * tls-auth/tls-crypt/secret blocks included).
     */
    public String config;

    /**
     * Optional override credentials, used when the profile needs
     * auth-user-pass but has no inline username/password block.
     */
    public String username;
    public String password;

    @Override
    public void initializeDefaultValues() {
        if (config == null) config = "";
        if (username == null) username = "";
        if (password == null) password = "";

        try {
            OpenVpnFmt.Info info = OpenVpnFmt.inspect(config);
            serverAddress = info.getHost();
            serverPort = info.getPort();
        } catch (Exception ignored) {
            // leave serverAddress/serverPort as whatever AbstractBean defaults to;
            // the config is likely empty/invalid (e.g. a freshly created profile).
        }

        super.initializeDefaultValues();
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0); // version
        super.serialize(output);
        output.writeString(config);
        output.writeString(username);
        output.writeString(password);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        config = input.readString();
        username = input.readString();
        password = input.readString();
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(new OpenVPNBean(), KryoConverters.serialize(this));
    }

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) return name;
        try {
            return OpenVpnFmt.inspect(config).getName();
        } catch (Exception ignored) {
            return "OpenVPN";
        }
    }

    @Override
    public String displayAddress() {
        try {
            OpenVpnFmt.Info info = OpenVpnFmt.inspect(config);
            return info.getHost() + ":" + info.getPort();
        } catch (Exception ignored) {
            return super.displayAddress();
        }
    }

    public static final Creator<OpenVPNBean> CREATOR = new CREATOR<OpenVPNBean>() {
        @NonNull
        @Override
        public OpenVPNBean newInstance() {
            return new OpenVPNBean();
        }

        @Override
        public OpenVPNBean[] newArray(int size) {
            return new OpenVPNBean[size];
        }
    };

}
