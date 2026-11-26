package snd.komf.providers.bookwalker

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import snd.komf.model.*
import snd.komf.providers.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.text.Normalizer
import java.util.Locale

/** Searches the Japanese storefront with an optional local index. */
class BookWalkerMetadataProvider(
    private val config: ProviderConfig,
    private val httpClient: HttpClient,
) : MetadataProvider {
    private val db = if (System.getenv("KOMF_BOOKWALKER_JP_INDEX_ENABLED").equals("false", ignoreCase = true)) {
        null
    } else {
        Path.of(System.getenv("KOMF_BOOKWALKER_JP_DB") ?: "/config/bookwalker-jp/catalog.sqlite").toAbsolutePath()
    }
    private val category = if (config.mediaType == MediaType.NOVEL) "NOVEL" else "MANGA"
    private val fileSuffix = Regex("\\.(?:cbz|cbr|zip|epub|kfx-zip)$", RegexOption.IGNORE_CASE)
    private val archiveHash = Regex("\\s*\\[[0-9a-f]{12,64}]$", RegexOption.IGNORE_CASE)
    private val volumeAndLabel = Regex("\\s+(?:vol\\.?\\s*|第)?[0-9]{1,4}(?:巻)?(?:\\s*\\([^()]+\\))?$")
    private val parenthesizedVolume = Regex("\\s*\\([0-9]{1,4}\\)")
    private val subtitleSeparator = Regex("[~～〜：:]")
    private val trailingSubtitle = Regex("""[~〜]([^~〜]+)[~〜](?:\s*[【〖][^】〗]+[】〗])?$""")
    private val creditSuffix = Regex("[（(]([^（）()]+)[）)]$")
    private val dmmVolumeSuffix = Regex("""(?<=[】〗])[0-9]{1,4}(?:巻)?(?:\s*\([^()]+\))?$""")
    private val bookExtraAfterVolume = Regex("""(?:\s*[【〖](?:描きおろし|描き下ろし|かきおろし|書き下ろし|書きおろし)[^】〗]*[】〗])+(?:\s*\([^()]+\))?\s*$""")
    private val store = BookWalkerStoreSearch(httpClient, category == "MANGA")
    private val dmm = DmmBooksFallback(httpClient, config).takeIf { category == "MANGA" }
    private val ebookJapan = EbookJapanFallback(httpClient, config).takeIf { category == "MANGA" }
    private val allowedProduct = Regex("/de[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/?")
    private val jsonLd = Regex("""<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val detailsCache = object : LinkedHashMap<String, Details>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Details>?) = size > 128
    }

    override fun providerName() = CoreProviders.BOOK_WALKER

    override suspend fun searchSeries(seriesName: String, limit: Int): Collection<SeriesSearchResult> {
        ebookJapan?.idFromUrl(seriesName)?.let { id ->
            val match = ebookJapan.series(id) ?: return emptyList()
            val title = match.metadata.title?.name ?: return emptyList()
            return listOf(SeriesSearchResult(
                url = match.metadata.links.firstOrNull()?.url ?: seriesName,
                title = title, provider = providerName(), resultId = id.value,
            ))
        }
        val productId = store.productId(seriesName)
        if (productId != null) {
            val edition = resolveEdition(productId) ?: return emptyList()
            return listOf(edition.toSearchResult())
        }
        if ("://" in seriesName) return emptyList()
        val edition = publicationEdition(seriesName)
        val chapters = chapterEditionName.containsMatchIn(seriesName)
        val resultLimit = limit.coerceIn(1, 100)
        val dmmMatches = if (chapters) emptyList() else searchDmm(seriesName, edition = edition)
        if (dmmMatches.isNotEmpty()) return dmmMatches.take(resultLimit).map { it.toSearchResult() }

        val indexed = candidates(seriesName, limit).mapNotNull { record ->
            selectEdition(editions(record), edition, chapters)
        }
        val queryKeys = titleVariants(seriesName).map(::searchKey).toSet()
        val exact = indexed.filter { matchesTitle(it, seriesName, queryKeys) }
        if (exact.isNotEmpty()) return exact.map { it.toSearchResult() }
        val online = searchStore(seriesName).take(resultLimit).mapNotNull { entry ->
            selectEdition(editions(entry.id), edition, chapters)?.toSearchResult()
        }.distinctBy { it.resultId }
        if (online.isNotEmpty()) return online
        // Keep shortened DMM title suggestions available after exact BookWalker matches.
        if (!chapters && indexed.isEmpty()) {
            val suggestions = searchDmm(seriesName, allowPrefix = true, edition = edition)
            if (suggestions.isNotEmpty()) return suggestions.take(resultLimit).map { it.toSearchResult() }
        }
        val ebookJapanResults = if (chapters) emptyList() else searchEbookJapan(seriesName, edition)
        return ebookJapanResults.take(resultLimit).map { hit ->
            SeriesSearchResult(url = hit.url, title = hit.title, provider = providerName(), resultId = hit.id.value)
        }.ifEmpty { indexed.map { it.toSearchResult() } }
    }

    override suspend fun matchSeriesMetadata(matchQuery: MatchQuery): ProviderSeriesMetadata? {
        val title = matchQuery.seriesName
        val fileName = matchQuery.bookQualifier?.name.orEmpty()
        val edition = publicationEdition(fileName) ?: publicationEdition(title)
        val searchKeys = titleVariants(title).map(::searchKey).toSet()
        val chapters = chapterEditionName.containsMatchIn(fileName) || chapterEditionName.containsMatchIn(title)
        if (titleQueries(title).isEmpty() || "://" in title) return null
        if (!chapters) {
            val dmmMatches = searchDmm(title, edition = edition).ifEmpty {
                fileName.takeIf { it.isNotBlank() && it != title }?.let { searchDmm(it, edition = edition) }.orEmpty()
            }
            dmmMatches.singleOrNull()?.let { hit ->
                dmm?.series(ProviderSeriesId(hit.id))?.let { return it }
            }
        }

        val candidates = candidates(title, 25)
        val options = candidates.mapNotNull { record -> selectEdition(editions(record), edition, chapters) }
        val indexed = options.filter { matchesTitle(it, title, searchKeys) }.ifEmpty {
            options.filter { option ->
                val shortTitle = searchKey(option.title.split(subtitleSeparator, limit = 2).first())
                shortTitle.length >= 8 && shortTitle in searchKeys
            }
        }
        if (indexed.isNotEmpty()) return indexed.singleOrNull()?.let { getSeriesMetadata(it) }

        val online = searchStore(title).mapNotNull { entry ->
            selectEdition(editions(entry.id), edition, chapters)
        }.filter { matchesTitle(it, title, searchKeys) }
            .distinctBy { it.reference.url }
        if (online.isNotEmpty()) return online.singleOrNull()?.let { getSeriesMetadata(it) }

        if (chapters) return null
        val ebookJapanHit = searchEbookJapan(title, edition).ifEmpty {
            fileName.takeIf { it.isNotBlank() && it != title }?.let { searchEbookJapan(it, edition) }.orEmpty()
        }.singleOrNull() ?: return null
        return ebookJapan?.series(ebookJapanHit.id, ebookJapanHit)
    }

    private fun matchesTitle(edition: Edition, title: String, searchKeys: Set<String>): Boolean {
        if (searchKey(edition.title) in searchKeys) return true
        val (base, subtitle) = titleWithSubtitle(title) ?: return false
        if (searchKey(edition.title) != searchKey(base)) return false
        return edition.products.any { product ->
            titleWithSubtitle(product.title)?.second?.let(::normalize) == normalize(subtitle)
        }
    }

    private fun titleWithSubtitle(title: String): Pair<String, String>? {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).trim()
        val match = trailingSubtitle.find(normalized) ?: return null
        val base = normalized.substring(0, match.range.first).trimEnd()
        if (normalize(base).length < 8) return null
        return base to match.groupValues[1]
    }

    private fun Edition.toSearchResult() = SeriesSearchResult(
        url = reference.url, title = title, provider = providerName(), resultId = id,
    )

    private fun DmmBooksFallback.Hit.toSearchResult() =
        SeriesSearchResult(url = url, title = title, provider = providerName(), resultId = id)

    private suspend fun searchDmm(
        title: String,
        allowPrefix: Boolean = false,
        edition: PublicationEdition? = null,
    ): List<DmmBooksFallback.Hit> {
        val variants = titleVariants(title, includeEditionless = false)
        val query = variants.minByOrNull { normalize(it).length } ?: return emptyList()
        val storefrontQuery = query.replace('~', '〜').replace('～', '〜')
        for (searchTerm in listOf(query, storefrontQuery).distinct()) {
            val hits = dmm?.search(searchTerm, variants, ::searchKey, allowPrefix).orEmpty()
                .filter { edition == null || publicationEdition(it.title) == edition }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private suspend fun searchEbookJapan(title: String, edition: PublicationEdition?): List<EbookJapanFallback.Hit> {
        val variants = titleVariants(title, includeEditionless = false)
        val fullTitle = variants.minByOrNull { it.length } ?: return emptyList()
        val queries = listOf(fullTitle, withoutEditionMarker(fullTitle).trim())
        return ebookJapan?.search(queries, variants, edition).orEmpty()
    }

    private suspend fun searchStore(title: String): List<BookWalkerStoreSearch.Entry> {
        val variants = titleVariants(title)
        val cleaned = variants.minByOrNull { normalize(it).length } ?: return emptyList()
        return store.search(cleaned, variants.map(::searchKey).distinct(), ::searchKey)
    }

    override suspend fun getSeriesMetadata(seriesId: ProviderSeriesId): ProviderSeriesMetadata {
        if (ebookJapan?.accepts(seriesId) == true) {
            return requireNotNull(ebookJapan.series(seriesId)) { "eBookJapan series not found" }
        }
        if (dmm?.accepts(seriesId.value) == true) {
            return requireNotNull(dmm.series(seriesId)) { "DMM Books series not found" }
        }
        val edition = requireNotNull(resolveEdition(seriesId.value)) { "BookWalker Japan edition not found" }
        return getSeriesMetadata(edition)
    }

    internal suspend fun matchSavedEdition(
        seriesId: ProviderSeriesId,
        localFiles: Collection<String>,
    ): ProviderSeriesMetadata? {
        val requested = localFiles.mapNotNull(::publicationEdition).distinct().singleOrNull()
        if (ebookJapan?.accepts(seriesId) == true) {
            return ebookJapan.series(seriesId)?.takeIf {
                requested == null || publicationEdition(it.metadata.title?.name.orEmpty()) == requested
            }
        }
        if (dmm?.accepts(seriesId.value) == true) {
            return dmm.series(seriesId)?.takeIf {
                requested == null || publicationEdition(it.metadata.title?.name.orEmpty()) == requested
            }
        }
        val edition = if (requested == null) resolveEdition(seriesId.value)
            else selectEdition(editions(seriesId.value.substringBefore('@')), requested, chapters = false)
        return edition?.let { getSeriesMetadata(it) }
    }

    private suspend fun getSeriesMetadata(series: Edition): ProviderSeriesMetadata {
        val products = series.products
        val reference = series.reference
        val details = details(reference.url)
        val status = if (config.seriesMetadata.status) {
            details.status ?: products.maxWithOrNull(compareBy<Product>({ date(it.release) }, { it.volume }))
                ?.takeIf { it.url != reference.url }?.let { details(it.url).status }
        } else null
        val release = products.mapNotNull { date(it.release) }.minOrNull() ?: details.releaseDate
        val title = SeriesTitle(series.title, TitleType.NATIVE, "ja")
        val volumes = products.filter { it.volume != null }.groupBy { it.volume }
        val bookMatches = products.map { book ->
            SeriesBook(ProviderBookId(book.id), book.volume?.let(::BookRange), book.title, null, null)
        }
        return MetadataConfigApplier.apply(ProviderSeriesMetadata(
            id = ProviderSeriesId(series.id),
            metadata = SeriesMetadata(
                status = status,
                title = title,
                titles = listOf(title),
                summary = details.description,
                publisher = (series.publisher.takeIf { it.isNotBlank() } ?: details.publisher)
                    ?.let { Publisher(it, PublisherType.ORIGINAL, "ja") },
                readingDirection = if (category == "MANGA") ReadingDirection.RIGHT_TO_LEFT else null,
                language = "ja",
                tags = listOfNotNull(details.label),
                authors = details.authors,
                totalBookCount = volumes.size.takeIf { it > 0 },
                releaseDate = release?.toReleaseDate(),
                links = listOf(WebLink("BookWalker Japan", reference.url)),
                thumbnail = if (config.seriesMetadata.thumbnail) cover(details.cover) else null,
            ),
            books = bookMatches
        ), config.seriesMetadata)
    }

    override suspend fun getSeriesCover(seriesId: ProviderSeriesId): Image? {
        if (dmm?.accepts(seriesId.value) == true || ebookJapan?.accepts(seriesId) == true) return null
        return resolveEdition(seriesId.value)?.let { cover(details(it.reference.url).cover) }
    }

    override suspend fun getBookMetadata(seriesId: ProviderSeriesId, bookId: ProviderBookId): ProviderBookMetadata {
        if (ebookJapan?.accepts(seriesId) == true) {
            return requireNotNull(ebookJapan.book(seriesId, bookId)) { "eBookJapan volume not found" }
        }
        if (dmm?.accepts(seriesId.value) == true) {
            return requireNotNull(dmm.book(seriesId, bookId)) { "DMM Books volume not found" }
        }
        val products = if ('@' in seriesId.value) resolveEdition(seriesId.value)?.products.orEmpty()
            else books(seriesId.value)
        val product = requireNotNull(products.find { it.id == bookId.id }) { "BookWalker Japan volume not found" }
        val info = details(product.url)
        return MetadataConfigApplier.apply(ProviderBookMetadata(
            id = bookId,
            seriesId = seriesId,
            metadata = BookMetadata(
                title = product.title,
                summary = info.description,
                number = product.volume?.let(::BookRange),
                numberSort = product.volume?.toDouble(),
                releaseDate = info.releaseDate ?: date(product.release),
                authors = info.authors,
                tags = setOfNotNull(info.label),
                links = listOf(WebLink("BookWalker Japan", product.url)),
                thumbnail = if (config.bookMetadata.thumbnail) cover(info.cover) else null
            )
        ), config.bookMetadata)
    }

    private suspend fun candidates(input: String, requestedLimit: Int): List<Series> {
        val queries = titleQueries(input)
        if (queries.isEmpty()) return emptyList()
        val limit = requestedLimit.coerceIn(1, 100)
        return withContext(Dispatchers.IO) {
            connect()?.use { connection ->
                val found = linkedMapOf<String, Series>()
                for (query in queries) for (column in listOf("search", "alias")) {
                    val exact = "SELECT * FROM series WHERE category=? AND $column=? LIMIT ?"
                    connection.prepareStatement(exact).use { statement ->
                        statement.setString(1, category)
                        statement.setString(2, query)
                        statement.setInt(3, limit)
                        statement.executeQuery().use { rows ->
                            while (rows.next()) {
                                val series = rows.toSeries()
                                found[series.id] = series
                            }
                        }
                    }
                }
                if (found.isEmpty()) {
                    for (query in queries.filter { it.length >= 4 }) for (column in listOf("search", "alias")) {
                        val prefix = "SELECT * FROM series WHERE category=? AND $column>=? AND $column<? LIMIT ?"
                        connection.prepareStatement(prefix).use { statement ->
                            statement.setString(1, category)
                            statement.setString(2, query)
                            statement.setString(3, query + "\uDBFF\uDFFF")
                            statement.setInt(4, limit)
                            statement.executeQuery().use { rows ->
                                while (rows.next()) {
                                    val series = rows.toSeries()
                                    found.putIfAbsent(series.id, series)
                                }
                            }
                        }
                    }
                }
                found.values.take(limit)
            }.orEmpty()
        }
    }

    private suspend fun resolveEdition(id: String): Edition? {
        val baseId = id.substringBefore('@')
        val options = editions(baseId)
        if (id != baseId) return options.find { it.id == id }

        val productEdition = options.firstOrNull { option ->
            option.products.any { store.productId(it.url) == baseId }
        }
        return productEdition ?: selectEdition(options, null, chapters = false) ?: options.singleOrNull()
    }

    private suspend fun editions(id: String): List<Edition> = series(id)?.let { editions(it) }.orEmpty()

    private suspend fun editions(series: Series): List<Edition> {
        val inherited = publicationEdition(series.title)
        return books(series.id).groupBy { publicationEdition(it.title) ?: inherited }.mapNotNull { (kind, books) ->
            val reference = representative(books) ?: return@mapNotNull null
            val key = kind?.let { "${series.id}@${it.key}" } ?: series.id
            val name = withoutCatalogSeriesSuffix(series.title)
            val title = if (kind != inherited && kind != null) "$name【${kind.label}】" else name
            Edition(key, title, series.publisher, kind, reference, books)
        }
    }

    private suspend fun selectEdition(
        options: List<Edition>,
        requested: PublicationEdition?,
        chapters: Boolean,
    ): Edition? {
        if (requested != null) return options.singleOrNull { it.kind == requested }
        val standard = options.singleOrNull { it.kind == null }
        if (category != "MANGA" || chapters) return standard ?: options.singleOrNull()
        if (standard != null && !details(standard.reference.url).chapterEdition) return standard
        return options.singleOrNull {
            it.kind in setOf(PublicationEdition.STANDARD, PublicationEdition.FULL_COLOR, PublicationEdition.DIGITAL_SPECIAL)
        }
    }

    private suspend fun series(id: String): Series? {
        if (store.isStoreId(id)) {
            val work = store.load(id) ?: return null
            val title = normalize(work.title)
            return Series(id, work.title, title, title, work.publisher)
        }
        return indexedSeries(id)
    }

    private suspend fun indexedSeries(id: String): Series? = withContext(Dispatchers.IO) {
        connect()?.use { c ->
            c.prepareStatement("SELECT * FROM series WHERE id=? AND category=?").use { q ->
                q.setString(1, id)
                q.setString(2, category)
                q.executeQuery().use { r -> if (r.next()) r.toSeries() else null }
            }
        }
    }

    private suspend fun books(id: String): List<Product> {
        if (store.isStoreId(id)) {
            val work = store.load(id) ?: return emptyList()
            return work.books.map { book ->
                val volume = store.volumeNumber(work.title, book.title)
                Product(book.url.substringAfter("/de").trimEnd('/'), book.title, book.url, book.release, volume)
            }
        }
        return indexedBooks(id)
    }

    private suspend fun indexedBooks(id: String): List<Product> = withContext(Dispatchers.IO) {
        connect()?.use { c ->
            c.prepareStatement("""SELECT books.*, series.title AS series_title
                FROM books JOIN series ON series.id=books.series_id WHERE books.series_id=?
                ORDER BY volume IS NULL, volume, release, books.title""").use { q ->
                q.setString(1, id)
                q.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            val title = r.getString("title")
                            val volume = (r.getObject("volume") as? Number)?.toInt()
                                ?: store.volumeNumber(r.getString("series_title"), title)
                            add(Product(r.getString("id"), title, r.getString("url"), r.getString("release"), volume))
                        }
                    }
                }
            }
        }.orEmpty()
    }

    private fun connect() = db?.takeIf { Files.isRegularFile(it) }?.let {
        DriverManager.getConnection("jdbc:sqlite:${it.toUri()}?mode=ro")
    }

    private fun java.sql.ResultSet.toSeries() =
        Series(getString("id"), getString("title"), getString("search"), getString("alias"), getString("publisher"))

    private fun representative(products: List<Product>): Product? =
        products.firstOrNull { "特装版" !in it.title && "限定版" !in it.title && "特別版" !in it.title }
            ?: products.firstOrNull()

    private fun date(value: String): LocalDate? = runCatching {
        val parts = value.split('/')
        if (parts.size == 3) LocalDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) else LocalDate.parse(value)
    }.getOrNull()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun searchKey(value: String): String =
        normalize(withoutEditionMarker(withoutVolumeBonus(withoutCatalogSeriesSuffix(value))))

    private fun titleQueries(input: String) = (titleVariants(input) + listOfNotNull(titleWithSubtitle(input)?.first))
        .map(::normalize).filter(String::isNotEmpty).distinct()

    private fun titleVariants(input: String, includeEditionless: Boolean = true): List<String> {
        val title = Normalizer.normalize(input, Normalizer.Form.NFKC).trim()
        val withoutHash = title.replace(fileSuffix, "").replace(archiveHash, "").trimEnd()
        val volume = parenthesizedVolume.find(withoutHash)
        // A converted filename may contain the book title, volume and then the repeated series title.
        val beforeVolume = volume?.let { match ->
            val prefix = withoutHash.substring(0, match.range.first).trimEnd()
            val suffix = withoutHash.substring(match.range.last + 1).trimStart()
            if (suffix.isEmpty() || suffix.startsWith(prefix) || suffix.startsWith("(") || bookExtraAfterVolume.matches(suffix)) {
                prefix
            } else null
        }
        val variants = listOfNotNull(
            title, withoutHash, withoutHash.replace(volumeAndLabel, ""), beforeVolume,
            withoutHash.replace(dmmVolumeSuffix, ""),
        )
        val searchable = variants + variants.map { withoutVolumeBonus(it).trim() }
        if (!includeEditionless) return searchable.distinct()
        return (searchable + searchable.map { withoutEditionMarker(it).trim() }).distinct()
    }

    private suspend fun details(url: String): Details {
        synchronized(detailsCache) { detailsCache[url] }?.takeUnless {
            System.nanoTime() - it.checkedAt > 30L * 60 * 1_000_000_000
        }?.let { return it }
        val uri = URI.create(url)
        require(uri.scheme == "https" && uri.host == "bookwalker.jp" && allowedProduct.matches(uri.path))
        val info = try {
            val response = httpClient.get(url)
            if (response.status.value != 200 || response.call.request.url.host != "bookwalker.jp") return Details()
            val html = response.bodyAsText()
            val product = jsonLd.findAll(html).mapNotNull { match ->
                runCatching { Json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
            }.firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == "Product" } ?: return Details()
            val document = Ksoup.parse(html)
            val fields = definitionFields(document.select("dl.t-c-detail-about-information__data > dt"))
            val primary = definitionFields(document.select("dl.t-c-product-main-data__authors > dt"))
                .flatMap { (role, element) ->
                    val names = element.select("a").map { it.text() }.ifEmpty { listOf(element.text()) }
                    names.mapNotNull { author(it, role) }
                }
            val secondary = fields.filter { it.first == "著者" }.flatMap { (_, element) ->
                element.select("a").mapNotNull { node ->
                    val text = node.text()
                    val suffix = creditSuffix.find(text)
                    if (suffix == null) author(text, "著者")
                    else author(text.substring(0, suffix.range.first), suffix.groupValues[1])
                }
            }
            val publisher = (product["brand"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            Details(
                status = publicationStatus(
                    document.select(".t-c-detail-main__labels .t-o-book-label").map { it.text() }
                ),
                chapterEdition = document.select("li.t-o-tag-label a").any { it.text() in setOf("分冊版", "単話版") },
                description = product["description"]?.jsonPrimitive?.contentOrNull,
                cover = product["image"]?.jsonPrimitive?.contentOrNull,
                publisher = publisher ?: fields.firstOrNull { it.first == "出版社" }?.second?.text(),
                authors = (primary + secondary).distinct(),
                label = fields.firstOrNull { it.first == "レーベル" }?.second?.text()?.takeUnless { it.isBlank() || it == "――" },
                releaseDate = fields.firstOrNull { it.first == "配信開始日" }?.second?.text()?.let(::date),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return Details()
        synchronized(detailsCache) { detailsCache[url] = info }
        return info
    }

    private fun definitionFields(headings: Iterable<Element>): List<Pair<String, Element>> = headings.mapNotNull { term ->
        term.nextElementSibling()?.takeIf { it.tagName() == "dd" }?.let { term.text().trim() to it }
    }

    private fun author(credit: String, label: String): Author? {
        val name = credit.trim()
        if (name.isBlank() || name in setOf("他", "ほか", "――")) return null
        val role = when (label.trim()) {
            "著", "著者", "原作", "原案", "企画・原案", "脚本", "作", "文", "文章", "ストーリー", "シナリオ" -> AuthorRole.WRITER
            "漫画", "マンガ", "まんが", "作画", "絵", "イラスト", "挿絵", "キャラクター原案", "キャラクターデザイン" -> AuthorRole.PENCILLER
            "着彩", "彩色", "カラー" -> AuthorRole.COLORIST
            "カバーイラスト", "表紙イラスト" -> AuthorRole.COVER
            "編集" -> AuthorRole.EDITOR
            "翻訳", "訳" -> AuthorRole.TRANSLATOR
            else -> return null
        }
        return Author(name, role)
    }

    private suspend fun cover(url: String?): Image? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host !in setOf("rimg.bookwalker.jp", "c.bookwalker.jp")) return null
        // The storefront uses these image IDs for its sign-in-required cover placeholder.
        if (uri.path.split('/', limit = 3).getOrNull(1) in setOf("699999999", "799999999")) return null
        try {
            val response = httpClient.get(url)
            val contentType = response.contentType() ?: return null
            if (response.status.value != 200 || contentType.contentType != "image") return null
            val bytes: ByteArray = response.body()
            return Image(bytes, "${contentType.contentType}/${contentType.contentSubtype}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
    }

    private data class Series(val id: String, val title: String, val search: String, val alias: String, val publisher: String)
    private data class Product(val id: String, val title: String, val url: String, val release: String, val volume: Int?)
    private data class Edition(
        val id: String,
        val title: String,
        val publisher: String,
        val kind: PublicationEdition?,
        val reference: Product,
        val products: List<Product>,
    )
    private data class Details(
        val description: String? = null,
        val cover: String? = null,
        val publisher: String? = null,
        val authors: List<Author> = emptyList(),
        val label: String? = null,
        val releaseDate: LocalDate? = null,
        val status: SeriesStatus? = null,
        val chapterEdition: Boolean = false,
        val checkedAt: Long = System.nanoTime(),
    )
}

private val editionMarker = Regex("""[【〖\[]((?:電子|フルカラー)*単行本版?|コミック版)[】〗\]]""")
private val chapterEditionName = Regex("""分冊版|単話版|[【〖\[]単話[】〗\]]|第\s*[0-9０-９]+\s*話""")

internal enum class PublicationEdition(val key: String, val label: String) {
    STANDARD("volume", "単行本版"),
    FULL_COLOR("color-volume", "フルカラー単行本版"),
    SPECIAL("special", "特装版"),
    DIGITAL_SPECIAL("digital-special", "電子特別版"),
    COLOR("color", "カラー版"),
}

internal fun publicationEdition(title: String): PublicationEdition? {
    if ("特装版" in title) return PublicationEdition.SPECIAL
    if ("電子特別版" in title) return PublicationEdition.DIGITAL_SPECIAL
    val marker = editionMarker.findAll(title).firstOrNull { "単行本" in it.value }
    if (marker != null) return if ("フルカラー" in marker.value) PublicationEdition.FULL_COLOR else PublicationEdition.STANDARD
    return PublicationEdition.COLOR.takeIf { colorEditionMarker.containsMatchIn(title) }
}

internal fun withoutCatalogSeriesSuffix(title: String): String =
    title.trimEnd().removeSuffix("シリーズ").trimEnd()

private val specialEditionMarker = Regex("""[【〖\[][^】〗\]]*(?:特装版|電子特別版)[^】〗\]]*[】〗\]]""")
private val colorEditionMarker = Regex("""[【〖\[](?:フル)?カラー版[】〗\]]""")
private val volumeBonusMarker = Regex("""[【〖\[]電子限定(?:おまけ|特典)[^】〗\]]*[】〗\]]""")

internal fun withoutEditionMarker(title: String): String =
    colorEditionMarker.replace(specialEditionMarker.replace(editionMarker.replace(title, ""), ""), "")

internal fun withoutVolumeBonus(title: String): String = volumeBonusMarker.replace(title, "")

internal fun publicationStatus(labels: Iterable<String>): SeriesStatus? =
    labels.mapNotNull {
        when (it.trim()) {
            "完結", "最終巻", "完結・最終巻" -> SeriesStatus.ENDED
            "連載中", "刊行中" -> SeriesStatus.ONGOING
            else -> null
        }
    }.distinct().singleOrNull()
