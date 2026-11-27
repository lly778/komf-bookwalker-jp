package snd.komf.providers.bookwalker

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
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

/** Public DMM Books pages used as the primary source for manga. */
internal class DmmBooksFallback(private val http: HttpClient, private val config: ProviderConfig) {
    private val host = "book.dmm.com"
    private val base = "https://$host"
    private val idPrefix = "dmm-books-"
    private val productPath = Regex("/product/([0-9]{1,12})/([a-z0-9]{1,64})/")
    private val promotional = Regex("期間限定|試し読み|無料版|分冊版|単話版|[（(【]単話[）)】]|タテヨミ")
    private val trailingVolume = Regex("""(?:\s*(?:第\s*)?\([0-9]{1,4}(?:\.[0-9]+)?\)|\s+(?:第\s*)?[0-9]{1,4}(?:\.[0-9]+)?(?:\s*巻)?)$""")
    private val latestMarker = Regex("""\s*[（(]最新刊[）)]$""")
    private val slots = Semaphore(2)
    private val products = object : LinkedHashMap<String, Product>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Product>?) = size > 128
    }

    fun accepts(id: String) = id.startsWith(idPrefix)

    suspend fun search(
        title: String,
        exactTitles: List<String>,
        requestedEdition: PublicationEdition?,
        allowPrefix: Boolean,
    ): List<Hit> {
        if (title.isBlank() || title.length > 240 || "://" in title) return emptyList()
        val exact = exactTitles.map(::key).filter(String::isNotEmpty).toSet()
        val baseTitles = exactTitles.map { key(dmmTitleWithoutEdition(it)) }.filter(String::isNotEmpty).toSet()
        val query = normalize(title).lowercase(Locale.ROOT)
        val hits = fetch("$base/search/", title)?.select(".m-boxSearchListTmb__ttl").orEmpty()
            .mapNotNull { name ->
                val path = path(name.closest("a")?.attr("href").orEmpty()) ?: return@mapNotNull null
                val label = name.text().trim()
                if (label.isEmpty() || promotional.containsMatchIn(label)) return@mapNotNull null
                Hit(idPrefix + path.groupValues.drop(1).joinToString("-"), label, base + path.value)
            }.distinctBy { it.id }
        val matches = hits.mapNotNull { hit ->
            val listingKey = key(dmmTitleWithoutEdition(hit.title))
            val name = normalize(hit.title).lowercase(Locale.ROOT)
            val rank = when {
                key(hit.title) in exact -> 0
                listingKey in baseTitles -> 1
                listingKey.length >= 8 && baseTitles.any { it.startsWith(listingKey) } -> 2
                allowPrefix && query.length >= 8 && name.startsWith(query) &&
                    name.length > query.length && name[query.length] in " 〜~：:―─" -> 3
                else -> return@mapNotNull null
            }
            val product = path(hit.url)?.value?.let { load(it) } ?: return@mapNotNull null
            if (requestedEdition != null && publicationEdition(product.seriesTitle) != requestedEdition) {
                return@mapNotNull null
            }
            // Short store labels need the complete title from the product page.
            if (rank == 2 && key(dmmTitleWithoutEdition(product.seriesTitle)) !in baseTitles) return@mapNotNull null
            rank to hit.copy(title = product.seriesTitle)
        }
        val best = matches.minOfOrNull { it.first } ?: return emptyList()
        return matches.filter { it.first == best }.map { it.second }
    }

    suspend fun series(id: ProviderSeriesId): ProviderSeriesMetadata? {
        val reference = pathFromId(id.value)?.let { load(it) } ?: return null
        val seriesPath = path(reference.seriesUrl.orEmpty())?.takeIf { it.groupValues[1] == reference.seriesKey }
        val first = seriesPath?.value?.let { load(it) } ?: reference
        val title = SeriesTitle(first.seriesTitle, TitleType.NATIVE, "ja")
        val knownStatus = reference.status ?: first.status
        val latestForStatus = if (config.seriesMetadata.status && knownStatus != SeriesStatus.ENDED) {
            first.latestPath?.let { load(it) }
        } else null
        val books = collectVolumes(first, reference, latestForStatus)
        val seriesBooks = books.mapNotNull { volume ->
            val cid = path(volume.path)?.groupValues?.get(2) ?: return@mapNotNull null
            val number = mangaVolumeAfterTitle(first.seriesTitle, volume.title)
                ?: first.volumeNumber().takeIf { volume.path == first.path }
            SeriesBook(ProviderBookId(cid), number, volume.title, null, null)
        }.distinctBy { it.id }.sortedBy { it.number?.start ?: Double.MAX_VALUE }
        return MetadataConfigApplier.apply(
            ProviderSeriesMetadata(
                id,
                SeriesMetadata(
                    status = latestForStatus?.status ?: knownStatus,
                    title = title,
                    titles = listOf(title),
                    summary = first.summary,
                    publisher = first.publisher?.let { Publisher(it, PublisherType.ORIGINAL, "ja") },
                    readingDirection = ReadingDirection.RIGHT_TO_LEFT,
                    language = "ja",
                    tags = first.tags,
                    authors = first.authors,
                    totalBookCount = first.volumeCount ?: seriesBooks.size.takeIf { it > 0 },
                    releaseDate = first.release?.toReleaseDate(),
                    links = listOf(WebLink("DMM Books", base + first.path)),
                ),
                seriesBooks,
            ), config.seriesMetadata
        )
    }

    // DMM only renders the ten-volume page that contains the current product.
    private suspend fun collectVolumes(first: Product, reference: Product, latest: Product?): List<Volume> {
        val volumes = linkedMapOf<String, Volume>()
        fun merge(product: Product) {
            for (volume in product.volumes) {
                if (!volume.path.endsWith("/latest/")) volumes[volume.path] = volume
            }
            volumes[product.path] = Volume(product.path, product.title)
        }
        merge(first)
        merge(reference)
        latest?.let(::merge)
        val count = first.volumeCount ?: first.volumes.size
        withTimeoutOrNull(30_000) {
            if (volumes.size >= count) return@withTimeoutOrNull
            if (latest == null) first.latestPath?.let { load(it)?.let(::merge) }
            var page = first
            val visited = mutableSetOf(first.path)
            while (volumes.size < count) {
                val boundary = page.volumes.lastOrNull { !it.path.endsWith("/latest/") } ?: break
                val edge = load(boundary.path) ?: break
                val nextPath = edge.nextPath ?: break
                if (!visited.add(nextPath)) break
                page = load(nextPath) ?: break
                merge(page)
                if (page.volumes.isEmpty()) break
            }
        }
        return volumes.values.toList()
    }

    suspend fun book(seriesId: ProviderSeriesId, bookId: ProviderBookId): ProviderBookMetadata? {
        val seriesKey = path(pathFromId(seriesId.value).orEmpty())?.groupValues?.get(1) ?: return null
        val bookPath = path("/product/$seriesKey/${bookId.id}/")?.value ?: return null
        val data = load(bookPath) ?: return null
        val number = data.volumeNumber()
        return MetadataConfigApplier.apply(
            ProviderBookMetadata(
                bookId,
                seriesId,
                BookMetadata(
                    title = data.title,
                    summary = data.summary,
                    number = number,
                    numberSort = number?.start,
                    releaseDate = data.release,
                    authors = data.authors,
                    tags = data.tags,
                    links = listOf(WebLink("DMM Books", base + data.path)),
                ),
            ), config.bookMetadata
        )
    }

    private suspend fun load(requestedPath: String): Product? {
        val requested = path(requestedPath) ?: return null
        synchronized(products) { products[requestedPath] }?.takeUnless {
            System.nanoTime() - it.checkedAt > 30L * 60 * 1_000_000_000
        }?.let { return it }
        val document = fetch(base + requestedPath) ?: return null
        val structured = document.select("script[type=application/ld+json]")
            .mapNotNull { runCatching { Json.parseToJsonElement(it.data()) as? JsonObject }.getOrNull() }
        val product = structured.firstOrNull { it.string("@type") == "Product" } ?: return null
        val breadcrumbs = structured.firstOrNull { it.string("@type") == "BreadcrumbList" }
            ?.get("itemListElement") as? JsonArray
        val category = breadcrumbs?.getOrNull(1) as? JsonObject
        val categoryItem = category?.get("item") as? JsonObject
        val categoryName = categoryItem?.string("name") ?: category?.string("name")
        if (categoryName in setOf("文芸・ラノベ", "ライトノベル", "小説・文芸")) return null
        val seriesKey = requested.groupValues[1]
        val productUrl = path(product.string("url").orEmpty()) ?: return null
        if (productUrl.groupValues[1] != seriesKey) return null
        val canonical = if (productUrl.groupValues[2] == "latest" || requested.groupValues[2] == "latest") {
            path(document.selectFirst("""meta[property="og:url"]""")?.attr("content").orEmpty())
        } else productUrl
        if (canonical == null) return null
        if (canonical.groupValues[1] != seriesKey || canonical.groupValues[2] == "latest") return null
        if (requested.groupValues[2] != "latest" && requested.value != canonical.value) return null

        val title = product.string("name")?.replace(latestMarker, "") ?: return null
        if (promotional.containsMatchIn(title)) return null
        val book = product["subjectOf"] as? JsonObject ?: return null
        val fields = document.select("dl:has(>dt)").mapNotNull { entry ->
            val term = entry.selectFirst("dt")?.text() ?: return@mapNotNull null
            val element = entry.selectFirst("dd") ?: return@mapNotNull null
            term to element
        }.toMap()
        val imprint = fields["掲載誌・レーベル"]?.text()
        if (imprint.equals("GIGATOON", ignoreCase = true)) return null
        val seriesName = fields["シリーズ名"]?.text()?.takeIf(String::isNotBlank) ?: title
        val seriesTitle = resolveSeriesTitle(seriesName, title)
        val seriesUrl = (book["isPartOf"] as? JsonObject)?.string("url")
        val authors = people(book["author"]).map { Author(it, AuthorRole.WRITER) }
        val tags = (strings(book["genre"]) + listOfNotNull(imprint)).toSet()
        val volumes = document.select("""[data-testid="volume-book-list"] a[data-is-limited=false]""")
            .mapNotNull { link ->
                val path = path(link.attr("href"))?.takeIf { it.groupValues[1] == seriesKey } ?: return@mapNotNull null
                val name = link.text().replace(latestMarker, "")
                if (promotional.containsMatchIn(name) || mangaVolumeAfterTitle(seriesTitle, name) == null) null
                else Volume(path.value, name)
            }
        val volumeCount = document.select("""[data-testid="title-header"]""").firstOrNull { it.text().startsWith("シリーズ") }
            ?.text()?.let { Regex("""([0-9]+)\s*冊""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val navigation = document.select("a[href]:has(>span)").mapNotNull { anchor ->
            val label = anchor.text()
            if (label !in setOf("次の巻", "最新刊")) return@mapNotNull null
            val target = path(anchor.attr("href")) ?: return@mapNotNull null
            if (target.groupValues[1] != seriesKey) return@mapNotNull null
            label to target.value
        }.toMap()
        val data = Product(
            canonical.value, seriesKey, title, seriesTitle, seriesUrl, product.string("description"),
            (book["publisher"] as? JsonObject)?.string("name") ?: product.string("brand"),
            book.string("dateCreated")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            authors, tags, volumes, volumeCount,
            navigation["次の巻"], navigation["最新刊"] ?: volumes.firstOrNull { it.path.endsWith("/latest/") }?.path,
            publicationStatus(
                document.select("""[data-testid="detail-book"] [data-testid="status-label"] span""")
                    .map { it.text() },
            ),
        )
        synchronized(products) {
            products[data.path] = data
            products[requestedPath] = data
        }
        return data
    }

    private fun resolveSeriesTitle(listing: String, book: String): String {
        val base = dmmTitleWithoutEdition(listing)
        val edition = publicationEdition(book) ?: publicationEdition(listing)
        val listingWithEdition = when {
            edition == null || publicationEdition(listing) == edition && withoutEditionMarker(listing) != listing -> listing
            else -> "$base【" + edition.label + "】"
        }
        if (edition == PublicationEdition.SPECIAL || mangaVolumeAfterTitle(listingWithEdition, book) != null) {
            return listingWithEdition
        }

        val bookTitle = dmmTitleWithoutEdition(book).replace(trailingVolume, "").trimEnd()
        val baseKey = key(base)
        val bookKey = key(bookTitle)
        if (bookKey.length <= baseKey.length || !bookKey.startsWith(baseKey)) return listingWithEdition
        return edition?.let { "$bookTitle【" + it.label + "】" } ?: bookTitle
    }

    private fun Product.volumeNumber(): BookRange? = mangaVolumeAfterTitle(seriesTitle, title)
        ?: BookRange(1).takeIf { key(seriesTitle) == key(title) && seriesUrl?.let { path(it)?.value } == path }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim()
    private fun key(value: String) = normalize(value).lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun pathFromId(id: String): String? {
        if (!accepts(id)) return null
        val (series, cid) = id.removePrefix(idPrefix).split('-', limit = 2).takeIf { it.size == 2 } ?: return null
        return path("/product/$series/$cid/")?.value
    }

    private fun path(url: String): MatchResult? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.isAbsolute && (uri.scheme != "https" || uri.rawAuthority != host)) return null
        if (uri.rawAuthority != null && uri.rawAuthority != host) return null
        return productPath.matchEntire(uri.rawPath.orEmpty())
    }

    private suspend fun fetch(url: String, title: String? = null): Document? = try {
        slots.withPermit {
            withTimeoutOrNull(8_000) {
                val response = http.get(url) {
                    cookie("book_safe_mode_level", "off")
                    if (title != null) parameter("searchstr", title)
                }
                if (response.status.value == 200 && response.call.request.url.host == host) {
                    Ksoup.parse(response.bodyAsText())
                } else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun strings(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.flatMap(::strings)
        else -> emptyList()
    }

    private fun people(value: JsonElement?): List<String> = when (value) {
        is JsonObject -> strings(value["name"])
        is JsonArray -> value.flatMap(::people).distinct()
        else -> strings(value)
    }.filter { it.isNotBlank() && it !in setOf("他", "ほか") }

    data class Hit(val id: String, val title: String, val url: String)
    private data class Volume(val path: String, val title: String)
    private data class Product(
        val path: String,
        val seriesKey: String,
        val title: String,
        val seriesTitle: String,
        val seriesUrl: String?,
        val summary: String?,
        val publisher: String?,
        val release: LocalDate?,
        val authors: List<Author>,
        val tags: Set<String>,
        val volumes: List<Volume>,
        val volumeCount: Int?,
        val nextPath: String?,
        val latestPath: String?,
        val status: SeriesStatus?,
        val checkedAt: Long = System.nanoTime(),
    )
}

private val dmmBareEdition = Regex("""\s+(?:特装版|電子特別版)$""")

internal fun dmmTitleWithoutEdition(title: String): String =
    withoutEditionMarker(withoutVolumeBonus(Normalizer.normalize(title, Normalizer.Form.NFKC)))
        .replace(dmmBareEdition, "").trim()
