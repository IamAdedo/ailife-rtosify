package com.iamadedo.phoneapp

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fetches weather and astronomy data on the phone and pushes it to the watch.
 *
 * Data sources:
 *  - Weather: Open-Meteo API (free, no key required)
 *  - Sunrise/sunset: computed locally using NOAA solar calculation
 *  - Moon phase: computed locally using standard lunar cycle algorithm
 *
 * Pushes:
 *  - WeatherData (current + 5-day forecast) → WEATHER_UPDATE
 *  - SunriseSunsetData                      → SUNRISE_SUNSET_UPDATE
 *  - MoonPhaseData                          → MOON_PHASE_UPDATE
 *
 * Auto-refreshes every REFRESH_INTERVAL_MS when started.
 */
class WeatherSyncManager(
    private val context: Context,
    private val onSendMessage: (ProtocolMessage) -> Unit
) {
    companion object {
        private const val REFRESH_INTERVAL_MS = 30 * 60_000L   // every 30 min
        private const val OPEN_METEO_URL =
            "https://api.open-meteo.com/v1/forecast" +
            "?latitude=%s&longitude=%s" +
            "&current=temperature_2m,weathercode,relativehumidity_2m,uv_index" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min" +
            "&forecast_days=5&timezone=auto"

        // WMO weather interpretation codes → our simplified codes
        private val WMO_MAP = mapOf(
            0 to "SUNNY", 1 to "SUNNY", 2 to "CLOUDY", 3 to "CLOUDY",
            45 to "FOG", 48 to "FOG",
            51 to "DRIZZLE", 53 to "DRIZZLE", 55 to "DRIZZLE",
            61 to "RAIN", 63 to "RAIN", 65 to "RAIN",
            71 to "SNOW", 73 to "SNOW", 75 to "SNOW", 77 to "SNOW",
            80 to "RAIN", 81 to "RAIN", 82 to "RAIN",
            85 to "SNOW", 86 to "SNOW",
            95 to "THUNDERSTORM", 96 to "THUNDERSTORM", 99 to "THUNDERSTORM"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var running = false
    private var lastLocation: Location? = null

    fun start(location: Location) {
        lastLocation = location
        if (!running) {
            running = true
            handler.post(refreshRunnable)
        }
    }

    fun updateLocation(location: Location) {
        lastLocation = location
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            val loc = lastLocation ?: return
            scope.launch { fetchAndPush(loc.latitude, loc.longitude) }
            if (running) handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun fetchAndPush(lat: Double, lon: Double) {
        try {
            val url = OPEN_METEO_URL.format(
                "%.4f".format(lat), "%.4f".format(lon)
            )
            val json = JSONObject(URL(url).readText())
            val current = json.getJSONObject("current")
            val daily   = json.getJSONObject("daily")

            val tempC    = current.getDouble("temperature_2m").toFloat()
            val wmoCode  = current.getInt("weathercode")
            val humidity = current.optInt("relativehumidity_2m", 0)
            val uvIndex  = current.optInt("uv_index", 0)

            val forecast = mutableListOf<WeatherForecastDay>()
            val wCodes = daily.getJSONArray("weathercode")
            val hiTemps = daily.getJSONArray("temperature_2m_max")
            val loTemps = daily.getJSONArray("temperature_2m_min")
            for (i in 0 until minOf(5, wCodes.length())) {
                forecast.add(WeatherForecastDay(
                    dayOffset     = i,
                    highTempC     = hiTemps.getDouble(i).toFloat(),
                    lowTempC      = loTemps.getDouble(i).toFloat(),
                    conditionCode = WMO_MAP[wCodes.getInt(i)] ?: "CLOUDY"
                ))
            }

            val weatherData = WeatherData(
                locationName  = "%.2f,%.2f".format(lat, lon),
                currentTempC  = tempC,
                conditionCode = WMO_MAP[wmoCode] ?: "CLOUDY",
                humidity      = humidity,
                uvIndex       = uvIndex,
                forecast      = forecast
            )
            handler.post { onSendMessage(ProtocolHelper.createWeatherUpdate(weatherData)) }

        } catch (e: Exception) {
            // Network unavailable — skip silently
        }

        // Astronomy is computed locally — always works offline
        pushAstronomy(lat, lon)
    }

    private fun pushAstronomy(lat: Double, lon: Double) {
        val now = Calendar.getInstance()
        val jd = julianDay(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH))

        val (sunrise, sunset) = sunriseSunset(lat, lon, jd)
        val goldenMorning = sunrise + 30 * 60_000L   // ~30 min after sunrise
        val goldenEvening = sunset  - 30 * 60_000L   // ~30 min before sunset

        val sunData = SunriseSunsetData(
            sunriseTime       = sunrise,
            sunsetTime        = sunset,
            goldenHourMorning = goldenMorning,
            goldenHourEvening = goldenEvening
        )
        handler.post { onSendMessage(ProtocolHelper.createSunriseSunsetUpdate(sunData)) }

        val (moonrise, moonset, phase, phaseName) = moonData(jd)
        val moonD = MoonPhaseData(
            moonriseTime = moonrise,
            moonsetTime  = moonset,
            phasePercent = phase,
            phaseName    = phaseName
        )
        handler.post { onSendMessage(ProtocolHelper.createMoonPhaseUpdate(moonD)) }
    }

    // ── Solar calculations (NOAA algorithm) ───────────────────────────────────

    private fun julianDay(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y--; m += 12 }
        val a = y / 100
        val b = 2 - a + a / 4
        return (365.25 * (y + 4716)).toLong() + (30.6001 * (m + 1)).toLong() + day + b - 1524.5
    }

    private fun sunriseSunset(lat: Double, lon: Double, jd: Double): Pair<Long, Long> {
        val D2R = PI / 180.0
        val n = jd - 2451545.0
        val L = (280.460 + 0.9856474 * n) % 360
        val g = (357.528 + 0.9856003 * n) % 360
        val lam = L + 1.915 * sin(g * D2R) + 0.020 * sin(2 * g * D2R)
        val eps = 23.439 - 0.0000004 * n
        val sinDec = sin(eps * D2R) * sin(lam * D2R)
        val dec = Math.toDegrees(Math.asin(sinDec))
        val cosHa = (cos(90.833 * D2R) - sin(lat * D2R) * sin(dec * D2R)) /
                    (cos(lat * D2R) * cos(dec * D2R))
        if (cosHa < -1 || cosHa > 1) return Pair(0L, 0L)   // polar day/night
        val ha = Math.toDegrees(acos(cosHa))
        val noon = (720 - 4 * lon) / 1440.0   // fraction of day
        val riseDay  = noon - ha / 360.0
        val setDay   = noon + ha / 360.0

        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        return Pair(
            base + (riseDay * 86_400_000).toLong(),
            base + (setDay  * 86_400_000).toLong()
        )
    }

    // ── Lunar calculations ────────────────────────────────────────────────────

    data class MoonInfo(val moonrise: Long, val moonset: Long, val phasePercent: Float, val phaseName: String)

    private fun moonData(jd: Double): MoonInfo {
        // Simple lunar phase: days since known new moon (Jan 6, 2000 = JD 2451549.5)
        val knownNewMoonJd = 2451549.5
        val lunarCycle = 29.53059
        val daysSinceNew = (jd - knownNewMoonJd) % lunarCycle
        val normalised = if (daysSinceNew < 0) daysSinceNew + lunarCycle else daysSinceNew
        val phasePercent = (normalised / lunarCycle * 100f).toFloat()

        val phaseName = when {
            phasePercent <  1.8  -> "NEW"
            phasePercent < 25.0  -> "WAXING_CRESCENT"
            phasePercent < 26.8  -> "FIRST_QUARTER"
            phasePercent < 50.0  -> "WAXING_GIBBOUS"
            phasePercent < 51.8  -> "FULL"
            phasePercent < 75.0  -> "WANING_GIBBOUS"
            phasePercent < 76.8  -> "LAST_QUARTER"
            phasePercent < 100.0 -> "WANING_CRESCENT"
            else                 -> "NEW"
        }

        // Rough moonrise: shifts ~50 min later per day from a base of 06:00 at new moon
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val riseOffset = ((6 * 60 + normalised * 50) % (24 * 60) * 60_000).toLong()
        val setOffset  = ((riseOffset / 60_000 + 12 * 60) % (24 * 60) * 60_000).toLong()

        return MoonInfo(
            moonrise     = base + riseOffset,
            moonset      = base + setOffset,
            phasePercent = phasePercent,
            phaseName    = phaseName
        )
    }
}
