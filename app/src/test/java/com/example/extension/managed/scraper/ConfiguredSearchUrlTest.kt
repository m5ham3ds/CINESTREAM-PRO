package com.example.extension.managed.scraper

import com.example.extension.managed.model.ManagedExtension
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguredSearchUrlTest {
    private fun extension(id: String, url: String) = ManagedExtension(
        id = id,
        name = id,
        baseUrl = "https://example.test",
        scraperKey = id,
        searchUrl = url
    )

    @Test
    fun qfilmTemplateKeepsKeywordsParameterAndNativePageQuery() {
        val ext = extension("qfilm", "https://a.qfilm.tv/search.php?keywords={query}")
        assertEquals(
            "https://a.qfilm.tv/search.php?keywords=One+Piece",
            ConfiguredSearchUrl.build(ext, "One Piece", 1, "https://a.qfilm.tv/search.php?keywords=x")
        )
        assertEquals(
            "https://a.qfilm.tv/search.php?keywords=One+Piece&page=2",
            ConfiguredSearchUrl.build(ext, "One Piece", 2, "https://a.qfilm.tv/search.php?keywords=x")
        )
    }

    @Test
    fun pagePathTemplateKeepsRootSearchForFirstPageAndNativePathForLaterPages() {
        val ext = extension("anime4up", "https://w1.anime4up.rest/{pagePath}?s={query}")
        assertEquals(
            "https://w1.anime4up.rest/?s=One+Piece",
            ConfiguredSearchUrl.build(ext, "One Piece", 1, "https://w1.anime4up.rest/?s=x")
        )
        assertEquals(
            "https://w1.anime4up.rest/page/3/?s=One+Piece",
            ConfiguredSearchUrl.build(ext, "One Piece", 3, "https://w1.anime4up.rest/?s=x")
        )
    }

    @Test
    fun normalQueryTemplatesAppendPageOnlyWhenNoExplicitPageTokenExists() {
        val ext = extension("animeblkom", "https://animeblkom.net/search?query={query}")
        assertEquals(
            "https://animeblkom.net/search?query=One+Piece&page=2",
            ConfiguredSearchUrl.build(ext, "One Piece", 2, "https://animeblkom.net/search?query=x")
        )
    }
}
