package com.sahidcode404.camera.core.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.sahidcode404.camera.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class DevelopmentOtaClient(
    private val status: (String) -> Unit,
) {
    private data class Manifest(
        val versionCode: Long,
        val versionName: String,
        val packageName: String,
        val sha256: String,
        val signerSha256: String,
        val assetName: String,
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DevelopmentOta") }
    @Volatile private var started = false
    @Volatile private var readyApk: File? = null

    fun checkAfterFirstFrame(activity: Activity) {
        if (started) return
        started = true
        executor.execute {
            try {
                val manifest = fetchManifest()
                if (manifest.versionCode <= BuildConfig.VERSION_CODE.toLong()) return@execute
                if (manifest.packageName != activity.packageName) throw IllegalStateException("OTA package mismatch")
                if (!manifest.signerSha256.equals(DEV_SIGNER_SHA256, ignoreCase = true)) throw IllegalStateException("OTA signer metadata mismatch")
                activity.runOnUiThread { status("Development update ${manifest.versionName} downloading") }
                val file = downloadAndVerify(activity, manifest)
                readyApk = file
                activity.runOnUiThread { installReadyIfPermitted(activity) }
            } catch (t: Throwable) {
                activity.runOnUiThread { status("OTA check skipped: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    fun installReadyIfPermitted(activity: Activity) {
        val apk = readyApk ?: return
        if (!apk.isFile) return
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            status("Allow Camera to install development updates")
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        status("Development update ready")
        activity.startActivity(intent)
    }

    private fun fetchManifest(): Manifest {
        val json = JSONObject(httpGetText(MANIFEST_URL))
        check(json.getInt("schema") == 1)
        check(json.getString("channel") == "development")
        return Manifest(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            packageName = json.getString("packageName"),
            sha256 = json.getString("sha256"),
            signerSha256 = json.getString("signerSha256"),
            assetName = json.getString("assetName"),
        )
    }

    private fun downloadAndVerify(activity: Activity, manifest: Manifest): File {
        check(manifest.assetName == "Camera-dev.apk")
        val root = File(activity.cacheDir, "updates")
        val verified = File(root, "verified")
        check(verified.exists() || verified.mkdirs())
        val part = File(root, "Camera-dev.apk.part")
        val target = File(verified, "Camera-dev.apk")
        if (part.exists()) part.delete()

        val connection = URL("$APK_URL?version=${manifest.versionCode}").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.connect()
        check(connection.responseCode in 200..299) { "APK HTTP ${connection.responseCode}" }
        connection.inputStream.use { input -> FileOutputStream(part).use { output -> input.copyTo(output, 128 * 1024) } }
        connection.disconnect()

        check(sha256(part).equals(manifest.sha256, ignoreCase = true)) { "APK hash mismatch" }
        val archive = inspectArchive(activity, part)
        check(archive.first == manifest.packageName) { "APK application ID mismatch" }
        check(archive.second == manifest.versionCode) { "APK version mismatch" }
        val installedSigner = installedSigner(activity)
        val candidateSigner = archive.third
        check(candidateSigner.equals(manifest.signerSha256, ignoreCase = true)) { "APK signer mismatch" }
        check(candidateSigner.equals(installedSigner, ignoreCase = true)) { "Installed signer continuity mismatch" }

        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Cannot promote verified APK" }
        return target
    }

    private fun inspectArchive(activity: Activity, file: File): Triple<String, Long, String> {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = activity.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("Cannot inspect downloaded APK")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return Triple(info.packageName, version, packageSignerSha256(info))
    }

    private fun installedSigner(activity: Activity): String {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val info = activity.packageManager.getPackageInfo(activity.packageName, flags)
        return packageSignerSha256(info)
    }

    private fun packageSignerSha256(info: android.content.pm.PackageInfo): String {
        val signature = if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = info.signingInfo ?: throw IllegalStateException("No signing info")
            val signatures = if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
            signatures.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        } ?: throw IllegalStateException("No APK signature")
        return MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGetText(url: String): String {
        val connection = URL("$url?nonce=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.connect()
        check(connection.responseCode in 200..299) { "Manifest HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    companion object {
        private const val MANIFEST_URL = "https://github.com/sahid-code404/ex1/releases/download/dev-latest/dev-manifest.json"
        private const val APK_URL = "https://github.com/sahid-code404/ex1/releases/download/dev-latest/Camera-dev.apk"
        private const val DEV_SIGNER_SHA256 = "9dde8fe35506ba993a5b8ffba8f01ff46d35c86419ba8fd5029d187b3f6fbd8c"
    }
}
