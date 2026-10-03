package com.x1colegal.materialyt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe

object SearchSuggestions {
    suspend fun get(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val service = NewPipe.getService(0)
            service.suggestionExtractor.suggestionList(query)
        }.getOrDefault(emptyList())
    }
}
