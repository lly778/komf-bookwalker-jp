import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import snd.komf.model.BookQualifier
import snd.komf.model.BookRange
import snd.komf.model.MatchQuery
import snd.komf.model.SeriesStatus
import snd.komf.model.ProviderSeriesId
import snd.komf.providers.ProviderConfig
import snd.komf.providers.bookwalker.BookWalkerMetadataProvider

fun main() = runBlocking {
    HttpClient(OkHttp).use { client ->
        val provider = BookWalkerMetadataProvider(ProviderConfig(enabled = true), client)
        val query = "クラス転移で拠点魔法を手に入れたら、ハーレム共同生活が始まりました"
        val hit = provider.searchSeries(query, 10).single()
        val item = provider.getSeriesMetadata(ProviderSeriesId(hit.resultId))
        check(item.metadata.publisher?.name == "講談社")
        check(!item.metadata.summary.isNullOrBlank())
        check(item.metadata.thumbnail?.bytes?.isNotEmpty() == true)
        check(item.metadata.authors.map { it.name }.toSet() == setOf("百井一途", "毒沼"))
        check("マガジンポケット" in item.metadata.tags)
        val volume = item.books.single()
        check(volume.number?.start == 1.0)
        val book = provider.getBookMetadata(item.id, volume.id)
        check(book.metadata.number?.start == 1.0)
        check(book.metadata.authors == item.metadata.authors)
        check(book.metadata.releaseDate != null)
        check(book.metadata.links.single().url.startsWith("https://bookwalker.jp/"))
        val auto = provider.matchSeriesMetadata(MatchQuery(query, null, null, null))
        check(auto?.id == item.id)
        val fullName = item.metadata.title!!.name
        check(provider.searchSeries("$fullName [6e5d5e24d759].cbz", 10).single().resultId == hit.resultId)
        val goblin = "ゴブリンだらけの世界で、俺だけ【チート付与魔術】で自由に生きていく"
        val converted = "$goblin (2) $goblin【単行本版】 (バンブーコミックス 異世界BC)"
        val goblinResult = provider.searchSeries(converted, 10).single()
        check(goblinResult.title.endsWith("【単行本版】"))
        check(provider.searchSeries("no-existing-sample-253026854", 10).isEmpty())

        val mixed = listOf(
            Triple("煉獄病棟～白衣の献身ご奉仕から逃げられない～", 3, "de36bced09-e747-4e4a-a9d2-1d0d0559a865"),
            Triple("土御門さんは青が好き", 1, "de204d8a8b-96b7-479c-b57f-d281af3992e9"),
            Triple("BSSリトライ～あの子とヤレるまで大学時代をやり直す～", 1, "de4bf5415f-3cae-4d93-b95b-aff4f34686c3"),
        )
        for ((base, count, product) in mixed) {
            val title = "$base【単行本版】"
            val fileName = "${title}1 (COMICアンブル).cbz"
            val expectedUrl = "https://bookwalker.jp/$product/"
            val match = checkNotNull(provider.matchSeriesMetadata(
                MatchQuery(base, null, BookQualifier(fileName, BookRange(1), null), null)
            )) { title }
            check(match.metadata.totalBookCount == count && match.books.size == count) { match.toString() }
            check(match.metadata.links.single().url == expectedUrl)
            check(match.books.all { it.name?.contains("単行本") == true })
            check(provider.searchSeries(title, 10).single().resultId == match.id.value)
            val stored = provider.getSeriesMetadata(match.id)
            check(stored.books == match.books)
            val book = provider.getBookMetadata(stored.id, stored.books.first().id)
            check(book.metadata.links.single().url == expectedUrl)
            if (count == 3) {
                check(match.metadata.status == SeriesStatus.ENDED)
                val direct = provider.searchSeries(expectedUrl, 10).single()
                val directMetadata = provider.getSeriesMetadata(ProviderSeriesId(direct.resultId))
                check(directMetadata.books.size == 3 && directMetadata.books.all { it.name?.contains("単行本") == true })
            }
            if (base == "土御門さんは青が好き") check(match.metadata.status == null)
        }
        println("Verified volume and chapter editions stay separate in search, auto-match and retrieval.")
        val productUrl = System.getenv("KOMF_BOOKWALKER_JP_TEST_PRODUCT_URL")
        if (productUrl != null) {
            val result = provider.searchSeries(productUrl, 10).single()
            val series = provider.getSeriesMetadata(ProviderSeriesId(result.resultId))
            check(!series.metadata.publisher?.name.isNullOrBlank())
            check(!series.metadata.summary.isNullOrBlank())
            val expectedCount = System.getenv("KOMF_BOOKWALKER_JP_TEST_MIN_VOLUMES")?.toInt() ?: 1
            check(series.books.size >= expectedCount)
            val book = provider.getBookMetadata(series.id, series.books.last().id)
            check(book.metadata.links.single().url.startsWith("https://bookwalker.jp/"))
            if (System.getenv("KOMF_BOOKWALKER_JP_TEST_HIDDEN_COVER") == "1") {
                check(series.metadata.thumbnail == null)
                check(provider.getSeriesCover(series.id) == null)
            }
            println("Verified direct product identification with ${series.books.size} volumes and cover availability.")
        }
        println("Verified Japanese source, summary, cover, publisher, volume 1, and auto-match.")
    }
}
