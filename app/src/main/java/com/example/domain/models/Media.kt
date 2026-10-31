package com.example.domain.models

import androidx.compose.runtime.Immutable

@Immutable
data class CastMember(
    val id: String,
    val name: String,
    val character: String,
    val profileUrl: String?,
    val order: Int = 0,
    /** Number of episodes credited to this performer when available. */
    val episodeCount: Int = 0
)

@Immutable
data class CrewMember(
    val id: String,
    val name: String,
    val department: String,
    val jobs: List<String> = emptyList(),
    val profileUrl: String? = null,
    val episodeCount: Int = 0
)

@Immutable
data class VideoTrailer(
    val name: String,
    val key: String,
    val type: String = "Trailer",
    val isOfficial: Boolean = false,
    val site: String = "YouTube",
    val publishedAt: String? = null,
    val language: String? = null
)

@Immutable
data class Genre(
    val id: Int,
    val name: String
)

@Immutable
data class Movie(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val releaseDate: String? = null,
    val year: Int,
    val rating: Double,
    val genres: List<String>,
    val runtime: Int,
    val language: String = "en",
    val country: String? = null,
    val director: String? = null,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<VideoTrailer> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val sourceUrl: String? = null,
    val providerId: String? = null,
    val mode: String? = null,
    val sourceCandidates: List<ProviderSourceCandidate> = emptyList(),
    // Extended FlixQuest-style details (optional for legacy/provider-supplied media).
    val backdropImages: List<String> = emptyList(),
    val posterImages: List<String> = emptyList(),
    val collectionId: Int? = null,
    val collectionName: String? = null,
    val collectionPosterUrl: String? = null,
    val collectionBackdropUrl: String? = null,
    val status: String? = null,
    val productionCompanies: List<String> = emptyList(),
    val productionCountries: List<String> = emptyList(),
    val spokenLanguages: List<String> = emptyList(),
    val imdbId: String? = null,
    val homepage: String? = null
)

@Immutable
data class Series(
    val id: String,
    val title: String,
    val originalTitle: String? = null,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val firstAirDate: String? = null,
    val year: Int,
    val rating: Double,
    val genres: List<String>,
    val cast: List<CastMember> = emptyList(),
    val trailers: List<VideoTrailer> = emptyList(),
    val seasons: List<Season> = emptyList(),
    val creator: String? = null,
    val status: String? = null,
    val originalLanguage: String? = null,
    val originCountry: List<String> = emptyList(),
    val genreIds: List<Int> = emptyList(),
    val sourceUrl: String? = null,
    val providerId: String? = null,
    val mode: String? = null,
    val sourceCandidates: List<ProviderSourceCandidate> = emptyList(),
    // Optional TMDB metadata used by the details and artwork tabs.
    val backdropImages: List<String> = emptyList(),
    val posterImages: List<String> = emptyList(),
    val lastAirDate: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val episodeRuntime: List<Int> = emptyList(),
    val productionCompanies: List<String> = emptyList(),
    val productionCountries: List<String> = emptyList(),
    val spokenLanguages: List<String> = emptyList(),
    val networkNames: List<String> = emptyList(),
    val imdbId: String? = null,
    val homepage: String? = null
)

@Immutable
data class Season(
    val id: String,
    val seriesId: String,
    val seasonNumber: Int,
    val title: String,
    val posterUrl: String,
    val episodeCount: Int = 0,
    val episodes: List<Episode> = emptyList(),
    val overview: String = "",
    val airDate: String? = null,
    val rating: Double = 0.0,
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val trailers: List<VideoTrailer> = emptyList(),
    val backdropImages: List<String> = emptyList(),
    val posterImages: List<String> = emptyList()
)

@Immutable
data class Episode(
    val id: String,
    val episodeNumber: Int,
    val title: String,
    val overview: String,
    val thumbnailUrl: String,
    val duration: Int,
    val rating: Double = 0.0,
    val seasonNumber: Int = 1
)

@Immutable
data class PersonDetails(
    val id: String,
    val name: String,
    val biography: String,
    val profileUrl: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val knownFor: String?,
    val movies: List<Movie>,
    val series: List<Series>,
    val deathday: String? = null,
    val gender: Int? = null,
    val popularity: Double? = null
)