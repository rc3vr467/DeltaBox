package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private fun ts(): String = timeFormat.get()!!.format(Date())

    // level int use logrus.go

    /** A visible section header, used to separate connection sessions in the log. */
    fun section(title: String) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${ts()} ======== $title ========")
    }

    /** A connection step in the timeline. */
    fun step(message: String) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${ts()} $message")
    }

    /** A connection step with the elapsed time (ms) since the session started. */
    fun step(message: String, elapsedMs: Long) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${ts()} $message (+${elapsedMs}ms)")
    }

    fun d(message: String) {
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] ${ts()} $message")
    }

    fun d(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Debug] [${mkTag()}] ${ts()} $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${ts()} $message")
    }

    fun i(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Info] [${mkTag()}] ${ts()} $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] ${ts()} $message")
    }

    fun w(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] ${ts()} $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        Libcore.nekoLogPrintln("[Warning] [${mkTag()}] ${ts()} " + exception.stackTraceToString())
    }

    fun e(message: String) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] ${ts()} $message")
    }

    fun e(message: String, exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] ${ts()} $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        Libcore.nekoLogPrintln("[Error] [${mkTag()}] ${ts()} " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}