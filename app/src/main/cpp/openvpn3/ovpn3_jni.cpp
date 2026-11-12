// JNI bridge between DeltaBox (Kotlin) and the OpenVPN 3 client core.
//
// STATUS: NOT COMPILED, NOT RUN. Written against the vendored sources in
// app/src/main/cpp/openvpn3 (openvpn3 master 0b27d9d):
//   client/ovpncli.hpp   -> OpenVPNClient, Config, ProvideCreds, Event, Status, LogInfo
//   openvpn/tun/builder/base.hpp -> TunBuilderBase tun_builder_* virtuals
// Not referenced by the Gradle build yet. See README-STATUS.md.
//
// Threading:
//   * nativeStart() performs eval_config()/provide_creds() on the calling thread and
//     then starts ONE dedicated thread that runs the blocking connect().
//   * All core callbacks (event, log, tun_builder_*, socket_protect) run on that thread.
//   * nativeStop() may be called from any thread (core stop() is thread-safe).
//   * nativeDestroy() stops the session, joins the thread, then frees everything.
//     It must not be called from inside a listener callback.
//
// Security: passwords are passed to provide_creds() and are never logged here.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>

#include "client/ovpncli.hpp"

namespace
{

constexpr const char *kTag = "ovpn3";
// How long nativeDestroy() waits for the core to stop before giving up (see below).
constexpr int kDestroyTimeoutMs = 10000;

JavaVM *g_vm = nullptr;
std::mutex g_registry_mu;
bool g_session_active = false; // one OpenVPN session per process

// Overwrites a secret before its memory is released. Volatile writes keep the compiler
// from removing the wipe as a dead store.
void wipeString(std::string &s)
{
    volatile char *p = s.empty() ? nullptr : &s[0];
    for (size_t i = 0; p != nullptr && i < s.size(); ++i)
    {
        p[i] = 0;
    }
    s.clear();
}

// Attaches the calling thread to the JVM if it is not attached yet.
class JniScope
{
  public:
    explicit JniScope(JavaVM *vm) : vm_(vm)
    {
        if (vm_ == nullptr)
        {
            return;
        }
        jint rc = vm_->GetEnv(reinterpret_cast<void **>(&env_), JNI_VERSION_1_6);
        if (rc == JNI_EDETACHED)
        {
            if (vm_->AttachCurrentThread(reinterpret_cast<void **>(&env_), nullptr) == JNI_OK)
            {
                attached_ = true;
            }
            else
            {
                env_ = nullptr;
            }
        }
    }

