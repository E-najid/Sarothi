package com.ngi.sarothi.plugins.information

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ngi.sarothi.core.plugin.JsonSchema
import com.ngi.sarothi.core.plugin.Plugin
import com.ngi.sarothi.core.plugin.PluginAvailability
import com.ngi.sarothi.core.plugin.PluginCategory
import com.ngi.sarothi.core.plugin.PluginContext
import com.ngi.sarothi.core.plugin.PluginResult
import com.ngi.sarothi.core.plugin.Sensitivity
import com.ngi.sarothi.core.plugin.pluginContext
import com.ngi.sarothi.core.util.Json
import com.ngi.sarothi.core.util.stringOrNull
import com.ngi.sarothi.plugins.common.textOrAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Decodes the HTML entities that show up in scraped search results. */
internal fun unescapeHtml(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#x27;", "'")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")
    .replace("&#x2F;", "/")
    .replace("&apos;", "'")

/** Strips tags and collapses whitespace, for turning a page into readable text. */
internal fun htmlToText(html: String, maxCharacters: Int): String {
    val withoutScripts = html
        .replace(Regex("""(?is)<script.*?</script>"""), " ")
        .replace(Regex("""(?is)<style.*?</style>"""), " ")
        .replace(Regex("""(?is)<noscript.*?</noscript>"""), " ")
        .replace(Regex("""(?is)<!--.*?-->"""), " ")
    val withBreaks = withoutScripts
        .replace(Regex("""(?i)</(p|div|li|h[1-6]|tr|section|article|br)>"""), "\n")
        .replace(Regex("""(?i)<br\s*/?>"""), "\n")
    val text = withBreaks
        .replace(Regex("<[^>]+>"), " ")
        .let { unescapeHtml(it) }
        .lines()
        .map { line -> line.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    return if (text.length > maxCharacters) text.take(maxCharacters) + " …[truncated]" else text
}

internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** True when the network is usable; otherwise a plugin should say so, not guess. */
internal fun offlineResult(context: PluginContext, what: String): PluginResult.Unavailable? {
    if (context.network.isOnline()) return null
    return PluginResult.Unavailable(
        PluginAvailability.unavailable(
            reason = "$what needs an internet connection and this phone is offline.",
            fixAction = "Turn on Wi-Fi or mobile data and try again.",
        ),
    )
}

/**
 * Web search against DuckDuckGo's HTML endpoint.
 *
 * No API key, no account, no tracking parameter — which is why it is the default
 * rather than a paid search API. It is HTML scraping, so it can break when
 * DuckDuckGo changes markup; when it returns nothing the plugin says "no results
 * could be parsed" rather than reporting an empty result set as though the web had
 * no answer.
 */
class WebSearchPlugin : Plugin {
    override val name = "web_search"
    override val description =
        "Search the web and return titles, URLs and snippets. Use it for anything current — prices, " +
            "news, opening hours, a fact you are not sure of. Results are links to read, not answers: " +
            "quote the source and say which one it came from."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "query" to JsonSchema.Property.Text("What to search for."),
            "limit" to JsonSchema.Property.Integer("How many results to return.", minimum = 1, maximum = 15, default = 6),
            "region" to JsonSchema.Property.Text("Region hint, e.g. 'bd-en' for Bangladesh or 'us-en'.", default = "bd-en"),
        ),
        required = listOf("query"),
    )

    override val example = """{"query":"ঢাকা আবহাওয়া আজ","limit":5}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "Web search needs an internet connection and this phone is offline.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "Web search")?.let { return it }

        val query = params.textOrAsk("query", "What should Sarothi search the web for?")
        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 15) ?: 6
        val region = params.stringOrNull("region")?.takeIf { it.isNotBlank() } ?: "bd-en"

        val url = "https://html.duckduckgo.com/html/?q=${encode(query)}&kl=${encode(region)}"
        val response = withContext(Dispatchers.IO) {
            runCatching { context.http.get(url) }
        }
        val body = response.fold(
            onSuccess = { result ->
                if (!result.isSuccess) {
                    return PluginResult.Failure(
                        summaryForUser = "DuckDuckGo answered HTTP ${result.statusCode} for that search.",
                        errorClass = "HttpErrorException",
                        retriable = result.statusCode in 500..599 || result.statusCode == 429,
                    )
                }
                result.bodyText()
            },
            onFailure = { failure ->
                return PluginResult.Failure(
                    summaryForUser = "The search could not be completed: ${failure.javaClass.simpleName}: " +
                        "${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            },
        )

        val results = parseDuckDuckGo(body).take(limit)
        if (results.isEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "Sarothi reached DuckDuckGo but could not read any results out of the " +
                    "page. Either there are none for \"$query\", or DuckDuckGo changed its layout and " +
                    "this parser needs updating. It will not invent results.",
                errorClass = "SearchParseFailureException",
                retriable = true,
                data = Json.obj {
                    addProperty("query", query)
                    addProperty("http_status", 200)
                    addProperty("body_characters", body.length)
                },
            )
        }

        val data = Json.obj {
            addProperty("query", query)
            addProperty("source", "duckduckgo-html")
            add("results", Json.arr {
                results.forEach { result ->
                    add(Json.obj {
                        addProperty("title", result.title)
                        addProperty("url", result.url)
                        addProperty("snippet", result.snippet)
                    })
                }
            })
            addProperty("count", results.size)
        }
        return PluginResult.Success(
            summaryForUser = "${results.size} result(s) for \"$query\": " +
                results.take(3).joinToString("; ") { it.title },
            data = data,
        )
    }

    private class Hit(val title: String, val url: String, val snippet: String)

    private fun parseDuckDuckGo(html: String): List<Hit> {
        val hits = mutableListOf<Hit>()
        val resultPattern = Regex(
            """(?is)<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
        )
        val snippetPattern = Regex(
            """(?is)<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""",
        )
        val snippets = snippetPattern.findAll(html).map { clean(it.groupValues[1]) }.toList()

        resultPattern.findAll(html).forEachIndexed { index, match ->
            val rawHref = match.groupValues[1]
            val title = clean(match.groupValues[2])
            val url = unwrapDuckDuckGo(rawHref)
            if (title.isBlank() || url.isBlank()) return@forEachIndexed
            hits += Hit(title, url, snippets.getOrElse(index) { "" })
        }
        return hits
    }

    /** DuckDuckGo wraps outbound links in a redirect; the real URL is in `uddg`. */
    private fun unwrapDuckDuckGo(href: String): String {
        val decoded = href.replace("&amp;", "&")
        val redirect = Regex("""[?&]uddg=([^&]+)""").find(decoded)
        if (redirect != null) {
            return runCatching {
                java.net.URLDecoder.decode(redirect.groupValues[1], "UTF-8")
            }.getOrDefault(decoded)
        }
        return if (decoded.startsWith("//")) "https:$decoded" else decoded
    }

    private fun clean(fragment: String): String =
        unescapeHtml(fragment.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
}

