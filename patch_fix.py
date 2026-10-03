import re
with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'r') as f:
    content = f.read()

content = re.sub(
    r'items\(comments\) \{ comment ->\n\s*CommentNode\(comment\)\n\s*Text\(comment\.uploaderName[^}]+\}\n\s*\} \}',
    r'items(comments) { comment -> CommentNode(comment) } }',
    content,
    flags=re.MULTILINE
)

with open('app/src/main/java/com/x1colegal/materialyt/MainActivity.kt', 'w') as f:
    f.write(content)
