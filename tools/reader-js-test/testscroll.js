// The scrolled flow and fixed-layout spreads through reader.js, as the app drives them
// (readerOpen / readerSettings / readerNext with the settings ReaderStyles.pageSettings makes).
// ?check=scroll (files/6, long chapters), ?check=pages (files/6 in pages: jumps and turns back
// between chapters with the next one prepared, the book's last page), ?check=fxl (files/7, fixed
// layout) or ?check=fxl5 (files/8, fixed layout, five pages). Results in window.__result; each
// check lists what it saw and `ok`.
const q = new URLSearchParams(location.search)
const check = q.get('check') || 'scroll'
const out = { check, checks: {} }
window.__result = null
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const since = (t, name) => window.__calls.filter((c) => c.name === name && c.at > t)
const last = (name) => window.__calls.filter((c) => c.name === name).at(-1)?.payload

const SETTINGS = {
  css: 'html { font-size: 18px !important; } p, li, blockquote, dd { line-height: 1.5 !important; }',
  bg: '#f1e8d0', fg: '#5b4636', dark: false,
  flow: 'scrolled', animated: true, columns: '2', gap: '6%', margin: '28px',
  maxInlineSize: '720px', maxBlockSize: '1440px', fixedLayoutSpread: 'auto',
}

function size(w, h) {
  document.getElementById('app').style.cssText = `position:absolute;left:0;top:0;width:${w}px;height:${h}px`
}

const view = () => document.querySelector('foliate-view')
const r = () => view().renderer
const index = () => r().getContents()[0]?.index
const doc = () => r().getContents()[0].doc
const atEnd = () => r().viewSize - r().end <= 4
// The paginator's scroller (the chapter iframe's View's parent, in its shadow root).
const container = () => doc().defaultView.frameElement.parentElement.parentElement

/** Scrolls [el] into view as the user's finger would move the page. A hidden page (a browser tab not on
 * screen) never renders, so the browser sends no scroll event: send the one it would. */
function scrollToEl(el, options) {
  el.scrollIntoView(options)
  if (document.hidden) container().dispatchEvent(new Event('scroll'))
}

function touch(type, y) {
  const d = doc()
  const W = d.defaultView
  const t = new W.Touch({ identifier: 1, target: d.body, screenX: 200, screenY: y, clientX: 200, clientY: y })
  const live = type === 'touchend' ? [] : [t]
  d.body.dispatchEvent(new W.TouchEvent(type, { bubbles: true, cancelable: true, touches: live, targetTouches: live, changedTouches: [t] }))
}

async function swipe(fromY, toY) {
  touch('touchstart', fromY)
  await sleep(60)
  touch('touchend', toY)
}

async function toChapterEnd() {
  for (let i = 0; i < 60 && !atEnd(); i++) {
    window.readerNext()
    await sleep(420)
  }
  await sleep(500)
}

