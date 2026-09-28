// Hosts BookOrbit's vendored foliate-js the way client/src/features/reader/epub does, with the
// native app as the shell. Requests to /api/v1/* are same-origin here and are answered by the app
// (the downloaded copy, or the server with the bearer token added), so no credentials ever live in
// this page. The app only answers this book's own routes: an EPUB's epub/ routes (streamed section
// by section), or for the other formats foliate reads (KEPUB, MOBI, AZW3, AZW, FB2) the whole file
// from books/files/:fileId/serve, opened with foliate's makeBook as the web reader does.
import { makeBook, makeStreamingBook } from '../foliate/view.js'

// The app's bridge, for the page it loaded only. This script is one of the page's own, so a book can
// load it (or the whole page) again inside a chapter; that copy gets a bridge that does nothing, or
// it would tell the app it is ready and the app would open the book again under the one on screen.
const native = window.top === window ? window.Android : new Proxy({}, { get: () => () => {} })
const API = '/api/v1'
const LEFT_ZONE = 0.3
const RIGHT_ZONE = 0.7
// Scrolled flow: the volume keys move this much of a screen, so the last lines read stay in view.
const SCROLL_STEP = 0.85
// Scrolled flow: a swipe at least this long (CSS px = dp) that starts at a chapter's end (or start)
// carries on into the next (or previous) chapter, as foliate's next() does at the end of one.
const PULL_PX = 72
// Within this of the end (or start) of a chapter counts as there.
const EDGE_PX = 4
// foliate's reading-time unit: bytes of a section per minute (view.js: new SectionProgress(sections, 1500, 1600)).
const SIZE_PER_MINUTE = 1600

let view = null
// What the app sends (ReaderStyles.pageSettings in the app, which unit-tests it): the chapters'
// CSS, the page colours, and the paginator's layout attributes. This page doesn't turn reading
// settings into styles itself.
let settings = {
  css: '', bg: '#ffffff', fg: '#000000', dark: false,
  flow: 'paginated', animated: true, columns: '2', gap: '6%', margin: '28px',
  maxInlineSize: '720px', maxBlockSize: '1440px', fixedLayoutSpread: 'auto',
}

let appliedCSS = null

function applyPageColours() {
  document.documentElement.style.setProperty('--bg', settings.bg)
  document.documentElement.style.setProperty('--fg', settings.fg)
}

/** The scrolled flow is on (a fixed-layout book always shows pages). */
function isScrolled() {
  return settings.flow === 'scrolled' && !!view?.renderer && !view.isFixedLayout
}

// Each renderer attribute change re-lays out the chapter (and recomputes the visible range, CFI,
// etc.), so only touch what actually changed. Don't call this on every section `load`: the
// paginator re-applies its stored styles to each new section itself, and attributes persist.
function applySettings() {
  applyPageColours()
  if (!view?.renderer) return
  if (drawnDark !== !!settings.dark) redrawAnnotations()
  if (view.isFixedLayout && settings.fixedLayoutSpread !== openedSpread) {
    reopenHere() // foliate lays out a fixed-layout book's spreads once, when it opens
    return
  }
  const r = view.renderer
  const attrs = {
    flow: settings.flow === 'scrolled' ? 'scrolled' : 'paginated',
    'max-column-count': String(settings.columns),
    'max-inline-size': settings.maxInlineSize,
    'max-block-size': settings.maxBlockSize,
    gap: settings.gap,
    margin: settings.margin,
  }
  for (const [name, value] of Object.entries(attrs)) {
    if (r.getAttribute(name) !== value) r.setAttribute(name, value)
  }
  // Slide page turns (foliate's `animated`); the Nexus build left them off for its GPU.
  if (settings.animated && !r.hasAttribute('animated')) r.setAttribute('animated', '')
  else if (!settings.animated && r.hasAttribute('animated')) r.removeAttribute('animated')
  const css = settings.css ?? ''
  if (css !== appliedCSS) {
    appliedCSS = css
    r.setStyles?.(css)
  }
}

// --- Fixed-layout spreads (the web's fixedLayoutSpread) -----------------------------------------
// 'none' shows one page at a time; 'auto' the book's own spreads. foliate reads it when the book
// opens, so a change reopens the book where it is (as the web reader does).

let openedSpread = 'auto'

