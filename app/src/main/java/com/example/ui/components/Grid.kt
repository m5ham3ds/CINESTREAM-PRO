package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Extension for LazyListScope to render a virtualized grid where each row is an individual LazyColumn item.
 * This ensures that offscreen rows/cards are recycled and never composed simultaneously,
 * eliminating jank and high RAM usage in large lists.
 */
fun <T> LazyListScope.verticalGridItems(
    items: List<T>,
    columns: Int = 3,
    horizontalSpacing: Dp = 16.dp,
    verticalSpacing: Dp = 16.dp,
    horizontalPadding: Dp = 16.dp,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (T) -> Unit
) {
    val safeColumns = columns.coerceAtLeast(1)
    // Keep the existing duplicate-key guard, but avoid chunked() allocating a
    // second list-of-lists every time a screen recomposes during scrolling.
    val safeItems = if (key != null) items.distinctBy { key(it) } else items
    val rowCount = (safeItems.size + safeColumns - 1) / safeColumns
    items(
        count = rowCount,
        key = if (key != null) { rowIndex ->
            val startIndex = rowIndex * safeColumns
            val endIndex = (startIndex + safeColumns).coerceAtMost(safeItems.size)
            "grid_row_${rowIndex}_" + (startIndex until endIndex)
                .joinToString(separator = "_") { key(safeItems[it]).toString() }
        } else null,
        contentType = { "media_grid_row" }
    ) { rowIndex ->
        val startIndex = rowIndex * safeColumns
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = verticalSpacing),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
        ) {
            for (colIndex in 0 until safeColumns) {
                val itemIndex = startIndex + colIndex
                if (itemIndex < safeItems.size) {
                    Box(modifier = Modifier.weight(1f)) {
                        CompositionLocalProvider(
                            LocalMediaCardRevealIndex provides itemIndex
                        ) {
                            itemContent(safeItems[itemIndex])
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Fallback VerticalGrid for small fixed layouts or non-lazy scopes.
 */
@Composable
fun <T> VerticalGrid(
    items: List<T>,
    columns: Int = 3,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        for (i in items.indices step columns) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (j in 0 until columns) {
                    if (i + j < items.size) {
                        Box(modifier = Modifier.weight(1f)) {
                            content(items[i + j])
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
