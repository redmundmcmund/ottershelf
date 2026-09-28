#!/usr/bin/env bash
# The foliate reader's whole-file formats (KEPUB, MOBI, KF8/AZW3, AZW, FB2) checked in a browser,
# through the real reader page: app/src/main/assets/reader/reader.js with a stand-in window.Android
# (stub.js) and the app's routes played by a static server. testrun.js opens a sample as the app
# does (readerOpen with its format), then checks the title and TOC, a position's CFI restored to the
# same place, a selection's CFI (reader.js' onSelection), the highlight foliate draws for it, and
# search. The last sample is the MOBI with its PalmDOC header marked encrypted: expect the DRM error.
#
# Run from the repo root (JDK 18+ for jwebserver):
#   bash tools/reader-js-test/run.sh
# then open in Chromium or Chrome and read window.__result in the console:
#   http://127.0.0.1:8765/assets/reader/test.html?file=1&format=mobi    (MOBI6)
#   http://127.0.0.1:8765/assets/reader/test.html?file=2&format=azw3    (KF8)
#   http://127.0.0.1:8765/assets/reader/test.html?file=3&format=fb2
#   http://127.0.0.1:8765/assets/reader/test.html?file=4&format=kepub   (a zipped EPUB via zip.js)
#   http://127.0.0.1:8765/assets/reader/test.html?file=5&format=azw     (encrypted: DRM error)
# The samples were made from samples/sample.html with Calibre's ebook-convert (--authors "Test Author",
# and --mobi-file-type old for the MOBI; the EPUB without --authors).
# The scrolled flow (a scroll counts as a move, volume-key steps, taps, a swipe on to the next
# chapter and back, switching flow in place, the selection popup while scrolling, margins, the end
# of the book), jumps and turns back between chapters in pages (the next chapter's hidden View must
# not push the new one off screen) and the book's last page, and the fixed-layout spreads (reopened
# in place; the end of the book on a spread, both sides in landscape, one in portrait), on books
# make-epubs.sh builds:
#   http://127.0.0.1:8765/assets/reader/scroll.html?check=scroll
#   http://127.0.0.1:8765/assets/reader/scroll.html?check=pages
#   http://127.0.0.1:8765/assets/reader/scroll.html?check=fxl
#   http://127.0.0.1:8765/assets/reader/scroll.html?check=fxl5    (the last page on a spread's right)
# A hostile book (scripts inline, in handlers, links, SVG and srcdoc; the reader's page and script
# and its own /api/ files framed, loaded or refreshed to; links out; a layout animated on every
# frame) under the reader's own CSP (hostile.html is index.html for this server's origin):
#   http://127.0.0.1:8765/assets/reader/hostile.html
set -euo pipefail
cd "$(dirname "$0")/../.."
# READER_JS_ROOT and READER_JS_PORT keep parallel runs apart.
ROOT="${READER_JS_ROOT:-${TMPDIR:-/tmp}/bookorbit-reader-js-test}"
PORT="${READER_JS_PORT:-8765}"
rm -rf "$ROOT"
mkdir -p "$ROOT/assets"
cp -r app/src/main/assets/foliate app/src/main/assets/reader "$ROOT/assets/"
cp tools/reader-js-test/test.html tools/reader-js-test/stub.js tools/reader-js-test/testrun.js "$ROOT/assets/reader/"
S=tools/reader-js-test/samples
i=0
for f in sample.mobi sample.azw3 sample.fb2 sample.epub sample.mobi; do
  i=$((i + 1))
  mkdir -p "$ROOT/api/v1/books/files/$i"
  cp "$S/$f" "$ROOT/api/v1/books/files/$i/serve"
done
# File 5: mark record 0's PalmDOC encryption type (byte 12) as 2 (Mobipocket DRM).
perl -e 'open(F, "+<", $ARGV[0]) or die; binmode F; seek(F, 78, 0); read(F, $b, 4); seek(F, unpack("N", $b) + 12, 0); print F pack("n", 2)' \
  "$ROOT/api/v1/books/files/5/serve"
# Files 6 to 8 (testscroll.js): long chapters for the scrolled flow, and fixed-layout books of six
# and five pages. File 9 (testhostile.js): the hostile book, and its files on the /api/ route.
ORIGIN="http://127.0.0.1:$PORT"
bash tools/reader-js-test/make-epubs.sh "$ROOT" "$ORIGIN"
cp tools/reader-js-test/scroll.html tools/reader-js-test/testscroll.js tools/reader-js-test/testhostile.js "$ROOT/assets/reader/"
for i in 6 7 8 9; do mkdir -p "$ROOT/api/v1/books/files/$i"; done
mv "$ROOT/long.epub" "$ROOT/api/v1/books/files/6/serve"
mv "$ROOT/fxl.epub" "$ROOT/api/v1/books/files/7/serve"
mv "$ROOT/fxl5.epub" "$ROOT/api/v1/books/files/8/serve"
mv "$ROOT/hostile.epub" "$ROOT/api/v1/books/files/9/serve"
mkdir -p "$ROOT/api/v1/epub/9/file/OEBPS"
mv "$ROOT/hostile-page.xhtml" "$ROOT/api/v1/epub/9/file/OEBPS/page.xhtml"
mv "$ROOT/hostile-book.js" "$ROOT/api/v1/epub/9/file/OEBPS/book.js"
sed -e "s#https://appassets.androidplatform.net#$ORIGIN#g" \
  -e 's#<script type="module" src="reader.js"></script>#<script src="stub.js"></script>\n  &\n  <script type="module" src="testhostile.js"></script>#' \
  app/src/main/assets/reader/index.html > "$ROOT/assets/reader/hostile.html"
DIR="$ROOT"
command -v cygpath >/dev/null && DIR="$(cygpath -w "$ROOT")"
exec jwebserver -p "$PORT" -b 127.0.0.1 -d "$DIR"