function prepareRendition(book) {
  openedSpread = settings.fixedLayoutSpread
  if (book.rendition?.layout !== 'pre-paginated') return
  if (!('ownSpread' in bookRef)) bookRef.ownSpread = book.rendition.spread
  book.rendition = { ...book.rendition, spread: settings.fixedLayoutSpread === 'none' ? 'none' : bookRef.ownSpread }
}

let reopening = false

async function reopenHere() {
  if (reopening || !view) return
  reopening = true
  const old = view
  let cfi = null
  let fraction = 0
  try {
    cfi = old.lastLocation?.cfi ?? null
    fraction = old.lastLocation?.fraction ?? 0
  } catch (e) { /* open at the fraction */ }
  try {
    view = null
    old.close()
    old.remove()
    await showBook(old.book, cfi, fraction * 100)
  } catch (e) {
    native.onError(e?.message || String(e))
    return
  } finally {
    reopening = false
  }
  // Changed again while it reopened: once more, for the latest.
  if (view?.isFixedLayout && settings.fixedLayoutSpread !== openedSpread) reopenHere()
}

// --- Scrolled flow ---------------------------------------------------------------------------

/** Where the scroll is in the chapter: { start, end } (both for a chapter shorter than the screen). */
function scrollEdges() {
  const r = view?.renderer
  if (!isScrolled() || !r.viewSize) return { start: false, end: false }
  return { start: r.start <= EDGE_PX, end: r.viewSize - r.end <= EDGE_PX }
}

function hasSectionAfter(index, dir) {
  const sections = view?.book?.sections
  if (!sections || typeof index !== 'number') return false
  for (let i = index + dir; i >= 0 && i < sections.length; i += dir) if (sections[i]?.linear !== 'no') return true
  return false
}

function currentIndex() {
  return view?.renderer?.getContents?.()[0]?.index
}

/**
 * At the end of the book, with nothing left to turn or scroll to: the last page of the last linear
 * section (paginated), its bottom (scrolled), or a page on screen that is the book's last (fixed
 * layout, both sides of a spread counted). foliate's fraction can't say this: paginated it marks the
 * end of what shows, so the last pages of a long book all come within half a percent of 1; scrolled
 * it marks the top, so the bottom of a short book, or a last section shorter than the screen, stays
 * short of it; a spread reports its first page. Only a flag: the saved percentage stays foliate's
 * (the server works the user's status out from it).
 */
function atBookEnd(index) {
  const r = view?.renderer
  if (!r) return false
  if (view.isFixedLayout) return (r.getContents?.() ?? []).some((c) => pageShown(c.doc) && !hasSectionAfter(c.index, 1))
  if (isScrolled()) return typeof index === 'number' && scrollEdges().end && !hasSectionAfter(index, 1)
  // Laid out (a section's text is at least one page between the paginator's two blank ones).
  return r.pages > 2 && !!r.atEnd
}

/** A fixed-layout page is on screen (in portrait a spread shows one side, the other's frame hidden). */
function pageShown(doc) {
  const frame = doc?.defaultView?.frameElement?.parentElement
  return !frame || frame.style.display !== 'none'
}

/** The volume keys' move in the scrolled flow: most of a screen, or to the end of the chapter. */
function scrollStep(dir) {
  const r = view.renderer
  const room = dir > 0 ? r.viewSize - r.end : r.start
  if (room <= EDGE_PX) return undefined // at the chapter's edge: foliate goes on to the next one
  return Math.min(r.size * SCROLL_STEP, room)
}

/**
 * A swipe onward that starts at the end of a chapter (the scroll can't go further) opens the next
 * chapter at its start; one backward at the start opens the previous chapter at its end. Passive,
 * so scrolling never waits for it.
 */
function attachPull(target) {
  let pull = null
  target.addEventListener('touchstart', (e) => {
    pull = null
    if (e.touches.length !== 1) return
    const edges = scrollEdges()
    if (!edges.start && !edges.end) return
    const t = e.touches[0]
    pull = { x: t.screenX, y: t.screenY, edges }
  }, { passive: true })
  target.addEventListener('touchmove', (e) => {
    if (pull && e.touches.length !== 1) pull = null
  }, { passive: true })
  target.addEventListener('touchend', (e) => {
    const p = pull
    pull = null
    const t = e.changedTouches?.[0]
    if (!p || !t || !isScrolled()) return
    const dy = p.y - t.screenY
    const dx = p.x - t.screenX
    if (Math.abs(dy) < PULL_PX || Math.abs(dy) < Math.abs(dx) * 1.5) return
    const doc = e.currentTarget?.getSelection ? e.currentTarget : null
    const selection = doc?.getSelection?.()
    if (selection && !selection.isCollapsed) return
    const now = scrollEdges()
    const index = currentIndex()
    if (dy > 0 && p.edges.end && now.end && hasSectionAfter(index, 1)) turn(() => view.next())
    else if (dy < 0 && p.edges.start && now.start && hasSectionAfter(index, -1)) turn(() => view.prev())
  }, { passive: true })
}

