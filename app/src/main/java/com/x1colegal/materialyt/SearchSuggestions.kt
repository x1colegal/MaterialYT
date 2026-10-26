package com.x1colegal.materialyt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SearchSuggestions {
    suspend fun get(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { YouTubeRepository.searchSuggestions(query) }.getOrDefault(emptyList())
    }
}
