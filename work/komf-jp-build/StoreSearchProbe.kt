package snd.komf.providers.bookwalker

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import snd.komf.model.MatchQuery
import snd.komf.model.ProviderSeriesId
import snd.komf.providers.ProviderConfig
import java.text.Normalizer

fun main() = runBlocking {
    val title = "架空漫画～ある作品～（コミック）"
    check(mangaVolumeAfterTitle(title, "架空漫画〜ある作品〜（コミック） ： ４【特典】")?.start == 4.0)
    check(mangaVolumeAfterTitle(title, "${title}【DMM限定特典イラスト付】（７）")?.start == 7.0)
    check(mangaVolumeAfterTitle("2番目の漫画", "２番目の漫画 １ 2番目の漫画 (出版社)")?.start == 1.0)
    check(mangaVolumeAfterTitle(title, "${title} 第３話") == null)
    check(mangaVolumeAfterTitle(title, "${title} 12-14") == null)
    check(mangaVolumeAfterTitle(title, "${title}外伝 3") == null)
    check(mangaVolumeAfterTitle("架空漫画 THE COMICシリーズ", "架空漫画 THE COMIC１ (出版社)")?.start == 1.0)
    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
    HttpClient(OkHttp).use { http ->
        val search = BookWalkerStoreSearch(http, true)
        val query = "キングダムオブザＺ"
        val own = search.search(query, listOf(normalize(query)), ::normalize).single()
        check(own.url == "https://bookwalker.jp/series/203637/list/")
        val fresh = BookWalkerStoreSearch(http, true)
        val work = checkNotNull(fresh.load(own.id))
        check(work.title == query)
        check(work.books.first().url == "https://bookwalker.jp/deeec38e85-1546-40d9-93cc-a6815596fb91/")
        check(work.publisher == "講談社")

        val provider = BookWalkerMetadataProvider(ProviderConfig(enabled = true), http)
        val automatic = checkNotNull(provider.matchSeriesMetadata(MatchQuery(query, null, null, null)))
        check(automatic.metadata.links.single().url.startsWith("https://book.dmm.com/"))
        val manual = provider.searchSeries(query, 10)
        check(manual.first().resultId == automatic.id.value)

        val direct = provider.searchSeries(work.books.first().url, 10).single()
        check(direct.url == work.books.first().url)
        val bookWalker = provider.getSeriesMetadata(ProviderSeriesId(direct.resultId))
        val volume = bookWalker.books.single { it.number?.start == 1.0 }
        val metadata = provider.getBookMetadata(bookWalker.id, volume.id).metadata
        check(metadata.links.single().url == work.books.first().url)
        check(metadata.authors.isNotEmpty())

        HttpClient(OkHttp) {
            engine {
                addInterceptor { chain ->
                    val request = chain.request()
                    if (request.url.host == "book.dmm.com") {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("DMM unavailable in fallback probe")
                            .body("".toResponseBody())
                            .build()
                    } else chain.proceed(request)
                }
            }
        }.use { fallbackClient ->
            val fallbackProvider = BookWalkerMetadataProvider(ProviderConfig(enabled = true), fallbackClient)
            val fallback = checkNotNull(fallbackProvider.matchSeriesMetadata(MatchQuery(query, null, null, null)))
            check(fallback.metadata.links.single().url == work.books.first().url)
            check(fallbackProvider.searchSeries(query, 10).first().resultId == fallback.id.value)
        }

        val indexedId = ProviderSeriesId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        check(provider.matchSavedEdition(indexedId, emptyList()) == null)
        check(provider.matchSavedEdition(indexedId, listOf("${query}【描き下ろし漫画付き特装版】1.cbz")) == null)
        check(provider.matchSavedEdition(bookWalker.id, emptyList())?.id == bookWalker.id)
        println("Verified DMM priority, direct BookWalker lookup, volume metadata, and saved IDs without an index.")
    }
}