async function scrollCheck() {
  size(411, 780)
  window.readerOpen({ bookId: 1, fileId: 6, format: 'kepub', cfi: null, percentage: 0, settings: SETTINGS })
  const opened = await window.__wait('onOpened', () => true, 20000)
  out.checks.opened = { toc: opened.toc.length, fixedLayout: opened.fixedLayout, flow: r().getAttribute('flow'), sections: view().book.sections.length }
  await sleep(1500)

  // Opening moved nothing of the user's: no relocate says turned.
  const moves = window.__calls.filter((c) => c.name === 'onRelocate' && c.payload.turned).length
  out.checks.openIsNotAMove = { turned: moves, ok: moves === 0 }
  const first = last('onRelocate')
  out.checks.timeFields = { ...first && { timeSection: first.timeSection, timeTotal: first.timeTotal, timeBook: first.timeBook }, ok: first?.timeBook > 0 && first?.timeSection > 0 && first?.timeTotal >= first?.timeSection }

  // The user's first scroll counts (an anchor that moved nothing used to swallow it).
  let t = performance.now()
  scrollToEl(doc().querySelectorAll('p')[10])
  await sleep(800)
  const s1 = since(t, 'onRelocate')
  out.checks.firstScrollCounts = { relocates: s1.length, turned: s1.some((c) => c.payload.turned), fraction: s1.at(-1)?.payload.fraction, ok: s1.some((c) => c.payload.turned) }
  const locAfterScroll = JSON.parse(window.readerLocation())
  out.checks.locationFollows = { cfi: locAfterScroll.cfi, ok: !!locAfterScroll.cfi && locAfterScroll.fraction > 0 }

  // The volume keys: most of a screen, and back.
  let before = r().start
  t = performance.now()
  window.readerNext()
  await sleep(800)
  const step = r().start - before
  out.checks.keyStep = { step, screen: r().size, ratio: +(step / r().size).toFixed(3), turned: since(t, 'onRelocate').some((c) => c.payload.turned), ok: Math.abs(step / r().size - 0.85) < 0.02 }
  window.readerPrev()
  await sleep(800)
  out.checks.keyBack = { off: r().start - before, ok: Math.abs(r().start - before) < 2 }

  // A tap on the left edge shows the bars instead of turning a page.
  before = r().start
  const i0 = index()
  t = performance.now()
  doc().body.dispatchEvent(new (doc().defaultView.MouseEvent)('click', { bubbles: true, cancelable: true, clientX: 15, clientY: 300, button: 0 }))
  await sleep(300)
  out.checks.tapToggles = { toggles: since(t, 'onToggleUi').length, moved: r().start - before, ok: since(t, 'onToggleUi').length === 1 && r().start === before && index() === i0 }

  // The end of the chapter: said so, then a swipe up opens the next chapter at its start.
  await toChapterEnd()
  const endReloc = last('onRelocate')
  out.checks.chapterEnd = { atEnd: atEnd(), chapterEnd: endReloc?.chapterEnd, bookEnd: endReloc?.bookEnd, ok: atEnd() && endReloc?.chapterEnd === true && endReloc?.bookEnd === false }
  const from = index()
  t = performance.now()
  await swipe(600, 480)
  await sleep(1500)
  out.checks.swipeOn = { from, to: index(), start: r().start, turned: since(t, 'onRelocate').some((c) => c.payload.turned), ok: index() === from + 1 && r().start < 4 }
  out.checks.chapterEndCleared = { chapterEnd: last('onRelocate')?.chapterEnd, ok: last('onRelocate')?.chapterEnd === false }

  // A short swipe or a sideways one does nothing.
  await swipe(500, 470)
  await sleep(800)
  out.checks.shortSwipeIgnored = { index: index(), ok: index() === from + 1 }

  // Back at the start: a swipe down opens the previous chapter at its end.
  await swipe(400, 530)
  await sleep(1500)
  out.checks.swipeBack = { index: index(), atEnd: atEnd(), ok: index() === from && atEnd() }

  // The volume key at the end of the chapter carries on into the next one.
  window.readerNext()
  await sleep(1500)
  out.checks.keyOnAtEnd = { index: index(), start: r().start, ok: index() === from + 1 && r().start < 4 }

  // Switching to pages and back keeps the place.
  scrollToEl(doc().querySelectorAll('p')[30])
  await sleep(800)
  const a = JSON.parse(window.readerLocation())
  window.readerSettings({ ...SETTINGS, flow: 'paginated' })
  await sleep(1200)
  const b = JSON.parse(window.readerLocation())
  window.readerSettings({ ...SETTINGS, flow: 'scrolled' })
  await sleep(1200)
  const c = JSON.parse(window.readerLocation())
  const para = (cfi) => cfi?.match(/!\/4\/(\d+)/)?.[1]
  out.checks.flowSwitch = {
    scrolled: [a.fraction, para(a.cfi)], paged: [b.fraction, para(b.cfi)], back: [c.fraction, para(c.cfi)],
    flow: r().getAttribute('flow'),
    ok: Math.abs(b.fraction - a.fraction) < 0.02 && Math.abs(c.fraction - a.fraction) < 0.02 && r().getAttribute('flow') === 'scrolled',
  }

  // Reopening at a saved CFI in the scrolled flow.
  await view().goTo(0)
  await sleep(500)
  await window.readerGoTo(a.cfi)
  await sleep(800)
  const d = JSON.parse(window.readerLocation())
  out.checks.restoreCfi = { want: a.fraction, got: d.fraction, ok: Math.abs(d.fraction - a.fraction) < 0.01 }

  // A selection's popup hides while the text scrolls and comes back where it is.
  const p = doc().querySelectorAll('p')[34]
  scrollToEl(p)
  await sleep(800)
  const range = doc().createRange()
  range.setStart(p.firstChild, 0)
  range.setEnd(p.firstChild, 20)
  const sel = doc().getSelection()
  sel.removeAllRanges()
  sel.addRange(range)
  const s0 = await window.__wait('onSelection', () => true, 5000).catch(() => null)
  t = performance.now()
  scrollToEl(doc().querySelectorAll('p')[33])
  await sleep(1200)
  const cleared = since(t, 'onSelectionCleared').length
  const again = since(t, 'onSelection').at(-1)?.payload
  out.checks.selectionFollows = { before: s0?.rect?.top, after: again?.rect?.top, cleared, ok: cleared >= 1 && !!again && Math.abs(again.rect.top - s0.rect.top) > 20 }
  window.readerClearSelection()

  // Margins: the chapter's side padding follows at once. Nothing resizes in the scrolled flow, so
  // it used to wait for the next chapter, a rotation or a flow switch.
  const padding = () => parseFloat(getComputedStyle(doc().documentElement).paddingLeft)
  const width = container().getBoundingClientRect().width
  const pad6 = padding()
  window.readerSettings({ ...SETTINGS, gap: '10%' })
  await sleep(600)
  const pad10 = padding()
  out.checks.marginsFollow = {
    before: +pad6.toFixed(2), after: +pad10.toFixed(2), want: +((width * 0.1) / 0.9).toFixed(2),
    ok: Math.abs(pad6 - (width * 0.06) / 0.94) < 1 && Math.abs(pad10 - (width * 0.1) / 0.9) < 1,
  }
  window.readerSettings(SETTINGS)
  await sleep(600)

  // The bottom of the last section (a short afterword) is the end of the book, said outright:
  // foliate's fraction there is short of 1 by the share of it on screen.
  const lastSection = view().book.sections.length - 1
  await window.readerGoTo(lastSection)
  await sleep(800)
  await toChapterEnd()
  const bottom = last('onRelocate')
  out.checks.bookEnd = {
    index: index(), atEnd: atEnd(), fraction: bottom?.fraction, bookEnd: bottom?.bookEnd, chapterEnd: bottom?.chapterEnd,
    ok: index() === lastSection && atEnd() && bottom?.bookEnd === true && bottom?.chapterEnd === false,
  }
  window.readerPrev()
  await sleep(800)
  out.checks.bookEndCleared = { bookEnd: last('onRelocate')?.bookEnd, ok: last('onRelocate')?.bookEnd === false }
}

