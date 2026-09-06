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
import com.streamflixreborn.streamflix.utils.DnsResolver
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * SeriesFav Catalog
 *
 * Catalog-only provider for keiriblest's SeriesFav gist.
 *
 * IMPORTANT / LIMITATION BY DESIGN:
 * This provider intentionally IGNORES the "verURL" field present in the
 * source JSON. It never reads, stores, maps or exposes any playback URL
 * from that field. As a direct consequence:
 *   - getEpisodesBySeason() always returns an empty list.
 *   - getServers() always returns an empty list.
 *   - getVideo() always throws, because it must never be called
 *     (there are no servers to select from).
 *
 * This provider only surfaces catalog metadata: title, poster/banner,
 * description, platform, section, date and trailer.
 */
object SeriesFavCatalogProvider : Provider {

    override val name = "SeriesFav Catalog"
    override val baseUrl =
        "https://gist.githubusercontent.com/keiriblest/4634024c08cf2c794b4c6aa0b68ce7e8/raw/series-datos%20(1).json"
    override val logo = "https://keiriblest.github.io/SeriesFav/desktop/favicon.ico"
    override val language = "es"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    @Serializable
    private data class SeriesFavEntry(
        val titulo: String = "",
        val imgURL: String? = null,
        val temporada: String? = null,
        val descripcion: String? = null,
        val plataforma: String? = null,
        val seccion: String? = null,
        val fecha: String? = null,
        val trailerURL: String? = null,
        // "verURL" is deliberately NOT declared here.
        // With ignoreUnknownKeys = true, it is skipped entirely during parsing
        // and never becomes available anywhere in this provider.
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
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

    private var cache: List<SeriesFavEntry>? = null

    private fun fetchCatalog(): List<SeriesFavEntry> {
        cache?.let { return it }

        val request = Request.Builder()
            .url(baseUrl)
            .get()
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }

        if (body.isBlank()) return emptyList()

        val entries = runCatching {
            json.decodeFromString<List<SeriesFavEntry>>(body)
        }.getOrDefault(emptyList())

        cache = entries
        return entries
    }

    private fun SeriesFavEntry.toId(): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(titulo.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "seriesfav:$digest"
    }

    private fun SeriesFavEntry.toTvShow(): TvShow {
        return TvShow(
            id = toId(),
            title = titulo,
            overview = descripcion,
            released = fecha?.takeIf { it.isNotBlank() },
            trailer = trailerURL,
            poster = imgURL,
            banner = imgURL,
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
                ?: "SeriesFav Catalog"
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
        throw UnsupportedOperationException("SeriesFav Catalog only provides TV shows")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val entry = fetchCatalog().firstOrNull { it.toId() == id }
            ?: throw NoSuchElementException("SeriesFav Catalog: show not found")
        return entry.toTvShow()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        // "verURL" (temporadas/capitulos/URLs) is intentionally never read.
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        throw UnsupportedOperationException("SeriesFav Catalog does not support genres")
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw UnsupportedOperationException("SeriesFav Catalog does not support people")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        // No playback sources are ever exposed by this provider.
        return emptyList()
    }

    override suspend fun getVideo(server: Video.Server): Video {
        throw UnsupportedOperationException(
            "SeriesFav Catalog never returns servers, so getVideo() must never be called"
        )
    }
}
