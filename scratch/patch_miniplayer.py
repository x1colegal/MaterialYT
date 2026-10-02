import re

with open("app/src/main/java/com/x1colegal/materialyt/MainActivity.kt", "r") as f:
    content = f.read()

mini_player = r"""private fun MiniPlayer\(openMusic: \(\) -> Unit\) \{
    val track = AutoMusicService\.nowPlaying\.value \?: return
    var downwardDrag by remember \{ mutableFloatStateOf\(0f\) \}
    val animatedOffset by animateFloatAsState\(
        targetValue = downwardDrag\.coerceAtLeast\(0f\),
        animationSpec = spring\(stiffness = Spring\.StiffnessMediumLow\),
        label = "miniplayer_drag"
    \)
    val alpha = \(1f - \(animatedOffset / 100f\)\)\.coerceIn\(0f, 1f\)

    AnimatedVisibility\(
        visible = true,
        enter = slideInVertically \{ it \} \+ fadeIn\(\),
        exit = slideOutVertically \{ it \} \+ fadeOut\(\)
    \) \{
        Surface\(
            Modifier
                \.fillMaxWidth\(\)
                \.offset \{ IntOffset\(0, animatedOffset\.roundToInt\(\)\) \}
                \.graphicsLayer \{ this\.alpha = alpha \}
                \.height\(66\.dp\)
                \.pointerInput\(Unit\) \{
                    detectVerticalDragGestures\(
                        onDragEnd = \{ if \(downwardDrag > 60f\) AutoMusicService\.stop\(\) else downwardDrag = 0f \}
                    \) \{ change, dragAmount ->
                        change\.consume\(\)
                        downwardDrag \+= dragAmount
                    \}
                \}
                \.clickable \{ openMusic\(\) \},
            color = MaterialTheme\.colorScheme\.surfaceContainerHighest
        \) \{
            Row\(Modifier\.padding\(horizontal = 12\.dp\), verticalAlignment = Alignment\.CenterVertically\) \{
                AsyncImage\(track\.thumbnail, null, Modifier\.size\(48\.dp\)\.clip\(RoundedCornerShape\(8\.dp\)\), contentScale = ContentScale\.Crop\)"""

mini_player_new = r"""private fun MiniPlayer(openMusic: () -> Unit) {
    val track = AutoMusicService.nowPlaying.value ?: return
    var downwardDrag by remember { mutableFloatStateOf(0f) }
    val animatedOffset by animateFloatAsState(
        targetValue = downwardDrag.coerceAtLeast(0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "miniplayer_drag"
    )
    val alpha = (1f - (animatedOffset / 100f)).coerceIn(0f, 1f)
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut()
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, animatedOffset.roundToInt()) }
                .graphicsLayer { this.alpha = alpha }
                .height(if (tablet) 84.dp else 66.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (downwardDrag > (if (tablet) 100f else 60f)) AutoMusicService.stop() else downwardDrag = 0f }
                    ) { change, dragAmount ->
                        change.consume()
                        downwardDrag += dragAmount
                    }
                }
                .clickable { openMusic() },
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(track.thumbnail, null, Modifier.size(if (tablet) 64.dp else 48.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)"""

content = re.sub(mini_player, mini_player_new, content)

with open("app/src/main/java/com/x1colegal/materialyt/MainActivity.kt", "w") as f:
    f.write(content)