/** How far the current chapter's frame is below the top of the page area (0: on screen). */
const frameOffset = () => Math.round(doc().defaultView.frameElement.getBoundingClientRect().top - container().getBoundingClientRect().top)
// The Views in the paginator's container: the current one, and the next section's prepared one.
const views = () => container().children.length

/** Once section [want] shows (its first relocate, before any idle work): where its frame is. */
function whenShown(want) {
  return new Promise((resolve) => {
    const on = () => {
      if (index() !== want) return
      view().removeEventListener('relocate', on)
      resolve({ offset: frameOffset(), views: views() })
    }
    view().addEventListener('relocate', on)
    setTimeout(() => resolve({ timeout: true }), 5000)
  })
}

async function pagesCheck() {
  size(411, 780)
  window.readerOpen({ bookId: 1, fileId: 6, format: 'kepub', cfi: null, percentage: 0, settings: { ...SETTINGS, flow: 'paginated' } })
  const opened = await window.__wait('onOpened', () => true, 20000)
  const idle = () => sleep(2000) // the next section is prepared in a hidden View meanwhile
  await idle()
  out.checks.prepared = { views: views(), ok: views() === 2 }

  // A contents pick of another chapter shows its text at once (the hidden View used to push it a
  // page-height down, off screen, until the idle callback removed it).
  let shown = whenShown(2)
  window.readerGoTo(opened.toc[2].href)
  let s = await shown
  out.checks.contentsPick = { ...s, ok: s.offset === 0 && s.views === 1 }

  // A turn back from a chapter's first page into the previous chapter's last page.
  await idle()
  const prepared = views()
  shown = whenShown(1)
  window.readerPrev()
  s = await shown
  out.checks.turnBack = { prepared, ...s, ok: prepared === 2 && s.offset === 0 && s.views === 1 }

  // The slider.
  await idle()
  shown = whenShown(0)
  window.readerGoToFraction(0.05)
  s = await shown
  out.checks.slider = { ...s, ok: s.offset === 0 && s.views === 1 }

  // The last section by the contents: with nothing after it, nothing removed a stale View later.
  await idle()
  const lastSection = view().book.sections.length - 1
  shown = whenShown(lastSection)
  window.readerGoTo(opened.toc.at(-1).href)
  s = await shown
  await idle()
  out.checks.lastSection = { ...s, later: { offset: frameOffset(), views: views() }, ok: s.offset === 0 && frameOffset() === 0 && views() === 1 }

  // Page turns onward still swap in the prepared View (the fast path).
  await window.readerGoTo(opened.toc[1].href)
  await idle()
  const before = views()
  const pages = r().pages
  for (let i = 0; i < pages + 2 && index() === 1; i++) {
    window.readerNext()
    await sleep(450)
  }
  await sleep(300)
  out.checks.turnOn = { prepared: before, index: index(), offset: frameOffset(), ok: before === 2 && index() === 2 && frameOffset() === 0 }

  // The end of the book: its last page says so (the next book in the series shows there), the
  // page before it doesn't, whatever their fractions (a long book's last pages all come within
  // half a percent of 1).
  await window.readerGoTo(opened.toc.at(-1).href)
  await idle()
  for (let i = 0; i < 40 && !r().atEnd; i++) {
    window.readerNext()
    await sleep(450)
  }
  await sleep(600)
  const lastPage = last('onRelocate')
  out.checks.bookEndPaged = { page: r().page, pages: r().pages, fraction: lastPage?.fraction, bookEnd: lastPage?.bookEnd, ok: r().atEnd && lastPage?.bookEnd === true }
  window.readerPrev()
  await sleep(800)
  const pageBefore = last('onRelocate')
  out.checks.bookEndPagedBefore = { index: index(), page: r().page, fraction: pageBefore?.fraction, bookEnd: pageBefore?.bookEnd, ok: !r().atEnd && pageBefore?.bookEnd === false }
}