/** Fetches one page and returns its readable text. */
class FetchWebPagePlugin : Plugin {
    override val name = "fetch_web_page"
    override val description =
        "Download one web page and return its readable text, for reading a result from web_search. " +
            "Scripts, styles and navigation are stripped. Returns plain text, not a rendering, so a " +
            "page that is entirely JavaScript will come back nearly empty and say so."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "url" to JsonSchema.Property.Text("The page address, including https://."),
            "max_characters" to JsonSchema.Property.Integer("How much text to return.", minimum = 500, maximum = 40000, default = 12000),
        ),
        required = listOf("url"),
    )

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable("Fetching a page needs an internet connection and this phone is offline.")
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "Fetching a page")?.let { return it }

        val url = params.textOrAsk("url", "Which page should Sarothi fetch?")
        val maxCharacters = params.get("max_characters")?.takeIf { it.isJsonPrimitive }?.asInt
            ?.coerceIn(500, 40000) ?: 12000

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return PluginResult.Failure(
                summaryForUser = "\"$url\" is not a full web address. Sarothi will not guess whether you " +
                    "meant http or https, or which domain.",
                errorClass = "MalformedUrlException",
                retriable = true,
            )
        }

        val response = withContext(Dispatchers.IO) { runCatching { context.http.get(url) } }
        return response.fold(
            onSuccess = { result ->
                if (!result.isSuccess) {
                    return PluginResult.Failure(
                        summaryForUser = "${result.finalUrl} answered HTTP ${result.statusCode}.",
                        errorClass = "HttpErrorException",
                        retriable = result.statusCode in 500..599,
                    )
                }
                val contentType = result.header("Content-Type") ?: ""
                val html = result.bodyText()
                val text = htmlToText(html, maxCharacters)
                if (text.length < 120) {
                    return PluginResult.Failure(
                        summaryForUser = "That page produced only ${text.length} characters of readable " +
                            "text (content type: ${contentType.ifBlank { "unknown" }}). It is probably " +
                            "built entirely in JavaScript, which Sarothi does not execute.",
                        errorClass = "NoReadableTextException",
                        retriable = false,
                        data = Json.obj {
                            addProperty("url", result.finalUrl)
                            addProperty("content_type", contentType)
                            addProperty("text", text)
                        },
                    )
                }
                PluginResult.Success(
                    summaryForUser = "Fetched ${result.finalUrl}: ${text.length} characters of text.",
                    data = Json.obj {
                        addProperty("url", result.finalUrl)
                        addProperty("requested_url", url)
                        addProperty("status", result.statusCode)
                        addProperty("content_type", contentType)
                        addProperty("characters", text.length)
                        addProperty("truncated", text.endsWith("…[truncated]"))
                        addProperty("text", text)
                    },
                )
            },
            onFailure = { failure ->
                PluginResult.Failure(
                    summaryForUser = "Sarothi could not fetch that page: ${failure.javaClass.simpleName}: " +
                        "${failure.message}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            },
        )
    }
}

