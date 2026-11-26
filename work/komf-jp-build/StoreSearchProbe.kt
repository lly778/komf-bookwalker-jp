package snd.komf.providers.bookwalker

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
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
        println("Verified public storefront search, stable series ID, and volume pages.")
    }
}