    ~JniScope()
    {
        if (attached_)
        {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv *env() const { return env_; }

    JniScope(const JniScope &) = delete;
    JniScope &operator=(const JniScope &) = delete;

  private:
    JavaVM *vm_;
    JNIEnv *env_ = nullptr;
    bool attached_ = false;
};

// Owns a local jstring and deletes the local reference when it goes out of scope.
// Needed because the connect() thread stays attached for the whole session and the
// JNI local reference table is small on Android.
class LocalString
{
  public:
    LocalString(JNIEnv *env, const std::string &value)
        : env_(env), ref_(env->NewStringUTF(value.c_str()))
    {
    }

    ~LocalString()
    {
        if (ref_ != nullptr)
        {
            env_->DeleteLocalRef(ref_);
        }
    }

    LocalString(const LocalString &) = delete;
    LocalString &operator=(const LocalString &) = delete;

    jstring get() const { return ref_; }

  private:
    JNIEnv *env_;
    jstring ref_;
};

// Clears a pending Java exception so it never crosses back into the C++ core.
bool clearException(JNIEnv *env)
{
    if (env->ExceptionCheck())
    {
        env->ExceptionClear();
        return true;
    }
    return false;
}

class OvpnSession final : public OpenVPNClient
{
  public:
    OvpnSession(JavaVM *vm, jobject listener_global) : vm_(vm), listener_(listener_global) {}

    ~OvpnSession() override = default;

    OvpnSession(const OvpnSession &) = delete;
    OvpnSession &operator=(const OvpnSession &) = delete;

    // Looks up every listener method once. Returns false if any is missing.
    bool resolveMethods(JNIEnv *env)
    {
        jclass cls = env->GetObjectClass(listener_);
        if (cls == nullptr)
        {
            return false;
        }
        m_event_ = env->GetMethodID(cls, "onEvent", "(Ljava/lang/String;Ljava/lang/String;ZZ)V");
        m_log_ = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
        m_set_remote_ = env->GetMethodID(cls, "tunSetRemoteAddress", "(Ljava/lang/String;Z)Z");
        m_add_address_ = env->GetMethodID(cls, "tunAddAddress", "(Ljava/lang/String;ILjava/lang/String;ZZ)Z");
        m_add_route_ = env->GetMethodID(cls, "tunAddRoute", "(Ljava/lang/String;IIZZ)Z");
        m_set_mtu_ = env->GetMethodID(cls, "tunSetMtu", "(I)Z");
        m_set_session_name_ = env->GetMethodID(cls, "tunSetSessionName", "(Ljava/lang/String;)Z");
        m_establish_ = env->GetMethodID(cls, "tunEstablish", "()I");
        m_teardown_ = env->GetMethodID(cls, "tunTeardown", "(Z)V");
        m_protect_ = env->GetMethodID(cls, "protectSocket", "(IZ)Z");
        m_add_dns_ = env->GetMethodID(cls, "tunAddDnsServer", "(Ljava/lang/String;)Z");
        m_add_search_ = env->GetMethodID(cls, "tunAddSearchDomain", "(Ljava/lang/String;)Z");
        m_new_ = env->GetMethodID(cls, "tunNew", "()Z");
        env->DeleteLocalRef(cls);

        if (clearException(env))
        {
            return false;
        }
        return m_event_ && m_log_ && m_set_remote_ && m_add_address_ && m_add_route_ &&
               m_set_mtu_ && m_set_session_name_ && m_establish_ && m_teardown_ && m_protect_ &&
               m_add_dns_ && m_add_search_ && m_new_;
    }

    // Runs eval_config() and provide_creds() on the caller thread and starts the connect
    // thread. Returns an empty string on success, otherwise the error message.
    // Takes its arguments by value so the sensitive copies can be wiped here.
    std::string start(std::string content, std::string protoOverride, std::string username,
                      std::string password)
    {
        if (started_.exchange(true))
        {
            wipeString(password);
            wipeString(content);
            return "OpenVPN session already started";
        }

        Config config;
        config.content = content;
        config.protoOverride = protoOverride;
        wipeString(content);

        EvalConfig eval = eval_config(config);
        wipeString(config.content);
        if (eval.error)
        {
            wipeString(password);
            return eval.message.empty() ? "invalid OpenVPN profile" : eval.message;
        }

        ProvideCreds creds;
        creds.username = username;
        creds.password = password;
        wipeString(password);
        Status creds_status = provide_creds(creds);
        wipeString(creds.password);
        if (creds_status.error)
        {
            return creds_status.message.empty() ? "invalid credentials" : creds_status.message;
        }

        thread_ = std::thread([this]() { runConnect(); });
        return std::string();
    }

    // Requests disconnect. Core stop() only takes effect while connect() is running, so
    // the caller keeps calling this until the connect thread reports it has finished.
    void requestStop()
    {
        stop_requested_.store(true);
        stop();
    }

    // Asks the core to stop and waits up to timeoutMs for the connect thread to finish.
    // Returns true when the thread has finished (or never started) and it is safe to free
    // the session. Returns false if it is still running; the caller must then not free it.
    bool stopAndWait(int timeoutMs)
    {
        if (!thread_.joinable())
        {
            return true;
        }
        const int step_ms = 50;
        for (int waited = 0; waited <= timeoutMs; waited += step_ms)
        {
            requestStop();
            if (finished_.load())
            {
                thread_.join();
                return true;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(step_ms));
        }
        return false;
    }

    bool isConnectThread() const { return thread_.get_id() == std::this_thread::get_id(); }

    jobject listener() const { return listener_; }

    // ---- core callbacks (connect() thread) --------------------------------

    void event(const Event &ev) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return;
        }
        LocalString name(env, ev.name);
        LocalString info(env, ev.info);
        env->CallVoidMethod(listener_, m_event_, name.get(), info.get(),
                            static_cast<jboolean>(ev.error), static_cast<jboolean>(ev.fatal));
        clearException(env);
    }

    void acc_event(const AppCustomControlMessageEvent &) override
    {
        // Custom app control channel is not used by DeltaBox. Ignored on purpose.
    }

