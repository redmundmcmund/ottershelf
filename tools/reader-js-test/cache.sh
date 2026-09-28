#!/usr/bin/env bash
# reader.js' response cache (cachedFetch: every file the streaming loader reads, and the section
# prefetch) checked on its own, with no server: the file's own code from `const responseCache` to
# the end of cachedFetch, then testcache.js (a stand-in fetch and the checks), in one page. Rerun
# after changing cachedFetch. Run from anywhere:
#   bash tools/reader-js-test/cache.sh
# It opens the page in headless Chrome (CHROME=<path> for another Chromium) and prints ALL PASS or
# what failed; without a Chrome it prints the page's path, to open by hand and read <pre id="out">.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="${READER_JS_ROOT:-${TMPDIR:-/tmp}/bookorbit-reader-js-test}-cache"
rm -rf "$OUT"
mkdir -p "$OUT"
page="$OUT/cache.html"
{
  echo '<!doctype html><html><head><meta charset="utf-8"></head><body><pre id="out">RUNNING</pre><script>'
  awk '/^const responseCache/{on=1} on{print} on&&/^function cachedFetch/{f=1} f&&/^}/{exit}' app/src/main/assets/reader/reader.js
  echo '</script><script>'
  cat tools/reader-js-test/testcache.js
  echo '</script></body></html>'
} > "$page"
CHROME="${CHROME:-}"
for c in "/c/Program Files/Google/Chrome/Application/chrome.exe" "/c/Program Files (x86)/Google/Chrome/Application/chrome.exe" google-chrome chromium chromium-browser; do
  [ -n "$CHROME" ] && break
  if [ -x "$c" ] || command -v "$c" >/dev/null 2>&1; then CHROME="$c"; fi
done
url="file://$page"
profile="$OUT/profile"
if command -v cygpath >/dev/null; then
  url="file:///$(cygpath -m "$page")"
  profile="$(cygpath -w "$profile")"
fi
if [ -z "$CHROME" ]; then
  echo "No Chrome found: open $url and read <pre id=\"out\">"
  exit 2
fi
# The first <pre id="out"> to its </pre> only (the scripts' text follows in the dump), reading on to
# the end so Chrome never writes into a closed pipe.
result="$("$CHROME" --headless=new --disable-gpu --no-first-run --user-data-dir="$profile" --virtual-time-budget=10000 --dump-dom "$url" 2>/dev/null \
  | awk 'done{next} /<pre id="out">/{on=1} on{print} on&&/<\/pre>/{on=0; done=1}' | sed -e 's/.*<pre id="out">//' -e 's/<\/pre>.*//')"
echo "$result"
case "$result" in "ALL PASS"*) exit 0 ;; *) exit 1 ;; esac
