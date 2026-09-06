package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
        val directUrl: String?,
        val episodesList: List<ParsedEpisode>
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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

    /**
     * Réplica exacta de la función esPelicula(item) de desktop.html
     */
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

                val isMovie = isMovieCheck(tipo, seccion, plataforma, temporada)
                val entrySeasonNum = parseSeasonNumber(temporada)

                var directUrl: String? = null
                val parsedEpisodes = mutableListOf<ParsedEpisode>()

                val verURLObj = item["verURL"] ?: item["verUrl"] ?: item["url"] ?: item["streamUrl"]
                when (verURLObj) {
                    is JsonPrimitive -> {
                        directUrl = verURLObj.content.trim().takeIf { it.isNotBlank() }
                    }
                    is JsonArray -> {
                        for (element in verURLObj) {
                            when (element) {
                                is JsonPrimitive -> {
                                    if (directUrl == null) directUrl = element.content.trim()
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
                    directUrl = directUrl,
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

    override suspend fun getHome(): List<Category> {
        val entries = fetchCatalog()
        if (entries.isEmpty()) return emptyList()

        val grouped = entries.groupBy { entry ->
            entry.seccion?.takeIf { it.isNotBlank() }
                ?: entry.plataforma?.takeIf { it.isNotBlank() }
                ?: "Catálogo SeriesFav"
        }

        return grouped.map { (sectionName, sectionEntries) ->
            Category(
                name = sectionName,
                list = groupEntriesToItems(sectionEntries)
            )
        }
    }

    /**
     * Agrupa entradas por título para consolidar temporadas y evitar fichas duplicadas
     */
    private fun groupEntriesToItems(entries: List<SeriesFavEntry>): List<AppAdapter.Item> {
        val movies = entries.filter { it.isMovie }.map { createMovieObject(it) }

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
            Season(
                id = "$showId:s$sNum",
                number = sNum,
                title = "Temporada $sNum"
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
        return fetchCatalog().filter { it.isMovie }.map { createMovieObject(it) }
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
            } else if (entry.entrySeasonNum == targetSeasonNum && !entry.directUrl.isNullOrBlank()) {
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

        if (id.contains(":ep")) {
            val targetSeasonNum = id.substringAfter(":s").substringBefore(":ep").toIntOrNull() ?: 1
            val targetEpNum = id.substringAfter(":ep").toIntOrNull() ?: 1

            for (entry in entries) {
                val epMatch = entry.episodesList.firstOrNull { it.seasonNum == targetSeasonNum && it.episodeNum == targetEpNum }
                if (epMatch != null) {
                    return listOf(
                        Video.Server(
                            id = epMatch.url,
                            name = entry.plataforma?.takeIf { it.isNotBlank() } ?: "Servidor Principal"
                        )
                    )
                } else if (entry.entrySeasonNum == targetSeasonNum && !entry.directUrl.isNullOrBlank()) {
                    return listOf(
                        Video.Server(
                            id = entry.directUrl,
                            name = entry.plataforma?.takeIf { it.isNotBlank() } ?: "Servidor Principal"
                        )
                    )
                }
            }
        } else {
            val movieEntry = entries.firstOrNull { !it.directUrl.isNullOrBlank() }
            if (movieEntry != null && movieEntry.directUrl != null) {
                return listOf(
                    Video.Server(
                        id = movieEntry.directUrl,
                        name = movieEntry.plataforma?.takeIf { it.isNotBlank() } ?: "Servidor Principal"
                    )
                )
            }
        }

        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Video(
            source = server.id
        )
    }
}