    void log(const LogInfo &info) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return;
        }
        LocalString text(env, info.text);
        env->CallVoidMethod(listener_, m_log_, text.get());
        clearException(env);
    }

    bool pause_on_connection_timeout() override { return false; }

    void external_pki_cert_request(ExternalPKICertRequest &req) override
    {
        req.error = true;
        req.errorText = "External PKI is not supported by DeltaBox";
    }

    void external_pki_sign_request(ExternalPKISignRequest &req) override
    {
        req.error = true;
        req.errorText = "External PKI is not supported by DeltaBox";
    }

    // ---- TunBuilderBase: forwarded to Kotlin VpnService.Builder ------------

    // The core calls these unconditionally during setup. TunBuilderBase defaults return
    // false, which would abort every connection, so each one is overridden here.

    // Starts a fresh VpnService.Builder for this connect attempt.
    bool tun_builder_new() override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        jboolean ok = env->CallBooleanMethod(listener_, m_new_);
        return !clearException(env) && ok == JNI_TRUE;
    }

    // Only TUN (layer 3) is supported on Android. 0 means "not specified".
    bool tun_builder_set_layer(int layer) override
    {
        if (layer == 3 || layer == 0)
        {
            return true;
        }
        logText("TUN layer " + std::to_string(layer) + " is not supported on Android");
        return false;
    }

    // Android has no gateway rerouting API. redirect-gateway is emulated with the usual
    // split default routes, which override the default route without removing it.
    bool tun_builder_reroute_gw(bool ipv4, bool ipv6, unsigned int flags) override
    {
        (void)flags;
        bool ok = true;
        if (ipv4)
        {
            ok = addRoute("0.0.0.0", 1, 0, false, false) && ok;
            ok = addRoute("128.0.0.0", 1, 0, false, false) && ok;
        }
        if (ipv6)
        {
            ok = addRoute("::", 1, 0, true, false) && ok;
            ok = addRoute("8000::", 1, 0, true, false) && ok;
        }
        return ok;
    }

    // Route metrics are not configurable through VpnService. Accept and ignore.
    bool tun_builder_set_route_metric_default(int metric) override
    {
        (void)metric;
        return true;
    }

    // The following settings have no VpnService equivalent. They are reported to the
    // app log and accepted, so the connection is not aborted for an optional option.
    bool tun_builder_add_proxy_bypass(const std::string &bypass_host) override
    {
        logText("proxy bypass setting ignored on Android VpnService");
        (void)bypass_host;
        return true;
    }

    bool tun_builder_set_proxy_auto_config_url(const std::string &url) override
    {
        (void)url;
        logText("proxy auto-config setting ignored on Android VpnService");
        return true;
    }

    bool tun_builder_set_proxy_http(const std::string &host, int port) override
    {
        (void)host;
        (void)port;
        logText("HTTP proxy setting ignored on Android VpnService");
        return true;
    }

    bool tun_builder_set_proxy_https(const std::string &host, int port) override
    {
        (void)host;
        (void)port;
        logText("HTTPS proxy setting ignored on Android VpnService");
        return true;
    }

    bool tun_builder_add_wins_server(const std::string &address) override
    {
        (void)address;
        logText("WINS server setting ignored on Android VpnService");
        return true;
    }

    bool tun_builder_set_remote_address(const std::string &address, bool ipv6) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        LocalString addr(env, address);
        jboolean ok = env->CallBooleanMethod(listener_, m_set_remote_, addr.get(), static_cast<jboolean>(ipv6));
        return !clearException(env) && ok == JNI_TRUE;
    }

    bool tun_builder_add_address(const std::string &address, int prefix_length, const std::string &gateway,
                                 bool ipv6, bool net30) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        LocalString addr(env, address);
        LocalString gw(env, gateway);
        jboolean ok = env->CallBooleanMethod(listener_, m_add_address_, addr.get(),
                                             static_cast<jint>(prefix_length), gw.get(),
                                             static_cast<jboolean>(ipv6), static_cast<jboolean>(net30));
        return !clearException(env) && ok == JNI_TRUE;
    }

    bool tun_builder_add_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return addRoute(address, prefix_length, metric, ipv6, false);
    }

    bool tun_builder_exclude_route(const std::string &address, int prefix_length, int metric, bool ipv6) override
    {
        return addRoute(address, prefix_length, metric, ipv6, true);
    }

    bool tun_builder_set_mtu(int mtu) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        jboolean ok = env->CallBooleanMethod(listener_, m_set_mtu_, static_cast<jint>(mtu));
        return !clearException(env) && ok == JNI_TRUE;
    }

    bool tun_builder_set_session_name(const std::string &name) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        LocalString n(env, name);
        jboolean ok = env->CallBooleanMethod(listener_, m_set_session_name_, n.get());
        return !clearException(env) && ok == JNI_TRUE;
    }

    // Returns the TUN file descriptor. Kotlin owns the descriptor from here on and
    // closes it in tun_builder_teardown(); the core must not close it.
    int tun_builder_establish() override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return -1;
        }
        jint fd = env->CallIntMethod(listener_, m_establish_);
        if (clearException(env))
        {
            return -1;
        }
        return static_cast<int>(fd);
    }

    void tun_builder_teardown(bool disconnect) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return;
        }
        env->CallVoidMethod(listener_, m_teardown_, static_cast<jboolean>(disconnect));
        clearException(env);
    }

    // Protects the transport socket so its traffic bypasses the VPN (prevents routing loops).
    bool socket_protect(openvpn_io::detail::socket_type socket, std::string remote, bool ipv6) override
    {
        (void)remote;
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        jboolean ok = env->CallBooleanMethod(listener_, m_protect_, static_cast<jint>(socket),
                                             static_cast<jboolean>(ipv6));
        return !clearException(env) && ok == JNI_TRUE;
    }

    // Applies DNS servers and search domains from the server/profile (DnsOptions).
    // Servers are taken in priority order (std::map key). VpnService.Builder cannot
    // route individual domains to individual servers, set a non-53 port, or use DoT/DoH:
    // those parts are reported to the app log and the global DNS server list is applied.
    bool tun_builder_set_dns_options(const DnsOptions &dns) override
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        bool any_server = false;
        for (const auto &entry : dns.servers)
        {
            const auto &server = entry.second;
            if (!server.domains.empty())
            {
                logToApp(env, "DNS: per-domain routing is not supported by VpnService; applied as global DNS");
            }
            for (const auto &addr : server.addresses)
            {
                if (addr.port != 0 && addr.port != 53)
                {
                    logToApp(env, "DNS: custom port is not supported by VpnService; using port 53");
                }
                LocalString a(env, addr.address);
                jboolean ok = env->CallBooleanMethod(listener_, m_add_dns_, a.get());
                if (!clearException(env) && ok == JNI_TRUE)
                {
                    any_server = true;
                }
            }
        }
        for (const auto &domain : dns.search_domains)
        {
            LocalString d(env, domain.domain);
            env->CallBooleanMethod(listener_, m_add_search_, d.get());
            clearException(env);
        }
        return any_server;
    }

  private:
    void logToApp(JNIEnv *env, const std::string &text)
    {
        LocalString t(env, text);
        env->CallVoidMethod(listener_, m_log_, t.get());
        clearException(env);
    }

    // Sends a line to the app log from any core thread.
    void logText(const std::string &text)
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return;
        }
        logToApp(env, text);
    }

    void runConnect()
    {
        if (!stop_requested_.load())
        {
            Status status = connect(); // blocks until disconnect
            if (status.error)
            {
                Event ev;
                ev.error = true;
                ev.fatal = true;
                ev.name = status.status.empty() ? "CONNECT_FAILED" : status.status;
                ev.info = status.message;
                event(ev);
            }
        }
        __android_log_print(ANDROID_LOG_INFO, kTag, "connect() returned");
        // Set last: nativeDestroy() may free the session once this is observed.
        finished_.store(true);
    }

    bool addRoute(const std::string &address, int prefix_length, int metric, bool ipv6, bool exclude)
    {
        JniScope scope(vm_);
        JNIEnv *env = scope.env();
        if (env == nullptr || listener_ == nullptr)
        {
            return false;
        }
        LocalString addr(env, address);
        jboolean ok = env->CallBooleanMethod(listener_, m_add_route_, addr.get(),
                                             static_cast<jint>(prefix_length), static_cast<jint>(metric),
                                             static_cast<jboolean>(ipv6), static_cast<jboolean>(exclude));
        return !clearException(env) && ok == JNI_TRUE;
    }

    JavaVM *vm_;
    jobject listener_; // JNI global reference, owned by this session
    std::thread thread_;
    std::atomic<bool> started_{false};        // start() ran once
    std::atomic<bool> stop_requested_{false}; // stop asked before or during connect()
    std::atomic<bool> finished_{false};       // connect thread has left connect()

    jmethodID m_event_ = nullptr;
    jmethodID m_log_ = nullptr;
    jmethodID m_set_remote_ = nullptr;
    jmethodID m_add_address_ = nullptr;
    jmethodID m_add_route_ = nullptr;
    jmethodID m_set_mtu_ = nullptr;
    jmethodID m_set_session_name_ = nullptr;
    jmethodID m_establish_ = nullptr;
    jmethodID m_teardown_ = nullptr;
    jmethodID m_protect_ = nullptr;
    jmethodID m_add_dns_ = nullptr;
    jmethodID m_add_search_ = nullptr;
    jmethodID m_new_ = nullptr;
};

