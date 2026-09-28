// reader.js' response cache on its own (cache.sh puts the file's code from `const responseCache` to
// the end of cachedFetch before this): a stand-in server, then the rules. A body is fetched once and
// kept; one over RESPONSE_BODY_MAX goes to every caller but isn't kept; the oldest go while the kept
// ones pass RESPONSE_BYTES_MAX, never one still coming in; the 96-entry limit; failures not kept;
// the bytes always add up; a section too big to keep isn't prefetched. The results go in the page's
// #out element, which cache.sh prints: ALL PASS, or what failed.

// The stand-in server: url -> { status, bytes }, counting requests; a url in `held` answers only
// when released (held[url] becomes the release function).
window.served = {}
window.requests = {}
window.held = {}
window.fetch = (url) => {
  requests[url] = (requests[url] || 0) + 1
  const s = served[url]
  if (!s) return Promise.reject(new TypeError('network error'))
  const answer = () => new Response(s.status === 200 ? new Uint8Array(s.bytes) : 'err', { status: s.status, headers: { 'Content-Type': 'application/xhtml+xml' } })
  if (held[url]) return new Promise((resolve) => { held[url] = () => resolve(answer()) })
  return Promise.resolve(answer())
}

;(async () => {
  const KB = 1024
  const MB = 1024 * KB
  const results = []
  const ok = (name, cond, detail = '') => results.push(`${cond ? 'PASS' : 'FAIL'} ${name}${detail ? ' ' + detail : ''}`)
  const tick = () => new Promise((r) => setTimeout(r, 0))
  const reset = () => {
    for (const k of [...responseCache.keys()]) responseCache.delete(k)
    responseSizes.clear()
    responseBytes = 0
    for (const k of Object.keys(requests)) delete requests[k]
  }
  // Sizes are only for cached entries, and add up to the total.
  const consistent = (name) => {
    let sum = 0
    let orphan = false
    for (const [url, size] of responseSizes) {
      sum += size
      if (!responseCache.has(url)) orphan = true
    }
    ok(`${name}: sizes add up`, sum === responseBytes && !orphan, `sum=${sum} total=${responseBytes} orphan=${orphan}`)
  }
  const get = async (url) => (await (await cachedFetch(url)).arrayBuffer()).byteLength

  try {
    ok('constants', RESPONSE_CACHE_MAX === 96 && RESPONSE_BYTES_MAX === 12 * MB && RESPONSE_BODY_MAX === MB)

    // 1. A small body is fetched once and kept.
    reset()
    served.a = { status: 200, bytes: 100 * KB }
    ok('small: body', (await get('a')) === 100 * KB)
    await tick()
    ok('small: again from the cache', (await get('a')) === 100 * KB && requests.a === 1, `requests=${requests.a}`)
    ok('small: counted', responseBytes === 100 * KB && responseSizes.get('a') === 100 * KB)
    consistent('small')

    // 2. A body over 1 MB is handed over (to every caller) but not kept.
    reset()
    served.big = { status: 200, bytes: 2 * MB }
    const both = await Promise.all([get('big'), get('big')])
    ok('big: both callers get it', both[0] === 2 * MB && both[1] === 2 * MB && requests.big === 1, `${both} requests=${requests.big}`)
    await tick()
    ok('big: not kept', !responseCache.has('big') && !responseSizes.has('big') && responseBytes === 0)
    ok('big: fetched again next time', (await get('big')) === 2 * MB && requests.big === 2)
    consistent('big')
    // Exactly 1 MB is kept.
    served.mb = { status: 200, bytes: MB }
    await get('mb'); await tick()
    ok('1 MB: kept', responseCache.has('mb') && responseBytes === MB)
    consistent('1 MB')

    // 3. The oldest go while the kept bodies come to more than 12 MB.
    reset()
    for (let i = 0; i < 20; i++) {
      served['p' + i] = { status: 200, bytes: 900 * KB }
      await get('p' + i)
      await tick()
    }
    ok('bytes: under the cap', responseBytes <= 12 * MB, `total=${responseBytes}`)
    ok('bytes: 13 newest kept', responseCache.size === 13 && responseCache.has('p19') && responseCache.has('p7') && !responseCache.has('p6'), `size=${responseCache.size} keys=${[...responseCache.keys()]}`)
    consistent('bytes')
    await get('p19'); await get('p0')
    ok('bytes: newest from the cache, oldest fetched again', requests.p19 === 1 && requests.p0 === 2)
    await tick()
    consistent('bytes, after')
    // A hit makes an entry the newest: p8 (the oldest left once p0 came back) survives the next one.
    await get('p8'); await tick()
    served.q = { status: 200, bytes: 900 * KB }
    await get('q'); await tick()
    ok('bytes: a hit is kept as the newest', responseCache.has('p8') && !responseCache.has('p9'), `keys=${[...responseCache.keys()]}`)
    consistent('bytes, lru')

    // 4. The count limit still holds, and subtracts what it evicts.
    reset()
    for (let i = 0; i < 100; i++) {
      served['t' + i] = { status: 200, bytes: 1000 + i }
      await get('t' + i)
    }
    await tick()
    ok('count: 96 kept', responseCache.size === 96 && responseSizes.size === 96 && !responseCache.has('t3') && responseCache.has('t4'))
    consistent('count')

    // 5. Failures are not kept, nor counted.
    reset()
    served.e = { status: 502, bytes: 0 }
    const r = await cachedFetch('e')
    await tick()
    ok('error: handed over', r.status === 502)
    ok('error: not kept', !responseCache.has('e') && responseBytes === 0)
    await cachedFetch('e')
    ok('error: fetched again', requests.e === 2)
    let threw = false
    try { await cachedFetch('missing') } catch (e) { threw = true }
    await tick()
    ok('network error: rejected, not kept', threw && !responseCache.has('missing') && responseBytes === 0)
    consistent('error')

    // 6. The byte limit never drops an entry still on its way (the +2 section prefetch).
    reset()
    served.slow = { status: 200, bytes: 500 * KB }
    held.slow = true
    const slow = get('slow')
    await tick()
    for (let i = 0; i < 15; i++) {
      served['f' + i] = { status: 200, bytes: 900 * KB }
      await get('f' + i)
      await tick()
    }
    ok('pending: still cached', responseCache.has('slow') && !responseSizes.has('slow'))
    held.slow()
    ok('pending: body', (await slow) === 500 * KB)
    await tick()
    ok('pending: counted once in', responseSizes.get('slow') === 500 * KB && responseBytes <= 12 * MB, `total=${responseBytes}`)
    consistent('pending')
    held.slow = false

    // 7. An entry evicted while on its way, then fetched again: counted once.
    reset()
    served.x = { status: 200, bytes: 300 * KB }
    held.x = true
    const x1 = get('x')
    const release1 = held.x
    for (let i = 0; i < 96; i++) { served['y' + i] = { status: 200, bytes: 10 }; await get('y' + i) }
    ok('evicted pending: gone', !responseCache.has('x'))
    held.x = false
    ok('evicted pending: fetched again', (await get('x')) === 300 * KB && requests.x === 2)
    await tick()
    ok('evicted pending: second counted', responseSizes.get('x') === 300 * KB)
    const before = responseBytes
    release1()
    ok('evicted pending: first still answers', (await x1) === 300 * KB)
    await tick()
    ok('evicted pending: first not counted', responseBytes === before && responseSizes.get('x') === 300 * KB)
    consistent('evicted pending')

    // 8. The section two ahead is prefetched only if it can be kept: one over 1 MB would be dropped
    // as it came in, then fetched again when holdSections loads it a turn later.
    ok('prefetch: a small section', prefetchable({ id: 'a.xhtml', size: 40 * KB }))
    ok('prefetch: exactly 1 MB', prefetchable({ id: 'a.xhtml', size: MB }))
    ok('prefetch: size unknown', prefetchable({ id: 'a.xhtml', size: 0 }) && prefetchable({ id: 'a.xhtml' }))
    ok('prefetch: not one over 1 MB', !prefetchable({ id: 'a.xhtml', size: MB + 1 }))
    ok('prefetch: not past the end', !prefetchable(undefined) && !prefetchable(null) && !prefetchable({ size: 10 }))
  } catch (e) {
    results.push('FAIL threw ' + (e && e.stack || e))
  }
  const failed = results.filter((l) => l.startsWith('FAIL')).length
  document.getElementById('out').textContent = `${failed === 0 ? 'ALL PASS' : failed + ' FAILED'}\n${results.join('\n')}`
})()
