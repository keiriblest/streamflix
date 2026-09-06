package com.streamflixreborn.streamflix.providers

import android.util.Base64
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Desempaquetador de scripts JS obuscados tipo Dean Edwards (eval(function(p,a,c,k,e,d)...))
 */
object JsUnpacker {
    private val PACKED_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun unpack(script: String): String {
        val match = PACKED_REGEX.find(script) ?: return script
        val (p, aStr, cStr, kStr) = match.destructured
        var payload = p
        val a = aStr.toIntOrNull() ?: 10
        val c = cStr.toIntOrNull() ?: 0
        val k = kStr.split("|")

        fun getNth(n: Int): String {
            val prefix = if (n >= a) getNth(n / a) else ""
            val remainder = n % a
            val char = if (remainder > 35) (remainder + 29).toChar() else remainder.toString(36)
            return prefix + char
        }

        var count = c
        while (count > 0) {
            count--
            val word = if (count < k.size && k[count].isNotEmpty()) k[count] else getNth(count)
            payload = payload.replace(Regex("\\b${getNth(count)}\\b"), word)
        }
        return payload
    }
}

object SeriesFavCatalogProvider : Provider {

    override val name = "SeriesFav Catalog"
    override val baseUrl =
        "https://gist.githubusercontent.com/keiriblest/4634024c08cf2c794b4c6aa0b68ce7e8/raw/series-datos%20(1).json"
    override val logo = "https://keiriblest.github.io/SeriesFav/desktop/favicon.ico"
    override val language = "es"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private data class ParsedEpisode(
        val seasonNum: Int,
        val episodeNum: Int,
        val url: String
    )

    private data class SeriesFavEntry(
        val titulo: String,
        val imgURL: String?,
        val temporada: String?,
        val descripcion: String?,
        val plataforma: String?,
        val seccion: String?,
        val fecha: String?,
        val trailerURL: String?,
        val isMovie: Boolean,
        val entrySeasonNum: Int,
        val directUrls: List<String>,
        val episodesList: List<ParsedEpisode>
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            .build()
    }

    private var cache: List<SeriesFavEntry> = emptyList()