// A selection's popup would stay behind while the text scrolls: hide it at once and show it again
// where the selection is once the scrolling stops.
let resurface = null
function onRendererScroll() {
  if (!isScrolled() || (!selectionShown && !resurface)) return
  if (selectionShown) {
    resurface = selectionAt
    hideSelection()
  }
  clearTimeout(selectionTimer)
  selectionTimer = setTimeout(() => {
    const s = resurface
    resurface = null
    if (s) emitSelection(s.doc, s.index)
  }, 350)
}

/** foliate's whole-book reading time (its minutes: section bytes / SIZE_PER_MINUTE), for the app's shares. */
function bookMinutes(book) {
  const size = (book?.sections ?? []).reduce((sum, s) => sum + (s.linear !== 'no' && s.size > 0 ? s.size : 0), 0)
  return size / SIZE_PER_MINUTE
}

const finite = (x) => (typeof x === 'number' && Number.isFinite(x) ? x : null)

// Some EPUBs split into hundreds of one-page sections, so most page turns cross a section boundary
// and fetch the next section's files through the native side on the spot. Prefetch the neighbours
// into a small response cache that the streaming loader reads through.
//
// Deliberately not done by calling section.load() ahead of time: foliate's loader doesn't count a
// second reference to a top-level section, so releasing a prefetch revokes the blob URL the
// paginator is about to display, and navigation locks up.
const responseCache = new Map() // url -> Promise<{ status, type, body }>
const RESPONSE_CACHE_MAX = 96
// Bounded by bytes too: every file the loader reads comes through here (XHTML, CSS, fonts, images)
// and foliate also keeps a blob of each resource of the sections it holds, so in an illustrated or
// fixed-layout book 96 whole bodies came to tens of MB. A body over RESPONSE_BODY_MAX is handed
// over but not kept, and the oldest go while the kept ones come to more than RESPONSE_BYTES_MAX.
const RESPONSE_BODY_MAX = 1024 * 1024
const RESPONSE_BYTES_MAX = 12 * 1024 * 1024
const responseSizes = new Map() // url -> bytes of a kept body that came in
let responseBytes = 0
let bookRef = null
let prefetchHandle = 0

/** Drops [url] from the response cache, and its bytes from the total. */
function dropResponse(url) {
  responseCache.delete(url)
  const size = responseSizes.get(url)
  if (size === undefined) return
  responseSizes.delete(url)
  responseBytes -= size
}

/**
 * Whether [section] is worth fetching ahead: not one over RESPONSE_BODY_MAX, which would be dropped
 * as it came in and fetched again a turn later, when holdSections loads it. Its size is the
 * manifest's (the zip entry's bytes, what the server sends), 0 when unknown.
 */
const prefetchable = (section) => !!section?.id && !(section.size > RESPONSE_BODY_MAX)

function cachedFetch(url, init) {
  let entry = responseCache.get(url)
  if (entry) {
    responseCache.delete(url) // re-insert as most recent
  } else {
    entry = fetch(url, init).then(async (r) => ({
      status: r.status,
      type: r.headers.get('Content-Type'),
      body: r.ok ? await r.arrayBuffer() : null,
    }))
    // Only keep successes: a 502 from a dropped connection would otherwise break that section for
    // the rest of the session, even after the connection comes back.
    const own = entry
    const forget = () => { if (responseCache.get(url) === own) dropResponse(url) }
    entry.then((e) => {
      if (e.status < 200 || e.status >= 300) return forget()
      if (responseCache.get(url) !== own) return // evicted while it came in
      const size = e.body?.byteLength ?? 0
      if (size > RESPONSE_BODY_MAX) return forget()
      responseSizes.set(url, size)
      responseBytes += size
      // Oldest first; one still coming in has no size yet and stays (the next section's prefetch).
      for (const old of responseCache.keys()) {
        if (responseBytes <= RESPONSE_BYTES_MAX) break
        if (responseSizes.has(old)) dropResponse(old)
      }
    }, forget)
  }
  responseCache.set(url, entry)
  while (responseCache.size > RESPONSE_CACHE_MAX) dropResponse(responseCache.keys().next().value)
  return entry.then((e) => new Response(e.body, { status: e.status, headers: e.type ? { 'Content-Type': e.type } : {} }))
}

