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
 * SeriesFav Provider (Con soporte completo de reproducción)
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
        val verURL: String? = null // ✅ Declarado para incluir las URLs de reproducción
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
        val showId = toId()
        // Creamos una temporada por defecto basada en los datos
        val seasonNumber = temporada?.filter { it.isDigit() }?.toIntOrNull() ?: 1
        
        val seasonsList = listOf(
            Season(
                id = "$showId:s$seasonNumber",
                number = seasonNumber,
                title = temporada ?: "Temporada $seasonNumber"
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
        throw UnsupportedOperationException("SeriesFav Catalog solo ofrece series")
    }

    override suspend fun getTvShow(id: String): TvShow {
        val entry = fetchCatalog().firstOrNull { it.toId() == id }
            ?: throw NoSuchElementException("SeriesFav Catalog: Serie no encontrada")
        return entry.toTvShow()
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val showId = seasonId.substringBefore(":s")
        val entry = fetchCatalog().firstOrNull { it.toId() == showId } ?: return emptyList()
        
        // Si no existe verURL, no hay enlaces de reproducción
        val url = entry.verURL?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Genera el episodio principal utilizando verURL como identificador del servidor
        return listOf(
            Episode(
                id = "$seasonId:ep1",
                number = 1,
                title = "${entry.titulo} - Ver en línea",
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
        // Obtenemos la serie por su ID
        val showId = id.substringBefore(":s")
        val entry = fetchCatalog().firstOrNull { it.toId() == showId } ?: return emptyList()

        val playbackUrl = entry.verURL?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Devolvemos el servidor de vídeo con la URL encontrada
        return listOf(
            Video.Server(
                id = playbackUrl,
                name = entry.plataforma?.takeIf { it.isNotBlank() } ?: "Servidor Principal"
            )
        )
    }

    override suspend fun getVideo(server: Video.Server): Video {
        // Devuelve el objeto Video listo para reproducir desde verURL
        return Video(
            url = server.id,
            quality = Video.Quality.QUALITY_1080P
        )
    }
}
