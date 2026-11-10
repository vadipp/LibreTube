// Explicit opt-in live probe. Configuration stays in environment, never in reports.
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import { configFromEnv, createAdapter } from '../server.mjs';
import { readRange } from '../media.mjs';

const ids = process.argv.slice(2);
assert(ids.length > 0 && ids.every(id => /^[A-Za-z0-9_-]{11}$/.test(id)), 'Supply video IDs');
const config = configFromEnv();
const adapter = createAdapter(config);
adapter.server.listen(0, '127.0.0.1'); await once(adapter.server, 'listening');
const endpoint = `http://127.0.0.1:${adapter.server.address().port}`;
try {
  for (const id of ids) {
    const start = Date.now();
    const response = await fetch(`${endpoint}/downloads/streams/${id}`, { signal: AbortSignal.timeout(120000) });
    assert.equal(response.status, 200);
    const result = await response.json();
    const source = response.headers.get('x-extractor-source');
    assert.equal(source, 'enriched', 'Live enrichment failed; original Piped response remains fallback');
    const video = result.videoStreams.filter(s => s.videoOnly);
    assert(video.some(s => s.height >= 720)); assert(result.audioStreams.length > 0);
    const max = video.reduce((a, b) => a.height > b.height ? a : b);
    const checks = [];
    for (const stream of [max, result.audioStreams[0]]) {
      const u = new URL(stream.url); assert.equal(u.origin, new URL(result.proxyUrl).origin);
      if (config.proxyProbe) { const internal = new URL(config.proxyProbe); u.protocol = internal.protocol; u.host = internal.host; }
      assert(stream.initEnd >= 0 && stream.indexStart === stream.initEnd + 1 && stream.indexEnd > stream.indexStart);
      const offset = stream.contentLength > 0 ? Math.floor(stream.contentLength / 2) : stream.indexEnd + 65536;
      const options = { host: config.proxyHost, start: offset, end: offset + 63 };
      const part = await readRange(u, options); assert.equal(part.length, 64);
      const repeated = await readRange(u, options);
      assert.equal(createHash('sha256').update(part).digest('hex'), createHash('sha256').update(repeated).digest('hex'));
      checks.push({ mimeType: stream.mimeType, codec: stream.codec, init: [stream.initStart, stream.initEnd],
        index: [stream.indexStart, stream.indexEnd], contentLength: stream.contentLength, seekSampleBytes: part.length });
    }
    const cachedAt = Date.now();
    const cached = await fetch(`${endpoint}/downloads/streams/${id}`); assert.equal(cached.headers.get('x-extractor-source'), 'enriched');
    const cachedData = await cached.json(); assert.deepEqual(cachedData, result);
    console.log(JSON.stringify({ id, source, coldMs: Date.now() - start, cacheMs: Date.now() - cachedAt,
      heights: video.map(s => s.height).sort((a, b) => a - b), checks, cacheHits: adapter.counters.hits }));
  }
} finally { await new Promise(resolve => adapter.server.close(resolve)); }
