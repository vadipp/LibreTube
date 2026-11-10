import http from 'node:http';
import https from 'node:https';

export class ProbeError extends Error {
  constructor(reason) { super(reason); this.name = 'ProbeError'; }
}

export function googleMedia(url) {
  const u = new URL(url);
  if (u.protocol !== 'https:' || !u.hostname.endsWith('.googlevideo.com') ||
      u.username || u.password || u.port || u.pathname !== '/videoplayback') {
    throw new ProbeError('unsupported_media_url');
  }
  return u;
}

export function proxyUrl(url, base) {
  const u = googleMedia(url);
  const target = new URL(base);
  if (target.pathname !== '/' || target.search || target.hash || target.username || target.password) {
    throw new ProbeError('unsupported_proxy_base');
  }
  target.pathname = u.pathname;
  target.search = u.search;
  target.searchParams.set('host', u.hostname);
  return target.toString();
}

// Use node:http so an internal virtual-host override is actually sent.
// Do not follow redirects, which could silently bypass the media proxy.
export function readRange(url, { start = 0, end = 131071, host, signal } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const headers = { Range: `bytes=${start}-${end}`, 'Accept-Encoding': 'identity' };
    if (host) headers.Host = host;
    let done = false;
    const fail = () => { if (!done) { done = true; reject(new ProbeError('media_probe_failed')); } };
    const request = (u.protocol === 'https:' ? https : http).get(u, { headers, signal, timeout: 10000 }, response => {
      if (response.statusCode !== 206 || !/^\w+\/(mp4|octet-stream)/i.test(response.headers['content-type'] || '')) {
        response.destroy(); fail(); return;
      }
      const range = /^bytes (\d+)-(\d+)\/(\d+|\*)$/.exec(response.headers['content-range'] || '');
      // The deployed proxy has a known total/end header bug; still require the requested start.
      if (!range || Number(range[1]) !== start) { response.destroy(); fail(); return; }
      const parts = []; let size = 0;
      response.on('data', part => {
        size += part.length;
        if (size > end - start + 1) { response.destroy(); fail(); return; }
        parts.push(part);
      });
      response.on('end', () => {
        if (done) return;
        done = true;
        resolve(Buffer.concat(parts, size));
      });
      response.on('error', fail);
      response.on('aborted', fail);
    });
    request.on('timeout', () => request.destroy());
    request.on('error', fail);
  });
}

export function mp4Ranges(bytes) {
  let offset = 0; let ftyp = false; let moov = false;
  while (offset + 8 <= bytes.length) {
    let size = bytes.readUInt32BE(offset);
    const type = bytes.toString('ascii', offset + 4, offset + 8);
    let header = 8;
    if (size === 1) {
      if (offset + 16 > bytes.length) break;
      const wide = bytes.readBigUInt64BE(offset + 8);
      if (wide > BigInt(Number.MAX_SAFE_INTEGER)) throw new ProbeError('invalid_mp4_box');
      size = Number(wide); header = 16;
    }
    if (size < header) throw new ProbeError('invalid_mp4_box');
    if (offset + size > bytes.length) break;
    if (type === 'ftyp' && offset === 0) ftyp = true;
    if (type === 'moov') moov = true;
    if (type === 'sidx' && ftyp && moov) {
      if (size < header + 24) throw new ProbeError('invalid_sidx');
      const version = bytes[offset + header];
      if (version > 1) throw new ProbeError('unsupported_sidx');
      const timescale = bytes.readUInt32BE(offset + header + 8);
      const countAt = offset + header + (version === 0 ? 22 : 30);
      if (!timescale || countAt + 2 > offset + size) throw new ProbeError('invalid_sidx');
      const count = bytes.readUInt16BE(countAt);
      if (!count || countAt + 2 + count * 12 !== offset + size) throw new ProbeError('invalid_sidx');
      // Nested indexes are not supported by this first implementation.
      for (let i = 0; i < count; i++) {
        if (bytes.readUInt32BE(countAt + 2 + i * 12) >>> 31) throw new ProbeError('nested_sidx');
      }
      return { initStart: 0, initEnd: offset - 1, indexStart: offset, indexEnd: offset + size - 1 };
    }
    offset += size;
  }
  throw new ProbeError('mp4_index_not_found');
}

export function candidates(info, maxHeight = 1080) {
  if (!Number.isFinite(info.duration) || info.duration <= 0 || info.is_live || info.live_status === 'is_live') {
    throw new ProbeError('unsupported_live_or_duration');
  }
  const formats = (info.formats || []).filter(f => f.url && !f.has_drm && ['https', 'http'].includes(f.protocol));
  const video = formats.filter(f => f.ext === 'mp4' && /^avc1\./.test(f.vcodec || '') &&
    f.acodec === 'none' && f.height > 0 && f.height <= maxHeight && f.width > 0 && f.fps > 0);
  // One AVC representation per resolution/fps, avoiding duplicate clients and newer codecs on older phones.
  const unique = new Map();
  for (const f of video.sort((a, b) => (b.tbr || 0) - (a.tbr || 0))) {
    const key = `${f.height}:${f.fps}`;
    if (!unique.has(key)) unique.set(key, f);
  }
  const audio = formats.filter(f => ['m4a', 'mp4'].includes(f.ext) && f.vcodec === 'none' &&
    /^mp4a\./.test(f.acodec || '') && !String(f.format_id).includes('drc'))
    .sort((a, b) => (b.language_preference || 0) - (a.language_preference || 0) || (b.abr || 0) - (a.abr || 0));
  return { video: [...unique.values()].sort((a, b) => b.height - a.height).slice(0, 8), audio: audio.slice(0, 1) };
}

export function streamMetadata(format, info, ranges, base, audio = false) {
  const u = googleMedia(format.url);
  const itag = Number(u.searchParams.get('itag') || String(format.format_id).split('-')[0]);
  const bitrate = Math.round((audio ? format.abr || format.tbr : format.vbr || format.tbr) * 1000);
  if (!Number.isSafeInteger(itag) || itag <= 0 || !Number.isSafeInteger(bitrate) || bitrate <= 0) {
    throw new ProbeError('invalid_format_metadata');
  }
  return {
    ...ranges, url: proxyUrl(format.url, base), itag,
    format: audio ? 'M4A' : 'MPEG_4', mimeType: audio ? 'audio/mp4' : 'video/mp4',
    codec: audio ? format.acodec : format.vcodec, videoOnly: !audio, bitrate,
    quality: audio ? `${Math.round(bitrate / 1000)} kbps` : `${format.height}p${format.fps > 30 ? Math.round(format.fps) : ''}`,
    width: audio ? 0 : format.width, height: audio ? 0 : format.height,
    fps: audio ? 0 : Math.round(format.fps), durationMs: Math.round(info.duration * 1000),
    contentLength: Number.isSafeInteger(format.filesize) && format.filesize > 0 ? format.filesize : -1,
    audioTrackId: audio ? String(format.language || 'original') : null,
    audioTrackType: audio ? 'original' : null, audioTrackLocale: audio ? format.language || null : null,
    audioTrackName: audio ? format.language || 'Original' : null
  };
}