/**
 * Weather from Open-Meteo.
 *
 * Chosen because it needs no API key and no account, which matters for an
 * open-source app whose users should not have to register anywhere to get a
 * forecast. Hourly and daily data come from `api.open-meteo.com`, place names are
 * resolved by `geocoding-api.open-meteo.com`.
 */
class WeatherPlugin : Plugin {
    override val name = "weather"
    override val description =
        "Current weather and a forecast for a place, from Open-Meteo. Give a city name, or latitude and " +
            "longitude. Returns temperature, rain, wind and humidity, plus up to 7 days of forecast. " +
            "No API key and no account are involved."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "place" to JsonSchema.Property.Text("A city or area name, e.g. 'Habiganj' or 'ঢাকা'. Ignored when latitude and longitude are given."),
            "latitude" to JsonSchema.Property.Number("Latitude, -90 to 90.", minimum = -90.0, maximum = 90.0),
            "longitude" to JsonSchema.Property.Number("Longitude, -180 to 180.", minimum = -180.0, maximum = 180.0),
            "days" to JsonSchema.Property.Integer("How many forecast days, including today.", minimum = 1, maximum = 7, default = 3),
            "country_hint" to JsonSchema.Property.Text("Narrow the place search, e.g. 'BD'.", default = "BD"),
        ),
    )

    override val example = """{"place":"হবিগঞ্জ","days":3}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "Weather comes from Open-Meteo over the internet and this phone is offline.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "Weather")?.let { return it }

        val days = params.get("days")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 7) ?: 3
        var latitude = params.get("latitude")?.takeIf { it.isJsonPrimitive }?.asDouble
        var longitude = params.get("longitude")?.takeIf { it.isJsonPrimitive }?.asDouble
        var placeName = "the given coordinates"
        var placeCountry: String? = null

        if (latitude == null || longitude == null) {
            val place = params.textOrAsk(
                "place",
                "Which place should Sarothi get the weather for? A city or area name.",
            )
            val hint = params.stringOrNull("country_hint")?.takeIf { it.isNotBlank() }
            val geocodeUrl = buildString {
                append("https://geocoding-api.open-meteo.com/v1/search?name=").append(encode(place))
                append("&count=5&language=en&format=json")
                if (hint != null) append("&countryCode=").append(encode(hint))
            }
            val geocode = withContext(Dispatchers.IO) { runCatching { context.http.get(geocodeUrl) } }
                .getOrElse { failure ->
                    return PluginResult.Failure(
                        summaryForUser = "Sarothi could not look up \"$place\": ${failure.javaClass.simpleName}",
                        errorClass = failure.javaClass.simpleName,
                        retriable = true,
                    )
                }
            if (!geocode.isSuccess) {
                return PluginResult.Failure(
                    "The place lookup answered HTTP ${geocode.statusCode}.",
                    "HttpErrorException",
                    retriable = geocode.statusCode in 500..599,
                )
            }
            val candidates = runCatching {
                JsonParser.parseString(geocode.bodyText()).asJsonObject.getAsJsonArray("results")
            }.getOrNull()
            if (candidates == null || candidates.size() == 0) {
                return PluginResult.Failure(
                    summaryForUser = "Open-Meteo found no place called \"$place\"" +
                        if (hint != null) " in country $hint" else "" +
                        ". Sarothi will not guess coordinates.",
                    errorClass = "PlaceNotFoundException",
                    retriable = true,
                )
            }
            if (candidates.size() > 1) {
                val options = candidates.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val candidate = element.asJsonObject
                    val name = candidate.stringOrNull("name") ?: return@mapNotNull null
                    val admin = candidate.stringOrNull("admin1")
                    val country = candidate.stringOrNull("country_code")
                    "$name" + listOfNotNull(admin, country).joinToString(prefix = ", ") { it }
                }
                return PluginResult.NeedsUserInput(
                    question = "${candidates.size()} places match \"$place\". Which one?",
                    field = "place",
                    choices = options.take(5),
                )
            }
            val chosen = candidates[0].asJsonObject
            latitude = chosen.get("latitude")?.takeIf { it.isJsonPrimitive }?.asDouble
            longitude = chosen.get("longitude")?.takeIf { it.isJsonPrimitive }?.asDouble
            placeName = chosen.stringOrNull("name") ?: place
            placeCountry = chosen.stringOrNull("country_code")
            if (latitude == null || longitude == null) {
                return PluginResult.Failure(
                    "The place lookup returned no coordinates for \"$place\".",
                    "PlaceNotFoundException",
                    retriable = true,
                )
            }
        }

        val weatherUrl = buildString {
            append("https://api.open-meteo.com/v1/forecast?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,")
            append("weather_code,wind_speed_10m,wind_direction_10m")
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,")
            append("precipitation_probability_max,wind_speed_10m_max,sunrise,sunset")
            append("&forecast_days=").append(days)
            append("&timezone=auto")
        }
        val weather = withContext(Dispatchers.IO) { runCatching { context.http.get(weatherUrl) } }
            .getOrElse { failure ->
                return PluginResult.Failure(
                    summaryForUser = "Sarothi could not reach the weather service: ${failure.javaClass.simpleName}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            }
        if (!weather.isSuccess) {
            return PluginResult.Failure(
                "The weather service answered HTTP ${weather.statusCode}.",
                "HttpErrorException",
                retriable = weather.statusCode in 500..599,
            )
        }
        val payload = runCatching { JsonParser.parseString(weather.bodyText()).asJsonObject }
            .getOrElse { failure ->
                return PluginResult.Failure(
                    "The weather service returned something that is not JSON: ${failure.message}",
                    "MalformedResponseException",
                    retriable = true,
                )
            }

        val current = payload.getAsJsonObject("current")
        val daily = payload.getAsJsonObject("daily")
        val zone = payload.stringOrNull("timezone") ?: ZoneId.systemDefault().id
        if (current == null || daily == null) {
            return PluginResult.Failure(
                "The weather service returned no current or daily block for those coordinates.",
                "MalformedResponseException",
                retriable = true,
            )
        }

        val temperature = current.numberOrNull("temperature_2m")
        val feelsLike = current.numberOrNull("apparent_temperature")
        val humidity = current.numberOrNull("relative_humidity_2m")
        val code = current.numberOrNull("weather_code")?.toInt()
        val wind = current.numberOrNull("wind_speed_10m")
        val precipitation = current.numberOrNull("precipitation")

        val forecast = mutableListOf<JsonObject>()
        val dates = daily.getAsJsonArray("time")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?: emptyList()
        dates.forEachIndexed { index, date ->
            forecast += Json.obj {
                addProperty("date", date)
                daily.numberArrayOrNull("temperature_2m_max")?.getOrNull(index)?.let { addProperty("max_c", it) }
                daily.numberArrayOrNull("temperature_2m_min")?.getOrNull(index)?.let { addProperty("min_c", it) }
                daily.numberArrayOrNull("precipitation_sum")?.getOrNull(index)?.let { addProperty("rain_mm", it) }
                daily.numberArrayOrNull("precipitation_probability_max")?.getOrNull(index)
                    ?.let { addProperty("rain_chance_percent", it.toInt()) }
                daily.numberArrayOrNull("wind_speed_10m_max")?.getOrNull(index)?.let { addProperty("wind_kmh", it) }
                daily.numberArrayOrNull("weather_code")?.getOrNull(index)?.toInt()
                    ?.let { addProperty("condition", describeWeatherCode(it)) }
                daily.stringArrayOrNull("sunrise")?.getOrNull(index)?.let { addProperty("sunrise", it) }
                daily.stringArrayOrNull("sunset")?.getOrNull(index)?.let { addProperty("sunset", it) }
            }
        }

        val condition = code?.let { describeWeatherCode(it) } ?: "conditions not reported"
        val data = Json.obj {
            addProperty("place", placeName)
            placeCountry?.let { addProperty("country", it) }
            addProperty("latitude", latitude!!)
            addProperty("longitude", longitude!!)
            addProperty("timezone", zone)
            addProperty("source", "open-meteo.com")
            addProperty("retrieved_at", Instant.now().toString())
            add("current", Json.obj {
                temperature?.let { addProperty("temperature_c", it) }
                feelsLike?.let { addProperty("feels_like_c", it) }
                humidity?.let { addProperty("humidity_percent", it.toInt()) }
                precipitation?.let { addProperty("precipitation_mm", it) }
                wind?.let { addProperty("wind_kmh", it) }
                addProperty("condition", condition)
            })
            add("forecast", Json.arr { forecast.forEach { add(it) } })
        }

        return PluginResult.Success(
            summaryForUser = buildString {
                append(placeName)
                append(": ").append(condition)
                temperature?.let { append(", ").append(it.toInt()).append("°C") }
                feelsLike?.let { append(" (feels like ").append(it.toInt()).append("°C)") }
                humidity?.let { append(", ").append(it.toInt()).append("% humidity") }
                precipitation?.takeIf { value -> value > 0.0 }?.let { append(", ").append(it).append(" mm rain") }
                wind?.let { append(", wind ").append(it.toInt()).append(" km/h") }
                append('.').append(if (forecast.isNotEmpty()) " ${forecast.size} day(s) of forecast included." else "")
            },
            data = data,
        )
    }

    companion object {
        /** WMO weather interpretation codes, as published by Open-Meteo. */
        fun describeWeatherCode(code: Int): String = when (code) {
            0 -> "clear sky"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51 -> "light drizzle"
            53 -> "drizzle"
            55 -> "heavy drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80 -> "light rain showers"
            81 -> "rain showers"
            82 -> "violent rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorm"
            96 -> "thunderstorm with light hail"
            99 -> "thunderstorm with heavy hail"
            else -> "unreported condition (WMO code $code)"
        }
    }
}

private fun JsonObject.numberOrNull(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun JsonObject.numberArrayOrNull(key: String): List<Double>? =
    getAsJsonArray(key)?.mapNotNull { element ->
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asDouble else null
    }

private fun JsonObject.stringArrayOrNull(key: String): List<String>? =
    getAsJsonArray(key)?.mapNotNull { element ->
        if (element.isJsonPrimitive) element.asString else null
    }

/**
 * Translation through MyMemory.
 *
 * A free, key-less translation memory API. Quality is uneven and it is nowhere
 * near a full neural engine — which is exactly why the result carries the source
 * name and a match score, so the user can see how much to trust it. Sarothi does
 * not pretend a machine translation is authoritative.
 */
class TranslatePlugin : Plugin {
    override val name = "translate"
    override val description =
        "Translate text between two languages using the free MyMemory service. Give ISO codes: 'bn' for " +
            "Bengali, 'en' for English, 'hi', 'ar' and so on. The result includes the engine's own " +
            "match score — treat a low score as a rough translation, not a reliable one."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "text" to JsonSchema.Property.Text("The text to translate."),
            "from" to JsonSchema.Property.Text("Source language code, e.g. 'bn'.", default = "bn"),
            "to" to JsonSchema.Property.Text("Target language code, e.g. 'en'.", default = "en"),
        ),
        required = listOf("text"),
    )

    override val example = """{"text":"আমি ভালো আছি","from":"bn","to":"en"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "Translation is done by an online service; Sarothi has no offline translation model.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "Translation")?.let { return it }

        val text = params.textOrAsk("text", "What should Sarothi translate?")
        val from = (params.stringOrNull("from") ?: "bn").trim().lowercase()
        val to = (params.stringOrNull("to") ?: "en").trim().lowercase()
        if (from == to) {
            return PluginResult.Failure(
                "Source and target language are both '$from', so there is nothing to translate.",
                "SameLanguageException",
                retriable = true,
            )
        }
        if (text.length > MAX_TEXT_LENGTH) {
            return PluginResult.Failure(
                summaryForUser = "MyMemory accepts at most $MAX_TEXT_LENGTH characters per request and " +
                    "this text has ${text.length}. Split it and translate the parts.",
                errorClass = "TextTooLongException",
                retriable = true,
            )
        }

        val url = "https://api.mymemory.translated.net/get?q=${encode(text)}&langpair=${encode(from)}|${encode(to)}"
        val response = withContext(Dispatchers.IO) { runCatching { context.http.get(url) } }
            .getOrElse { failure ->
                return PluginResult.Failure(
                    summaryForUser = "Sarothi could not reach the translation service: " +
                        "${failure.javaClass.simpleName}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            }
        if (!response.isSuccess) {
            return PluginResult.Failure(
                "The translation service answered HTTP ${response.statusCode}.",
                "HttpErrorException",
                retriable = response.statusCode in 500..599,
            )
        }
        val payload = runCatching { JsonParser.parseString(response.bodyText()).asJsonObject }
            .getOrElse { failure ->
                return PluginResult.Failure(
                    "The translation service returned something that is not JSON: ${failure.message}",
                    "MalformedResponseException",
                    retriable = true,
                )
            }

        val status = payload.getAsJsonObject("responseStatus")
        val statusOk = status != null && (
            (status.isJsonPrimitive && status.asString == "200") ||
                (status.isJsonPrimitive && status.asJsonPrimitive.isNumber && status.asInt == 200)
            )
        val responseData = payload.getAsJsonObject("responseData")
        val translated = responseData?.stringOrNull("translatedText")
        if (!statusOk || translated.isNullOrBlank()) {
            val message = payload.stringOrNull("responseDetails")
            return PluginResult.Failure(
                summaryForUser = if (message != null) {
                    "The translation service could not translate that: $message"
                } else {
                    "The translation service could not translate that. It may have hit its daily " +
                        "anonymous limit."
                },
                errorClass = "TranslationRefusedException",
                retriable = true,
            )
        }

        val match = responseData.numberOrNull("match")
        val alternatives = payload.getAsJsonArray("matches")?.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val candidate = element.asJsonObject
            val value = candidate.stringOrNull("translation") ?: return@mapNotNull null
            val quality = candidate.stringOrNull("quality")
            val source = candidate.stringOrNull("created-by")
            value to listOfNotNull(quality?.let { "quality $it" }, source)
        }?.distinctBy { it.first }?.take(4) ?: emptyList()

        val data = Json.obj {
            addProperty("text", text)
            addProperty("translated", unescapeHtml(translated))
            addProperty("from", from)
            addProperty("to", to)
            addProperty("source", "mymemory.translated.net")
            match?.let { addProperty("match_score", it) }
            add("alternatives", Json.arr {
                alternatives.forEach { (value, notes) ->
                    add(Json.obj {
                        addProperty("translation", unescapeHtml(value))
                        if (notes.isNotEmpty()) addProperty("notes", notes.joinToString(", "))
                    })
                }
            })
            if (match != null && match < LOW_CONFIDENCE) {
                addProperty("warning", "The service rated this match ${"%.2f".format(match)} — treat it as rough.")
            }
        }
        return PluginResult.Success(
            summaryForUser = "$from → $to: \"${unescapeHtml(translated)}\"" +
                if (match != null && match < LOW_CONFIDENCE) " (low confidence)" else "",
            data = data,
        )
    }

    private companion object {
        const val MAX_TEXT_LENGTH = 500
        const val LOW_CONFIDENCE = 0.7
    }
}

