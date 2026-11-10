import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createAdapter, configFromEnv, extract, backendJson } from '../server.mjs';
import { mp4Ranges, proxyUrl, candidates, readRange } from '../media.mjs';

function box(type, body = Buffer.alloc(0)) {
  const result = Buffer.alloc(8 + body.length);
  result.writeUInt32BE(result.length); result.write(type, 4); body.copy(result, 8); return result;
}
function indexedMp4() {
  const sidx = Buffer.alloc(36); sidx.writeUInt32BE(1000, 8); sidx.writeUInt16BE(1, 22);
  sidx.writeUInt32BE(2000, 24); sidx.writeUInt32BE(1000, 28);
  return Buffer.concat([box('ftyp', Buffer.alloc(8)), box('moov', Buffer.alloc(8)), box('sidx', sidx)]);
}
const original = { title: 'Example', duration: 60, proxyUrl: 'https://proxy.example.org',
  relatedStreams: [{ url: '/watch?v=ABCDEFGHIJK' }],
  videoStreams: [{ url: 'https://proxy.example.org/videoplayback?itag=18', videoOnly: false, height: 360 }], audioStreams: [] };
const media = (itag) => `https://rr1.googlevideo.com/videoplayback?itag=${itag}&expire=${Math.floor(Date.now() / 1000) + 3600}&sig=fixture`;
function info() {
  return { duration: 60, formats: [
    { url: media(137), format_id: '137', ext: 'mp4', protocol: 'https', vcodec: 'avc1.640028', acodec: 'none', height: 1080, width: 1920, fps: 30, tbr: 3000, filesize: 24000000 },
    { url: media(140), format_id: '140', ext: 'm4a', protocol: 'https', vcodec: 'none', acodec: 'mp4a.40.2', abr: 128, language: 'en' }
  ] };
}
const config = () => configFromEnv({ PROXY_PROBE_URL: 'http://proxy:80', PROXY_PROBE_HOST: 'proxy.example.org' });
const dependencies = (overrides = {}) => ({ backend: async () => structuredClone(original),
  extract: async () => info(), readRange: async () => indexedMp4(), ...overrides });

