package com.operit.timetracker.data.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * 同步配置存储
 *
 * 存储服务器地址、端口、共享 token、启用状态等。
 * 配置项持久化在 SharedPreferences 中。
 */
class SyncConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 同步功能是否启用 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 服务器 IP 或主机名（手动配置 / mDNS 发现后自动写入） */
    var serverHost: String
        get() = prefs.getString(KEY_SERVER_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_HOST, value).apply()

    /** 服务器端口 */
    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 8080)
        set(value) = prefs.edit().putInt(KEY_SERVER_PORT, value).apply()

    /** 共享 token（电脑端启动时生成，首次配对时输入） */
    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    /** 上次成功连接的地址（自动缓存，用于快速重连） */
    var lastConnectedHost: String
        get() = prefs.getString(KEY_LAST_CONNECTED_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_CONNECTED_HOST, value).apply()

    /** 上次成功连接的端口 */
    var lastConnectedPort: Int
        get() = prefs.getInt(KEY_LAST_CONNECTED_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_CONNECTED_PORT, value).apply()

    /** 是否已完成配对（token 已设置） */
    val isPaired: Boolean
        get() = authToken.isNotBlank()

    /** 构建当前服务器 URL */
    fun getServerUrl(): String {
        val host = serverHost.ifBlank { lastConnectedHost }
        val port = if (serverHost.isNotBlank()) serverPort else lastConnectedPort
        if (host.isBlank()) return ""
        return "http://$host:$port"
    }

    /** 保存连接成功的地址到缓存 */
    fun cacheConnectedAddress(host: String, port: Int) {
        lastConnectedHost = host
        lastConnectedPort = port
    }

    /** 清除所有配置 */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_ENABLED = "sync_enabled"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_LAST_CONNECTED_HOST = "last_connected_host"
        private const val KEY_LAST_CONNECTED_PORT = "last_connected_port"
    }
}
