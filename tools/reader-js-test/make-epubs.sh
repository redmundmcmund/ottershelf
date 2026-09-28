#!/usr/bin/env bash
# Builds three EPUBs for the scrolled-flow, paginated and fixed-layout checks (testscroll.js) into $1:
#   long.epub  four chapters of 60 generated paragraphs each (long enough to scroll), then a
#              short afterword of 8
#   fxl.epub   a pre-paginated (fixed-layout) book of six 600x800 pages, spreads by default
#   fxl5.epub  the same with five pages, so its last page is the right side of a spread
# and, for the hostile-book check (testhostile.js), hostile.epub with the two files it reaches on the
# page's /api/ route (hostile-page.xhtml, hostile-book.js), its absolute URLs on origin $2.
# Made with the JDK's jar (a zip; foliate's zip reader doesn't need mimetype stored first).
set -euo pipefail
OUT="$1"
ORIGIN="${2:-http://127.0.0.1:8765}"
JAR="${JAVA_HOME:+$JAVA_HOME/bin/}jar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

container() {
  mkdir -p "$1/META-INF"
  printf 'application/epub+zip' > "$1/mimetype"
  cat > "$1/META-INF/container.xml" <<'EOF'
<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
EOF
}

zipit() {
  local dest="$2"
  command -v cygpath >/dev/null && dest="$(cygpath -w "$2")"
  (cd "$1" && "$JAR" --create --file "$dest" --no-manifest -0 mimetype META-INF OEBPS)
}

# --- long.epub ---
L="$work/long"
container "$L"
mkdir -p "$L/OEBPS"
manifest=""
spine=""
nav=""
for c in 1 2 3 4; do
  {
    echo '<?xml version="1.0" encoding="utf-8"?>'
    echo '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter '"$c"'</title></head><body>'
    echo "<h1 id=\"c$c\">Chapter $c</h1>"
    for p in $(seq 1 60); do
      echo "<p id=\"c${c}p$p\">Chapter $c, paragraph $p. The orbit station hummed quietly while the crew went about the slow work of the evening, checking gauges, writing short notes and watching the planet turn below them in the dark.</p>"
    done
    echo '</body></html>'
  } > "$L/OEBPS/ch$c.xhtml"
  manifest+="<item id=\"ch$c\" href=\"ch$c.xhtml\" media-type=\"application/xhtml+xml\"/>"
  spine+="<itemref idref=\"ch$c\"/>"
  nav+="<li><a href=\"ch$c.xhtml\">Chapter $c</a></li>"
done
# A short last section (a few % of the book, but taller than a screen), as an afterword often is:
# at its bottom foliate's fraction is well short of 1 in the scrolled flow.
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Afterword</title></head><body>'
  echo '<h1 id="aw">Afterword</h1>'
  for p in $(seq 1 8); do
    echo "<p id=\"awp$p\">Afterword, paragraph $p. The orbit station hummed quietly while the crew went about the slow work of the evening, checking gauges, writing short notes and watching the planet turn below them in the dark.</p>"
  done
  echo '</body></html>'
} > "$L/OEBPS/afterword.xhtml"
manifest+="<item id=\"afterword\" href=\"afterword.xhtml\" media-type=\"application/xhtml+xml\"/>"
spine+="<itemref idref=\"afterword\"/>"
nav+="<li><a href=\"afterword.xhtml\">Afterword</a></li>"
cat > "$L/OEBPS/nav.xhtml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>
<body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>
EOF
cat > "$L/OEBPS/content.opf" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">bookorbit-long-sample</dc:identifier><dc:title>Long Sample</dc:title><dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest>
  <spine>$spine</spine>
</package>
EOF
zipit "$L" "$OUT/long.epub"

# --- fxl.epub, fxl5.epub: fxl <pages> <file> ---
fxl() {
F="$work/fxl$1"
container "$F"
mkdir -p "$F/OEBPS"
manifest=""
spine=""
nav=""
for n in $(seq 1 "$1"); do
  cat > "$F/OEBPS/p$n.xhtml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page $n</title><meta name="viewport" content="width=600, height=800"/></head>
<body style="margin:0;width:600px;height:800px;background:#dde;font:bold 200px sans-serif;display:flex;align-items:center;justify-content:center">$n</body></html>
EOF
  manifest+="<item id=\"p$n\" href=\"p$n.xhtml\" media-type=\"application/xhtml+xml\"/>"
  spine+="<itemref idref=\"p$n\"/>"
  nav+="<li><a href=\"p$n.xhtml\">Page $n</a></li>"
done
cat > "$F/OEBPS/nav.xhtml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>
<body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>
EOF
cat > "$F/OEBPS/content.opf" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id" prefix="rendition: http://www.idpf.org/vocab/rendition/#">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">bookorbit-fxl-sample</dc:identifier><dc:title>Fixed Sample</dc:title><dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
    <meta property="rendition:layout">pre-paginated</meta>
    <meta property="rendition:spread">auto</meta>
  </metadata>
  <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest>
  <spine>$spine</spine>
</package>
EOF
zipit "$F" "$OUT/$2"
}
fxl 6 fxl.epub
fxl 5 fxl5.epub