// Same URL shape as streaming-loader.js getFileUrl().
function fileUrl(name) {
  return `${API}/epub/${bookRef.bookId}/file/${name.split('/').map(encodeURIComponent).join('/')}?fileId=${bookRef.fileId}`
}

// Adjacent sections are also fully prepared (parsed, resources wrapped, blob URL built) by holding a
// reference with section.load(), so the paginator's own load() on a turn is a cache hit. This relies
// on the top-level ref-count fix in epub.js Loader.ref().
const held = new Set()

function holdSections(index) {
  const sections = view?.book?.sections
  if (!sections) return
  const want = new Set([index + 1, index - 1].filter((i) => i >= 0 && i < sections.length))
  for (const i of [...held]) {
    if (!want.has(i)) {
      held.delete(i)
      try { sections[i].unload?.() } catch (e) { /* ignore */ }
    }
  }
  for (const i of want) {
    if (held.has(i) || !sections[i]?.load) continue
    held.add(i)
    Promise.resolve().then(() => sections[i].load()).catch(() => held.delete(i))
  }
}

function schedulePrefetch(index) {
  cancelIdleCallback(prefetchHandle)
  if (typeof index !== 'number') return
  // After the turn has painted, not during it.
  prefetchHandle = requestIdleCallback(() => {
    const sections = view?.book?.sections
    if (!sections || !bookRef) return
    holdSections(index)
    // A whole-file book (MOBI, FB2...) is already in memory: there is nothing to fetch ahead.
    const next = bookRef.streamed ? sections[index + 2] : null
    if (prefetchable(next)) cachedFetch(fileUrl(next.id)).catch(() => {})
  }, { timeout: 800 })
}

function flattenToc(items, depth = 0, out = []) {
  for (const item of items || []) {
    out.push({ label: (item.label || '').trim(), href: item.href || null, depth })
    if (item.subitems?.length) flattenToc(item.subitems, depth + 1, out)
  }
  return out
}

function attachInput(doc, index) {
  attachSelection(doc, index)
  attachPull(doc)
  doc.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0) return
    if (e.target.closest?.('a[href]')) return
    const selection = doc.getSelection?.()
    if (selection && !selection.isCollapsed) return
    // A tap on a highlight opens it (foliate's show-annotation, below), not a page turn.
    if (annotationAt(doc, e)) return
    // The tap that dismissed a selection (and its popup) doesn't turn the page too.
    if (performance.now() - selectionClearedAt < 600) return
    // Scrolled: no page-turn zones; a tap anywhere shows or hides the bars.
    if (isScrolled()) {
      native.onToggleUi()
      return
    }
    // Convert iframe-relative coordinates to the viewport, as useFoliateInput does.
    const frame = doc.defaultView?.frameElement
    const x = (frame ? frame.getBoundingClientRect().left : 0) + e.clientX
    const f = x / window.innerWidth
    if (f < LEFT_ZONE) turn(() => view.goLeft())
    else if (f > RIGHT_ZONE) turn(() => view.goRight())
    else native.onToggleUi()
  })
}

// Page turns the user started (tap zones, the app's keys) that are still running. A turn into the next
// section reaches foliate as 'navigation', like a jump, so the relocates while it runs count as the user's.
let userTurns = 0
function turn(go) {
  userTurns++
  Promise.resolve().then(go).catch(() => {}).finally(() => { userTurns-- })
}

async function restore(cfi, percentage) {
  if (cfi) {
    try {
      const result = await view.goTo(cfi)
      if (result && typeof result.index === 'number') return
    } catch (e) { /* fall through */ }
  }
  if (percentage > 0) await view.goToFraction(percentage / 100)
  else await view.goTo(0)
}

// Formats read as one file: everything foliate opens but EPUB (whose epub/ routes stream it).
const isWholeFile = (format) => !!format && format !== 'epub'

