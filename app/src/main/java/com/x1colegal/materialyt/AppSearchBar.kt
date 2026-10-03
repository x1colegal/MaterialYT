package com.x1colegal.materialyt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    placeholder: String = "Search YouTube",
    trailingIcon: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    SearchBar(
        query = query,
        onQueryChange = { 
            onQueryChange(it)
            searchJob?.cancel()
            if (it.isNotBlank()) {
                searchJob = scope.launch {
                    delay(200)
                    suggestions = SearchSuggestions.get(it)
                }
            } else {
                suggestions = emptyList()
            }
        },
        onSearch = {
            active = false
            keyboard?.hide()
            onSearch(it)
        },
        active = active,
        onActiveChange = { active = it },
        placeholder = { Text(placeholder) },
        leadingIcon = { 
            if (active) {
                IconButton(onClick = { active = false }) { Icon(Icons.Default.ArrowBack, "Back") }
            } else {
                Icon(Icons.Default.Search, "Search")
            }
        },
        trailingIcon = {
            if (active && query.isNotBlank()) {
                IconButton(onClick = { onQueryChange(""); suggestions = emptyList() }) { Icon(Icons.Default.Clear, "Clear") }
            } else if (!active && trailingIcon != null) {
                trailingIcon()
            }
        },
        modifier = modifier.fillMaxWidth().padding(if (active) 0.dp else 12.dp)
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(suggestions) { suggestion ->
                ListItem(
                    headlineContent = { Text(suggestion) },
                    leadingContent = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().clickable {
                        onQueryChange(suggestion)
                        active = false
                        keyboard?.hide()
                        onSearch(suggestion)
                    }
                )
            }
        }
    }
}
