import http from 'node:http';
import https from 'node:https';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { candidates, streamMetadata, readRange, googleMedia, mp4Ranges } from './media.mjs';

export function backendJson(url, { host, signal } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const fail = () => reject(new Error('backend_failed'));
    const request = (u.protocol === 'https:' ? https : http).get(u, {
      signal: AbortSignal.any([signal, AbortSignal.timeout(12000)].filter(Boolean)),
      headers: host ? { Host: host } : {}
    }, response => {
      if (response.statusCode !== 200) { response.destroy(); fail(); return; }
      const parts = []; let size = 0;
      response.on('data', part => {
        size += part.length;
        if (size > 4 * 1024 * 1024) { response.destroy(); fail(); return; }
        parts.push(part);
      });
      response.on('error', fail);
      response.on('aborted', fail);
      response.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(parts).toString('utf8'))); }
        catch { fail(); }
      });
    });
    request.on('error', fail);
  });
}

export function extract(videoId, config, signal) {
  return new Promise((resolve, reject) => {
    const args = ['--ignore-config', '--no-playlist', '--no-warnings', '--socket-timeout', '15',
      '--retries', '0', '--extractor-retries', '0', '--js-runtimes', `node:${process.execPath}`,
      '--remote-components', 'ejs:github', '--dump-single-json', `https://www.youtube.com/watch?v=${videoId}`];
    const child = spawn(config.extractor, args, { stdio: ['ignore', 'pipe', 'pipe'], detached: true,
      env: { PATH: process.env.PATH, HOME: '/tmp', XDG_CACHE_HOME: '/tmp/cache', TMPDIR: '/tmp' } });
    const kill = () => { if (child.pid) { try { process.kill(-child.pid, 'SIGKILL'); } catch {} } };
    signal.addEventListener('abort', kill, { once: true });
    if (signal.aborted) kill();
    const parts = []; let size = 0;
    // Discard stderr: upstream exceptions can contain signed URLs or tokens.
    child.stderr.resume();
    child.stdout.on('data', part => {
      size += part.length;
      if (size > 8 * 1024 * 1024) { kill(); reject(new Error('extractor_output_limit')); }
      else parts.push(part);
    });
    const timeout = setTimeout(kill, config.extractTimeoutMs);
    child.on('error', () => { clearTimeout(timeout); signal.removeEventListener('abort', kill); reject(new Error('extractor_unavailable')); });
    child.on('close', code => {
      clearTimeout(timeout);
      signal.removeEventListener('abort', kill);
      if (code !== 0) { reject(new Error('extractor_failed')); return; }
      try { resolve(JSON.parse(Buffer.concat(parts).toString('utf8'))); }
      catch { reject(new Error('invalid_extractor_response')); }
    });
  });
}

export function configFromEnv(env = process.env) {
  const integer = (key, fallback, min, max) => {
    const value = Number(env[key] || fallback);
    if (!Number.isSafeInteger(value) || value < min || value > max) throw new Error(`invalid_${key}`);
    return value;
  };
  const config = {
    backend: env.PIPED_BACKEND_URL || 'http://piped-backend:8080',
    backendHost: env.PIPED_BACKEND_HOST || '',
    proxyProbe: env.PROXY_PROBE_URL || '', proxyHost: env.PROXY_PROBE_HOST || '',
    proxyBase: env.PUBLIC_PROXY_URL || '', extractor: env.YT_DLP_PATH || '/opt/yt-dlp',
    port: integer('PORT', 8080, 1, 65535), maxHeight: integer('MAX_HEIGHT', 1080, 720, 1080),
    cacheTtlMs: integer('CACHE_SECONDS', 600, 10, 600) * 1000,
    extractTimeoutMs: integer('EXTRACT_TIMEOUT_SECONDS', 70, 1, 90) * 1000,
    requestTimeoutMs: integer('REQUEST_TIMEOUT_SECONDS', 110, 2, 120) * 1000,
    cacheEntries: integer('CACHE_ENTRIES', 16, 1, 32)
  };
  for (const key of ['backend', 'proxyProbe', 'proxyBase']) {
    if (!config[key]) continue;
    const u = new URL(config[key]);
    if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password ||
        u.pathname !== '/' || u.search || u.hash) throw new Error(`invalid_${key}`);
  }
  return config;
}

