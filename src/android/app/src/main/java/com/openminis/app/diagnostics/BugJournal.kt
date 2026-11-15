package com.openminis.app.diagnostics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Small, always-on diagnostic breadcrumbs; no prompts, URLs, keys or exception messages. */
object BugJournal {
    private val installed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "BugJournal").apply { isDaemon = true }
    }
    @Volatile private var sink: BugJournalFile? = null

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        sink = BugJournalFile(File(application.filesDir, "logs"))
        event("process.start", mapOf("api" to Build.VERSION.SDK_INT.toString(),
            "version" to com.openminis.app.BuildConfig.VERSION_NAME))
        // Preserve ACRA's handler; this only adds a bounded, message-free breadcrumb.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                sink?.append(BugJournalFile.encode("crash.uncaught", emptyMap(), error))
            }
            previous.uncaughtException(thread, error)
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = record(activity, "created")
            override fun onActivityStarted(activity: Activity) = record(activity, "started")
            override fun onActivityResumed(activity: Activity) = record(activity, "resumed")
            override fun onActivityPaused(activity: Activity) = record(activity, "paused")
            override fun onActivityStopped(activity: Activity) = record(activity, "stopped")
            override fun onActivityDestroyed(activity: Activity) = record(activity, "destroyed")
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
    }

    private fun record(activity: Activity, phase: String) {
        if (!activity.javaClass.name.startsWith("com.openminis.app.")) return
        val power = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display?.displayId
            else activity.windowManager.defaultDisplay.displayId
        event("activity.$phase", mapOf(
            "screen" to activity.javaClass.simpleName,
            "display" to (display?.toString() ?: "unknown"),
            "interactive" to (power?.isInteractive?.toString() ?: "unknown"),
        ))
    }

    fun event(name: String, fields: Map<String, String> = emptyMap(), error: Throwable? = null) {
        val file = sink ?: return
        val line = BugJournalFile.encode(name, fields, error)
        runCatching { writer.execute { runCatching { file.append(line) } } }
    }

    /** Route templates only: never record session ids, callback queries or tokens. */
    fun routeName(route: String?): String {
        val raw = route.orEmpty().trim()
        // URLs and absolute paths are not routes: their first segment ("https")
        // would otherwise pass the identifier check and read as a page name.
        if (raw.isEmpty() || "://" in raw || raw.startsWith("/")) return "unknown"
        val first = raw.substringBefore('?').substringBefore('/').substringBefore(':')
        return first.takeIf { it.matches(Regex("[a-zA-Z][a-zA-Z0-9_-]{0,63}")) } ?: "unknown"
    }

    /**
     * Human page title for page-level events (MibotScaffold passes a UI title,
     * which is Chinese/English prose, not a route id). Keeps letters and numbers
     * of any script, drops control characters that could break the log format,
     * and never carries more than 80 characters.
     */
    fun screenLabel(text: String?): String {
        val cleaned = text.orEmpty().replace(Regex("\\p{Cntrl}"), " ").trim()
        return cleaned.take(80).ifBlank { "unknown" }
    }

    /** Wait only off the main thread, e.g. immediately before export/read. */
    internal fun flush() { runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) } }
}

/** Pure JVM sink, shared with regression tests (rotation and secret exclusion). */
internal class BugJournalFile(private val directory: File, private val maxBytes: Long = 256 * 1024L) {
    @Synchronized fun append(line: String) {
        directory.mkdirs()
        val current = File(directory, "bug-events.log")
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (current.exists() && current.length() + bytes.size > maxBytes) {
            val previous = File(directory, "bug-events-previous.log")
            if (previous.exists() && !previous.delete()) return
            if (!current.renameTo(previous)) return
        }
        java.io.FileOutputStream(current, true).use { it.write(bytes) }
    }

    companion object {
        private val allowedKeys = setOf("api", "screen", "display", "interactive", "from", "to",
            "phase", "lifecycle", "width", "height", "visible", "port", "preferredPort",
            "models", "providers", "accounts", "version", "reason", "outcome")
        // Keep any script (page titles are Chinese) but drop separators that
        // would let a value forge fields or break the one-line record.
        private fun safe(value: String): String =
            value.replace(Regex("[^\\p{L}\\p{N}_.:-]"), "_").take(80)
        fun encode(name: String, fields: Map<String, String>, error: Throwable?, now: Long = System.currentTimeMillis()): String = buildString {
            append(now).append(' ').append(safe(name))
            fields.filterKeys { it in allowedKeys }.toSortedMap().forEach { (key, value) ->
                append(' ').append(key).append('=').append(safe(value))
            }
            if (error != null) {
                // Throwable.message can embed credentials/URLs. Record only class + project frames.
                append(" error=").append(safe(error.javaClass.name))
                error.stackTrace.filter { it.className.startsWith("com.openminis.app.") }
                    .take(6).forEach { frame ->
                        append(" at=").append(safe(frame.className)).append(':')
                            .append(safe(frame.methodName)).append(':').append(frame.lineNumber)
                    }
            }
        }
    }
}
