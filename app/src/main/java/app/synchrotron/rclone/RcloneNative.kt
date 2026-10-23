package app.synchrotron.rclone

import app.synchrotron.rclone.bridge.Bridge

/** The only file that touches the gomobile-generated classes. */
internal object RcloneNative {
    fun initialize(home: String, tmp: String) = Bridge.initialize(home, tmp)

    /** Blocking. Returns (HTTP-like status, JSON output). */
    fun rpc(method: String, input: String): Pair<Int, String> {
        val r = Bridge.rpc(method, input)
        return r.status.toInt() to r.output
    }
}
