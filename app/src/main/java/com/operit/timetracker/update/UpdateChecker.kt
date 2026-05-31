package com.operit.timetracker.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release 更新检查器
 *
 * 工作原理：
 * 1. 请求 GitHub API 获取最新 Release
 * 2. 比较 tag_name（版本号）与当前 App 版本
 * 3. 如果有新版本，下载 APK 并触发安装
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        const val GITHUB_OWNER = "Moqim-Flourite"
        const val GITHUB_REPO = "TimeTrackerApp"
        private const val API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        // 国内镜像备选（如果 GitHub API 超时）
        private const val API_URL_MIRROR =
            "https://ghproxy.com/https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val MAX_REDIRECTS = 5
    }

    data class UpdateInfo(
        val version: String,
        val releaseName: String,
        val releaseBody: String,
        val apkDownloadUrl: String,
        val apkFileName: String,
        val publishedAt: String
    )

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    fun compareVersions(local: String, remote: String): Int {
        val cleanLocal = local.removePrefix("v").removePrefix("V")
        val cleanRemote = remote.removePrefix("v").removePrefix("V")
        val localParts = cleanLocal.split(".").map { it.toIntOrNull() ?: 0 }
        val remoteParts = cleanRemote.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(localParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val l = localParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (l != r) return r - l
        }
        return 0
    }

    /**
     * 检查是否有新版本
     */
    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "检查更新: $API_URL")
            
            // 尝试主 URL，失败则用镜像
            val body = try {
                fetchUrl(API_URL)
            } catch (e: Exception) {
                Log.w(TAG, "GitHub API 主地址失败，尝试镜像: ${e.message}")
                fetchUrl(API_URL_MIRROR)
            }

            val json = org.json.JSONObject(body)
            val tagName = json.getString("tag_name")
            val releaseName = json.optString("name", tagName)
            val releaseBody = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            // 从 assets 中找 .apk 文件
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            var apkName = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    apkName = name
                    break
                }
            }

            if (apkUrl.isEmpty()) {
                Log.w(TAG, "Release 中没有找到 APK 文件")
                return@withContext Result.failure(Exception("Release 中没有 APK 文件"))
            }

            val updateInfo = UpdateInfo(
                version = tagName,
                releaseName = releaseName,
                releaseBody = releaseBody,
                apkDownloadUrl = apkUrl,
                apkFileName = apkName,
                publishedAt = publishedAt
            )

            val currentVersion = getCurrentVersion()
            val hasUpdate = compareVersions(currentVersion, tagName) > 0
            Log.i(TAG, "当前=$currentVersion, 最新=$tagName, 有更新=$hasUpdate")

            if (hasUpdate) {
                Result.success(updateInfo)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败", e)
            Result.failure(e)
        }
    }

    /**
     * 下载 APK 到外部缓存目录
     */
    suspend fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "updates"
            )
            if (!dir.exists()) dir.mkdirs()

            // 先清理旧版本 APK（不同文件名的）
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk") && file.name != updateInfo.apkFileName) {
                    file.delete()
                    Log.i(TAG, "清理旧 APK: ${file.name}")
                }
            }

            val apkFile = File(dir, updateInfo.apkFileName)

            // 如果已下载过同名文件，用 HEAD 请求比对大小，更准确
            if (apkFile.exists() && apkFile.length() > 100_000) {
                try {
                    val headConn = openConnection(URL(updateInfo.apkDownloadUrl), "HEAD")
                    val remoteSize = headConn.contentLength.toLong()
                    headConn.disconnect()
                    
                    if (remoteSize > 0 && apkFile.length() == remoteSize) {
                        Log.i(TAG, "APK 已存在且大小匹配 (${apkFile.length()} bytes)，跳过下载")
                        return@withContext Result.success(apkFile)
                    } else {
                        Log.i(TAG, "APK 大小不匹配: 本地=${apkFile.length()}, 远程=$remoteSize，重新下载")
                        apkFile.delete()
                    }
                } catch (e: Exception) {
                    // HEAD 请求失败，保守起见直接用缓存
                    Log.w(TAG, "HEAD 请求失败，使用缓存: ${e.message}")
                    return@withContext Result.success(apkFile)
                }
            }

            Log.i(TAG, "开始下载: ${updateInfo.apkDownloadUrl}")
            val conn = openConnection(URL(updateInfo.apkDownloadUrl), "GET")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.disconnect()
                return@withContext Result.failure(Exception("下载失败: HTTP $responseCode"))
            }

            return@withContext downloadFromConnection(conn, apkFile, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "下载 APK 失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 请求 URL 并返回响应体文本
     */
    private fun fetchUrl(urlString: String): String {
        val conn = openConnection(URL(urlString), "GET")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        
        val responseCode = conn.responseCode
        if (responseCode != 200) {
            val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            throw Exception("HTTP $responseCode: $errorStream")
        }
        
        val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()
        return body
    }

    /**
     * 打开 HTTP 连接，支持多级重定向（GitHub 下载经常 302 链式跳转）
     */
    private fun openConnection(url: URL, method: String): HttpURLConnection {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val conn = currentUrl.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "TimeTracker-Android/${getCurrentVersion()}")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false // 手动处理重定向

            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location != null) {
                    currentUrl = URL(location)
                    redirectCount++
                    Log.d(TAG, "重定向 ($redirectCount): $location")
                    continue
                }
            }
            return conn
        }
        throw Exception("重定向次数过多 (${MAX_REDIRECTS})")
    }

    private fun downloadFromConnection(
        conn: HttpURLConnection,
        apkFile: File,
        onProgress: (Int) -> Unit
    ): Result<File> {
        val totalSize = conn.contentLength
        var downloaded = 0

        conn.inputStream.use { input ->
            apkFile.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (totalSize > 0) {
                        onProgress((downloaded * 100 / totalSize))
                    }
                }
            }
        }
        conn.disconnect()

        // 验证下载完整性
        if (totalSize > 0 && apkFile.length() != totalSize.toLong()) {
            apkFile.delete()
            return Result.failure(
                Exception("下载不完整: 期望 ${totalSize} 字节，实际 ${apkFile.length()} 字节")
            )
        }

        Log.i(TAG, "下载完成: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
        return Result.success(apkFile)
    }

    /**
     * 安装 APK（触发系统安装界面）
     */
    fun installApk(apkFile: File) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
            throw e
        }
    }
}