// A Mobipocket/Kindle file whose text records are encrypted (Kindle DRM). foliate's MOBI reader
// doesn't check and would show garbage, so say so instead. Record 0's PalmDOC header holds the
// encryption type (0 = none) at byte 12; record 0's offset is the first entry after the PDB header.
async function isEncryptedMobi(file) {
  if (file.size < 96) return false
  const head = new DataView(await file.slice(0, 96).arrayBuffer())
  const type = String.fromCharCode(...new Uint8Array(head.buffer, 60, 8))
  if (type !== 'BOOKMOBI') return false // the only type mobi.js's isMOBI takes
  const record0 = head.getUint32(78)
  if (record0 + 14 > file.size) return false
  const palmdoc = new DataView(await file.slice(record0, record0 + 14).arrayBuffer())
  return palmdoc.getUint16(12) !== 0
}

/** The whole file of a KEPUB/MOBI/AZW3/AZW/FB2 book, as a File foliate's makeBook can tell apart. */
async function fetchWholeFile(fileId, format) {
  const res = await fetch(`${API}/books/files/${fileId}/serve`)
  if (!res.ok) throw new Error(`Couldn't load the book (HTTP ${res.status})`)
  const blob = await res.blob()
  // Named and typed as the web reader does (useFoliate.ts): makeBook tells MOBI, KF8 and AZW by
  // their header, FB2 by the .fb2 name (or its XML), and opens any other zip (KEPUB) as an EPUB.
  const file = new File([blob], `book-file-${fileId}.${format}`, { type: 'application/zip' })
  if (await isEncryptedMobi(file)) throw new Error('This book is DRM-protected and can’t be opened')
  return file
}

window.readerOpen = async (args) => {
  try {
    if (args.settings) settings = { ...settings, ...args.settings }
    applyPageColours()

    const whole = isWholeFile(args.format)
    let info = null
    let file = null
    if (whole) {
      file = await fetchWholeFile(args.fileId, args.format)
    } else {
      const res = await fetch(`${API}/epub/${args.bookId}/info?fileId=${args.fileId}`)
      if (!res.ok) throw new Error(`Couldn't load the book (HTTP ${res.status})`)
      info = await res.json()
    }

    bookRef = { bookId: args.bookId, fileId: args.fileId, streamed: !whole }
    const book = whole
      ? await makeBook(file)
      : await makeStreamingBook(args.bookId, `${API}/epub`, info, cachedFetch, null, args.fileId)
    // The app opens a book once per page; were it asked again, one view, not two stacked.
    if (view) {
      const old = view
      view = null
      try { old.close() } catch (e) { /* removed all the same */ }
      old.remove()
    }
    await showBook(book, args.cfi, args.percentage || 0)
  } catch (e) {
    native.onError(e?.message || String(e))
  }
}

let totalMinutes = 0

/** Shows [book] in a new foliate-view at [cfi] (else [percentage]); also used to reopen it. */
async function showBook(book, cfi, percentage) {
  view = document.createElement('foliate-view')
  view.style.cssText = 'width:100%;height:100%;display:block;'
  document.getElementById('app').appendChild(view)

  view.addEventListener('load', (e) => attachInput(e.detail.doc, e.detail.index))
  attachAnnotations(view)
  // A link out of the book (https:, mailto:, javascript:, intent:...): foliate would window.open it
  // from this page, which has the bridge; only the CSP and the app's refusal of any navigation of
  // this page stood in the way. Links out of books do nothing, as they always did here.
  view.addEventListener('external-link', (e) => e.preventDefault())
  view.addEventListener('relocate', (e) => {
    const d = e.detail
    schedulePrefetch(d.section?.current)
    const edges = scrollEdges()
    // Only cheap fields here; reading d.cfi/tocItem/kobo*/koreader* would compute them (see the
    // lazy getters in view.js #onRelocate). The full location follows once the page is idle.
    queueRelocate({
      fraction: d.fraction ?? 0,
      page: d.location?.current ?? null,
      pages: d.location?.total ?? null,
      // The user moved (a turn, swipe or scroll), as opposed to foliate settling a section (the first
      // layout, fonts, images) or opening at a position, which can change the fraction too.
      turned: userTurns > 0 || d.reason === 'page' || d.reason === 'snap' || d.reason === 'scroll',
      // foliate's reading time left (minutes) in the chapter and the book, and the whole book's.
      timeSection: finite(d.time?.section),
      timeTotal: finite(d.time?.total),
      timeBook: totalMinutes || null,
      // Scrolled to the end of a chapter with another after it (a swipe onward goes there).
      chapterEnd: edges.end && hasSectionAfter(d.section?.current, 1),
      // At the end of the book (the next book in the series is offered there).
      bookEnd: atBookEnd(d.section?.current),
    })
  })

  prepareRendition(book)
  appliedCSS = null
  totalMinutes = bookMinutes(book)
  await view.open(book)
  view.renderer.addEventListener('scroll', onRendererScroll)
  attachPull(view.renderer)
  applySettings()
  await restore(cfi, percentage)
  native.onOpened(JSON.stringify({
    title: typeof book.metadata?.title === 'string' ? book.metadata.title : null,
    toc: flattenToc(book.toc),
    fixedLayout: !!view.isFixedLayout,
  }))
}

