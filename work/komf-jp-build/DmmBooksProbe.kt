import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import snd.komf.model.MatchQuery
import snd.komf.model.BookQualifier
import snd.komf.model.BookRange
import snd.komf.model.ProviderSeriesId
import snd.komf.providers.ProviderConfig
import snd.komf.providers.bookwalker.BookWalkerMetadataProvider

fun main() = runBlocking {
    HttpClient(OkHttp).use { http ->
        val provider = BookWalkerMetadataProvider(ProviderConfig(enabled = true), http)
        val shortTitle = "あやつりカレンダー"
        val fullTitle = "$shortTitle【フルカラー電子単行本版】"
        for (title in listOf(fullTitle, shortTitle, "$fullTitle (2) [0123456789ab].cbz")) {
            val manual = provider.searchSeries(title, 10).single()
            check(manual.url?.startsWith("https://book.dmm.com/") == true) { manual.toString() }
            val auto = checkNotNull(provider.matchSeriesMetadata(MatchQuery(title, null, null, null)))
            check(manual.resultId == auto.id.value)
            val series = provider.getSeriesMetadata(ProviderSeriesId(manual.resultId))
            check(series.metadata.title?.name == fullTitle)
            check(series.metadata.publisher?.name == "wwwave comics")
            check(series.metadata.authors.single().name == "相川たつき")
            check(series.metadata.totalBookCount == 3)
            check(series.books.mapNotNull { it.number?.start } == listOf(1.0, 2.0, 3.0)) { series.books.toString() }
            val volume = provider.getBookMetadata(series.id, series.books[1].id).metadata
            check(volume.number?.start == 2.0)
            check(volume.authors.single().name == "相川たつき")
            check(volume.links.single().url == "https://book.dmm.com/product/6255390/b574acmin06277/")
            check(volume.releaseDate.toString() == "2026-05-18")
            check(volume.thumbnail == null && series.metadata.thumbnail == null)
        }
        check(provider.searchSeries("$shortTitle【電子単行本版】", 10).isEmpty())
        println("Verified Identify and Auto-Identify fallback, correct edition, all three volumes, credits, dates, and native covers.")

        val longer = checkNotNull(provider.matchSeriesMetadata(MatchQuery("カレシがいるのに", null, null, null)))
        check(longer.books.mapNotNull { it.number?.start } == (1..14).map { it.toDouble() }) { longer.books.toString() }
        val latest = provider.getBookMetadata(longer.id, longer.books.last().id).metadata
        check(latest.number?.start == 14.0 && latest.releaseDate.toString() == "2026-09-25")
        check(latest.links.single().url == "https://book.dmm.com/product/923444/b355jakta26036/")
        check(!latest.summary.isNullOrBlank() && latest.thumbnail == null)
        println("Verified DMM pagination through volume 14, including summary, publication date, and stable product URL.")

        val shortHospital = "ハッピーホスピタルへようこそ"
        val officialHospital = "$shortHospital 白衣の天使たちのエッチな願望、かなえちゃいます"
        val localVolume = "$officialHospital （3） 【かきおろし漫画付】 (コミックラクウ).cbz"
        check(provider.searchSeries(shortHospital, 10).single().title == officialHospital)
        val detected = checkNotNull(provider.matchSeriesMetadata(MatchQuery(shortHospital, null, BookQualifier(localVolume, BookRange(3), null), null)))
        check(detected.metadata.title?.name == officialHospital)
        val third = detected.books.single { it.number?.start == 3.0 }
        val hospitalVolume = provider.getBookMetadata(detected.id, third.id).metadata
        check(!hospitalVolume.summary.isNullOrBlank() && hospitalVolume.releaseDate != null)
        println("Verified DMM search by series abbreviation and exact auto-match by the CBZ filename, including volume 3.")
    }
}