/**
 * News from RSS feeds.
 *
 * RSS rather than a news API because feeds need no key and the publisher controls
 * them. The default set is Bangladeshi and international public broadcasters;
 * users can add their own feed URLs in plugin settings, and any feed they add is
 * used instead of the defaults.
 */
class NewsPlugin : Plugin {
    override val name = "news"
    override val description =
        "Read the latest headlines from RSS feeds. Without a topic it returns the configured feeds' " +
            "top stories; with a topic it fetches a Google News RSS search for it. Headlines are the " +
            "publisher's own words, with links — Sarothi does not rewrite or summarise them into " +
            "something that looks like a verified fact."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "topic" to JsonSchema.Property.Text("A search topic, e.g. 'cricket' or 'বাংলাদেশ অর্থনীতি'. Empty reads the configured feeds."),
            "limit" to JsonSchema.Property.Integer("How many headlines.", minimum = 1, maximum = 25, default = 8),
            "feed_url" to JsonSchema.Property.Text("A specific RSS/Atom feed to read instead."),
        ),
    )

    override val example = """{"topic":"বাংলাদেশ অর্থনীতি","limit":6}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "News comes from live RSS feeds and this phone is offline.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "News")?.let { return it }

        val limit = params.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, 25) ?: 8
        val topic = params.stringOrNull("topic")?.trim()?.takeIf { it.isNotEmpty() }
        val explicitFeed = params.stringOrNull("feed_url")?.trim()?.takeIf { it.isNotEmpty() }
        val configuredFeeds = context.config.string("feeds")?.split('|')?.map { it.trim() }
            ?.filter { it.isNotEmpty() } ?: emptyList()

        val feeds: List<Pair<String, String>> = when {
            explicitFeed != null -> listOf("requested" to explicitFeed)
            topic != null -> listOf(
                "google-news" to "https://news.google.com/rss/search?q=${encode(topic)}&hl=en&gl=BD&ceid=BD:en",
            )
            configuredFeeds.isNotEmpty() -> configuredFeeds.map { "configured" to it }
            else -> DEFAULT_FEEDS
        }

        val stories = mutableListOf<JsonObject>()
        val failures = mutableListOf<String>()
        for ((label, feedUrl) in feeds) {
            if (stories.size >= limit) break
            val response = withContext(Dispatchers.IO) { runCatching { context.http.get(feedUrl) } }
            // Not `response.getOrElse { ... continue }`: break and continue are not
            // allowed inside an inline lambda without an experimental compiler flag,
            // so the failure is tested explicitly and the loop continues from here.
            if (response.isFailure) {
                failures += "$label: ${response.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
                continue
            }
            val result = response.getOrThrow()
            if (!result.isSuccess) {
                failures += "$label: HTTP ${result.statusCode}"
                continue
            }
            val parseAttempt = runCatching { parseFeed(result.bodyText(), label, feedUrl) }
            if (parseAttempt.isFailure) {
                failures += "$label: ${parseAttempt.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}"
                continue
            }
            val parsed = parseAttempt.getOrThrow()
            stories += parsed.take(limit - stories.size)
        }

        if (stories.isEmpty()) {
            return PluginResult.Failure(
                summaryForUser = "Sarothi could not read any feed. " +
                    if (failures.isEmpty()) "The feeds returned no items."
                    else "Failures: ${failures.joinToString("; ")}",
                errorClass = "FeedFetchFailedException",
                retriable = true,
            )
        }

        val data = Json.obj {
            addProperty("topic", topic ?: "")
            add("stories", Json.arr { stories.forEach { add(it) } })
            addProperty("count", stories.size)
            if (failures.isNotEmpty()) add("feed_failures", Json.arr { failures.forEach { add(it) } })
        }
        return PluginResult.Success(
            summaryForUser = "${stories.size} headline(s)" +
                if (topic != null) " for \"$topic\"" else "" + ": " +
                stories.take(3).joinToString("; ") { it.stringOrNull("title") ?: "" },
            data = data,
        )
    }

    /** Parses the subset of RSS 2.0 and Atom that news publishers actually emit. */
    private fun parseFeed(xml: String, label: String, feedUrl: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        val itemPattern = Regex("""(?is)<item\b[^>]*>(.*?)</item>""")
        val entryPattern = Regex("""(?is)<entry\b[^>]*>(.*?)</entry>""")
        val blocks = itemPattern.findAll(xml).map { it.groupValues[1] }.toList() +
            entryPattern.findAll(xml).map { it.groupValues[1] }.toList()

        blocks.forEach { block ->
            val title = tag(block, "title") ?: return@forEach
            val link = tag(block, "link") ?: atomLink(block)
            val description = tag(block, "description") ?: tag(block, "summary") ?: tag(block, "content")
            val published = tag(block, "pubDate") ?: tag(block, "published") ?: tag(block, "updated")
            val source = tag(block, "source") ?: label
            items += Json.obj {
                addProperty("title", cleanCdata(title))
                link?.let { addProperty("url", cleanCdata(it)) }
                description?.let { addProperty("summary", htmlToText(cleanCdata(it), 400)) }
                published?.let { addProperty("published", cleanCdata(it)) }
                addProperty("feed", source)
                addProperty("feed_url", feedUrl)
            }
        }
        return items
    }

    private fun tag(block: String, name: String): String? {
        val match = Regex("""(?is)<$name\b[^>]*>(.*?)</$name>""").find(block) ?: return null
        return match.groupValues[1].trim()
    }

    private fun atomLink(block: String): String? =
        Regex("(?is)<link[^>]+href=\"([^\"]+)\"").find(block)?.groupValues?.get(1)

    private fun cleanCdata(value: String): String {
        val withoutCdata = value.replace(Regex("""(?is)<!\[CDATA\[(.*?)]]>""")) { it.groupValues[1] }
        return unescapeHtml(withoutCdata.replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        /**
         * Public-broadcaster and wire feeds. No key, no account, no tracking.
         * Configurable in Settings → Plugins → news.
         */
        val DEFAULT_FEEDS = listOf(
            "BBC World" to "https://feeds.bbci.co.uk/news/world/rss.xml",
            "Al Jazeera" to "https://www.aljazeera.com/xml/rss/all.xml",
            "The Daily Star" to "https://www.thedailystar.net/news/rss",
            "Prothom Alo" to "https://www.prothomalo.com/feed/",
            "Reuters World" to "https://feeds.reuters.com/Reuters/worldNews",
        )
    }
}

/** Wikipedia summaries, in Bengali or English. */
class WikipediaPlugin : Plugin {
    override val name = "wikipedia"
    override val description =
        "Look something up on Wikipedia and return a summary plus the page link. Set language='bn' for " +
            "Bengali Wikipedia. Good for facts, people, places and concepts; not for anything time-" +
            "sensitive, where web_search or news is better."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "topic" to JsonSchema.Property.Text("What to look up."),
            "language" to JsonSchema.Property.Text("Wikipedia language code.", enum = listOf("bn", "en", "hi", "ar"), default = "bn"),
        ),
        required = listOf("topic"),
    )

    override val example = """{"topic":"হবিগঞ্জ জেলা","language":"bn"}"""

    override suspend fun availability(context: PluginContext): PluginAvailability =
        if (context.network.isOnline()) {
            PluginAvailability.READY
        } else {
            PluginAvailability.unavailable(
                "Wikipedia is looked up online; Sarothi has no offline copy of it.",
                fixAction = "Turn on Wi-Fi or mobile data.",
            )
        }

    override suspend fun execute(params: JsonObject): PluginResult {
        val context = pluginContext()
        offlineResult(context, "Wikipedia")?.let { return it }

        val topic = params.textOrAsk("topic", "What should Sarothi look up on Wikipedia?")
        val language = (params.stringOrNull("language") ?: "bn").trim().lowercase()
        if (language !in SUPPORTED_LANGUAGES) {
            return PluginResult.Failure(
                "'$language' is not one of the Wikipedias Sarothi is set up for: " +
                    SUPPORTED_LANGUAGES.joinToString(),
                "UnsupportedLanguageException",
                retriable = true,
            )
        }

        val searchUrl = "https://$language.wikipedia.org/w/rest.php/v1/search/page" +
            "?q=${encode(topic)}&limit=3"
        val searchResponse = withContext(Dispatchers.IO) { runCatching { context.http.get(searchUrl) } }
            .getOrElse { failure ->
                return PluginResult.Failure(
                    summaryForUser = "Sarothi could not search Wikipedia: ${failure.javaClass.simpleName}",
                    errorClass = failure.javaClass.simpleName,
                    retriable = true,
                )
            }
        if (!searchResponse.isSuccess) {
            return PluginResult.Failure(
                "Wikipedia answered HTTP ${searchResponse.statusCode}.",
                "HttpErrorException",
                retriable = searchResponse.statusCode in 500..599,
            )
        }
        val pages = runCatching {
            JsonParser.parseString(searchResponse.bodyText()).asJsonObject.getAsJsonArray("pages")
        }.getOrNull()
        if (pages == null || pages.size() == 0) {
            return PluginResult.Failure(
                summaryForUser = "The $language Wikipedia has no page matching \"$topic\".",
                errorClass = "PageNotFoundException",
                retriable = true,
            )
        }
        if (pages.size() > 1) {
            val titles = pages.mapNotNull { element ->
                if (!element.isJsonObject) return@mapNotNull null
                element.asJsonObject.stringOrNull("title") ?: element.asJsonObject.stringOrNull("key")
            }
            // Take the best match automatically, but tell the user what else existed
            // so a wrong pick is visible rather than silent.
            val chosen = titles.firstOrNull() ?: topic
            val others = titles.drop(1).take(3)
            val summary = fetchSummary(context, language, chosen)
                ?: return PluginResult.Failure(
                    "Wikipedia found \"$chosen\" but would not return its text.",
                    "PageFetchFailedException",
                    retriable = true,
                )
            return PluginResult.Success(
                summaryForUser = summary.first,
                data = summary.second.apply {
                    add("other_matches", Json.arr { others.forEach { add(it) } })
                },
            )
        }

        val title = pages[0].asJsonObject.stringOrNull("title")
            ?: pages[0].asJsonObject.stringOrNull("key")
            ?: topic
        val summary = fetchSummary(context, language, title)
            ?: return PluginResult.Failure(
                summaryForUser = "Wikipedia has a page called \"$title\" but would not return its text.",
                errorClass = "PageFetchFailedException",
                retriable = true,
            )
        return PluginResult.Success(summary.first, summary.second)
    }

    private suspend fun fetchSummary(
        context: PluginContext,
        language: String,
        title: String,
    ): Pair<String, JsonObject>? {
        val url = "https://$language.wikipedia.org/api/rest_v1/page/summary/${encode(title.replace(' ', '_'))}"
        val response = withContext(Dispatchers.IO) { runCatching { context.http.get(url) } }.getOrNull()
            ?: return null
        if (!response.isSuccess) return null
        val payload = runCatching { JsonParser.parseString(response.bodyText()).asJsonObject }.getOrNull()
            ?: return null

        val extract = payload.stringOrNull("extract")
        val description = payload.stringOrNull("description")
        val pageUrl = payload.getAsJsonObject("content_urls")?.getAsJsonObject("desktop")?.stringOrNull("page")
            ?: "https://$language.wikipedia.org/wiki/${encode(title.replace(' ', '_'))}"
        if (extract.isNullOrBlank()) return null

        val data = Json.obj {
            addProperty("title", payload.stringOrNull("title") ?: title)
            description?.let { addProperty("description", it) }
            addProperty("extract", extract)
            addProperty("url", pageUrl)
            addProperty("language", language)
            addProperty("source", "wikipedia")
            addProperty("retrieved_at", Instant.now().toString())
        }
        val headline = buildString {
            append(payload.stringOrNull("title") ?: title)
            description?.let { append(" — ").append(it) }
            append(". ").append(extract.take(300))
            if (extract.length > 300) append('…')
        }
        return headline to data
    }

    private companion object {
        val SUPPORTED_LANGUAGES = setOf("bn", "en", "hi", "ar")
    }
}

/** Today's date and time, so the model never has to guess what day it is. */
class DateTimePlugin : Plugin {
    override val name = "date_time"
    override val description =
        "The current date, time, weekday and time zone on this phone. Use it before any calculation " +
            "involving 'today', 'tomorrow', 'next week' or a date — models do not know the current date " +
            "and must not guess one."
    override val category = PluginCategory.INFORMATION
    override val sensitivity = Sensitivity.READ_ONLY

    override val parameters = JsonSchema(
        properties = mapOf(
            "offset_days" to JsonSchema.Property.Integer("Days from today; negative is in the past.", minimum = -3650, maximum = 3650, default = 0),
        ),
    )

    override suspend fun execute(params: JsonObject): PluginResult {
        val offsetDays = params.get("offset_days")?.takeIf { it.isJsonPrimitive }?.asLong?.coerceIn(-3650, 3650) ?: 0L
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone).plusDays(offsetDays)
        val formatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

        val data = Json.obj {
            addProperty("date", LocalDate.of(now.year, now.month, now.dayOfMonth).toString())
            addProperty("time", DateTimeFormatter.ofPattern("HH:mm:ss").format(now))
            addProperty("weekday", now.dayOfWeek.name)
            addProperty("timezone", zone.id)
            addProperty("epoch_millis", now.toInstant().toEpochMilli())
            addProperty("offset_days", offsetDays)
            addProperty("bangla_date", com.ngi.sarothi.plugins.common.Digits.toBangla(
                DateTimeFormatter.ofPattern("d/M/yyyy").format(now),
            ))
        }
        return PluginResult.Success(
            summaryForUser = "${formatter.format(now)}, ${DateTimeFormatter.ofPattern("HH:mm").format(now)} " +
                "(${zone.id})" + if (offsetDays != 0L) " — $offsetDays day(s) from today" else "",
            data = data,
        )
    }
}
