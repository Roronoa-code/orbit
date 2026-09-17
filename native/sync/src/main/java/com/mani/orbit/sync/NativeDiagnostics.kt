package com.mani.orbit.sync

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort diagnostics are separate from the durable health journal and its export. */
object NativeDiagnostics {
    val trace = TraceLedger()
    private val writer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "orbit-diagnostics").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    @Volatile private var context: Context? = null
    @Volatile private var build = JSONObject().put("product", "orbit-native").put("source", "unavailable")

    fun install(app: Application) {
        if (context != null) return
        context = app
        writer.execute {
            try {
                val embedded = app.assets.open("orbit-native-build.json").bufferedReader().use { JSONObject(it.readText()) }
                require(embedded.getInt("schema") == 1 && embedded.getString("product") == "orbit-native")
                val source = embedded.getString("source").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                build = JSONObject().put("product", "orbit-native").put("source", source).put("versionCode", info.longVersionCode)
                    .put("debuggable", app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                retain(directory(app), System.currentTimeMillis())
            } catch (_: Exception) { Log.w("OrbitDiagnostics", "Build identity unavailable") }
        }
    }
    fun changed() {
        if (context == null || !pending.compareAndSet(false, true)) return
        writer.schedule({
            pending.set(false)
            val app = context ?: return@schedule
            try { write(app) } catch (_: Exception) { Log.w("OrbitDiagnostics", "Local trace write failed") }
        }, 2, TimeUnit.SECONDS)
    }
    fun begin(feature: TraceFeature, route: TraceRoute, stage: TraceStage = TraceStage.INPUT_ACCEPTED) =
        trace.begin(feature, route, stage).also { changed() }
    fun mark(op: Long, stage: TraceStage) { if (trace.mark(op, stage)) changed() }

    private fun write(app: Context) {
        val dir = directory(app)
        retain(dir, System.currentTimeMillis())
        val config = app.resources.configuration
        val data = trace.snapshot().put("build", build).put("configuration", JSONObject()
            .put("api", Build.VERSION.SDK_INT).put("round", config.isScreenRound)
            .put("width", if (config.screenWidthDp < 300) "watch" else if (config.screenWidthDp < 600) "compact" else "expanded")
            .put("text", if (config.fontScale > 1.3f) "large" else "standard"))
        val bytes = data.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val file = AtomicFile(File(dir, "trace.json"))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun directory(context: Context) = File(context.cacheDir, "diagnostics").apply { check(isDirectory || mkdirs()) }

    /** One file (plus AtomicFile's temporary recovery files), seven-day expiry on next access. */
    internal fun retain(directory: File, now: Long) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name !in setOf("trace.json", "trace.json.new", "trace.json.bak") ||
                    file.length() > MAX_BYTES || now < file.lastModified() || now - file.lastModified() > RETENTION_MS)) {
                check(file.delete())
            }
        }
    }
    const val MAX_BYTES = 128 * 1024
    const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
}
