package com.example.extension.managed.model

/**
 * Builds provider search phrases for an episode request without mutating the media title,
 * season number, or episode number used for playback/download selection.
 */
object EpisodeSearchQueryBuilder {
    data class Request(
        val baseTitle: String,
        val season: Int,
        val episode: Int
    )

    fun parseEpisodeSuffix(title: String, seasonFallback: Int = 1, episodeFallback: Int = 1): Request {
        val normalized = title.trim()
        val patterns = listOf(
            Regex("""(?i)^(.*?)\s+S(\d{1,2})\s*E(\d{1,3})\s*$"""),
            Regex("""(?i)^(.*?)\s+Season\s*(\d{1,2})\s+Episode\s*(\d{1,3})\s*$"""),
            Regex("""(?i)^(.*?)\s+(\d{1,2})x(\d{1,3})\s*$""")
        )
        for (pattern in patterns) {
            val match = pattern.matchEntire(normalized) ?: continue
            val titlePart = match.groupValues[1].trim().trim('-', ':', '–', '—').trim()
            if (titlePart.isNotBlank()) {
                return Request(
                    baseTitle = cleanTitle(titlePart),
                    season = match.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: seasonFallback.coerceAtLeast(1),
                    episode = match.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: episodeFallback.coerceAtLeast(1)
                )
            }
        }
        return Request(cleanTitle(normalized), seasonFallback.coerceAtLeast(1), episodeFallback.coerceAtLeast(1))
    }

    /** First phrase is preferred; following phrases are safe search fallbacks for provider naming. */
    fun buildQueries(title: String, season: Int, episode: Int, isMovie: Boolean): List<String> {
        val parsed = parseEpisodeSuffix(title, season, episode)
        val base = parsed.baseTitle
        if (base.isBlank()) return emptyList()
        if (isMovie) return listOf(base).distinct()

        val targetSeason = season.coerceAtLeast(1)
        val phrases = if (targetSeason == 1) {
            listOf(base)
        } else {
            val ordinal = ordinal(targetSeason)
            listOf("$base $ordinal Season", "$base Season $targetSeason", "$base Season ${roman(targetSeason)}")
        }
        return phrases.map(::cleanTitle).filter(String::isNotBlank).distinct()
    }

    /** EgyDead indexes episode pages by Arabic season/episode wording rather than English ordinals. */
    fun buildEgyDeadEpisodeQuery(title: String, season: Int, episode: Int): String {
        val base = baseTitle(title)
        if (base.isBlank()) return ""
        val seasonPhrase = when (season.coerceAtLeast(1)) {
            1 -> "الموسم الاول"
            2 -> "الموسم الثاني"
            3 -> "الموسم الثالث"
            4 -> "الموسم الرابع"
            5 -> "الموسم الخامس"
            6 -> "الموسم السادس"
            7 -> "الموسم السابع"
            8 -> "الموسم الثامن"
            9 -> "الموسم التاسع"
            10 -> "الموسم العاشر"
            else -> "الموسم ${season}"
        }
        return "$base $seasonPhrase الحلقة ${episode.coerceAtLeast(1)} مترجمة"
    }

    fun baseTitle(title: String): String = parseEpisodeSuffix(title).baseTitle

    private fun cleanTitle(value: String): String =
        value.replace(Regex("""(?i)\s+S\d{1,2}\s*E\d{1,3}\s*$"""), "")
            .replace(Regex("""(?i)\s+Season\s*\d{1,2}\s+Episode\s*\d{1,3}\s*$"""), "")
            .replace(Regex("""(?i)\s+\d{1,2}x\d{1,3}\s*$"""), "")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun ordinal(number: Int): String {
        val suffix = if (number % 100 in 11..13) "th" else when (number % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$number$suffix"
    }

    private fun roman(number: Int): String {
        val values = listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C",
            90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")
        var remaining = number.coerceIn(1, 3999)
        val result = StringBuilder()
        for ((value, symbol) in values) while (remaining >= value) {
            result.append(symbol)
            remaining -= value
        }
        return result.toString()
    }
}
