package com.operit.timetracker.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.operit.timetracker.data.DataStore
import com.operit.timetracker.data.TimeRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 同步仓库
 *
 * 核心同步逻辑：
 * 1. 发现服务器（mDNS → 缓存地址 → 手动配置）
 * 2. 查询未同步日期列表
 * 3. 打包每日数据为 JSON
 * 4. POST 到电脑端
 * 5. 更新同步日志
 */
class SyncRepository(private val context: Context) {

    companion object {
        private const val TAG = "SyncRepository"
        private const val SYNC_LIST_ENDPOINT = "/list"
        private const val SYNC_DATA_ENDPOINT = "/sync"
        private const val HEALTH_ENDPOINT = "/health"
    }

    val config = SyncConfig(context)
    private val logStore = SyncLogStore(context)

    /** 清除同步日志（解除配对时调用） */
    fun clearSyncLog() = logStore.clearAll()
    private val nsdDiscovery = NsdDiscovery(context)
    private val dataStore = DataStore(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /**
     * 同步结果
     */
    sealed class SyncResult {
        data class Success(val syncedDates: List<String>) : SyncResult()
        data class PartialSuccess(
            val syncedDates: List<String>,
            val failedDates: List<String>,
            val reason: String
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()
        object Disabled : SyncResult()
        object NotPaired : SyncResult()
    }

    /**
     * 发现服务器地址
     *
     * 优先级：
     * 1. 缓存地址（上次成功连接的）→ 健康检查
     * 2. mDNS 发现 → 健康检查 → 缓存
     * 3. 手动配置地址 → 健康检查
     * 4. 全部失败 → null
     */
    suspend fun discoverServer(): Pair<String, Int>? {
        // 1. 尝试缓存地址
        val cachedHost = config.lastConnectedHost
        val cachedPort = config.lastConnectedPort
        if (cachedHost.isNotBlank() && cachedPort > 0) {
            if (nsdDiscovery.checkHealth(cachedHost, cachedPort)) {
                Log.i(TAG, "使用缓存地址: $cachedHost:$cachedPort")
                return cachedHost to cachedPort
            }
        }

        // 2. mDNS 发现
        val discovered = nsdDiscovery.discover()
        if (discovered != null) {
            val (host, port) = discovered
            if (nsdDiscovery.checkHealth(host, port)) {
                config.cacheConnectedAddress(host, port)
                Log.i(TAG, "mDNS 发现成功: $host:$port")
                return discovered
            }
        }

        // 3. 手动配置地址
        val manualHost = config.serverHost
        val manualPort = config.serverPort
        if (manualHost.isNotBlank()) {
            if (nsdDiscovery.checkHealth(manualHost, manualPort)) {
                config.cacheConnectedAddress(manualHost, manualPort)
                Log.i(TAG, "使用手动配置地址: $manualHost:$manualPort")
                return manualHost to manualPort
            }
        }

        Log.w(TAG, "未找到可用服务器")
        return null
    }

    /**
     * 执行完整同步流程
     *
     * @param onProgress 进度回调 (当前第几个, 总数, 当前日期)
     */
    suspend fun syncAll(
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): SyncResult = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext SyncResult.Disabled
        if (!config.isPaired) return@withContext SyncResult.NotPaired

        // 发现服务器
        val server = discoverServer()
        if (server == null) {
            return@withContext SyncResult.Error("未找到同步服务器，请检查电脑端是否已启动")
        }
        val (host, port) = server
        val baseUrl = "http://$host:$port"

        // 查找需要同步的日期
        val unsyncedDates = findUnsyncedDates()
        if (unsyncedDates.isEmpty()) {
            Log.i(TAG, "所有日期已同步，无需操作")
            return@withContext SyncResult.Success(emptyList())
        }

        Log.i(TAG, "需要同步 ${unsyncedDates.size} 个日期: $unsyncedDates")

        val syncedDates = mutableListOf<String>()
        val failedDates = mutableListOf<String>()

        for ((index, dateStr) in unsyncedDates.withIndex()) {
            onProgress?.invoke(index + 1, unsyncedDates.size, dateStr)

            try {
                val success = syncDayData(baseUrl, dateStr)
                if (success) {
                    logStore.markSynced(dateStr)
                    syncedDates.add(dateStr)
                    Log.i(TAG, "同步成功: $dateStr")
                } else {
                    failedDates.add(dateStr)
                    Log.w(TAG, "同步失败: $dateStr")
                }
            } catch (e: Exception) {
                failedDates.add(dateStr)
                Log.e(TAG, "同步异常: $dateStr", e)
            }
        }

        return@withContext when {
            failedDates.isEmpty() -> SyncResult.Success(syncedDates)
            syncedDates.isEmpty() -> SyncResult.Error("所有日期同步失败")
            else -> SyncResult.PartialSuccess(
                syncedDates,
                failedDates,
                "部分日期同步失败"
            )
        }
    }

    /**
     * 查找所有未同步的日期
     *
     * 从数据库中遍历所有记录，提取唯一的日期，
     * 过滤掉已同步的。
     */
    private fun findUnsyncedDates(): List<String> {
        val records = dataStore.loadRecords()
        val calendar = Calendar.getInstance()

        val allDates = records.map { record ->
            calendar.timeInMillis = record.startTime
            dateFormat.format(calendar.time)
        }.distinct().sorted()

        // 加上今天（即使没有记录也要同步，表示今天无数据）
        val today = dateFormat.format(System.currentTimeMillis())
        val datesWithToday = (allDates + today).distinct().sorted()

        return datesWithToday.filter { !logStore.isSynced(it) }
    }

    /**
     * 同步某天的数据（下载 → 合并 → 上传）
     *
     * 核心修复：先下载服务器数据，与本地数据合并后再上传，
     * 避免本地旧数据覆盖服务器新数据。
     */
    private fun syncDayData(baseUrl: String, dateStr: String): Boolean {
        // 1. 获取本地当天记录
        val localRecords = getDayRecords(dateStr)

        // 2. 尝试下载服务器上的当天数据
        val serverRecords = downloadDayData(baseUrl, dateStr)

        // 3. 如果服务器支持下载，合并后再上传；否则只上传本地数据
        val recordsToUpload = if (serverRecords != null) {
            Log.i(TAG, "合并模式: 本地 ${localRecords.size} 条, 服务器 ${serverRecords.size} 条")
            mergeRecords(localRecords, serverRecords)
        } else {
            Log.i(TAG, "直传模式: 本地 ${localRecords.size} 条")
            localRecords
        }

        // 4. 上传
        return uploadRecords(baseUrl, dateStr, recordsToUpload)
    }

    /**
     * 获取本地某天的记录（含跨日分摊）
     */
    private fun getDayRecords(dateStr: String): List<TimeRecord> {
        val records = dataStore.loadRecords()
        val dayStart = dateFormat.parse(dateStr)?.time ?: 0
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        return records.filter { record ->
            record.startTime < dayEnd && (record.endTime ?: System.currentTimeMillis()) > dayStart
        }.mapNotNull { record ->
            val effectiveStart = maxOf(record.startTime, dayStart)
            val effectiveEnd = minOf(record.endTime ?: System.currentTimeMillis(), dayEnd)
            val effectiveDuration = (effectiveEnd - effectiveStart) / 1000

            if (effectiveDuration <= 0) return@mapNotNull null

            record.copy(
                startTime = effectiveStart,
                endTime = effectiveEnd,
                durationSeconds = effectiveDuration
            )
        }
    }

    /**
     * 下载服务器上某天的数据
     *
     * @return 服务器记录列表；如果服务器不支持下载返回 null（降级为直传）
     */
    private fun downloadDayData(baseUrl: String, dateStr: String): List<TimeRecord>? {
        val request = Request.Builder()
            .url("$baseUrl$SYNC_DATA_ENDPOINT")
            .get()
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("X-Date", dateStr)
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                response.close()
                if (body.isNullOrBlank()) return emptyList()
                parseServerRecords(body)
            } else {
                response.close()
                Log.w(TAG, "服务器不支持下载 (HTTP ${response.code}), 降级为直传")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载服务器数据失败: $dateStr, 降级为直传", e)
            null
        }
    }

