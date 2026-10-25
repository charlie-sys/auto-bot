package com.autobot.reader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ApiException(val status: Int, message: String) : java.io.IOException(message)

object AccountApi {
    fun server(value: String): String {
        val address = value.trim().trimEnd('/')
        val parsed = runCatching { URI(address) }.getOrNull()
        require(parsed?.scheme in listOf("http", "https") && !parsed?.host.isNullOrBlank() && parsed?.userInfo == null && parsed?.query == null && parsed?.fragment == null) { "请填写有效的后端 HTTP 或 HTTPS 地址" }
        return address
    }

    fun request(base: String, path: String, method: String = "GET", body: JSONObject? = null, token: String = ""): JSONObject {
        val connection = (URL(server(base) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 12000; readTimeout = 150000; instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            if (method in listOf("POST", "PUT", "PATCH")) {
                val bytes = (body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val value = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) throw ApiException(status, value?.optString("detail")?.takeIf { it.isNotBlank() } ?: "服务请求失败 HTTP $status")
            return value ?: throw ApiException(502, "服务没有返回有效数据")
        } finally { connection.disconnect() }
    }
}
