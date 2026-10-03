package com.x1colegal.materialyt

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.suggestion.SuggestionExtractor

fun suggestions() {
    val service = NewPipe.getService(0)
    val extractor = service.getSuggestionExtractor()
    extractor.suggestionList("minecraft")
}
