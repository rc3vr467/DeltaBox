package io.nekohasekai.sagernet.ovpn3

// Owns at most one native OpenVPN 3 session for this process.
//
// Threading:
//   * start() and destroy() may be called from any thread. start() does not block on the
//     connection; destroy() blocks for up to 10 s, so run it on a background thread.
//   * stop() never blocks and is safe from listener callbacks (which run on the core's
//     connect thread).
//
// Ownership: the native handle is only freed in destroy(). destroy() clears the handle
// under the lock before freeing it, so a stop() that arrives later is a no-op rather than
// a use-after-free.
//
// Not implemented here (see README-STATUS.md): profile storage, Engine selection in the
// UI, credential storage, reconnect and network-change handling. The VpnService that owns
// the TUN file descriptor is supplied by the caller through the listener.
class OpenVpn3Engine(private val listener: OpenVpn3Listener) {

    private val native = OpenVpn3Native()
    private val lock = Any()

    // Guarded by lock. 0 means no native session.
    private var handle = 0L

    // Returns null when the connect attempt was started, otherwise a user-facing error.
    // The engine stays idle on error. The connect result arrives later via listener.onEvent.
    fun start(content: String, protoOverride: String?, username: String?, password: String?): String? {
        if (!OpenVpn3Native.isLibraryLoaded) {
            return "OpenVPN engine is not available in this build"
        }
        synchronized(lock) {
            if (handle != 0L) {
                return "An OpenVPN session is already running"
            }
            val created = native.nativeCreate(listener)
            if (created == 0L) {
                return "OpenVPN engine could not be created"
            }
            val error = native.nativeStart(created, content, protoOverride, username, password)
            if (error != null) {
                // Session was never started, so it can be freed directly.
                native.nativeDestroy(created)
                return error
            }
            handle = created
            return null
        }
    }

    // Requests disconnect. The listener then receives the final event.
    fun stop() {
        synchronized(lock) {
            if (handle != 0L) native.nativeStop(handle)
        }
    }

    // Stops the session and frees native memory. Blocks for up to 10 s. Returns false if
    // the core did not stop in time; the native session is then kept alive by design.
    fun destroy(): Boolean {
        val toFree: Long
        synchronized(lock) {
            toFree = handle
            handle = 0L
            if (toFree != 0L) native.nativeStop(toFree)
        }
        if (toFree == 0L) return true
        return native.nativeDestroy(toFree)
    }

    fun isRunning(): Boolean = synchronized(lock) { handle != 0L }
}
