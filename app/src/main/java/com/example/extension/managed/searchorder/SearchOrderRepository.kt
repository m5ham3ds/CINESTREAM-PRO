package com.example.extension.managed.searchorder

import com.example.extension.managed.model.ContentType
import java.util.concurrent.atomic.AtomicReference

interface SearchOrderRepository {
    suspend fun getSearchOrder(forceRefresh: Boolean = false): SearchOrder?
    suspend fun getOrderForContentType(contentType: ContentType, forceRefresh: Boolean = false): List<String>
    suspend fun getDubbedOrder(language: com.example.domain.models.VariantLanguage = com.example.domain.models.VariantLanguage.ARABIC, forceRefresh: Boolean = false): List<String> = emptyList()
    fun clearCache()
}

class DefaultSearchOrderRepository(
    private val dataSource: SearchOrderDataSource = FirebaseSearchOrderDataSource(),
    private val registry: com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry = com.example.extension.managed.registry.ManagedExtensionRuntimeRegistry.INSTANCE,
    private val cacheTtlMillis: Long = 10 * 60 * 1000L // 10 minutes
) : SearchOrderRepository {

    private data class CachedSearchOrder(
        val order: SearchOrder,
        val timestamp: Long
    )

    private val cache = AtomicReference<CachedSearchOrder?>(null)

    override suspend fun getSearchOrder(forceRefresh: Boolean): SearchOrder? {
        // Runtime is deliberately local-only. Firestore updates are delivered by
        // ManagedExtensionRealtimeSyncManager into ManagedExtensionRuntimeRegistry.
        // Never initiate a network read from search/playback when the local snapshot is empty.
        val order = registry.getSearchOrder()
        return if (!order.isEmpty || order.updatedAt != null) {
            cache.set(CachedSearchOrder(order, System.currentTimeMillis()))
            order
        } else {
            cache.get()?.order
        }
    }

    override suspend fun getOrderForContentType(
        contentType: ContentType,
        forceRefresh: Boolean
    ): List<String> {
        // Phase 05M Rule 02 & 18: Zero Firebase network requests during Playback/Download!
        // Directly read canonical order from Local Runtime Registry snapshot
        val registryOrder = registry.getSearchOrder()
        if (!forceRefresh && (!registryOrder.isEmpty || registryOrder.updatedAt != null)) {
            return registryOrder.getOrderForContentType(contentType)
        }

        val searchOrder = getSearchOrder(forceRefresh) ?: return registry.getOrderForContentType(contentType)
        return searchOrder.getOrderForContentType(contentType)
    }

    override suspend fun getDubbedOrder(
        language: com.example.domain.models.VariantLanguage,
        forceRefresh: Boolean
    ): List<String> {
        val registryOrder = registry.getSearchOrder()
        if (!forceRefresh && (!registryOrder.isEmpty || registryOrder.updatedAt != null)) {
            return registryOrder.getDubbedOrder(language)
        }
        val searchOrder = getSearchOrder(forceRefresh) ?: return registry.getSearchOrder().getDubbedOrder(language)
        return searchOrder.getDubbedOrder(language)
    }

    override fun clearCache() {
        cache.set(null)
    }
}
