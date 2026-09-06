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

    private data class SeriesFavEntry(
        val titulo: String,
        val imgURL: String?,
        val temporada: String?,
        val descripcion: String?,
        val plataforma: String?,
        val seccion: String?,
        val fecha: String?,
        val trailerURL: String?,
        val verURL: String?
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
                val verURL = item.getFlexString("verURL", "verUrl", "url", "streamUrl", "link")

                SeriesFavEntry(
                    titulo = titulo,
                    imgURL = imgURL,
                    temporada = temporada,
                    descripcion = descripcion,
                    plataforma = plataforma,
                    seccion = seccion,
                    fecha = fecha,
                    trailerURL = trailerURL,
                    verURL = verURL
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun SeriesFavEntry.toId(): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(titulo.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "seriesfav:$digest"
    }

    private fun SeriesFavEntry.toTvShow(): TvShow {
        val showId = toId()
        val seasonTitle = temporada?.takeIf { it.isNotBlank() } ?: "Temporada 1"
        val seasonNum = seasonTitle.filter { it.isDigit() }.toIntOrNull() ?: 1

        val seasonsList = listOf(
            Season(
                id = "$showId:s$seasonNum",
                number = seasonNum,
                title = seasonTitle
            )
        )

        return TvShow(
            id = showId,
            title = titulo,
            overview = descripcion,
            released = fecha?.takeIf { it.isNotBlank() },
            trailer = trailerURL,
            poster = imgURL,
            banner = imgURL,
            seasons = seasonsList
        ).apply {
            providerName = name
        }
    }

    private fun matches(entry: SeriesFavEntry, query: String): Boolean {
        if (query.isBlank()) return true
        val needle = query.trim().lowercase()
        return entry.titulo.lowercase().contains(needle) ||
            entry.descripcion?.lowercase()?.contains(needle) == true
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
                list = sectionEntries.map { it.toTvShow() }
            )
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        return fetchCatalog()
            .filter { matches(it, query) }
            .map { it.toTvShow() }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getTvShows(page: Int): List<TvShow> {
        if (page > 1) return emptyList()
        return fetchCatalog().map { it.toTvShow() }
    }

    override suspend fun getMovie(id: String): Movie {
        throw UnsupportedOperationException("SeriesFav Catalog solo ofrece series")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val cleanId = id.substringBefore(":s").substringBefore(":ep")
        val entry = fetchCatalog().firstOrNull { it.toId() == cleanId }
            ?: throw NoSuchElementException("SeriesFav Catalog: Serie no encontrada")
        return entry.toTvShow()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val cleanId = seasonId.substringBefore(":s").substringBefore(":ep")
        val entry = fetchCatalog().firstOrNull { it.toId() == cleanId } ?: return emptyList()
        
        if (entry.verURL.isNullOrBlank()) return emptyList()

        return listOf(
            Episode(
                id = "$cleanId:ep1",
                number = 1,
                title = entry.titulo,
                poster = entry.imgURL
            )
        )
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        throw UnsupportedOperationException("SeriesFav Catalog no soporta géneros")
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("SeriesFav Catalog no soporta personas")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val cleanId = id.substringBefore(":s").substringBefore(":ep")
        val entry = fetchCatalog().firstOrNull { it.toId() == cleanId } ?: return emptyList()
        val playbackUrl = entry.verURL?.takeIf { it.isNotBlank() } ?: return emptyList()

        return listOf(
            Video.Server(
                id = playbackUrl,
                name = entry.plataforma?.takeIf { it.isNotBlank() } ?: "Servidor Principal"
            )
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Video(
            source = server.id
        )
    }
}