/**
 * From the first page of a fixed-layout book to its end, a page turn at a time: what each move's
 * relocate said about the end of the book, and its fraction.
 */
async function walkToEnd() {
  await view().goTo(0)
  await sleep(800)
  const seen = []
  for (let i = 0; i < 12; i++) {
    const t = performance.now()
    window.readerNext()
    await sleep(600)
    const moved = since(t, 'onRelocate').at(-1)?.payload
    if (!moved) break // nothing further: the end
    seen.push({ bookEnd: moved.bookEnd, fraction: +moved.fraction.toFixed(3), pages: r().getContents().map((c) => c.index) })
  }
  return { seen, ok: seen.length > 0 && seen.at(-1).bookEnd === true && seen.slice(0, -1).every((s) => s.bookEnd === false) }
}

async function fxlCheck() {
  size(900, 500) // landscape, where the book's spreads pair its pages
  window.readerOpen({ bookId: 1, fileId: 7, format: 'kepub', cfi: null, percentage: 0, settings: { ...SETTINGS, flow: 'scrolled' } })
  const opened = await window.__wait('onOpened', () => true, 20000)
  await sleep(800)
  out.checks.opened = { fixedLayout: opened.fixedLayout, renderer: r().localName, frames: r().getContents().length, ok: opened.fixedLayout === true && r().localName === 'foliate-fxl' }
  window.readerNext()
  await sleep(800)
  window.readerNext()
  await sleep(800)
  const spreadIndex = index()
  out.checks.spreads = { index: spreadIndex, frames: r().getContents().length, ok: r().getContents().length === 2 }
  const old = view()
  window.readerSettings({ ...SETTINGS, flow: 'scrolled', fixedLayoutSpread: 'none' })
  const reopened = await window.__wait('onOpened', () => true, 10000).catch(() => null)
  await sleep(800)
  out.checks.singlePages = {
    reopened: !!reopened && view() !== old, spread: view().book.rendition.spread, index: index(), frames: r().getContents().length,
    ok: !!reopened && view().book.rendition.spread === 'none' && r().getContents().length === 1 && Math.abs(index() - spreadIndex) <= 1,
  }
  // A tap's zones still turn pages in a fixed-layout book, whatever the flow says.
  const i0 = index()
  window.readerNext()
  await sleep(800)
  out.checks.stillPages = { from: i0, to: index(), ok: index() === i0 + 1 }
  window.readerSettings({ ...SETTINGS, flow: 'scrolled', fixedLayoutSpread: 'auto' })
  const back = await window.__wait('onOpened', () => true, 10000).catch(() => null)
  await sleep(800)
  out.checks.bookSpreadsAgain = { spread: view().book.rendition.spread, frames: r().getContents().length, ok: !!back && view().book.rendition.spread === 'auto' && r().getContents().length === 2 }

  // The end of the book is the spread with the last page (both of its pages show in landscape),
  // however short of 1 its fraction is; in portrait a spread shows one side at a time, so only the
  // side with the last page is the end.
  out.checks.bookEndSpreads = await walkToEnd()
  size(500, 900)
  await sleep(800)
  out.checks.bookEndPortrait = await walkToEnd()
  size(900, 500)
}

