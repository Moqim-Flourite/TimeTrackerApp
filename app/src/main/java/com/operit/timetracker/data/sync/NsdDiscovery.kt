package com.operit.timetracker.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * mDNS 服务发现
 *
 * 通过 Android NsdManager 发现局域网内注册的
 * `_timetracker._tcp` 服务，获取电脑端的 IP 和端口。
 *
 * 发现逻辑：
 * 1. mDNS 发现 → 成功 → 缓存地址
 * 2. mDNS 超时/失败 → 使用缓存地址
 * 3. 缓存也没有 → 用户手动配置
 */
class NsdDiscovery(context: Context) {

    companion object {
        private const val TAG = "NsdDiscovery"
        private const val SERVICE_TYPE = "_timetracker._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * 发现服务，返回找到的主机和端口，超时返回 null
     */
    suspend fun discover(): Pair<String, Int>? {
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var discoveryListener: NsdManager.DiscoveryListener? = null
                var resolved = false

                discoveryListener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {
                        Log.d(TAG, "mDNS 发现开始: $serviceType")
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "发现服务: ${serviceInfo.serviceName}")
                        if (!resolved) {
                            nsdManager.resolveService(
                                serviceInfo,
                                object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(
                                        sInfo: NsdServiceInfo,
                                        errorCode: Int
                                    ) {
                                        Log.w(TAG, "解析服务失败: $errorCode")
                                    }

                                    override fun onServiceResolved(sInfo: NsdServiceInfo) {
                                        if (resolved) return
                                        resolved = true
                                        val host = sInfo.host?.hostAddress ?: return
                                        val port = sInfo.port
                                        Log.i(TAG, "服务解析成功: $host:$port")

                                        // 停止发现
                                        try {
                                            nsdManager.stopServiceDiscovery(discoveryListener)
                                        } catch (_: Exception) {
                                        }

                                        if (continuation.isActive) {
                                            continuation.resume(host to port)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "服务丢失: ${serviceInfo.serviceName}")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        Log.d(TAG, "mDNS 发现停止")
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "启动发现失败: $errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "停止发现失败: $errorCode")
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        nsdManager.stopServiceDiscovery(discoveryListener)
                    } catch (_: Exception) {
                    }
                }

                nsdManager.discoverServices(
                    SERVICE_TYPE,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener
                )
            }
        }
    }

    /**
     * 尝试连接到服务器（健康检查）
     * 返回 true 表示服务器可达
     */
    suspend fun checkHealth(host: String, port: Int): Boolean {
        return try {
            val url = "http://$host:$port/health"
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.d(TAG, "健康检查失败: ${e.message}")
            false
        }
    }
}
