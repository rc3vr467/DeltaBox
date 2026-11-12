package io.nekohasekai.sagernet.ovpn3

// JNI declarations for the OpenVPN 3 engine. Implemented in
// app/src/main/cpp/openvpn3/ovpn3_jni.cpp. Use OpenVpn3Engine, which checks
// isLibraryLoaded first; calling these methods without the library loaded throws
// UnsatisfiedLinkError.
class OpenVpn3Native {

    // Returns a native handle, or 0 if setup failed or another session is active.
    external fun nativeCreate(listener: OpenVpn3Listener): Long

    // Returns null on success, otherwise an error message suitable for the user.
    external fun nativeStart(
        handle: Long,
        content: String,
        protoOverride: String?,
        username: String?,
        password: String?,
    ): String?

    // Requests disconnect. Safe from any thread, does not block.
    external fun nativeStop(handle: Long)

    // Stops the session, waits up to 10 s for the core to finish and frees native memory.
    // Returns false if the session was not freed (core did not stop in time, or called from
    // a listener callback); the native memory is then kept on purpose. Blocks, so call it
    // from a background thread.
    external fun nativeDestroy(handle: Long): Boolean

    companion object {
        const val LIBRARY_NAME = "ovpn3_jni"

        // Loaded on first use, never at app start. False when the library is not packaged
        // (default builds, or the native build was disabled) or cannot be loaded.
        val isLibraryLoaded: Boolean by lazy {
            try {
                System.loadLibrary(LIBRARY_NAME)
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }
    }
}