# --- hostile.epub: every way a book could try to reach the reader page ---
# Each attempt, should it run, calls the bridge with 'crafted:<what>' (window.Android in the app's
# WebView, which has it in every frame; the page's in a browser). Chapters: the in-page attempts and
# two links, a chapter whose CSS animates its layout on every frame, two that refresh themselves (to
# the reader's page, and to a book's file on the /api/ route), and a plain last one.
H="$work/hostile"
container "$H"
mkdir -p "$H/OEBPS"
CALL='(window.Android || top.Android).onError'
paras() {
  for p in $(seq 1 30); do
    echo "<p>Paragraph $p of $1: the reader lays this chapter out as it would any other, whatever the book tries around it.</p>"
  done
}
hchapter() { # hchapter <file> <title> <extra head> <body>
  {
    echo '<?xml version="1.0" encoding="utf-8"?>'
    echo "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$2</title>$3</head><body>"
    echo "<h1>$2</h1>"
    echo "$4"
    paras "$2"
    echo '</body></html>'
  } > "$H/OEBPS/$1"
}
API="$ORIGIN/api/v1/epub/9/file/OEBPS"
hchapter attempts.xhtml Attempts '' "<script>$CALL('crafted:inline')</script>
<p><img src=\"missing.png\" alt=\"\" onerror=\"$CALL('crafted:onerror')\"/></p>
<p><a id=\"js-link\" href=\"javascript:$CALL('crafted:js-link')\">A javascript: link.</a>
<a id=\"web-link\" href=\"https://example.com/\">A link out of the book.</a></p>
<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"12\" height=\"12\"><script>$CALL('crafted:svg-inline')</script><rect width=\"12\" height=\"12\"/></svg>
<iframe title=\"srcdoc\" width=\"12\" height=\"12\" srcdoc=\"&lt;script&gt;$CALL('crafted:srcdoc')&lt;/script&gt;\"></iframe>
<iframe title=\"drawing\" width=\"24\" height=\"24\" src=\"drawing.svg\"></iframe>
<object data=\"drawing.svg\" type=\"image/svg+xml\" width=\"24\" height=\"24\"></object>
<script type=\"module\" src=\"$ORIGIN/assets/reader/reader.js\"></script>
<iframe title=\"reader\" width=\"12\" height=\"12\" src=\"$ORIGIN/assets/reader/index.html\"></iframe>
<iframe title=\"page\" width=\"12\" height=\"12\" src=\"$API/page.xhtml?fileId=9\"></iframe>
<script src=\"$API/book.js?fileId=9\"></script>"
hchapter animated.xhtml Animated '<style>@keyframes grow { from { height: 20px } to { height: 1400px } } .grow { animation: grow 0.8s linear infinite alternate; background: #ccd; }</style>' '<div class="grow"></div>'
hchapter refresh.xhtml 'A Refresh' "<meta http-equiv=\"refresh\" content=\"1;url=$ORIGIN/assets/reader/index.html\"/>" ''
hchapter refresh-api.xhtml 'Another Refresh' "<meta http-equiv=\"refresh\" content=\"1;url=$API/page.xhtml?fileId=9\"/>" ''
hchapter end.xhtml 'The End' '' ''
cat > "$H/OEBPS/drawing.svg" <<EOF
<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="#888"/><script>$CALL('crafted:svg-file')</script></svg>
EOF
cat > "$OUT/hostile-page.xhtml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Page</title></head>
<body><p>The book's own page.</p><script>$CALL('crafted:api-page')</script></body></html>
EOF
echo "$CALL('crafted:api-script')" > "$OUT/hostile-book.js"
manifest=""
spine=""
nav=""
for c in attempts:Attempts animated:Animated refresh:'A Refresh' refresh-api:'Another Refresh' end:'The End'; do
  id="${c%%:*}"
  manifest+="<item id=\"$id\" href=\"$id.xhtml\" media-type=\"application/xhtml+xml\"/>"
  spine+="<itemref idref=\"$id\"/>"
  nav+="<li><a href=\"$id.xhtml\">${c#*:}</a></li>"
done
manifest+='<item id="drawing" href="drawing.svg" media-type="image/svg+xml"/>'
cat > "$H/OEBPS/nav.xhtml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head>
<body><nav epub:type="toc"><ol>$nav</ol></nav></body></html>
EOF
cat > "$H/OEBPS/content.opf" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">bookorbit-hostile-sample</dc:identifier><dc:title>Hostile Sample</dc:title><dc:language>en</dc:language>
    <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
  </metadata>
  <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$manifest</manifest>
  <spine>$spine</spine>
</package>
EOF
zipit "$H" "$OUT/hostile.epub"