test('MP4 initialization and sidx ranges are exact and inclusive', () => {
  assert.deepEqual(mp4Ranges(indexedMp4()), { initStart: 0, initEnd: 31, indexStart: 32, indexEnd: 75 });
  assert.throws(() => mp4Ranges(indexedMp4().subarray(0, 60)));
  assert.throws(() => mp4Ranges(Buffer.from('<html>login</html>')));
  const zero = indexedMp4(); zero.writeUInt32BE(0, 48); assert.throws(() => mp4Ranges(zero));
});
test('rewrites signed media to proxy and rejects arbitrary hosts/paths', () => {
  const rewritten = new URL(proxyUrl(media(137), 'https://proxy.example.org'));
  assert.equal(rewritten.hostname, 'proxy.example.org'); assert.equal(rewritten.searchParams.get('sig'), 'fixture');
  assert.equal(rewritten.searchParams.get('host'), 'rr1.googlevideo.com');
  for (const u of ['http://rr1.googlevideo.com/videoplayback', 'https://googlevideo.com.evil.example/videoplayback', 'https://127.0.0.1/videoplayback', 'https://rr1.googlevideo.com/other']) {
    assert.throws(() => proxyUrl(u, 'https://proxy.example.org'));
  }
});
test('preserves Piped metadata and combined fallback, adds indexed HD and audio', async () => {
  const { streams } = createAdapter(config(), dependencies());
  const result = await streams('ABCDEFGHIJK');
  assert.equal(result.kind, 'enriched'); assert.equal(result.data.title, original.title);
  assert.deepEqual(result.data.relatedStreams, original.relatedStreams);
  assert.equal(result.data.videoStreams[0].height, 1080); assert.equal(result.data.videoStreams[0].indexEnd, 75);
  assert.equal(result.data.videoStreams[0].codec, 'avc1.640028');
  assert.deepEqual(result.data.videoStreams.at(-1), original.videoStreams[0]);
  assert.equal(result.data.audioStreams[0].mimeType, 'audio/mp4');
  assert.equal(new URL(result.data.audioStreams[0].url).hostname, 'proxy.example.org');
});
test('same video requests share extraction and subsequent requests hit cache', async () => {
  let calls = 0;
  const adapter = createAdapter(config(), dependencies({ extract: async () => { calls++; await new Promise(r => setTimeout(r, 20)); return info(); } }));
  const results = await Promise.all(Array.from({ length: 8 }, () => adapter.streams('ABCDEFGHIJK')));
  assert(results.every(r => r.kind === 'enriched')); await adapter.streams('ABCDEFGHIJK');
  assert.equal(calls, 1); assert.equal(adapter.counters.hits, 1);
});
test('failed extraction, missing audio/index, expired URLs and unsupported live all fall back intact', async () => {
  for (const overrides of [
    { extract: async () => { throw new Error('fixture failure'); } },
    { readRange: async () => Buffer.from('not mp4') },
    { extract: async () => ({ ...info(), formats: info().formats.slice(0, 1) }) },
    { extract: async () => ({ ...info(), formats: info().formats.map(f => ({ ...f, url: f.url.replace(/expire=\d+/, 'expire=1') })) }) },
    { extract: async () => ({ ...info(), is_live: true }) }
  ]) {
    const adapter = createAdapter(config(), dependencies(overrides)); const result = await adapter.streams('ABCDEFGHIJK');
    assert.equal(result.kind, 'fallback'); assert.deepEqual(result.data, original);
  }
});
test('one active extraction; unrelated video falls back instead of spawning more processes', async () => {
  let release; let calls = 0;
  const adapter = createAdapter(config(), dependencies({ extract: async () => { calls++; await new Promise(r => { release = r; }); return info(); } }));
  const first = adapter.streams('ABCDEFGHIJK');
  await new Promise(r => setImmediate(r));
  assert.equal((await adapter.streams('LMNOPQRSTUV')).kind, 'fallback');
  release(); assert.equal((await first).kind, 'enriched'); assert.equal(calls, 1);
});
test('only compatible non-DRM AVC/AAC formats within limit are selected', () => {
  const i = info(); i.formats.push({ ...i.formats[0], height: 2160 }, { ...i.formats[0], vcodec: 'av01.0' }, { ...i.formats[0], has_drm: true });
  assert.equal(candidates(i).video.length, 1);
});
test('exact routes only, no account forwarding, backend failure is generic 503', async () => {
  const { server } = createAdapter(config(), dependencies({ backend: async () => { throw new Error('private detail'); } }));
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    // Playback must never enter this adapter, even if nginx is misconfigured.
    assert.equal((await fetch(base + '/streams/ABCDEFGHIJK')).status, 404);
    for (const path of ['/login', '/downloads/streams/too-short', '/downloads/streams/%2Fwatch?v=ABCDEFGHIJK', '/downloads/streams/ABCDEFGHIJK?url=anything']) {
      assert.equal((await fetch(base + path)).status, 404);
    }
    assert.equal((await fetch(base + '/downloads/streams/ABCDEFGHIJK', { method: 'POST' })).status, 405);
    const preflight = await fetch(base + '/downloads/streams/ABCDEFGHIJK', { method: 'OPTIONS' });
    assert.equal(preflight.status, 200);
    assert.equal(preflight.headers.get('access-control-allow-origin'), '*');
    assert.equal(preflight.headers.get('access-control-allow-headers'), '*, Authorization');
    const response = await fetch(base + '/downloads/streams/ABCDEFGHIJK');
    assert.equal(response.status, 503); assert(!(await response.text()).includes('private detail'));
  } finally { await new Promise(r => server.close(r)); }
});
test('cached URLs are refreshed before expiry and qhash deployments remain on original backend', async () => {
  const cfg = { ...config(), cacheTtlMs: 10 }; let calls = 0;
  const adapter = createAdapter(cfg, dependencies({ extract: async () => { calls++; return info(); } }));
  await adapter.streams('ABCDEFGHIJK');
  await new Promise(r => setTimeout(r, 20));
  await adapter.streams('ABCDEFGHIJK'); assert.equal(calls, 2);
  const signed = createAdapter(config(), dependencies({ backend: async () => ({ ...original,
    videoStreams: [{ ...original.videoStreams[0], url: original.videoStreams[0].url + '&qhash=fixture' }] }),
    extract: async () => { throw new Error('should not run'); } }));
  assert.equal((await signed.streams('ABCDEFGHIJK')).kind, 'fallback');
});
test('backend sends correct virtual host and media probes reject login/redirect/oversize', async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/backend') { res.end(JSON.stringify({ host: req.headers.host })); return; }
    if (req.url === '/redirect') { res.writeHead(302, { Location: '/valid' }); res.end(); return; }
    if (req.url === '/login') { res.writeHead(200, { 'Content-Type': 'text/html' }); res.end('login'); return; }
    res.writeHead(206, { 'Content-Type': 'video/mp4', 'Content-Range': 'bytes 0-7/8' }); res.end('12345678');
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const base = `http://127.0.0.1:${server.address().port}`;
    assert.equal((await backendJson(base + '/backend', { host: 'api.example.org' })).host, 'api.example.org');
    assert.equal((await readRange(base + '/valid', { end: 7 })).length, 8);
    await assert.rejects(readRange(base + '/valid', { end: 3 }));
    await assert.rejects(readRange(base + '/redirect')); await assert.rejects(readRange(base + '/login'));
  } finally { await new Promise(r => server.close(r)); }
});
test('extractor timeout kills process group and missing binary fails safely', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'extractor-test-'));
  const executable = join(dir, 'sleep.sh');
  await writeFile(executable, '#!/bin/sh\nsleep 60\n', { mode: 0o700 });
  try {
    const cfg = { ...config(), extractor: executable, extractTimeoutMs: 50 };
    const started = Date.now(); await assert.rejects(extract('ABCDEFGHIJK', cfg, new AbortController().signal));
    assert(Date.now() - started < 3000);
    await assert.rejects(extract('ABCDEFGHIJK', { ...cfg, extractor: join(dir, 'absent') }, new AbortController().signal));
  } finally { await rm(dir, { recursive: true }); }
});
