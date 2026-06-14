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
 *
 * 注意：
 * - GitHub API 未认证限速 60 次/小时，建议配置 token
 * - ghproxy 等镜像仅用于文件下载，不适用于 API 请求
 */
class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        const val GITHUB_OWNER = "Moqim-Flourite"
        const val GITHUB_REPO = "TimeTrackerApp"
        private const val API_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
        private const val MAX_REDIRECTS = 5

        // GitHub 镜像列表，仅用于 APK 下载（API 请求不用镜像）
        private val DOWNLOAD_MIRRORS = listOf(
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://mirror.ghproxy.com/",
            "https://gh-proxy.com/",
            "https://hub.gitmirror.com/",
            "https://github.moeyy.xyz/",
        )
    }

    data class UpdateInfo(
        val version: String,
        val releaseName: String,
        val releaseBody: String,
        val apkDownloadUrl: String,
        val apkFileName: String,
        val publishedAt: String
    )

    /**
     * 检查结果：区分"无更新"和"检查失败"
     */
    sealed class CheckResult {
        /** 有新版本可用 */
        data class HasUpdate(val info: UpdateInfo) : CheckResult()
        /** 已是最新版本 */
        object UpToDate : CheckResult()
        /** 检查失败（网络错误、API 限速等） */
        data class Error(val message: String, val exception: Exception? = null) : CheckResult()
    }

    fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    /**
     * 比较两个版本号
     * @return 正数表示 remote 更新，负数表示 local 更新，0 表示相同
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
     * 检查是否有新版本（新版本，返回 CheckResult）
     */
    suspend fun checkForResult(): CheckResult = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "检查更新: $API_URL")

            val body = fetchUrlWithAuth(API_URL)
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
                return@withContext CheckResult.Error("Release 中没有 APK 文件")
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
            val comparison = compareVersions(currentVersion, tagName)
            Log.i(TAG, "当前=$currentVersion, 最新=$tagName, 比较=$comparison")

            if (comparison > 0) {
                CheckResult.HasUpdate(updateInfo)
            } else {
                CheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查更新失败", e)
            val msg = when {
                e.message?.contains("403") == true -> "API 请求次数超限，请稍后再试"
                e.message?.contains("timeout", true) == true -> "网络超时，请检查网络连接"
                e.message?.contains("resolve", true) == true -> "无法连接到 GitHub，请检查网络"
                else -> "检查更新失败: ${e.message}"
            }
            CheckResult.Error(msg, e)
        }
    }

    /**
     * 兼容旧接口
     */
    suspend fun checkForUpdate(): Result<UpdateInfo?> {
        return when (val result = checkForResult()) {
            is CheckResult.HasUpdate -> Result.success(result.info)
            is CheckResult.UpToDate -> Result.success(null)
            is CheckResult.Error -> Result.failure(result.exception ?: Exception(result.message))
        }
    }

    /**
     * 请求 URL 并返回响应体文本，自动带上 auth header 提升 API 配额
     */
    private fun fetchUrlWithAuth(urlString: String): String {
        val headers = mutableMapOf(
            "Accept" to "application/vnd.github.v3+json"
        )
        // 尝试从 SharedPreferences 读取 GitHub token
        val prefs = context.getSharedPreferences("sync_config", Context.MODE_PRIVATE)
        val token = prefs.getString("github_token", null)
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "token $token"
        }
        val conn = openConnection(URL(urlString), "GET", headers)

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
     * 下载 APK，优先用镜像加速（国内网络）
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

            // 清理旧版本 APK
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk") && file.name != updateInfo.apkFileName) {
                    file.delete()
                    Log.i(TAG, "清理旧 APK: ${file.name}")
                }
            }

            val apkFile = File(dir, updateInfo.apkFileName)

            // 如果已下载过同名文件，用 HEAD 请求比对大小
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
                    Log.w(TAG, "HEAD 请求失败，使用缓存: ${e.message}")
                    return@withContext Result.success(apkFile)
                }
            }

            // 尝试主 URL，失败则逐个尝试镜像
            val urls = mutableListOf(updateInfo.apkDownloadUrl)
            if (updateInfo.apkDownloadUrl.contains("github.com")) {
                for (mirror in DOWNLOAD_MIRRORS) {
                    urls.add(mirror + updateInfo.apkDownloadUrl)
                }
            }

            var lastException: Exception? = null
            for (url in urls) {
                try {
                    Log.i(TAG, "尝试下载: $url")
                    val conn = openConnection(URL(url), "GET")
                    val responseCode = conn.responseCode
                    if (responseCode != 200) {
                        conn.disconnect()
                        lastException = Exception("HTTP $responseCode from $url")
                        continue
                    }
                    return@withContext downloadFromConnection(conn, apkFile, onProgress)
                } catch (e: Exception) {
                    Log.w(TAG, "下载失败 $url: ${e.message}")
                    lastException = e
                }
            }

            Result.failure(lastException ?: Exception("所有下载源均失败"))
        } catch (e: Exception) {
            Log.e(TAG, "下载 APK 失败", e)
            Result.failure(e)
        }
    }

    /**
     * 打开 HTTP 连接，支持多级重定向
     */
    private fun openConnection(
        url: URL,
        method: String,
        headers: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount < MAX_REDIRECTS) {
            val conn = currentUrl.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "TimeTracker-Android/${getCurrentVersion()}")
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false

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
     * 安装 APK
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
