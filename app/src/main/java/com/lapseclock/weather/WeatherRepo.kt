package com.lapseclock.weather

import com.lapseclock.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class WeatherUi(
    val city: String,
    val text: String,
    val temp: String,
    val icon: String,
)

private data class Geo(val city: String, val lat: String, val lon: String)

class WeatherRepo {

    /** 查询文本（城市名或 "lon,lat"） -> "lon,lat"，命中缓存避免重复 Geo 查询 */
    private val locationCache = mutableMapOf<String, String>()

    /** 按城市名拉取，city 为 GeoAPI 返回的展示名 */
    suspend fun fetch(city: String): WeatherUi = withContext(Dispatchers.IO) {
        val location = locationCache.getOrPut(city) { geoLookup(city) }
        fetchWeather(city, location)
    }

    /** 按坐标反查城市并拉取天气（定位模式） */
    suspend fun fetchByCoords(lat: Double, lon: Double): WeatherUi = withContext(Dispatchers.IO) {
        val query = "$lon,$lat"
        val location = locationCache.getOrPut(query) { geoLookup(query) }
        val city = location.substringAfterLast('|')
        fetchWeather(city, location)
    }

    private fun fetchWeather(city: String, location: String): WeatherUi {
        val coords = location.substringBefore('|')
        val json = get("$BASE_URL/v7/weather/now?location=${enc(coords)}&lang=zh&unit=m")
        val root = JSONObject(json)
        if (root.optString("code") != "200") {
            throw IOException("和风返回 code=${root.optString("code")}")
        }
        val now = root.getJSONObject("now")
        return WeatherUi(
            city = city,
            text = now.optString("text"),
            temp = now.optString("temp"),
            icon = now.optString("icon"),
        )
    }

    /** 返回 "lon,lat|展示城市名" */
    private fun geoLookup(query: String): String {
        val json = get("$BASE_URL/geo/v2/city/lookup?location=${enc(query)}&lang=zh&number=1")
        val root = JSONObject(json)
        if (root.optString("code") != "200") throw IOException("城市查询失败: ${root.optString("code")}")
        val locations = root.optJSONArray("location") ?: throw IOException("城市未找到: $query")
        if (locations.length() == 0) throw IOException("城市未找到: $query")
        val loc = locations.getJSONObject(0)
        val name = loc.optString("name")
        val adm2 = loc.optString("adm2")
        val display = if (adm2.isNotEmpty() && adm2 != name) adm2 else name
        return "${loc.getString("lon")},${loc.getString("lat")}|$display"
    }

    private fun get(url: String): String {
        android.util.Log.i("LapseClock", "请求: $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("X-QW-Api-Key", BuildConfig.QWEATHER_KEY)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?: throw IOException("HTTP $code")
            val raw = if (conn.contentEncoding == "gzip") GZIPInputStream(stream) else stream
            val body = raw.bufferedReader().use { it.readText() }
            if (code !in 200..299) throw IOException("HTTP $code @ $url: ${body.take(300)}")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val BASE_URL: String = "https://" + BuildConfig.QWEATHER_HOST
            .trim()
            .trimEnd('/')
            .removePrefix("https://")
            .removePrefix("http://")
    }
}
