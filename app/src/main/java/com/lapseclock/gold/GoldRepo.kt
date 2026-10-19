package com.lapseclock.gold

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 工行积存金现价：抓「积存金行情查询」页，解析实时主动积存价格。
 * 页面为服务端直出 HTML（GBK），无需登录。
 */
object GoldRepo {

    private const val PAGE_URL =
        "https://mybank.icbc.com.cn/icbc/newperbank/perbank3/gold/goldaccrual_query_out.jsp"
    private val PRICE_REGEX = Regex("""activeprice_080020000521">([\d.]+)""")

    /** 返回价格文本（元/克），如 "900.21" */
    suspend fun fetchPrice(): String = withContext(Dispatchers.IO) {
        val conn = URL(PAGE_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            )
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw IOException("HTTP $code")
            val raw = if (conn.contentEncoding == "gzip") GZIPInputStream(stream) else stream
            val bytes = raw.use { it.readBytes() }
            if (code !in 200..299) {
                throw IOException("HTTP $code: ${String(bytes, Charsets.UTF_8).take(120)}")
            }
            val html = String(bytes, charset("GBK"))
            val match = PRICE_REGEX.find(html) ?: throw IOException("页面中未找到积存金价格")
            match.groupValues[1]
        } finally {
            conn.disconnect()
        }
    }
}
