import re

with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'r') as f:
    content = f.read()

# Replace block 1
content = re.sub(
    r'items\(comments\) \{ comment ->\n\s*Column\(Modifier\.padding\(20\.dp,\s*12\.dp\)\) \{\n\s*Text\(comment\.uploaderName[^}]+\}\n\s*\} \}',
    r'items(comments) { comment -> CommentNode(comment) }',
    content,
    flags=re.MULTILINE
)

# Replace block 2
content = re.sub(
    r'items\(comments\) \{ comment ->\n\s*Column\(Modifier\.padding\(16\.dp,\s*10\.dp\)\) \{\n.*?\}\n\s*\}',
    r'items(comments) { comment -> CommentNode(comment) }',
    content,
    flags=re.DOTALL
)

# Replace block 3 (single line)
content = re.sub(
    r'items\(comments\) \{ comment ->\n\s*Column\(Modifier\.padding\(16\.dp, 10\.dp\)\) \{ Text\(comment\.uploaderName.*?\}\n\s*\}',
    r'items(comments) { comment -> CommentNode(comment) }',
    content,
    flags=re.DOTALL
)

with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'w') as f:
    f.write(content)

