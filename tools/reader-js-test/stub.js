window.__errs = []; window.addEventListener('error', (e) => window.__errs.push([e.message, e.filename, e.lineno, e.colno]), true)
// Stands in for the app's window.Android bridge: records every call.
window.__calls = []
window.__waiters = []
const record = (name) => (payload) => {
  const entry = { name, payload: typeof payload === 'string' && payload.startsWith('{') ? JSON.parse(payload) : payload, at: performance.now() }
  window.__calls.push(entry)
  window.__waiters = window.__waiters.filter((w) => (w.name === name && w.test(entry.payload) ? (w.resolve(entry.payload), false) : true))
}
window.Android = {}
for (const n of ['onReady', 'onOpened', 'onSelection', 'onSelectionCleared', 'onAnnotationTap', 'onSearchResults', 'onRelocate', 'onLocation', 'onToggleUi', 'onError']) window.Android[n] = record(n)
window.__wait = (name, test = () => true, ms = 15000) => new Promise((resolve, reject) => {
  const hit = window.__calls.find((c) => c.name === name && test(c.payload) && !c.used)
  if (hit) { hit.used = true; return resolve(hit.payload) }
  window.__waiters.push({ name, test, resolve })
  setTimeout(() => reject(new Error('timeout waiting for ' + name)), ms)
})
