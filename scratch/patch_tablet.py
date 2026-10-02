import re

with open("app/src/main/java/com/x1colegal/materialyt/MainActivity.kt", "r") as f:
    content = f.read()

# Add Keyboard imports
if "import androidx.compose.ui.platform.LocalSoftwareKeyboardController" not in content:
    content = content.replace("import androidx.compose.ui.platform.LocalContext\n", "import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalSoftwareKeyboardController\nimport androidx.compose.foundation.text.KeyboardOptions\nimport androidx.compose.foundation.text.KeyboardActions\nimport androidx.compose.ui.text.input.ImeAction\nimport androidx.compose.foundation.lazy.grid.GridItemSpan\n")

# Patch OutlinedTextField in HomeScreen
home_tf = r"""OutlinedTextField\(query, \{ query = it \}, Modifier\.weight\(1f\), singleLine = true, placeholder = \{ Text\("Search YouTube"\) \}, shape = CircleShape, leadingIcon = \{ Icon\(Icons\.Default\.Search, null\) \}\)"""
home_tf_new = r"""val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search YouTube") }, shape = CircleShape, leadingIcon = { Icon(Icons.Default.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide(); if (query.isNotBlank()) scope.launch { loading = true; error = null; runCatching { withContext(Dispatchers.IO) { YouTubeRepository.search(query) } }.onSuccess { feed = it }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = it.message ?: "Search failed" }; loading = false } }))"""
content = re.sub(home_tf, home_tf_new, content)

# Patch OutlinedTextField in MusicFeedScreen
music_tf = r"""OutlinedTextField\(query, \{ query = it \}, Modifier\.weight\(1f\), singleLine = true, placeholder = \{ Text\("Search music"\) \}, shape = CircleShape, leadingIcon = \{ Icon\(Icons\.Default\.Search, null\) \}\)"""
music_tf_new = r"""val keyboardController = LocalSoftwareKeyboardController.current
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search music") }, shape = CircleShape, leadingIcon = { Icon(Icons.Default.Search, null) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide(); if (query.isNotBlank()) load { YouTubeRepository.musicSearch(query) } }))"""
content = re.sub(music_tf, music_tf_new, content)

# Patch HomeScreen LazyColumn
home_list = r"""        } else LazyColumn \{
            if \(results\.isNotEmpty\(\)\) item \{ Text\("Channels and playlists", Modifier\.padding\(16\.dp\), style = MaterialTheme\.typography\.titleLarge\) \}
            items\(results\) \{ item -> ResultRow\(item\) \{ selectedResult = item \} \}
            if \(feed\.isNotEmpty\(\) && results\.isNotEmpty\(\)\) item \{ Text\("Videos", Modifier\.padding\(16\.dp\), style = MaterialTheme\.typography\.titleLarge\) \}
            items\(feed\) \{ item -> FeedRow\(item\) \{ when \{ item\.channel -> selectedChannel = item\.url; item\.playlist -> selectedPlaylist = item\.url; else -> selected = item\.url \} \} \}
        \}"""
home_list_new = r"""        } else {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
            if (tablet) {
                LazyVerticalGrid(GridCells.Adaptive(320.dp), contentPadding = PaddingValues(16.dp)) {
                    if (results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Channels and playlists", Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.titleLarge) }
                    gridItems(results, span = { GridItemSpan(maxLineSpan) }) { item -> ResultRow(item) { selectedResult = item } }
                    if (feed.isNotEmpty() && results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("Videos", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleLarge) }
                    gridItems(feed) { item -> Box(Modifier.padding(8.dp)) { FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
                }
            } else {
                LazyColumn {
                    if (results.isNotEmpty()) item { Text("Channels and playlists", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                    items(results) { item -> ResultRow(item) { selectedResult = item } }
                    if (feed.isNotEmpty() && results.isNotEmpty()) item { Text("Videos", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) }
                    items(feed) { item -> FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } }
                }
            }
        }"""
content = re.sub(home_list, home_list_new, content)

# Patch NativeFeedScreen LazyColumn
native_list = r"""LazyColumn \{ items\(feed\) \{ item -> FeedRow\(item\) \{ when \{ item\.channel -> selectedChannel = item\.url; item\.playlist -> selectedPlaylist = item\.url; else -> selected = item\.url \} \} \} \}"""
native_list_new = r"""val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
        if (tablet) {
            LazyVerticalGrid(GridCells.Adaptive(320.dp), contentPadding = PaddingValues(8.dp)) {
                gridItems(feed) { item -> Box(Modifier.padding(8.dp)) { FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
            }
        } else {
            LazyColumn { items(feed) { item -> FeedRow(item) { when { item.channel -> selectedChannel = item.url; item.playlist -> selectedPlaylist = item.url; else -> selected = item.url } } } }
        }"""
content = re.sub(native_list, native_list_new, content)

with open("app/src/main/java/com/x1colegal/materialyt/MainActivity.kt", "w") as f:
    f.write(content)