export function createAdapter(config, dependencies = {}) {
  const getExtraction = dependencies.extract || extract;
  const getRange = dependencies.readRange || readRange;
  const getBackend = dependencies.backend || (async (id, signal) => {
    const u = new URL(`/streams/${id}`, config.backend);
    return backendJson(u, { signal, host: config.backendHost });
  });
  const cache = new Map(); const inFlight = new Map(); let active = 0;
  const counters = { enriched: 0, fallback: 0, hits: 0 };

  async function build(id) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), config.requestTimeoutMs);
    let original;
    try {
      original = await getBackend(id, controller.signal);
      if (!original || !Array.isArray(original.videoStreams) || !Array.isArray(original.audioStreams)) {
        throw new Error('invalid_backend_response');
      }
      if (active >= 1 || original.livestream || original.videoStreams.some(s => s.url && new URL(s.url).searchParams.has('qhash'))) {
        return { data: original, kind: 'fallback', ttl: 5000 };
      }
      active++;
      try {
        const info = await getExtraction(id, config, controller.signal);
        const base = config.proxyBase || original.proxyUrl;
        if (!base) throw new Error('missing_proxy_base');
        const selected = candidates(info, config.maxHeight);
        const video = []; const audio = []; let expiry = Infinity;
        for (const [formats, result, audioOnly] of [[selected.audio, audio, true], [selected.video, video, false]]) {
          for (const format of formats) {
            try {
              const remote = googleMedia(format.url);
              const expiresAt = Number(remote.searchParams.get('expire')) * 1000;
              if (!Number.isFinite(expiresAt) || expiresAt <= Date.now() + 120000) continue;
              // First create the public URL, then route index probes through the same proxy internally.
              const preliminary = streamMetadata(format, info, {}, base, audioOnly);
              const probe = new URL(preliminary.url);
              if (config.proxyProbe) {
                const internal = new URL(config.proxyProbe);
                probe.protocol = internal.protocol; probe.host = internal.host;
              }
              const ranges = mp4Ranges(await getRange(probe, { host: config.proxyHost, signal: controller.signal }));
              result.push({ ...preliminary, ...ranges });
              expiry = Math.min(expiry, expiresAt);
            } catch {
              // A rejected or unsupported representation must never create a broken DASH entry.
            }
          }
          if (audioOnly && audio.length === 0) throw new Error('no_verified_audio');
        }
        if (!video.some(s => s.height >= 720)) throw new Error('no_verified_hd_video');
        if (expiry <= Date.now() + 120000) throw new Error('media_expiring_too_soon');
        // Keep original combined streams for clients that need progressive playback.
        const combined = original.videoStreams.filter(s => s.videoOnly === false);
        const data = { ...original, videoStreams: [...video, ...combined], audioStreams: audio,
          hls: null, dash: null };
        return { data, kind: 'enriched', ttl: Math.min(config.cacheTtlMs, expiry - Date.now() - 120000) };
      } catch {
        return { data: original, kind: 'fallback', ttl: 30000 };
      } finally { active--; }
    } finally { clearTimeout(timeout); }
  }

  async function streams(id) {
    const entry = cache.get(id);
    if (entry && entry.until > Date.now()) { counters.hits++; return entry.result; }
    if (entry) cache.delete(id);
    if (inFlight.has(id)) return inFlight.get(id);
    // Bound both backend fetches and extraction; unrelated requests fall back via backend.
    if (inFlight.size >= 4) throw new Error('adapter_busy');
    const work = build(id).then(result => {
      counters[result.kind]++;
      if (Buffer.byteLength(JSON.stringify(result.data)) <= 1024 * 1024 && result.ttl > 0) {
        if (cache.size >= config.cacheEntries) cache.delete(cache.keys().next().value);
        cache.set(id, { result, until: Date.now() + result.ttl });
      }
      return result;
    }).finally(() => inFlight.delete(id));
    inFlight.set(id, work);
    return work;
  }

  const server = http.createServer(async (req, res) => {
    const send = (status, data, kind) => {
      res.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
        'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': '*',
        'Access-Control-Allow-Headers': '*, Authorization',
        ...(kind ? { 'X-Extractor-Source': kind } : {}) });
      res.end(JSON.stringify(data));
    };
    if (req.method === 'GET' && req.url === '/healthz') { send(200, { status: 'ok' }); return; }
    const match = /^\/downloads\/streams\/([A-Za-z0-9_-]{11})$/.exec(req.url || '');
    if (!match) { send(404, { error: 'Not found' }); return; }
    if (req.method === 'OPTIONS') { send(200, {}); return; }
    if (req.method !== 'GET') { send(405, { error: 'Method not allowed' }); return; }
    try { const result = await streams(match[1]); send(200, result.data, result.kind); }
    catch { send(503, { error: 'Stream information temporarily unavailable' }); }
  });
  server.headersTimeout = 15000; server.requestTimeout = 15000;
  server.maxHeadersCount = 40; server.maxConnections = 32;
  return { server, streams, counters };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = configFromEnv();
  const { server } = createAdapter(config);
  server.listen(config.port, '0.0.0.0', () => console.log('extractor adapter listening'));
  for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => server.close(() => process.exit(0)));
}