// Relocates go to the app at most once every RELOCATE_MS, and only when something in them changed. A
// chapter whose CSS animates its layout makes foliate relocate on every frame with nothing moved:
// 60 calls a second, each one activity to the app (the screen kept on, a reading session that never
// goes idle). The first after a pause goes at once, so a turn shows straight away; later ones wait,
// the newest replacing the one waiting, the user's move carried over (a turn isn't lost to the settle
// after it). One still waiting goes when the page is hidden.
const RELOCATE_MS = 250
let relocateWaiting = null
let relocateTimer = 0
let relocateSent = null // as sent (JSON)
let relocateSentAt = -Infinity

function queueRelocate(r) {
  if (relocateWaiting?.turned) r.turned = true
  relocateWaiting = r
  if (relocateTimer) return
  const wait = relocateSentAt + RELOCATE_MS - performance.now()
  if (wait > 0) relocateTimer = setTimeout(sendRelocate, wait)
  else sendRelocate()
}

function sendRelocate() {
  clearTimeout(relocateTimer)
  relocateTimer = 0
  const r = relocateWaiting
  relocateWaiting = null
  if (!r) return
  const json = JSON.stringify(r)
  if (json === relocateSent) return
  relocateSent = json
  relocateSentAt = performance.now()
  native.onRelocate(json)
  scheduleFullLocation()
}

/** Everything the server's progress endpoint wants, plus labels for the toolbar. */
function fullLocation() {
  const d = view?.lastLocation
  if (!d) return null
  return {
    cfi: d.cfi ?? null,
    fraction: d.fraction ?? 0,
    source: d.source ?? null,
    koboLocationType: d.koboLocationType ?? null,
    koboLocationValue: d.koboLocationValue ?? null,
    contentSourceProgressPercent: d.contentSourceProgressPercent ?? null,
    koreaderProgress: d.koreaderProgress ?? null,
    tocLabel: d.tocItem?.label?.trim() ?? null,
    tocHref: d.tocItem?.href ?? null,
    page: d.location?.current ?? null,
    pages: d.location?.total ?? null,
  }
}

// The full location is pushed to the app shortly after every settle. The app keeps the newest and
// saves it from outside the page, so closing the reader never has to wait for this page.
let fullLocationHandle = 0
function scheduleFullLocation() {
  cancelIdleCallback(fullLocationHandle)
  fullLocationHandle = requestIdleCallback(pushLocation, { timeout: 300 })
}

function pushLocation() {
  cancelIdleCallback(fullLocationHandle)
  const loc = fullLocation()
  if (loc) native.onLocation(JSON.stringify(loc))
}