    /**
     * 解析服务器返回的记录 JSON
     */
    private fun parseServerRecords(json: String): List<TimeRecord> {
        return try {
            val obj = JSONObject(json)
            val array = obj.optJSONArray("records")
            if (array != null) return parseRecordsArray(array)

            // 兼容直接返回数组的情况
            val directArray = JSONArray(json)
            parseRecordsArray(directArray)
        } catch (e: Exception) {
            Log.w(TAG, "解析服务器记录失败", e)
            emptyList()
        }
    }

    private fun parseRecordsArray(array: JSONArray): List<TimeRecord> {
        val records = mutableListOf<TimeRecord>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            records.add(
                TimeRecord(
                    id = obj.optLong("id", 0),
                    category = obj.getString("category"),
                    startTime = obj.getLong("startTime"),
                    endTime = if (obj.isNull("endTime")) null else obj.getLong("endTime"),
                    durationSeconds = obj.getLong("durationSeconds"),
                    originalInput = obj.optString("originalInput", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return records
    }

    /**
     * 合并本地和服务器记录
     *
     * 匹配规则：startTime + category 相同视为同一条记录
     * 冲突解决：保留 createdAt 更新的记录
     * 未匹配记录：两侧独有的一律保留
     */
    private fun mergeRecords(
        localRecords: List<TimeRecord>,
        serverRecords: List<TimeRecord>
    ): List<TimeRecord> {
        val merged = mutableListOf<TimeRecord>()
        val serverMap = mutableMapOf<String, TimeRecord>()

        for (record in serverRecords) {
            val key = "${record.startTime}-${record.category}"
            serverMap[key] = record
        }

        val matchedKeys = mutableSetOf<String>()
        for (local in localRecords) {
            val key = "${local.startTime}-${local.category}"
            val server = serverMap[key]

            if (server != null) {
                matchedKeys.add(key)
                // 保留 createdAt 更新的
                if (local.createdAt >= server.createdAt) {
                    merged.add(local)
                } else {
                    merged.add(server)
                }
            } else {
                merged.add(local)
            }
        }

        // 添加服务器独有的记录
        for ((key, server) in serverMap) {
            if (key !in matchedKeys) {
                merged.add(server)
            }
        }

        // 按 startTime 排序并重新分配 ID
        return merged.sortedBy { it.startTime }.mapIndexed { index, record ->
            record.copy(id = (index + 1).toLong())
        }
    }

    /**
     * 上传记录到服务器
     */
    private fun uploadRecords(baseUrl: String, dateStr: String, records: List<TimeRecord>): Boolean {
        val dayData = packageRecords(dateStr, records)
        val jsonBody = dayData.toString(2)

        val request = Request.Builder()
            .url("$baseUrl$SYNC_DATA_ENDPOINT")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${config.authToken}")
            .addHeader("X-Date", dateStr)
            .build()

        val response = client.newCall(request).execute()
        val success = response.isSuccessful
        if (!success) {
            Log.w(TAG, "上传失败 HTTP ${response.code}: ${response.body?.string()}")
        }
        response.close()
        return success
    }

    /**
     * 打包记录为 JSON
     */
    private fun packageRecords(dateStr: String, records: List<TimeRecord>): JSONObject {
        val recordsArray = JSONArray()
        val summary = mutableMapOf<String, Long>()

        for (record in records) {
            val recordObj = JSONObject().apply {
                put("id", record.id)
                put("category", record.category)
                put("startTime", record.startTime)
                put("endTime", if (record.endTime != null) record.endTime else JSONObject.NULL)
                put("durationSeconds", record.durationSeconds)
                put("originalInput", record.originalInput)
                put("createdAt", record.createdAt)
            }
            recordsArray.put(recordObj)

            summary[record.category] = (summary[record.category] ?: 0) + record.durationSeconds
        }

        val summaryObj = JSONObject()
        for ((category, seconds) in summary) {
            summaryObj.put(category, seconds)
        }

        return JSONObject().apply {
            put("app_id", "timetracker")
            put("date", dateStr)
            put("syncedAt", System.currentTimeMillis())
            put("records", recordsArray)
            put("summary", summaryObj)
        }
    }

    /**
     * 检查是否在 WiFi 网络下
     */
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 配对（设置 token）
     */
    fun pair(token: String, host: String, port: Int) {
        config.authToken = token
        config.serverHost = host
        config.serverPort = port
        config.enabled = true
        config.cacheConnectedAddress(host, port)
    }

    /**
     * 获取同步状态摘要（用于 UI 显示）
     */
    fun getSyncStatusSummary(): String {
        if (!config.enabled) return "同步未启用"
        if (!config.isPaired) return "未配对"

        val allDates = getAllRecordDates()
        val syncedCount = allDates.count { logStore.isSynced(it) }
        val totalCount = allDates.size
        val unsyncedCount = totalCount - syncedCount

        return if (unsyncedCount == 0) {
            "全部已同步 ($totalCount 天)"
        } else {
            "已同步 $syncedCount/$totalCount 天，$unsyncedCount 天待同步"
        }
    }

    /**
     * 获取所有记录日期
     */
    private fun getAllRecordDates(): List<String> {
        val records = dataStore.loadRecords()
        val calendar = Calendar.getInstance()
        return records.map { record ->
            calendar.timeInMillis = record.startTime
            dateFormat.format(calendar.time)
        }.distinct().sorted()
    }
}