    private suspend fun fetchCatalog(): List<SeriesFavEntry> {
        if (cache.isNotEmpty()) return cache

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl?t=${System.currentTimeMillis()}")
                .get()
                .build()

            val body = runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                }
            }.getOrNull()

            if (body.isNullOrBlank()) return@withContext emptyList()

            val entries = parseJsonToEntries(body)
            if (entries.isNotEmpty()) {
                cache = entries
            }
            entries
        }
    }

    private fun isMovieCheck(tipo: String?, seccion: String?, plataforma: String?, temporada: String?): Boolean {
        val t = tipo?.lowercase().orEmpty()
        val s = seccion?.lowercase().orEmpty()
        val p = plataforma?.lowercase().orEmpty()
        val temp = temporada?.lowercase().orEmpty()

        return t == "pelicula" || t == "peli" ||
               s.contains("peli") || p.contains("peli") ||
               temp.contains("peli")
    }

    private fun parseSeasonNumber(raw: String?): Int {
        if (raw.isNullOrBlank()) return 1
        val digits = raw.replace(Regex("[^0-9]"), "")
        return digits.toIntOrNull() ?: 1
    }

    private fun parseEpisodeNumber(raw: String?): Int {
        if (raw.isNullOrBlank()) return 1
        val digits = raw.replace(Regex("[^0-9]"), "")
        return digits.toIntOrNull() ?: 1
    }

    private fun parseJsonToEntries(rawJson: String): List<SeriesFavEntry> {
        return runCatching {
            val jsonElement = Json.parseToJsonElement(rawJson)

            val array = when (jsonElement) {
                is JsonArray -> jsonElement
                is JsonObject -> {
                    jsonElement.values.firstOrNull { it is JsonArray } as? JsonArray ?: JsonArray(emptyList())
                }
                else -> JsonArray(emptyList())
            }

            array.mapNotNull { item ->
                if (item !is JsonObject) return@mapNotNull null

                fun JsonObject.getFlexString(vararg keys: String): String? {
                    for (key in keys) {
                        val value = this[key] ?: continue
                        if (value is JsonPrimitive) {
                            val content = value.content.trim()
                            if (content.isNotBlank() && content != "null") {
                                return content
                            }
                        }
                    }
                    return null
                }

                val titulo = item.getFlexString("titulo", "title", "name") ?: return@mapNotNull null
                val imgURL = item.getFlexString("imgURL", "imgUrl", "poster", "image", "banner")
                val temporada = item.getFlexString("temporada", "season")
                val descripcion = item.getFlexString("descripcion", "overview", "description")
                val plataforma = item.getFlexString("plataforma", "platform")
                val seccion = item.getFlexString("seccion", "section", "category")
                val fecha = item.getFlexString("fecha", "date", "year")
                val trailerURL = item.getFlexString("trailerURL", "trailerUrl", "trailer")
                val tipo = item.getFlexString("tipo", "type")

                val directUrls = mutableListOf<String>()
                val parsedEpisodes = mutableListOf<ParsedEpisode>()

                val verURLObj = item["verURL"] ?: item["verUrl"] ?: item["url"] ?: item["streamUrl"]
                when (verURLObj) {
                    is JsonPrimitive -> {
                        val str = verURLObj.content.trim()
                        if (str.isNotBlank()) directUrls.add(str)
                    }
                    is JsonArray -> {
                        for (element in verURLObj) {
                            when (element) {
                                is JsonPrimitive -> {
                                    val str = element.content.trim()
                                    if (str.isNotBlank()) directUrls.add(str)
                                }
                                is JsonObject -> {
                                    val epUrl = element.getFlexString("url", "link", "verURL", "verUrl")
                                    if (!epUrl.isNullOrBlank()) {
                                        val sNum = parseSeasonNumber(element.getFlexString("temporada", "season") ?: temporada)
                                        val eNum = parseEpisodeNumber(element.getFlexString("capitulo", "episode"))
                                        parsedEpisodes.add(ParsedEpisode(sNum, eNum, epUrl))
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> {}
                }

                val isExplicitMovie = isMovieCheck(tipo, seccion, plataforma, temporada)
                val isImplicitMovie = parsedEpisodes.isEmpty() && directUrls.isNotEmpty() && (temporada.isNullOrBlank() || temporada.lowercase() == "null")
                val isMovie = isExplicitMovie || isImplicitMovie

                val entrySeasonNum = parseSeasonNumber(temporada)

                SeriesFavEntry(
                    titulo = titulo,
                    imgURL = imgURL,
                    temporada = temporada,
                    descripcion = descripcion,
                    plataforma = plataforma,
                    seccion = seccion,
                    fecha = fecha,
                    trailerURL = trailerURL,
                    isMovie = isMovie,
                    entrySeasonNum = entrySeasonNum,
                    directUrls = directUrls,
                    episodesList = parsedEpisodes
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun generateShowId(cleanTitle: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(cleanTitle.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "seriesfav:$digest"
    }

    private fun unwrapUrl(rawUrl: String): String {
        var clean = rawUrl.trim()
        if (clean.startsWith("aHR0c")) {
            clean = runCatching {
                String(Base64.decode(clean, Base64.DEFAULT)).trim()
            }.getOrDefault(clean)
        }

        val httpUrl = clean.toHttpUrlOrNull() ?: return clean
        httpUrl.queryParameter("url")?.let { inner ->
            return runCatching { URLDecoder.decode(inner, "UTF-8") }.getOrDefault(clean)
        }
        return clean
    }

    private fun extractHostLabel(value: String): String {
        return runCatching {
            val host = value.toHttpUrlOrNull()?.host.orEmpty()
                .removePrefix("www.")
                .substringBefore(".")
            when {
                host.contains("goodstream", ignoreCase = true) -> "Goodstream"
                host.contains("ok", ignoreCase = true) -> "Ok.ru"
                host.contains("minochinos", ignoreCase = true) -> "Minochinos"
                host.contains("callistanise", ignoreCase = true) -> "Callistanise"
                host.contains("drive.google", ignoreCase = true) -> "Google Drive"
                host.contains("vimeos", ignoreCase = true) -> "Vimeos"
                host.contains("voe", ignoreCase = true) -> "VOE"
                host.contains("rpmvid", ignoreCase = true) || host.contains("cubeembed", ignoreCase = true) -> "CubeEmbed"
                host.contains("cine-seguro", ignoreCase = true) -> "CineSeguro"
                host.contains("sendvid", ignoreCase = true) -> "Sendvid"
                host.contains("hglink", ignoreCase = true) -> "HGLink"
                host.isBlank() -> "Servidor"
                else -> host.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }.getOrDefault("Servidor")
    }

    override suspend fun getHome(): List<Category> {
        val entries = fetchCatalog()
        if (entries.isEmpty()) return emptyList()

        val categories = mutableListOf<Category>()

        val movieEntries = entries.filter { it.isMovie }
        if (movieEntries.isNotEmpty()) {
            val moviesList = movieEntries.distinctBy { it.titulo.lowercase().trim() }.map { createMovieObject(it) }
            categories.add(Category("Películas", moviesList))
        }

        val seriesEntries = entries.filter { !it.isMovie }
        val groupedSeries = seriesEntries.groupBy { entry ->
            entry.seccion?.takeIf { it.isNotBlank() && !it.lowercase().contains("peli") }
                ?: entry.plataforma?.takeIf { it.isNotBlank() }
                ?: "Catálogo Series"
        }

        for ((sectionName, sectionItems) in groupedSeries) {
            val seriesList = groupEntriesToItems(sectionItems).filterIsInstance<TvShow>()
            if (seriesList.isNotEmpty()) {
                categories.add(Category(sectionName, seriesList))
            }
        }

        return categories
    }

    private fun groupEntriesToItems(entries: List<SeriesFavEntry>): List<AppAdapter.Item> {
        val movies = entries.filter { it.isMovie }.distinctBy { it.titulo.lowercase().trim() }.map { createMovieObject(it) }

        val seriesGrouped = entries.filter { !it.isMovie }
            .groupBy { it.titulo.lowercase().trim() }
            .map { (_, sameTitleEntries) -> createTvShowObject(sameTitleEntries) }

        return movies + seriesGrouped
    }

    private fun createMovieObject(entry: SeriesFavEntry): Movie {
        return Movie(
            id = generateShowId(entry.titulo),
            title = entry.titulo,
            overview = entry.descripcion,
            released = entry.fecha?.takeIf { it.isNotBlank() },
            trailer = entry.trailerURL,
            poster = entry.imgURL,
            banner = entry.imgURL
        ).apply { providerName = name }
    }

    private fun createTvShowObject(sameTitleEntries: List<SeriesFavEntry>): TvShow {
        val first = sameTitleEntries.first()
        val showId = generateShowId(first.titulo)

        val seasonNumbers = mutableSetOf<Int>()
        for (entry in sameTitleEntries) {
            if (entry.episodesList.isNotEmpty()) {
                seasonNumbers.addAll(entry.episodesList.map { it.seasonNum })
            } else {
                seasonNumbers.add(entry.entrySeasonNum)
            }
        }

        val seasons = seasonNumbers.sorted().map { sNum ->
            val matchingEntry = sameTitleEntries.firstOrNull { 
                it.entrySeasonNum == sNum || it.episodesList.any { ep -> ep.seasonNum == sNum } 
            }
            val seasonPoster = matchingEntry?.imgURL?.takeIf { it.isNotBlank() } ?: first.imgURL

            Season(
                id = "$showId:s$sNum",
                number = sNum,
                title = "Temporada $sNum",
                poster = seasonPoster
            )
        }

        return TvShow(
            id = showId,
            title = first.titulo,
            overview = first.descripcion,
            released = first.fecha?.takeIf { it.isNotBlank() },
            trailer = first.trailerURL,
            poster = first.imgURL,
            banner = first.imgURL,
            seasons = seasons
        ).apply { providerName = name }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val needle = query.trim().lowercase()
        val filtered = fetchCatalog().filter {
            it.titulo.lowercase().contains(needle) || it.descripcion?.lowercase()?.contains(needle) == true
        }
        return groupEntriesToItems(filtered)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        if (page > 1) return emptyList()
        return fetchCatalog().filter { it.isMovie }.distinctBy { it.titulo.lowercase().trim() }.map { createMovieObject(it) }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return fetchCatalog().filter { !it.isMovie }
            .groupBy { it.titulo.lowercase().trim() }
            .map { (_, list) -> createTvShowObject(list) }
    }

    override suspend fun getMovie(id: String): Movie {
        val cleanId = id.substringBefore(":s").substringBefore(":ep")
        val entry = fetchCatalog().firstOrNull { generateShowId(it.titulo) == cleanId }
            ?: throw NoSuchElementException("Película no encontrada")
        return createMovieObject(entry)
    }

    override suspend fun getTvShow(id: String): TvShow {
        val cleanId = id.substringBefore(":s").substringBefore(":ep")
        val entries = fetchCatalog().filter { generateShowId(it.titulo) == cleanId }
        if (entries.isEmpty()) throw NoSuchElementException("Serie no encontrada")
        return createTvShowObject(entries)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val cleanId = seasonId.substringBefore(":s").substringBefore(":ep")
        val targetSeasonNum = seasonId.substringAfter(":s", "1").substringBefore(":ep").toIntOrNull() ?: 1

        val entries = fetchCatalog().filter { generateShowId(it.titulo) == cleanId }
        if (entries.isEmpty()) return emptyList()

        val episodeList = mutableListOf<Episode>()

        for (entry in entries) {
            val matchingEps = entry.episodesList.filter { it.seasonNum == targetSeasonNum }
            if (matchingEps.isNotEmpty()) {
                for (ep in matchingEps) {
                    episodeList.add(
                        Episode(
                            id = "$cleanId:s${targetSeasonNum}:ep${ep.episodeNum}",
                            number = ep.episodeNum,
                            title = "Capítulo ${ep.episodeNum}",
                            overview = entry.descripcion,
                            poster = entry.imgURL
                        )
                    )
                }
            } else if (entry.entrySeasonNum == targetSeasonNum && entry.directUrls.isNotEmpty()) {
                episodeList.add(
                    Episode(
                        id = "$cleanId:s${targetSeasonNum}:ep1",
                        number = 1,
                        title = entry.titulo,
                        overview = entry.descripcion,
                        poster = entry.imgURL
                    )
                )
            }
        }

        return episodeList.distinctBy { it.number }.sortedBy { it.number }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        throw UnsupportedOperationException("SeriesFav Catalog no soporta géneros")
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("SeriesFav Catalog no soporta personas")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val cleanId = id.substringBefore(":s").substringBefore(":ep")
        val entries = fetchCatalog().filter { generateShowId(it.titulo) == cleanId }
        if (entries.isEmpty()) return emptyList()

        val rawUrls = mutableListOf<Pair<String, String?>>()

        if (id.contains(":ep")) {
            val targetSeasonNum = id.substringAfter(":s").substringBefore(":ep").toIntOrNull() ?: 1
            val targetEpNum = id.substringAfter(":ep").toIntOrNull() ?: 1

            for (entry in entries) {
                val matchingEps = entry.episodesList.filter { it.seasonNum == targetSeasonNum && it.episodeNum == targetEpNum }
                for (ep in matchingEps) {
                    rawUrls.add(ep.url to entry.plataforma)
                }

                if (matchingEps.isEmpty() && entry.entrySeasonNum == targetSeasonNum) {
                    for (url in entry.directUrls) {
                        rawUrls.add(url to entry.plataforma)
                    }
                }
            }
        } else {
            for (entry in entries) {
                for (url in entry.directUrls) {
                    rawUrls.add(url to entry.plataforma)
                }
            }
        }

        return rawUrls.distinctBy { it.first }.mapIndexed { index, (url, platform) ->
            val cleanUrl = unwrapUrl(url)
            val hostLabel = extractHostLabel(cleanUrl)
            val nameLabel = if (!platform.isNullOrBlank()) "$platform - $hostLabel ${index + 1}" else "$hostLabel ${index + 1}"

            Video.Server(
                id = cleanUrl,
                name = nameLabel,
                src = cleanUrl
            )
        }
    }

    /**
     * Resolutor multitarget con Desempaquetador JS e inspección profunda de HTML/Iframes
     */
    private suspend fun resolveCustomStreamUrl(targetUrl: String): String = withContext(Dispatchers.IO) {
        val cleanUrl = targetUrl.trim()
        if (cleanUrl.isBlank()) return@withContext targetUrl

        // 1. Google Drive
        if (cleanUrl.contains("drive.google.com")) {
            val fileId = Regex("""/file/d/([a-zA-Z0-9_-]+)""").find(cleanUrl)?.groupValues?.get(1)
            if (!fileId.isNullOrBlank()) {
                return@withContext "https://drive.google.com/uc?export=download&id=$fileId"
            }
        }

        // 2. Ok.ru (Procesamiento API + Extracción JSON)
        if (cleanUrl.contains("ok.ru")) {
            val videoId = cleanUrl.substringAfter("videoembed/").substringAfter("video/").substringBefore("?").substringBefore("/")
            if (videoId.isNotBlank() && videoId.all { it.isDigit() }) {
                runCatching {
                    val metaUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"
                    val req = Request.Builder().url(metaUrl).post(FormBody.Builder().build()).build()
                    val resp = client.newCall(req).execute().use { it.body?.string() }
                    if (!resp.isNullOrBlank()) {
                        val jsonElem = Json.parseToJsonElement(resp)
                        val videos = jsonElem.jsonObject["videos"]?.jsonArray
                        val highestVideo = videos?.lastOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
                        if (!highestVideo.isNullOrBlank()) return@withContext highestVideo
                    }
                }
            }
        }

        // 3. Extracción general con bypass de Referer, desobfuscación JS e inspección de Iframes
        return@withContext runCatching {
            val baseHost = cleanUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: cleanUrl
            
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", baseHost)
                .get()
                .build()

            val rawHtml = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (rawHtml.isBlank()) return@runCatching cleanUrl

            // Desempaqueta scripts JS si están comprimidos con Dean Edwards Packer
            val html = JsUnpacker.unpack(rawHtml)

            // Extractor específico para VOE
            if (cleanUrl.contains("voe.sx") || cleanUrl.contains("/e/")) {
                val voeMatch = Regex("""'hls':\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
                    ?: Regex("""const\s+sources\s*=\s*\{[^}]*["']?hls["']?\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                if (!voeMatch.isNullOrBlank()) {
                    return@runCatching if (voeMatch.startsWith("aHR0c")) String(Base64.decode(voeMatch, Base64.DEFAULT)) else voeMatch
                }
            }

            // Búsqueda de enlaces directos de vídeo .m3u8 o .mp4
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!m3u8Match.isNullOrBlank()) return@runCatching m3u8Match

            val mp4Match = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)?.value
            if (!mp4Match.isNullOrBlank()) return@runCatching mp4Match

            val fileMatch = Regex("""file\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!fileMatch.isNullOrBlank()) return@runCatching fileMatch

            // Si hay un iframe anidado, lo inspecciona recursivamente
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank() && !iframeSrc.startsWith("about:") && iframeSrc != cleanUrl) {
                val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                val iframeReq = Request.Builder().url(fullIframeUrl).header("Referer", cleanUrl).get().build()
                val iframeHtml = client.newCall(iframeReq).execute().use { it.body?.string().orEmpty() }
                val unpackedIframe = JsUnpacker.unpack(iframeHtml)

                val subM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(unpackedIframe)?.value
                if (!subM3u8.isNullOrBlank()) return@runCatching subM3u8

                val subMp4 = Regex("""https?://[^\s"'<>]+\.mp4[^\s"'<>]*""").find(unpackedIframe)?.value
                if (!subMp4.isNullOrBlank()) return@runCatching subMp4
            }

            cleanUrl
        }.getOrDefault(cleanUrl)
    }

    override suspend fun getVideo(server: Video.Server): Video {
        val resolved = resolveCustomStreamUrl(server.src)

        return runCatching {
            Extractor.extract(server.src, server)
        }.getOrElse {
            Video(source = resolved)
        }
    }
}