// Leaving: the app going to the background (WebView.onPause) or the page being torn down.
function leave() {
  sendRelocate()
  pushLocation()
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') leave() })
window.addEventListener('pagehide', leave)

// --- Highlights (the web reader's useFoliateAnnotations) ---------------------------------------
// Drawn with foliate's overlayer at the CFIs the web reader makes, so they line up on the web and
// KOReader. The app sends the whole list; each section draws its own as its overlayer is created.

const SEARCH_PREFIX = 'foliate-search:'
const annotationStyles = new Map() // cfi -> { color, style }
const annotationSections = new Map() // cfi -> section index, resolved once
let drawnDark = false

function svg(tag) {
  return document.createElementNS('http://www.w3.org/2000/svg', tag)
}

function drawFunction(style) {
  const dark = !!settings.dark
  switch (style) {
    case 'underline':
    case 'strikethrough':
      return (rects, { color }) => {
        const g = svg('g')
        g.setAttribute('fill', color)
        for (const { left, top, bottom, width } of Array.from(rects)) {
          const el = svg('rect')
          el.setAttribute('x', String(left))
          el.setAttribute('y', String(style === 'underline' ? bottom - 2 : (top + bottom) / 2))
          el.setAttribute('height', '2')
          el.setAttribute('width', String(width))
          g.append(el)
        }
        return g
      }
    case 'squiggly':
      return (rects, { color }) => {
        const g = svg('g')
        g.setAttribute('fill', 'none')
        g.setAttribute('stroke', color)
        g.setAttribute('stroke-width', '2')
        const block = 3
        for (const { left, bottom, width } of Array.from(rects)) {
          const n = Math.max(1, Math.round(width / block / 1.5))
          const inline = width / n
          const el = svg('path')
          el.setAttribute('d', `M${left} ${bottom}` + Array.from({ length: n }, (_, i) => `l${inline} ${i % 2 ? block : -block}`).join(''))
          g.append(el)
        }
        return g
      }
    case 'invert':
      return (rects, { color }) => {
        const g = svg('g')
        g.setAttribute('fill', color)
        g.style.mixBlendMode = 'difference'
        for (const { left, top, height, width } of Array.from(rects)) {
          const el = svg('rect')
          el.setAttribute('x', String(left))
          el.setAttribute('y', String(top))
          el.setAttribute('height', String(height))
          el.setAttribute('width', String(width))
          g.append(el)
        }
        return g
      }
    default:
      // The web multiplies at 30%; on a dark page that would vanish, so a plain wash there.
      return (rects, { color }) => {
        const g = svg('g')
        g.setAttribute('fill', color)
        g.style.opacity = dark ? '0.32' : '0.3'
        if (!dark) g.style.mixBlendMode = 'multiply'
        for (const { left, top, height, width } of Array.from(rects)) {
          const el = svg('rect')
          el.setAttribute('x', String(left))
          el.setAttribute('y', String(top))
          el.setAttribute('height', String(height))
          el.setAttribute('width', String(width))
          g.append(el)
        }
        return g
      }
  }
}

function sectionOf(cfi) {
  if (annotationSections.has(cfi)) return annotationSections.get(cfi)
  let index = null
  try { index = view?.resolveNavigation(cfi)?.index ?? null } catch (e) { index = null }
  annotationSections.set(cfi, index)
  return index
}

function shownSections() {
  return new Set((view?.renderer?.getContents?.() ?? []).map((c) => c.index))
}

function drawAnnotation(cfi) {
  Promise.resolve().then(() => view?.addAnnotation({ value: cfi })).catch(() => {})
}

function redrawAnnotations() {
  drawnDark = !!settings.dark
  const shown = shownSections()
  for (const cfi of annotationStyles.keys()) {
    const index = sectionOf(cfi)
    if (index == null || shown.has(index)) drawAnnotation(cfi)
  }
}

function attachAnnotations(v) {
  v.addEventListener('draw-annotation', (e) => {
    const { draw, annotation } = e.detail ?? {}
    const stored = annotation?.value && annotationStyles.get(annotation.value)
    if (!draw || !stored) return
    drawnDark = !!settings.dark
    draw(drawFunction(stored.style), { color: stored.color })
  })
  // A section's overlayer is attached just after this event: draw its highlights once it is.
  v.addEventListener('create-overlay', (e) => {
    const index = e.detail?.index
    setTimeout(() => {
      for (const cfi of annotationStyles.keys()) {
        const at = sectionOf(cfi)
        if (at == null || at === index) drawAnnotation(cfi)
      }
    }, 0)
  })
  v.addEventListener('show-annotation', (e) => {
    const { value, range } = e.detail ?? {}
    if (!value || value.startsWith(SEARCH_PREFIX)) return
    let rect = null
    try { rect = rectOf(range, range.startContainer.ownerDocument) } catch (err) { rect = null }
    native.onAnnotationTap(JSON.stringify({ cfi: value, rect }))
  })
}

function annotationAt(doc, e) {
  const content = view?.renderer?.getContents?.().find((c) => c.doc === doc)
  const [value] = content?.overlayer?.hitTest?.(e) ?? []
  return Boolean(value && !value.startsWith(SEARCH_PREFIX))
}

/** The app's list: [{ cfi, color, style }]. Removed ones are erased, new and changed ones drawn. */
window.readerSetAnnotations = (list) => {
  const next = new Map((list || []).filter((a) => a?.cfi).map((a) => [a.cfi, { color: a.color, style: a.style }]))
  for (const cfi of [...annotationStyles.keys()]) {
    if (next.has(cfi)) continue
    annotationStyles.delete(cfi)
    Promise.resolve().then(() => view?.deleteAnnotation({ value: cfi })).catch(() => {})
  }
  const shown = shownSections()
  for (const [cfi, style] of next) {
    const old = annotationStyles.get(cfi)
    if (old && old.color === style.color && old.style === style.style) continue
    annotationStyles.set(cfi, style)
    if (!view) continue
    const index = sectionOf(cfi)
    if (index == null || shown.has(index)) drawAnnotation(cfi)
  }
}

// --- Selection (the web reader's useFoliateSelection, touch path) ------------------------------
// The WebView's own selection menu is suppressed by the app; once a selection settles, the app gets
// its text, CFI and place on screen and shows its popup there. While it changes the popup hides.

let selectionShown = false
let selectionAt = null // { doc, index } of the selection shown
let selectionTimer = 0
let selectionClearedAt = -Infinity

function rectOf(range, doc) {
  const r = range.getBoundingClientRect()
  const frame = doc?.defaultView?.frameElement
  const f = frame ? frame.getBoundingClientRect() : { left: 0, top: 0 }
  return { left: f.left + r.left, top: f.top + r.top, right: f.left + r.right, bottom: f.top + r.bottom }
}

function hideSelection() {
  if (!selectionShown) return
  selectionShown = false
  selectionClearedAt = performance.now()
  native.onSelectionCleared()
}

function emitSelection(doc, index) {
  const selection = doc.getSelection?.()
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) return
  const range = selection.getRangeAt(0)
  const text = range.toString().trim()
  if (!text) return
  let cfi = null
  try { cfi = view.getCFI(index, range) } catch (e) { cfi = null }
  let chapter = null
  try { chapter = view.getProgressOf?.(index, range)?.tocItem?.label?.trim() || null } catch (e) { chapter = null }
  selectionShown = true
  selectionAt = { doc, index }
  native.onSelection(JSON.stringify({ text, cfi, chapter, rect: rectOf(range, doc) }))
}

