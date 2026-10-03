import re
with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'r') as f:
    content = f.read()

content = re.sub(
    r'item \{ ChoiceSection\("Video decoder", DecoderMode.entries, videoDecoderMode, \{ it.label \}, onVideoDecoderMode\)\s*item \{ ChoiceSection\("Audio decoder", DecoderMode.entries, audioDecoderMode, \{ it.label \}, onAudioDecoderMode\) \} \}',
    r'item { ChoiceSection("Video decoder", DecoderMode.entries, videoDecoderMode, { it.label }, onVideoDecoderMode) }\n        item { ChoiceSection("Audio decoder", DecoderMode.entries, audioDecoderMode, { it.label }, onAudioDecoderMode) }',
    content,
    flags=re.MULTILINE
)

with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'w') as f:
    f.write(content)
