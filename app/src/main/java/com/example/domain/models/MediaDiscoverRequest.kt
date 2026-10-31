package com.example.domain.models

/** Server-side TMDB Discover filters shared by Movies, Series and Anime. */
data class MediaDiscoverRequest(
    val page: Int = 1,
    val sortBy: String = "popularity.desc",
    val genreIds: List<Int> = emptyList(),
    val year: Int? = null,
    val minimumVoteCount: Int = 0,
    val providerIds: List<Int> = emptyList(),
    val watchRegion: String = "US",
    val status: String? = null,
    val animeOnly: Boolean = false
)