function attachSelection(doc, index) {
  doc.addEventListener('selectionchange', () => {
    clearTimeout(selectionTimer)
    resurface = null
    hideSelection()
    const selection = doc.getSelection?.()
    if (!selection || selection.isCollapsed || selection.rangeCount === 0) return
    selectionTimer = setTimeout(() => emitSelection(doc, index), 350)
  })
}

window.readerClearSelection = () => {
  clearTimeout(selectionTimer)
  resurface = null
  selectionShown = false
  try { view?.deselect() } catch (e) { /* ignore */ }
}

// --- Search (the web reader's useSearch over foliate's search.js) ------------------------------
// Results go to the app in batches; foliate outlines each match in the page until cleared.

let searchId = 0

window.readerSearch = async (query, id) => {
  searchId = id
  if (!view) return
  const send = (items, progress, done, error) =>
    native.onSearchResults(JSON.stringify({ id, items, progress, done, error: error ?? null }))
  let batch = []
  let progress = 0
  let sentAt = 0
  try {
    for await (const result of view.search({ query })) {
      if (searchId !== id) return
      if (result === 'done') break
      if (typeof result?.progress === 'number') progress = result.progress
      for (const item of result?.subitems ?? []) {
        batch.push({
          cfi: item.cfi ?? '',
          pre: item.excerpt?.pre ?? '',
          match: item.excerpt?.match ?? '',
          post: item.excerpt?.post ?? '',
          section: result.label ?? '',
        })
      }
      const now = performance.now()
      if (now - sentAt > 250) {
        send(batch, progress, false)
        batch = []
        sentAt = now
      }
    }
    if (searchId === id) send(batch, 1, true)
  } catch (e) {
    if (searchId === id) send(batch, 1, true, e?.message || String(e))
  }
}

window.readerClearSearch = () => {
  searchId = -1
  try { view?.clearSearch() } catch (e) { /* ignore */ }
}

window.readerLocation = () => JSON.stringify(fullLocation())
window.readerSettings = (s) => { settings = { ...settings, ...s }; applySettings() }
window.readerGoTo = (target) => view?.goTo(target)
window.readerGoToFraction = (f) => view?.goToFraction(f)
// The app's keys (volume): a page, or in the scrolled flow most of a screen (at a chapter's end, on
// into the next chapter, as foliate's next() does).
window.readerNext = () => { if (view?.renderer) turn(() => (isScrolled() ? view.next(scrollStep(1)) : view.goRight())) }
window.readerPrev = () => { if (view?.renderer) turn(() => (isScrolled() ? view.prev(scrollStep(-1)) : view.goLeft())) }

native.onReady()
