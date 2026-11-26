package snd.komf.providers.bookwalker

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import snd.komf.model.MatchQuery
import snd.komf.model.ProviderSeriesId
import snd.komf.model.SeriesStatus
import snd.komf.providers.ProviderConfig

fun main() = runBlocking {
    check(publicationStatus(listOf("連載中")) == SeriesStatus.ONGOING)
    check(publicationStatus(listOf("最終巻")) == SeriesStatus.ENDED)
    check(publicationStatus(listOf("最新刊")) == null)
    check(publicationStatus(listOf("完結", "連載中")) == null)

    HttpClient(OkHttp).use { http ->
        val config = ProviderConfig(enabled = true)
        val bookWalker = BookWalkerMetadataProvider(config, http)
        val completed = bookWalker.matchSeriesMetadata(MatchQuery("キングダムオブザＺ", null, null, null))
        check(completed?.metadata?.status == SeriesStatus.ENDED) { "BookWalker status: ${completed?.metadata?.status}" }
        val hackingGhost = checkNotNull(bookWalker.matchSeriesMetadata(
            MatchQuery("ＨＡＣＫＩＮＧ　ＧＨＯＳＴ～カラダにしか価値のない学園～", null, null, null)
        ))
        check(hackingGhost.books.mapNotNull { it.number?.start?.toInt() }.toSet().containsAll((1..7).toList()))
        val dmm = DmmBooksFallback(http, config)
        val finished = dmm.series(ProviderSeriesId("dmm-books-6255632-latest"))
        check(finished?.metadata?.status == SeriesStatus.ENDED) { "DMM status: ${finished?.metadata?.status}" }
        val latest = checkNotNull(dmm.series(ProviderSeriesId("dmm-books-923444-b355gakta01899")))
        check(latest.metadata.status == null)
        check(latest.books.size == 14)
        val samples = mapOf(
            "dmm-books-4352010-b900bkds28614" to 7,
            "dmm-books-4512325-b371khkss03788" to 6,
            "dmm-books-4216204-b350eftdb02068" to 9,
        )
        for ((source, count) in samples) {
            val series = checkNotNull(dmm.series(ProviderSeriesId(source)))
            check(series.books.mapNotNull { it.number?.start?.toInt() }.toSet().containsAll((1..count).toList())) {
                "$source: ${series.books.map { it.number }}"
            }
        }
        val zombie = bookWalker.searchSeries("ゾンビのあふれた世界で俺だけが襲われない", 10).single()
        check(zombie.resultId.startsWith("dmm-books-4163193-"))
        val kaguyaTitle = "異世界ソープランド輝夜"
        val kaguya = checkNotNull(bookWalker.matchSeriesMetadata(MatchQuery(kaguyaTitle, null, null, null)))
        check(kaguya.id.value.startsWith("dmm-books-861323-")) { kaguya.id.value }
        check(bookWalker.searchSeries(kaguyaTitle, 10).first().resultId.startsWith("dmm-books-861323-"))
        val checkedTitles = listOf(
            Triple("色欲覇道~魔力と精力ツヨツヨなので24時間無双します~【単行本版】", "dmm-books-6308575-", 1),
            Triple("35歳の選択～異世界転生を選んだ場合～", "dmm-books-4219477-", 9),
            Triple("俺と聖女がデキてることがバレたらこのパーティーは崩壊する【電子限定おまけ付き】", "", 2),
            Triple("貞操逆転世界で頼めばヤれると噂の俺【電子特別版】", "", 2),
            Triple("その悪役貴族、ママヒロインが好きすぎる ～真摯な努力で最強となり不遇な推しキャラ助けまくる～", "", 6),
            Triple("キングダムオブザＺ【描き下ろし漫画付き特装版】", "", 6),
        )
        for ((title, source, count) in checkedTitles) {
            val series = checkNotNull(bookWalker.matchSeriesMetadata(MatchQuery(title, null, null, null))) { title }
            check(series.id.value.startsWith(source)) { "${title}: ${series.id}" }
            check(series.books.mapNotNull { it.number?.start?.toInt() }.toSet().containsAll((1..count).toList())) {
                "${title}: ${series.books.map { it.number }}"
            }
            if ("特装版" in title) {
                check(series.books.size == 6 && series.books.all { "特装版" in it.name.orEmpty() })
                check(series.metadata.status == SeriesStatus.ENDED)
            }
            if ("電子特別版" in title) check(series.books.all { "電子特別版" in it.name.orEmpty() })
            val manual = bookWalker.searchSeries(title, 10)
            check(manual.any { it.resultId == series.id.value }) { "${title}: $manual" }
        }
        check(kaguya.books.mapNotNull { it.number?.start?.toInt() }.toSet().containsAll((1..8).toList()))
        val volume14 = latest.books.single { it.number?.start == 14.0 }
        check(dmm.book(latest.id, volume14.id)?.metadata?.releaseDate.toString() == "2026-09-25")
        val disabled = config.copy(seriesMetadata = config.seriesMetadata.copy(status = false))
        val disabledStatus = checkNotNull(DmmBooksFallback(http, disabled).series(ProviderSeriesId("dmm-books-6255632-latest")))
        check(disabledStatus.metadata.status == null)
        println("BookWalker and DMM completed status verified. Latest-only remains unknown. Status switch and volume pagination passed.")
    }
}
