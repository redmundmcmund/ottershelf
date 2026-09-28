// Opens ?file=<id>&format=<fmt> through reader.js as the app does and checks position, CFI round
// trip, a selection's CFI, a highlight drawn from it, and search. Results in window.__result.
const q = new URLSearchParams(location.search)
const fileId = Number(q.get('file'))
const format = q.get('format')
const out = { format, fileId, checks: {} }
window.__result = null
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function run() {
  await sleep(50)
  const errorP = window.__wait('onError', () => true, 20000).then((e) => ({ error: e }))
  window.readerOpen({ bookId: 1, fileId, format, cfi: null, percentage: 0, settings: { theme: 'dark', flow: 'paginated', animated: false } })
  const first = await Promise.race([window.__wait('onOpened', () => true, 20000), errorP])
  if (first.error) { out.error = first.error; return }
  out.checks.title = first.title
  out.checks.toc = first.toc.map((t) => t.label)
  const view = document.querySelector('foliate-view')
  out.checks.sections = view.book.sections.length
  out.checks.bookType = view.book.constructor?.name
  // Position: jump to 60% and read the full location the app would save.
  await window.readerGoToFraction(0.6)
  await sleep(600)
  const loc = JSON.parse(window.readerLocation())
  out.checks.location = { cfi: loc.cfi, fraction: loc.fraction, tocLabel: loc.tocLabel, koreader: loc.koreaderProgress }
  // Reopen-style restore: back to start, then goTo(cfi) must land on the same section.
  await view.goTo(0)
  await sleep(300)
  const resolved = await view.goTo(loc.cfi)
  await sleep(400)
  const back = JSON.parse(window.readerLocation())
  out.checks.cfiRoundTrip = { index: resolved?.index ?? null, fraction: back.fraction, sameCfi: back.cfi === loc.cfi }
  // Selection: select the first words of the first paragraph on screen, as a long press would.
  const content = view.renderer.getContents()[0]
  const doc = content.doc
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT, { acceptNode: (n) => n.nodeValue.trim().length > 12 ? 1 : 3 })
  const node = walker.nextNode()
  const range = doc.createRange()
  range.setStart(node, 0)
  range.setEnd(node, Math.min(node.nodeValue.length, 12))
  const sel = doc.getSelection()
  sel.removeAllRanges()
  sel.addRange(range)
  const selection = await window.__wait('onSelection', () => true, 5000).catch((e) => ({ err: e.message }))
  out.checks.selection = selection
  window.readerClearSelection()
  // Highlight it through the app's call and see foliate draw it.
  let drawn = false
  view.addEventListener('draw-annotation', (e) => { if (e.detail?.annotation?.value === selection.cfi) drawn = true })
  if (selection?.cfi) {
    window.readerSetAnnotations([{ cfi: selection.cfi, color: '#facc15', style: 'highlight' }])
    await sleep(500)
  }
  out.checks.highlightDrawn = drawn
  // Search over the whole book.
  window.readerSearch('zephyr', 7)
  const res = await window.__wait('onSearchResults', (p) => p.done, 10000).catch((e) => ({ err: e.message }))
  const all = window.__calls.filter((c) => c.name === 'onSearchResults').flatMap((c) => c.payload.items)
  out.checks.search = { hits: all.length, first: all[0] ?? null, error: res.error ?? res.err ?? null }
  out.relocates = window.__calls.filter((c) => c.name === 'onRelocate').length
}
run().catch((e) => { out.exception = String(e?.stack || e) }).finally(() => {
  out.pageErrors = window.__errs
  window.__result = out
  document.title = 'DONE'
})
