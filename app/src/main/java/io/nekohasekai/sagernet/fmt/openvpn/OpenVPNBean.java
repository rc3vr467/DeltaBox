package io.nekohasekai.sagernet.fmt.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenVPNBean extends AbstractBean {

    // mode: "tls" or "static_key"
    public String mode;

    // transport
    public String protocol; // udp, udp4, udp6, tcp, tcp4, tcp6

    // tls auth material (PEM text, inline, newline separated with \n escaped as real newlines)
    public String ca;
    public String cert;
    public String key;

    // control channel wrapping: tls-auth / tls-crypt / tls-crypt-v2
    public String controlWrapType; // "", "tls_auth", "tls_crypt", "tls_crypt_v2"
    public String controlWrapKey;
    public String keyDirection; // "", "server", "client"

    // static key mode (pre-shared static key, no TLS handshake)
    public String staticKey;

    // user/pass auth (auth-user-pass)
    public String username;
    public String password;

    public String cipher;
    public String auth;
    public String compression; // "", "lz4", "lz4-v2", "stub", "stub-v2"

    public Boolean redirectGateway;
    public Boolean blockIPv6;

    public Integer mtu;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (mode == null) mode = "tls";
        if (protocol == null) protocol = "udp";
        if (ca == null) ca = "";
        if (cert == null) cert = "";
        if (key == null) key = "";
        if (controlWrapType == null) controlWrapType = "";
        if (controlWrapKey == null) controlWrapKey = "";
        if (keyDirection == null) keyDirection = "";
        if (staticKey == null) staticKey = "";
        if (username == null) username = "";
        if (password == null) password = "";
        if (cipher == null) cipher = "";
        if (auth == null) auth = "";
        if (compression == null) compression = "";
        if (redirectGateway == null) redirectGateway = true;
        if (blockIPv6 == null) blockIPv6 = false;
        if (mtu == null) mtu = 1500;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(1);
        super.serialize(output);
        output.writeString(mode);
        output.writeString(protocol);
        output.writeString(ca);
        output.writeString(cert);
        output.writeString(key);
        output.writeString(controlWrapType);
        output.writeString(controlWrapKey);
        output.writeString(keyDirection);
        output.writeString(staticKey);
        output.writeString(username);
        output.writeString(password);
        output.writeString(cipher);
        output.writeString(auth);
        output.writeString(compression);
        output.writeBoolean(redirectGateway);
        output.writeBoolean(blockIPv6);
        output.writeInt(mtu);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        mode = input.readString();
        protocol = input.readString();
        ca = input.readString();
        cert = input.readString();
        key = input.readString();
        controlWrapType = input.readString();
        controlWrapKey = input.readString();
        keyDirection = input.readString();
        staticKey = input.readString();
        username = input.readString();
        password = input.readString();
        cipher = input.readString();
        auth = input.readString();
        compression = input.readString();
        redirectGateway = input.readBoolean();
        blockIPv6 = input.readBoolean();
        mtu = input.readInt();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @Override
    public boolean canMapping() {
        return false;
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(new OpenVPNBean(), KryoConverters.serialize(this));
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