OvpnSession *fromHandle(jlong handle)
{
    return reinterpret_cast<OvpnSession *>(handle);
}

std::string toStdString(JNIEnv *env, jstring s)
{
    if (s == nullptr)
    {
        return std::string();
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr)
    {
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *)
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// Returns a handle, or 0 if a session is already active or setup failed.
JNIEXPORT jlong JNICALL
Java_io_nekohasekai_sagernet_ovpn3_OpenVpn3Native_nativeCreate(JNIEnv *env, jobject thiz, jobject listener)
{
    (void)thiz;
    if (listener == nullptr)
    {
        return 0;
    }
    std::lock_guard<std::mutex> lock(g_registry_mu);
    if (g_session_active)
    {
        return 0;
    }
    jobject global = env->NewGlobalRef(listener);
    if (global == nullptr)
    {
        return 0;
    }
    auto *session = new OvpnSession(g_vm, global);
    if (!session->resolveMethods(env))
    {
        env->DeleteGlobalRef(global);
        delete session;
        return 0;
    }
    g_session_active = true;
    return reinterpret_cast<jlong>(session);
}

// Returns null on success, otherwise the error message to show the user.
JNIEXPORT jstring JNICALL
Java_io_nekohasekai_sagernet_ovpn3_OpenVpn3Native_nativeStart(JNIEnv *env, jobject thiz, jlong handle,
                                                              jstring content, jstring protoOverride,
                                                              jstring username, jstring password)
{
    (void)thiz;
    OvpnSession *session = fromHandle(handle);
    if (session == nullptr)
    {
        return env->NewStringUTF("OpenVPN session is not initialised");
    }
    std::string error = session->start(toStdString(env, content), toStdString(env, protoOverride),
                                       toStdString(env, username), toStdString(env, password));
    if (error.empty())
    {
        return nullptr;
    }
    return env->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL Java_io_nekohasekai_sagernet_ovpn3_OpenVpn3Native_nativeStop(JNIEnv *env, jobject thiz,
                                                                                     jlong handle)
{
    (void)env;
    (void)thiz;
    OvpnSession *session = fromHandle(handle);
    if (session != nullptr)
    {
        session->requestStop();
    }
}

// Stops, waits for the connect thread and frees the session. Must not be called from a
// listener callback (that would wait on itself). If the core does not stop within
// kDestroyTimeoutMs the session is intentionally NOT freed: the connect thread may still
// use it, and freeing it would be a use-after-free. The process-wide "active" flag then
// stays set, so no new session can start in this process.
// Returns true if the session was freed, false if it was kept (see above) or was not found.
JNIEXPORT jboolean JNICALL Java_io_nekohasekai_sagernet_ovpn3_OpenVpn3Native_nativeDestroy(JNIEnv *env, jobject thiz,
                                                                                           jlong handle)
{
    (void)thiz;
    OvpnSession *session = fromHandle(handle);
    if (session == nullptr)
    {
        return JNI_FALSE;
    }
    if (session->isConnectThread())
    {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeDestroy called from connect thread; ignored");
        return JNI_FALSE;
    }
    if (!session->stopAndWait(kDestroyTimeoutMs))
    {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "OpenVPN core did not stop within timeout; session kept to avoid use-after-free");
        return JNI_FALSE;
    }
    env->DeleteGlobalRef(session->listener());
    delete session;

    std::lock_guard<std::mutex> lock(g_registry_mu);
    g_session_active = false;
    return JNI_TRUE;
}

} // extern "C"
