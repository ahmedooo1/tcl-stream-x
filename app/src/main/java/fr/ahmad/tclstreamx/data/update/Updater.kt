package fr.ahmad.tclstreamx.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import fr.ahmad.tclstreamx.BuildConfig
import fr.ahmad.tclstreamx.data.xtream.JsonStream
import fr.ahmad.tclstreamx.data.xtream.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int, val url: String, val size: Long) : UpdateState
    data class Downloading(val build: Int, val percent: Int) : UpdateState
    data class Ready(val build: Int) : UpdateState
    data class Failed(val reason: Reason) : UpdateState

    enum class Reason { NETWORK, SIGNATURE, NO_PERMISSION }
}

/**
 * Updates the app from the project's own releases on GitHub. CI marks a release "latest" only when
 * all its tests passed. The package is downloaded over HTTPS, then checked before the system
 * installer opens: same package name, newer build and the same signing certificate as the app
 * already installed. The user confirms the installation in the system dialog.
 */
class Updater(private val context: Context, http: OkHttpClient) {
    private val client = http.newBuilder().cache(null).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = try {
            val req = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                @Suppress("UNCHECKED_CAST")
                val root = JsonStream(InputStreamReader(resp.body!!.byteStream())).readRoot() as? Map<String, Any?>
                    ?: throw IOException("format")
                val build = root.text("tag_name")?.removePrefix("build-")?.toIntOrNull() ?: throw IOException("tag")
                @Suppress("UNCHECKED_CAST")
                val apk = (root["assets"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { it.text("name") == ASSET }
                    ?: throw IOException("asset")
                val url = apk.text("browser_download_url")?.takeIf { it.startsWith("https://github.com/$REPO/") }
                    ?: throw IOException("url")
                if (build > currentBuild) UpdateState.Available(build, url, apk.text("size")?.toLongOrNull() ?: 0) else UpdateState.UpToDate
            }
        } catch (e: IOException) {
            UpdateState.Failed(UpdateState.Reason.NETWORK)
        }
        _state.value = result
        result
    }

    /** Downloads the package, verifies it, then opens the system installer. */
    suspend fun downloadAndInstall(update: UpdateState.Available) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "StreamX-${update.build}.apk")
            try {
                client.newCall(Request.Builder().url(update.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty")
                    val total = body.contentLength().takeIf { it > 0 } ?: update.size
                    body.byteStream().use { input ->
                        target.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (read > MAX_SIZE) throw IOException("too large")
                                if (total > 0) _state.value = UpdateState.Downloading(update.build, (read * 100 / total).toInt())
                            }
                        }
                    }
                }
                target
            } catch (e: IOException) {
                target.delete()
                null
            }
        }
        if (file == null) {
            _state.value = UpdateState.Failed(UpdateState.Reason.NETWORK)
            return
        }
        if (!isSameAppNewer(file, update.build)) {
            file.delete()
            _state.value = UpdateState.Failed(UpdateState.Reason.SIGNATURE)
            return
        }
        _state.value = UpdateState.Ready(update.build)
        install(file)
    }

    fun install(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            // First time: the user allows this app to install its own updates, then presses again.
            _state.value = UpdateState.Failed(UpdateState.Reason.NO_PERMISSION)
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Same package, newer build, same signing certificate as the installed app. */
    @Suppress("DEPRECATION")
    private fun isSameAppNewer(file: File, build: Int): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveCode != build.toLong() || archiveCode <= currentBuild) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        val mine = certificates(installed)
        val theirs = certificates(archive)
        if (mine.isEmpty() || theirs.isEmpty()) return false
        if (mine == theirs) return true
        // Signing key rotation (APK v3): the new build proves it comes from the installed key.
        return Build.VERSION.SDK_INT >= 28 && mine.all { it in history(archive) }
    }

    /** Every key in the APK's proof of rotation, the current one included (API 28+). */
    private fun history(info: android.content.pm.PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < 28) return emptySet()
        val si = info.signingInfo ?: return emptySet()
        if (si.hasMultipleSigners()) return emptySet()
        return si.signingCertificateHistory.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        private const val REPO = "ahmedooo1/tcl-stream-x"
        private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
        private const val ASSET = "StreamX.apk"
        private const val MAX_SIZE = 200L * 1024 * 1024
    }
}
