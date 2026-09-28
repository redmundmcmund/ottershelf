// A book that tries every way into the reader page (files/9, hostile.epub from make-epubs.sh),
// opened through reader.js under the reader's own CSP: hostile.html is index.html itself, for this
// server's origin. Its scripts (inline, an event handler, a javascript: link, SVG inline and as a
// file, srcdoc), the reader's page and script loaded from a chapter, its own file framed or loaded
// as a script from the /api/ route, and refreshes to both never reach the bridge; its links out
// open nothing; a chapter whose CSS animates its layout on every frame sends at most four
// relocates a second, at its top and part-way down (where they all differ), the last one where the
// page stopped; and the book still reads. Results in window.__result; each check lists what it saw
// and `ok`.
const out = { check: 'hostile', checks: {} }
window.__result = null
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const since = (t, name) => window.__calls.filter((c) => c.name === name && c.at > t)

// What the reader page tells the app about any book.
const NORMAL = new Set(['onReady', 'onOpened', 'onRelocate', 'onLocation'])
const SETTINGS = {
  css: 'html { font-size: 18px !important; }', bg: '#ffffff', fg: '#000000', dark: false,
  flow: 'paginated', animated: false, columns: '1', gap: '6%', margin: '28px',
  maxInlineSize: '720px', maxBlockSize: '1440px', fixedLayoutSpread: 'auto',
}

const view = () => document.querySelector('foliate-view')
const contents = () => view().renderer.getContents()[0]

// A window the page opens is recorded instead (foliate hands a link out of the book to window.open).
window.__opened = []
window.open = (url) => { window.__opened.push(String(url)); return null }

async function hostileCheck() {
  document.getElementById('app').style.cssText = 'position:absolute;left:0;top:0;width:411px;height:780px'
  window.readerOpen({ bookId: 9, fileId: 9, format: 'kepub', cfi: null, percentage: 0, settings: SETTINGS })
  const opened = await window.__wait('onOpened', () => true, 20000)
  out.checks.opened = { toc: opened.toc.map((t) => t.label), ok: opened.toc.length === 5 }
  await sleep(1500)

  // The user's taps on the chapter's two links: foliate's own link handling runs, and opens nothing.
  const doc = contents().doc
  const js = doc.getElementById('js-link')
  const web = doc.getElementById('web-link')
  js?.click()
  web?.click()
  await sleep(800)
  out.checks.linksOpenNothing = { found: !!js && !!web, opened: window.__opened, ok: !!js && !!web && window.__opened.length === 0 }

  // Every chapter in turn (a refresh fires after a second; the next chapter is prepared meanwhile).
  for (const entry of opened.toc) {
    await window.readerGoTo(entry.href)
    await sleep(2500)
  }

  // The animated chapter in the scrolled flow, where its height changes the chapter's on every frame.
  window.readerSettings({ ...SETTINGS, flow: 'scrolled' })
  await window.readerGoTo(opened.toc[1].href)
  await sleep(1500)
  const t = performance.now()
  await sleep(3000)
  const flood = since(t, 'onRelocate').length
  out.checks.animationBounded = { relocatesIn3s: flood, ok: flood <= 14 }

  // Part-way down it (a volume-key step): the text on screen is kept in place as the animated
  // block above it grows and shrinks, so every relocate differs. At most one every 250 ms goes out
  // (13 in 3 s), and once the animation stops the last one sent is where the page stopped.
  window.readerNext()
  await sleep(1500)
  const t2 = performance.now()
  await sleep(3000)
  const moving = since(t2, 'onRelocate')
  const fractions = new Set(moving.map((c) => c.payload.fraction)).size
  contents().doc.querySelector('.grow')?.style.setProperty('animation-play-state', 'paused')
  await sleep(1000)
  const last = window.__calls.filter((c) => c.name === 'onRelocate').at(-1)?.payload
  const shown = view().lastLocation?.fraction
  out.checks.animationPartWay = {
    start: view().renderer.start, relocatesIn3s: moving.length, fractions, last: last?.fraction, shown,
    ok: moving.length >= 4 && moving.length <= 13 && fractions >= 2 && last?.fraction === shown,
  }
  window.readerSettings(SETTINGS)
  await window.readerGoTo(opened.toc[0].href)
  await sleep(2000)

  const counts = {}
  for (const c of window.__calls) counts[c.name] = (counts[c.name] || 0) + 1
  const crafted = window.__calls.filter((c) => JSON.stringify(c.payload ?? '').includes('crafted'))
  out.checks.nothingReachedTheBridge = { crafted: crafted.map((c) => c.payload), ok: crafted.length === 0 }
  out.checks.onlyTheReadersCalls = { counts, ok: Object.keys(counts).every((n) => NORMAL.has(n)) && counts.onReady === 1 && counts.onOpened === 1 }
  const c = contents()
  const text = c?.doc?.body?.innerText?.length ?? 0
  out.checks.stillReads = { views: document.querySelectorAll('foliate-view').length, index: c?.index, text, ok: document.querySelectorAll('foliate-view').length === 1 && c?.index === 0 && text > 1000 }
}

;(async () => {
  await sleep(50)
  try {
    await hostileCheck()
  } catch (e) {
    out.exception = String(e?.stack || e)
  }
  out.pageErrors = window.__errs
  out.allOk = !out.exception && Object.values(out.checks).every((c) => c.ok !== false)
  window.__result = out
  document.title = 'DONE'
})()
