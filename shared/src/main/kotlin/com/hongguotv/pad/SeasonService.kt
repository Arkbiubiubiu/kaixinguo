package com.hongguotv.pad

/**
 * 季发现领域边界：详情页/播放器只依赖这里，不再直接散调 Api.seasons/nextSeason/fillMissing。
 * 内部暂时转调 Api；后续把季缓存/主创校验搬进来时调用方不用改。
 */
object SeasonService {
    fun list(seriesId: String): List<SeasonRef> = Api.seasons(seriesId)

    fun next(seriesId: String): String? = Api.nextSeason(seriesId)

    fun fillMissing(
        seriesId: String,
        title: String,
        have: List<SeasonRef>,
        baseCreators: Set<String>,
    ): List<SeasonRef> = Api.fillMissingSeasons(seriesId, title, have, baseCreators)
}
