package com.example.data.repository

import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

/**
 * The items are paired with a Boolean that is true only for movies. Cursor and error
 * state are kept separately for each tab so a failed request cannot poison other feeds.
 */
data class TabPaginationState(
    val items: List<Pair<Any, Boolean>> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isLoadingFirstPage: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val hasNextPage: Boolean = true,
    val failedRequestPage: Int? = null
)

class TmdbListPaginator(
    private val fetchMoviesPage: suspend (page: Int) -> PaginatedResult<Movie>,
    private val fetchSeriesPage: suspend (page: Int) -> PaginatedResult<Series>,
    private val fetchAnimePage: suspend (page: Int) -> PaginatedResult<Series>,
    private val coroutineScope: CoroutineScope
) {
    private val _tab0State = MutableStateFlow(TabPaginationState())
    private val _tab1State = MutableStateFlow(TabPaginationState())
    private val _tab2State = MutableStateFlow(TabPaginationState())
    private val _tab3State = MutableStateFlow(TabPaginationState())

    val tab0State: StateFlow<TabPaginationState> = _tab0State.asStateFlow()
    val tab1State: StateFlow<TabPaginationState> = _tab1State.asStateFlow()
    val tab2State: StateFlow<TabPaginationState> = _tab2State.asStateFlow()
    val tab3State: StateFlow<TabPaginationState> = _tab3State.asStateFlow()

    private val _tabStates = MutableStateFlow(
        mapOf(
            0 to TabPaginationState(), 1 to TabPaginationState(),
            2 to TabPaginationState(), 3 to TabPaginationState()
        )
    )
    val tabStates: StateFlow<Map<Int, TabPaginationState>> = _tabStates.asStateFlow()

    private val tabJobs = mutableMapOf<Int, Job>()
    private val tabGenerations = mutableMapOf<Int, Long>()

    fun getTabStateFlow(tabIndex: Int): StateFlow<TabPaginationState> = when (tabIndex) {
        0 -> tab0State
        1 -> tab1State
        2 -> tab2State
        3 -> tab3State
        else -> tab0State
    }

    fun getState(tabIndex: Int): TabPaginationState = when (tabIndex) {
        0 -> _tab0State.value
        1 -> _tab1State.value
        2 -> _tab2State.value
        3 -> _tab3State.value
        else -> _tabStates.value[tabIndex] ?: TabPaginationState()
    }

    private fun nextGeneration(tabIndex: Int): Long {
        val next = (tabGenerations[tabIndex] ?: 0L) + 1L
        tabGenerations[tabIndex] = next
        return next
    }

    private fun isCurrentGeneration(tabIndex: Int, generation: Long): Boolean =
        tabGenerations[tabIndex] == generation

    private fun updateState(tabIndex: Int, transform: (TabPaginationState) -> TabPaginationState) {
        val newState = when (tabIndex) {
            0 -> _tab0State.updateAndGet(transform)
            1 -> _tab1State.updateAndGet(transform)
            2 -> _tab2State.updateAndGet(transform)
            3 -> _tab3State.updateAndGet(transform)
            else -> transform(_tabStates.value[tabIndex] ?: TabPaginationState())
        }
        _tabStates.update { current -> current + (tabIndex to newState) }
    }

    private fun mediaKey(media: Any, isMovie: Boolean): String {
        val id = if (isMovie) (media as Movie).id else (media as Series).id
        return "${if (isMovie) "m" else "s"}_$id"
    }

    fun ensureTabLoaded(tabIndex: Int) {
        val state = getState(tabIndex)
        // Do not automatically hammer the gateway after an error. Error state remains
        // visible until a user explicitly retries or pulls to refresh.
        if (state.currentPage == 0 && !state.isLoadingFirstPage && state.loadMoreError == null) {
            loadFirstPage(tabIndex)
        }
    }

    private fun describeLoadError(error: Throwable): String = when (error) {
        is HttpException -> when (error.code()) {
            429 -> "The catalog server is temporarily busy. Wait a few seconds, then retry."
            504 -> "No cached response is available. Check your connection and retry."
            in 500..599 -> "The catalog server is temporarily unavailable (HTTP ${error.code()})."
            else -> "The catalog request failed (HTTP ${error.code()})."
        }
        is IOException -> "Could not connect to the catalog. Check your connection and retry."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Could not load catalog items. Please retry."
    }

    private data class LoadedPage(
        val items: List<Pair<Any, Boolean>>,
        val page: Int,
        val totalPages: Int,
        val totalResults: Int
    )

    private suspend fun fetchPage(tabIndex: Int, page: Int): LoadedPage = when (tabIndex) {
        1 -> fetchMoviesPage(page).let { result ->
            LoadedPage(result.items.map { it to true }, result.page, result.totalPages, result.totalResults)
        }
        2 -> fetchSeriesPage(page).let { result ->
            LoadedPage(result.items.map { it to false }, result.page, result.totalPages, result.totalResults)
        }
        3 -> fetchAnimePage(page).let { result ->
            LoadedPage(result.items.map { it to false }, result.page, result.totalPages, result.totalResults)
        }
        else -> kotlinx.coroutines.coroutineScope {
            val movies = async { fetchMoviesPage(page) }
            val series = async { fetchSeriesPage(page) }
            val anime = async { fetchAnimePage(page) }
            val movieResult = movies.await()
            val seriesResult = series.await()
            val animeResult = anime.await()
            val items = (movieResult.items.map { it to true } +
                seriesResult.items.map { it to false } +
                animeResult.items.map { it to false })
                .distinctBy { mediaKey(it.first, it.second) }
            LoadedPage(
                items = items,
                page = page,
                totalPages = maxOf(movieResult.totalPages, seriesResult.totalPages, animeResult.totalPages).coerceAtLeast(1),
                totalResults = maxOf(movieResult.totalResults, seriesResult.totalResults, animeResult.totalResults)
            )
        }
    }

    private fun applyLoadedPage(tabIndex: Int, result: LoadedPage, replace: Boolean) {
        updateState(tabIndex) { old ->
            val items = if (replace) result.items
            else (old.items + result.items).distinctBy { mediaKey(it.first, it.second) }
            val page = result.page.coerceAtLeast(1)
            val totalPages = result.totalPages.coerceAtLeast(page)
            old.copy(
                items = items,
                currentPage = page,
                totalPages = totalPages,
                isLoadingFirstPage = false,
                isLoadingMore = false,
                loadMoreError = null,
                failedRequestPage = null,
                hasNextPage = page < totalPages
            )
        }
    }

    fun loadFirstPage(tabIndex: Int) {
        val generation = nextGeneration(tabIndex)
        tabJobs[tabIndex]?.cancel()
        // Publish loading synchronously before launching network work. Compose can then
        // render skeletons immediately, instead of briefly rendering a false empty state.
        updateState(tabIndex) {
            it.copy(
                isLoadingFirstPage = true,
                isLoadingMore = false,
                loadMoreError = null,
                failedRequestPage = null
            )
        }
        val job = coroutineScope.launch {
            try {
                val result = fetchPage(tabIndex, 1)
                if (!isCurrentGeneration(tabIndex, generation)) return@launch
                applyLoadedPage(tabIndex, result, replace = true)
            } catch (error: CancellationException) {
                if (isCurrentGeneration(tabIndex, generation)) {
                    updateState(tabIndex) { it.copy(isLoadingFirstPage = false, isLoadingMore = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (isCurrentGeneration(tabIndex, generation)) {
                    updateState(tabIndex) {
                        it.copy(
                            isLoadingFirstPage = false,
                            isLoadingMore = false,
                            loadMoreError = describeLoadError(error),
                            failedRequestPage = 1
                        )
                    }
                }
            } finally {
                // Guard against an unexpected cancellation/error path leaving a spinner
                // stuck forever, without clearing a newer request's loading state.
                if (isCurrentGeneration(tabIndex, generation) && getState(tabIndex).isLoadingFirstPage) {
                    updateState(tabIndex) { it.copy(isLoadingFirstPage = false) }
                }
            }
        }
        tabJobs[tabIndex] = job
    }

    fun loadMore(tabIndex: Int) {
        val state = getState(tabIndex)
        if (state.isLoadingMore || state.isLoadingFirstPage || !state.hasNextPage || state.loadMoreError != null) return
        if (state.currentPage <= 0) {
            loadFirstPage(tabIndex)
            return
        }

        val nextPage = state.currentPage + 1
        if (nextPage > state.totalPages) {
            updateState(tabIndex) { it.copy(hasNextPage = false) }
            return
        }

        val generation = tabGenerations[tabIndex] ?: 0L
        updateState(tabIndex) { it.copy(isLoadingMore = true, loadMoreError = null, failedRequestPage = null) }
        val job = coroutineScope.launch {
            try {
                val result = fetchPage(tabIndex, nextPage)
                if (!isCurrentGeneration(tabIndex, generation)) return@launch
                applyLoadedPage(tabIndex, result, replace = false)
            } catch (error: CancellationException) {
                if (isCurrentGeneration(tabIndex, generation)) {
                    updateState(tabIndex) { it.copy(isLoadingMore = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (isCurrentGeneration(tabIndex, generation)) {
                    updateState(tabIndex) {
                        it.copy(
                            isLoadingMore = false,
                            loadMoreError = describeLoadError(error),
                            failedRequestPage = nextPage
                        )
                    }
                }
            } finally {
                if (isCurrentGeneration(tabIndex, generation) && getState(tabIndex).isLoadingMore) {
                    updateState(tabIndex) { it.copy(isLoadingMore = false) }
                }
            }
        }
        tabJobs[tabIndex] = job
    }

    /** Retry only tabs which previously recorded a load error after connectivity returns. */
    fun retryFailedTabs() {
        for (tabIndex in 0..3) {
            val state = getState(tabIndex)
            if (state.loadMoreError != null && !state.isLoadingFirstPage && !state.isLoadingMore) retry(tabIndex)
        }
    }

    fun retry(tabIndex: Int) {
        val state = getState(tabIndex)
        if (state.isLoadingFirstPage || state.isLoadingMore) return
        val failedPage = state.failedRequestPage ?: if (state.currentPage <= 0) 1 else state.currentPage + 1
        updateState(tabIndex) { it.copy(loadMoreError = null, failedRequestPage = null) }
        if (failedPage <= 1 || state.currentPage <= 0) loadFirstPage(tabIndex) else loadMore(tabIndex)
    }

    fun refresh(tabIndex: Int) = loadFirstPage(tabIndex)

    fun resetTab(tabIndex: Int) {
        nextGeneration(tabIndex)
        tabJobs[tabIndex]?.cancel()
        updateState(tabIndex) { TabPaginationState() }
    }

    fun onTabSwitched(newTabIndex: Int, cancelInFlight: Boolean = true) {
        if (cancelInFlight) {
            tabJobs.filterKeys { it != newTabIndex }.toList().forEach { (tabIndex, job) ->
                nextGeneration(tabIndex)
                job.cancel()
                updateState(tabIndex) { it.copy(isLoadingFirstPage = false, isLoadingMore = false) }
            }
        }
        ensureTabLoaded(newTabIndex)
    }

    fun cancelInFlight() {
        tabJobs.toList().forEach { (tabIndex, job) ->
            nextGeneration(tabIndex)
            job.cancel()
            updateState(tabIndex) { it.copy(isLoadingFirstPage = false, isLoadingMore = false) }
        }
        tabJobs.clear()
    }
}
