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
import org.json.JSONArray
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
        // TODO: 替换为你的 GitHub 仓库
        const val GITHUB_OWNER = "Moqim-Flourite"
        const val GITHUB_REPO = "TimeTrackerApp"
        private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    data class UpdateInfo(
        val version: String,          // tag_name, e.g. "v1.0.0"
        val releaseName: String,      // release title
        val releaseBody: String,      // release description / changelog
        val apkDownloadUrl: String,   // direct download URL for .apk asset
        val apkFileName: String,      // file name of the .apk
        val publishedAt: String       // publish time
    )

    /**
     * 获取当前 App 版本名
     */
    fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    /**
     * 比较版本号: v1.2.3 vs v1.2.4
     * @return 正数=remote更新, 0=相同, 负数=local更新(不正常)
     */
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
     * @return UpdateInfo 如果有更新，null 如果已是最新
     */
    suspend fun checkForUpdate(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "检查更新: $API_URL")
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "TimeTracker-Android")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorStream = conn.errorStream?.bufferedReader()?.readText() ?: ""
                Log.e(TAG, "GitHub API 返回 $responseCode: $errorStream")
                return@withContext Result.failure(Exception("GitHub API 返回 $responseCode"))
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            conn.disconnect()

            val json = org.json.JSONObject(body)
            val tagName = json.getString("tag_name")
            val releaseName = json.getString("name") ?: tagName
            val releaseBody = json.getString("body") ?: ""
            val publishedAt = json.getString("published_at") ?: ""

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
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
            if (!dir.exists()) dir.mkdirs()
            val apkFile = File(dir, updateInfo.apkFileName)

            // 如果已下载过同版本，直接返回
            if (apkFile.exists() && apkFile.length() > 100_000) {
                Log.i(TAG, "APK 已存在: ${apkFile.absolutePath}")
                return@withContext Result.success(apkFile)
            }

            Log.i(TAG, "开始下载: ${updateInfo.apkDownloadUrl}")
            val url = URL(updateInfo.apkDownloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TimeTracker-Android")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            // 处理 302 重定向（GitHub 下载链接通常会重定向）
            if (responseCode in 301..308) {
                val redirectUrl = conn.getHeaderField("Location")
                conn.disconnect()
                if (redirectUrl != null) {
                    val redirectConn = URL(redirectUrl).openConnection() as HttpURLConnection
                    redirectConn.requestMethod = "GET"
                    redirectConn.connectTimeout = 15000
                    redirectConn.readTimeout = 60000
                    return@withContext downloadFromConnection(redirectConn, apkFile, onProgress)
                }
            }

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
