package com.example.ui.screens.home

/**
 * One-shot hand-off from Search genre tiles to Discover. Keeping this tiny
 * launch contract avoids adding query parameters to the main Discover route,
 * so existing bottom-navigation route matching remains unchanged.
 */
object DiscoverLaunchSelection {
    data class Selection(val kindName: String, val genreId: Int?)

    @Volatile
    private var pending: Selection? = null

    fun request(kindName: String?, genreId: Int?) {
        pending = kindName?.let { Selection(it, genreId) }
    }

    fun consume(): Selection? {
        val value = pending
        pending = null
        return value
    }
}
