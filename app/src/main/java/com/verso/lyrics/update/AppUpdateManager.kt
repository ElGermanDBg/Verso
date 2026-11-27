package com.verso.lyrics.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.verso.lyrics.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GithubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val title: String,
    val notes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

/**
 * Gestor de actualizaciones OTA mediante GitHub Releases para Verso.
 */
object AppUpdateManager {

    const val REPO_OWNER = "ElGermanDBg"
    const val REPO_NAME = "Verso"
    const val REPO_API_LATEST =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 25_000

    fun cleanVersion(raw: String): String {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        return trimmed.substringBefore('-').substringBefore('+').trim()
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val (remoteCore, remotePre) = splitSemver(remoteVersion)
        val (currentCore, currentPre) = splitSemver(currentVersion)

        val maxLen = maxOf(remoteCore.size, currentCore.size)
        for (i in 0 until maxLen) {
            val r = remoteCore.getOrElse(i) { 0 }
            val c = currentCore.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return comparePrerelease(remotePre, currentPre) > 0
    }

    private fun splitSemver(raw: String): Pair<List<Int>, String?> {
        val noBuild = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+').trim()
        val dashIdx = noBuild.indexOf('-')
        val coreStr = if (dashIdx >= 0) noBuild.substring(0, dashIdx) else noBuild
        val preStr = if (dashIdx >= 0) noBuild.substring(dashIdx + 1).trim().ifEmpty { null } else null
        val core = coreStr.split('.').map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return core to preStr
    }

    private fun comparePrerelease(remotePre: String?, currentPre: String?): Int {
        if (remotePre == null && currentPre == null) return 0
        if (remotePre == null) return 1
        if (currentPre == null) return -1

        val rParts = remotePre.split('.')
        val cParts = currentPre.split('.')
        val count = maxOf(rParts.size, cParts.size)
        for (i in 0 until count) {
            val rp = rParts.getOrNull(i) ?: return -1
            val cp = cParts.getOrNull(i) ?: return 1
            val rNum = rp.toIntOrNull()
            val cNum = cp.toIntOrNull()
            val cmp = when {
                rNum != null && cNum != null -> rNum.compareTo(cNum)
                rNum != null -> -1
                cNum != null -> 1
                else -> rp.compareTo(cp, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun parseReleaseJson(
        jsonString: String,
        currentVersion: String
    ): GithubReleaseInfo? = runCatching {
        val root = JSONObject(jsonString)
        val tagName = root.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null

        val assets = root.optJSONArray("assets") ?: return null
        var selectedName: String? = null
        var selectedUrl: String? = null
        var selectedSize = 0L

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "").trim()
            val url = asset.optString("browser_download_url", "").trim()
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://", ignoreCase = true)) {
                selectedName = name
                selectedUrl = url
                selectedSize = asset.optLong("size", 0L)
                break
            }
        }

        val apkUrl = selectedUrl ?: return null
        val apkName = selectedName ?: return null
        val cleanedRemote = cleanVersion(tagName)
        val title = root.optString("name", "").ifBlank { "Verso $tagName" }
        val notes = root.optString("body", "").trim()

        GithubReleaseInfo(
            tagName = tagName,
            versionName = cleanedRemote,
            title = title,
            notes = notes,
            apkDownloadUrl = apkUrl,
            apkFileName = apkName,
            apkSizeBytes = selectedSize,
            isNewer = isNewerVersion(cleanedRemote, currentVersion)
        )
    }.getOrNull()

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): Result<GithubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(REPO_API_LATEST).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Verso-OTA/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code == 404) {
                    throw IllegalStateException("Aún no hay versiones publicadas en GitHub.")
                }
                if (code !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    throw IllegalStateException("HTTP $code ${errText.take(120)}".trim())
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseReleaseJson(body, currentVersion)
                    ?: throw IllegalStateException("No se encontró un paquete APK válido en la release.")
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        release: GithubReleaseInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!release.apkDownloadUrl.startsWith("https://", ignoreCase = true)) {
                throw SecurityException("Solo se permiten URLs HTTPS para actualizaciones.")
            }

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeVersion = cleanVersion(release.versionName).ifBlank { "latest" }
            val targetFile = File(updatesDir, "Verso-$safeVersion.apk")
            val tempFile = File(updatesDir, "Verso-$safeVersion.apk.tmp")

            updatesDir.listFiles()?.forEach { old ->
                if (old.isFile && old.name != targetFile.name) old.delete()
            }

            if (release.apkSizeBytes > 0L && targetFile.isFile && targetFile.length() == release.apkSizeBytes) {
                verifyPackageName(context, targetFile)
                onProgress(1f)
                return@runCatching targetFile
            }

            if (targetFile.exists()) targetFile.delete()
            if (tempFile.exists()) tempFile.delete()

            val conn = openFollowingHttpsRedirects(release.apkDownloadUrl)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Error al descargar APK: HTTP $code")
                }
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                val buffer = ByteArray(32 * 1024)
                var downloaded = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tempFile.length() <= 0L) {
                tempFile.delete()
                throw IllegalStateException("El archivo APK descargado está vacío.")
            }

            verifyPackageName(context, tempFile)

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(1f)
            targetFile
        }
    }

    private fun openFollowingHttpsRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "Verso-OTA/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location").orEmpty()
                conn.disconnect()
                if (!location.startsWith("https://", ignoreCase = true)) {
                    throw SecurityException("Redirección OTA no HTTPS bloqueada.")
                }
                currentUrl = location
            } else {
                return conn
            }
        }
        throw IllegalStateException("Demasiadas redirecciones al descargar la actualización.")
    }

    private fun verifyPackageName(context: Context, apkFile: File) {
        val pm = context.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: throw SecurityException("El APK descargado está corrupto o es ilegible.")

        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("Identificador de paquete inesperado: ${archiveInfo.packageName}")
        }
    }

    fun promptInstallApk(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permIntent)
            return false
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }
}
