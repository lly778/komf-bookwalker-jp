package snd.komf.providers.bookwalker

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import snd.komf.model.*
import snd.komf.providers.MetadataConfigApplier
import snd.komf.providers.ProviderConfig
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/** Searches public eBookJapan listings after DMM Books and BookWalker Japan. */
internal class EbookJapanFallback(private val http: HttpClient, private val config: ProviderConfig) {
    private val host = "ebookjapan.yahoo.co.jp"
    private val base = "https://$host"
    private val idPrefix = "ebookjapan-"
    private val seriesNumber = Regex("[0-9]{1,12}")
    private val searchPath = Regex("""/books/([0-9]{1,12})/""")
    private val catalogPath = Regex("""/books/all/([0-9]{1,12})/""")
    private val productPath = Regex("""/books/([0-9]{1,12})/(A[0-9]{1,16})/""")
    private val excludedEdition = Regex("""分冊版|単話版|[【（(]単話[】）)]|タテヨミ|縦読み|試し読み版|期間限定無料版|無料お試し版""")
    private val authorSeparator = Regex("""[、／/]""")
    private val slots = Semaphore(2)
    private val catalogs = object : LinkedHashMap<String, Catalog>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Catalog>?) = size > 64
    }

    fun accepts(id: ProviderSeriesId) = numberOf(id) != null

    fun idFromUrl(url: String): ProviderSeriesId? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.rawAuthority != host) return null
        val path = uri.rawPath.orEmpty().trimEnd('/') + "/"
        val match = catalogPath.matchEntire(path) ?: searchPath.matchEntire(path) ?: productPath.matchEntire(path)
        return match?.groupValues?.get(1)?.let(::providerId)
    }

    suspend fun search(terms: List<String>, variants: List<String>, edition: PublicationEdition?): List<Hit> {
        val exactTitles = variants.map(::titleKey).filter(String::isNotEmpty).toSet()
        val baseTitles = variants.map { titleKey(withoutEditionMarker(it)) }.filter(String::isNotEmpty).toSet()
        for (term in terms.filter { it.isNotBlank() && it.length <= 150 }.distinct()) {
            val hits = fetchHits(term).filter { hit ->
                val candidateEdition = publicationEdition(hit.title)
                val compatible = candidateEdition == edition ||
                    (edition == null && candidateEdition == PublicationEdition.STANDARD)
                compatible && titleKey(withoutEditionMarker(hit.title)) in baseTitles
            }
            if (hits.isNotEmpty()) return hits.filter { titleKey(it.title) in exactTitles }.ifEmpty { hits }
        }
        return emptyList()
    }

    suspend fun series(id: ProviderSeriesId, hit: Hit? = null): ProviderSeriesMetadata? {
        val number = numberOf(id) ?: return null
        val catalog = catalog(number, hit) ?: return null
        val first = catalog.volumes.first()
        val details = details(number, first.id)
        val title = SeriesTitle(catalog.title, TitleType.NATIVE, "ja")
        return MetadataConfigApplier.apply(
            ProviderSeriesMetadata(
                id,
                SeriesMetadata(
                    title = title,
                    titles = listOf(title),
                    status = catalog.status,
                    summary = details?.summary,
                    publisher = (details?.publisher ?: first.publisher)
                        ?.let { Publisher(it, PublisherType.ORIGINAL, "ja") },
                    readingDirection = ReadingDirection.RIGHT_TO_LEFT,
                    language = "ja",
                    tags = listOfNotNull(catalog.label),
                    authors = details?.authors.orEmpty().ifEmpty { catalog.authors },
                    totalBookCount = catalog.volumes.size.takeIf { catalog.status == SeriesStatus.ENDED },
                    releaseDate = (details?.releaseDate ?: first.releaseDate)?.toReleaseDate(),
                    links = listOf(WebLink("eBookJapan", catalogUrl(number))),
                ),
                catalog.volumes.map { volume ->
                    SeriesBook(ProviderBookId(volume.id), volume.number, volume.title, null, null)
                },
            ),
            config.seriesMetadata,
        )
    }

    suspend fun book(seriesId: ProviderSeriesId, bookId: ProviderBookId): ProviderBookMetadata? {
        val number = numberOf(seriesId) ?: return null
        val catalog = catalog(number) ?: return null
        val volume = catalog.volumes.singleOrNull { it.id == bookId.id } ?: return null
        val details = details(number, volume.id)
        return MetadataConfigApplier.apply(
            ProviderBookMetadata(
                bookId,
                seriesId,
                BookMetadata(
                    title = volume.title,
                    number = volume.number,
                    numberSort = volume.number?.start,
                    summary = details?.summary,
                    releaseDate = details?.releaseDate ?: volume.releaseDate,
                    tags = setOfNotNull(catalog.label),
                    authors = details?.authors.orEmpty().ifEmpty { catalog.authors },
                    links = listOf(WebLink("eBookJapan", base + "/books/$number/" + volume.id + "/")),
                ),
            ),
            config.bookMetadata,
        )
    }

    private suspend fun fetchHits(term: String): List<Hit> =
        fetch("/search/", term)?.select("a.unification-title-item[href]").orEmpty().mapNotNull { entry ->
            val series = searchPath.matchEntire(entry.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = entry.selectFirst(".title-content__title")?.text()?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (excludedEdition.containsMatchIn(title)) return@mapNotNull null
            val authors = entry.selectFirst(".text-block__author")?.text().orEmpty().split(authorSeparator)
            Hit(
                providerId(series), title, catalogUrl(series), authors.toAuthors(),
                publicationStatus(entry.select(".title-content__setting-icon[alt]").map { it.attr("alt") }),
            )
        }.distinctBy { it.id }

    private suspend fun catalog(number: String, hit: Hit? = null): Catalog? {
        synchronized(catalogs) { catalogs[number] }
            ?.takeIf { it.checkedAt.elapsedNow() < 30.minutes }?.let { return it }
        val document = fetch("/books/all/$number/") ?: return null
        val title = document.selectFirst(".heading__main")?.text()?.takeIf(String::isNotBlank) ?: return null
        if (excludedEdition.containsMatchIn(title)) return null
        val edition = publicationEdition(title)
        if (hit != null && (titleKey(hit.title) != titleKey(title) || publicationEdition(hit.title) != edition)) return null
        val publications = catalogPublications(document, number)
        val volumes = document.select("a.list-item__content[href]").mapNotNull { entry ->
            val path = productPath.matchEntire(entry.attr("href")) ?: return@mapNotNull null
            if (path.groupValues[1] != number || entry.select(".book-item__diagonal-label").any { it.text() == "関連" }) {
                return@mapNotNull null
            }
            val bookTitle = entry.selectFirst("img[alt]")?.attr("alt")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (excludedEdition.containsMatchIn(bookTitle)) return@mapNotNull null
            val volumeEdition = publicationEdition(bookTitle)
            if (volumeEdition != null && volumeEdition != edition) return@mapNotNull null
            val status = publicationStatus(entry.select(".book-caption__tagtext").map { it.text() })
            val id = path.groupValues[2]
            val publication = publications[id]
            Volume(
                id, bookTitle, mangaVolumeAfterTitle(title, bookTitle), status,
                publication?.releaseDate, publication?.publisher,
            )
        }.distinctBy { it.id }
        if (volumes.isEmpty()) return null
        val numbered = if (volumes.size == 1 && volumes.single().number == null &&
            titleKey(volumes.single().title) == titleKey(title)
        ) {
            listOf(volumes.single().copy(number = BookRange(1)))
        } else volumes
        val source = hit ?: fetchHits(title).singleOrNull { it.id == providerId(number) }
        val label = document.selectFirst("a.breadcrumb-item__link[href^=/lists/labels/] [itemprop=name]")
            ?.text()?.takeIf(String::isNotBlank)
        return Catalog(
            title,
            numbered.sortedBy { it.number?.start ?: Double.MAX_VALUE },
            source?.authors.orEmpty(),
            source?.status ?: numbered.mapNotNull { it.status }.distinct().singleOrNull(),
            label,
        ).also { synchronized(catalogs) { catalogs[number] = it } }
    }

    /** The public volume list includes sale dates and publisher names in its Nuxt data. */
    private fun catalogPublications(document: Document, series: String): Map<String, Publication> {
        val script = document.selectFirst("script#__NUXT_DATA__") ?: return emptyMap()
        val data = runCatching { Json.parseToJsonElement(script.data()) as? JsonArray }.getOrNull()
            ?: return emptyMap()

        fun resolve(reference: JsonElement?): JsonElement? {
            val index = (reference as? JsonPrimitive)?.intOrNull ?: return null
            return data.getOrNull(index)
        }

        val publications = data.firstNotNullOfOrNull { entry ->
            val listing = (entry as? JsonObject) ?: return@firstNotNullOfOrNull null
            val titleId = (resolve(listing["titleId"]) as? JsonPrimitive)?.contentOrNull
            (resolve(listing["publications"]) as? JsonArray).takeIf { titleId == series }
        } ?: return emptyMap()

        return publications.mapNotNull { reference ->
            val item = resolve(reference) as? JsonObject ?: return@mapNotNull null
            val id = (resolve(item["publicationCd"]) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val date = (resolve(item["saleDate"]) as? JsonPrimitive)?.contentOrNull
                ?.substringBefore(' ')?.replace('/', '-')?.let(::parseDate)
            val publisher = resolve(item["publisher"]) as? JsonObject
            val name = (resolve(publisher?.get("name")) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            id to Publication(date, name)
        }.toMap()
    }

    private suspend fun details(series: String, item: String): Details? {
        val document = fetch("/books/$series/$item/") ?: return null
        val schema = document.select("script[type=application/ld+json]").firstNotNullOfOrNull { script ->
            val json = runCatching { Json.parseToJsonElement(script.data()) as? JsonObject }.getOrNull()
            json?.takeIf { it.string("@type") == "Book" }
        } ?: return null
        return Details(
            summary = schema.string("description"),
            publisher = (schema["publisher"] as? JsonObject)?.string("name"),
            authors = people(schema["author"]).toAuthors(),
            releaseDate = schema.string("datePublished")?.let(::parseDate),
        )
    }

    private suspend fun fetch(path: String, term: String? = null): Document? = try {
        slots.withPermit {
            withTimeoutOrNull(8_000) {
                val response = http.get(base + path) {
                    if (term != null) parameter("keyword", term)
                }
                val url = response.call.request.url
                if (response.status.value == 200 && url.host == host && url.encodedPath == path) {
                    Ksoup.parse(response.bodyAsText())
                } else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun numberOf(id: ProviderSeriesId): String? =
        id.value.takeIf { it.startsWith(idPrefix) }?.removePrefix(idPrefix)?.takeIf(seriesNumber::matches)

    private fun providerId(number: String) = ProviderSeriesId(idPrefix + number)
    private fun catalogUrl(number: String) = "$base/books/all/$number/"

    private fun titleKey(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun List<String>.toAuthors(): List<Author> =
        map(String::trim).filter { it.isNotEmpty() && it !in setOf("他", "ほか") }
            .distinct().map { Author(it, AuthorRole.WRITER) }

    private fun people(value: JsonElement?): List<String> = when (value) {
        is JsonObject -> listOfNotNull(value.string("name"))
        is JsonArray -> value.flatMap(::people)
        else -> emptyList()
    }

    data class Hit(val id: ProviderSeriesId, val title: String, val url: String, val authors: List<Author>, val status: SeriesStatus?)

    private data class Catalog(
        val title: String,
        val volumes: List<Volume>,
        val authors: List<Author>,
        val status: SeriesStatus?,
        val label: String?,
        val checkedAt: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow(),
    )

    private data class Volume(
        val id: String,
        val title: String,
        val number: BookRange?,
        val status: SeriesStatus?,
        val releaseDate: LocalDate?,
        val publisher: String?,
    )

    private data class Publication(val releaseDate: LocalDate?, val publisher: String?)

    private data class Details(
        val summary: String?,
        val publisher: String?,
        val authors: List<Author>,
        val releaseDate: LocalDate?,
    )
}
