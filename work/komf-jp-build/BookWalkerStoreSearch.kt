package snd.komf.providers.bookwalker

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import snd.komf.model.BookRange
import java.net.URI
import java.text.Normalizer

/** Reads public catalog pages for manual searches when the official CSV has no match. */
internal class BookWalkerStoreSearch(private val client: HttpClient, private val manga: Boolean) {
    private val base = "https://bookwalker.jp"
    private val productPath = Regex("/de[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}/")
    private val seriesPath = Regex("/series/([0-9]{1,10})/list/")
    private val storeId = Regex("bookwalker-jp-(?:s[0-9]{1,10}|de[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})")
    private val titleTag = Regex("""<a\s+href="(https://bookwalker\.jp/[^"?#]+/)"\s+class="m-book-item__title"[^>]*>([^<]+)</a>""")
    private val labelTag = Regex("""<p class="m-book-item__label">([^<]+)</p>""")
    private val categoryTag = Regex("""<span class="a-tag-(comic|bunko|book)">([^<]+)</span>""")
    private val heading = Regex("""<h1 class="o-contents-section__title">『([^<]+?)\(.*?\)』の電子書籍一覧</h1>""")
    private val publisherLink = Regex("""data-action-label="出版社-([^"]+)"""")
    private val productSeriesPath = Regex("/series/[0-9]{1,10}/list/")
    private val jsonLd = Regex("""<script\b[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val seriesVolume = Regex("""<a\b(?=[^>]*\bdata-ga-category="series_list")(?=[^>]*\bdata-action-label="title")[^>]*\bhref="([^"]+)"[^>]*>\s*([^<]+?)\s*</a>""")
    private val releaseDate = Regex("""<dt>\s*配信開始日\s*</dt>\s*<dd>\s*([0-9]{4}/[0-9]{1,2}/[0-9]{1,2})\s*</dd>""")
    private val latestMarker = Regex("^[【〖]最新刊[】〗]\\s*")
    private val requestSlots = Semaphore(2)
    private val promotional = Regex("期間限定|試し読み|無料お試し|無料版|分冊版|[【〖](?:単話版?|タテヨミ)[】〗]")
    private val works = object : LinkedHashMap<String, Work>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Work>?) = size > 64
    }

    fun isStoreId(id: String) = storeId.matches(id)

    fun productId(input: String): String? {
        val url = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (url.scheme != "https" || url.rawAuthority != "bookwalker.jp") return null
        val path = url.rawPath.trimEnd('/') + "/"
        return if (productPath.matches(path)) "bookwalker-jp-${path.trim('/')}" else null
    }

    suspend fun search(title: String, queries: List<String>, normalize: (String) -> String): List<Entry> {
        if (queries.none { it.length >= 4 } || title.length > 240) return emptyList()
        val html = fetch("$base/search/", title) ?: return emptyList()
        return catalogEntries(html).mapNotNull { entry ->
            val bareTitle = entry.title
            val normalized = normalize(bareTitle)
            // Keep spin-offs and other editions out of a fallback meant for an exact title.
            if (normalized !in queries) return@mapNotNull null
            val path = entry.url.removePrefix(base)
            val id = seriesPath.matchEntire(path)?.let { "s${it.groupValues[1]}" }
                ?: path.takeIf(productPath::matches)?.trim('/') ?: return@mapNotNull null
            Entry("bookwalker-jp-$id", bareTitle, entry.url)
        }.distinctBy(Entry::id)
    }

    suspend fun load(id: String): Work? {
        if (!isStoreId(id)) return null
        synchronized(works) { works[id] }?.let { return it }
        val key = id.removePrefix("bookwalker-jp-")
        val work = if (key.startsWith('s')) {
            val html = fetch("$base/series/${key.drop(1)}/list/") ?: return null
            val entries = catalogEntries(html).filter { productPath.matches(it.url.removePrefix(base)) }
                .distinctBy(Book::url)
            val first = entries.firstOrNull() ?: return null
            val pageTitle = heading.find(html)?.groupValues?.get(1)?.let(::unescape) ?: return null
            val title = stripLabel(pageTitle, first.label)
            val publisher = publisherLink.findAll(html).map { unescape(it.groupValues[1]) }.distinct().singleOrNull()
            Work(title, publisher.orEmpty(), entries.reversed())
        } else loadProduct("$base/$key/") ?: return null
        synchronized(works) { works[id] = work }
        return work
    }

    private suspend fun loadProduct(url: String): Work? {
        val html = fetch(url) ?: return null
        val data = jsonLd.findAll(html).mapNotNull { match ->
            runCatching { Json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
        }.associateBy { it.string("@type") }
        val product = data["Product"] ?: return null
        if (product.string("url") != url || !hasCategory(product.string("category"))) return null
        val productTitle = product.string("name")?.replace(latestMarker, "")?.takeIf(String::isNotBlank) ?: return null
        if (promotional.containsMatchIn(productTitle)) return null
        val section = data["BreadcrumbList"]?.get("itemListElement")?.jsonArray.orEmpty().mapNotNull { node ->
            node.jsonObject["item"] as? JsonObject
        }.firstOrNull { item -> productSeriesPath.matches(item.string("@id").orEmpty().removePrefix(base)) }
        val sectionName = section?.string("name")
        val label = sectionName?.substringAfterLast('（', "")?.removeSuffix("）").orEmpty()
        val title = sectionName?.let { stripLabel(it, label) } ?: productTitle
        val date = releaseDate.find(html)?.groupValues?.get(1).orEmpty()
        val seed = Book(productTitle, url, label, date)
        val volumes = seriesVolume.findAll(html).mapNotNull { anchor ->
            val otherUrl = anchor.groupValues[1]
            if (!otherUrl.startsWith(base) || !productPath.matches(otherUrl.removePrefix(base))) return@mapNotNull null
            val volumeTitle = unescape(anchor.groupValues[2]).replace(latestMarker, "")
            if (volumeNumber(title, volumeTitle) == null || promotional.containsMatchIn(volumeTitle)) return@mapNotNull null
            Book(volumeTitle, otherUrl, label)
        }
        val books = (sequenceOf(seed) + volumes).distinctBy(Book::url)
            .sortedBy { volumeNumber(title, it.title) ?: Int.MAX_VALUE }.toList()
        val publisher = (product["brand"] as? JsonObject)?.string("name").orEmpty()
        return Work(title, publisher, books)
    }

    fun volumeNumber(series: String, title: String): Int? =
        mangaVolumeAfterTitle(series, title)?.start?.let { if (it % 1.0 == 0.0) it.toInt() else null }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull

    private fun catalogEntries(html: String): List<Book> = html.split("<li class=\"m-tile\"").drop(1).mapNotNull { tile ->
        val category = categoryTag.find(tile)?.groupValues?.get(2) ?: return@mapNotNull null
        if (!hasCategory(category)) return@mapNotNull null
        val anchor = titleTag.find(tile) ?: return@mapNotNull null
        val label = labelTag.find(tile)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val title = stripLabel(unescape(anchor.groupValues[2].trim()), label)
        if (promotional.containsMatchIn(title)) return@mapNotNull null
        Book(title, anchor.groupValues[1], label)
    }

    private fun hasCategory(category: String?) =
        if (manga) category == "マンガ" else category in setOf("ライトノベル", "新文芸", "文芸・小説")

    private suspend fun fetch(url: String, query: String? = null): String? = try {
        requestSlots.withPermit {
            withTimeoutOrNull(8_000) {
                val response = client.get(url) {
                    if (query != null) {
                        parameter("word", query)
                        if (manga) parameter("qcat", 2)
                    }
                }
                if (response.status.value == 200 && response.call.request.url.host == "bookwalker.jp") {
                    response.bodyAsText()
                } else null
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun stripLabel(title: String, label: String): String =
        if (label.isEmpty()) title else title.removeSuffix("（$label）").removeSuffix("($label)").trim()

    private fun unescape(text: String): String = text.replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    data class Entry(val id: String, val title: String, val url: String)
    data class Work(val title: String, val publisher: String, val books: List<Book>)
    data class Book(val title: String, val url: String, val label: String, val release: String = "")
}

private val retailerBonus = Regex(
    """[【〖\[](?:(?:DMM|Amazon(?:\.co\.jp)?|電子(?:コミック|書籍|版)?)限定)[^】〗\]\r\n]*特典[^】〗\]\r\n]*[】〗\]]?""",
    RegexOption.IGNORE_CASE,
)
private val volumeImprint = Regex(
    """^\([^()]{0,45}(?:コミックス|コミック|COMICS|文庫)[^()]{0,12}\)\s*""", RegexOption.IGNORE_CASE,
)
private val mangaVolumeSuffix = Regex(
    """^(?:第\s*|vol\.?\s*)?(?:\(([0-9]{1,4}(?:\.[0-9]+)?)\)|([0-9]{1,4}(?:\.[0-9]+)?)(?=\s*巻|[\s(【〖\[]|$))(?!\s*[話章])""",
    RegexOption.IGNORE_CASE,
)

internal fun mangaVolumeAfterTitle(series: String, book: String): BookRange? {
    fun normalizedTitle(value: String) = withoutEditionMarker(Normalizer.normalize(value, Normalizer.Form.NFKC))
        .let(::withoutVolumeBonus).replace(retailerBonus, "")
    val prefix = normalizedTitle(withoutCatalogSeriesSuffix(series)).filter(Char::isLetterOrDigit)
    if (prefix.isEmpty()) return null

    val name = normalizedTitle(book)
    var index = 0
    for ((position, char) in name.withIndex()) {
        if (!char.isLetterOrDigit()) continue
        if (!char.equals(prefix[index], ignoreCase = true)) return null
        if (++index == prefix.length) {
            val tail = name.substring(position + 1).trimStart {
                it.isWhitespace() || it in ")」』】〗~〜～:!?。"
            }
            val afterImprint = volumeImprint.replaceFirst(tail, "")
            val match = mangaVolumeSuffix.find(afterImprint)
                ?: (if (publicationEdition(book) == PublicationEdition.SPECIAL) {
                    mangaVolumeSuffix.find(afterImprint.substringAfter("特装版", "").trimStart())
                } else null)
                ?: return null
            val number = match.groupValues.drop(1).firstOrNull(String::isNotEmpty)?.toDoubleOrNull() ?: return null
            return number.takeIf { it > 0 }?.let(::BookRange)
        }
    }
    return null
}