/** A five-page fixed-layout book (files/8): its last page is the right side of the last spread. */
async function fxlEndCheck() {
  size(900, 500)
  window.readerOpen({ bookId: 1, fileId: 8, format: 'kepub', cfi: null, percentage: 0, settings: SETTINGS })
  const opened = await window.__wait('onOpened', () => true, 20000)
  await sleep(800)
  out.checks.opened = { fixedLayout: opened.fixedLayout, sections: view().book.sections.length, ok: opened.fixedLayout === true && view().book.sections.length === 5 }
  // Landscape: the last spread shows the last page on its right, and reports its left page's
  // fraction (4 of 5), well short of 1.
  const spreads = await walkToEnd()
  const end = spreads.seen.at(-1)
  out.checks.bookEndLastSpread = { ...spreads, ok: spreads.ok && end?.pages.length === 2 && end?.fraction < 0.995 }
  // Portrait: that spread's left side isn't the end, its right side is.
  size(500, 900)
  await sleep(800)
  out.checks.bookEndLastSpreadPortrait = await walkToEnd()
  size(900, 500)
}

;(async () => {
  await sleep(50)
  try {
    if (check === 'fxl') await fxlCheck()
    else if (check === 'fxl5') await fxlEndCheck()
    else if (check === 'pages') await pagesCheck()
    else await scrollCheck()
  } catch (e) {
    out.exception = String(e?.stack || e)
  }
  out.pageErrors = window.__errs
  out.allOk = !out.exception && Object.values(out.checks).every((c) => c.ok !== false)
  window.__result = out
  document.title = 'DONE'
})()
